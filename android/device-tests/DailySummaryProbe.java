package cn.local.micompanion;

import android.app.Instrumentation;
import android.os.Bundle;
import java.net.Socket;
import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import org.json.JSONObject;
import org.json.JSONArray;

/** Read-only MCP acceptance. Never exports credentials or raw health results. */
public final class DailySummaryProbe extends Instrumentation {
    public void onCreate(Bundle args) { super.onCreate(args); start(); }
    public void onStart() { new Thread(this::verify, "daily-summary-probe").start(); }
    private void verify() {
        Bundle result = new Bundle();
        try {
            getTargetContext().startForegroundService(new android.content.Intent(getTargetContext(), MonitorService.class));
            String token = SettingsStore.load(getTargetContext()).token;
            // Instrumentation restarts the target process; wait boundedly for its service listener.
            long deadline = android.os.SystemClock.elapsedRealtime() + 10000;
            while (true) {
                try (Socket ready = new Socket("127.0.0.1", McpServer.PORT)) { break; }
                catch (java.net.ConnectException e) {
                    if (android.os.SystemClock.elapsedRealtime() >= deadline) throw e;
                    Thread.sleep(100);
                }
            }
            JSONObject list = rpc(token, "tools/list", null).getJSONObject("result");
            String actions = list.getJSONArray("tools").getJSONObject(0).getJSONObject("inputSchema")
                    .getJSONObject("properties").getJSONObject("action").getJSONArray("enum").toString();
            require(actions.contains("daily_summary") && !actions.contains("sleep_summary"), "action list");
            String date = java.time.LocalDate.now().toString();
            JSONObject daily = call(token, new JSONObject().put("action", "daily_summary").put("date", date));
            String text = daily.getJSONArray("content").getJSONObject(0).getString("text");
            require(!daily.optBoolean("isError") && text.contains("每日总结") && text.contains("心率")
                    && text.contains("压力") && text.contains("血氧") && text.contains("测量点"), "daily result");
            JSONObject range = call(token, new JSONObject().put("action", "daily_summary")
                    .put("start_date", java.time.LocalDate.now().minusDays(1).toString()).put("end_date", date));
            require(!range.optBoolean("isError") && range.toString().contains("每日总结"), "range result");
            for (String old : new String[]{"sleep_summary", "heart_rate_summary", "stress_summary", "spo2_summary"})
                require(call(token, new JSONObject().put("action", old)).optBoolean("isError"), "removed summary");
            for (String detail : new String[]{"sleep_detail", "heart_rate_detail", "stress_detail", "spo2_detail"})
                require(!call(token, new JSONObject().put("action", detail).put("date", date)).optBoolean("isError"), "detail result");
            result.putString("daily_summary", "PASS: list, single day, range, removed summaries, retained details");
            finish(-1, result);
        } catch (Throwable e) {
            result.putString("daily_summary", "FAIL: " + e.getClass().getSimpleName() + " " + e.getMessage());
            finish(0, result);
        }
    }
    private JSONObject call(String token, JSONObject args) throws Exception {
        return rpc(token, "tools/call", new JSONObject().put("name", "health").put("arguments", args)).getJSONObject("result");
    }
    private JSONObject rpc(String token, String method, JSONObject params) throws Exception {
        JSONObject req = new JSONObject().put("jsonrpc", "2.0").put("id", 1).put("method", method);
        if (params != null) req.put("params", params);
        byte[] body = req.toString().getBytes(StandardCharsets.UTF_8);
        try (Socket socket = new Socket("127.0.0.1", McpServer.PORT)) {
            socket.setSoTimeout(40000);
            OutputStream out = socket.getOutputStream();
            out.write(("POST /mcp HTTP/1.1\r\nHost: 127.0.0.1\r\nAuthorization: Bearer " + token
                    + "\r\nContent-Type: application/json\r\nContent-Length: " + body.length + "\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
            out.write(body); out.flush();
            ByteArrayOutputStream raw = new ByteArrayOutputStream();
            byte[] buffer = new byte[4096]; int n;
            while ((n = socket.getInputStream().read(buffer)) != -1) raw.write(buffer, 0, n);
            String response = new String(raw.toByteArray(), StandardCharsets.UTF_8);
            require(response.startsWith("HTTP/1.1 200"), "HTTP status");
            return new JSONObject(response.substring(response.indexOf("\r\n\r\n") + 4));
        }
    }
    private void require(boolean valid, String message) { if (!valid) throw new AssertionError(message); }
}

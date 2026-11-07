package cn.local.micompanion;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import org.json.JSONArray;
import org.json.JSONObject;

public final class McpServerTest {
    public static void main(String[] args) throws Exception {
        JSONObject data = new JSONObject().put("status", "ok").put("action", "heart_rate_detail")
                .put("date", "2026-10-31").put("timezone", "Asia/Shanghai").put("metric", "heart_rate")
                .put("unit", "次/分").put("mode", "detail")
                .put("summary", new JSONObject().put("min", 82).put("max", 98).put("average", 90.0).put("count", 2))
                .put("buckets", new org.json.JSONArray().put(new JSONObject().put("start", "2026-10-31T08:00:00+08:00")
                        .put("end", "2026-10-31T08:15:00+08:00").put("min", 82).put("max", 98).put("count", 2)));
        JSONObject exercise = new JSONObject().put("status", "ok").put("action", "exercise_records")
                .put("start_date", "2026-10-31").put("end_date", "2026-10-31").put("timezone", "Asia/Shanghai")
                .put("count", 1).put("records", new org.json.JSONArray().put(new JSONObject()
                        .put("id", "1:482:22").put("sport", "户外跑步")
                        .put("start", "2026-10-31T08:00:00+08:00").put("end", "2026-10-31T08:08:01+08:00")
                        .put("duration_seconds", 481).put("distance_m", 960)
                        .put("pace", new JSONObject().put("avg_seconds_per_km", 501).put("max_seconds_per_km", 285))
                        .put("cadence", new JSONObject().put("avg_spm", 148).put("max_spm", 174))
                        .put("stride", new JSONObject().put("avg_cm", 88).put("max_cm", 127))
                        .put("heart_rate", new JSONObject().put("avg_bpm", 149).put("max_bpm", 161))
                        .put("heart_rate_zones", new JSONObject().put("warmup_seconds", 0)
                                .put("fat_burn_seconds", 54).put("aerobic_seconds", 408)
                                .put("anaerobic_seconds", 0).put("extreme_seconds", 0))));
        McpServer server = new McpServer(17992, "abcdefghijklmnop", request -> {
            String action = request.optString("action");
            return action.startsWith("exercise_") ? exercise : data;
        }, request -> new JSONObject().put("status", "ok").put("text", "天气（今天 2026-11-07）：\n现在：气温 16℃"));
        if (!server.start()) throw new AssertionError("MCP server did not start");
        try {
            check(request("", "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\"}").startsWith("HTTP/1.1 401"), "auth required");
            String init = request("abcdefghijklmnop", "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\"}");
            check(init.startsWith("HTTP/1.1 200"), "initialize status");
            check(init.contains("\"protocolVersion\":\"2024-11-05\""), "protocol version");
            String list = request("abcdefghijklmnop", "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/list\"}");
            JSONObject listed = new JSONObject(list.substring(list.indexOf("\r\n\r\n") + 4));
            JSONArray tools = listed.getJSONObject("result").getJSONArray("tools");
            check(tools.length() == 2, "health and weather are separate tools");
            check("health".equals(tools.getJSONObject(0).getString("name")), "first tool is health");
            check("weather".equals(tools.getJSONObject(1).getString("name")), "second tool is weather");
            check(tools.getJSONObject(1).getJSONObject("inputSchema").getJSONObject("properties").length() == 0,
                    "weather takes no arguments");
            String call = request("abcdefghijklmnop", "{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"tools/call\",\"params\":{\"name\":\"health\",\"arguments\":{\"action\":\"heart_rate_detail\",\"date\":\"2026-10-31\"}}}");
            check(call.contains("08:00-08:15"), "detail text");
            check(call.contains("82-98"), "range text");
            String exerciseCall = request("abcdefghijklmnop", "{\"jsonrpc\":\"2.0\",\"id\":4,\"method\":\"tools/call\",\"params\":{\"name\":\"health\",\"arguments\":{\"action\":\"exercise_records\",\"start_date\":\"2026-10-31\",\"end_date\":\"2026-10-31\"}}}");
            check(exerciseCall.contains("户外跑步") && exerciseCall.contains("ID 1:482:22"), "exercise text");
            check(exerciseCall.contains("8'21"), "exercise pace text");
            String weatherCall = request("abcdefghijklmnop", "{\"jsonrpc\":\"2.0\",\"id\":5,\"method\":\"tools/call\",\"params\":{\"name\":\"weather\",\"arguments\":{}}}");
            check(weatherCall.contains("天气（今天 2026-11-07）"), "weather text");
            check(weatherCall.contains("气温 16℃"), "weather facts");
            String noArgs = request("abcdefghijklmnop", "{\"jsonrpc\":\"2.0\",\"id\":6,\"method\":\"tools/call\",\"params\":{\"name\":\"weather\"}}");
            check(noArgs.contains("天气（今天 2026-11-07）"), "weather works without arguments");
            System.out.println("McpServerTest OK");
        } finally { server.stop(); }
    }

    private static String request(String token, String body) throws Exception {
        try (Socket socket = new Socket("127.0.0.1", 17992)) {
            socket.setSoTimeout(5000);
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            String headers = "POST /mcp HTTP/1.1\r\nHost: 127.0.0.1\r\nContent-Type: application/json\r\n"
                    + "Content-Length: " + bytes.length + "\r\n" + (token.isEmpty() ? "" : "Authorization: Bearer " + token + "\r\n") + "\r\n";
            OutputStream out = socket.getOutputStream();
            out.write(headers.getBytes(StandardCharsets.ISO_8859_1)); out.write(bytes); out.flush();
            ByteArrayOutputStream response = new ByteArrayOutputStream();
            InputStream in = socket.getInputStream(); byte[] b = new byte[4096]; int n;
            while ((n = in.read(b)) != -1) response.write(b, 0, n);
            return new String(response.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
}

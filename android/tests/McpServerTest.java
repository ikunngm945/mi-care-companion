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
        JSONObject screenFields = new JSONObject().put("interval_start", "2026-11-13T00:00:00+08:00")
                .put("interval_end", "2026-11-13T12:00:00+08:00").put("timezone", "Asia/Shanghai")
                .put("screen_on_ms", 5400000).put("unlocked_use_ms", 3600000)
                .put("statistical_basis", "UsageEvents 当地今天区间统计");
        JSONObject screen = new JSONObject().put("status", "ok").put("date", "2026-11-13").put("fields", screenFields);
        McpServer server = new McpServer(17992, "abcdefghijklmnop", request -> {
            String action = request.optString("action");
            if ("screen_usage".equals(action)) return screen;
            if (action.startsWith("sleep_")) return sleepFixture(request);
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
            check(tools.length() == 3, "health, weather and screen usage are separate tools");
            check("screen_usage".equals(tools.getJSONObject(2).getString("name")), "third tool is screen usage");
            check(tools.getJSONObject(2).getJSONObject("inputSchema").getJSONObject("properties").length() == 0,
                    "screen usage queries today without arguments");
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
            String napSummary = sleepCall("sleep_summary", "2026-10-31");
            check(napSummary.contains("25分钟") && napSummary.contains("分期不可用"),
                    "unstaged nap keeps native duration and explains missing stages");
            check(!napSummary.contains("清醒 0分钟") && !napSummary.contains("浅睡 0分钟"),
                    "missing nap stages are not rendered as zero");
            String napDetail = sleepCall("sleep_detail", "2026-10-31");
            check(napDetail.contains("13:27") && napDetail.contains("13:52")
                            && napDetail.contains("25分钟") && napDetail.contains("分期不可用"),
                    "detail retains unstaged nap interval and duration");
            check(!napDetail.contains("没有睡眠段") && !napDetail.contains("睡眠段 1"),
                    "unstaged nap is visible without inventing a stage");
            String clippedSummary = sleepCall("sleep_summary", "2026-10-31", "13:30");
            String clippedDetail = sleepCall("sleep_detail", "2026-10-31", "13:30");
            check(clippedSummary.contains("共不可用") && clippedDetail.contains("共不可用")
                            && clippedDetail.contains("合计：总睡眠 不可用"),
                    "unknown clipped nap duration stays unavailable in sessions and totals");
            check(!clippedSummary.contains("共0分钟") && !clippedDetail.contains("总睡眠 0分钟"),
                    "unknown sleep duration is not zero");
            String mixedSummary = sleepCall("sleep_summary", "2026-11-01");
            String mixedDetail = sleepCall("sleep_detail", "2026-11-01");
            check(mixedSummary.contains("10-31 23:00") && mixedSummary.contains("11-01 06:10"),
                    "cross-night summary shows dates on both interval endpoints");
            check(mixedSummary.contains("总睡眠 7小时25分钟") && mixedDetail.contains("总睡眠 7小时25分钟")
                            && mixedSummary.contains("分期仅含已知数据") && mixedDetail.contains("分期仅含已知数据"),
                    "mixed sleep totals include nap duration and label incomplete stage totals");
            check(mixedDetail.contains("25分钟") && mixedDetail.contains("睡眠段 1")
                            && mixedDetail.contains("分期不可用"),
                    "detailed stages and unstaged nap are both retained");
            check(!napDetail.contains("清醒 0分钟") && !napDetail.contains("浅睡 0分钟"),
                    "entirely missing stages are not rendered as measured zero in detail totals");
            String screenCall = request("abcdefghijklmnop", "{\"jsonrpc\":\"2.0\",\"id\":10,\"method\":\"tools/call\",\"params\":{\"name\":\"screen_usage\"}}");
            check(screenCall.contains("2026-11-13") && screenCall.contains("亮屏时长：1小时30分")
                            && screenCall.contains("解锁使用时长：1小时"), "screen tool returns today's observed screen and unlocked durations");
            screen.put("status", "missing").put("fields", new JSONObject()).put("reason", "未授权使用情况访问；请在系统设置授权");
            String denied = request("abcdefghijklmnop", "{\"jsonrpc\":\"2.0\",\"id\":11,\"method\":\"tools/call\",\"params\":{\"name\":\"screen_usage\",\"arguments\":{}}}");
            check(denied.contains("未授权使用情况访问") && denied.contains("\"isError\":true") && !denied.contains("0秒"),
                    "missing permission is explicit and never becomes zero usage");
            screen.put("status", "ok").put("fields", screenFields.put("screen_on_ms", 0).put("unlocked_use_ms", 0));
            String zero = request("abcdefghijklmnop", "{\"jsonrpc\":\"2.0\",\"id\":12,\"method\":\"tools/call\",\"params\":{\"name\":\"screen_usage\",\"arguments\":{}}}");
            check(zero.contains("亮屏时长：0秒") && zero.contains("\"isError\":false"), "observed zero remains valid data");
            System.out.println("McpServerTest OK");
        } finally { server.stop(); }
    }

    private static JSONObject sleepFixture(JSONObject request) throws Exception {
        JSONObject summary = new JSONObject().put("total_minutes", 25).put("stages_available", false)
                .put("deep_minutes", 0).put("light_minutes", 0).put("rem_minutes", 0).put("awake_minutes", 0);
        JSONObject nap = new JSONObject().put("start", "2026-10-31T13:27:00+08:00")
                .put("end", "2026-10-31T13:52:00+08:00").put("total_minutes", 25)
                .put("summary", summary).put("segments", new JSONArray());
        if (request.has("start_time")) {
            nap.put("total_minutes", JSONObject.NULL);
            summary.put("total_minutes", JSONObject.NULL);
        }
        JSONArray sessions = new JSONArray().put(nap);
        JSONArray segments = new JSONArray();
        JSONObject totals = new JSONObject(summary.toString());
        if ("2026-11-01".equals(request.getString("date"))) {
            JSONObject nightSummary = new JSONObject().put("total_minutes", 420).put("stages_available", true)
                    .put("deep_minutes", 0).put("light_minutes", 420).put("rem_minutes", 0).put("awake_minutes", 10);
            segments.put(new JSONObject().put("start", "2026-10-31T23:00:00+08:00")
                    .put("end", "2026-11-01T06:00:00+08:00").put("stage", "light").put("duration_minutes", 420));
            segments.put(new JSONObject().put("start", "2026-11-01T06:00:00+08:00")
                    .put("end", "2026-11-01T06:10:00+08:00").put("stage", "awake").put("duration_minutes", 10));
            JSONObject night = new JSONObject().put("start", "2026-10-31T23:00:00+08:00")
                    .put("end", "2026-11-01T06:10:00+08:00").put("total_minutes", 420)
                    .put("summary", nightSummary).put("segments", segments);
            nap.put("start", "2026-11-01T13:27:00+08:00").put("end", "2026-11-01T13:52:00+08:00");
            sessions = new JSONArray().put(night).put(nap);
            totals.put("total_minutes", 445).put("light_minutes", 420).put("awake_minutes", 10);
        }
        return new JSONObject().put("status", "ok").put("action", request.getString("action"))
                .put("date", request.getString("date")).put("timezone", "Asia/Shanghai")
                .put("sessions", sessions).put("segments", segments).put("totals", totals);
    }

    private static String sleepCall(String action, String date) throws Exception {
        return sleepCall(action, date, null);
    }

    private static String sleepCall(String action, String date, String startTime) throws Exception {
        JSONObject arguments = new JSONObject().put("action", action).put("date", date);
        if (startTime != null) arguments.put("start_time", startTime);
        JSONObject call = new JSONObject().put("jsonrpc", "2.0").put("id", 9).put("method", "tools/call")
                .put("params", new JSONObject().put("name", "health").put("arguments", arguments));
        String response = request("abcdefghijklmnop", call.toString());
        JSONObject body = new JSONObject(response.substring(response.indexOf("\r\n\r\n") + 4));
        return body.getJSONObject("result").getJSONArray("content").getJSONObject(0).getString("text");
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

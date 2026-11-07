package cn.local.micompanion;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Minimal MCP Streamable HTTP server. Tools only read bounded daily projections;
 * every request must carry the existing local link token as a Bearer token.
 */
public final class McpServer {
    public static final int PORT = 17891;
    private static final int MAX_BODY = 128 * 1024;
    private static final String PROTOCOL = "2024-11-05";
    private static final String VERSION = "0.8.0";

    public interface Gateway { JSONObject query(JSONObject request) throws Exception; }

    private final int port;
    private final String token;
    private final Gateway gateway;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private volatile ServerSocket server;
    private volatile ExecutorService workers;
    private volatile String error = "";

    public McpServer(String token, Gateway gateway) {
        this(PORT, token, gateway);
    }

    McpServer(int port, String token, Gateway gateway) {
        this.port = port;
        this.token = token == null ? "" : token;
        this.gateway = gateway;
    }

    public boolean start() {
        if (running.get()) return true;
        try {
            ServerSocket socket = new ServerSocket();
            socket.setReuseAddress(true);
            socket.bind(new InetSocketAddress(port), 8);
            server = socket;
            running.set(true);
            workers = Executors.newCachedThreadPool(r -> daemon(r, "mi-companion-mcp"));
            Thread accept = daemon(this::acceptLoop, "mi-companion-mcp-accept");
            accept.start();
            error = "";
            return true;
        } catch (Exception e) {
            error = "MCP 端口 " + port + " 无法监听";
            return false;
        }
    }

    public void stop() {
        running.set(false);
        ServerSocket socket = server;
        server = null;
        if (socket != null) try { socket.close(); } catch (IOException ignored) { }
        ExecutorService pool = workers;
        workers = null;
        if (pool != null) pool.shutdownNow();
    }

    public String error() { return error; }
    public boolean isRunning() { return running.get(); }

    private void acceptLoop() {
        while (running.get()) {
            try {
                Socket socket = server.accept();
                ExecutorService pool = workers;
                if (pool != null) pool.execute(() -> handle(socket));
                else close(socket);
            } catch (IOException e) {
                if (running.get()) error = "MCP 连接失败";
            }
        }
    }

    private void handle(Socket socket) {
        try {
            socket.setSoTimeout(30000);
            BufferedInputStream in = new BufferedInputStream(socket.getInputStream());
            LinkProtocol.HttpMessage request = LinkProtocol.readHttp(in, LinkProtocol.MAX_HEADER);
            String start = request.startLine;
            if (start.startsWith("OPTIONS ")) { respond(socket, 204, "", false); return; }
            if (!start.startsWith("POST /mcp")) { respond(socket, 405, "", false); return; }
            String auth = request.headers.get("authorization");
            String supplied = auth != null && auth.regionMatches(true, 0, "Bearer ", 0, 7) ? auth.substring(7).trim() : "";
            if (!LinkProtocol.tokenEquals(token, supplied)) { respond(socket, 401, "", false); return; }
            long length;
            try { length = Long.parseLong(request.headers.getOrDefault("content-length", "0")); }
            catch (Exception e) { respond(socket, 400, "", false); return; }
            if (length <= 0 || length > MAX_BODY) { respond(socket, 413, "", false); return; }
            byte[] body = read(in, (int)length);
            JSONObject message;
            try { message = new JSONObject(LinkProtocol.utf8(body)); }
            catch (Exception e) { respond(socket, 400, "", false); return; }
            JSONObject response = dispatch(message);
            if (response == null) { respond(socket, 202, "", false); return; }
            respond(socket, 200, response.toString(), true);
        } catch (Exception ignored) {
            close(socket);
        }
    }

    private JSONObject dispatch(JSONObject message) {
        Object id = message.opt("id");
        boolean notification = !message.has("id");
        String method = message.optString("method", "");
        JSONObject response = new JSONObject();
        try {
            response.put("jsonrpc", "2.0");
            if (!notification) response.put("id", id);
            if (method.startsWith("notifications/")) return null;
            if ("initialize".equals(method)) {
                response.put("result", new JSONObject()
                        .put("protocolVersion", PROTOCOL)
                        .put("capabilities", new JSONObject().put("tools", new JSONObject()))
                        .put("serverInfo", new JSONObject().put("name", "mi-companion").put("version", VERSION)));
            } else if ("ping".equals(method)) {
                response.put("result", new JSONObject());
            } else if ("tools/list".equals(method)) {
                response.put("result", new JSONObject().put("tools", tools()));
            } else if ("tools/call".equals(method)) {
                response.put("result", call(message.optJSONObject("params")));
            } else {
                response.put("error", new JSONObject().put("code", -32601).put("message", "Method not found"));
            }
        } catch (Exception e) {
            try {
                response.put("jsonrpc", "2.0");
                if (!notification) response.put("id", id);
                response.put("error", new JSONObject().put("code", -32603).put("message", "Internal error"));
            } catch (Exception ignored) { }
        }
        return notification ? null : response;
    }

    private JSONObject call(JSONObject params) throws Exception {
        if (params == null) throw new IllegalArgumentException("缺少 params");
        if (!"health".equals(params.optString("name", ""))) throw new IllegalArgumentException("未知工具");
        JSONObject arguments = params.optJSONObject("arguments");
        if (arguments == null) throw new IllegalArgumentException("缺少 arguments");
        Action action = Action.of(arguments.optString("action", ""));
        if (action == null) throw new IllegalArgumentException("未知 action");
        JSONObject request = new JSONObject(arguments.toString());
        request.put("action", action.action);
        JSONObject result = new JSONObject();
        try {
            JSONObject data = gateway.query(request);
            String text = View.render(data);
            result.put("content", new JSONArray().put(new JSONObject().put("type", "text").put("text", text)));
            result.put("isError", false);
        } catch (Exception e) {
            String reason = e.getMessage() == null ? "查询失败" : e.getMessage();
            result.put("content", new JSONArray().put(new JSONObject().put("type", "text").put("text", "查询失败：" + reason)));
            result.put("isError", true);
        }
        return result;
    }

    private static JSONArray tools() throws Exception {
        return new JSONArray().put(new JSONObject()
                .put("name", "health")
                .put("description", "查询小米运动健康的睡眠、心率、压力、血氧和运动数据；用 action 选择类型，日期按 Asia/Shanghai")
                .put("inputSchema", new JSONObject()
                        .put("type", "object")
                        .put("properties", new JSONObject()
                                .put("action", new JSONObject().put("type", "string")
                                        .put("description", "查询类型")
                                        .put("enum", actionNames()))
                                .put("date", new JSONObject().put("type", "string")
                                        .put("description", "某一天 YYYY-MM-DD；睡眠和三项指标使用，省略为今天"))
                                .put("start_date", new JSONObject().put("type", "string")
                                        .put("description", "运动记录开始日期 YYYY-MM-DD，默认今天"))
                                .put("end_date", new JSONObject().put("type", "string")
                                        .put("description", "运动记录结束日期 YYYY-MM-DD（含），最多31天"))
                                .put("id", new JSONObject().put("type", "string")
                                        .put("description", "运动 ID：开始时间:结束时间:运动类型；exercise_detail 必填")))
                        .put("required", new JSONArray().put("action"))
                        .put("additionalProperties", false)));
    }

    private static JSONArray actionNames() {
        JSONArray names = new JSONArray();
        for (Action action : Action.values()) names.put(action.name);
        return names;
    }

    private enum Action {
        SLEEP_SUMMARY("sleep_summary", "sleep_summary", "某一天睡眠概括：入睡/醒来、总睡眠、各阶段时长、心率/血氧范围"),
        SLEEP_DETAIL("sleep_detail", "sleep_detail", "某一天全部睡眠段和阶段段，一次打包返回；每段含起止、阶段、心率/血氧范围"),
        HEART_SUMMARY("heart_rate_summary", "heart_rate_summary", "某一天心率概括：范围、平均值、采样数"),
        HEART_DETAIL("heart_rate_detail", "heart_rate_detail", "某一天心率明细：每 15 分钟的最小-最大范围"),
        STRESS_SUMMARY("stress_summary", "stress_summary", "某一天压力概括：范围、平均值、采样数"),
        STRESS_DETAIL("stress_detail", "stress_detail", "某一天压力明细：每 15 分钟的最小-最大范围"),
        SPO2_SUMMARY("spo2_summary", "spo2_summary", "某一天血氧概括：范围、平均值、采样数"),
        SPO2_DETAIL("spo2_detail", "spo2_detail", "某一天血氧明细：每 15 分钟的最小-最大范围"),
        EXERCISE_RECORDS("exercise_records", "exercise_records", "查询某个时间段的运动记录摘要，返回运动 ID、配速、步频、步幅、心率区段"),
        EXERCISE_DETAIL("exercise_detail", "exercise_detail", "按运动 ID 返回完整摘要、心率区段以及逐秒心率/步频/步幅/配速/距离/卡路里/步数");
        final String action, name, description;
        Action(String action, String name, String description) { this.action = action; this.name = name; this.description = description; }
        JSONObject tool() throws Exception {
            JSONObject properties = new JSONObject();
            JSONArray required = new JSONArray();
            if (this == EXERCISE_RECORDS) {
                properties.put("start_date", new JSONObject().put("type", "string")
                        .put("description", "开始日期 YYYY-MM-DD，默认今天"));
                properties.put("end_date", new JSONObject().put("type", "string")
                        .put("description", "结束日期 YYYY-MM-DD（含），默认与开始日期相同，最多31天"));
            } else if (this == EXERCISE_DETAIL) {
                properties.put("id", new JSONObject().put("type", "string")
                        .put("description", "运动 ID：开始时间:结束时间:运动类型"));
                required.put("id");
            } else {
                properties.put("date", new JSONObject().put("type", "string")
                        .put("description", "日期 YYYY-MM-DD，按 Asia/Shanghai；省略为今天"));
            }
            JSONObject schema = new JSONObject().put("type", "object").put("properties", properties)
                    .put("additionalProperties", false);
            if (required.length() > 0) schema.put("required", required);
            return new JSONObject().put("name", name).put("description", description).put("inputSchema", schema);
        }
        static Action of(String name) {
            for (Action action : values()) if (action.name.equals(name)) return action;
            return null;
        }
    }

    /** Human-readable text kept compact enough for a chat model. */
    static final class View {
        private View() { }
        static String render(JSONObject data) {
            if (data == null) return "没有数据";
            if (!"ok".equals(data.optString("status"))) return data.optString("date", data.optString("start_date", "")) + "：" + data.optString("reason", "没有数据");
            String action = data.optString("action", "");
            try {
                if (action.startsWith("sleep_")) return sleep(data, action.endsWith("detail"));
                if (action.startsWith("exercise_")) return exercise(data, action.endsWith("detail"));
                return vitals(data);
            } catch (Exception e) { return "数据格式不完整"; }
        }

        private static String exercise(JSONObject data, boolean detail) throws Exception {
            if (detail) {
                StringBuilder out = new StringBuilder("运动详情（").append(data.optString("timezone")).append("）\n");
                record(out, data);
                JSONObject detailData = data.optJSONObject("detail");
                if (detailData == null || !"ok".equals(detailData.optString("status"))) {
                    out.append("逐秒明细：").append(detailData == null ? "没有数据" : detailData.optString("reason", "不可用")).append('\n');
                    return out.toString();
                }
                out.append("逐秒明细：").append(detailData.optInt("sample_count")).append(" 条\n");
                out.append("t,心率,步频,步幅,配速,距离,卡路里,步数\n");
                JSONArray samples = detailData.optJSONArray("samples");
                for (int i = 0; samples != null && i < samples.length(); i++) {
                    JSONObject sample = samples.getJSONObject(i);
                    out.append(sample.optInt("t")).append(',')
                            .append(value(sample, "heart_rate_bpm")).append(',')
                            .append(value(sample, "cadence_spm")).append(',')
                            .append(value(sample, "stride_cm")).append(',')
                            .append(paceValue(sample.opt("pace_seconds_per_km"))).append(',')
                            .append(value(sample, "distance_m")).append(',')
                            .append(value(sample, "calories_kcal")).append(',')
                            .append(value(sample, "steps")).append('\n');
                }
                return out.toString();
            }
            StringBuilder out = new StringBuilder("运动记录 ").append(data.optString("start_date"))
                    .append("～").append(data.optString("end_date")).append("（").append(data.optString("timezone")).append("）\n");
            JSONArray records = data.optJSONArray("records");
            if (records == null || records.length() == 0) return out.append("没有运动记录").toString();
            out.append("共 ").append(records.length()).append(" 条\n");
            for (int i = 0; i < records.length(); i++) {
                out.append('[').append(i + 1).append("] ");
                record(out, records.getJSONObject(i));
            }
            return out.toString();
        }

        private static void record(StringBuilder out, JSONObject record) throws Exception {
            out.append(record.optString("sport", "运动")).append(' ')
                    .append(stamp(record.optString("start"))).append("～").append(stamp(record.optString("end")))
                    .append("（").append(durationSeconds(record.optLong("duration_seconds"))).append("）\n");
            out.append("ID ").append(record.optString("id")).append('\n');
            if (record.has("distance_m")) out.append("距离 ").append(record.optLong("distance_m")).append(" 米；");
            if (record.has("activity_kcal")) out.append("活动 ").append(record.optLong("activity_kcal")).append(" 千卡；");
            if (record.has("total_kcal")) out.append("总消耗 ").append(record.optLong("total_kcal")).append(" 千卡");
            out.append('\n');
            JSONObject pace = record.optJSONObject("pace");
            if (pace != null && pace.has("avg_seconds_per_km")) out.append("配速 平均 ").append(pace(pace.optLong("avg_seconds_per_km")));
            if (pace != null && pace.has("max_seconds_per_km")) out.append("，最快 ").append(pace(pace.optLong("max_seconds_per_km")));
            if (pace != null && pace.length() > 0) out.append('\n');
            JSONObject cadence = record.optJSONObject("cadence");
            if (cadence != null && cadence.length() > 0) {
                out.append("步频");
                if (cadence.has("avg_spm")) out.append(" 平均 ").append(cadence.optInt("avg_spm"));
                if (cadence.has("max_spm")) out.append("，最高 ").append(cadence.optInt("max_spm"));
                out.append('\n');
            }
            JSONObject stride = record.optJSONObject("stride");
            if (stride != null && stride.length() > 0) {
                out.append("步幅");
                if (stride.has("avg_cm")) out.append(" 平均 ").append(stride.optInt("avg_cm")).append(" cm");
                if (stride.has("max_cm")) out.append("，最大 ").append(stride.optInt("max_cm")).append(" cm");
                out.append('\n');
            }
            JSONObject heart = record.optJSONObject("heart_rate");
            if (heart != null && heart.length() > 0) {
                out.append("心率");
                if (heart.has("avg_bpm")) out.append(" 平均 ").append(heart.optInt("avg_bpm"));
                if (heart.has("max_bpm")) out.append("，最高 ").append(heart.optInt("max_bpm"));
                out.append('\n');
            }
            JSONObject zones = record.optJSONObject("heart_rate_zones");
            if (zones != null) out.append("心率区段：热身 ").append(zones.optLong("warmup_seconds"))
                    .append("秒；燃脂 ").append(zones.optLong("fat_burn_seconds"))
                    .append("秒；有氧 ").append(zones.optLong("aerobic_seconds"))
                    .append("秒；无氧 ").append(zones.optLong("anaerobic_seconds"))
                    .append("秒；极限 ").append(zones.optLong("extreme_seconds")).append("秒\n");
        }

        private static String value(JSONObject object, String key) {
            Object value = object.opt(key);
            return value == null || value == JSONObject.NULL ? "-" : String.valueOf(value);
        }

        private static String paceValue(Object value) {
            if (!(value instanceof Number)) return "-";
            return pace(((Number)value).longValue());
        }

        private static String pace(long seconds) {
            if (seconds <= 0) return "-";
            return (seconds / 60) + "'" + String.format(java.util.Locale.US, "%02d", seconds % 60) + "\"/km";
        }

        private static String durationSeconds(long seconds) {
            if (seconds <= 0) return "0秒";
            long h = seconds / 3600, m = (seconds % 3600) / 60, s = seconds % 60;
            StringBuilder out = new StringBuilder();
            if (h > 0) out.append(h).append("小时");
            if (m > 0) out.append(m).append("分");
            if (s > 0 || out.length() == 0) out.append(s).append("秒");
            return out.toString();
        }

        private static String stamp(String iso) {
            try { return OffsetDateTime.parse(iso).format(DateTimeFormatter.ofPattern("MM-dd HH:mm")); }
            catch (Exception e) { return iso; }
        }

        private static String sleep(JSONObject data, boolean detail) throws Exception {
            StringBuilder out = new StringBuilder(data.optString("date")).append(" 睡眠").append(detail ? "详细" : "概括")
                    .append("（").append(data.optString("timezone")).append("）\n");
            JSONArray sessions = data.optJSONArray("sessions");
            if (sessions == null) return out.append("没有睡眠段").toString();
            for (int i = 0; i < sessions.length(); i++) {
                JSONObject session = sessions.getJSONObject(i);
                out.append("睡眠段 ").append(i + 1).append("：").append(clock(session.optString("start")))
                        .append("～").append(clock(session.optString("end"))).append("（共").append(duration(session.optLong("total_minutes"))).append("）\n");
                JSONObject summary = session.optJSONObject("summary");
                if (summary != null) {
                    out.append("清醒 ").append(duration(summary.optLong("awake_minutes")))
                            .append("；快速眼动 ").append(duration(summary.optLong("rem_minutes")))
                            .append("；浅睡 ").append(duration(summary.optLong("light_minutes")))
                            .append("；深睡 ").append(duration(summary.optLong("deep_minutes")));
                    if (summary.has("heart_rate_min")) out.append("；心率 ").append(summary.optInt("heart_rate_min")).append("-").append(summary.optInt("heart_rate_max"));
                    if (summary.has("spo2_min")) out.append("；血氧 ").append(summary.optInt("spo2_min")).append("-").append(summary.optInt("spo2_max")).append("%");
                    out.append('\n');
                }
                if (detail) {
                    JSONArray segments = session.optJSONArray("segments");
                    for (int j = 0; segments != null && j < segments.length(); j++) {
                        JSONObject segment = segments.getJSONObject(j);
                        out.append(clock(segment.optString("start"))).append('-').append(clock(segment.optString("end")))
                                .append("  ").append(stage(segment.optString("stage")));
                        JSONObject hr = segment.optJSONObject("heart_rate");
                        if (hr != null) out.append("  心率 ").append(hr.optInt("min")).append('-').append(hr.optInt("max")).append(" 次/分");
                        JSONObject sp = segment.optJSONObject("spo2");
                        if (sp != null) out.append("  血氧 ").append(sp.optInt("min")).append('-').append(sp.optInt("max")).append('%');
                        out.append('\n');
                    }
                }
            }
            JSONObject totals = data.optJSONObject("totals");
            if (sessions.length() > 1 && totals != null) {
                out.append("合计：总睡眠 ").append(duration(totals.optLong("total_minutes")))
                        .append("；清醒 ").append(duration(totals.optLong("awake_minutes")))
                        .append("；快速眼动 ").append(duration(totals.optLong("rem_minutes")))
                        .append("；浅睡 ").append(duration(totals.optLong("light_minutes")))
                        .append("；深睡 ").append(duration(totals.optLong("deep_minutes"))).append('\n');
            }
            return out.toString();
        }

        private static String vitals(JSONObject data) throws Exception {
            String metric = data.optString("metric", "");
            String label = "heart_rate".equals(metric) ? "心率" : "stress".equals(metric) ? "压力" : "血氧";
            String unit = data.optString("unit", "");
            boolean detail = "detail".equals(data.optString("mode"));
            StringBuilder out = new StringBuilder(data.optString("date")).append(' ').append(label)
                    .append(detail ? "详细（15分钟）" : "概括").append('\n');
            JSONObject summary = data.optJSONObject("summary");
            if (summary != null) {
                out.append("范围 ").append(summary.optInt("min")).append('-').append(summary.optInt("max"));
                if (!unit.isEmpty()) out.append(' ').append(unit);
                out.append("；平均 ").append(summary.optDouble("average")).append(unit.isEmpty() ? "" : " " + unit)
                        .append("；采样 ").append(summary.optInt("count")).append(" 个\n");
            }
            if (detail) {
                JSONArray buckets = data.optJSONArray("buckets");
                for (int i = 0; buckets != null && i < buckets.length(); i++) {
                    JSONObject bucket = buckets.getJSONObject(i);
                    out.append(clock(bucket.optString("start"))).append('-').append(clock(bucket.optString("end")))
                            .append("  ").append(bucket.optInt("min")).append('-').append(bucket.optInt("max"));
                    if (!unit.isEmpty()) out.append(' ').append(unit);
                    out.append('\n');
                }
            }
            return out.toString();
        }

        private static String clock(String iso) {
            try { return OffsetDateTime.parse(iso).format(DateTimeFormatter.ofPattern("HH:mm")); }
            catch (Exception e) { return iso; }
        }

        private static String duration(long minutes) {
            if (minutes <= 0) return "0分钟";
            long h = minutes / 60, m = minutes % 60;
            return h == 0 ? m + "分钟" : (m == 0 ? h + "小时" : h + "小时" + m + "分钟");
        }

        private static String stage(String value) {
            if ("deep".equals(value)) return "深睡";
            if ("light".equals(value)) return "浅睡";
            if ("rem".equals(value)) return "快速眼动";
            if ("awake".equals(value)) return "清醒";
            return "未知";
        }
    }

    private static byte[] read(InputStream in, int length) throws IOException {
        byte[] out = new byte[length];
        int offset = 0;
        while (offset < length) {
            int n = in.read(out, offset, length - offset);
            if (n < 0) throw new IOException("请求体不完整");
            offset += n;
        }
        return out;
    }

    private static void respond(Socket socket, int status, String body, boolean json) {
        try {
            byte[] bytes = body == null ? new byte[0] : body.getBytes(StandardCharsets.UTF_8);
            String reason = status == 200 ? "OK" : status == 202 ? "Accepted" : status == 204 ? "No Content"
                    : status == 400 ? "Bad Request" : status == 401 ? "Unauthorized" : status == 405 ? "Method Not Allowed" : status == 413 ? "Payload Too Large" : "Error";
            String headers = "HTTP/1.1 " + status + " " + reason + "\r\nConnection: close\r\n";
            if (json) headers += "Content-Type: application/json; charset=utf-8\r\n";
            headers += "Content-Length: " + bytes.length + "\r\n\r\n";
            OutputStream out = socket.getOutputStream();
            out.write(headers.getBytes(StandardCharsets.ISO_8859_1));
            out.write(bytes);
            out.flush();
        } catch (IOException ignored) { }
        finally { close(socket); }
    }

    private static void close(Socket socket) {
        try { socket.close(); } catch (IOException ignored) { }
    }

    private static Thread daemon(Runnable run, String name) {
        Thread thread = new Thread(run, name);
        thread.setDaemon(true);
        return thread;
    }
}

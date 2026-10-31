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

    public interface Gateway { JSONObject query(String action, String date) throws Exception; }

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
        String name = params.optString("name", "");
        Action action = Action.of(name);
        if (action == null) throw new IllegalArgumentException("未知工具");
        JSONObject arguments = params.optJSONObject("arguments");
        String date = arguments == null ? "" : arguments.optString("date", "");
        JSONObject result = new JSONObject();
        try {
            JSONObject data = gateway.query(action.action, date);
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
        JSONArray out = new JSONArray();
        for (Action action : Action.values()) out.put(action.tool());
        return out;
    }

    private enum Action {
        SLEEP_SUMMARY("sleep_summary", "sleep_summary", "某一天睡眠概括：入睡/醒来、总睡眠、各阶段时长、心率/血氧范围"),
        SLEEP_DETAIL("sleep_detail", "sleep_detail", "某一天全部睡眠段和阶段段，一次打包返回；每段含起止、阶段、心率/血氧范围"),
        HEART_SUMMARY("heart_rate_summary", "heart_rate_summary", "某一天心率概括：范围、平均值、采样数"),
        HEART_DETAIL("heart_rate_detail", "heart_rate_detail", "某一天心率明细：每 15 分钟的最小-最大范围"),
        STRESS_SUMMARY("stress_summary", "stress_summary", "某一天压力概括：范围、平均值、采样数"),
        STRESS_DETAIL("stress_detail", "stress_detail", "某一天压力明细：每 15 分钟的最小-最大范围"),
        SPO2_SUMMARY("spo2_summary", "spo2_summary", "某一天血氧概括：范围、平均值、采样数"),
        SPO2_DETAIL("spo2_detail", "spo2_detail", "某一天血氧明细：每 15 分钟的最小-最大范围");
        final String action, name, description;
        Action(String action, String name, String description) { this.action = action; this.name = name; this.description = description; }
        JSONObject tool() throws Exception {
            JSONObject schema = new JSONObject().put("type", "object")
                    .put("properties", new JSONObject().put("date", new JSONObject().put("type", "string")
                            .put("description", "日期 YYYY-MM-DD，按 Asia/Shanghai；省略为今天")))
                    .put("additionalProperties", false);
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
            if (!"ok".equals(data.optString("status"))) return data.optString("date", "") + "：" + data.optString("reason", "没有数据");
            String action = data.optString("action", "");
            try {
                if (action.startsWith("sleep_")) return sleep(data, action.endsWith("detail"));
                return vitals(data);
            } catch (Exception e) { return "数据格式不完整"; }
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

package cn.local.micompanion;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import org.json.JSONObject;

public final class McpServerTest {
    public static void main(String[] args) throws Exception {
        JSONObject data = new JSONObject().put("status", "ok").put("action", "heart_rate_detail")
                .put("date", "2026-10-31").put("timezone", "Asia/Shanghai").put("metric", "heart_rate")
                .put("unit", "次/分").put("mode", "detail")
                .put("summary", new JSONObject().put("min", 82).put("max", 98).put("average", 90.0).put("count", 2))
                .put("buckets", new org.json.JSONArray().put(new JSONObject().put("start", "2026-10-31T08:00:00+08:00")
                        .put("end", "2026-10-31T08:15:00+08:00").put("min", 82).put("max", 98).put("count", 2)));
        McpServer server = new McpServer(17892, "abcdefghijklmnop", (action, date) -> data);
        if (!server.start()) throw new AssertionError("MCP server did not start");
        try {
            check(request("", "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\"}").startsWith("HTTP/1.1 401"), "auth required");
            String init = request("abcdefghijklmnop", "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\"}");
            check(init.startsWith("HTTP/1.1 200"), "initialize status");
            check(init.contains("\"protocolVersion\":\"2024-11-05\""), "protocol version");
            String list = request("abcdefghijklmnop", "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/list\"}");
            check(list.contains("sleep_detail") && list.contains("spo2_detail"), "tools list");
            String call = request("abcdefghijklmnop", "{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"tools/call\",\"params\":{\"name\":\"heart_rate_detail\",\"arguments\":{\"date\":\"2026-10-31\"}}}");
            check(call.contains("08:00-08:15"), "detail text");
            check(call.contains("82-98"), "range text");
            System.out.println("McpServerTest OK");
        } finally { server.stop(); }
    }

    private static String request(String token, String body) throws Exception {
        try (Socket socket = new Socket("127.0.0.1", 17892)) {
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

package cn.local.micompanion;

import org.json.JSONObject;
import java.io.BufferedInputStream;
import java.io.Closeable;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.concurrent.atomic.AtomicReference;

public final class LinkServerTest {
    private static int checks;
    private static final String TOKEN = "aB3dE6gH9jK2mN5p";

    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
        checks++;
    }

    private static int freePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) {
            return socket.getLocalPort();
        }
    }

    private static String handshake(String token, String kind) {
        return "GET / HTTP/1.1\r\nHost: 127.0.0.1\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n"
                + "Sec-WebSocket-Key: " + LinkProtocol.newKey(new SecureRandom()) + "\r\n"
                + "Sec-WebSocket-Version: 13\r\nAuthorization: Bearer " + token + "\r\n"
                + "X-Mi-Companion-Kind: " + kind + "\r\n\r\n";
    }

    private static final class Client implements Closeable {
        final Socket socket;
        final InputStream in;
        final OutputStream out;
        Client(int port) throws IOException {
            socket = new Socket("127.0.0.1", port);
            socket.setSoTimeout(3000);
            in = new BufferedInputStream(socket.getInputStream());
            out = socket.getOutputStream();
        }
        void send(String text) throws IOException {
            out.write(LinkProtocol.frame(LinkProtocol.OP_TEXT, text.getBytes(StandardCharsets.UTF_8), true, new SecureRandom()));
            out.flush();
        }
        void send(JSONObject json) throws IOException { send(json.toString()); }
        LinkProtocol.Frame frame() throws IOException { return LinkProtocol.readFrame(in, false); }
        JSONObject json() throws Exception {
            while (true) {
                LinkProtocol.Frame frame = frame();
                if (frame.opcode == LinkProtocol.OP_PING) continue;
                if (frame.opcode == LinkProtocol.OP_CLOSE) throw new EOFException("closed");
                if (frame.opcode == LinkProtocol.OP_TEXT) return new JSONObject(LinkProtocol.utf8(frame.payload));
            }
        }
        @Override public void close() throws IOException { socket.close(); }
    }

    private static Client authorized(int port, String kind) throws Exception {
        Client client = new Client(port);
        client.out.write(handshake(TOKEN, kind).getBytes(StandardCharsets.ISO_8859_1));
        client.out.flush();
        LinkProtocol.HttpMessage response = LinkProtocol.readHttp(client.in, LinkProtocol.MAX_HEADER);
        check(response.startLine.contains(" 101"), "授权连接应返回 101");
        client.send(new JSONObject().put("type", "hello").put("kind", kind).put("protocol", 1));
        JSONObject ready = client.json();
        check("ready".equals(ready.getString("type")), "hello 后应收到 ready");
        return client;
    }

    private static JSONObject snapshot(String pkg, String status) throws Exception {
        return new JSONObject("{\"status\":\"" + status + "\",\"source_package\":\"" + pkg
                + "\",\"collected_at\":\"2026-10-24T00:00:00Z\",\"source_updated_at\":null,\"reason\":null,\"fields\":{}}");
    }

    private static void authRejects(int port, String token, String kind, String status) throws Exception {
        try (Client client = new Client(port)) {
            client.out.write(handshake(token, kind).getBytes(StandardCharsets.ISO_8859_1));
            client.out.flush();
            LinkProtocol.HttpMessage response = LinkProtocol.readHttp(client.in, LinkProtocol.MAX_HEADER);
            check(response.startLine.contains(status), "握手应返回 " + status + "，实际 " + response.startLine);
        }
    }

    public static void main(String[] args) throws Exception {
        AtomicReference<String> kindRef = new AtomicReference<>();
        AtomicReference<JSONObject> snapshotRef = new AtomicReference<>();
        int port = freePort();
        LinkServer server = new LinkServer(port, TOKEN, (kind, snap) -> {
            if (snap == null || !"weather".equals(kind) || !"com.miui.weather2".equals(snap.optString("source_package")))
                throw new IllegalArgumentException("invalid snapshot");
            kindRef.set(kind);
            snapshotRef.set(snap);
        });
        check(server.start(), "服务端应能监听回环端口");

        authRejects(port, "Zz9Yy8Xx7Ww6Vv5U", "weather", " 401");
        authRejects(port, "short", "weather", " 401");
        authRejects(port, TOKEN, "unknown", " 400");

        try (Client weather = authorized(port, "weather")) {
            JSONObject snapshot = snapshot("com.miui.weather2", "ok");
            weather.send(new JSONObject().put("type", "snapshot").put("snapshot", snapshot));
            JSONObject ack = weather.json();
            check("ack".equals(ack.getString("type")), "合法快照应收到 ack");
            check("weather".equals(kindRef.get()), "服务端按 kind 落盘");
            check(snapshot.toString().equals(snapshotRef.get().toString()), "快照内容一致");
        }

        // 三次非法快照后服务端必须断开，且不得调用 sink。
        kindRef.set(null);
        try (Client bad = authorized(port, "health")) {
            for (int i = 0; i < 3; i++) {
                bad.send(new JSONObject().put("type", "snapshot").put("snapshot", snapshot("com.miui.weather2", "ok")));
                JSONObject error = bad.json();
                check("error".equals(error.getString("type")), "错误来源应被拒绝");
            }
            boolean closed = false;
            try { bad.frame(); } catch (IOException e) { closed = true; }
            check(closed || kindRef.get() == null, "连续非法快照后应关闭");
        }
        check(kindRef.get() == null, "非法快照不得落盘");

        // 新连接替换同 kind 的旧连接。
        Client first = authorized(port, "weather");
        Client second = authorized(port, "weather");
        boolean replaced = false;
        for (int i = 0; i < 5 && !replaced; i++) {
            try {
                LinkProtocol.Frame frame = first.frame();
                if (frame.opcode == LinkProtocol.OP_CLOSE) replaced = true;
            } catch (IOException e) { replaced = true; }
        }
        check(replaced, "同 kind 新连接应关闭旧连接");
        second.send(new JSONObject().put("type", "snapshot").put("snapshot", snapshot("com.miui.weather2", "ok")));
        check("ack".equals(second.json().getString("type")), "替换后新连接仍可用");
        first.close();
        second.close();
        server.stop();

        // 心跳：100ms ping、400ms 无 pong 断开。
        int heartbeatPort = freePort();
        LinkServer heartbeat = new LinkServer(heartbeatPort, TOKEN, (kind, snap) -> { }, 100, 400);
        check(heartbeat.start(), "心跳测试服务端应启动");
        try (Client client = authorized(heartbeatPort, "weather")) {
            client.socket.setSoTimeout(2000);
            long deadline = System.currentTimeMillis() + 2500;
            boolean sawPing = false, closed = false;
            while (System.currentTimeMillis() < deadline && !closed) {
                try {
                    LinkProtocol.Frame frame = client.frame();
                    if (frame.opcode == LinkProtocol.OP_PING) sawPing = true;
                    if (frame.opcode == LinkProtocol.OP_CLOSE) closed = true;
                } catch (IOException e) { closed = true; }
            }
            check(sawPing, "客户端应收到 ping");
            check(closed, "未 pong 时应被服务端断开");
        }
        heartbeat.stop();

        System.out.println("LinkServer: " + checks + " checks passed");
    }
}

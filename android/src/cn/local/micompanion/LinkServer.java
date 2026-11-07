package cn.local.micompanion;

import org.json.JSONException;
import org.json.JSONObject;
import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Loopback-only WebSocket server owned by the main app. Modules authenticate with
 * a 16-character token, push snapshots, and answer a ping every five seconds.
 */
public final class LinkServer {
    private static final String TAG = "MiCompanionLink";
    public interface Sink { void store(String kind, JSONObject snapshot) throws Exception; }
    public interface DatabaseSink { void accept(String kind, JSONObject message) throws Exception; }

    public static final class Status {
        public volatile boolean connected;
        public volatile long connectedAt;
        public volatile long lastPongAt;
        public volatile long lastStoredAt;
        public volatile long rttMs = -1;
        public volatile int authFailures;
        public volatile String lastError = "";
        public volatile boolean everConnected;
        public volatile long disconnectedAt;
    }

    private final int port;
    private final String token;
    private final Sink sink;
    private volatile DatabaseSink databaseSink;
    private final long pingIntervalMs;
    private final long timeoutMs;
    private final SecureRandom random = new SecureRandom();
    private final Object lock = new Object();
    private final Map<String, Connection> connections = new HashMap<>();
    private final Map<String, Status> statuses = new HashMap<>();
    private final Map<String, PendingQuery> queries = new HashMap<>();
    private final AtomicBoolean running = new AtomicBoolean(false);
    private volatile ServerSocket serverSocket;
    private volatile String fatalError = "";
    private ScheduledExecutorService timer;
    private ExecutorService workers;

    public LinkServer(int port, String token, Sink sink) {
        this(port, token, sink, 5000, 10000);
    }

    LinkServer(int port, String token, Sink sink, long pingIntervalMs, long timeoutMs) {
        this.port = port;
        this.token = token;
        this.sink = sink;
        this.pingIntervalMs = pingIntervalMs;
        this.timeoutMs = timeoutMs;
        statuses.put("weather", new Status());
        statuses.put("health", new Status());
    }

    public int port() { return port; }
    public String error() { return fatalError; }
    public boolean isRunning() { return running.get(); }
    public void setDatabaseSink(DatabaseSink sink) { databaseSink = sink; }
    public Status status(String kind) {
        Status status = statuses.get(kind);
        return status == null ? new Status() : status;
    }

    /** Sends a request to one module and waits for its matching query_result. */
    public JSONObject query(String kind, JSONObject request, long timeoutMs) throws Exception {
        Connection connection;
        synchronized (lock) {
            connection = connections.get(kind);
            if (connection == null) throw new IOException("模块未连接");
        }
        String id = UUID.randomUUID().toString();
        PendingQuery pending = new PendingQuery();
        synchronized (lock) { queries.put(id, pending); }
        try {
            connection.send(new JSONObject().put("type", "query").put("id", id).put("query", request));
            synchronized (pending) {
                long deadline = System.currentTimeMillis() + Math.max(1, timeoutMs);
                while (!pending.done) {
                    long left = deadline - System.currentTimeMillis();
                    if (left <= 0) break;
                    pending.wait(left);
                }
            }
            if (!pending.done) throw new IOException("模块查询超时");
            if (pending.error != null) throw new IOException(pending.error);
            return pending.data == null ? new JSONObject() : pending.data;
        } finally {
            synchronized (lock) { queries.remove(id); }
        }
    }

    private void completeQuery(JSONObject message) {
        String id = message.optString("id", "");
        PendingQuery pending;
        synchronized (lock) { pending = queries.get(id); }
        if (pending == null) return;
        JSONObject data = message.optJSONObject("data");
        pending.complete(message.optBoolean("ok", false), data,
                message.optString("reason", "模块查询失败"));
    }

    private void failQueries(String kind, String reason) {
        ArrayList<PendingQuery> failed = new ArrayList<>();
        synchronized (lock) {
            for (Map.Entry<String, PendingQuery> entry : queries.entrySet()) {
                if (kind == null || kind.equals(entry.getValue().kind)) failed.add(entry.getValue());
            }
        }
        for (PendingQuery pending : failed) pending.complete(false, null, reason);
    }

    private static final class PendingQuery {
        final String kind = "health";
        boolean done;
        JSONObject data;
        String error;
        synchronized void complete(boolean ok, JSONObject value, String message) {
            if (done) return;
            done = true;
            data = ok ? value : null;
            error = ok ? null : message;
            notifyAll();
        }
    }

    public boolean start() {
        synchronized (lock) {
            if (running.get()) return true;
            try {
                ServerSocket socket = new ServerSocket();
                socket.setReuseAddress(true);
                socket.bind(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), port), 4);
                serverSocket = socket;
            } catch (Exception e) {
                fatalError = "端口 " + port + " 无法监听（可能被占用）";
                log("bind failed: " + e.getClass().getSimpleName());
                return false;
            }
            fatalError = "";
            running.set(true);
            workers = Executors.newCachedThreadPool(r -> daemon(r, "mi-companion-link"));
            timer = Executors.newScheduledThreadPool(2, r -> daemon(r, "mi-companion-heartbeat"));
            Thread accept = daemon(this::acceptLoop, "mi-companion-accept");
            accept.start();
            long startedAt = now();
            for (Status status : statuses.values()) if (!status.connected) status.disconnectedAt = startedAt;
            timer.scheduleAtFixedRate(this::heartbeat, pingIntervalMs, pingIntervalMs, TimeUnit.MILLISECONDS);
            timer.scheduleAtFixedRate(this::watchdog, Math.max(50, timeoutMs / 10), Math.max(50, timeoutMs / 10), TimeUnit.MILLISECONDS);
            return true;
        }
    }

    public void stop() {
        List<Connection> open;
        synchronized (lock) {
            running.set(false);
            long stoppedAt = now();
            closeQuietly(serverSocket);
            serverSocket = null;
            open = new ArrayList<>(connections.values());
            connections.clear();
            for (Status status : statuses.values()) {
                status.connected = false;
                status.rttMs = -1;
                status.disconnectedAt = stoppedAt;
            }
            if (timer != null) timer.shutdownNow();
            if (workers != null) workers.shutdownNow();
            timer = null;
            workers = null;
        }
        for (Connection connection : open) connection.close(1001, "server stop");
    }

    private static Thread daemon(Runnable runnable, String name) {
        Thread thread = new Thread(runnable, name);
        thread.setDaemon(true);
        return thread;
    }

    private void acceptLoop() {
        while (running.get()) {
            Socket socket;
            try {
                socket = serverSocket.accept();
            } catch (IOException e) {
                if (running.get()) log("accept failed: " + e.getClass().getSimpleName());
                break;
            }
            synchronized (lock) {
                if (!running.get()) { closeQuietly(socket); break; }
                if (connections.size() >= LinkProtocol.MAX_CONNECTIONS) {
                    reject(socket, "503 Service Unavailable");
                    continue;
                }
            }
            try {
                workers.execute(() -> handle(socket));
            } catch (java.util.concurrent.RejectedExecutionException e) {
                closeQuietly(socket);
                break;
            }
        }
    }

    private void handle(Socket socket) {
        Connection connection = null;
        try {
            socket.setSoTimeout(5000);
            BufferedInputStream in = new BufferedInputStream(socket.getInputStream());
            OutputStream out = socket.getOutputStream();
            LinkProtocol.HttpMessage request = LinkProtocol.readHttp(in, LinkProtocol.MAX_HEADER);
            if (!request.startLine.startsWith("GET / HTTP/1.1")) { reject(socket, "400 Bad Request"); return; }
            String host = request.headers.get("host");
            if (host == null || !(host.equals("127.0.0.1") || host.startsWith("127.0.0.1:")
                    || host.equals("localhost") || host.startsWith("localhost:"))) {
                reject(socket, "400 Bad Request"); return;
            }
            String key = request.headers.get("sec-websocket-key");
            if (key == null || key.isEmpty()
                    || !"13".equals(request.headers.get("sec-websocket-version"))
                    || !LinkProtocol.headerContains(request.headers, "upgrade", "websocket")
                    || !LinkProtocol.headerContains(request.headers, "connection", "upgrade")) {
                reject(socket, "400 Bad Request"); return;
            }
            String kind = request.headers.get("x-mi-companion-kind");
            Status status = kind == null ? null : statuses.get(kind);
            String authorization = request.headers.get("authorization");
            String supplied = authorization != null && authorization.regionMatches(true, 0, "Bearer ", 0, 7)
                    ? authorization.substring(7).trim() : "";
            if (!LinkProtocol.tokenEquals(token, supplied)) {
                if (status != null) {
                    status.authFailures++;
                    status.lastError = "Token 不匹配";
                }
                log("handshake rejected: token");
                reject(socket, "401 Unauthorized"); return;
            }
            if (status == null) { reject(socket, "400 Bad Request"); return; }

            String response = "HTTP/1.1 101 Switching Protocols\r\n"
                    + "Upgrade: websocket\r\nConnection: Upgrade\r\n"
                    + "Sec-WebSocket-Accept: " + LinkProtocol.accept(key) + "\r\n\r\n";
            synchronized (out) { out.write(response.getBytes(StandardCharsets.ISO_8859_1)); out.flush(); }
            connection = new Connection(socket, in, out, kind, status);
            replace(connection);
            connection.run();
        } catch (Throwable e) {
            if (running.get()) log("connection ended: " + e.getClass().getSimpleName());
        } finally {
            if (connection != null) connection.cleanup();
            closeQuietly(socket);
        }
    }

    private void replace(Connection fresh) {
        Connection old;
        synchronized (lock) {
            old = connections.put(fresh.kind, fresh);
            fresh.status.connected = true;
            fresh.status.everConnected = true;
            fresh.status.disconnectedAt = 0;
            fresh.status.connectedAt = now();
            fresh.status.lastPongAt = fresh.status.connectedAt;
            fresh.status.rttMs = -1;
            fresh.status.lastError = "";
        }
        if (old != null) old.close(1000, "replaced");
    }

    private void heartbeat() {
        for (Connection connection : snapshotConnections()) connection.ping();
    }

    private void watchdog() {
        long now = now();
        for (Connection connection : snapshotConnections())
            if (now - connection.status.lastPongAt > timeoutMs) connection.close(1001, "heartbeat timeout");
    }

    private List<Connection> snapshotConnections() {
        synchronized (lock) { return new ArrayList<>(connections.values()); }
    }

    private static void reject(Socket socket, String status) {
        try {
            OutputStream out = socket.getOutputStream();
            String response = "HTTP/1.1 " + status + "\r\nConnection: close\r\nContent-Length: 0\r\n\r\n";
            synchronized (out) { out.write(response.getBytes(StandardCharsets.ISO_8859_1)); out.flush(); }
        } catch (IOException ignored) { }
        closeQuietly(socket);
    }

    private static void closeQuietly(java.io.Closeable closeable) {
        if (closeable != null) try { closeable.close(); } catch (IOException ignored) { }
    }

    private final class Connection {
        final Socket socket;
        final BufferedInputStream in;
        final OutputStream out;
        final String kind;
        final Status status;
        final Object writeLock = new Object();
        volatile boolean closed;

        Connection(Socket socket, BufferedInputStream in, OutputStream out, String kind, Status status) {
            this.socket = socket;
            this.in = in;
            this.out = out;
            this.kind = kind;
            this.status = status;
        }

        void run() throws Exception {
            socket.setSoTimeout(5000);
            LinkProtocol.Frame first = LinkProtocol.readFrame(in, true);
            if (first.opcode != LinkProtocol.OP_TEXT) { close(1002, "hello required"); return; }
            JSONObject hello = new JSONObject(LinkProtocol.utf8(first.payload));
            if (!"hello".equals(hello.optString("type")) || !kind.equals(hello.optString("kind"))
                    || hello.optInt("protocol", -1) != LinkProtocol.PROTOCOL_VERSION) {
                close(1002, "bad hello");
                return;
            }
            send(new JSONObject().put("type", "ready").put("protocol", LinkProtocol.PROTOCOL_VERSION));
            socket.setSoTimeout(15000);
            int rejected = 0;
            while (!closed && running.get()) {
                LinkProtocol.Frame frame = LinkProtocol.readFrame(in, true);
                if (frame.opcode == LinkProtocol.OP_PONG) {
                    long now = now();
                    long sent = LinkProtocol.pingValue(frame.payload);
                    status.lastPongAt = now;
                    if (sent > 0 && now >= sent && now - sent < 60000) status.rttMs = now - sent;
                    continue;
                }
                if (frame.opcode == LinkProtocol.OP_PING) {
                    write(LinkProtocol.frame(LinkProtocol.OP_PONG, frame.payload, false, random));
                    continue;
                }
                if (frame.opcode == LinkProtocol.OP_CLOSE) break;
                if (frame.opcode != LinkProtocol.OP_TEXT) { close(1003, "text only"); break; }
                JSONObject message;
                try {
                    message = new JSONObject(LinkProtocol.utf8(frame.payload));
                } catch (Exception e) {
                    close(1007, "invalid json");
                    break;
                }
                String type = message.optString("type");
                if ("query_result".equals(type)) {
                    completeQuery(message);
                    continue;
                }
                if (type.startsWith("database_")) {
                    DatabaseSink receiver = databaseSink;
                    if (receiver == null || !"health".equals(kind)) {
                        sendError("database", "数据库接收器未就绪");
                        continue;
                    }
                    try {
                        receiver.accept(kind, message);
                        send(new JSONObject().put("type", "ack").put("storedAtMs", System.currentTimeMillis()));
                    } catch (Exception e) {
                        status.lastError = "数据库同步失败";
                        sendError("database", e.getMessage() == null ? "数据库同步失败" : e.getMessage());
                    }
                    continue;
                }
                if (!"snapshot".equals(type)) {
                    sendError("protocol", "未知消息");
                    continue;
                }
                try {
                    sink.store(kind, message.optJSONObject("snapshot"));
                    status.lastStoredAt = System.currentTimeMillis();
                    rejected = 0;
                    send(new JSONObject().put("type", "ack").put("storedAtMs", status.lastStoredAt));
                } catch (Exception e) {
                    rejected++;
                    status.lastError = "快照校验失败";
                    sendError("snapshot", "快照校验失败");
                    if (rejected >= 3) { close(1008, "snapshot rejected"); break; }
                }
            }
        }

        void ping() {
            if (closed) return;
            try { write(LinkProtocol.pingFrame(now())); }
            catch (Exception e) { close(1001, "ping failed"); }
        }

        void send(JSONObject message) throws IOException {
            write(LinkProtocol.frame(LinkProtocol.OP_TEXT,
                    message.toString().getBytes(StandardCharsets.UTF_8), false, random));
        }

        void sendError(String code, String reason) {
            try { send(new JSONObject().put("type", "error").put("code", code).put("reason", reason)); }
            catch (Exception ignored) { }
        }

        void write(byte[] frame) throws IOException {
            synchronized (writeLock) {
                if (closed) throw new IOException("closed");
                out.write(frame);
                out.flush();
            }
        }

        synchronized void close(int code, String reason) {
            if (closed) return;
            try { write(LinkProtocol.closeFrame(code, reason)); } catch (Exception ignored) { }
            closed = true;
            closeQuietly(socket);
        }

        void cleanup() {
            synchronized (lock) {
                if (connections.get(kind) == this) {
                    connections.remove(kind);
                    status.connected = false;
                    status.rttMs = -1;
                    status.disconnectedAt = now();
                }
            }
            closeQuietly(socket);
            failQueries(kind, "模块连接已断开");
        }
    }
    private static long now() { return System.nanoTime() / 1_000_000L; }

    private static void log(String message) {
        try { android.util.Log.w(TAG, message); }
        catch (Throwable ignored) { System.err.println(TAG + ": " + message); }
    }
}

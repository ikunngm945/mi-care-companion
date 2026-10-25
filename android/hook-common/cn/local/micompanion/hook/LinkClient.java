package cn.local.micompanion.hook;

import android.content.Context;
import android.os.SystemClock;
import android.util.Log;
import cn.local.micompanion.LinkProtocol;
import org.json.JSONObject;
import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/** Injected WebSocket client: connects to the main app, sends the latest snapshot, answers pings. */
public final class LinkClient {
    private static final String TAG = "MiCompanionHook";
    private static final String VERSION = "0.7.0";
    private static volatile LinkClient shared;

    public static LinkClient shared(Context context, String kind) {
        LinkClient local = shared;
        if (local == null) {
            synchronized (LinkClient.class) {
                local = shared;
                if (local == null) {
                    local = new LinkClient(context.getApplicationContext(), kind);
                    shared = local;
                }
            }
        }
        return local;
    }

    public static final class Status {
        public final String state;
        public final boolean connected;
        public final long lastHeartbeatAt;
        public final int authFailures;
        public final String error;
        public final long delivered;
        Status(String state, boolean connected, long lastHeartbeatAt, int authFailures, String error, long delivered) {
            this.state = state;
            this.connected = connected;
            this.lastHeartbeatAt = lastHeartbeatAt;
            this.authFailures = authFailures;
            this.error = error;
            this.delivered = delivered;
        }
    }

    private static final class AuthFailure extends Exception { }

    private Context context;
    private final String kind;
    private final SecureRandom random = new SecureRandom();
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicLong generation = new AtomicLong();
    private final Object writeLock = new Object();
    private volatile Thread worker;
    private volatile Socket socket;
    private volatile InputStream in;
    private volatile OutputStream out;
    private volatile boolean connected;
    private volatile String state = "未启动";
    private volatile String error = "";
    private volatile int authFailures;
    private volatile long lastHeartbeatAt;
    private volatile long delivered;
    private volatile JSONObject pending;

    private LinkClient(Context context, String kind) {
        this.context = context;
        this.kind = kind;
    }

    public synchronized void start() {
        if (running.get()) return;
        if (context == null) { state = "等待应用上下文"; return; }
        running.set(true);
        state = HookConfig.configured(context) ? "连接中" : "未配置";
        worker = new Thread(this::loop, "mi-companion-hook");
        worker.setDaemon(true);
        worker.start();
    }

    public void stop() {
        running.set(false);
        generation.incrementAndGet();
        close();
        Thread thread = worker;
        if (thread != null) thread.interrupt();
        worker = null;
        state = "已停止";
    }

    /** Applies new port/token by dropping the socket and letting the loop reconnect immediately. */
    public void reconnect() {
        generation.incrementAndGet();
        close();
        Thread thread = worker;
        if (thread != null) thread.interrupt();
        if (!running.get()) start();
    }

    /** Keeps the newest snapshot in memory; sends it now if connected, otherwise after the next connect. */
    public void send(JSONObject snapshot) {
        if (snapshot == null) return;
        pending = snapshot;
        if (!connected) return;
        try {
            writeRaw(LinkProtocol.frame(LinkProtocol.OP_TEXT, snapshotMessage(snapshot), true, random));
        } catch (Exception e) {
            error = "发送失败";
            close();
        }
    }

    public Status status() {
        return new Status(state, connected, lastHeartbeatAt, authFailures, error, delivered);
    }

    private void loop() {
        int attempt = 0;
        while (running.get()) {
            String token = HookConfig.token(context);
            int port = HookConfig.port(context);
            if (!LinkProtocol.validToken(token)) {
                state = "未配置";
                error = "请在设置中填写 16 位 Token";
                sleep(2000);
                continue;
            }
            try {
                connect(port, token);
                attempt = 0;
                readLoop();
            } catch (AuthFailure e) {
                authFailures++;
                state = "鉴权失败";
                error = "Token 不匹配";
                sleep(5000);
            } catch (Exception e) {
                if (running.get()) {
                    state = "未连接";
                    error = describe(e);
                    sleep(backoff(attempt++));
                }
            } finally {
                close();
            }
        }
        state = "已停止";
    }

    private void connect(int port, String token) throws Exception {
        Socket fresh = new Socket();
        long expected = generation.get();
        try {
            fresh.connect(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), port), 3000);
            fresh.setSoTimeout(5000);
            BufferedInputStream input = new BufferedInputStream(fresh.getInputStream());
            OutputStream output = fresh.getOutputStream();
            String key = LinkProtocol.newKey(random);
            String request = "GET / HTTP/1.1\r\n"
                    + "Host: 127.0.0.1:" + port + "\r\n"
                    + "Upgrade: websocket\r\n"
                    + "Connection: Upgrade\r\n"
                    + "Sec-WebSocket-Key: " + key + "\r\n"
                    + "Sec-WebSocket-Version: 13\r\n"
                    + "Authorization: Bearer " + token + "\r\n"
                    + "X-Mi-Companion-Kind: " + kind + "\r\n"
                    + "X-Mi-Companion-Version: " + VERSION + "\r\n\r\n";
            output.write(request.getBytes(StandardCharsets.ISO_8859_1));
            output.flush();
            LinkProtocol.HttpMessage response = LinkProtocol.readHttp(input, LinkProtocol.MAX_HEADER);
            if (response.startLine.contains(" 401")) throw new AuthFailure();
            if (!response.startLine.contains(" 101")) throw new IOException("握手被拒绝");
            if (!LinkProtocol.accept(key).equals(response.headers.get("sec-websocket-accept")))
                throw new IOException("握手校验失败");
            if (!running.get() || expected != generation.get()) throw new IOException("重连中");
            synchronized (writeLock) {
                socket = fresh;
                in = input;
                out = output;
            }
            writeRaw(LinkProtocol.frame(LinkProtocol.OP_TEXT, helloMessage(), true, random));
            JSONObject latest = pending;
            if (latest != null) writeRaw(LinkProtocol.frame(LinkProtocol.OP_TEXT, snapshotMessage(latest), true, random));
            connected = true;
            error = "";
            authFailures = 0;
            state = "已连接";
            lastHeartbeatAt = SystemClock.elapsedRealtime();
            Log.i(TAG, kind + " connected");
        } catch (Exception e) {
            try { fresh.close(); } catch (IOException ignored) { }
            throw e;
        }
    }

    private void readLoop() throws Exception {
        Socket current = socket;
        if (current == null) throw new IOException("未连接");
        current.setSoTimeout(12000);
        while (running.get() && connected) {
            LinkProtocol.Frame frame = LinkProtocol.readFrame(in, false);
            if (frame.opcode == LinkProtocol.OP_PING) {
                writeRaw(LinkProtocol.frame(LinkProtocol.OP_PONG, frame.payload, true, random));
                lastHeartbeatAt = SystemClock.elapsedRealtime();
                state = "已连接";
                continue;
            }
            if (frame.opcode == LinkProtocol.OP_CLOSE) {
                state = "未连接";
                error = "服务端关闭连接";
                return;
            }
            if (frame.opcode != LinkProtocol.OP_TEXT) continue;
            JSONObject message;
            try {
                message = new JSONObject(LinkProtocol.utf8(frame.payload));
            } catch (Exception e) {
                continue;
            }
            String type = message.optString("type");
            if ("ack".equals(type)) {
                delivered++;
                error = "";
            } else if ("error".equals(type)) {
                error = message.optString("reason", "服务端拒绝了快照");
            }
        }
    }

    private byte[] helloMessage() throws Exception {
        return new JSONObject().put("type", "hello").put("kind", kind)
                .put("version", VERSION).put("protocol", LinkProtocol.PROTOCOL_VERSION)
                .toString().getBytes(StandardCharsets.UTF_8);
    }

    private byte[] snapshotMessage(JSONObject snapshot) throws Exception {
        return new JSONObject().put("type", "snapshot").put("snapshot", snapshot)
                .toString().getBytes(StandardCharsets.UTF_8);
    }

    private void writeRaw(byte[] frame) throws IOException {
        synchronized (writeLock) {
            OutputStream output = out;
            if (output == null) throw new IOException("未连接");
            output.write(frame);
            output.flush();
        }
    }

    private void close() {
        Socket current = socket;
        socket = null;
        in = null;
        out = null;
        connected = false;
        if (current != null) try { current.close(); } catch (IOException ignored) { }
    }

    private static void sleep(long millis) {
        try { Thread.sleep(millis); } catch (InterruptedException ignored) { }
    }

    private static long backoff(int attempt) {
        long base = attempt <= 0 ? 1000 : Math.min(30000, 1000L << Math.min(attempt, 5));
        long jitter = (long) (base * 0.2 * (Math.random() * 2 - 1));
        return Math.max(500, base + jitter);
    }

    private static String describe(Exception e) {
        if (e instanceof java.net.ConnectException) return "主程序未运行或端口不通";
        if (e instanceof java.net.SocketTimeoutException) return "连接超时";
        return "连接失败：" + e.getClass().getSimpleName();
    }
}

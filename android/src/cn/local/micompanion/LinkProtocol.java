package cn.local.micompanion;

import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Minimal RFC 6455 subset shared by the app-side server and the injected clients.
 * No Android dependency so the same code can be unit-tested on a desktop JVM.
 */
public final class LinkProtocol {
    public static final int DEFAULT_PORT = 17890;
    public static final int PROTOCOL_VERSION = 1;
    public static final int MAX_FRAME = 327680;
    public static final int MAX_HEADER = 8192;
    public static final int MAX_CONNECTIONS = 4;
    public static final String GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11";
    public static final int OP_CONTINUATION = 0, OP_TEXT = 1, OP_BINARY = 2, OP_CLOSE = 8, OP_PING = 9, OP_PONG = 10;
    private static final char[] ALPHABET =
            "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789".toCharArray();

    private LinkProtocol() { }

    public static final class ProtocolException extends IOException {
        public final int closeCode;
        public ProtocolException(int closeCode, String message) {
            super(message);
            this.closeCode = closeCode;
        }
    }

    public static final class Frame {
        public final int opcode;
        public final byte[] payload;
        Frame(int opcode, byte[] payload) { this.opcode = opcode; this.payload = payload; }
    }

    public static final class HttpMessage {
        public final String startLine;
        public final Map<String, String> headers;
        HttpMessage(String startLine, Map<String, String> headers) {
            this.startLine = startLine;
            this.headers = headers;
        }
    }

    /** Sec-WebSocket-Accept for a client key (RFC 6455 section 4.2.2). */
    public static String accept(String key) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-1");
            digest.update((key + GUID).getBytes(StandardCharsets.ISO_8859_1));
            return Base64.getEncoder().encodeToString(digest.digest());
        } catch (Exception e) {
            throw new IOException("SHA-1 unavailable", e);
        }
    }

    public static String newKey(SecureRandom random) {
        byte[] value = new byte[16];
        random.nextBytes(value);
        return Base64.getEncoder().encodeToString(value);
    }

    /** 16 characters from [A-Za-z0-9], with at least one lowercase, uppercase and digit. */
    public static String newToken(SecureRandom random) {
        char[] out = new char[16];
        out[0] = (char) ('a' + random.nextInt(26));
        out[1] = (char) ('A' + random.nextInt(26));
        out[2] = (char) ('0' + random.nextInt(10));
        for (int i = 3; i < out.length; i++) out[i] = ALPHABET[random.nextInt(ALPHABET.length)];
        for (int i = out.length - 1; i > 0; i--) {
            int j = random.nextInt(i + 1);
            char swap = out[i]; out[i] = out[j]; out[j] = swap;
        }
        return new String(out);
    }

    public static boolean validToken(String token) {
        if (token == null || token.length() != 16) return false;
        for (int i = 0; i < token.length(); i++) {
            char c = token.charAt(i);
            if (!((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9'))) return false;
        }
        return true;
    }

    /** Length and content are compared without early exit. */
    public static boolean tokenEquals(String expected, String supplied) {
        if (expected == null || supplied == null) return false;
        byte[] a = expected.getBytes(StandardCharsets.US_ASCII);
        byte[] b = supplied.getBytes(StandardCharsets.US_ASCII);
        int diff = a.length ^ b.length;
        int count = Math.min(a.length, b.length);
        for (int i = 0; i < count; i++) diff |= a[i] ^ b[i];
        return diff == 0;
    }

    public static boolean validKind(String kind) {
        return "weather".equals(kind) || "health".equals(kind);
    }

    public static boolean validPort(int port) {
        return port >= 1024 && port <= 65535;
    }

    public static String sourcePackage(String kind) {
        return "weather".equals(kind) ? "com.miui.weather2" : "health".equals(kind) ? "com.mi.health" : null;
    }

    public static byte[] frame(int opcode, byte[] payload, boolean mask, SecureRandom random) {
        if (payload == null) payload = new byte[0];
        boolean control = opcode >= 8;
        if (control && payload.length > 125) throw new IllegalArgumentException("控制帧过大");
        if (payload.length > MAX_FRAME) throw new IllegalArgumentException("帧过大");
        int extended = payload.length <= 125 ? 0 : payload.length <= 65535 ? 2 : 8;
        byte[] maskKey = null;
        if (mask) {
            maskKey = new byte[4];
            random.nextBytes(maskKey);
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream(2 + extended + payload.length + 4);
        out.write(0x80 | opcode);
        out.write((mask ? 0x80 : 0) | (extended == 0 ? payload.length : extended == 2 ? 126 : 127));
        if (extended == 2) {
            out.write((payload.length >>> 8) & 0xff);
            out.write(payload.length & 0xff);
        } else if (extended == 8) {
            for (int shift = 56; shift >= 0; shift -= 8) out.write((int) (((long) payload.length >>> shift) & 0xff));
        }
        if (mask) {
            out.write(maskKey, 0, 4);
            for (int i = 0; i < payload.length; i++) out.write(payload[i] ^ maskKey[i & 3]);
        } else {
            out.write(payload, 0, payload.length);
        }
        return out.toByteArray();
    }

    public static byte[] closeFrame(int code, String reason) {
        byte[] text = (reason == null ? "" : reason).getBytes(StandardCharsets.UTF_8);
        int keep = Math.min(text.length, 123);
        byte[] payload = new byte[2 + keep];
        payload[0] = (byte) ((code >>> 8) & 0xff);
        payload[1] = (byte) (code & 0xff);
        System.arraycopy(text, 0, payload, 2, keep);
        return frame(OP_CLOSE, payload, false, new SecureRandom());
    }

    public static byte[] pingFrame(long elapsedMillis) {
        byte[] payload = new byte[8];
        for (int i = 0; i < 8; i++) payload[i] = (byte) (elapsedMillis >>> (56 - 8 * i));
        return frame(OP_PING, payload, false, new SecureRandom());
    }

    public static long pingValue(byte[] payload) {
        if (payload == null || payload.length != 8) return -1;
        long value = 0;
        for (int i = 0; i < 8; i++) value = (value << 8) | (payload[i] & 0xffL);
        return value;
    }

    /** Reads exactly one unfragmented frame; control frames must fit in 125 bytes. */
    public static Frame readFrame(InputStream in, boolean expectMasked) throws IOException {
        int first = in.read();
        if (first < 0) throw new EOFException("连接已关闭");
        int second = in.read();
        if (second < 0) throw new EOFException("帧不完整");
        boolean fin = (first & 0x80) != 0;
        int opcode = first & 0x0f;
        if ((first & 0x70) != 0) throw new ProtocolException(1002, "RSV 位非法");
        boolean masked = (second & 0x80) != 0;
        if (masked != expectMasked) throw new ProtocolException(1002, "帧掩码不符合角色");
        long length = second & 0x7f;
        if (length == 126) {
            int a = in.read(), b = in.read();
            if (a < 0 || b < 0) throw new EOFException("长度不完整");
            length = ((long) a << 8) | b;
        } else if (length == 127) {
            length = 0;
            for (int i = 0; i < 8; i++) {
                int value = in.read();
                if (value < 0) throw new EOFException("长度不完整");
                length = (length << 8) | value;
            }
            if ((length & 0x8000000000000000L) != 0) throw new ProtocolException(1002, "长度非法");
        }
        boolean control = opcode >= 8;
        if (control) {
            if (!fin) throw new ProtocolException(1002, "控制帧不可分片");
            if (length > 125) throw new ProtocolException(1002, "控制帧过大");
        } else if (!fin) {
            throw new ProtocolException(1003, "不支持分片消息");
        }
        if (opcode == OP_CONTINUATION) throw new ProtocolException(1003, "不支持分片消息");
        if (opcode != OP_TEXT && opcode != OP_BINARY && opcode != OP_CLOSE && opcode != OP_PING && opcode != OP_PONG)
            throw new ProtocolException(1002, "未知 opcode");
        if (length > MAX_FRAME) throw new ProtocolException(1009, "帧超过上限");
        byte[] mask = new byte[4];
        if (masked) readFully(in, mask);
        byte[] payload = new byte[(int) length];
        readFully(in, payload);
        if (masked) for (int i = 0; i < payload.length; i++) payload[i] ^= mask[i & 3];
        return new Frame(opcode, payload);
    }

    private static void readFully(InputStream in, byte[] target) throws IOException {
        int offset = 0;
        while (offset < target.length) {
            int read = in.read(target, offset, target.length - offset);
            if (read < 0) throw new EOFException("帧不完整");
            offset += read;
        }
    }

    public static String utf8(byte[] payload) throws ProtocolException {
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(payload)).toString();
        } catch (CharacterCodingException e) {
            throw new ProtocolException(1007, "文本不是合法 UTF-8");
        }
    }

    /** Reads CRLF-terminated HTTP headers without consuming frame bytes. */
    public static HttpMessage readHttp(InputStream in, int limit) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        int matched = 0;
        while (buffer.size() < limit) {
            int value = in.read();
            if (value < 0) throw new EOFException("握手不完整");
            if (value == 0) throw new ProtocolException(1002, "握手包含 NUL");
            buffer.write(value);
            if ((matched == 0 || matched == 2) && value == '\r') matched++;
            else if ((matched == 1 || matched == 3) && value == '\n') matched++;
            else matched = 0;
            if (matched == 4) break;
        }
        if (matched != 4) throw new ProtocolException(1002, "握手头超过上限");
        String[] lines = new String(buffer.toByteArray(), StandardCharsets.ISO_8859_1).split("\r\n");
        if (lines.length == 0 || lines[0].isEmpty()) throw new ProtocolException(1002, "握手首行缺失");
        Map<String, String> headers = new LinkedHashMap<>();
        for (int i = 1; i < lines.length; i++) {
            if (lines[i].isEmpty()) continue;
            int colon = lines[i].indexOf(':');
            if (colon <= 0) throw new ProtocolException(1002, "握手头非法");
            String name = lines[i].substring(0, colon).trim().toLowerCase(Locale.US);
            String value = lines[i].substring(colon + 1).trim();
            if (headers.containsKey(name)) throw new ProtocolException(1002, "握手头重复");
            headers.put(name, value);
        }
        return new HttpMessage(lines[0].trim(), headers);
    }

    public static boolean headerContains(Map<String, String> headers, String name, String token) {
        String value = headers.get(name);
        if (value == null) return false;
        for (String part : value.split(",")) if (token.equalsIgnoreCase(part.trim())) return true;
        return false;
    }
}

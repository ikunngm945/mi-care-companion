package cn.local.micompanion;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.security.SecureRandom;
import java.util.HashSet;
import java.util.Set;

public final class LinkProtocolTest {
    private static int checks;

    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
        checks++;
    }

    private static void expectCode(int code, byte[] data, boolean expectMasked) throws Exception {
        try {
            LinkProtocol.readFrame(new ByteArrayInputStream(data), expectMasked);
            throw new AssertionError("应拒绝 close=" + code);
        } catch (LinkProtocol.ProtocolException e) {
            check(e.closeCode == code, "close 码应为 " + code + "，实际 " + e.closeCode);
        }
    }

    public static void main(String[] args) throws Exception {
        SecureRandom random = new SecureRandom();
        check("s3pPLMBiTxaQ9kYGzzhZRbK+xOo=".equals(LinkProtocol.accept("dGhlIHNhbXBsZSBub25jZQ==")), "RFC accept 示例");
        check(LinkProtocol.newKey(random).length() > 20, "握手 key 为 Base64");

        for (String token : new String[]{"aB3dE6gH9jK2mN5p", "Zz9Yy8Xx7Ww6Vv5U"}) check(LinkProtocol.validToken(token), "合法 token");
        check(!LinkProtocol.validToken(null), "null token");
        check(!LinkProtocol.validToken("aB3dE6gH9jK2mN5"), "15 位 token");
        check(!LinkProtocol.validToken("aB3dE6gH9jK2mN5pQ"), "17 位 token");
        check(!LinkProtocol.validToken("aB3dE6gH9jK2mN5-"), "非法字符");
        Set<String> tokens = new HashSet<>();
        for (int i = 0; i < 500; i++) {
            String token = LinkProtocol.newToken(random);
            check(LinkProtocol.validToken(token), "生成 token 合法");
            boolean lower = false, upper = false, digit = false;
            for (int j = 0; j < token.length(); j++) {
                char c = token.charAt(j);
                lower |= c >= 'a' && c <= 'z';
                upper |= c >= 'A' && c <= 'Z';
                digit |= c >= '0' && c <= '9';
            }
            check(lower && upper && digit, "生成 token 含三类字符");
            tokens.add(token);
        }
        check(tokens.size() == 500, "500 次生成不重复");
        check(LinkProtocol.tokenEquals("aB3dE6gH9jK2mN5p", "aB3dE6gH9jK2mN5p"), "token 相等");
        check(!LinkProtocol.tokenEquals("aB3dE6gH9jK2mN5p", "aB3dE6gH9jK2mN5q"), "token 不等");
        check(!LinkProtocol.tokenEquals("aB3dE6gH9jK2mN5p", "aB3dE6gH9jK2mN5"), "长度不等");
        check(!LinkProtocol.tokenEquals(null, "aB3dE6gH9jK2mN5p"), "null 比较");

        for (int length : new int[]{0, 5, 125, 126, 700, 65535, 70000}) {
            byte[] payload = new byte[length];
            random.nextBytes(payload);
            for (boolean mask : new boolean[]{false, true}) {
                byte[] wire = LinkProtocol.frame(LinkProtocol.OP_TEXT, payload, mask, random);
                LinkProtocol.Frame frame = LinkProtocol.readFrame(new ByteArrayInputStream(wire), mask);
                check(frame.opcode == LinkProtocol.OP_TEXT, "opcode 往返");
                check(java.util.Arrays.equals(frame.payload, payload), "payload 往返 " + length + " mask=" + mask);
            }
        }
        byte[] text = LinkProtocol.frame(LinkProtocol.OP_TEXT, "hello".getBytes("UTF-8"), false, random);
        try {
            LinkProtocol.readFrame(new ByteArrayInputStream(text), true);
            throw new AssertionError("缺少掩码应拒绝");
        } catch (LinkProtocol.ProtocolException e) { check(e.closeCode == 1002, "掩码不符 close 1002"); }

        // FIN=0 的分片文本。
        byte[] fragmented = new byte[]{0x01, (byte) 0x80, 0, 0, 0, 0};
        expectCode(1003, fragmented, true);

        // 126 长度扩展，声明 PAYLOAD=126，但只给 1 字节，应在读取时报 EOF，而不是先报长度。
        ByteArrayOutputStream lengthPrefix = new ByteArrayOutputStream();
        lengthPrefix.write(LinkProtocol.OP_TEXT | 0x80);
        lengthPrefix.write(127);
        long tooLarge = LinkProtocol.MAX_FRAME + 1L;
        for (int shift = 56; shift >= 0; shift -= 8) lengthPrefix.write((int) ((tooLarge >>> shift) & 0xff));
        try {
            LinkProtocol.readFrame(new ByteArrayInputStream(lengthPrefix.toByteArray()), false);
            throw new AssertionError("超大帧应拒绝");
        } catch (LinkProtocol.ProtocolException e) { check(e.closeCode == 1009, "超大帧 close 1009"); }

        byte[] ping = LinkProtocol.pingFrame(123456789L);
        LinkProtocol.Frame pingFrame = LinkProtocol.readFrame(new ByteArrayInputStream(ping), false);
        check(pingFrame.opcode == LinkProtocol.OP_PING, "ping opcode");
        check(LinkProtocol.pingValue(pingFrame.payload) == 123456789L, "ping 时间往返");
        check(LinkProtocol.pingValue(null) == -1, "ping 非法负载");

        byte[] close = LinkProtocol.closeFrame(1008, "auth");
        LinkProtocol.Frame closeFrame = LinkProtocol.readFrame(new ByteArrayInputStream(close), false);
        check(closeFrame.opcode == LinkProtocol.OP_CLOSE, "close opcode");
        check(((closeFrame.payload[0] & 0xff) << 8 | (closeFrame.payload[1] & 0xff)) == 1008, "close 码");

        try {
            LinkProtocol.utf8(new byte[]{(byte) 0xc3, 0x28});
            throw new AssertionError("非法 UTF-8 应拒绝");
        } catch (LinkProtocol.ProtocolException e) { check(e.closeCode == 1007, "非法 UTF-8 close 1007"); }

        String request = "GET / HTTP/1.1\r\nHost: 127.0.0.1:17890\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Version: 13\r\n\r\n";
        LinkProtocol.HttpMessage message = LinkProtocol.readHttp(new ByteArrayInputStream(request.getBytes("ISO-8859-1")), 8192);
        check(message.startLine.equals("GET / HTTP/1.1"), "请求行");
        check(message.headers.get("host").equals("127.0.0.1:17890"), "Host 头");
        check(LinkProtocol.headerContains(message.headers, "connection", "upgrade"), "Connection 解析");
        check(!LinkProtocol.headerContains(message.headers, "connection", "keep-alive"), "Connection 不匹配");
        check(!LinkProtocol.validPort(80), "80 非法");
        check(LinkProtocol.validPort(17890), "17890 合法");
        check(!LinkProtocol.validPort(70000), "70000 非法");

        System.out.println("LinkProtocol: " + checks + " checks passed");
    }
}

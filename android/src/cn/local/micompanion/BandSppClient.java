package cn.local.micompanion;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;
import android.content.Context;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.Locale;
import java.util.UUID;
import javax.crypto.Cipher;

/**
 * Direct Xiaomi Band 10 client over Classic Bluetooth SPP (RFCOMM).
 *
 * <p>The band answers a SPP V1 version request with protocol version 3 and then expects the
 * SPP V2 framing (A5 A5, sequence numbers, CRC-16/ARC, go-back-N acks). Authentication is the
 * ordinary miwear-auth handshake; SPP V2 protects command bodies with AES/CTR using the derived
 * key as both key and IV.</p>
 */
final class BandSppClient {
    static final UUID SPP_UUID = UUID.fromString("00001101-0000-1000-8000-00805f9b34fb");
    private static final byte[] V1_PREAMBLE = new byte[]{(byte) 0xba, (byte) 0xdc, (byte) 0xfe};
    private static final byte[] V2_PREAMBLE = new byte[]{(byte) 0xa5, (byte) 0xa5};
    private static final byte[] V1_VERSION_REQUEST = new byte[]{
            (byte) 0xba, (byte) 0xdc, (byte) 0xfe, 0x00, (byte) 0xc0, 0x03, 0x00, 0x00, 0x00, 0x00, (byte) 0xef
    };
    private static final int V2_NACK = 0, V2_ACK = 1, V2_SESSION_CONFIG = 2, V2_DATA = 3;
    private static final int CHANNEL_PROTOBUF = 1, CHANNEL_DATA = 2, CHANNEL_ACTIVITY = 5;
    private static final int OPCODE_PLAINTEXT = 1, OPCODE_ENCRYPTED = 2;

    interface Listener {
        void onState(String state);
        void onReady();
        void onFileIds(int subtype, List<byte[]> fileIds);
        void onFile(byte[] file);
        void onError(String reason);
    }

    private final Context context;
    private final BandConfig config;
    private final Listener listener;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final SecureRandom random = new SecureRandom();
    private final ByteArrayOutputStream pending = new ByteArrayOutputStream();
    private BluetoothSocket socket;
    private InputStream input;
    private OutputStream output;
    private Thread reader;
    private volatile boolean closed;
    private volatile boolean v2;
    private volatile boolean encrypted;
    private volatile boolean ready;
    private int txSequence;
    private int authPhase;
    private final ByteArrayOutputStream activityBuffer = new ByteArrayOutputStream();
    private final List<byte[]> pendingFiles = new ArrayList<>();
    private final Set<String> receivedFiles = new HashSet<>();
    private final Set<String> requestedFiles = new HashSet<>();
    private boolean fetchingFile;
    private byte[] secretKey;
    private byte[] phoneNonce;
    private byte[] watchNonce;
    private byte[] decryptionKey, encryptionKey, decryptionNonce, encryptionNonce;

    BandSppClient(Context context, BandConfig config, Listener listener) {
        this.context = context.getApplicationContext();
        this.config = config;
        this.listener = listener;
    }

    void connect() {
        new Thread(this::connectBlocking, "band-spp-connect").start();
        handler.postDelayed(() -> {
            if (!closed && !ready) fail("手环认证超时");
        }, 25000);
    }

    private void connectBlocking() {
        try {
            BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
            if (adapter == null || !adapter.isEnabled()) { fail("蓝牙未开启"); return; }
            BluetoothDevice device = adapter.getRemoteDevice(config.mac);
            state("正在连接手环");
            socket = device.createRfcommSocketToServiceRecord(SPP_UUID);
            socket.connect();
            input = socket.getInputStream();
            output = socket.getOutputStream();
            state("已连接手环，正在握手");
            reader = new Thread(this::readLoop, "band-spp-reader");
            reader.start();
            sendV1(V1_VERSION_REQUEST);
        } catch (Throwable error) {
            fail("连接手环失败：" + error.getMessage());
        }
    }

    void close() {
        closed = true;
        handler.removeCallbacksAndMessages(null);
        try { if (socket != null) socket.close(); } catch (Exception ignored) { }
        socket = null;
    }

    void requestToday() {
        if (!ready) { fail("手环尚未认证"); return; }
        sendCommand(BandProto.fetchTodayCommand());
    }

    void requestPast() {
        if (!ready) { fail("手环尚未认证"); return; }
        state("发送历史列表请求");
        sendCommand(BandProto.fetchPastCommand());
    }

    void requestFile(byte[] fileId) {
        if (!ready) { fail("手环尚未认证"); return; }
        sendCommand(BandProto.requestFileCommand(fileId));
    }

    private void readLoop() {
        byte[] buffer = new byte[4096];
        try {
            while (!closed) {
                int read = input.read(buffer);
                if (read < 0) { fail("手环已断开连接"); return; }
                pending.write(buffer, 0, read);
                drain();
            }
        } catch (Throwable error) {
            if (!closed) fail("读取手环数据失败：" + error.getMessage());
        }
    }

    private void drain() throws Exception {
        while (true) {
            byte[] data = pending.toByteArray();
            if (!v2) {
                int start = indexOf(data, V1_PREAMBLE);
                if (start < 0) { if (data.length > 2) replacePending(new byte[0]); return; }
                if (start > 0) { replacePending(slice(data, start)); data = pending.toByteArray(); }
                if (data.length < 11) return;
                int size = (data[5] & 0xff) | ((data[6] & 0xff) << 8);
                int total = 11 + (size - 3);
                if (total < 11 || data.length < total) return;
                byte[] frame = slice(data, 0, total);
                replacePending(slice(data, total));
                handleV1(frame);
            } else {
                int start = indexOf(data, V2_PREAMBLE);
                if (start < 0) { if (data.length > 1) replacePending(new byte[0]); return; }
                if (start > 0) { replacePending(slice(data, start)); data = pending.toByteArray(); }
                if (data.length < 8) return;
                int size = (data[4] & 0xff) | ((data[5] & 0xff) << 8);
                int total = 8 + size;
                if (data.length < total) return;
                byte[] frame = slice(data, 0, total);
                replacePending(slice(data, total));
                handleV2(frame);
            }
        }
    }

    private void handleV1(byte[] frame) throws Exception {
        if (frame.length < 11) return;
        int channel = frame[3] & 0xf;
        int payloadLength = ((frame[5] & 0xff) | ((frame[6] & 0xff) << 8)) - 3;
        if (payloadLength < 0 || 10 + payloadLength > frame.length) return;
        byte[] payload = slice(frame, 10, payloadLength);
        if (channel != 0 || payload.length == 0) return;
        int version = payload[0] & 0xff;
        state("手环 SPP 版本 " + version);
        if (version >= 2) {
            v2 = true;
            pending.reset();
            sendSessionConfig();
        } else {
            fail("手环 SPP 版本不受支持：" + version);
        }
    }

    private void sendSessionConfig() throws Exception {
        byte[] payload = new byte[]{
                1, // OPCODE_START_SESSION_REQUEST
                1, 3, 0, 1, 0, 0,             // version 1.0.0
                2, 2, 0, 0, (byte) 0xfc,      // max frame size 0xfc00
                3, 2, 0, 0x20, 0,             // tx window 32
                4, 2, 0, 0x10, 0x27           // send timeout 10000 ms
        };
        writeFrame(V2_SESSION_CONFIG, 0, payload);
        state("已请求 SPP V2 会话");
    }

    private void handleV2(byte[] frame) throws Exception {
        if (frame.length < 8) return;
        int type = frame[2] & 0xf;
        int sequence = frame[3] & 0xff;
        int length = (frame[4] & 0xff) | ((frame[5] & 0xff) << 8);
        if (frame.length < 8 + length) return;
        byte[] payload = slice(frame, 8, length);
        int expected = checksum(payload);
        int actual = (frame[6] & 0xff) | ((frame[7] & 0xff) << 8);
        if (expected != actual) { fail("SPP 校验失败"); return; }
        switch (type) {
            case V2_SESSION_CONFIG:
                if (authPhase == 0) startAuth();
                break;
            case V2_DATA:
                writeFrame(V2_ACK, sequence, new byte[0]);
                handleData(sequence, payload);
                break;
            case V2_ACK:
                break;
            case V2_NACK:
                // We only have one outstanding command at a time; resending is unnecessary.
                break;
            default:
                break;
        }
    }

    private void handleData(int sequence, byte[] payload) throws Exception {
        if (payload.length < 2) return;
        int rawChannel = payload[0] & 0xf;
        int opCode = payload[1] & 0xff;
        byte[] body = slice(payload, 2, payload.length - 2);
        if (opCode == OPCODE_ENCRYPTED) {
            if (!encrypted) { fail("手环提前发送了加密命令"); return; }
            body = BandCrypto.ctrCrypt(Cipher.DECRYPT_MODE, decryptionKey, body);
        }
        if (rawChannel == CHANNEL_PROTOBUF) handleCommand(body);
        else if (rawChannel == CHANNEL_ACTIVITY) handleActivity(body);
    }

    private void startAuth() throws Exception {
        if (authPhase != 0) return;
        authPhase = 1;
        secretKey = hex(config.authKey);
        phoneNonce = new byte[16];
        random.nextBytes(phoneNonce);
        state("正在认证手环");
        sendRawData(CHANNEL_PROTOBUF, OPCODE_PLAINTEXT, BandProto.authNonceCommand(phoneNonce));
    }

    private void handleCommand(byte[] command) throws Exception {
        java.util.Map<Integer, Object> fields = BandProto.fields(command);
        int type = (int) BandProto.varint(fields, 1, -1);
        int subtype = (int) BandProto.varint(fields, 2, -1);
        long status = BandProto.varint(fields, 100, 0);
        byte[] account = BandProto.bytes(fields, 3);
        if (type == 1) {
            handleAuth(account, subtype, status);
        } else if (type == 8) {
            handleHealth(fields, subtype, status);
        }
    }

    private void handleAuth(byte[] account, int subtype, long status) throws Exception {
        if (subtype == 26 && authPhase == 1) {
            java.util.Map<Integer, Object> accountFields = BandProto.fields(account);
            byte[] deviceVerify = BandProto.bytes(accountFields, 31);
            if (deviceVerify == null) { fail("手环认证拒绝（状态 " + status + "）"); return; }
            java.util.Map<Integer, Object> verifyFields = BandProto.fields(deviceVerify);
            watchNonce = BandProto.bytes(verifyFields, 1);
            byte[] deviceHmac = BandProto.bytes(verifyFields, 2);
            if (watchNonce == null || watchNonce.length != 16 || deviceHmac == null) {
                fail("手环认证响应不完整"); return;
            }
            byte[] derived = BandCrypto.authStep3Hmac(secretKey, phoneNonce, watchNonce);
            decryptionKey = slice(derived, 0, 16);
            encryptionKey = slice(derived, 16, 16);
            decryptionNonce = slice(derived, 32, 4);
            encryptionNonce = slice(derived, 36, 4);
            byte[] expectedHmac = BandCrypto.hmac(decryptionKey, watchNonce, phoneNonce);
            if (!BandCrypto.equal(expectedHmac, deviceHmac)) { fail("手环认证密钥不匹配"); return; }
            byte[] encryptedNonces = BandCrypto.hmac(encryptionKey, phoneNonce, watchNonce);
            byte[] info = BandProto.companionDevice(
                    Build.VERSION.SDK_INT,
                    Build.MODEL == null ? "Android" : Build.MODEL,
                    region());
            byte[] deviceInfo = BandCrypto.ccmEncrypt(encryptionKey, nonce(encryptionNonce, 0), info);
            authPhase = 2;
            state("正在确认手环认证");
            sendRawData(CHANNEL_PROTOBUF, OPCODE_PLAINTEXT,
                    BandProto.authConfirmCommand(encryptedNonces, deviceInfo));
        } else if (subtype == 27 && authPhase == 2) {
            if (status != 0 && status != 1) { fail("手环认证失败（状态 " + status + "）"); return; }
            encrypted = true;
            ready = true;
            authPhase = 3;
            state("手环认证成功");
            try { listener.onReady(); } catch (Throwable ignored) { }
        } else if (authPhase >= 1 && status != 0 && status != 1) {
            fail("手环认证失败（状态 " + status + "）");
        }
    }

    private void handleHealth(java.util.Map<Integer, Object> fields, int subtype, long status) {
        byte[] health = BandProto.bytes(fields, 10);
        if (health == null) return;
        try {
            java.util.Map<Integer, Object> healthFields = BandProto.fields(health);
            byte[] ids = BandProto.bytes(healthFields, 2);
            if (ids != null) {
                List<byte[]> fileIds = new ArrayList<>();
                for (int offset = 0; offset + 7 <= ids.length; offset += 7) {
                    fileIds.add(slice(ids, offset, 7));
                }
                state("收到文件列表 subtype=" + subtype + " count=" + fileIds.size());
                for (byte[] fileId : fileIds) {
                    String key = hex(fileId);
                    if (!receivedFiles.contains(key) && !requestedFiles.contains(key)) pendingFiles.add(fileId);
                }
                listener.onFileIds(subtype, fileIds);
                if (subtype == BandProto.HEALTH_FETCH_TODAY) {
                    state("请求历史文件列表");
                    requestPast();
                }
                requestNextFile();
            }
        } catch (Exception ignored) { }
    }

    private void handleActivity(byte[] data) {
        if (data.length < 4) return;
        // Activity chunk: u16 total number of chunks, u16 current chunk (1-based), payload.
        int total = (data[0] & 0xff) | ((data[1] & 0xff) << 8);
        int number = (data[2] & 0xff) | ((data[3] & 0xff) << 8);
        if (number == 1) activityBuffer.reset();
        activityBuffer.write(data, 4, data.length - 4);
        if (number != total) return;
        byte[] file = activityBuffer.toByteArray();
        activityBuffer.reset();
        if (file.length < 13) return;
        long expectedCrc = ((long) (file[file.length - 4] & 0xff))
                | ((long) (file[file.length - 3] & 0xff) << 8)
                | ((long) (file[file.length - 2] & 0xff) << 16)
                | ((long) (file[file.length - 1] & 0xff) << 24);
        java.util.zip.CRC32 crc32 = new java.util.zip.CRC32();
        crc32.update(file, 0, file.length - 4);
        if (crc32.getValue() != expectedCrc) {
            fail("手环活动文件校验失败");
            return;
        }
        byte[] fileId = slice(file, 0, 7);
        receivedFiles.add(hex(fileId));
        requestedFiles.remove(hex(fileId));
        fetchingFile = false;
        listener.onFile(file);
        // The official app acknowledges a file before parsing it. Acknowledge only after the
        // bytes are safely handed to the caller; the caller persists them synchronously.
        sendCommand(BandProto.ackFileCommand(fileId));
        requestNextFile();
    }

    private synchronized void requestNextFile() {
        if (!ready || fetchingFile) return;
        while (!pendingFiles.isEmpty()) {
            byte[] fileId = pendingFiles.remove(0);
            String key = hex(fileId);
            if (receivedFiles.contains(key) || requestedFiles.contains(key)) continue;
            requestedFiles.add(key);
            fetchingFile = true;
            state("请求活动文件 " + key);
            sendCommand(BandProto.requestFileCommand(fileId));
            return;
        }
        state("手环活动文件同步完成");
    }

    private void sendCommand(byte[] command) {
        try {
            byte[] body = BandCrypto.ctrCrypt(Cipher.ENCRYPT_MODE, encryptionKey, command);
            sendRawData(CHANNEL_PROTOBUF, OPCODE_ENCRYPTED, body);
        } catch (Throwable error) {
            fail("命令加密失败：" + error.getMessage());
        }
    }

    private synchronized void sendRawData(int channel, int opCode, byte[] body) throws Exception {
        ByteArrayOutputStream payload = new ByteArrayOutputStream(body.length + 2);
        payload.write(channel & 0xf);
        payload.write(opCode & 0xff);
        payload.write(body, 0, body.length);
        writeFrame(V2_DATA, txSequence & 0xff, payload.toByteArray());
        txSequence = (txSequence + 1) & 0xff;
    }

    private synchronized void sendV1(byte[] frame) throws Exception {
        output.write(frame);
        output.flush();
    }

    private synchronized void writeFrame(int type, int sequence, byte[] payload) throws Exception {
        ByteBuffer frame = ByteBuffer.allocate(8 + payload.length).order(ByteOrder.LITTLE_ENDIAN);
        frame.put(V2_PREAMBLE);
        frame.put((byte) (type & 0xf));
        frame.put((byte) (sequence & 0xff));
        frame.putShort((short) payload.length);
        frame.putShort((short) checksum(payload));
        frame.put(payload);
        output.write(frame.array());
        output.flush();
    }

    private byte[] nonce(byte[] prefix, int index) {
        ByteBuffer buffer = ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN);
        buffer.put(prefix);
        buffer.putInt(0);
        buffer.putInt(index);
        return buffer.array();
    }

    private String region() {
        String country = Locale.getDefault().getCountry();
        return country == null || country.length() != 2 ? "CN" : country.toUpperCase(Locale.US);
    }

    private void state(String message) {
        try { listener.onState(message); } catch (Throwable ignored) { }
    }

    private void fail(String message) {
        if (closed) return;
        closed = true;
        try { if (socket != null) socket.close(); } catch (Exception ignored) { }
        try { listener.onError(message); } catch (Throwable ignored) { }
    }

    private void replacePending(byte[] value) {
        pending.reset();
        pending.write(value, 0, value.length);
    }

    private static int indexOf(byte[] data, byte[] pattern) {
        outer:
        for (int i = 0; i + pattern.length <= data.length; i++) {
            for (int j = 0; j < pattern.length; j++) {
                if (data[i + j] != pattern[j]) continue outer;
            }
            return i;
        }
        return -1;
    }

    private static byte[] slice(byte[] data, int start) {
        return slice(data, start, data.length - start);
    }

    private static byte[] slice(byte[] data, int start, int length) {
        byte[] out = new byte[length];
        System.arraycopy(data, start, out, 0, length);
        return out;
    }

    private static String hex(byte[] value) {
        StringBuilder out = new StringBuilder(value.length * 2);
        for (byte b : value) out.append(String.format("%02x", b & 0xff));
        return out.toString();
    }

    private static byte[] hex(String value) {
        byte[] out = new byte[value.length() / 2];
        for (int i = 0; i < out.length; i++) {
            out[i] = (byte) Integer.parseInt(value.substring(i * 2, i * 2 + 2), 16);
        }
        return out;
    }

    /** CRC-16/ARC, exactly as used by Gadgetbridge's SPP V2 implementation. */
    private static int checksum(byte[] payload) {
        int crc = 0;
        for (byte value : payload) {
            for (int bit = 0; bit < 8; bit++) {
                crc <<= 1;
                if ((((crc >> 16) & 1) ^ ((value >> bit) & 1)) == 1) crc ^= 0x8005;
            }
        }
        return Integer.reverse(crc) >>> 16;
    }
}

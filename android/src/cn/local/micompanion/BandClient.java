package cn.local.micompanion;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.content.Context;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.json.JSONObject;

/**
 * Direct Xiaomi BLE client. It uses the existing system bond and the auth key that Mi Fitness
 * already provisioned, then speaks the same miwear-auth/BLE protocol as the official app.
 */
final class BandClient {
    static final UUID SERVICE = UUID.fromString("0000fe95-0000-1000-8000-00805f9b34fb");
    static final UUID CHAR_COMMAND_READ = UUID.fromString("00000051-0000-1000-8000-00805f9b34fb");
    static final UUID CHAR_COMMAND_WRITE = UUID.fromString("00000052-0000-1000-8000-00805f9b34fb");
    static final UUID CHAR_ACTIVITY = UUID.fromString("00000053-0000-1000-8000-00805f9b34fb");
    static final UUID CCCD = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");
    private static final byte[] PAYLOAD_ACK = new byte[]{0, 0, 3, 0};
    private static final byte[] CHUNK_START_ACK = new byte[]{0, 0, 1, 1};
    private static final byte[] CHUNK_END_ACK = new byte[]{0, 0, 1, 0};

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
    private final ArrayDeque<Write> writes = new ArrayDeque<>();
    private final List<BluetoothGattCharacteristic> notifyQueue = new ArrayList<>();
    private final Map<Integer, byte[]> receivedChunks = new HashMap<>();
    private BluetoothGatt gatt;
    private BluetoothGattCharacteristic readChar, writeChar, activityChar;
    private int mtu = 23;
    private int notifyIndex;
    private boolean writing;
    private boolean closed;
    private boolean authReady;
    private int encryptedIndex = 1;
    private byte[] secretKey;
    private byte[] phoneNonce;
    private byte[] decryptionKey, encryptionKey, decryptionNonce, encryptionNonce;
    private int incomingChunks;
    private ByteArrayOutputStream incoming = new ByteArrayOutputStream();
    private ByteArrayOutputStream fileBuffer = new ByteArrayOutputStream();
    private int fileTotal, fileNumber;

    private static final class Write {
        final BluetoothGattCharacteristic characteristic;
        final byte[] value;
        Write(BluetoothGattCharacteristic characteristic, byte[] value) {
            this.characteristic = characteristic;
            this.value = value;
        }
    }

    BandClient(Context context, BandConfig config, Listener listener) {
        this.context = context.getApplicationContext();
        this.config = config;
        this.listener = listener;
    }

    void connect() {
        BluetoothManager manager = (BluetoothManager) context.getSystemService(Context.BLUETOOTH_SERVICE);
        BluetoothAdapter adapter = manager == null ? null : manager.getAdapter();
        if (adapter == null || !adapter.isEnabled()) { fail("蓝牙未开启"); return; }
        BluetoothDevice device = adapter.getRemoteDevice(config.mac);
        state("正在连接手环");
        gatt = device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE);
        if (gatt == null) fail("无法创建 BLE 连接");
        else handler.postDelayed(() -> { if (!closed && !authReady) fail("手环连接超时"); }, 20000);
    }

    void close() {
        closed = true;
        handler.removeCallbacksAndMessages(null);
        if (gatt != null) {
            try { gatt.disconnect(); } catch (Exception ignored) { }
            try { gatt.close(); } catch (Exception ignored) { }
            gatt = null;
        }
    }

    void requestToday() {
        if (!authReady) { fail("手环尚未认证"); return; }
        send(BandProto.fetchTodayCommand());
    }

    void requestFile(byte[] fileId) {
        if (!authReady) { fail("手环尚未认证"); return; }
        send(BandProto.requestFileCommand(fileId));
    }

    private final BluetoothGattCallback callback = new BluetoothGattCallback() {
        @Override public void onConnectionStateChange(BluetoothGatt g, int status, int newState) {
            if (closed) return;
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                state("已连接，请求 MTU");
                g.requestMtu(512);
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                if (!authReady) fail("手环连接断开"); else state("手环已断开");
            }
        }

        @Override public void onMtuChanged(BluetoothGatt g, int newMtu, int status) {
            mtu = Math.max(23, newMtu);
            state("MTU " + mtu + "，发现服务");
            g.discoverServices();
        }

        @Override public void onServicesDiscovered(BluetoothGatt g, int status) {
            if (status != BluetoothGatt.GATT_SUCCESS) { fail("服务发现失败"); return; }
            BluetoothGattService service = g.getService(SERVICE);
            if (service == null) { fail("未找到小米手环服务"); return; }
            readChar = service.getCharacteristic(CHAR_COMMAND_READ);
            writeChar = service.getCharacteristic(CHAR_COMMAND_WRITE);
            activityChar = service.getCharacteristic(CHAR_ACTIVITY);
            if (readChar == null || writeChar == null || activityChar == null) { fail("缺少小米手环特征"); return; }
            notifyQueue.clear();
            notifyQueue.add(readChar); notifyQueue.add(writeChar); notifyQueue.add(activityChar);
            notifyIndex = 0;
            enableNextNotify();
        }

        @Override public void onDescriptorWrite(BluetoothGatt g, BluetoothGattDescriptor descriptor, int status) {
            enableNextNotify();
        }

        @Override public void onCharacteristicWrite(BluetoothGatt g, BluetoothGattCharacteristic characteristic, int status) {
            writing = false;
            if (status != BluetoothGatt.GATT_SUCCESS) { fail("BLE 写入失败"); return; }
            writeNext();
        }

        @Override public void onCharacteristicChanged(BluetoothGatt g, BluetoothGattCharacteristic characteristic, byte[] value) {
            handleNotification(characteristic, value);
        }

        @Override public void onCharacteristicChanged(BluetoothGatt g, BluetoothGattCharacteristic characteristic) {
            byte[] value = characteristic.getValue();
            if (value != null) handleNotification(characteristic, value);
        }
    };

    private void enableNextNotify() {
        if (notifyIndex >= notifyQueue.size()) {
            state("通知已开启，开始认证");
            sendAuthNonce();
            return;
        }
        BluetoothGattCharacteristic characteristic = notifyQueue.get(notifyIndex++);
        if (!gatt.setCharacteristicNotification(characteristic, true)) { fail("无法开启通知"); return; }
        BluetoothGattDescriptor descriptor = characteristic.getDescriptor(CCCD);
        if (descriptor == null) { enableNextNotify(); return; }
        descriptor.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
        if (!gatt.writeDescriptor(descriptor)) fail("无法写入通知描述符");
    }

    private void sendAuthNonce() {
        secretKey = hex(config.authKey);
        phoneNonce = new byte[16];
        random.nextBytes(phoneNonce);
        byte[] command = BandProto.authNonceCommand(phoneNonce);
        writeChar.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT);
        enqueue(writeChar, frameSingle(command, false, 0));
        state("已发送认证 nonce");
        handler.postDelayed(() -> { if (!closed && !authReady) fail("手环认证超时"); }, 15000);
    }

    private void handleAuth(byte[] command) throws Exception {
        Map<Integer, Object> outer = BandProto.fields(command);
        int type = (int) BandProto.varint(outer, 1, -1);
        int subtype = (int) BandProto.varint(outer, 2, 0);
        long status = BandProto.varint(outer, 100, 0);
        byte[] accountBytes = BandProto.bytes(outer, 3);
        if (type != BandProto.CMD_TYPE_AUTH || accountBytes == null) return;
        Map<Integer, Object> account = BandProto.fields(accountBytes);
        if (subtype == BandProto.AUTH_SUBTYPE_DEVICE_NONCE) {
            byte[] deviceVerify = BandProto.bytes(account, 31);
            if (deviceVerify == null) throw new IllegalStateException("认证响应缺少 deviceVerify");
            Map<Integer, Object> verify = BandProto.fields(deviceVerify);
            byte[] watchNonce = BandProto.bytes(verify, 1);
            byte[] watchHmac = BandProto.bytes(verify, 2);
            if (watchNonce == null || watchNonce.length != 16 || watchHmac == null)
                throw new IllegalStateException("认证响应 nonce 无效");
            byte[] step = BandCrypto.authStep3Hmac(secretKey, phoneNonce, watchNonce);
            decryptionKey = Arrays.copyOfRange(step, 0, 16);
            encryptionKey = Arrays.copyOfRange(step, 16, 32);
            decryptionNonce = Arrays.copyOfRange(step, 32, 36);
            encryptionNonce = Arrays.copyOfRange(step, 36, 40);
            byte[] expected = BandCrypto.hmac(decryptionKey, watchNonce, phoneNonce);
            if (!BandCrypto.equal(expected, watchHmac)) throw new SecurityException("手环密钥不匹配");
            byte[] encryptedNonces = BandCrypto.hmac(encryptionKey, phoneNonce, watchNonce);
            String region = Locale.getDefault().getLanguage();
            region = region.length() >= 2 ? region.substring(0, 2).toUpperCase(Locale.ROOT) : "CN";
            byte[] deviceInfo = BandProto.companionDevice(Build.VERSION.SDK_INT, Build.MODEL, region);
            byte[] encryptedDeviceInfo = BandCrypto.ccmEncrypt(encryptionKey, nonce(encryptionNonce, 0), deviceInfo);
            byte[] confirm = BandProto.authConfirmCommand(encryptedNonces, encryptedDeviceInfo);
            enqueue(writeChar, frameSingle(confirm, false, 0));
            state("已发送认证确认");
        } else if (subtype == BandProto.AUTH_SUBTYPE_CONFIRM || status == 1) {
            authReady = true;
            encryptedIndex = 1;
            state("认证成功");
            listener.onReady();
        }
    }

    private void handleCommand(byte[] plain) {
        try {
            Map<Integer, Object> outer = BandProto.fields(plain);
            int type = (int) BandProto.varint(outer, 1, -1);
            if (type == BandProto.CMD_TYPE_AUTH) { handleAuth(plain); return; }
            if (type != BandProto.CMD_TYPE_HEALTH) return;
            int subtype = (int) BandProto.varint(outer, 2, 0);
            byte[] healthBytes = BandProto.bytes(outer, 10);
            if (healthBytes == null) return;
            Map<Integer, Object> health = BandProto.fields(healthBytes);
            if (subtype == BandProto.HEALTH_FETCH_TODAY || subtype == BandProto.HEALTH_FETCH_PAST) {
                byte[] ids = BandProto.bytes(health, 2);
                List<byte[]> files = new ArrayList<>();
                if (ids != null) for (int i = 0; i + 7 <= ids.length; i += 7)
                    files.add(Arrays.copyOfRange(ids, i, i + 7));
                listener.onFileIds(subtype, files);
                if (subtype == BandProto.HEALTH_FETCH_TODAY) send(BandProto.fetchPastCommand());
            }
        } catch (Exception ex) {
            fail("手环响应解析失败");
        }
    }

    private void handleNotification(BluetoothGattCharacteristic characteristic, byte[] value) {
        if (closed || value == null || value.length < 2) return;
        if (characteristic.getUuid().equals(CHAR_ACTIVITY)) { handleActivity(value); return; }
        if (!characteristic.getUuid().equals(CHAR_COMMAND_READ)
                && !characteristic.getUuid().equals(CHAR_COMMAND_WRITE)) return;
        try {
            ByteBuffer buffer = ByteBuffer.wrap(value).order(ByteOrder.LITTLE_ENDIAN);
            int chunk = buffer.getShort();
            if (chunk != 0) {
                if (incomingChunks <= 0 || chunk > incomingChunks) return;
                byte[] body = new byte[buffer.remaining()];
                buffer.get(body);
                receivedChunks.put(chunk, body);
                if (receivedChunks.size() == incomingChunks) {
                    enqueue(characteristic, CHUNK_END_ACK);
                    incoming.reset();
                    for (int i = 1; i <= incomingChunks; i++) {
                        byte[] part = receivedChunks.get(i);
                        if (part != null) incoming.write(part, 0, part.length);
                    }
                    receivedChunks.clear();
                    byte[] payload = incoming.toByteArray();
                    incoming = new ByteArrayOutputStream();
                    handleCommand(authReady ? BandCrypto.ccmDecrypt(decryptionKey, nonce(decryptionNonce, 0), payload) : payload);
                    incomingChunks = 0;
                }
                return;
            }
            int type = buffer.get();
            if (type == 0) {
                buffer.get(); // encryption flag
                incomingChunks = buffer.getShort();
                receivedChunks.clear();
                enqueue(characteristic, CHUNK_START_ACK);
            } else if (type == 2) {
                enqueue(characteristic, PAYLOAD_ACK);
                int encrypted = buffer.get();
                byte[] body = new byte[buffer.remaining()];
                buffer.get(body);
                byte[] plain = encrypted == 1
                        ? BandCrypto.ccmDecrypt(decryptionKey, nonce(decryptionNonce, 0), body) : body;
                handleCommand(plain);
            } else if (type == 3) {
                // outgoing command acknowledged
            }
        } catch (Exception ex) {
            fail("手环数据帧解析失败");
        }
    }

    private void handleActivity(byte[] value) {
        if (value.length < 4) return;
        ByteBuffer buffer = ByteBuffer.wrap(value).order(ByteOrder.LITTLE_ENDIAN);
        fileTotal = buffer.getShort() & 0xffff;
        fileNumber = buffer.getShort() & 0xffff;
        if (fileNumber == 1) fileBuffer = new ByteArrayOutputStream();
        fileBuffer.write(value, 4, value.length - 4);
        if (fileNumber == fileTotal && fileTotal > 0) {
            byte[] file = fileBuffer.toByteArray();
            fileBuffer = new ByteArrayOutputStream();
            listener.onFile(file);
        }
    }

    private void send(byte[] command) {
        try {
            boolean encrypted = authReady;
            byte[] payload = encrypted
                    ? BandCrypto.ccmEncrypt(encryptionKey, nonce(encryptionNonce, encryptedIndex), command) : command;
            byte[] frame = frameSingle(payload, encrypted, encryptedIndex);
            if (encrypted) encryptedIndex++;
            enqueue(writeChar, frame);
        } catch (Exception ex) {
            fail("手环命令加密失败");
        }
    }

    private static byte[] frameSingle(byte[] payload, boolean encrypted, int index) {
        ByteBuffer out = ByteBuffer.allocate(4 + (encrypted ? 2 : 0) + payload.length).order(ByteOrder.LITTLE_ENDIAN);
        out.putShort((short) 0);
        out.put((byte) 2);
        out.put((byte) (encrypted ? 1 : 2));
        if (encrypted) out.putShort((short) index);
        out.put(payload);
        return out.array();
    }

    private static byte[] nonce(byte[] prefix, int index) {
        ByteBuffer out = ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN);
        out.put(prefix, 0, 4);
        out.putInt(0);
        out.putInt(index);
        return out.array();
    }

    private void enqueue(BluetoothGattCharacteristic characteristic, byte[] value) {
        if (closed) return;
        writes.add(new Write(characteristic, value));
        writeNext();
    }

    private void writeNext() {
        if (closed || writing || gatt == null) return;
        Write write = writes.poll();
        if (write == null) return;
        writing = true;
        write.characteristic.setValue(write.value);
        write.characteristic.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT);
        if (!gatt.writeCharacteristic(write.characteristic)) { writing = false; fail("BLE 写入启动失败"); }
    }

    private void state(String value) { listener.onState(value); }
    private void fail(String reason) {
        if (closed) return;
        closed = true;
        handler.removeCallbacksAndMessages(null);
        if (gatt != null) try { gatt.disconnect(); gatt.close(); } catch (Exception ignored) { }
        listener.onError(reason);
    }

    private static byte[] hex(String value) {
        byte[] out = new byte[value.length() / 2];
        for (int i = 0; i < out.length; i++)
            out[i] = (byte) Integer.parseInt(value.substring(i * 2, i * 2 + 2), 16);
        return out;
    }
}

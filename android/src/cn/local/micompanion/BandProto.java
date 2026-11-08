package cn.local.micompanion;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/** Tiny proto2 codec for the subset of Xiaomi's command/auth messages used by direct BLE sync. */
final class BandProto {
    private BandProto() { }

    static final int CMD_TYPE_AUTH = 1;
    static final int CMD_TYPE_HEALTH = 8;
    static final int AUTH_SUBTYPE_DEVICE_NONCE = 26;
    static final int AUTH_SUBTYPE_CONFIRM = 27;
    static final int HEALTH_FETCH_TODAY = 1;
    static final int HEALTH_FETCH_PAST = 2;
    static final int HEALTH_FETCH_REQUEST = 3;
    static final int HEALTH_FETCH_ACK = 5;

    static byte[] authNonceCommand(byte[] phoneNonce) {
        byte[] appVerify = bytesField(1, phoneNonce);
        byte[] account = bytesField(30, appVerify);
        return command(CMD_TYPE_AUTH, AUTH_SUBTYPE_DEVICE_NONCE, account);
    }

    static byte[] authConfirmCommand(byte[] encryptedNonces, byte[] encryptedDeviceInfo) {
        byte[] appConfirm = concat(bytesField(1, encryptedNonces), bytesField(2, encryptedDeviceInfo));
        byte[] account = bytesField(32, appConfirm);
        return command(CMD_TYPE_AUTH, AUTH_SUBTYPE_CONFIRM, account);
    }

    static byte[] companionDevice(float apiLevel, String phoneName, String region) {
        byte[] name = phoneName == null ? new byte[0] : phoneName.getBytes(StandardCharsets.UTF_8);
        byte[] reg = region == null ? new byte[0] : region.getBytes(StandardCharsets.UTF_8);
        return concat(
                varintField(1, 0),
                fixed32Field(2, Float.floatToIntBits(apiLevel)),
                bytesField(3, name),
                varintField(4, 224),
                bytesField(5, reg)
        );
    }

    static byte[] fetchTodayCommand() {
        byte[] request = varintField(1, 0); // ActivitySyncRequestToday.unknown1 = 0
        byte[] health = bytesField(5, request);
        return healthCommand(HEALTH_FETCH_TODAY, health);
    }

    static byte[] fetchPastCommand() {
        return healthCommand(HEALTH_FETCH_PAST, null);
    }

    static byte[] requestFileCommand(byte[] fileId) {
        byte[] health = bytesField(2, fileId);
        return healthCommand(HEALTH_FETCH_REQUEST, health);
    }

    static byte[] ackFileCommand(byte[] fileId) {
        byte[] health = bytesField(3, fileId);
        return healthCommand(HEALTH_FETCH_ACK, health);
    }

    /** Health lives in Command field 10, unlike Auth which is Command field 3. */
    static byte[] healthCommand(int subtype, byte[] health) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        writeVarint(out, (1 << 3) | 0); writeVarint(out, CMD_TYPE_HEALTH);
        if (subtype != 0) { writeVarint(out, (2 << 3) | 0); writeVarint(out, subtype); }
        if (health != null) {
            writeVarint(out, (10 << 3) | 2); writeVarint(out, health.length);
            out.write(health, 0, health.length);
        }
        return out.toByteArray();
    }

    static byte[] command(int type, int subtype, byte[] auth) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        writeVarint(out, (1 << 3) | 0); writeVarint(out, type);
        if (subtype != 0) { writeVarint(out, (2 << 3) | 0); writeVarint(out, subtype); }
        if (auth != null) { writeVarint(out, (3 << 3) | 2); writeVarint(out, auth.length); out.write(auth, 0, auth.length); }
        return out.toByteArray();
    }

    static Map<Integer, Object> fields(byte[] data) {
        Map<Integer, Object> out = new HashMap<>();
        int index = 0;
        while (index < data.length) {
            long tag = 0;
            int shift = 0;
            while (true) {
                if (index >= data.length || shift > 63) throw new IllegalArgumentException("bad protobuf tag");
                int b = data[index++] & 0xff;
                tag |= (long) (b & 0x7f) << shift;
                if ((b & 0x80) == 0) break;
                shift += 7;
            }
            int field = (int) (tag >>> 3);
            int wire = (int) (tag & 7);
            if (wire == 0) {
                long value = 0; shift = 0;
                while (true) {
                    if (index >= data.length || shift > 63) throw new IllegalArgumentException("bad varint");
                    int b = data[index++] & 0xff;
                    value |= (long) (b & 0x7f) << shift;
                    if ((b & 0x80) == 0) break;
                    shift += 7;
                }
                out.put(field, value);
            } else if (wire == 2) {
                long length = 0; shift = 0;
                while (true) {
                    if (index >= data.length || shift > 63) throw new IllegalArgumentException("bad length");
                    int b = data[index++] & 0xff;
                    length |= (long) (b & 0x7f) << shift;
                    if ((b & 0x80) == 0) break;
                    shift += 7;
                }
                if (length < 0 || length > data.length - index) throw new IllegalArgumentException("truncated field");
                byte[] value = new byte[(int) length];
                System.arraycopy(data, index, value, 0, value.length);
                index += value.length;
                out.put(field, value);
            } else if (wire == 5) {
                if (index + 4 > data.length) throw new IllegalArgumentException("truncated fixed32");
                out.put(field, ByteBuffer.wrap(data, index, 4).order(ByteOrder.LITTLE_ENDIAN).getInt());
                index += 4;
            } else if (wire == 1) {
                if (index + 8 > data.length) throw new IllegalArgumentException("truncated fixed64");
                index += 8;
            } else {
                throw new IllegalArgumentException("unsupported wire type " + wire);
            }
        }
        return out;
    }

    static byte[] bytes(Map<Integer, Object> fields, int field) {
        Object value = fields.get(field);
        return value instanceof byte[] ? (byte[]) value : null;
    }

    static long varint(Map<Integer, Object> fields, int field, long fallback) {
        Object value = fields.get(field);
        return value instanceof Long ? (Long) value : fallback;
    }

    private static byte[] bytesField(int field, byte[] value) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(value.length + 8);
        writeVarint(out, (field << 3) | 2); writeVarint(out, value.length); out.write(value, 0, value.length);
        return out.toByteArray();
    }

    private static byte[] varintField(int field, long value) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(8);
        writeVarint(out, (field << 3) | 0); writeVarint(out, value);
        return out.toByteArray();
    }

    private static byte[] fixed32Field(int field, int value) {
        byte[] out = new byte[5];
        out[0] = (byte) ((field << 3) | 5);
        out[1] = (byte) (value & 0xff); out[2] = (byte) ((value >>> 8) & 0xff);
        out[3] = (byte) ((value >>> 16) & 0xff); out[4] = (byte) ((value >>> 24) & 0xff);
        return out;
    }

    private static void writeVarint(ByteArrayOutputStream out, long value) {
        while (true) {
            int b = (int) (value & 0x7f);
            value >>>= 7;
            if (value != 0) out.write(b | 0x80); else { out.write(b); return; }
        }
    }

    private static byte[] concat(byte[]... parts) {
        return BandCrypto.concat(parts);
    }
}

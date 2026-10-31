package cn.local.micompanion;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Reads the private per-second sport record written by Mi Health. The file is
 * already decrypted in the app's private storage; this parser is read-only and
 * deliberately limited to the outdoor-foot v5 layout used by this device.
 */
final class WorkoutDetail {
    private static final int MAX_BYTES = 4 * 1024 * 1024;
    private static final int MAX_SAMPLES = 12000;
    private static final String[] NAMES = {
            "calories", "heartRate", "wholeKm", "distance", "stride",
            "impact", "contactRatio", "cadence", "pace"
    };
    private static final int[] SIZES = {1, 1, 1, 1, 1, 4, 1, 1, 2};

    private WorkoutDetail() { }

    static JSONObject read(File fitnessRoot, String sid, long start, int protoType) throws Exception {
        JSONObject result = new JSONObject().put("status", "missing").put("source", "sport_record");
        File file = find(fitnessRoot, sid, start, protoType);
        if (file == null) return result.put("reason", "未找到运动详情文件");
        byte[] bytes = read(file);
        if (bytes.length < 13) return result.put("status", "error").put("reason", "运动详情文件过短");
        int version = u8(bytes, 5);
        int actualProto = u8(bytes, 6);
        if (version != 5 || actualProto != 22) {
            return result.put("status", "unsupported").put("version", version).put("proto_type", actualProto)
                    .put("reason", "暂不支持该运动详情版本");
        }

        // v5 header: 4-byte timestamp, tz/15min, version, sport type, reserved, 5-byte validity.
        int header = 13;
        boolean[] exists = new boolean[NAMES.length];
        boolean[] high = new boolean[NAMES.length];
        int nibble = 0;
        for (int i = 0; i < NAMES.length; i++) {
            int at = header - 5 + nibble / 2;
            int value = (nibble % 2 == 0) ? (bytes[at] >> 4) & 0x0F : bytes[at] & 0x0F;
            exists[i] = (value & 0x08) != 0;
            high[i] = (value & 0x04) != 0;
            nibble++;
        }
        int recordBytes = 0;
        for (int i = 0; i < NAMES.length; i++) if (exists[i]) recordBytes += SIZES[i];
        if (recordBytes != 13) {
            return result.put("status", "unsupported").put("reason", "运动详情字段布局不支持");
        }

        JSONArray samples = new JSONArray();
        JSONArray segments = new JSONArray();
        int offset = header;
        int maxStride = 0;
        while (offset + 17 <= bytes.length) {
            offset += 4; // pause/init block
            long count = u32(bytes, offset);
            offset += 4;
            long segmentStart = u32(bytes, offset);
            offset += 4;
            offset += 5; // v5 IT summary
            if (count < 0 || count > MAX_SAMPLES - samples.length()) {
                return result.put("status", "error").put("reason", "运动详情采样数过多");
            }
            if (count * recordBytes > bytes.length - offset) {
                return result.put("status", "error").put("reason", "运动详情文件不完整");
            }
            segments.put(new JSONObject().put("start_epoch_seconds", segmentStart)
                    .put("offset_seconds", segmentStart - start).put("count", count));
            for (int i = 0; i < count; i++) {
                int[] values = new int[NAMES.length];
                boolean[] has = new boolean[NAMES.length];
                for (int c = 0; c < NAMES.length; c++) {
                    if (!exists[c]) continue;
                    int value;
                    if (SIZES[c] == 1) value = u8(bytes, offset);
                    else if (SIZES[c] == 2) value = u16(bytes, offset);
                    else value = (int) u32(bytes, offset);
                    offset += SIZES[c];
                    if (high[c]) {
                        values[c] = value;
                        has[c] = true;
                    }
                }
                int t = (int) (segmentStart - start + i);
                JSONObject sample = new JSONObject().put("t", t)
                        .put("heart_rate_bpm", valid(has[1], values[1], 1, 300))
                        .put("cadence_spm", valid(has[7], values[7], 1, 300))
                        .put("stride_cm", valid(has[4], values[4], 1, 300))
                        .put("pace_seconds_per_km", valid(has[8], values[8], 1, 36000))
                        .put("distance_m", has[3] ? values[3] : JSONObject.NULL)
                        .put("calories_kcal", has[0] ? (values[0] >> 4) & 0x0F : JSONObject.NULL)
                        .put("steps", has[0] ? values[0] & 0x0F : JSONObject.NULL);
                if (has[4] && values[4] > maxStride) maxStride = values[4];
                samples.put(sample);
            }
        }
        return result.put("status", "ok").put("version", version).put("proto_type", actualProto)
                .put("sample_interval_seconds", 1).put("sample_count", samples.length())
                .put("segments", segments).put("max_stride_cm", maxStride > 0 ? maxStride : JSONObject.NULL)
                .put("samples", samples);
    }

    private static File find(File root, String sid, long start, int protoType) {
        if (root == null || !root.isDirectory()) return null;
        String name = start + "_" + protoType + "_record";
        if (sid != null && !sid.isEmpty()) {
            File direct = new File(new File(root, "d" + sid + "/sport/p32"), name);
            if (direct.isFile()) return direct;
        }
        File[] accounts = root.listFiles();
        if (accounts != null) for (File account : accounts) {
            File file = new File(new File(account, "sport/p32"), name);
            if (file.isFile()) return file;
        }
        return null;
    }

    private static byte[] read(File file) throws Exception {
        if (file.length() > MAX_BYTES) throw new IllegalArgumentException("运动详情文件过大");
        try (InputStream in = new FileInputStream(file); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int n;
            while ((n = in.read(buffer)) != -1) {
                out.write(buffer, 0, n);
                if (out.size() > MAX_BYTES) throw new IllegalArgumentException("运动详情文件过大");
            }
            return out.toByteArray();
        }
    }

    private static Object valid(boolean present, int value, int min, int max) {
        return present && value >= min && value <= max ? value : JSONObject.NULL;
    }

    private static int u8(byte[] bytes, int at) {
        return bytes[at] & 0xFF;
    }

    private static int u16(byte[] bytes, int at) {
        return (bytes[at] & 0xFF) | ((bytes[at + 1] & 0xFF) << 8);
    }

    private static long u32(byte[] bytes, int at) {
        return (bytes[at] & 0xFFL) | ((bytes[at + 1] & 0xFFL) << 8)
                | ((bytes[at + 2] & 0xFFL) << 16) | ((bytes[at + 3] & 0xFFL) << 24);
    }
}

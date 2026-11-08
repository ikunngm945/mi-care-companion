package cn.local.micompanion;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import org.json.JSONArray;
import org.json.JSONObject;

/** Parsers for the raw Xiaomi activity files fetched directly from the band. */
final class BandData {
    private BandData() { }

    static final class FileId {
        final long timestamp;
        final int timezoneQuarterHours;
        final int version;
        final int type;
        final int subtype;
        final int detailType;
        final String hex;

        FileId(byte[] id) {
            if (id.length != 7) throw new IllegalArgumentException("file id must be 7 bytes");
            timestamp = (id[0] & 0xffL) | ((id[1] & 0xffL) << 8) | ((id[2] & 0xffL) << 16) | ((id[3] & 0xffL) << 24);
            timezoneQuarterHours = id[4];
            version = id[5] & 0xff;
            int flags = id[6] & 0xff;
            type = (flags >> 7) & 1;
            subtype = (flags >> 2) & 0x1f;
            detailType = flags & 3;
            StringBuilder out = new StringBuilder();
            for (byte b : id) out.append(String.format("%02x", b & 0xff));
            hex = out.toString();
        }

        String isoTime() {
            ZoneOffset offset = ZoneOffset.ofTotalSeconds(timezoneQuarterHours * 900);
            return DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(Instant.ofEpochSecond(timestamp).atOffset(offset));
        }
    }

    /** Parse a complete raw activity file (7-byte id + pad + body + CRC32). */
    static JSONObject parse(byte[] file) throws Exception {
        if (file == null || file.length < 13) return null;
        FileId id = new FileId(java.util.Arrays.copyOfRange(file, 0, 7));
        byte[] body = java.util.Arrays.copyOfRange(file, 8, file.length - 4);
        JSONObject out = new JSONObject();
        out.put("file_id", id.hex);
        out.put("timestamp", id.timestamp);
        out.put("time", id.isoTime());
        out.put("version", id.version);
        out.put("type", id.type);
        out.put("subtype", id.subtype);
        out.put("detail_type", id.detailType);
        if (id.type == 0 && id.subtype == 0 && id.detailType == 1) {
            JSONObject summary = parseDailySummary(id, body);
            if (summary != null) {
                summary.put("time", id.isoTime());
                summary.put("timestamp", id.timestamp);
                out.put("daily_summary", summary);
            }
        } else if (id.type == 0 && id.subtype == 8) {
            JSONObject sleep = parseSleepDetails(id, body);
            if (sleep != null) out.put("sleep_details", sleep);
        } else if (id.type == 0 && id.subtype == 0 && id.detailType == 0) {
            out.put("daily_details_bytes", body.length);
        }
        return out;
    }

    private interface SlotReader { void read(ByteBuffer in, JSONObject out, boolean valid) throws Exception; }

    private static final SlotReader[] SLOTS = new SlotReader[]{
            (b, o, v) -> { int x = b.getInt(); if (v) o.put("steps", x); },
            (b, o, v) -> { int x = b.getShort() & 0xffff; if (v) o.put("active_calories", x); },
            (b, o, v) -> b.get(),
            (b, o, v) -> { int x = b.get() & 0xff; if (v) o.put("hr_resting", x); },
            (b, o, v) -> { int x = b.get() & 0xff; if (v) o.put("hr_max", x); },
            (b, o, v) -> { int x = b.getInt(); if (v) o.put("hr_max_at", x); },
            (b, o, v) -> { int x = b.get() & 0xff; if (v) o.put("hr_min", x); },
            (b, o, v) -> { int x = b.getInt(); if (v) o.put("hr_min_at", x); },
            (b, o, v) -> { int x = b.get() & 0xff; if (v) o.put("hr_avg", x); },
            (b, o, v) -> { int x = b.get() & 0xff; if (v) o.put("stress_avg", x); },
            (b, o, v) -> { int x = b.get() & 0xff; if (v) o.put("stress_max", x); },
            (b, o, v) -> { int x = b.get() & 0xff; if (v) o.put("stress_min", x); },
            (b, o, v) -> {
                byte[] standing = new byte[3]; b.get(standing);
                if (v) {
                    JSONArray hours = new JSONArray();
                    for (int hour = 0; hour < 24; hour++) {
                        if ((standing[hour / 8] & (1 << (hour % 8))) != 0) hours.put(hour);
                    }
                    o.put("standing_hours", hours);
                }
            },
            (b, o, v) -> { int x = b.getShort() & 0xffff; if (v) o.put("calories", x); },
            (b, o, v) -> { int x = b.getShort() & 0xffff; if (v) o.put("recovery_hours", x); },
            (b, o, v) -> b.get(),
            (b, o, v) -> { int x = b.get() & 0xff; if (v) o.put("spo2_max", x); },
            (b, o, v) -> { int x = b.getInt(); if (v) o.put("spo2_max_at", x); },
            (b, o, v) -> { int x = b.get() & 0xff; if (v) o.put("spo2_min", x); },
            (b, o, v) -> { int x = b.getInt(); if (v) o.put("spo2_min_at", x); },
            (b, o, v) -> { int x = b.get() & 0xff; if (v) o.put("spo2_avg", x); },
            (b, o, v) -> { int x = b.getShort() & 0xffff; if (v) o.put("training_load_day", x); },
            (b, o, v) -> { int x = b.getShort() & 0xffff; if (v) o.put("training_load_week", x); },
            (b, o, v) -> { int x = b.get() & 0xff; if (v) o.put("training_load_level", x); },
            (b, o, v) -> { int x = b.get() & 0xff; if (v) o.put("vitality_light", x); },
            (b, o, v) -> { int x = b.get() & 0xff; if (v) o.put("vitality_moderate", x); },
            (b, o, v) -> { int x = b.get() & 0xff; if (v) o.put("vitality_high", x); },
            (b, o, v) -> { int x = b.getShort() & 0xffff; if (v) o.put("vitality_current", x); },
            (b, o, v) -> b.get(),
            (b, o, v) -> b.get(),
            (b, o, v) -> b.getShort(),
            (b, o, v) -> b.getShort(),
    };

    private static JSONObject parseDailySummary(FileId id, byte[] body) throws Exception {
        if (id.version != 3 && id.version != 4 && id.version != 5) return null;
        int headerSize = id.version == 5 ? 4 : 3;
        if (body.length < headerSize) return null;
        ByteBuffer in = ByteBuffer.wrap(body).order(ByteOrder.LITTLE_ENDIAN);
        byte[] header = new byte[headerSize];
        in.get(header);
        JSONObject out = new JSONObject();
        out.put("version", id.version);
        int slots = id.version == 5 ? 30 : 21;
        for (int i = 0; i < slots; i++) {
            if (!hasSlot(header, i)) {
                // Slots still occupy bytes in the file; skip them without interpreting values.
                if (in.remaining() < slotSize(i)) break;
                in.position(in.position() + slotSize(i));
                continue;
            }
            if (in.remaining() < slotSize(i)) break;
            SLOTS[i].read(in, out, true);
        }
        return out;
    }

    private static boolean hasSlot(byte[] header, int index) {
        int byteIndex = index / 8;
        return byteIndex < header.length && (header[byteIndex] & (0x80 >> (index % 8))) != 0;
    }

    private static int slotSize(int i) {
        switch (i) {
            case 0: case 5: case 7: case 17: case 19: return 4;
            case 1: case 13: case 14: case 21: case 22: case 27: case 30: case 31: return 2;
            case 12: return 3;
            default: return 1;
        }
    }

    /**
     * Sleep-details v4/v5 have a bitmap header followed by a sequence of records. The record
     * layouts differ across versions; the stable fields needed for a summary are the totals and
     * the compact stage sequence. Return null for unsupported versions rather than guessing.
     */
    private static JSONObject parseSleepDetails(FileId id, byte[] body) throws Exception {
        if (id.version < 2 || id.version > 5) return null;
        ByteBuffer in = ByteBuffer.wrap(body).order(ByteOrder.LITTLE_ENDIAN);
        int headerSize = id.version == 5 ? 2 : 1;
        if (in.remaining() < headerSize + 9) return null;
        byte[] header = new byte[headerSize];
        in.get(header);
        int headerIndex = 0;
        boolean awake = (in.get() & 0xff) == 1;
        headerIndex++;
        long bedTime = in.getInt() & 0xffffffffL;
        headerIndex++;
        long wakeTime = in.getInt() & 0xffffffffL;
        headerIndex++;
        JSONObject out = new JSONObject();
        out.put("version", id.version);
        out.put("bytes", body.length);
        out.put("awake", awake);
        out.put("bedtime_epoch", bedTime);
        out.put("wakeup_epoch", wakeTime);
        out.put("bedtime", epochTime(bedTime, id.timezoneQuarterHours));
        out.put("wakeup", epochTime(wakeTime, id.timezoneQuarterHours));
        long duration = wakeTime - bedTime;
        if (duration > 0 && duration < 36 * 3600) out.put("duration_minutes", duration / 60);
        if (id.version >= 4 && headerIndex < header.length * 8 && hasSlot(header, headerIndex)) {
            out.put("quality", in.get() & 0xff);
        }
        return out;
    }

    private static String epochTime(long epoch, int tzQuarterHours) {
        ZoneOffset offset = ZoneOffset.ofTotalSeconds(tzQuarterHours * 900);
        return DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(Instant.ofEpochSecond(epoch).atOffset(offset));
    }

    static String describe(JSONObject sync) throws Exception {
        if (sync == null || !"ok".equals(sync.optString("status"))) return "手环数据不可用";
        JSONObject summary = sync.optJSONObject("today");
        if (summary == null) return "手环已同步，但没有今日摘要";
        StringBuilder out = new StringBuilder("手环直连 · ").append(summary.optString("time", "")).append('\n');
        append(out, summary, "steps", "步数");
        append(out, summary, "calories", "卡路里");
        append(out, summary, "hr_resting", "静息心率");
        appendRange(out, summary, "hr_min", "hr_max", "心率范围");
        append(out, summary, "hr_avg", "平均心率");
        appendRange(out, summary, "stress_min", "stress_max", "压力范围");
        append(out, summary, "stress_avg", "平均压力");
        appendRange(out, summary, "spo2_min", "spo2_max", "血氧范围");
        append(out, summary, "spo2_avg", "平均血氧");
        append(out, summary, "training_load_day", "训练负荷");
        append(out, summary, "vitality_current", "活力值");
        JSONObject sleep = sync.optJSONObject("sleep");
        if (sleep != null) {
            out.append("睡眠 ").append(sleep.optLong("duration_minutes", 0)).append(" 分钟");
            if (sleep.has("quality")) out.append("，质量 ").append(sleep.opt("quality"));
            out.append('\n');
        }
        return out.toString();
    }

    private static void append(StringBuilder out, JSONObject o, String key, String label) {
        if (o.has(key) && o.opt(key) != org.json.JSONObject.NULL) out.append(label).append(' ').append(o.opt(key)).append('\n');
    }

    private static void appendRange(StringBuilder out, JSONObject o, String minKey, String maxKey, String label) {
        if (o.has(minKey) && o.has(maxKey)) out.append(label).append(' ').append(o.opt(minKey)).append('-').append(o.opt(maxKey)).append('\n');
    }
}

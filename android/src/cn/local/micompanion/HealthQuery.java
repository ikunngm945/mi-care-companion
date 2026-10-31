package cn.local.micompanion;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.TimeZone;
import java.util.TreeMap;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Read-only, date-scoped health history used by the MCP service. It deliberately
 * returns bounded projections only: no source JSON, account ids, or free text.
 */
final class HealthQuery {
    private HealthQuery() { }
    private static final int MAX_POINTS = 12000;
    private static final int MAX_SESSIONS = 64;
    private static final int MAX_SEGMENTS = 512;
    private static final String[] SLEEP_KEYS = {"items", "sleepSegments"};

    static JSONObject query(HealthSource.Reader db, JSONObject request, long now, TimeZone zone) throws Exception {
        if (db == null) throw new IllegalArgumentException("健康数据库不可用");
        String action = request.optString("action", "");
        LocalDate day = date(request.optString("date", ""), now, zone);
        String date = day.toString();
        ZoneId zid = zone.toZoneId();
        long start = day.atStartOfDay(zid).toEpochSecond();
        long end = day.plusDays(1).atStartOfDay(zid).toEpochSecond();
        if (start >= now / 1000L) return missing(action, date, zone, "查询日期还没有开始");
        if (action.startsWith("sleep_")) return sleep(db, action, date, start, end, zone);
        Metric metric = Metric.of(action);
        if (metric == null) throw new IllegalArgumentException("未知查询类型");
        return vitals(db, action, metric, date, start, end, zone);
    }

    private static LocalDate date(String value, long now, TimeZone zone) {
        if (value != null && !value.trim().isEmpty()) {
            try { return LocalDate.parse(value.trim(), DateTimeFormatter.ISO_LOCAL_DATE); }
            catch (Exception e) { throw new IllegalArgumentException("date 必须是 YYYY-MM-DD"); }
        }
        return Instant.ofEpochMilli(now).atZone(zone.toZoneId()).toLocalDate();
    }

    private static JSONObject missing(String action, String date, TimeZone zone, String reason) throws Exception {
        return new JSONObject().put("status", "missing").put("action", action).put("date", date)
                .put("timezone", zone.getID()).put("reason", reason);
    }

    private static JSONObject sleep(HealthSource.Reader db, String action, String date, long start, long end, TimeZone zone) throws Exception {
        boolean detail = "sleep_detail".equals(action);
        JSONArray rows = db.query("SELECT value,time,0 AS updateTime,endTime,sid,key FROM sleep_segment "
                + "WHERE isDeleted=0 AND key='sleep' AND endTime>? AND endTime<=? ORDER BY endTime ASC LIMIT " + (MAX_SESSIONS + 1),
                new String[]{Long.toString(start), Long.toString(end)});
        if (rows == null || rows.length() > MAX_SESSIONS) throw new IllegalArgumentException("睡眠记录过多");
        List<long[]> heart = points(db, "hr_record", "key IN ('heart_rate','single_heart_rate')", "bpm", start, end, 1, 300);
        List<long[]> oxygen = points(db, "spo2_record", "key IN ('spo2','single_spo2')", "spo2", start, end, 1, 100);
        TreeMap<Long, JSONObject> sessions = new TreeMap<>();
        HashSet<String> seen = new HashSet<>();
        for (int i = 0; i < rows.length(); i++) {
            try {
                JSONObject row = rows.getJSONObject(i), raw = new JSONObject(row.getString("value"));
                long bed = number(raw, "bedtime"), wake = number(raw, "wake_up_time");
                if (bed <= 0 || wake <= bed || wake > end || wake < start) continue;
                String signature = bed + ":" + wake + ":" + String.valueOf(raw.opt("items")) + ":" + String.valueOf(raw.opt("sleepSegments"));
                if (!seen.add(signature)) continue;
                JSONObject session = session(raw, bed, wake, detail, heart, oxygen, zone);
                sessions.put(wake + (sessions.containsKey(wake) ? 1 : 0), session);
            } catch (Exception ignored) { }
        }
        if (sessions.isEmpty()) return missing(action, date, zone, "当天没有可用的睡眠记录");
        JSONArray out = new JSONArray();
        long total = 0, deep = 0, light = 0, rem = 0, awake = 0;
        for (JSONObject session : sessions.values()) {
            out.put(session);
            JSONObject s = session.getJSONObject("summary");
            total += s.optLong("total_minutes", 0); deep += s.optLong("deep_minutes", 0);
            light += s.optLong("light_minutes", 0); rem += s.optLong("rem_minutes", 0);
            awake += s.optLong("awake_minutes", 0);
        }
        JSONObject result = new JSONObject().put("status", "ok").put("action", action).put("date", date)
                .put("timezone", zone.getID()).put("mode", detail ? "detail" : "summary")
                .put("sessions", out).put("totals", new JSONObject().put("total_minutes", total)
                        .put("deep_minutes", deep).put("light_minutes", light).put("rem_minutes", rem).put("awake_minutes", awake));
        return result;
    }

    private static JSONObject session(JSONObject raw, long bed, long wake, boolean detail, List<long[]> heart, List<long[]> oxygen, TimeZone zone) throws Exception {
        JSONArray items = null;
        for (String key : SLEEP_KEYS) { if (raw.optJSONArray(key) != null) { items = raw.getJSONArray(key); break; } }
        JSONArray segments = new JSONArray();
        long deep = 0, light = 0, rem = 0, awake = 0;
        boolean stages = false;
        if (items != null) {
            if (items.length() > MAX_SEGMENTS) throw new IllegalArgumentException("睡眠阶段过多");
            for (int i = 0; i < items.length(); i++) {
                JSONObject item = items.optJSONObject(i); if (item == null) continue;
                long s = number(item, "start_time"), e = number(item, "end_time");
                if (s < bed || e > wake || e <= s) continue;
                String stage = stage(item.opt("state"));
                long minutes = Math.round((e - s) / 60.0);
                stages = true;
                if ("deep".equals(stage)) deep += minutes; else if ("light".equals(stage)) light += minutes;
                else if ("rem".equals(stage)) rem += minutes; else if ("awake".equals(stage)) awake += minutes;
                if (detail) {
                    JSONObject segment = new JSONObject().put("start", iso(s, zone)).put("end", iso(e, zone))
                            .put("stage", stage).put("duration_minutes", minutes);
                    JSONObject hr = range(heart, s, e, "min", "max");
                    JSONObject sp = range(oxygen, s, e, "min", "max");
                    segment.put("heart_rate", hr == null ? JSONObject.NULL : hr);
                    segment.put("spo2", sp == null ? JSONObject.NULL : sp);
                    segments.put(segment);
                }
            }
        }
        if (!stages) {
            deep = raw.optLong("sleep_deep_duration", 0); light = raw.optLong("sleep_light_duration", 0);
            rem = raw.optLong("sleep_rem_duration", 0); awake = raw.optLong("sleep_awake_duration", 0);
        }
        long total = deep + light + rem;
        JSONObject summary = new JSONObject().put("total_minutes", total).put("deep_minutes", deep)
                .put("light_minutes", light).put("rem_minutes", rem).put("awake_minutes", awake);
        JSONObject hr = range(heart, bed, wake, "min", "max"); if (hr != null) {
            summary.put("heart_rate_min", hr.getInt("min")); summary.put("heart_rate_max", hr.getInt("max"));
        }
        JSONObject sp = range(oxygen, bed, wake, "min", "max"); if (sp != null) {
            summary.put("spo2_min", sp.getInt("min")); summary.put("spo2_max", sp.getInt("max"));
        }
        JSONObject out = new JSONObject().put("start", iso(bed, zone)).put("end", iso(wake, zone)).put("total_minutes", total).put("summary", summary);
        if (detail) out.put("segments", segments);
        return out;
    }

    private static JSONObject vitals(HealthSource.Reader db, String action, Metric metric, String date, long start, long end, TimeZone zone) throws Exception {
        List<long[]> values = points(db, metric.table, metric.keys, metric.field, start, end, metric.min, metric.max);
        if (values.isEmpty()) return missing(action, date, zone, "当天没有可用记录");
        long min = Long.MAX_VALUE, max = Long.MIN_VALUE, sum = 0;
        for (long[] p : values) { min = Math.min(min, p[1]); max = Math.max(max, p[1]); sum += p[1]; }
        double average = Math.round(sum * 10.0 / values.size()) / 10.0;
        JSONObject summary = new JSONObject().put("min", min).put("max", max).put("average", average)
                .put("count", values.size()).put("first", iso(values.get(0)[0], zone)).put("last", iso(values.get(values.size() - 1)[0], zone));
        JSONObject out = new JSONObject().put("status", "ok").put("action", action).put("date", date)
                .put("timezone", zone.getID()).put("metric", metric.name).put("unit", metric.unit)
                .put("summary", summary).put("mode", action.endsWith("_detail") ? "detail" : "summary");
        if (action.endsWith("_detail")) out.put("buckets", buckets(values, zone));
        return out;
    }

    private static JSONArray buckets(List<long[]> values, TimeZone zone) throws Exception {
        TreeMap<Long, long[]> map = new TreeMap<>();
        for (long[] p : values) {
            ZonedDateTime t = Instant.ofEpochSecond(p[0]).atZone(zone.toZoneId());
            long at = t.withMinute((t.getMinute() / 15) * 15).withSecond(0).withNano(0).toEpochSecond();
            long[] b = map.get(at);
            if (b == null) map.put(at, new long[]{p[1], p[1], 1});
            else { b[0] = Math.min(b[0], p[1]); b[1] = Math.max(b[1], p[1]); b[2]++; }
        }
        JSONArray out = new JSONArray();
        for (Map.Entry<Long, long[]> e : map.entrySet()) {
            out.put(new JSONObject().put("start", iso(e.getKey(), zone)).put("end", iso(e.getKey() + 900, zone))
                    .put("min", e.getValue()[0]).put("max", e.getValue()[1]).put("count", e.getValue()[2]));
        }
        return out;
    }

    private static List<long[]> points(HealthSource.Reader db, String table, String keys, String field, long start, long end, int min, int max) throws Exception {
        JSONArray rows = db.query("SELECT value,time,0 AS updateTime,endTime,sid,key FROM " + table
                + " WHERE isDeleted=0 AND " + keys + " AND time>=? AND time<? ORDER BY time ASC LIMIT " + (MAX_POINTS + 1),
                new String[]{Long.toString(start), Long.toString(end)});
        if (rows == null || rows.length() > MAX_POINTS) throw new IllegalArgumentException("记录过多");
        TreeMap<Long, Long> dedup = new TreeMap<>();
        for (int i = 0; i < rows.length(); i++) {
            try {
                JSONObject row = rows.getJSONObject(i), raw = new JSONObject(row.getString("value"));
                long at = number(raw, "time"); if (at <= 0) at = row.getLong("time");
                if (at < start || at >= end) continue;
                long value = number(raw, field);
                if (value < min || value > max) continue;
                Long old = dedup.get(at);
                if (old == null || old == value) dedup.put(at, value);
            } catch (Exception ignored) { }
        }
        ArrayList<long[]> out = new ArrayList<>();
        for (Map.Entry<Long, Long> e : dedup.entrySet()) out.add(new long[]{e.getKey(), e.getValue()});
        return out;
    }

    private static JSONObject range(List<long[]> points, long start, long end, String minKey, String maxKey) throws Exception {
        long min = Long.MAX_VALUE, max = Long.MIN_VALUE;
        for (long[] p : points) if (p[0] > start && p[0] <= end) { min = Math.min(min, p[1]); max = Math.max(max, p[1]); }
        if (min == Long.MAX_VALUE) return null;
        return new JSONObject().put(minKey, min).put(maxKey, max);
    }

    private static long number(JSONObject object, String key) {
        Object value = object.opt(key);
        if (!(value instanceof Number)) return 0;
        double d = ((Number)value).doubleValue();
        if (!Double.isFinite(d) || d != Math.rint(d)) return 0;
        return ((Number)value).longValue();
    }

    private static String stage(Object state) {
        long s = state instanceof Number ? ((Number)state).longValue() : -1;
        if (s == 2) return "deep";
        if (s == 3) return "light";
        if (s == 4) return "rem";
        if (s == 5) return "awake";
        return "unknown";
    }

    private static String iso(long second, TimeZone zone) {
        return Instant.ofEpochSecond(second).atZone(zone.toZoneId()).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
    }

    private static final class Metric {
        final String name, table, keys, field, unit; final int min, max;
        Metric(String name, String table, String keys, String field, String unit, int min, int max) {
            this.name = name; this.table = table; this.keys = keys; this.field = field; this.unit = unit; this.min = min; this.max = max;
        }
        static Metric of(String action) {
            if (action.startsWith("heart_rate_")) return new Metric("heart_rate", "hr_record", "key IN ('heart_rate','single_heart_rate')", "bpm", "次/分", 1, 300);
            if (action.startsWith("stress_")) return new Metric("stress", "stress_record", "key IN ('stress','single_stress')", "stress", "", 1, 100);
            if (action.startsWith("spo2_")) return new Metric("spo2", "spo2_record", "key IN ('spo2','single_spo2')", "spo2", "%", 1, 100);
            return null;
        }
    }
}

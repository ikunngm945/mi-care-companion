package cn.local.micompanion;

import java.io.File;
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
    private static final int MAX_WORKOUTS = 100;
    private static final int MAX_EXERCISE_DAYS = 31;
    private static final String[] SLEEP_KEYS = {"items", "sleepSegments"};

    static JSONObject query(HealthSource.Reader db, JSONObject request, long now, TimeZone zone) throws Exception {
        return query(db, request, now, zone, null);
    }

    static JSONObject query(HealthSource.Reader db, JSONObject request, long now, TimeZone zone, File fitnessRoot) throws Exception {
        if (db == null) throw new IllegalArgumentException("健康数据库不可用");
        String action = request.optString("action", "");
        if (action.startsWith("exercise_")) return exercise(db, action, request, now, zone, fitnessRoot);
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

    private static JSONObject exercise(HealthSource.Reader db, String action, JSONObject request, long now, TimeZone zone, File fitnessRoot) throws Exception {
        if ("exercise_detail".equals(action)) return exerciseDetail(db, request, now, zone, fitnessRoot);
        String startValue = request.optString("start_date", "").trim();
        String endValue = request.optString("end_date", "").trim();
        if (startValue.isEmpty() && endValue.isEmpty()) {
            startValue = endValue = Instant.ofEpochMilli(now).atZone(zone.toZoneId()).toLocalDate().toString();
        } else if (startValue.isEmpty()) {
            startValue = endValue;
        } else if (endValue.isEmpty()) {
            endValue = startValue;
        }
        LocalDate from = exerciseDate(startValue, "start_date");
        LocalDate to = exerciseDate(endValue, "end_date");
        if (to.isBefore(from)) throw new IllegalArgumentException("end_date 不能早于 start_date");
        if (to.toEpochDay() - from.toEpochDay() + 1 > MAX_EXERCISE_DAYS) {
            throw new IllegalArgumentException("查询范围最多31天");
        }
        ZoneId zid = zone.toZoneId();
        long start = from.atStartOfDay(zid).toEpochSecond();
        long end = to.plusDays(1).atStartOfDay(zid).toEpochSecond();
        if (start >= now / 1000L) {
            return new JSONObject().put("status", "missing").put("action", action)
                    .put("start_date", from.toString()).put("end_date", to.toString())
                    .put("timezone", zone.getID()).put("reason", "查询日期还没有开始");
        }
        JSONArray rows = db.query("SELECT value,time,0 AS updateTime,time,sid,key FROM sport_report "
                + "WHERE isDeleted=0 AND time>=? AND time<? ORDER BY time ASC LIMIT " + (MAX_WORKOUTS + 1),
                new String[]{Long.toString(start), Long.toString(end)});
        if (rows == null || rows.length() > MAX_WORKOUTS) throw new IllegalArgumentException("运动记录过多");
        TreeMap<Long, JSONObject> unique = new TreeMap<>();
        HashSet<String> seen = new HashSet<>();
        for (int i = 0; i < rows.length(); i++) {
            try {
                JSONObject item = workout(rows.getJSONObject(i), zone);
                if (item == null || !seen.add(item.getString("id"))) continue;
                unique.put(item.getLong("start_epoch_seconds"), item);
            } catch (Exception ignored) { }
        }
        JSONArray records = new JSONArray();
        for (JSONObject item : unique.values()) records.put(item);
        return new JSONObject().put("status", "ok").put("action", action)
                .put("start_date", from.toString()).put("end_date", to.toString())
                .put("timezone", zone.getID()).put("count", records.length()).put("records", records);
    }

    private static JSONObject exerciseDetail(HealthSource.Reader db, JSONObject request, long now, TimeZone zone, File fitnessRoot) throws Exception {
        String id = request.optString("id", "").trim();
        String[] parts = id.split(":");
        if (parts.length != 3) throw new IllegalArgumentException("id 必须是 开始时间:结束时间:运动类型");
        long start, end;
        int sportType;
        try {
            start = Long.parseLong(parts[0]);
            end = Long.parseLong(parts[1]);
            sportType = Integer.parseInt(parts[2]);
        } catch (Exception e) {
            throw new IllegalArgumentException("id 必须是 开始时间:结束时间:运动类型");
        }
        if (start <= 0 || end <= start) throw new IllegalArgumentException("id 的时间范围无效");
        JSONArray rows = db.query("SELECT value,time,0 AS updateTime,time,sid,key FROM sport_report "
                + "WHERE isDeleted=0 AND time=? ORDER BY time ASC LIMIT 21", new String[]{Long.toString(start)});
        JSONObject record = null;
        String sid = null;
        int protoType = 0;
        for (int i = 0; rows != null && i < rows.length(); i++) {
            try {
                JSONObject row = rows.getJSONObject(i);
                JSONObject item = workout(row, zone);
                if (item != null && id.equals(item.getString("id")) && item.getLong("end_epoch_seconds") == end) {
                    record = item;
                    sid = row.optString("sid", "");
                    protoType = new JSONObject(row.getString("value")).optInt("proto_type", 0);
                    break;
                }
            } catch (Exception ignored) { }
        }
        if (record == null) {
            return new JSONObject().put("status", "missing").put("action", "exercise_detail")
                    .put("id", id).put("timezone", zone.getID()).put("reason", "没有找到这条运动记录");
        }
        JSONObject out = new JSONObject(record.toString());
        out.put("status", "ok").put("action", "exercise_detail").put("timezone", zone.getID());
        JSONObject detail;
        try {
            detail = WorkoutDetail.read(fitnessRoot, sid, start, protoType > 0 ? protoType : 22);
        } catch (Exception e) {
            detail = new JSONObject().put("status", "error").put("reason", "运动详情解析失败");
        }
        if ("ok".equals(detail.optString("status")) && detail.has("max_stride_cm") && !detail.isNull("max_stride_cm")) {
            JSONObject stride = out.optJSONObject("stride");
            if (stride != null) stride.put("max_cm", detail.optLong("max_stride_cm"));
        }
        out.put("detail", detail);
        return out;
    }

    private static JSONObject workout(JSONObject row, TimeZone zone) throws Exception {
        JSONObject raw = new JSONObject(row.getString("value"));
        long start = number(raw, "start_time");
        if (start <= 0) start = row.optLong("time");
        long end = number(raw, "end_time");
        if (start <= 0 || end <= start) return null;
        long duration = number(raw, "duration");
        if (duration <= 0 || duration > 86400) duration = end - start;
        int sportType = (int) number(raw, "sport_type");
        String key = raw.optString("key", row.optString("key", ""));
        String id = start + ":" + end + ":" + sportType;
        JSONObject out = new JSONObject().put("id", id).put("sport", sportName(key))
                .put("sport_key", key).put("start", iso(start, zone)).put("end", iso(end, zone))
                .put("start_epoch_seconds", start).put("end_epoch_seconds", end)
                .put("duration_seconds", duration);
        number(out, raw, "distance_m", "distance", 0, 10000000);
        number(out, raw, "activity_kcal", "calories", 0, 1000000);
        number(out, raw, "total_kcal", "total_cal", 0, 1000000);
        number(out, raw, "steps", "steps", 0, 1000000);
        JSONObject pace = new JSONObject();
        number(pace, raw, "avg_seconds_per_km", "avg_pace", 0, 36000);
        number(pace, raw, "max_seconds_per_km", "max_pace", 0, 36000);
        JSONObject cadence = new JSONObject();
        number(cadence, raw, "avg_spm", "avg_cadence", 0, 300);
        number(cadence, raw, "max_spm", "max_cadence", 0, 300);
        JSONObject stride = new JSONObject();
        number(stride, raw, "avg_cm", "avg_stride", 0, 300);
        if (raw.has("max_stride")) number(stride, raw, "max_cm", "max_stride", 0, 300);
        JSONObject heart = new JSONObject();
        number(heart, raw, "avg_bpm", "avg_hrm", 1, 300);
        number(heart, raw, "max_bpm", "max_hrm", 1, 300);
        JSONObject zones = new JSONObject().put("warmup_seconds", number(raw, "hrm_warm_up_duration"))
                .put("fat_burn_seconds", number(raw, "hrm_fat_burning_duration"))
                .put("aerobic_seconds", number(raw, "hrm_aerobic_duration"))
                .put("anaerobic_seconds", number(raw, "hrm_anaerobic_duration"))
                .put("extreme_seconds", number(raw, "hrm_extreme_duration"));
        return out.put("pace", pace).put("cadence", cadence).put("stride", stride)
                .put("heart_rate", heart).put("heart_rate_zones", zones);
    }

    private static LocalDate exerciseDate(String value, String field) {
        try {
            return LocalDate.parse(value, DateTimeFormatter.ISO_LOCAL_DATE);
        } catch (Exception e) {
            throw new IllegalArgumentException(field + " 必须是 YYYY-MM-DD");
        }
    }

    private static void number(JSONObject out, JSONObject raw, String dest, String key, double lo, double hi) throws Exception {
        double value = raw.optDouble(key, Double.NaN);
        if (!Double.isNaN(value) && !Double.isInfinite(value) && value >= lo && value <= hi) {
            out.put(dest, value == Math.rint(value) ? (long) value : value);
        }
    }

    private static String sportName(String key) {
        if ("outdoor_running".equals(key)) return "户外跑步";
        if ("indoor_running".equals(key)) return "室内跑步";
        if ("walking".equals(key) || "outdoor_walking".equals(key)) return "户外步行";
        if ("indoor_walking".equals(key)) return "室内步行";
        if ("cycling".equals(key) || "outdoor_cycling".equals(key)) return "户外骑行";
        if ("indoor_cycling".equals(key)) return "室内骑行";
        if ("free_training".equals(key)) return "自由训练";
        if ("strength_training".equals(key)) return "力量训练";
        if ("swimming".equals(key)) return "游泳";
        return "运动";
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

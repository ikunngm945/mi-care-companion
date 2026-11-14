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
        if ("daily_summary".equals(action)) return dailySummary(db, request, now, zone);
        if (action.startsWith("exercise_")) return exercise(db, action, request, now, zone, fitnessRoot);
        if (action.endsWith("_summary") && (has(request, "start_date") || has(request, "end_date")))
            return rangeSummary(db, action, request, now, zone);
        LocalDate day = date(request.optString("date", ""), now, zone);
        String date = day.toString();
        ZoneId zid = zone.toZoneId();
        long dayStart = day.atStartOfDay(zid).toEpochSecond();
        long dayEnd = day.plusDays(1).atStartOfDay(zid).toEpochSecond();
        long[] window = window(request, dayStart, dayEnd);
        long start = window[0], end = window[1];
        if (dayStart >= now / 1000L) return missing(action, date, zone, "查询日期还没有开始");
        if (action.startsWith("sleep_")) return sleep(db, action, date, start, end, has(request, "start_time") || has(request, "end_time"), zone);
        Metric metric = Metric.of(action);
        if (metric == null) throw new IllegalArgumentException("未知查询类型");
        return vitals(db, action, metric, date, start, end, zone);
    }

    private static JSONObject dailySummary(HealthSource.Reader db, JSONObject request, long now, TimeZone zone) throws Exception {
        boolean range = has(request, "start_date") || has(request, "end_date");
        if ((range && has(request, "date")) || (range && (!has(request, "start_date") || !has(request, "end_date")))
                || has(request, "start_time") || has(request, "end_time"))
            throw new IllegalArgumentException("daily_summary 仅支持date或完整start_date/end_date，不支持时间段");
        if (range) return rangeSummary(db, "daily_summary", request, now, zone);
        LocalDate day = date(request.optString("date", ""), now, zone);
        long start = day.atStartOfDay(zone.toZoneId()).toEpochSecond();
        long end = day.plusDays(1).atStartOfDay(zone.toZoneId()).toEpochSecond();
        long observedEnd = Math.min(end, now / 1000L + 1);
        Metric heartMetric = Metric.of("heart_rate_summary");
        List<long[]> heart = points(db, heartMetric.table, heartMetric.keys, heartMetric.field, start, observedEnd, heartMetric.min, heartMetric.max);
        JSONObject out = new JSONObject().put("action", "daily_summary").put("date", day.toString()).put("timezone", zone.getID());
        JSONObject sleep = start >= now / 1000L ? missing("sleep_summary", day.toString(), zone, "查询日期还没有开始")
                : sleep(db, "sleep_summary", day.toString(), start, end, false, zone);
        out.put("sleep", sleep);
        boolean any = "ok".equals(sleep.optString("status"));
        for (String name : new String[]{"heart_rate", "stress", "spo2"}) {
            Metric metric = Metric.of(name + "_summary");
            List<long[]> values = "heart_rate".equals(name) ? heart : points(db, metric.table, metric.keys, metric.field, start, observedEnd, metric.min, metric.max);
            JSONObject summary = summarize(values, zone);
            JSONObject item = new JSONObject().put("status", values.isEmpty() ? "missing" : "ok")
                    .put("unit", metric.unit).put("summary", summary);
            sampling(item, values, heart, day, now, zone);
            out.put(name, item); any |= !values.isEmpty();
        }
        return out.put("status", any ? "ok" : "missing");
    }

    private static void sampling(JSONObject item, List<long[]> values, List<long[]> heart, LocalDate day, long now, TimeZone zone) throws Exception {
        List<long[]> hours = new ArrayList<>();
        List<Integer> positive = new ArrayList<>();
        ZonedDateTime until = day.plusDays(1).atStartOfDay(zone.toZoneId());
        for (ZonedDateTime hour = day.atStartOfDay(zone.toZoneId()); hour.isBefore(until); hour = hour.plusHours(1)) {
            ZonedDateTime next = hour.plusHours(1);
            if (next.isAfter(until) || next.toInstant().toEpochMilli() > now) break;
            long start = hour.toEpochSecond(), end = next.toEpochSecond();
            int count = count(values, start, end), heartCount = count(heart, start, end);
            hours.add(new long[]{start, end, count, heartCount});
            if (count > 0) positive.add(count);
        }
        JSONArray low = new JSONArray();
        item.put("low_sampling_periods", low).put("comparison_hours", hours.size()).put("typical_hourly_count", JSONObject.NULL);
        if (values.isEmpty()) { item.put("sampling_note", "当天没有可用数据，无法比较小时采样量，不推断原因"); return; }
        if (positive.size() < 2) { item.put("sampling_note", "有有效点的完整小时不足2个，无法比较；当前未完成小时和未来小时不参与比较"); return; }
        java.util.Collections.sort(positive);
        int n = positive.size();
        double typical = n % 2 == 1 ? positive.get(n/2) : (positive.get(n/2-1) + positive.get(n/2)) / 2.0;
        item.put("typical_hourly_count", typical).put("sampling_note", "以当天至少2个有有效点的完整小时点数中位数为基准，少于一半标为相对数据较少；不等于无数据。当前未完成小时和未来小时不参与比较；原因仅为可能，不能据此确定佩戴或设备状态");
        for (long[] h : hours) if (h[2] < typical / 2) {
            String hint = h[3] > 0 ? "该时段有心率记录，可能处于省电模式中或手部动作较大，也可能与测量设置、传感器状态或同步延迟等有关，不能确定原因"
                    : "该时段没有有效心率记录，可能未佩戴或佩戴不紧，也可能与设备电量、测量设置、传感器状态或同步延迟等有关，不能确定原因";
            JSONObject previous = low.length() == 0 ? null : low.getJSONObject(low.length()-1);
            if (previous != null && previous.getString("end").equals(iso(h[0], zone))
                    && (previous.getLong("heart_rate_count") > 0) == (h[3] > 0)) {
                previous.put("end", iso(h[1], zone)).put("count", previous.getLong("count") + h[2])
                        .put("heart_rate_count", previous.getLong("heart_rate_count") + h[3]);
            } else low.put(new JSONObject().put("start", iso(h[0], zone)).put("end", iso(h[1], zone))
                    .put("count", h[2]).put("heart_rate_count", h[3]).put("hint", hint));
        }
    }

    private static int count(List<long[]> values, long start, long end) {
        int n = 0;
        for (long[] p : values) if (p[0] >= start && p[0] < end) n++;
        return n;
    }

    private static JSONObject summarize(List<long[]> values, TimeZone zone) throws Exception {
        if (values.isEmpty()) return new JSONObject().put("count", 0).put("min", JSONObject.NULL).put("max", JSONObject.NULL)
                .put("average", JSONObject.NULL).put("first", JSONObject.NULL).put("last", JSONObject.NULL);
        long min = Long.MAX_VALUE, max = Long.MIN_VALUE, sum = 0;
        for (long[] p : values) { min = Math.min(min, p[1]); max = Math.max(max, p[1]); sum += p[1]; }
        return new JSONObject().put("count", values.size()).put("min", min).put("max", max)
                .put("average", Math.round(sum * 10.0 / values.size()) / 10.0)
                .put("first", iso(values.get(0)[0], zone)).put("last", iso(values.get(values.size()-1)[0], zone));
    }

    private static boolean has(JSONObject request, String key) {
        String value = request.optString(key, "").trim();
        return !value.isEmpty();
    }

    private static long[] window(JSONObject request, long dayStart, long dayEnd) {
        String from = request.optString("start_time", "").trim();
        String to = request.optString("end_time", "").trim();
        long start = from.isEmpty() ? dayStart : dayStart + clock(from, "start_time");
        long end = to.isEmpty() ? dayEnd : dayStart + clock(to, "end_time");
        if (end > dayEnd) end = dayEnd;
        if (start < dayStart || start >= end) throw new IllegalArgumentException("时间段必须在当天且开始早于结束");
        return new long[]{start, end};
    }

    private static int clock(String value, String name) {
        try {
            String[] parts = value.split(":");
            if (parts.length < 2 || parts.length > 3) throw new IllegalArgumentException(name + " 必须是 HH:mm");
            int hour = Integer.parseInt(parts[0]);
            int minute = Integer.parseInt(parts[1]);
            int second = parts.length == 3 ? Integer.parseInt(parts[2]) : 0;
            if (hour == 24 && minute == 0 && second == 0) return 86400;
            if (hour < 0 || hour > 23 || minute < 0 || minute > 59 || second < 0 || second > 59)
                throw new IllegalArgumentException(name + " 必须是 HH:mm");
            return hour * 3600 + minute * 60 + second;
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException(name + " 必须是 HH:mm");
        }
    }

    private static JSONObject rangeSummary(HealthSource.Reader db, String action, JSONObject request, long now, TimeZone zone) throws Exception {
        LocalDate from = date(request.optString("start_date", ""), now, zone);
        LocalDate to = date(request.optString("end_date", ""), now, zone);
        if (to.isBefore(from)) throw new IllegalArgumentException("end_date 不能早于 start_date");
        if (to.toEpochDay() - from.toEpochDay() + 1 > 31) throw new IllegalArgumentException("概括范围最多31天");
        JSONArray days = new JSONArray();
        boolean any = false;
        for (LocalDate day = from; !day.isAfter(to); day = day.plusDays(1)) {
            JSONObject dayRequest = new JSONObject(request.toString());
            dayRequest.remove("start_date");
            dayRequest.remove("end_date");
            dayRequest.put("date", day.toString());
            JSONObject result = query(db, dayRequest, now, zone, null);
            days.put(result);
            any |= "ok".equals(result.optString("status"));
        }
        return new JSONObject().put("status", any ? "ok" : "missing").put("action", action)
                .put("start_date", from.toString()).put("end_date", to.toString())
                .put("timezone", zone.getID()).put("count", days.length()).put("days", days);
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

    private static JSONObject sleep(HealthSource.Reader db, String action, String date, long start, long end, boolean timeWindow, TimeZone zone) throws Exception {
        boolean detail = "sleep_detail".equals(action);
        long dayStart = LocalDate.parse(date).atStartOfDay(zone.toZoneId()).toEpochSecond();
        long dayEnd = LocalDate.parse(date).plusDays(1).atStartOfDay(zone.toZoneId()).toEpochSecond();
        JSONArray rows = db.query("SELECT value,time,0 AS updateTime,endTime,sid,key FROM sleep_segment "
                + "WHERE isDeleted=0 AND key='sleep' AND endTime>? AND endTime<=? ORDER BY endTime ASC LIMIT " + (MAX_SESSIONS + 1),
                new String[]{Long.toString(dayStart), Long.toString(dayEnd + 60)});
        if (rows == null || rows.length() > MAX_SESSIONS) throw new IllegalArgumentException("睡眠记录过多");
        List<JSONObject> candidates = new ArrayList<>();
        long sampleStart = start;
        for (int i = 0; i < rows.length(); i++) {
            try {
                JSONObject raw = new JSONObject(rows.getJSONObject(i).getString("value"));
                long bed = number(raw, "bedtime"), wake = number(raw, "wake_up_time");
                if (bed <= 0 || wake <= bed || wake < dayStart || wake >= dayEnd) continue;
                if (timeWindow && (wake <= start || bed >= end)) continue;
                candidates.add(raw);
                if (!timeWindow) sampleStart = Math.min(sampleStart, bed);
            } catch (Exception ignored) { }
        }
        List<long[]> heart = points(db, "hr_record", "key IN ('heart_rate','single_heart_rate')", "bpm", sampleStart, end, 1, 300);
        List<long[]> oxygen = points(db, "spo2_record", "key IN ('spo2','single_spo2')", "spo2", sampleStart, end, 1, 100);
        TreeMap<Long, JSONObject> sessions = new TreeMap<>();
        HashSet<String> seen = new HashSet<>();
        for (JSONObject raw : candidates) {
            try {
                long bed = number(raw, "bedtime"), wake = number(raw, "wake_up_time");
                String signature = bed + ":" + wake + ":" + String.valueOf(raw.opt("items")) + ":" + String.valueOf(raw.opt("sleepSegments"));
                if (!seen.add(signature)) continue;
                JSONObject session = session(raw, bed, wake, timeWindow ? start : Math.min(start, bed), end, detail, heart, oxygen, zone);
                sessions.put(wake + (sessions.containsKey(wake) ? 1 : 0), session);
            } catch (Exception ignored) { }
        }
        if (sessions.isEmpty()) return missing(action, date, zone, "当天没有可用的睡眠记录");
        JSONArray out = new JSONArray();
        long total = 0, deep = 0, light = 0, rem = 0, awake = 0;
        boolean durationKnown = true, allStagesAvailable = true;
        for (JSONObject session : sessions.values()) {
            out.put(session);
            JSONObject s = session.getJSONObject("summary");
            durationKnown &= !s.isNull("total_minutes");
            allStagesAvailable &= s.optBoolean("stages_available", true);
            total += s.optLong("total_minutes", 0); deep += s.optLong("deep_minutes", 0);
            light += s.optLong("light_minutes", 0); rem += s.optLong("rem_minutes", 0);
            awake += s.optLong("awake_minutes", 0);
        }
        JSONObject result = new JSONObject().put("status", "ok").put("action", action).put("date", date)
                .put("timezone", zone.getID()).put("mode", detail ? "detail" : "summary")
                .put("sessions", out).put("totals", new JSONObject().put("total_minutes", durationKnown ? total : JSONObject.NULL)
                        .put("deep_minutes", deep).put("light_minutes", light).put("rem_minutes", rem).put("awake_minutes", awake)
                        .put("stages_available", allStagesAvailable));
        if (detail) {
            JSONArray all = new JSONArray();
            for (JSONObject session : sessions.values()) {
                JSONArray segments = session.optJSONArray("segments");
                for (int i = 0; segments != null && i < segments.length(); i++) all.put(segments.getJSONObject(i));
            }
            result.put("segments", all);
        }
        return result;
    }

    private static JSONObject session(JSONObject raw, long bed, long wake, long windowStart, long windowEnd,
                                      boolean detail, List<long[]> heart, List<long[]> oxygen, TimeZone zone) throws Exception {
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
                if (s < bed || e > wake || e <= s || e <= windowStart || s >= windowEnd) continue;
                s = Math.max(s, windowStart);
                e = Math.min(e, windowEnd);
                if (e <= s) continue;
                String stage = stage(item.opt("state"));
                long minutes = Math.round((e - s) / 60.0);
                stages = true;
                if ("deep".equals(stage)) deep += minutes; else if ("light".equals(stage)) light += minutes;
                else if ("rem".equals(stage)) rem += minutes; else if ("awake".equals(stage)) awake += minutes;
                if (detail) {
                    JSONObject segment = new JSONObject().put("start", iso(s, zone)).put("end", iso(e, zone))
                            .put("start_epoch_seconds", s).put("end_epoch_seconds", e)
                            .put("stage", stage).put("stage_label", stageLabel(stage)).put("duration_minutes", minutes);
                    JSONObject hr = range(heart, s, e, "min", "max");
                    JSONObject sp = range(oxygen, s, e, "min", "max");
                    segment.put("heart_rate", hr == null ? JSONObject.NULL : hr);
                    segment.put("spo2", sp == null ? JSONObject.NULL : sp);
                    segments.put(segment);
                }
            }
        }
        boolean wholeSession = windowStart <= bed && windowEnd >= wake;
        if (!stages && wholeSession) {
            deep = raw.optLong("sleep_deep_duration", 0); light = raw.optLong("sleep_light_duration", 0);
            rem = raw.optLong("sleep_rem_duration", 0); awake = raw.optLong("sleep_awake_duration", 0);
        }
        long total = deep + light + rem;
        boolean stagesAvailable = stages || total > 0;
        if (!stages && wholeSession) {
            long nativeMinutes = number(raw, "duration");
            if (nativeMinutes > 0 && nativeMinutes <= (wake - bed + 59) / 60) total = nativeMinutes;
        }
        Object totalMinutes = stages || (wholeSession && total > 0) ? total : JSONObject.NULL;
        JSONObject summary = new JSONObject().put("total_minutes", totalMinutes).put("deep_minutes", deep)
                .put("light_minutes", light).put("rem_minutes", rem).put("awake_minutes", awake)
                .put("stages_available", stagesAvailable);
        JSONObject hr = range(heart, bed, wake, "min", "max"); if (hr != null) {
            summary.put("heart_rate_min", hr.getInt("min")); summary.put("heart_rate_max", hr.getInt("max"));
        }
        JSONObject sp = range(oxygen, bed, wake, "min", "max"); if (sp != null) {
            summary.put("spo2_min", sp.getInt("min")); summary.put("spo2_max", sp.getInt("max"));
        }
        JSONObject out = new JSONObject().put("start", iso(bed, zone)).put("end", iso(wake, zone)).put("total_minutes", totalMinutes).put("summary", summary);
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
        long min = Long.MAX_VALUE, max = Long.MIN_VALUE, minAt = 0, maxAt = 0;
        int count = 0;
        for (long[] p : points) {
            if (p[0] < start || p[0] > end) continue;
            if (p[1] < min) { min = p[1]; minAt = p[0]; }
            if (p[1] > max) { max = p[1]; maxAt = p[0]; }
            count++;
        }
        if (count == 0) return null;
        return new JSONObject().put(minKey, min).put(maxKey, max)
                .put("start_epoch_seconds", start).put("end_epoch_seconds", end)
                .put("min_at_epoch_seconds", minAt).put("max_at_epoch_seconds", maxAt)
                .put("count", count);
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

    private static String stageLabel(String stage) {
        if ("deep".equals(stage)) return "深睡";
        if ("light".equals(stage)) return "浅睡";
        if ("rem".equals(stage)) return "快速眼动";
        if ("awake".equals(stage)) return "清醒";
        return "未知";
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

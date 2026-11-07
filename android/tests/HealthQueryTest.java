package cn.local.micompanion;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.TimeZone;
import org.json.JSONArray;
import org.json.JSONObject;

public final class HealthQueryTest {
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private static final long DAY = LocalDate.of(2026, 10, 31).atStartOfDay(ZONE).toEpochSecond();
    private static final long BED = DAY + 1 * 3600 + 49 * 60;
    private static final long WAKE = BED + 4 * 3600 + 49 * 60;

    private static JSONObject sleepValue() throws Exception {
        JSONArray items = new JSONArray()
                .put(new JSONObject().put("start_time", BED).put("end_time", BED + 20 * 60).put("state", 3))
                .put(new JSONObject().put("start_time", BED + 20 * 60).put("end_time", BED + 25 * 60).put("state", 5))
                .put(new JSONObject().put("start_time", BED + 2 * 3600 + 39 * 60).put("end_time", BED + 2 * 3600 + 59 * 60).put("state", 4))
                .put(new JSONObject().put("start_time", WAKE - 30 * 60).put("end_time", WAKE).put("state", 2));
        return new JSONObject().put("bedtime", BED).put("wake_up_time", WAKE).put("duration", 289)
                .put("sleep_deep_duration", 30).put("sleep_light_duration", 20).put("sleep_rem_duration", 20)
                .put("sleep_awake_duration", 5).put("items", items);
    }

    private static JSONArray row(String value, long time) throws Exception {
        return new JSONArray().put(new JSONObject().put("value", value).put("time", time)
                .put("updateTime", time).put("endTime", time).put("sid", "s").put("key", "k"));
    }

    private static HealthSource.Reader reader() {
        return (sql, args) -> {
            if (sql.contains("FROM sleep_segment")) return row(sleepValue().toString(), BED);
            if (sql.contains("FROM sport_report")) {
                long workoutStart = DAY + 8 * 3600;
                JSONObject sport = new JSONObject().put("start_time", workoutStart).put("end_time", workoutStart + 481)
                        .put("sport_type", 22).put("duration", 481).put("distance", 960).put("calories", 50)
                        .put("total_cal", 61).put("avg_pace", 501).put("max_pace", 285).put("avg_cadence", 148)
                        .put("max_cadence", 174).put("avg_stride", 88).put("avg_hrm", 149).put("max_hrm", 161)
                        .put("steps", 1133).put("hrm_warm_up_duration", 0).put("hrm_fat_burning_duration", 54)
                        .put("hrm_aerobic_duration", 408).put("hrm_anaerobic_duration", 0).put("hrm_extreme_duration", 0);
                return row(sport.toString(), workoutStart);
            }
            if (sql.contains("FROM hr_record")) {
                return new JSONArray().put(new JSONObject().put("value", new JSONObject().put("bpm", 60).put("time", BED + 5 * 60).toString()).put("time", BED + 5 * 60))
                        .put(new JSONObject().put("value", new JSONObject().put("bpm", 65).put("time", BED + 10 * 60).toString()).put("time", BED + 10 * 60))
                        .put(new JSONObject().put("value", new JSONObject().put("bpm", 55).put("time", BED + 2 * 3600 + 45 * 60).toString()).put("time", BED + 2 * 3600 + 45 * 60))
                        .put(new JSONObject().put("value", new JSONObject().put("bpm", 68).put("time", BED + 2 * 3600 + 50 * 60).toString()).put("time", BED + 2 * 3600 + 50 * 60));
            }
            if (sql.contains("FROM spo2_record")) {
                return new JSONArray().put(new JSONObject().put("value", new JSONObject().put("spo2", 97).put("time", BED + 2 * 3600 + 45 * 60).toString()).put("time", BED + 2 * 3600 + 45 * 60))
                        .put(new JSONObject().put("value", new JSONObject().put("spo2", 99).put("time", BED + 2 * 3600 + 50 * 60).toString()).put("time", BED + 2 * 3600 + 50 * 60));
            }
            if (sql.contains("FROM stress_record")) {
                return new JSONArray().put(new JSONObject().put("value", new JSONObject().put("stress", 36).put("time", BED + 2 * 3600).toString()).put("time", BED + 2 * 3600));
            }
            return new JSONArray();
        };
    }

    public static void main(String[] args) throws Exception {
        JSONObject detail = HealthQuery.query(reader(), new JSONObject().put("action", "sleep_detail").put("date", "2026-10-31"),
                (DAY + 86400) * 1000, TimeZone.getTimeZone(ZONE));
        check("ok".equals(detail.getString("status")), "sleep status");
        JSONObject session = detail.getJSONArray("sessions").getJSONObject(0);
        check(session.getJSONArray("segments").length() == 4, "all sleep segments");
        check(session.getJSONObject("summary").getLong("total_minutes") == 70, "sleep total excludes awake");
        JSONObject rem = session.getJSONArray("segments").getJSONObject(2);
        check("rem".equals(rem.getString("stage")), "REM stage");
        check(rem.getJSONObject("heart_rate").getInt("min") == 55, "segment HR range");
        check(rem.getJSONObject("spo2").getInt("min") == 97, "segment SpO2 range");
        JSONObject awake = session.getJSONArray("segments").getJSONObject(1);
        check("awake".equals(awake.getString("stage")) && awake.isNull("heart_rate") && awake.isNull("spo2"), "missing segment ranges are null");
        check(rem.getLong("start_epoch_seconds") == BED + 2 * 3600 + 39 * 60, "segment start epoch");
        check(rem.getLong("end_epoch_seconds") == BED + 2 * 3600 + 59 * 60, "segment end epoch");
        JSONObject remHr = rem.getJSONObject("heart_rate");
        check(remHr.getLong("start_epoch_seconds") == BED + 2 * 3600 + 39 * 60, "HR window start");
        check(remHr.getLong("end_epoch_seconds") == BED + 2 * 3600 + 59 * 60, "HR window end");
        check(remHr.getLong("min_at_epoch_seconds") == BED + 2 * 3600 + 45 * 60, "HR min time");

        JSONObject window = HealthQuery.query(reader(), new JSONObject().put("action", "sleep_detail")
                        .put("date", "2026-10-31").put("start_time", "01:49").put("end_time", "02:20"),
                (DAY + 86400) * 1000, TimeZone.getTimeZone(ZONE));
        JSONObject windowSession = window.getJSONArray("sessions").getJSONObject(0);
        check(windowSession.getJSONArray("segments").length() == 2, "time window filters sleep segments");

        JSONObject endOfDay = HealthQuery.query(reader(), new JSONObject().put("action", "sleep_detail")
                        .put("date", "2026-10-31").put("end_time", "24:00"),
                (DAY + 86400) * 1000, TimeZone.getTimeZone(ZONE));
        check(endOfDay.getJSONArray("sessions").length() == 1, "24:00 is the end of the day");

        JSONObject summary = HealthQuery.query(reader(), new JSONObject().put("action", "sleep_summary").put("date", "2026-10-31"),
                (DAY + 86400) * 1000, TimeZone.getTimeZone(ZONE));
        check(!summary.getJSONArray("sessions").getJSONObject(0).has("segments"), "summary omits segments");

        JSONObject ranged = HealthQuery.query(reader(), new JSONObject().put("action", "sleep_summary")
                        .put("start_date", "2026-10-31").put("end_date", "2026-11-01"),
                (DAY + 86400) * 1000, TimeZone.getTimeZone(ZONE));
        check("ok".equals(ranged.getString("status")) && ranged.getJSONArray("days").length() == 2, "range summary days");

        long hrAt = DAY + 8 * 3600 + 2 * 60;
        HealthSource.Reader vital = (sql, bound) -> sql.contains("FROM hr_record")
                ? new JSONArray().put(new JSONObject().put("value", new JSONObject().put("bpm", 82).put("time", hrAt).toString()).put("time", hrAt))
                  .put(new JSONObject().put("value", new JSONObject().put("bpm", 98).put("time", hrAt + 15 * 60).toString()).put("time", hrAt + 15 * 60))
                : new JSONArray();
        JSONObject heart = HealthQuery.query(vital, new JSONObject().put("action", "heart_rate_detail").put("date", "2026-10-31"),
                (DAY + 86400) * 1000, TimeZone.getTimeZone(ZONE));
        check(heart.getJSONObject("summary").getInt("min") == 82, "heart min");
        check(heart.getJSONArray("buckets").length() == 2, "15-minute buckets");
        check(heart.getJSONArray("buckets").getJSONObject(0).getString("start").endsWith("08:00:00+08:00"), "bucket start");

        JSONObject missing = HealthQuery.query((sql, bound) -> new JSONArray(), new JSONObject().put("action", "stress_summary").put("date", "2026-10-31"),
                (DAY + 86400) * 1000, TimeZone.getTimeZone(ZONE));
        check("missing".equals(missing.getString("status")), "missing not zero-filled");

        JSONObject exercises = HealthQuery.query(reader(), new JSONObject().put("action", "exercise_records")
                        .put("start_date", "2026-10-31").put("end_date", "2026-10-31"),
                (DAY + 86400) * 1000, TimeZone.getTimeZone(ZONE));
        check("ok".equals(exercises.getString("status")) && exercises.getInt("count") == 1, "exercise range");
        JSONObject workout = exercises.getJSONArray("records").getJSONObject(0);
        check(workout.getString("id").equals((DAY + 8 * 3600) + ":" + (DAY + 8 * 3600 + 481) + ":22"), "exercise id");
        check(workout.getJSONObject("pace").getInt("avg_seconds_per_km") == 501, "exercise pace");
        check(workout.getJSONObject("stride").getInt("avg_cm") == 88, "exercise stride");
        check(workout.getJSONObject("heart_rate_zones").getInt("aerobic_seconds") == 408, "exercise zones");
        JSONObject one = HealthQuery.query(reader(), new JSONObject().put("action", "exercise_detail")
                        .put("id", workout.getString("id")), (DAY + 86400) * 1000, TimeZone.getTimeZone(ZONE));
        check("ok".equals(one.getString("status")), "detail summary status");
        check("missing".equals(one.getJSONObject("detail").getString("status")), "detail file missing is contained");
        System.out.println("HealthQueryTest OK");
    }

    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
}

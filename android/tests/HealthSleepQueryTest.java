package cn.local.micompanion;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.TimeZone;
import org.json.JSONArray;
import org.json.JSONObject;

/** Regressions through the health query boundary used by MCP. */
public final class HealthSleepQueryTest {
    private static final TimeZone ZONE = TimeZone.getTimeZone("Asia/Shanghai");
    private static final long DAY = LocalDate.of(2026, 11, 12).atStartOfDay(ZoneId.of("Asia/Shanghai")).toEpochSecond();
    private static final long NOW = (DAY + 3 * 86400) * 1000;

    private static JSONObject sleep(long bed, long wake, boolean stages) throws Exception {
        JSONObject value = new JSONObject().put("bedtime", bed).put("wake_up_time", wake)
                .put("duration", (wake - bed) / 60);
        JSONArray items = new JSONArray();
        if (stages) items.put(new JSONObject().put("start_time", bed).put("end_time", wake).put("state", 3));
        return value.put("items", items);
    }

    private static HealthSource.Reader reader(JSONObject... values) {
        return (sql, args) -> {
            JSONArray rows = new JSONArray();
            if (sql.contains("FROM sleep_segment")) for (JSONObject value : values) {
                long wake = value.getLong("wake_up_time");
                rows.put(new JSONObject().put("value", value.toString()).put("time", wake)
                        .put("endTime", wake + 60).put("sid", "band").put("key", "sleep"));
            }
            return rows;
        };
    }

    private static JSONObject query(HealthSource.Reader reader, String action, String date) throws Exception {
        return HealthQuery.query(reader, new JSONObject().put("action", action).put("date", date), NOW, ZONE);
    }

    private static void wakeDateOwnsWholeSession() throws Exception {
        HealthSource.Reader source = reader(sleep(DAY - 3600, DAY + 6 * 3600, true),
                sleep(DAY + 23 * 3600, DAY + 30 * 3600, true));
        JSONObject result = query(source, "sleep_summary", "2026-11-12");
        check(result.getJSONArray("sessions").length() == 1, "next night's session must not appear on its bedtime date");
        check(result.getJSONObject("totals").getLong("total_minutes") == 420, "wake date includes the entire preceding night");
        JSONObject tomorrow = query(source, "sleep_summary", "2026-11-13");
        check(tomorrow.getJSONArray("sessions").length() == 1, "each session belongs to one wake date");
        check(tomorrow.getJSONObject("totals").getLong("total_minutes") == 420, "next date retains the full night");
    }

    private static void shortSleepRetainsNativeDurationWithoutInventingStages() throws Exception {
        HealthSource.Reader source = reader(sleep(DAY + 13 * 3600, DAY + 13 * 3600 + 25 * 60, false));
        for (String action : new String[]{"sleep_summary", "sleep_detail"}) {
            JSONObject result = query(source, action, "2026-11-12");
            check("ok".equals(result.getString("status")), "short sleep remains available in both query modes");
            check(result.getJSONObject("totals").getLong("total_minutes") == 25, "short sleep retains native 25 minutes");
            JSONObject session = result.getJSONArray("sessions").getJSONObject(0);
            check(!session.getJSONObject("summary").getBoolean("stages_available"), "missing stages are marked unavailable");
            if ("sleep_detail".equals(action)) check(session.getJSONArray("segments").length() == 0, "no fabricated short-sleep stages");
        }
    }

    private static void explicitEndOfDayClipsThePrecedingNight() throws Exception {
        HealthSource.Reader source = reader(sleep(DAY - 3600, DAY + 6 * 3600, true));
        JSONObject request = new JSONObject().put("action", "sleep_detail").put("date", "2026-11-12").put("end_time", "24:00");
        JSONObject result = HealthQuery.query(source, request, NOW, ZONE);
        check(result.getJSONObject("totals").getLong("total_minutes") == 360, "explicit day window excludes the preceding date's hour");
        check(result.getJSONArray("segments").getJSONObject(0).getLong("start_epoch_seconds") == DAY, "explicit window clips stage start");
        HealthSource.Reader midnight = reader(sleep(DAY + 23 * 3600, DAY + 86400, true));
        check("missing".equals(query(midnight, "sleep_summary", "2026-11-12").getString("status")), "midnight wake is excluded from previous day");
        check(query(midnight, "sleep_summary", "2026-11-13").getJSONObject("totals").getLong("total_minutes") == 60, "midnight wake belongs to the new day");
    }

    private static void crossNightSamplesFollowTheWholeSession() throws Exception {
        HealthSource.Reader sleeps = reader(sleep(DAY - 3600, DAY + 3600, true));
        HealthSource.Reader source = (sql, args) -> {
            if (sql.contains("FROM hr_record")) return new JSONArray()
                    .put(new JSONObject().put("time", DAY - 1800).put("value", new JSONObject().put("time", DAY - 1800).put("bpm", 55).toString()))
                    .put(new JSONObject().put("time", DAY + 1800).put("value", new JSONObject().put("time", DAY + 1800).put("bpm", 70).toString()));
            return sleeps.query(sql, args);
        };
        JSONObject result = query(source, "sleep_summary", "2026-11-12");
        JSONObject summary = result.getJSONArray("sessions").getJSONObject(0).getJSONObject("summary");
        check(summary.getInt("heart_rate_min") == 55 && summary.getInt("heart_rate_max") == 70, "whole-session samples include the preceding night");
    }

    private static void clippedUnstagedSleepDoesNotInventDuration() throws Exception {
        HealthSource.Reader source = reader(sleep(DAY + 13 * 3600, DAY + 13 * 3600 + 25 * 60, false));
        JSONObject result = HealthQuery.query(source, new JSONObject().put("action", "sleep_summary")
                .put("date", "2026-11-12").put("start_time", "13:10").put("end_time", "13:20"), NOW, ZONE);
        JSONObject session = result.getJSONArray("sessions").getJSONObject(0);
        check(session.isNull("total_minutes"), "without stages a partial window cannot claim a duration");
        check(result.getJSONObject("totals").isNull("total_minutes"), "unknown partial duration is not summed as zero");
    }

    public static void main(String[] args) throws Exception {
        wakeDateOwnsWholeSession();
        shortSleepRetainsNativeDurationWithoutInventingStages();
        explicitEndOfDayClipsThePrecedingNight();
        crossNightSamplesFollowTheWholeSession();
        clippedUnstagedSleepDoesNotInventDuration();
        System.out.println("HealthSleepQueryTest OK");
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}

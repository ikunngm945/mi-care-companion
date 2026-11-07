package cn.local.micompanion;

public final class SyncScheduleTest {
    public static void main(String[] args) {
        long now = 1_800_000_000_000L;
        check(SyncSchedule.WEATHER_MS == 30L * 60 * 1000, "weather is 30 minutes");
        check(SyncSchedule.HEALTH_MS == 10L * 60 * 1000, "health is 10 minutes");
        check(!SyncSchedule.due(0, now, SyncSchedule.HEALTH_MS), "unset is not due");
        check(!SyncSchedule.due(now - SyncSchedule.HEALTH_MS + 1, now, SyncSchedule.HEALTH_MS), "just before 10m");
        check(SyncSchedule.due(now - SyncSchedule.HEALTH_MS, now, SyncSchedule.HEALTH_MS), "at 10m");
        check(!SyncSchedule.due(now - SyncSchedule.WEATHER_MS + 1, now, SyncSchedule.WEATHER_MS), "just before 30m");
        check(SyncSchedule.due(now - SyncSchedule.WEATHER_MS, now, SyncSchedule.WEATHER_MS), "at 30m");
        System.out.println("SyncScheduleTest OK");
    }

    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
}

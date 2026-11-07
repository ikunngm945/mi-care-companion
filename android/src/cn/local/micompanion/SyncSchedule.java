package cn.local.micompanion;

/** Fixed local refresh cadence; sending remains driven by the idle/unlock trigger. */
final class SyncSchedule {
    static final long WEATHER_MS = 30L * 60 * 1000;
    static final long HEALTH_MS = 10L * 60 * 1000;

    private SyncSchedule() { }

    static boolean due(long last, long now, long interval) {
        return last > 0 && now >= last && now - last >= interval;
    }
}

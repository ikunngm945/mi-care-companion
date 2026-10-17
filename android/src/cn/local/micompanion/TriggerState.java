package cn.local.micompanion;

/** Monotonic milliseconds only. Manual submissions never touch this state. */
public final class TriggerState {
    public static final long OFF_MS = 10_800_000L, USE_MS = 600_000L;
    private long offSince = -1, usingSince = -1;
    private boolean armed;
    public void screenOff(long now) {
        if (offSince < 0) offSince = now;
        usingSince = -1;
    }
    public void screenOn(long now, boolean unlocked) {
        if (offSince >= 0 && now - offSince > OFF_MS) armed = true;
        offSince = -1;
        if (unlocked) unlock(now);
    }
    public void unlock(long now) {
        if (armed && usingSince < 0 && offSince < 0) usingSince = now;
    }
    public boolean tick(long now, boolean interactive, boolean unlocked) {
        return tick(now, interactive, unlocked, true);
    }
    /** Interruption tracking must run even when an older event blocks consumption. */
    public boolean tick(long now, boolean interactive, boolean unlocked, boolean consume) {
        if (!interactive) { screenOff(now); return false; }
        if (!unlocked) { usingSince = -1; return false; }
        if (armed && usingSince >= 0 && now - usingSince > USE_MS) {
            if (consume) { armed = false; usingSince = -1; }
            return true;
        }
        return false;
    }
    public void restart() { offSince = -1; usingSince = -1; }
    public boolean isArmed() { return armed; }
    public long offSinceMillis() { return offSince; }
    public long useMillis(long now) { return usingSince < 0 ? 0 : Math.max(0, now - usingSince); }
    public String encode() { return "1," + offSince + "," + usingSince + "," + armed; }
    public static TriggerState decode(String encoded) {
        TriggerState s = new TriggerState();
        try {
            String[] a = encoded.split(",");
            if (a.length != 4 || !"1".equals(a[0])) return s;
            long off = Long.parseLong(a[1]), use = Long.parseLong(a[2]);
            if (off < -1 || use < -1 || !("true".equals(a[3]) || "false".equals(a[3]))) return s;
            s.offSince = off; s.usingSince = use; s.armed = Boolean.parseBoolean(a[3]);
        } catch (RuntimeException ignored) { return new TriggerState(); }
        return s;
    }
}

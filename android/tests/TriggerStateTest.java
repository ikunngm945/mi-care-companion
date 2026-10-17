package cn.local.micompanion;
public final class TriggerStateTest {
    static int checks;
    static void check(boolean b, String name) { checks++; if (!b) throw new AssertionError(name); }
    public static void main(String[] args) {
        TriggerState s = new TriggerState();
        s.screenOff(0); s.screenOn(10_800_000L, true);
        check(!s.tick(11_400_001L, true, true), "exact off threshold must not arm");
        s.screenOff(20_000_000L); s.screenOn(30_800_001L, false);
        check(!s.tick(40_000_000L, true, false), "locked must not count");
        s.unlock(40_000_000L); s.unlock(40_100_000L);
        check(!s.tick(40_600_000L, true, true), "exact use threshold must not fire");
        s.screenOff(40_600_000L); s.screenOff(40_600_001L);
        s.screenOn(40_600_010L, true);
        check(!s.tick(41_200_010L, true, true), "A rule resets time");
        check(s.tick(41_200_011L, true, true), "A rule retains qualification");
        check(!s.tick(41_200_012L, true, true), "one event per round");
        TriggerState t = new TriggerState();
        t.screenOff(0); t.screenOff(5); t.screenOn(10_800_001L, true);
        String saved = t.encode();
        t = TriggerState.decode(saved);
        check(t.tick(11_400_002L, true, true), "serialize and duplicate off preserve start");
        t = TriggerState.decode(saved); t.restart(); t.screenOn(12_000_000L, true);
        check(!t.tick(12_600_000L, true, true), "restart clears continuous time");
        check(t.tick(12_600_001L, true, true), "restart retains proven qualification");
        TriggerState u = new TriggerState();
        u.screenOff(0); u.screenOn(10_800_001L, true);
        check(!u.tick(11_000_000L, true, false), "locking interrupts");
        u.unlock(12_000_000L);
        check(!u.tick(12_600_000L, true, true), "locking reset exact threshold");
        check(u.tick(12_600_001L, true, true), "unlock restart over threshold");
        check(!TriggerState.decode("broken").tick(99_000_000L, true, true), "corrupt state safe");
        TriggerState blocked = new TriggerState();
        blocked.screenOff(0); blocked.screenOn(10_800_001L, true);
        check(!blockedTick(blocked, 10_900_000L, true, false), "pending queue still observes lock");
        blocked.unlock(11_600_001L);
        check(!blocked.tick(11_600_001L, true, true), "pending lock interval must not count as use");
        check(blockedTick(blocked, 12_200_002L, true, true), "pending queue can observe readiness without consuming");
        check(blocked.isArmed(), "blocked queue must retain qualification");
        TriggerState candidate = TriggerState.decode(blocked.encode());
        check(candidate.tick(12_200_002L, true, true), "candidate can consume for atomic persistence");
        check(blocked.isArmed(), "failed candidate persistence leaves original qualification intact");
        check(blocked.tick(12_200_002L, true, true), "persisted candidate can be retried without losing event");
        System.out.println("TriggerState: " + checks + " checks passed");
    }
    private static boolean blockedTick(TriggerState state, long now, boolean interactive, boolean unlocked) {
        return state.tick(now, interactive, unlocked, false);
    }
}

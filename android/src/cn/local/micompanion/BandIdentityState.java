package cn.local.micompanion;

/** Device-page state for a single current pairing; changed snapshots always restore the mask. */
final class BandIdentityState {
    private BandConfig current;
    private boolean authKeyVisible;

    boolean update(BandConfig next) {
        if (current == next || (current != null && next != null
                && current.mac.equals(next.mac) && current.authKey.equals(next.authKey)
                && current.sourceScope.equals(next.sourceScope))) return false;
        current = next;
        authKeyVisible = false;
        return true;
    }

    BandConfig current() { return current; }

    boolean isAuthKeyVisible() { return authKeyVisible; }

    void toggleAuthKey() {
        if (current != null) authKeyVisible = !authKeyVisible;
    }

    String authKeyText(String unread) {
        if (current == null) return unread;
        return authKeyVisible ? current.authKey : "••••••••";
    }
}

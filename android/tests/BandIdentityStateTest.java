package cn.local.micompanion;

import android.content.SharedPreferences;
import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/** Uses only synthetic preference snapshots; no Android services or private files. */
public final class BandIdentityStateTest {
    private static final String MAC_A = "02:00:00:00:00:01", MAC_B = "02:00:00:00:00:02";
    private static final String KEY_A = "11111111111111111111111111111111";
    private static final String KEY_B = "22222222222222222222222222222222";
    private static final String UNREAD = "未读取";

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static BandConfig cached(String mac, String auth, String scope) {
        Map<String, String> values = new HashMap<>();
        if (mac != null) values.put(BandConfig.KEY_MAC, mac);
        if (auth != null) values.put(BandConfig.KEY_AUTH, auth);
        if (scope != null) values.put("source_scope", scope);
        return BandConfig.fromCachedPreferences(values);
    }

    private static void await(CountDownLatch latch, String phase) throws InterruptedException {
        check(latch.await(5, TimeUnit.SECONDS), phase + " did not complete");
    }

    private static void publicationWins(boolean synchronous) throws Exception {
        BandConfig old = cached(MAC_A, KEY_A, "source");
        BandConfig replacement = cached(MAC_B, KEY_B, "source");
        AtomicReference<BandConfig> published = new AtomicReference<>(old);
        BandIdentityState ui = new BandIdentityState();
        ui.update(old);
        ui.toggleAuthKey();
        CountDownLatch inPublication = new CountDownLatch(1);
        CountDownLatch finishPublication = new CountDownLatch(1);
        SharedPreferences.Editor editor = (SharedPreferences.Editor) Proxy.newProxyInstance(
                BandIdentityStateTest.class.getClassLoader(), new Class<?>[]{SharedPreferences.Editor.class},
                (proxy, method, args) -> {
                    check(method.getName().equals(synchronous ? "commit" : "apply"), "existing write mode is preserved");
                    inPublication.countDown();
                    await(finishPublication, "controlled publication");
                    published.set(replacement);
                    return synchronous ? Boolean.TRUE : null;
                });
        FutureTask<Void> writer = new FutureTask<>(() -> {
            BandConfig.publishCachedPreferences(editor, synchronous);
            return null;
        });
        Thread thread = new Thread(writer, "synthetic-pairing-publication");
        thread.setDaemon(true);
        thread.start();
        AtomicReference<String> copied = new AtomicReference<>();
        Runnable confirmAndCopy = () -> {
            if (!ui.update(published.get())) copied.set(ui.current().authKey);
        };
        try {
            await(inPublication, "writer entering publication");
            check(!BandConfig.tryWithCachedPairing(confirmAndCopy), "UI must not wait for a pending publication");
            check(copied.get() == null, "old key must not be copied while writer owns publication");
        } finally {
            finishPublication.countDown();
        }
        writer.get(5, TimeUnit.SECONDS);
        check(BandConfig.tryWithCachedPairing(confirmAndCopy), "UI may recheck once the writer is done");
        check(copied.get() == null && !ui.isAuthKeyVisible(), "changing keys masks and aborts the first stale action");
        check(BandConfig.tryWithCachedPairing(confirmAndCopy) && KEY_B.equals(copied.get()), "retry can only copy the new key");
    }

    public static void main(String[] args) throws Exception {
        BandIdentityState state = new BandIdentityState();
        check(state.current() == null && UNREAD.equals(state.authKeyText(UNREAD)), "unconfigured state");
        check(cached(MAC_A, null, null) == null, "missing key must not make a usable pairing");
        check(cached(null, KEY_A, null) == null, "orphan key must not make a usable pairing");

        check(state.update(cached(MAC_A, KEY_A, "source-a")), "first cached pair loads");
        check(MAC_A.equals(state.current().mac), "MAC matches first snapshot");
        check(!state.isAuthKeyVisible() && !state.authKeyText(UNREAD).contains(KEY_A), "key is masked by default");
        state.toggleAuthKey();
        check(state.isAuthKeyVisible() && KEY_A.equals(state.authKeyText(UNREAD)), "explicit reveal");
        check(!state.update(cached(MAC_A, KEY_A, "source-a")) && state.isAuthKeyVisible(), "unchanged snapshot does not reset reveal");
        state.toggleAuthKey();
        check(!state.isAuthKeyVisible() && !state.authKeyText(UNREAD).contains(KEY_A), "explicit hide");

        state.toggleAuthKey();
        check(state.update(cached(MAC_B, KEY_B, "source-a")), "device switch is detected before an action");
        check(!state.isAuthKeyVisible(), "device switch restores mask");
        check(MAC_B.equals(state.current().mac) && KEY_B.equals(state.current().authKey), "replacement gives only new pair");
        state.toggleAuthKey();
        check(state.update(cached(MAC_B, KEY_A, "source-a")) && !state.isAuthKeyVisible(), "same-MAC key rotation restores mask");
        state.toggleAuthKey();
        check(state.update(cached(MAC_B, KEY_A, "source-b")) && !state.isAuthKeyVisible(), "source account change restores mask");

        state.toggleAuthKey();
        check(state.update(null), "cache removal or leaving the page resets current pair");
        state.toggleAuthKey();
        check(state.current() == null && !state.isAuthKeyVisible() && UNREAD.equals(state.authKeyText(UNREAD)), "removed pair cannot be revealed or copied");
        check(state.update(cached(MAC_B, KEY_A, null)) && !state.isAuthKeyVisible(), "re-entry loads a masked pair");
        publicationWins(true);
        publicationWins(false);
        System.out.println("BandIdentityStateTest OK");
    }
}

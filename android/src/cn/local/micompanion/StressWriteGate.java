package cn.local.micompanion;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.os.Binder;
import android.os.Bundle;
import android.os.SystemClock;
import org.json.JSONObject;

/** A wire message can wake the module, but only a user save in this process can authorize a write. */
public final class StressWriteGate extends ContentProvider {
    static final Uri URI = Uri.parse("content://cn.local.micompanion.stress-writeback");
    private static Grant pending;

    static synchronized Grant open(JSONObject request) throws Exception {
        if (pending != null) throw new IllegalStateException("另一条压力记录正在写回，请稍候");
        pending = new Grant(request.getString("op"), request.toString());
        return pending;
    }

    static final class Grant implements AutoCloseable {
        final String op, request;
        final long expires = SystemClock.elapsedRealtime() + 15000;
        boolean used;
        JSONObject outcome;
        Grant(String op, String request) { this.op = op; this.request = request; }
        JSONObject outcome() { synchronized (StressWriteGate.class) { return outcome; } }
        boolean wasUsed() { synchronized (StressWriteGate.class) { return used; } }
        @Override public void close() {
            synchronized (StressWriteGate.class) { if (pending == this) pending = null; }
        }
    }

    @Override public Bundle call(String method, String op, Bundle args) {
        Bundle reply = new Bundle();
        if (getContext() == null || args == null || !("authorize".equals(method) || "complete".equals(method))) return reply;
        try {
            int healthUid = getContext().getPackageManager().getApplicationInfo("com.mi.health", 0).uid;
            if (Binder.getCallingUid() != healthUid || !"com.mi.health".equals(getCallingPackage())) return reply;
            synchronized (StressWriteGate.class) {
                Grant grant = pending;
                if (grant == null || !grant.op.equals(op) || !grant.request.equals(args.getString("request"))) return reply;
                if ("authorize".equals(method)) {
                    if (grant.used || SystemClock.elapsedRealtime() >= grant.expires) return reply;
                    grant.used = true;
                } else {
                    if (!grant.used || grant.outcome != null) return reply;
                    String status = args.getString("status", "");
                    if (!status.matches("ok|conflict|history_diverged|account|unsupported|failed")) return reply;
                    grant.outcome = new JSONObject().put("status", status).put("changed", args.getBoolean("changed"));
                }
                reply.putBoolean("allowed", true);
            }
        } catch (PackageManager.NameNotFoundException | org.json.JSONException ignored) { }
        return reply;
    }

    @Override public boolean onCreate() { return true; }
    @Override public Cursor query(Uri uri, String[] projection, String selection, String[] args, String sort) { return null; }
    @Override public String getType(Uri uri) { return null; }
    @Override public Uri insert(Uri uri, ContentValues values) { throw new UnsupportedOperationException(); }
    @Override public int update(Uri uri, ContentValues values, String selection, String[] args) { throw new UnsupportedOperationException(); }
    @Override public int delete(Uri uri, String selection, String[] args) { throw new UnsupportedOperationException(); }
}

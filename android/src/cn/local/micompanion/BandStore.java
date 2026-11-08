package cn.local.micompanion;

import android.content.Context;
import org.json.JSONObject;

/** Durable last successful direct-band result, kept out of the Mi Fitness database. */
final class BandStore {
    private static final String PREFS = "band_data";
    private static final String KEY_LAST = "last_sync";

    private BandStore() { }

    static void put(Context context, JSONObject value) {
        context.getSharedPreferences(PREFS, 0).edit().putString(KEY_LAST, value.toString()).apply();
    }

    static JSONObject read(Context context) {
        String raw = context.getSharedPreferences(PREFS, 0).getString(KEY_LAST, "");
        if (raw == null || raw.isEmpty()) return null;
        try { return new JSONObject(raw); } catch (Exception e) { return null; }
    }
}

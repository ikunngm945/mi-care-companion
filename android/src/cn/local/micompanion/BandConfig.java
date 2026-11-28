package cn.local.micompanion;

import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;
import org.json.JSONObject;

/** Reads the already-paired band identity and auth key from Mi Fitness' local device database. */
final class BandConfig {
    static final String PREFS = "band";
    static final String KEY_MAC = "mac";
    static final String KEY_AUTH = "auth_key";
    static final String KEY_MODEL = "model";
    private static final ReentrantLock CACHE_PUBLICATION = new ReentrantLock();
    final String mac;
    final String authKey;
    final String model;
    final String sourceScope;

    BandConfig(String mac, String authKey, String model) {
        this(mac, authKey, model, "");
    }

    BandConfig(String mac, String authKey, String model, String sourceScope) {
        this.mac = mac;
        this.authKey = authKey;
        this.model = model;
        this.sourceScope = sourceScope;
    }

    static BandConfig fromJson(JSONObject object) {
        String mac = object.optString("mac", "").trim().toUpperCase(Locale.US);
        String key = object.optString("key", object.optString("auth_key", "")).trim().toLowerCase(Locale.US);
        String model = object.optString("model", "").trim();
        if (!mac.matches("[0-9A-F]{2}(:[0-9A-F]{2}){5}") || !key.matches("[0-9a-f]{32}")) return null;
        return new BandConfig(mac, key, model, object.optString("source_scope", ""));
    }

    static BandConfig save(Context context, BandConfig config) {
        if (config == null) return null;
        SharedPreferences.Editor editor = context.getSharedPreferences(PREFS, 0).edit()
                .putString(KEY_MAC, config.mac).putString(KEY_AUTH, config.authKey)
                .putString(KEY_MODEL, config.model).putString("source_scope", config.sourceScope);
        publishCachedPreferences(editor, true);
        return config;
    }

    static void publishCachedPreferences(SharedPreferences.Editor editor, boolean synchronous) {
        CACHE_PUBLICATION.lock();
        try {
            if (synchronous) editor.commit();
            else editor.apply();
        } finally {
            CACHE_PUBLICATION.unlock();
        }
    }

    /** Keep a UI action's cache recheck and use together without making it wait for a writer. */
    static boolean tryWithCachedPairing(Runnable action) {
        if (!CACHE_PUBLICATION.tryLock()) return false;
        try {
            action.run();
            return true;
        } finally {
            CACHE_PUBLICATION.unlock();
        }
    }

    static BandConfig load(Context context) {
        // Read one snapshot: a concurrent sync can replace the MAC and key together.
        return fromCachedPreferences(context.getSharedPreferences(PREFS, 0).getAll());
    }

    static BandConfig fromCachedPreferences(Map<String, ?> values) {
        String mac = cachedString(values, KEY_MAC);
        String key = cachedString(values, KEY_AUTH);
        if (mac.isEmpty() || key.isEmpty()) return null;
        return new BandConfig(mac, key, cachedString(values, KEY_MODEL), cachedString(values, "source_scope"));
    }

    private static String cachedString(Map<String, ?> values, String field) {
        Object value = values.get(field);
        return value instanceof String ? (String) value : "";
    }

    /** Provisioning is explicit: it needs root once because Mi Fitness keeps its database private. */
    static BandConfig provision(Context context) throws Exception {
        File cache = new File(context.getCacheDir(), "band-provision");
        if (!cache.isDirectory() && !cache.mkdirs()) throw new IllegalStateException("无法创建手环配置缓存");
        File db = new File(cache, "device_db");
        // The app-triggered su process runs in the app's mount namespace, where Mi Fitness'
        // private directory is hidden. Enter the init mount namespace to make a one-time copy.
        String dbPath = cache.getAbsolutePath() + "/device_db";
        String walPath = cache.getAbsolutePath() + "/device_db-wal";
        String shmPath = cache.getAbsolutePath() + "/device_db-shm";
        String command = "mkdir -p " + cache.getAbsolutePath() + "; "
                + "rm -f " + dbPath + " " + walPath + " " + shmPath + "; "
                + "nsenter -t 1 -m -- /system/bin/cp /data/data/com.mi.health/databases/device_db " + dbPath + "; "
                + "nsenter -t 1 -m -- /system/bin/cp /data/data/com.mi.health/databases/device_db-wal " + walPath + " 2>/dev/null || true; "
                + "nsenter -t 1 -m -- /system/bin/cp /data/data/com.mi.health/databases/device_db-shm " + shmPath + " 2>/dev/null || true; "
                + "nsenter -t 1 -m -- /system/bin/chmod 644 " + dbPath + " " + walPath + " " + shmPath + " 2>/dev/null || true";
        run("su", "-c", command);
        if (!db.isFile()) throw new IllegalStateException("未找到运动健康的设备数据库");
        SQLiteDatabase database = null;
        try {
            database = SQLiteDatabase.openDatabase(db.getAbsolutePath(), null,
                    SQLiteDatabase.OPEN_READONLY | SQLiteDatabase.NO_LOCALIZED_COLLATORS);
            Cursor cursor = database.rawQuery("SELECT detail, extraValues FROM device", null);
            try {
                while (cursor.moveToNext()) {
                    for (int column = 0; column < 2; column++) {
                        String raw = cursor.getString(column);
                        if (raw == null || raw.isEmpty()) continue;
                        try {
                            JSONObject object = new JSONObject(raw);
                            String mac = object.optString("mac", "").trim().toUpperCase(Locale.US);
                            String key = object.optString("encrypt_key", "").trim();
                            String model = object.optString("model", "").trim();
                            if (mac.matches("[0-9A-F]{2}(:[0-9A-F]{2}){5}") && key.matches("[0-9a-fA-F]{32}")) {
                                BandConfig config = new BandConfig(mac, key.toLowerCase(Locale.US), model);
                                SharedPreferences.Editor editor = context.getSharedPreferences(PREFS, 0).edit()
                                        .putString(KEY_MAC, config.mac)
                                        .putString(KEY_AUTH, config.authKey)
                                        .putString(KEY_MODEL, config.model).remove("source_scope");
                                publishCachedPreferences(editor, false);
                                return config;
                            }
                        } catch (Exception ignored) { }
                    }
                }
            } finally {
                cursor.close();
            }
        } finally {
            if (database != null) database.close();
            deleteTree(cache);
        }
        throw new IllegalStateException("运动健康里没有可用的手环密钥");
    }

    static String run(String... command) throws Exception {
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        StringBuilder output = new StringBuilder();
        BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(), "UTF-8"));
        try {
            String line;
            while ((line = reader.readLine()) != null) output.append(line).append('\n');
        } finally {
            reader.close();
        }
        int code = process.waitFor();
        if (code != 0) throw new IllegalStateException("命令失败（" + code + "）：" + output.toString().trim());
        return output.toString();
    }

    private static void deleteTree(File file) {
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) for (File child : children) deleteTree(child);
        }
        file.delete();
    }
}

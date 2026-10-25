package cn.local.micompanion;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.SystemClock;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/** Lazily starts the two target apps just before an automatic event is collected. */
final class TargetRecovery {
    private static final long RETRY_MS = 60_000;
    private static final ExecutorService WORKER = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "mi-companion-recovery");
        thread.setDaemon(true);
        return thread;
    });
    private static final Object ROOT_LOCK = new Object();
    private static Boolean rootGranted;
    private static long rootCheckedAt;

    private TargetRecovery() { }

    /** Called only when an automatic event is about to be sent. */
    static boolean ensureForSend(Context context, LinkServer server, long timeoutMs) {
        if (server == null) return false;
        SharedPreferences prefs = prefs(context);
        if (server.status("weather").connected) prefs.edit().putBoolean("seen_weather", true).apply();
        if (server.status("health").connected) prefs.edit().putBoolean("seen_health", true).apply();
        boolean needWeather = prefs.getBoolean("seen_weather", false) || server.status("weather").everConnected;
        boolean needHealth = prefs.getBoolean("seen_health", false) || server.status("health").everConnected;
        if (!needWeather && !needHealth) return false;
        if (connected(server, needWeather, needHealth)) return true;
        if (!root(context) && !ShizukuShell.granted()) return false;
        if (needWeather && !server.status("weather").connected) launchNowSync(context, "weather");
        if (needHealth && !server.status("health").connected) launchNowSync(context, "health");
        long deadline = SystemClock.elapsedRealtime() + Math.max(0, timeoutMs);
        while (SystemClock.elapsedRealtime() < deadline) {
            if (connected(server, needWeather, needHealth)) return true;
            try { Thread.sleep(500); } catch (InterruptedException e) { Thread.currentThread().interrupt(); break; }
        }
        return connected(server, needWeather, needHealth);
    }

    private static boolean connected(LinkServer server, boolean weather, boolean health) {
        return (!weather || server.status("weather").connected)
                && (!health || server.status("health").connected);
    }

    static boolean launchNowSync(Context context, String kind) {
        try {
            if (!root(context) && !ShizukuShell.granted()) return false;
            launch(context, kind);
            return true;
        } catch (Exception e) {
            prefs(context).edit().putString("pull_error", "拉起失败：" + e.getClass().getSimpleName()).apply();
            return false;
        }
    }

    static String rootState(Context context) {
        return prefs(context).getString("root_state", "未检测");
    }

    static String privilegeText(Context context) {
        return "Root：" + rootState(context) + " · Shizuku：" + ShizukuShell.status(context);
    }

    static String requestRoot(Context context) {
        synchronized (ROOT_LOCK) {
            rootGranted = null;
            rootCheckedAt = 0;
        }
        boolean granted = root(context);
        return granted ? "Root 已授权，可在发送前自动拉起两个模块"
                : "Root 未授权；请在 Root 管理器中给本应用授权后再试";
    }

    static void launchNow(Context context, String kind) {
        Context app = context.getApplicationContext();
        WORKER.execute(() -> {
            try {
                if (!root(app) && !ShizukuShell.granted()) {
                    prefs(app).edit().putString("pull_error", "Root 和 Shizuku 都未授权").apply();
                    return;
                }
                launch(app, kind);
            } catch (Exception e) {
                prefs(app).edit().putString("pull_error", "拉起失败：" + e.getClass().getSimpleName()).apply();
            }
        });
    }

    private static boolean root(Context context) {
        SharedPreferences prefs = prefs(context);
        long now = SystemClock.elapsedRealtime();
        synchronized (ROOT_LOCK) {
            if (rootGranted != null && (rootGranted || now - rootCheckedAt < RETRY_MS)) return rootGranted;
            rootCheckedAt = now;
            try {
                rootGranted = run("su", "-c", "id").contains("uid=0");
            } catch (Exception e) {
                rootGranted = false;
            }
            prefs.edit().putString("root_state", rootGranted ? "已授权" : "未授权").apply();
            return rootGranted;
        }
    }

    private static void launch(Context context, String kind) throws Exception {
        String component = "weather".equals(kind)
                ? "com.miui.weather2/com.miui.weather2.ActivityWeatherMain"
                : "com.mi.health/com.xiaomi.fitness.main.MainActivity";
        String command = "am start -W -n " + component + " >/dev/null 2>&1; sleep 2; input keyevent 3";
        boolean preferShizuku = prefs(context).getBoolean("prefer_shizuku", false);
        if (preferShizuku && ShizukuShell.granted()) {
            try {
                ShizukuShell.run(command);
            } catch (Exception e) {
                if (!root(context)) throw e;
                run("su", "-c", command);
            }
        } else if (root(context)) {
            run("su", "-c", command);
        } else {
            ShizukuShell.run(command);
        }
        prefs(context).edit().putString("last_pull", kind + " " + System.currentTimeMillis()).apply();
    }

    private static String run(String... command) throws Exception {
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        StringBuilder out = new StringBuilder();
        Thread reader = new Thread(() -> {
            try (BufferedReader in = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = in.readLine()) != null) {
                    if (out.length() < 2000) out.append(line).append('\n');
                }
            } catch (Exception ignored) { }
        }, "mi-companion-su");
        reader.setDaemon(true);
        reader.start();
        if (!process.waitFor(8, TimeUnit.SECONDS)) {
            process.destroy();
            reader.join(500);
            throw new IllegalStateException("timeout");
        }
        reader.join(500);
        return out.toString();
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences("monitor", 0);
    }
}

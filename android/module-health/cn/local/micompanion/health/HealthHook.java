package cn.local.micompanion.health;

import android.app.Application;
import android.content.Context;
import android.database.sqlite.SQLiteDatabase;
import cn.local.micompanion.LocalCollector;
import cn.local.micompanion.hook.LinkClient;
import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;
import org.json.JSONObject;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/** Reads the health app only, then pushes the snapshot to the main app over loopback WebSocket. */
public final class HealthHook implements IXposedHookLoadPackage {
    private static final ScheduledExecutorService worker = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread thread = new Thread(r, "mi-companion-health");
        thread.setDaemon(true);
        return thread;
    });
    private static volatile Context target;
    private static volatile LinkClient client;
    private static volatile boolean started;

    @Override public void handleLoadPackage(final XC_LoadPackage.LoadPackageParam lp) throws Throwable {
        if (!"com.mi.health".equals(lp.packageName)) return;
        if (!lp.packageName.equals(lp.processName)) return;
        XposedBridge.hookAllMethods(SQLiteDatabase.class, "openDatabase", new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam param) {
                if (param.getResult() instanceof SQLiteDatabase) {
                    String path = ((SQLiteDatabase)param.getResult()).getPath();
                    if (path != null && path.endsWith("/fitness_summary") && path.contains("/com.mi.health/"))
                        LocalCollector.activeHealthPath = path;
                }
            }
        });
        HealthAboutInjector.install(lp.classLoader);
        XposedHelpers.findAndHookMethod(Application.class, "onCreate", new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam param) {
                target = (Context)param.thisObject;
                client = LinkClient.shared(target, "health");
                client.start();
                if (!started) {
                    started = true;
                    worker.scheduleWithFixedDelay(HealthHook::publish, 60, 60, TimeUnit.SECONDS);
                }
            }
        });
    }

    private static void publish() {
        Context context = target;
        LinkClient link = client;
        if (context == null) return;
        try {
            JSONObject snapshot = LocalCollector.collect(context, "health");
            if (link != null) link.send(snapshot);
        } catch (Throwable ex) {
            XposedBridge.log("MiCompanionHealth: snapshot publish failed (" + ex.getClass().getSimpleName() + ")");
        }
    }
}

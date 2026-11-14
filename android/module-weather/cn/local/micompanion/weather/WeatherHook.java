package cn.local.micompanion.weather;

import android.app.Application;
import android.content.Context;
import cn.local.micompanion.LocalCollector;
import cn.local.micompanion.hook.LinkClient;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.callbacks.XC_LoadPackage;
import org.json.JSONObject;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/** Reads the weather app only, then pushes the snapshot to the main app over loopback WebSocket. */
public final class WeatherHook implements IXposedHookLoadPackage {
    private static final ScheduledExecutorService worker = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread thread = new Thread(r, "mi-companion-weather");
        thread.setDaemon(true);
        return thread;
    });
    private static volatile Context target;
    private static volatile LinkClient client;
    private static volatile boolean started;
    private static ScheduledFuture<?> pending;

    @Override public void handleLoadPackage(final XC_LoadPackage.LoadPackageParam lp) throws Throwable {
        if (!"com.miui.weather2".equals(lp.packageName)) return;
        // Restrict collection to the primary source application process.
        if (!lp.packageName.equals(lp.processName)) return;
        WeatherSettingsInjector.install(lp.classLoader);
        XposedHelpers.findAndHookMethod(Application.class, "onCreate", new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam param) {
                target = (Context)param.thisObject;
                client = LinkClient.shared(target, "weather");
                WeatherCloudClient cloud = new WeatherCloudClient(target, lp.classLoader, WeatherHook::publishCloud);
                client.setQueryHandler(cloud::handle);
                client.setConnectedHandler(() -> worker.execute(WeatherHook::publish));
                client.start();
                if (!started) {
                    started = true;
                    worker.scheduleWithFixedDelay(WeatherHook::publish, 60, 60, TimeUnit.SECONDS);
                }
            }
        });
        try {
            Class<?> cls = XposedHelpers.findClass("com.miui.weather2.structures.WeatherData", lp.classLoader);
            XposedBridge.hookAllConstructors(cls, new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam param) { refresh(); }
            });
        } catch (Throwable ex) {
            XposedBridge.log("MiCompanionWeather: refresh hook unavailable; periodic collector remains");
        }
    }

    private static synchronized void refresh() {
        if (target == null || (pending != null && !pending.isDone())) return;
        pending = worker.schedule(WeatherHook::publish, 1500, TimeUnit.MILLISECONDS);
    }

    private static synchronized void publishCloud() throws Exception {
        Context context = target;
        LinkClient link = client;
        if (context == null || link == null) throw new IllegalStateException("weather_link_unavailable");
        JSONObject snapshot = LocalCollector.collect(context, "weather");
        if (!"ok".equals(snapshot.optString("status"))) throw new IllegalStateException("weather_snapshot_unavailable");
        // Only publish the existing allowlisted projection, never native response JSON.
        link.send(snapshot);
    }

    private static synchronized void publish() {
        Context context = target;
        LinkClient link = client;
        if (context == null) return;
        try {
            JSONObject snapshot = LocalCollector.collect(context, "weather");
            if (link != null) link.send(snapshot);
        } catch (Throwable ex) {
            XposedBridge.log("MiCompanionWeather: snapshot publish failed (" + ex.getClass().getSimpleName() + ")");
        }
    }
}

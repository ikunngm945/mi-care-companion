package cn.local.micompanion.weather;

import android.app.Activity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import java.util.HashSet;
import java.util.Set;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import cn.local.micompanion.hook.Injection;
import cn.local.micompanion.hook.InjectMatch;
import cn.local.micompanion.hook.LinkClient;
import cn.local.micompanion.hook.SettingsForm;

/** Adds a “数据连接设置” button at the top of the weather settings page. */
public final class WeatherSettingsInjector {
    private static final Set<String> logged = new HashSet<>();
    private WeatherSettingsInjector() { }

    public static void install(ClassLoader loader) {
        XC_MethodHook hook = new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam param) {
                if (!(param.thisObject instanceof Activity)) return;
                Activity activity = (Activity)param.thisObject;
                View decor = activity.getWindow().getDecorView();
                decor.post(() -> inject(activity, 0));
                decor.postDelayed(() -> inject(activity, 0), 600);
            }
        };
        XposedBridge.hookAllMethods(Activity.class, "onCreate", hook);
        XposedBridge.hookAllMethods(Activity.class, "onResume", hook);
    }

    private static void inject(Activity activity, int attempt) {
        try {
            if (activity.isFinishing()) return;
            String className = activity.getClass().getName();
            if (logged.add(className)) XposedBridge.log("MiCompanionWeather: activity " + className);
            if (!InjectMatch.weatherSettings(className, activity.getTitle())) return;
            ViewGroup content = activity.findViewById(android.R.id.content);
            if (content == null || Injection.TAG.equals(content.getTag())) return;
            if (content.getChildCount() != 1) {
                if (attempt < 3) activity.getWindow().getDecorView().postDelayed(() -> inject(activity, attempt + 1), 150);
                return;
            }

            LinkClient client = LinkClient.shared(activity, "weather");

            Button entry = new Button(activity);
            entry.setText("数据连接设置");
            entry.setAllCaps(false);
            entry.setTextSize(16);
            entry.setMinHeight(Injection.dp(activity, 52));
            entry.setOnClickListener(v -> SettingsForm.page(activity, client));

            if (!Injection.addTopEntry(activity, entry)) {
                XposedBridge.log("MiCompanionWeather: settings content is not a single child (" + className + ")");
                return;
            }
            XposedBridge.log("MiCompanionWeather: entry injected into " + className);
        } catch (Throwable t) {
            XposedBridge.log("MiCompanionWeather: inject failed (" + t.getClass().getSimpleName() + ")");
        }
    }

}

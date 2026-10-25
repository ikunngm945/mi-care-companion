package cn.local.micompanion.health;

import android.app.Activity;
import android.view.View;
import android.view.ViewGroup;
import java.util.HashSet;
import java.util.Set;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import cn.local.micompanion.hook.Injection;
import cn.local.micompanion.hook.InjectMatch;
import cn.local.micompanion.hook.LinkClient;
import cn.local.micompanion.hook.SettingsForm;

/** Replaces the health app's “关于” content with the loopback connection settings form. */
public final class HealthAboutInjector {
    private static final Set<String> logged = new HashSet<>();
    private HealthAboutInjector() { }

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
            if (logged.add(className)) XposedBridge.log("MiCompanionHealth: activity " + className);
            if (!InjectMatch.healthAbout(className, activity.getTitle())) return;
            ViewGroup content = activity.findViewById(android.R.id.content);
            if (content == null || Injection.TAG.equals(content.getTag())) return;
            if (content.getChildCount() == 0) {
                if (attempt < 3) activity.getWindow().getDecorView().postDelayed(() -> inject(activity, attempt + 1), 150);
                return;
            }
            View form = SettingsForm.inline(activity, LinkClient.shared(activity, "health"), "数据连接设置（已替换“关于”页）");
            if (Injection.replaceContent(activity, form))
                XposedBridge.log("MiCompanionHealth: about page replaced in " + className);
        } catch (Throwable t) {
            XposedBridge.log("MiCompanionHealth: inject failed (" + t.getClass().getSimpleName() + ")");
        }
    }
}

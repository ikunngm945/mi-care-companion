package cn.local.micompanion.hook;

import java.util.Locale;

/** Activity matching kept separate so the rules can be unit-tested and tightened on device. */
public final class InjectMatch {
    private InjectMatch() { }

    public static boolean weatherSettings(String className) {
        return weatherSettings(className, null);
    }

    public static boolean weatherSettings(String className, CharSequence title) {
        if (className == null || !className.startsWith("com.miui.weather2.")) return false;
        String simple = simple(className);
        if (simple.toLowerCase(Locale.US).contains("setting") || "ActivitySet".equals(simple)) return true;
        String text = title == null ? "" : title.toString().trim();
        return "设置".equals(text) || "天氣設置".equals(text) || "天气设置".equals(text);
    }

    public static boolean healthAbout(String className, CharSequence title) {
        if (className == null || !(className.startsWith("com.mi.health.") || className.startsWith("com.xiaomi.fitness."))) return false;
        if (simple(className).toLowerCase(Locale.US).contains("about")) return true;
        String text = title == null ? "" : title.toString().trim();
        return "关于".equals(text) || "關於".equals(text) || text.startsWith("关于") || text.startsWith("關於") || "About".equalsIgnoreCase(text);
    }

    static String simple(String className) {
        int dot = className.lastIndexOf('.');
        return dot < 0 ? className : className.substring(dot + 1);
    }
}

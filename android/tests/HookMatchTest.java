package cn.local.micompanion;

import cn.local.micompanion.hook.InjectMatch;

public final class HookMatchTest {
    private static int checks;
    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
        checks++;
    }
    public static void main(String[] args) {
        check(InjectMatch.weatherSettings("com.miui.weather2.ActivitySet"), "天气设置页真实类名");
        check(InjectMatch.weatherSettings("com.miui.weather2.ui.settings.WeatherSettingsActivity"), "setting 类名");
        check(InjectMatch.weatherSettings("com.miui.weather2.setting.SettingsActivity"), "setting 类名");
        check(!InjectMatch.weatherSettings("com.miui.weather2.MainActivity"), "非设置页");
        check(InjectMatch.weatherSettings("com.miui.weather2.ui.MainSettingsPage", "设置"), "标题兜底");
        check(!InjectMatch.weatherSettings("com.example.SettingsActivity"), "其他应用");
        check(!InjectMatch.weatherSettings(null), "null 类名");
        check(InjectMatch.healthAbout("com.xiaomi.fitness.about.AboutActivity", null), "健康关于页真实类名");
        check(InjectMatch.healthAbout("com.mi.health.about.AboutActivity", null), "健康关于页旧类名");
        check(InjectMatch.healthAbout("com.mi.health.settings.SettingsActivity", "关于"), "标题兜底");
        check(InjectMatch.healthAbout("com.mi.health.settings.SettingsActivity", " 關於 "), "繁体标题兜底");
        check(!InjectMatch.healthAbout("com.mi.health.MainActivity", "首页"), "非关于页");
        check(!InjectMatch.healthAbout("com.mi.health.MainActivity", null), "无标题非关于页");
        check(!InjectMatch.healthAbout("com.example.about.AboutActivity", null), "其他应用同名页面");
        System.out.println("HookMatch: " + checks + " checks passed");
    }
}

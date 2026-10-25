package cn.local.micompanion.hook;

import android.content.Context;
import android.content.SharedPreferences;
import cn.local.micompanion.LinkProtocol;

/** Port and token are stored inside the target app's private preferences. */
public final class HookConfig {
    private static final String FILE = "mi_companion_link";
    private HookConfig() { }

    public static int port(Context context) {
        int port = prefs(context).getInt("port", LinkProtocol.DEFAULT_PORT);
        return LinkProtocol.validPort(port) ? port : LinkProtocol.DEFAULT_PORT;
    }

    public static String token(Context context) {
        return prefs(context).getString("token", "");
    }

    public static boolean configured(Context context) {
        return LinkProtocol.validToken(token(context));
    }

    public static void validate(int port, String token) {
        if (!LinkProtocol.validPort(port)) throw new IllegalArgumentException("端口须在 1024–65535 之间");
        if (!LinkProtocol.validToken(token)) throw new IllegalArgumentException("Token 必须是 16 位字母或数字");
    }

    public static void save(Context context, int port, String token) {
        validate(port, token);
        if (!prefs(context).edit().putInt("port", port).putString("token", token.trim()).commit())
            throw new IllegalStateException("保存失败");
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }
}

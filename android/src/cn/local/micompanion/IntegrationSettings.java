package cn.local.micompanion;

import android.content.Context;
import android.content.SharedPreferences;

/** Independent opt-ins; neither toggle changes the configured credentials or monitoring mode. */
final class IntegrationSettings {
    private static final String ASTRBOT = "astrbot_enabled", MCP = "mcp_enabled";
    private static long astrbotRevision;

    private IntegrationSettings() { }
    private static SharedPreferences preferences(Context context) {
        return context.getSharedPreferences("integrations", Context.MODE_PRIVATE);
    }
    static boolean astrbotEnabled(Context context) { return preferences(context).getBoolean(ASTRBOT, true); }
    static boolean mcpEnabled(Context context) { return preferences(context).getBoolean(MCP, false); }

    static synchronized long astrbotTicket(Context context) {
        return astrbotEnabled(context) ? astrbotRevision : -1;
    }
    static synchronized boolean permitsAstrbot(Context context, long ticket) {
        return ticket >= 0 && ticket == astrbotRevision && astrbotEnabled(context);
    }
    static synchronized boolean setAstrbotEnabled(Context context, boolean enabled) {
        boolean previous = astrbotEnabled(context);
        if (enabled == previous) return true;
        if (!save(context, ASTRBOT, enabled, previous)) return false;
        astrbotRevision++;
        MonitorService.refreshAstrbot();
        return true;
    }
    static boolean setMcpEnabled(Context context, boolean enabled) {
        if (!save(context, MCP, enabled, mcpEnabled(context))) return false;
        MonitorService.refreshMcp();
        return true;
    }
    private static boolean save(Context context, String key, boolean value, boolean previous) {
        SharedPreferences settings = preferences(context);
        if (settings.edit().putBoolean(key, value).commit()) return true;
        // A failed commit may still have changed the in-memory preference.
        settings.edit().putBoolean(key, previous).apply();
        return false;
    }
}

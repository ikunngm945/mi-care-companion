package cn.local.micompanion;

import android.content.Context;
import org.json.JSONException;
import org.json.JSONObject;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * In-process snapshot store. Replaces the exported ContentProvider now that the
 * two injected modules deliver data over the authenticated loopback WebSocket.
 */
public final class SnapshotStore {
    private SnapshotStore() { }

    public static JSONObject read(Context c, String kind) {
        JSONObject base = UsageCollector.base(LinkProtocol.sourcePackage(kind) == null ? "unknown" : LinkProtocol.sourcePackage(kind));
        try {
            String raw = c.getSharedPreferences("snapshots", 0).getString(kind, "");
            if (raw.isEmpty()) return base.put("reason", "未收到模块数据；请安装并启用对应模块，检查端口与 Token");
            return refreshSnapshot(new JSONObject(raw), kind, System.currentTimeMillis());
        } catch (Exception ex) {
            try { base.put("status", "error").put("reason", "快照读取失败"); } catch (JSONException ignored) { }
            return base;
        }
    }

    static JSONObject refreshSnapshot(JSONObject o, String kind, long now) throws JSONException {
        if (!"health".equals(kind)) o.put("collected_at", UsageCollector.iso(now));
        return SnapshotFreshness.refresh(o, kind, now);
    }

    public static void validateSize(String raw) {
        if (raw == null || raw.getBytes(StandardCharsets.UTF_8).length > 256 * 1024)
            throw new IllegalArgumentException("无效快照：超过 256 KiB");
    }

    /** Validates the untrusted module payload and persists it atomically. */
    public static void put(Context c, String kind, JSONObject snapshot) throws Exception {
        String expected = LinkProtocol.sourcePackage(kind);
        if (expected == null) throw new IllegalArgumentException("未知数据类别");
        if (snapshot == null) throw new IllegalArgumentException("快照为空");
        String raw = snapshot.toString();
        validateSize(raw);
        String status = snapshot.getString("status");
        if (!Arrays.asList("ok", "missing", "stale", "error").contains(status)
                || !expected.equals(snapshot.getString("source_package")))
            throw new IllegalArgumentException("无效快照结构");
        if (snapshot.length() != 6 || !(snapshot.get("fields") instanceof JSONObject))
            throw new IllegalArgumentException("无效快照结构");
        snapshot.get("source_updated_at");
        snapshot.get("reason");
        snapshot.getString("collected_at");
        if (!c.getSharedPreferences("snapshots", 0).edit().putString(kind, snapshot.toString()).commit())
            throw new IllegalStateException("快照存储失败");
    }
}

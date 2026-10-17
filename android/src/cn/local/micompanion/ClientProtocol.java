package cn.local.micompanion;

import java.net.URI;
import java.net.URISyntaxException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;
import java.util.UUID;

/** Android-independent validation and event metadata shared by every sending path. */
public final class ClientProtocol {
    private ClientProtocol() { }
    public static String baseUrl(String value, boolean allowHttp) {
        if (value == null) throw new IllegalArgumentException("请填写 AstrBot base URL");
        String text = value.trim();
        while (text.endsWith("/")) text = text.substring(0, text.length() - 1);
        try {
            URI u = new URI(text);
            String scheme = u.getScheme();
            if (!("https".equals(scheme) || (allowHttp && "http".equals(scheme))))
                throw new IllegalArgumentException("默认必须使用 HTTPS；HTTP 需要显式开启");
            if (u.getHost() == null || u.getHost().isEmpty() || u.getRawUserInfo() != null
                    || u.getRawQuery() != null || u.getRawFragment() != null
                    || u.getPort() > 65535 || u.getPort() == 0
                    || !("/api/v1".equals(u.getRawPath()) || "/v1".equals(u.getRawPath()) || "".equals(u.getRawPath())))
                throw new IllegalArgumentException("URL 请填写 AstrBot 官方接口 https://用户域名/api/v1，不得含凭据、查询或片段");
            // Accept the user's old /v1 spelling, but always call the actual official prefix.
            return new URI(u.getScheme(), null, u.getHost(), u.getPort(), "/api/v1", null, null).toASCIIString();
        } catch (URISyntaxException e) { throw new IllegalArgumentException("URL 格式无效"); }
    }
    public static boolean withinSubmissionWindow(long created, long now) {
        return created >= now - 86400000L && created <= now + 300000L;
    }
    public static long eventTime(boolean manual, long pendingCreated, long now) {
        if (manual) return now;
        if (pendingCreated <= 0 || !withinSubmissionWindow(pendingCreated, now))
            throw new IllegalArgumentException("原自动触发已过期或时间证据缺失，不创建假当前事件");
        return pendingCreated;
    }
    public static String timestamp(long millis) {
        SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US);
        f.setTimeZone(TimeZone.getTimeZone("UTC"));
        return f.format(new Date(millis));
    }
    public static Map<String,Object> category(Map<String,Object> source, String pkg, long now) {
        Map<String,Object> result = new LinkedHashMap<>();
        Object status = source == null ? null : source.get("status");
        if (!("ok".equals(status) || "missing".equals(status) || "stale".equals(status) || "error".equals(status))) status = "missing";
        result.put("status", status);
        result.put("source_package", pkg);
        result.put("collected_at", timestamp(now));
        result.put("source_updated_at", source == null ? null : source.get("source_updated_at"));
        Object fields = source == null ? null : source.get("fields");
        result.put("fields", fields instanceof Map ? fields : new LinkedHashMap<String,Object>());
        result.put("reason", source == null ? "尚未读取到源数据；源更新时间未知" : source.get("reason"));
        return result;
    }
    public static Map<String,Object> metadata(boolean manual, String umo, long now) {
        Map<String,Object> result = new LinkedHashMap<>();
        result.put("protocol_version", 1);
        result.put("event_id", UUID.randomUUID().toString());
        Map<String,Object> trigger = new LinkedHashMap<>();
        trigger.put("type", manual ? "manual_test" : "long_idle");
        // Exact automatic timing evidence is owned by MonitorService; never invent wall-clock intervals.
        result.put("trigger", trigger);
        result.put("collected_at", timestamp(now));
        result.put("timezone", TimeZone.getDefault().getID());
        result.put("umo", umo);
        return result;
    }
}

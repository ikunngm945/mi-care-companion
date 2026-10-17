package cn.local.micompanion;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.UUID;

/** No Android runtime or mocking framework: exercises the production protocol helper. */
public final class ClientProtocolTest {
    private static Class<?> protocol;
    private static Object call(String name, Class<?>[] types, Object... args) throws Exception {
        try { return protocol.getDeclaredMethod(name, types).invoke(null, args); }
        catch (InvocationTargetException e) {
            if (e.getCause() instanceof IllegalArgumentException) throw (IllegalArgumentException)e.getCause();
            throw e;
        }
    }
    private static String url(String value, boolean http) throws Exception {
        return (String)call("baseUrl", new Class<?>[]{String.class, boolean.class}, value, http);
    }
    private static void rejects(String value, boolean http) throws Exception {
        try { url(value, http); throw new AssertionError("Accepted unsafe URL: " + value); }
        catch (IllegalArgumentException expected) { }
    }
    public static void main(String[] args) throws Exception {
        try { protocol = Class.forName("cn.local.micompanion.ClientProtocol"); }
        catch (ClassNotFoundException e) { throw new AssertionError("Missing production URI/protocol validation", e); }
        // A validator that accepts userinfo, HTTP without opt-in, or a query would leak credentials.
        assert "https://bot.example/api/v1".equals(url(" https://bot.example/api/v1/ ", false));
        assert "http://127.0.0.1:6185/api/v1".equals(url("http://127.0.0.1:6185/v1", true));
        assert "https://bot.example/api/v1".equals(url("https://bot.example", false));
        rejects("https://bot.example/mi-companion", false);
        rejects("http://bot.example/mi-companion", false);
        rejects("https://user:secret@bot.example/mi-companion", false);
        rejects("https://bot.example/mi-companion#fragment", false);
        rejects("https://bot.example/mi-companion?key=secret", false);
        rejects("https://bot.example/v1/chat/completions", false);
        rejects("file:///mi-companion", false);
        rejects("https://bot.example/mi-companion/../other", false);
        rejects("https://bot.example:99999/mi-companion", false);
        rejects("https://bot.example/mi-companion%2f", false);
        rejects("", false);
        // The manual path derives metadata without receiving or mutating any monitor state.
        @SuppressWarnings("unchecked") Map<String,Object> manual = (Map<String,Object>)call("metadata",
                new Class<?>[]{boolean.class, String.class, long.class}, true, "adapter:FriendMessage:123", 0L);
        assert Integer.valueOf(1).equals(manual.get("protocol_version"));
        assert "adapter:FriendMessage:123".equals(manual.get("umo"));
        assert "manual_test".equals(((Map<?,?>)manual.get("trigger")).get("type"));
        assert "1970-01-01T00:00:00.000Z".equals(manual.get("collected_at"));
        UUID.fromString((String)manual.get("event_id"));
        @SuppressWarnings("unchecked") Map<String,Object> second = (Map<String,Object>)call("metadata",
                new Class<?>[]{boolean.class, String.class, long.class}, false, "adapter:FriendMessage:123", 0L);
        assert !manual.get("event_id").equals(second.get("event_id"));
        assert "long_idle".equals(((Map<?,?>)second.get("trigger")).get("type"));
        assert ((String)manual.get("timezone")).length() > 0;
        @SuppressWarnings("unchecked") Map<String,Object> absent = (Map<String,Object>)call("category", new Class<?>[]{Map.class, String.class, long.class}, null, "com.mi.health", 0L);
        assert "missing".equals(absent.get("status"));
        assert absent.get("source_updated_at") == null;
        assert ((Map<?,?>)absent.get("fields")).isEmpty();
        java.util.Map<String,Object> source = new java.util.HashMap<>();
        source.put("status", "ok"); source.put("fields", java.util.Collections.singletonMap("steps", 0));
        source.put("untrusted_extra", "must not enter top-level category");
        @SuppressWarnings("unchecked") Map<String,Object> zero = (Map<String,Object>)call("category", new Class<?>[]{Map.class, String.class, long.class}, source, "com.mi.health", 0L);
        assert "ok".equals(zero.get("status"));
        assert Integer.valueOf(0).equals(((Map<?,?>)zero.get("fields")).get("steps"));
        assert zero.size() == 6 && !zero.containsKey("untrusted_extra");
        assert Boolean.TRUE.equals(call("withinSubmissionWindow", new Class<?>[]{long.class, long.class}, 0L, 86400000L));
        assert Boolean.FALSE.equals(call("withinSubmissionWindow", new Class<?>[]{long.class, long.class}, 0L, 86400001L));
        assert Boolean.FALSE.equals(call("withinSubmissionWindow", new Class<?>[]{long.class, long.class}, 600001L, 0L));
        System.out.println("ClientProtocolTest: PASS (URI security, UUID, manual/automatic metadata, timezone, missing/zero/schema, expiry)");
    }
}

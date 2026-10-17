package cn.local.micompanion;
import org.json.JSONObject;

/** Payload is encoded with the same JSON implementation used by the APK build. */
public final class ClientPayloadTest {
    public static void main(String[] args) throws Exception {
        JSONObject body = new JSONObject(ClientProtocol.metadata(true, "local:FriendMessage:1", 0L));
        body.put("weather", EventClient.categoryJson(ClientProtocol.category(null, "com.miui.weather2", 0L)));
        body.put("screen", EventClient.categoryJson(ClientProtocol.category(null, "android", 0L)));
        body.put("health", EventClient.categoryJson(ClientProtocol.category(null, "com.mi.health", 0L)));
        JSONObject encoded = new JSONObject(body.toString());
        assert encoded.length() == 9 : "top-level wire schema";
        for (String kind : new String[]{"weather", "screen", "health"}) {
            JSONObject category = encoded.getJSONObject(kind);
            assert category.length() == 6 : kind + " must preserve all six fields: " + category;
            assert category.has("source_updated_at") && category.isNull("source_updated_at");
            assert "missing".equals(category.getString("status"));
            assert category.getJSONObject("fields").length() == 0;
        }
        java.util.Map<String,Object> source = new java.util.LinkedHashMap<>();
        java.util.Map<String,Object> fields = new java.util.LinkedHashMap<>();
        fields.put("heart_rate", null); fields.put("steps", 0);
        source.put("fields", fields); source.put("status", "ok");
        JSONObject zero = EventClient.categoryJson(ClientProtocol.category(source, "com.mi.health", 0L));
        assert zero.getJSONObject("fields").has("heart_rate") : "Unknown measurement must remain explicit null";
        assert zero.getJSONObject("fields").isNull("heart_rate");
        assert zero.getJSONObject("fields").getInt("steps") == 0;
        assert zero.has("reason") && zero.isNull("reason");
        assert "manual_test".equals(encoded.getJSONObject("trigger").getString("type"));
        assert "1970-01-01T00:00:00.000Z".equals(encoded.getString("collected_at"));
        System.out.println("ClientPayloadTest: PASS (wire schema, explicit null, missing data, manual trigger)");
    }
}

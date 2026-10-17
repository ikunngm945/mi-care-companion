package cn.local.micompanion;
import org.json.*;
public class DataProjectionTest {
 public static void main(String[] args) throws Exception {
  JSONObject raw=new JSONObject("{\"current\":{\"temperature\":{\"value\":\"21\",\"unit\":\"℃\"},\"humidity\":{\"value\":\"44\",\"unit\":\"%\"},\"pubTime\":\"2026-10-17T10:00:00+08:00\"},\"token\":\"secret\"}");
  JSONObject w=DataProjection.weather(raw,"阴","城市");
  if(w.has("token")||w.getJSONObject("temperature").getDouble("value")!=21)throw new AssertionError("weather projection");
  JSONObject step=DataProjection.step(new JSONObject("{\"steps\":0,\"sidList\":[\"private\"]}"),"2026-10-17",true);
  if(step.getLong("steps")!=0 || step.has("sidList"))throw new AssertionError("zero and privacy");
  JSONObject hr=DataProjection.heart(new JSONObject("{\"bpm\":72,\"time\":1792200000}"));
  if(hr.getInt("bpm")!=72 || hr.has("sid"))throw new AssertionError("heart projection");
  JSONObject sleep=DataProjection.sleep(new JSONObject("{\"bedtime\":1792100000,\"wake_up_time\":1792128800,\"duration\":480,\"device_id\":\"private\"}"));
  if(sleep.getInt("duration_minutes")!=480 || sleep.has("device_id"))throw new AssertionError("sleep minutes and privacy");
  if(!DataProjection.heart(new JSONObject("{\"bpm\":0}")).isNull("bpm"))throw new AssertionError("invalid bpm missing");
  System.out.println("DataProjection: 5 checks passed");
 }
}

package cn.local.micompanion;
import org.json.*;
public class SnapshotFreshnessTest {
 private static int cycleChecks;
 private static void check(boolean ok,String message){cycleChecks++;if(!ok)throw new AssertionError(message);}
 private static JSONObject cycleHealth(long now,long age)throws Exception{
  return new JSONObject().put("status","ok").put("source_updated_at","2026-10-17T00:00:00Z")
   .put("fields",new JSONObject().put("cycle",new JSONObject().put("status","ok").put("start_ms",now-3600000)
   .put("end_ms",now-age).put("timezone","Asia/Shanghai").put("activity",new JSONObject().put("steps",987654))));
 }
 private static void cycleFreshness(long now)throws Exception{
  JSONObject boundary=cycleHealth(now,120000);
  SnapshotFreshness.refresh(boundary,"health",now);
  JSONObject fresh=boundary.getJSONObject("fields").getJSONObject("cycle");
  check("ok".equals(fresh.getString("status")),"120000ms cycle age remains valid");
  check(EventClient.screenBounds(boundary,now)[1]==now-120000,"fresh cycle keeps frozen end for screen bounds");
  check(HealthText.render(boundary,null).contains("步数：987654步"),"fresh cycle remains usable by HealthText");
  JSONObject expired=cycleHealth(now,120001);
  SnapshotFreshness.refresh(expired,"health",now);
  JSONObject stale=expired.getJSONObject("fields").getJSONObject("cycle");
  check("stale".equals(stale.getString("status")),"120001ms cycle age must expire despite fresh source_updated_at");
  check("周期快照超过120秒，请等待Hook刷新".equals(stale.getString("reason")),"expired cycle uses fixed safe reason");
  check(stale.getLong("start_ms")==now-3600000&&stale.getLong("end_ms")==now-120001,"aging must not rebase either bound");
  check(EventClient.screenBounds(expired,now)==null,"expired cycle cannot authorize screen interval");
  check(!HealthText.render(expired,null).contains("987654"),"expired cycle facts are unavailable to HealthText");
 }
 private static void invalidCycles(long now)throws Exception{
  Object[][] bounds={
   {now-3600000,now+1},{0L,now},{-1L,now},{now,now},{now+1,now},
   {now-604800001L,now},{"1792191600000",now},{now-3600000,"1792195200000"},
   {now-3600000.5,now},{now-3600000,(double)now},{JSONObject.NULL,now},
   {now-3600000,JSONObject.NULL},{now-3600000,Long.MAX_VALUE},{Long.MIN_VALUE,now}
  };
  for(Object[] bound:bounds){
   JSONObject health=cycleHealth(now,0),cycle=health.getJSONObject("fields").getJSONObject("cycle");
   cycle.put("start_ms",bound[0]).put("end_ms",bound[1]).put("reason","PRIVATE_REASON");
   SnapshotFreshness.refresh(health,"health",now);
   check("error".equals(cycle.getString("status")),"invalid cycle bounds must fail closed");
   check("周期时间范围无效".equals(cycle.getString("reason")),"bad bounds use fixed safe reason");
   check(String.valueOf(bound[0]).equals(String.valueOf(cycle.get("start_ms")))&&String.valueOf(bound[1]).equals(String.valueOf(cycle.get("end_ms"))),"invalid original bounds remain unchanged");
   check(EventClient.screenBounds(health,now)==null,"bad bounds cannot authorize screen interval");
   String facts=HealthText.render(health,null);
   check(!facts.contains("987654")&&!facts.contains("PRIVATE_REASON"),"bad bounds neither expose cycle facts nor untrusted reason");
  }
  for(String key:new String[]{"start_ms","end_ms"}){
   JSONObject health=cycleHealth(now,0),cycle=health.getJSONObject("fields").getJSONObject("cycle");cycle.remove(key);
   SnapshotFreshness.refresh(health,"health",now);
   check("error".equals(cycle.getString("status"))&&!cycle.has(key),"missing bounds are rejected, never synthesized");
  }
  JSONObject longest=cycleHealth(now,0);longest.getJSONObject("fields").getJSONObject("cycle").put("start_ms",now-604800000L);
  SnapshotFreshness.refresh(longest,"health",now);
  check("ok".equals(longest.getJSONObject("fields").getJSONObject("cycle").getString("status")),"exact seven day cycle remains valid");
  for(String status:new String[]{"missing","error","stale"}){
   JSONObject health=cycleHealth(now,120001),cycle=health.getJSONObject("fields").getJSONObject("cycle");cycle.put("status",status).put("reason","already unavailable");String before=cycle.toString();
   SnapshotFreshness.refresh(health,"health",now);
   check(before.equals(cycle.toString()),"non-ok cycle status must not be revived or relabelled");
  }
  JSONObject legacy=new JSONObject("{\"status\":\"ok\",\"fields\":{}}");String before=legacy.toString();
  SnapshotFreshness.refresh(legacy,"health",now);check(before.equals(legacy.toString()),"legacy health must not acquire fabricated cycle");
  JSONObject weather=cycleHealth(now,120001);before=weather.getJSONObject("fields").getJSONObject("cycle").toString();
  SnapshotFreshness.refresh(weather,"weather",now);check(before.equals(weather.getJSONObject("fields").getJSONObject("cycle").toString()),"cycle aging only applies to health");
 }
 private static void categoryStatusDoesNotBypassCycleGate(long now)throws Exception{
  for(String status:new String[]{"missing","error","ok","stale"}){
   for(long age:new long[]{120001,-1,120000}){
    JSONObject health=cycleHealth(now,age).put("status",status).put("reason","category reason");
    JSONObject cycle=health.getJSONObject("fields").getJSONObject("cycle");
    SnapshotFreshness.refresh(health,"health",now);
    String expected=age==120001?"stale":age==-1?"error":"ok";
    check(expected.equals(cycle.getString("status")),status+" category must not bypass cycle gate at age "+age);
    check(status.equals(health.getString("status"))&&"category reason".equals(health.getString("reason")),"cycle gate preserves independent category status and reason");
    check(cycle.getLong("start_ms")==now-3600000&&cycle.getLong("end_ms")==now-age,"cycle gate never rebases bounds under any category status");
    if(!"ok".equals(expected)){
     check((age==120001?"周期快照超过120秒，请等待Hook刷新":"周期时间范围无效").equals(cycle.getString("reason")),"cycle gate uses fixed reason under every category status");
     check(EventClient.screenBounds(health,now)==null,"unavailable cycle never authorizes screen regardless of category status");
     check(!HealthText.render(health,null).contains("987654"),"unavailable cycle never exposes facts regardless of category status");
    }
   }
   JSONObject weather=cycleHealth(now,120001).put("status",status).put("reason","weather reason");String before=weather.toString();
   SnapshotFreshness.refresh(weather,"weather",now);
   check(before.equals(weather.toString()),"weather retains existing early-return and cycle semantics for "+status);
  }
 }
 private static void sourceCollectionTime(long now)throws Exception{
  JSONObject health=cycleHealth(now,120001).put("collected_at","2026-10-16T23:57:59.999Z");
  SnapshotProvider.refreshSnapshot(health,"health",now);
  check("2026-10-16T23:57:59.999Z".equals(health.getString("collected_at")),"health cache read preserves real collection timestamp");
  check("stale".equals(health.getJSONObject("fields").getJSONObject("cycle").getString("status")),"receiver refresh ages original end using read-time now");
  JSONObject legacy=new JSONObject("{\"status\":\"ok\",\"fields\":{},\"collected_at\":\"2026-10-16T00:00:00Z\"}");
  SnapshotProvider.refreshSnapshot(legacy,"health",now);
  check("2026-10-16T00:00:00Z".equals(legacy.getString("collected_at")),"legacy health source collection time also remains unchanged");
  JSONObject missing=new JSONObject("{\"status\":\"missing\",\"fields\":{}}");SnapshotProvider.refreshSnapshot(missing,"health",now);
  check(!missing.has("collected_at"),"health collection timestamp must not be synthesized");
  JSONObject weather=new JSONObject("{\"status\":\"ok\",\"fields\":{},\"collected_at\":\"2026-10-16T00:00:00Z\"}");
  SnapshotProvider.refreshSnapshot(weather,"weather",now);
  check(UsageCollector.iso(now).equals(weather.getString("collected_at")),"weather receiver collection-time compatibility remains unchanged");
 }
 public static void main(String[] a)throws Exception{
  long now=1792195200000L; // 2026-10-17T00:00Z
  JSONObject w=new JSONObject("{\"status\":\"ok\",\"source_package\":\"com.miui.weather2\",\"source_updated_at\":\"2026-10-16T00:00:00+00:00\",\"fields\":{}}");
  if(!"stale".equals(SnapshotFreshness.refresh(w,"weather",now).getString("status")))throw new AssertionError("weather must age without host process");
  JSONObject h=new JSONObject("{\"status\":\"ok\",\"fields\":{\"today_steps\":{\"steps\":10,\"report_date\":\"2026-10-16\",\"is_today\":true},\"latest_heart_rate\":{\"bpm\":70,\"sample_time_epoch_seconds\":1792100000},\"latest_sleep\":{\"duration_minutes\":480,\"wake_up_epoch_seconds\":1791900000}}}");
  JSONObject r=SnapshotFreshness.refresh(h,"health",now);
  if(!r.getJSONObject("fields").isNull("today_steps"))throw new AssertionError("yesterday steps must not become today");
  if(!"stale".equals(r.getJSONObject("fields").getJSONObject("latest_heart_rate").getString("freshness")))throw new AssertionError("metric age independent of category");
  if(!"stale".equals(r.getJSONObject("fields").getJSONObject("latest_sleep").getString("freshness")))throw new AssertionError("sleep threshold");
  JSONObject expanded=new JSONObject("{\"status\":\"ok\",\"fields\":{\"daily_activity\":{\"report_date\":\"2026-10-16\",\"is_today\":true,\"steps\":1},\"latest_weight\":{\"kg\":60,\"sample_time_epoch_seconds\":1770000000},\"latest_blood_glucose\":{\"value\":5,\"sample_time_epoch_seconds\":1780000000}}}");
  JSONObject ef=SnapshotFreshness.refresh(expanded,"health",now).getJSONObject("fields");
  if(!ef.isNull("daily_activity"))throw new AssertionError("daily activity must clear across local date");
  if(!ef.getJSONObject("latest_weight").has("kg") || ef.getJSONObject("latest_weight").getBoolean("is_today"))throw new AssertionError("historical latest remains with historical marker");
  if(!"stale".equals(ef.getJSONObject("latest_blood_glucose").getString("freshness")))throw new AssertionError("new health metrics must be aged independently");
  JSONObject today=new JSONObject("{\"status\":\"ok\",\"fields\":{\"daily_activity\":{\"report_date\":\"2026-10-17\",\"is_today\":true},\"latest_stress\":{\"sample_time_epoch_seconds\":1792195199}}}");
  SnapshotFreshness.refresh(today,"health",now);
  if(today.getJSONObject("fields").isNull("daily_activity") || !today.getJSONObject("fields").getJSONObject("latest_stress").getBoolean("is_today"))throw new AssertionError("fresh metrics retained on same day");
  JSONObject more=new JSONObject("{\"status\":\"ok\",\"fields\":{\"today_calories\":{\"report_date\":\"2026-10-16\",\"kcal\":100},\"today_stand\":{\"report_date\":\"2026-10-16\"},\"sleep_summary\":{\"report_date\":\"2026-10-16\",\"is_today\":true}}}");
  JSONObject mf=SnapshotFreshness.refresh(more,"health",now).getJSONObject("fields");
  if(!mf.isNull("today_calories") || !mf.isNull("today_stand"))throw new AssertionError("all today metrics clear at midnight");
  if(mf.getJSONObject("sleep_summary").getBoolean("is_today"))throw new AssertionError("historical summaries relabel without losing record");
  JSONObject millis=new JSONObject("{\"status\":\"ok\",\"source_updated_at\":\"2026-10-16T00:00:00.123Z\",\"fields\":{}}");
  if(!"stale".equals(SnapshotFreshness.refresh(millis,"weather",now).getString("status")))throw new AssertionError("millisecond ISO weather times age correctly");
  cycleFreshness(now);
  invalidCycles(now);
  sourceCollectionTime(now);
  categoryStatusDoesNotBypassCycleGate(now);
  System.out.println("SnapshotFreshness: "+(11+cycleChecks)+" checks passed");
 }
}

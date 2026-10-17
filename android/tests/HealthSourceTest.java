package cn.local.micompanion;
import org.json.*;
import java.util.*;
public final class HealthSourceTest {
 private static int checks;
 private static void check(boolean b,String reason){checks++;if(!b)throw new AssertionError(reason);}
 private static final long NOW=1767355200000L;
 private static JSONObject j(String s)throws Exception{return new JSONObject(s);}
 private static JSONObject row(String value,long at,long update)throws Exception{return new JSONObject().put("value",value).put("time",at).put("updateTime",update);}
 public static void main(String[] args)throws Exception{
  // Mutations caught: whole-category failure, stale-as-today, seven-day cutoff, row-size overflow.
  final List<String> sqls=new ArrayList<>();
  HealthSource.Reader summary=(sql,bind)->{
   sqls.add(sql);
   if(sql.contains("FROM rainbow"))return new JSONArray().put(row("{\"goal_items\":[{\"field\":1,\"target_value\":8000,\"achieved_value\":456}]}",1767312000L,0));
   String type=bind[0];
   if(type.equals("STEP"))return new JSONArray().put(row("{\"steps\":456,\"distance\":3210}",1767312000L,1767350000000L));
   if(type.equals("HR"))return new JSONArray().put(row("{\"avgHr\":72,\"latestHrRecord\":{\"time\":1609459200,\"bpm\":73}}",1767312000L,1767350000000L));
   if(type.equals("CALORIE"))throw new IllegalStateException("SECRET account path");
   return new JSONArray();
  };
  HealthSource.Reader data=(sql,bind)->{
   sqls.add(sql);
   if(sql.contains("hr_record"))return new JSONArray().put(row("{\"time\":1609459200,\"bpm\":74,\"sid\":\"SECRET\"}",1609459200L,0));
   if(sql.contains("weight_item_record"))return new JSONArray().put(row("{\"weight\":73.5,\"time\":1609459200}",1609459200L,0));
   if(sql.contains("blood_sugar_record"))return new JSONArray().put(row("broken",1609459200L,0));
   if(sql.contains("sleep_segment"))throw new IllegalStateException("missing table");
   return new JSONArray();
  };
  JSONObject out=HealthSource.collect(summary,data,new JSONObject(),NOW,TimeZone.getTimeZone("UTC"));
  check(out.length()==6,"exact six category keys");
  check(out.getString("status").equals("ok"),"isolated errors do not discard successful metrics");
  JSONObject f=out.getJSONObject("fields");
  check(f.getJSONObject("today_steps").getInt("steps")==456,"today report");
  check(f.getJSONObject("daily_activity").getJSONObject("goals").getJSONObject("steps").getInt("target")==8000,"current goal is projected");
  check(f.getJSONObject("latest_heart_rate").getInt("bpm")==74,"raw latest wins only if same/newer epoch");
  check(f.getJSONObject("latest_weight").getBoolean("is_historical"),"ancient weight retained as historical");
  check(f.getJSONObject("metric_status").getJSONObject("latest_sleep").getString("status").equals("error"),"missing table isolated");
  check(f.getJSONObject("metric_status").getJSONObject("latest_blood_sugar").getString("status").equals("error"),"malformed JSON isolated");
  check(!out.toString().contains("SECRET"),"error messages never expose DB exception content");
  for(String sql:sqls){check(sql.contains("LIMIT "),"all queries SQL bounded");check(!sql.contains("604800")&&!sql.contains("SUM("),"no seven day restriction or sid sums");}
  HealthSource.Reader old=(sql,bind)->new JSONArray().put(row("{\"steps\":777}",1609459200L,1767350000000L));
  JSONObject stale=HealthSource.collect(old,null,new JSONObject(),NOW,TimeZone.getTimeZone("UTC"));
  check(stale.getJSONObject("fields").isNull("today_steps"),"old steps never relabeled today");
  check(!stale.getString("status").equals("ok"),"metadata alone is not a measurement");
  HealthSource.Reader empty=(sql,bind)->new JSONArray();
  JSONObject absent=HealthSource.collect(empty,empty,new JSONObject(),NOW,TimeZone.getTimeZone("UTC"));
  check(absent.getString("status").equals("missing"),"empty databases missing rather than error");
  JSONObject base=j("{\"token\":\"SECRET\",\"collected_at\":\"old\"}");
  JSONObject clean=HealthSource.collect(summary,data,base,NOW,TimeZone.getTimeZone("UTC"));
  check(!clean.has("token")&&clean.toString().getBytes("UTF-8").length<65536,"category bounded and base whitelist");
  HealthSource.Reader fallback=(sql,bind)->{
   if(sql.contains("daily_report")&&bind[0].equals("HR"))return new JSONArray().put(row("{\"avgHr\":0}",1767312000L,1767350000000L)).put(row("{\"avgHr\":66}",1767225600L,1767250000000L));
   return new JSONArray();
  };
  JSONObject recovered=HealthSource.collect(fallback,empty,new JSONObject(),NOW,TimeZone.getTimeZone("UTC"));
  check(recovered.getJSONObject("fields").getJSONObject("heart_rate_summary").getInt("average_bpm")==66,"invalid newest report does not hide older valid bounded report");
  HealthSource.Reader zeroGoal=(sql,bind)->sql.contains("FROM rainbow")?new JSONArray().put(row("{\"goal_items\":[{\"field\":1,\"target_value\":8000,\"achieved_value\":0}]}",1767312000L,0)):new JSONArray();
  JSONObject goalOnly=HealthSource.collect(zeroGoal,empty,new JSONObject(),NOW,TimeZone.getTimeZone("UTC"));
  check(!goalOnly.getString("status").equals("ok"),"configured goals alone are not an observed measurement");
  JSONObject hugeRaw=new JSONObject().put("weight",72.1).put("padding",new String(new char[1048600]).replace('\u0000','x'));
  HealthSource.Reader huge=(sql,bind)->sql.contains("weight_item_record")?new JSONArray().put(row(hugeRaw.toString(),1609459200L,0)):new JSONArray();
  JSONObject bounded=HealthSource.collect(empty,huge,new JSONObject(),NOW,TimeZone.getTimeZone("UTC"));
  check(bounded.getJSONObject("fields").isNull("latest_weight")&&bounded.toString().getBytes("UTF-8").length<65536,"oversized raw JSON isolated and not exported");
  HealthSource.Reader precedence=(sql,bind)->{
   if(sql.contains("daily_report")&&bind[0].equals("HR"))return new JSONArray().put(row("{\"avgHr\":71,\"latestHrRecord\":{\"bpm\":81,\"time\":1767350000}}",1767312000L,1767350000000L));
   return new JSONArray();
  };
  JSONObject prefer=HealthSource.collect(precedence,data,new JSONObject(),NOW,TimeZone.getTimeZone("UTC"));
  check(prefer.getJSONObject("fields").getJSONObject("latest_heart_rate").getInt("bpm")==81,"older raw record never replaces newer report sample");
  HealthSource.Reader sleepReport=(sql,bind)->sql.contains("daily_report")&&bind[0].equals("SLEEP")?new JSONArray().put(row("{\"total_duration\":420,\"sleep_score\":86,\"avg_hr\":61}",1767312000L,1767350000000L)):new JSONArray();
  HealthSource.Reader sleepData=(sql,bind)->sql.contains("sleep_segment")?new JSONArray().put(row("{\"duration\":420,\"bedtime\":1767300000,\"wake_up_time\":1767325200}",1767325200L,0)):new JSONArray();
  JSONObject sleepSnapshot=HealthSource.collect(sleepReport,sleepData,new JSONObject(),NOW,TimeZone.getTimeZone("UTC"));
  check(sleepSnapshot.getJSONObject("fields").getJSONObject("sleep_summary").getInt("score")==86&&sleepSnapshot.getJSONObject("fields").getJSONObject("latest_sleep").isNull("score"),"daily sleep score remains separate from individual sleep segment");
  HealthSource.Reader futureReport=(sql,bind)->sql.contains("daily_report")&&bind[0].equals("HR")?new JSONArray().put(row("{\"avgHr\":72}",1767484800L,1767500000000L)):new JSONArray();
  JSONObject futureSnapshot=HealthSource.collect(futureReport,empty,new JSONObject(),NOW,TimeZone.getTimeZone("UTC"));
  check(futureSnapshot.getJSONObject("fields").isNull("heart_rate_summary"),"future report labels never exported as historical");
  // Mutation caught: byte-size checks alone allow recursive parser stack exhaustion.
  StringBuilder nested=new StringBuilder("0");for(int i=0;i<40;i++)nested.insert(0,'[').append(']');
  final String deepRaw="{\"weight\":72.1,\"ignored\":"+nested+"}";
  HealthSource.Reader deep=(sql,bind)->sql.contains("weight_item_record")?new JSONArray().put(row(deepRaw,1609459200L,0)):new JSONArray();
  JSONObject deepSnapshot=HealthSource.collect(summary,deep,new JSONObject(),NOW,TimeZone.getTimeZone("UTC"));
  check(deepSnapshot.getJSONObject("fields").isNull("latest_weight"),"overdepth JSON is rejected before recursive parse");
  check(deepSnapshot.getJSONObject("fields").getJSONObject("metric_status").getJSONObject("latest_weight").getString("status").equals("error"),"overdepth JSON is isolated to metric error");
  check(deepSnapshot.getJSONObject("fields").getJSONObject("today_steps").getInt("steps")==456,"deep JSON does not discard unrelated valid metric");
  StringBuilder braces=new StringBuilder();for(int i=0;i<80;i++)braces.append("{[\\\"\\\\]}");
  final String quotedRaw="{\"weight\":72.1,\"ignored\":"+JSONObject.quote(braces.toString())+"}";
  HealthSource.Reader quoted=(sql,bind)->sql.contains("weight_item_record")?new JSONArray().put(row(quotedRaw,1609459200L,0)):new JSONArray();
  JSONObject quotedSnapshot=HealthSource.collect(empty,quoted,new JSONObject(),NOW,TimeZone.getTimeZone("UTC"));
  check(quotedSnapshot.getJSONObject("fields").getJSONObject("latest_weight").getDouble("kg")==72.1,"quoted braces and escaped quotes do not count as JSON depth");
  JSONObject integrated=HealthSource.collect(empty,HealthCycleTest.fixture(HealthCycleTest.sleeps()),new JSONObject(),HealthCycleTest.NOW,TimeZone.getTimeZone("Asia/Shanghai"));
  check(integrated.getJSONObject("fields").optJSONObject("cycle")!=null,"source collection must include measured cycle");
  check(integrated.getJSONObject("fields").getJSONObject("cycle").getLong("end_ms")==HealthCycleTest.NOW,"cycle extends to collection time");
  long end=HealthCycleTest.NOW/1000,start=end-6*86400,bed=end-25000,wake=end-1000;
   JSONArray stages=new JSONArray();for(int i=0;i<512;i++)stages.put(new JSONObject().put("start_time",bed+i*40).put("end_time",bed+(i+1)*40).put("state",i%2+2));
   JSONArray longSleeps=new JSONArray().put(HealthCycleTest.sleep(start-24000,start,400,new JSONArray())).put(HealthCycleTest.sleep(bed,wake,390,stages));
   HealthSource.Reader longCycle=(sql,bind)->{
    if(sql.contains("FROM sleep_segment"))return longSleeps;
    String key=sql.contains("FROM hr_record")?"bpm":sql.contains("FROM stress_record")?"stress":sql.contains("FROM spo2_record")?"spo2":null;
    if(key==null)return new JSONArray();JSONArray points=new JSONArray();for(long at=start+1;at<=end;at+=1800)points.put(HealthCycleTest.point(at,key,70));return points;
   };
   JSONObject large=HealthSource.collect(empty,longCycle,new JSONObject(),end*1000,TimeZone.getTimeZone("UTC"));
   check(large.toString().getBytes("UTF-8").length>65536,"full-stage fixture exceeds legacy source budget");
   check(large.getJSONObject("fields").getJSONObject("cycle").getJSONArray("sleep").getJSONObject(0).getJSONArray("stages").length()==512,"source retains all valid stages above 64KiB");
   SnapshotProvider.validateSize(large.toString());
   check(EventClient.normalizeCategory(large,"com.mi.health",end*1000).getJSONObject("fields").getJSONObject("cycle").getJSONArray("sleep").getJSONObject(0).getJSONArray("stages").length()==512,"source-provider-client pipeline never truncates stages");
   System.out.println("HealthSource: "+checks+" checks passed");
 }
}

package cn.local.micompanion;
import java.util.*;
import org.json.*;
public final class HealthCycleTest {
 static int checks; static void check(boolean b,String m){checks++;if(!b)throw new AssertionError(m);}
 static final long START=1767248640L, END=1767334860L, NOW=(END+7200)*1000L;
 static JSONObject value(JSONObject v,long time)throws Exception{return new JSONObject().put("value",v.toString()).put("time",time).put("endTime",time).put("sid","PRIVATE").put("key","sleep");}
 static JSONObject sleep(long a,long b,int duration,JSONArray items)throws Exception{return value(new JSONObject().put("bedtime",a).put("wake_up_time",b).put("duration",duration).put("items",items),b);}
 static JSONObject point(long at,String k,int v)throws Exception{return value(new JSONObject().put("time",at).put(k,v),at);}
 static JSONArray sleeps()throws Exception{return new JSONArray().put(sleep(END-24000,END,380,new JSONArray().put(new JSONObject().put("start_time",END-24000).put("end_time",END-12000).put("state",2)).put(new JSONObject().put("start_time",END-12000).put("end_time",END).put("state",3)))).put(sleep(START+22000,START+25000,50,new JSONArray())).put(sleep(START-25000,START,400,new JSONArray()));}
 static HealthSource.Reader fixture(JSONArray sleeps){return (sql,bind)->{
  check(sql.contains("LIMIT "),"unbounded SQL");
  if(sql.contains("FROM sleep_segment"))return sleeps;
  if(sql.contains("FROM hr_record"))return new JSONArray().put(point(START,"bpm",199)).put(point(START+10,"bpm",70)).put(point(END-20000,"bpm",60)).put(point(END-19000,"bpm",80));
  if(sql.contains("FROM spo2_record"))return new JSONArray().put(point(END-20000,"spo2",96)).put(point(END-19000,"spo2",97));
  if(sql.contains("FROM stress_record"))return new JSONArray().put(point(START+10,"stress",33));
  if(sql.contains("FROM step_record"))return new JSONArray().put(point(START,"steps",900)).put(point(START+10,"steps",100)).put(point(END,"steps",20));
  if(sql.contains("FROM calorie_record"))return new JSONArray().put(point(START+20,"calories",7));
  if(sql.contains("FROM mh_strength_record"))return new JSONArray().put(point(START+60,"ignored",0)).put(point(START+120,"ignored",0));
  return new JSONArray();
 };}
 public static void main(String[] args)throws Exception{
  JSONObject cycle=HealthCycle.collect(fixture(sleeps()),NOW,TimeZone.getTimeZone("Asia/Shanghai"));
  check("ok".equals(cycle.optString("status")),"two main sleeps must produce cycle");
  check(cycle.getLong("start_ms")==START*1000&&cycle.getLong("end_ms")==NOW,"nap must not cut cycle");
  check(cycle.getJSONObject("activity").getInt("steps")==120,"exclusive start and inclusive end");
  check(cycle.getJSONObject("activity").getInt("mh_minutes")==2,"count actual intensity points");
  check(cycle.getJSONObject("activity").getInt("sport_count")==0,"successful empty sports query means zero");
  check(cycle.getJSONArray("sleep").length()==1,"only current main sleep; nap must not create another sleep section");
  JSONArray stages=cycle.getJSONArray("sleep").getJSONObject(0).getJSONArray("stages");
  check(stages.length()==2,"all stages retained");
  check(stages.getJSONObject(0).getJSONObject("heart_rate").getInt("min")==60,"stage measured min");
  check(stages.getJSONObject(0).getJSONObject("heart_rate").getInt("max")==80,"stage measured max");
  check(stages.getJSONObject(1).isNull("heart_rate"),"missing stage must not inherit previous range");
  check(!cycle.toString().contains("PRIVATE")&&!cycle.toString().contains("sid"),"source identity never exported");
  check(!"ok".equals(HealthCycle.collect(fixture(new JSONArray().put(sleeps().get(0))),NOW,TimeZone.getTimeZone("UTC")).getString("status")),"one sleep cannot define cycle");
  long half=(NOW/1000/1800)*1800;
  HealthSource.Reader endPoint=(sql,bind)->sql.contains("FROM hr_record")?new JSONArray().put(point(half,"bpm",88)):fixture(sleeps()).query(sql,bind);
  JSONObject edge=HealthCycle.collect(endPoint,half*1000,TimeZone.getTimeZone("Asia/Shanghai"));
  JSONArray bins=edge.getJSONArray("heart_rate");check(bins.length()==1,"terminal sample retained");
  check(bins.getJSONObject(0).getLong("end_ms")==half*1000&&bins.getJSONObject(0).getLong("start_ms")<half*1000,"half-hour endpoint must not create zero length bin");
  HealthSource.Reader fractional=(sql,bind)->sql.contains("FROM calorie_record")?new JSONArray().put(value(new JSONObject().put("time",START+20).put("calories",0.1),START+20)).put(value(new JSONObject().put("time",START+40).put("calories",0.2),START+40)):fixture(sleeps()).query(sql,bind);
  JSONObject decimal=HealthCycle.collect(fractional,NOW,TimeZone.getTimeZone("UTC"));
  check(!decimal.getJSONObject("activity").isNull("calories_kcal")&&decimal.getJSONObject("activity").getDouble("calories_kcal")==0.3,"fractional calorie records must sum exactly without truncation");
  HealthSource.Reader crossing=(sql,bind)->{
   if(!sql.contains("FROM sport_report"))return fixture(sleeps()).query(sql,bind);
   JSONArray result=new JSONArray();long low=Long.parseLong(bind[0]),high=Long.parseLong(bind[1]);
   if(START-60>low&&START-60<=high)result.put(value(new JSONObject().put("start_time",START-60).put("end_time",START+60).put("sport_type",1),START-60));
   return result;
  };
  check(HealthCycle.collect(crossing,NOW,TimeZone.getTimeZone("UTC")).getJSONObject("activity").getInt("sport_count")==1,"completed workout crossing interval start must count once");
  HealthSource.Reader sources=(sql,bind)->sql.contains("FROM sport_report")?new JSONArray()
   .put(value(new JSONObject().put("start_time",START+60).put("end_time",START+660).put("sport_type",1),START+60).put("sid","one"))
   .put(value(new JSONObject().put("start_time",START+65).put("end_time",START+665).put("sport_type",1),START+65).put("sid","two")):fixture(sleeps()).query(sql,bind);
  check(HealthCycle.collect(sources,NOW,TimeZone.getTimeZone("UTC")).getJSONObject("activity").isNull("sport_count"),"ambiguous sport sources must not be added together");
  long dstEnd=java.time.Instant.parse("2026-11-01T06:30:00Z").getEpochSecond(),dstStart=dstEnd-86400;
  JSONArray dstSleeps=new JSONArray().put(sleep(dstStart-24000,dstStart,400,new JSONArray())).put(sleep(dstEnd-24000,dstEnd-3600,340,new JSONArray()));
  HealthSource.Reader dst=(sql,bind)->sql.contains("FROM hr_record")?new JSONArray().put(point(dstEnd-4500,"bpm",71)).put(point(dstEnd-900,"bpm",81)):fixture(dstSleeps).query(sql,bind);
  JSONArray repeated=HealthCycle.collect(dst,dstEnd*1000,TimeZone.getTimeZone("America/New_York")).getJSONArray("heart_rate");
  check(repeated.length()==2,"repeated DST local half hour must preserve two distinct buckets");
  check(repeated.getJSONObject(0).getLong("start_ms")==java.time.Instant.parse("2026-11-01T05:00:00Z").toEpochMilli(),"first repeated half hour retains original UTC offset");
  check(repeated.getJSONObject(1).getLong("start_ms")==java.time.Instant.parse("2026-11-01T06:00:00Z").toEpochMilli(),"second repeated half hour retains original UTC offset");
  System.out.println("HealthCycle: "+checks+" checks passed");
 }
}

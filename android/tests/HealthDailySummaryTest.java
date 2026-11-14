package cn.local.micompanion;

import java.time.*;
import java.util.*;
import org.json.*;

/** Daily summaries are tested only through the public query boundary and row-reader input. */
public final class HealthDailySummaryTest {
 static final TimeZone ZONE=TimeZone.getTimeZone("Asia/Shanghai");
 static final long START=LocalDate.parse("2026-11-13").atStartOfDay(ZONE.toZoneId()).toEpochSecond();
 static final long NOW=(START+86400+3600)*1000;
 static final Map<String,JSONArray> rows=new HashMap<>();
 public static void main(String[] args)throws Exception {
  for(int h=0;h<2;h++)for(int m=0;m<10;m++)add("hr_record",START+h*3600+m*60,"bpm",60+h*20);
  add("hr_record",START,"bpm",60); // same timestamp is counted once
  add("hr_record",START+7200,"bpm",0); // invalid sensor value is not a point
  JSONObject result=query(new JSONObject().put("date","2026-11-13"),NOW);
  JSONObject hr=result.getJSONObject("heart_rate").getJSONObject("summary");
  eq(20,hr.getInt("count"));eq(60,hr.getInt("min"));eq(80,hr.getInt("max"));
  if(hr.getDouble("average")!=70.0)throw new AssertionError(hr);
  JSONObject empty=result.getJSONObject("stress").getJSONObject("summary");
  eq(0,empty.getInt("count"));if(!empty.isNull("min")||!empty.isNull("average"))throw new AssertionError(empty);
  if(!result.has("sleep"))throw new AssertionError("missing sleep summary");
  rows.clear();
  for(int h=0;h<2;h++)for(int m=0;m<10;m++)add("stress_record",START+h*3600+m*60,"stress",40);
  add("stress_record",START+7200,"stress",30); // low count but not no data
  add("hr_record",START+7200,"bpm",70);
  add("stress_record",START+10800,"stress",35);
  add("hr_record",START+10800,"bpm",75);
  // Next complete low hour switches to the no-heart branch.
  result=query(new JSONObject().put("date","2026-11-13"),NOW);
  JSONObject stress=result.getJSONObject("stress");
  if(stress.getDouble("typical_hourly_count")!=5.5)throw new AssertionError(stress);
  JSONArray low=stress.getJSONArray("low_sampling_periods");eq(2,low.length());
  JSONObject withHeart=low.getJSONObject(0),withoutHeart=low.getJSONObject(1);
  eq(2,withHeart.getInt("count"));eq(2,withHeart.getInt("heart_rate_count"));
  eq(0,withoutHeart.getInt("count"));eq(0,withoutHeart.getInt("heart_rate_count"));
  if(!withHeart.getString("start").contains("02:00")||!withHeart.getString("end").contains("04:00"))throw new AssertionError(withHeart);
  if(!withoutHeart.getString("start").contains("04:00")||!withoutHeart.getString("end").contains("00:00"))throw new AssertionError(withoutHeart);
  if(!withHeart.getString("hint").contains("省电模式")||withHeart.getString("hint").contains("未佩戴"))throw new AssertionError(withHeart);
  if(!withoutHeart.getString("hint").contains("未佩戴")||withoutHeart.getString("hint").contains("省电模式"))throw new AssertionError(withoutHeart);
  if(!stress.getString("sampling_note").contains("不等于"))throw new AssertionError(stress);
  eq(0,result.getJSONObject("spo2").getJSONArray("low_sampling_periods").length());
  // Exact half is not low. The unfinished hour contributes to summary but not comparison.
  rows.clear();
  for(int h=0;h<2;h++)for(int m=0;m<10;m++)add("hr_record",START+h*3600+m*60,"bpm",60);
  for(int m=0;m<5;m++)add("hr_record",START+7200+m*60,"bpm",70);
  add("hr_record",START+4*3600,"bpm",80);
  add("hr_record",START+5*3600,"bpm",90); // future point must not count
  result=query(new JSONObject().put("date","2026-11-13"),(START+4*3600+1800)*1000);
  hr=result.getJSONObject("heart_rate");eq(26,hr.getJSONObject("summary").getInt("count"));
  eq(4,hr.getInt("comparison_hours"));eq(1,hr.getJSONArray("low_sampling_periods").length());
  if(!hr.getJSONArray("low_sampling_periods").getJSONObject(0).getString("start").contains("03:00"))throw new AssertionError(hr);
  // Future dates are structurally useful but never diagnose absent wearing.
  result=query(new JSONObject().put("date","2026-11-15"),NOW);
  eq(0,result.getJSONObject("heart_rate").getJSONObject("summary").getInt("count"));
  eq(0,result.getJSONObject("heart_rate").getJSONArray("low_sampling_periods").length());
  JSONObject range=query(new JSONObject().put("start_date","2026-11-01").put("end_date","2026-11-02"),NOW);
  eq(2,range.getJSONArray("days").length());
  reject(new JSONObject().put("start_date","2026-10-01").put("end_date","2026-11-01"));
  reject(new JSONObject().put("start_date","2026-11-01"));
  reject(new JSONObject().put("date","2026-11-01").put("start_date","2026-11-01").put("end_date","2026-11-02"));
  reject(new JSONObject().put("date","2026-02-30"));
  reject(new JSONObject().put("date","2026-11-01").put("start_time","01:00"));
  // A repeated hour has a different offset; all 25 completed hours remain distinct.
  TimeZone berlin=TimeZone.getTimeZone("Europe/Berlin");
  long after=OffsetDateTime.parse("2026-10-26T00:00:00+01:00").toInstant().toEpochMilli();
  result=HealthQuery.query((sql,a)->new JSONArray(),new JSONObject().put("action","daily_summary").put("date","2026-10-25"),after,berlin);
  eq(25,result.getJSONObject("heart_rate").getInt("comparison_hours"));
  System.out.println("HealthDailySummaryTest passed");
 }
 static JSONObject query(JSONObject request,long now)throws Exception {
  request.put("action","daily_summary");
  return HealthQuery.query((sql,args)->{
   for(Map.Entry<String,JSONArray> e:rows.entrySet())if(sql.contains("FROM "+e.getKey()+" "))return e.getValue();
   return new JSONArray();
  },request,now,ZONE);
 }
 static void add(String table,long at,String field,int value)throws Exception {
  JSONArray list=rows.get(table);if(list==null){list=new JSONArray();rows.put(table,list);}
  list.put(new JSONObject().put("time",at).put("value",new JSONObject().put("time",at).put(field,value).toString()));
 }
 static void reject(JSONObject request)throws Exception {
  try{query(request,NOW);throw new AssertionError("accepted invalid date request "+request);}
  catch(IllegalArgumentException expected){}
 }
 static void eq(int expected,int actual){if(expected!=actual)throw new AssertionError("expected "+expected+" got "+actual);}
}

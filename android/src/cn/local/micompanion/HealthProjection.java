package cn.local.micompanion;
import org.json.*;
import java.text.SimpleDateFormat;
import java.util.*;

/** Pure bounded whitelist. No source record, identifier, free text or location is copied. */
public final class HealthProjection {
 private HealthProjection(){}
 public static String date(long ms,TimeZone zone){SimpleDateFormat f=new SimpleDateFormat("yyyy-MM-dd",Locale.US);f.setTimeZone(zone);return f.format(new Date(ms));}
 private static Object number(JSONObject v,String key,double lo,double hi){double n=v.optDouble(key,Double.NaN);return Double.isNaN(n)||Double.isInfinite(n)||n<lo||n>hi?JSONObject.NULL:n;}
 private static void put(JSONObject out,JSONObject v,String dest,String key,double lo,double hi)throws JSONException{out.put(dest,number(v,key,lo,hi));}
 private static JSONObject dated(String date,long now,TimeZone zone)throws JSONException{boolean today=date.equals(date(now,zone));return new JSONObject().put("report_date",date).put("is_today",today).put("is_historical",!today);}
 public static JSONObject daily(String type,JSONObject v,long label,long updated,long now,TimeZone zone)throws JSONException{
  JSONObject o=dated(date(label*1000L,TimeZone.getTimeZone("UTC")),now,zone).put("report_time_label_epoch_seconds",label).put("source_updated_at_ms",updated>0?updated:JSONObject.NULL);
  switch(type){
   case "STEP":put(o,v,"steps","steps",0,1000000);put(o,v,"distance_m","distance",0,10000000);o.put("step_kcal",JSONObject.NULL);break;
   case "CALORIE":put(o,v,"activity_kcal","calories",0,1000000);break;
   case "MH_STRENGTH":put(o,v,"moderate_vigorous_minutes","duration",0,2880);break;
   case "VALID_STAND":put(o,v,"stand_count","standCount",0,48);break;
   case "HR":put(o,v,"average_bpm","avgHr",1,300);put(o,v,"minimum_bpm","minHr",1,300);put(o,v,"maximum_bpm","maxHr",1,300);put(o,v,"resting_bpm","restHr",1,300);break;
   case "SPO2":put(o,v,"average_percent","avgSpo2",1,100);put(o,v,"minimum_percent","minSpo2",1,100);put(o,v,"maximum_percent","maxSpo2",1,100);break;
   case "STRESS":put(o,v,"average_score","avgStress",1,100);put(o,v,"minimum_score","minStress",1,100);put(o,v,"maximum_score","maxStress",1,100);break;
   case "SLEEP":sleep(o,v,true);break;
   case "TRAINING_LOAD":put(o,v,"weekly_load","wtlSum",0,1000000);put(o,v,"load_level_code","tlLevel",0,100);break;
   case "HEARING_HEALTH":put(o,v,"total_duration_raw","totalDuration",0,10000000);put(o,v,"high_duration_raw","highDuration",0,10000000);o.put("duration_unit","unverified");break;
   default:break;
  }
  return o;
 }
 public static JSONObject record(String type,JSONObject v,long sample,long now,TimeZone zone)throws JSONException{
  boolean validTime=sample>0&&sample<=now/1000L+1;
  JSONObject o=dated(validTime?date(sample*1000L,zone):"unknown",now,zone).put("sample_time_epoch_seconds",validTime?sample:JSONObject.NULL);
  if(!validTime)v=new JSONObject();
  switch(type){
   case "HR":put(o,v,"bpm","bpm",1,300);break;
   case "SPO2":put(o,v,"percent","spo2",1,100);break;
   case "STRESS":put(o,v,"score","stress",1,100);break;
   case "WEIGHT":put(o,v,"kg","weight",0.1,1000);put(o,v,"bmi","bmi",1,200);break;
   case "BLOOD_SUGAR":put(o,v,"mmol_per_l","blood_sugar",0.1,100);put(o,v,"measurement_period_code","measurement_period",0,100);break;
   case "BLOOD_PRESSURE":put(o,v,"systolic_mmhg","systolic_pressure",30,300);put(o,v,"diastolic_mmhg","diastolic_pressure",30,200);put(o,v,"pulse_bpm","pulse",1,300);break;
   case "SLEEP":sleep(o,v,false);break;
   case "TRAINING_LOAD":put(o,v,"weekly_load","wtl_sum",0,1000000);put(o,v,"load_level_code","current_train_load_level",0,100);break;
   case "VITALITY":put(o,v,"accumulated_score","latest_accumulated_vitality",0,1000000);put(o,v,"high_intensity_score","daily_high_intensity_vitality",0,1000000);put(o,v,"medium_intensity_score","daily_medium_intensity_vitality",0,1000000);put(o,v,"low_intensity_score","daily_low_intensity_vitality",0,1000000);break;
   case "HEARING_HEALTH":put(o,v,"start_epoch_seconds","start_time",1,5000000000d);put(o,v,"end_epoch_seconds","end_time",1,5000000000d);if(v.opt("high") instanceof Boolean)o.put("high_noise",v.getBoolean("high"));break;
   default:break;
  }
  return o;
 }
 private static void sleep(JSONObject o,JSONObject v,boolean daily)throws JSONException{
  put(o,v,"duration_minutes",daily?"total_duration":"duration",0,2880);
  put(o,v,"deep_minutes","sleep_deep_duration",0,2880);put(o,v,"light_minutes","sleep_light_duration",0,2880);
  put(o,v,"rem_minutes","sleep_rem_duration",0,2880);put(o,v,"awake_minutes","sleep_awake_duration",0,2880);put(o,v,"trace_minutes","sleep_trace_duration",0,2880);
  put(o,v,"score","sleep_score",1,100);put(o,v,"average_bpm","avg_hr",1,300);put(o,v,"average_spo2_percent","avg_spo2",1,100);
  if(!daily){put(o,v,"bedtime_epoch_seconds","bedtime",1,5000000000d);put(o,v,"wake_up_epoch_seconds","wake_up_time",1,5000000000d);put(o,v,"awake_count","awake_count",0,1000);}
 }
 public static JSONObject goals(JSONObject v,long label,long now,TimeZone zone)throws JSONException{
  JSONObject out=dated(date(label*1000L,TimeZone.getTimeZone("UTC")),now,zone);JSONArray list=v.optJSONArray("goal_items");
  if(list!=null)for(int i=0;i<Math.min(128,list.length());i++){JSONObject item=list.optJSONObject(i);if(item==null)continue;String name;
   switch(item.optInt("field",0)){case 1:name="steps";break;case 2:name="activity_kcal";break;case 3:name="stand_count";break;case 4:name="moderate_vigorous_minutes";break;default:continue;}
   JSONObject goal=new JSONObject();put(goal,item,"target","target_value",0,1000000);put(goal,item,"achieved","achieved_value",0,1000000);out.put(name,goal);
  }return out;
 }
 /** Metadata, enum codes and timestamps do not themselves constitute a measurement. */
 public static boolean hasMeasurement(JSONObject o){
  for(Iterator<String> it=o.keys();it.hasNext();){String k=it.next();Object v=o.opt(k);if(k.endsWith("_code")||k.endsWith("_seconds")||k.endsWith("_ms")||k.equals("is_today")||k.equals("is_historical"))continue;if(v instanceof Number)return true;if(v instanceof Boolean&&k.equals("high_noise"))return true;if(v instanceof JSONObject&&hasMeasurement((JSONObject)v))return true;}return false;
 }
}

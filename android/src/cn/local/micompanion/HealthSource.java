package cn.local.micompanion;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import java.io.File;
import java.text.SimpleDateFormat;
import java.util.*;
import org.json.*;

/** Independent read-only connections; failures are contained to each requested metric. */
public final class HealthSource {
 private HealthSource(){}
 // Source JSON remains local. Summary queries: four rows; cycle queries: explicit LIMIT plus sentinel.
 private static final int MAX_INPUT=1024*1024;
 interface Reader {JSONArray query(String sql,String[] args)throws Exception;}
 private static String iso(long ms){return new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSXXX",Locale.US).format(new Date(ms));}
 private static SQLiteDatabase open(File f){return SQLiteDatabase.openDatabase(f.getAbsolutePath(),null,SQLiteDatabase.OPEN_READONLY|SQLiteDatabase.NO_LOCALIZED_COLLATORS);}
 private static Reader reader(SQLiteDatabase db){return (sql,args)->{
  JSONArray rows=new JSONArray();try(Cursor c=db.rawQuery(sql,args)){while(c.moveToNext()){
    if(rows.length()>=12001)throw new IllegalArgumentException("query row limit");
   JSONObject row=new JSONObject();String value=c.getString(0);
   // Do not parse arbitrarily large embedded histories. A newer schema is explicitly unavailable.
   if(value==null||value.length()>MAX_INPUT)throw new IllegalArgumentException("record size");
   row.put("value",value).put("time",c.getLong(1));if(c.getColumnCount()>2)row.put("updateTime",c.getLong(2));
    if(c.getColumnCount()>3)row.put("endTime",c.getLong(3));
    if(c.getColumnCount()>4)row.put("sid",c.getString(4));
    if(c.getColumnCount()>5)row.put("key",c.getString(5));rows.put(row);
  }}return rows;
 };}
 private static File account(Context c){
  File root=c.getDatabasePath("x").getParentFile();if(root==null)return null;String active=LocalCollector.activeHealthPath;
  if(active!=null)try{File f=new File(active);if(f.getCanonicalPath().startsWith(root.getCanonicalPath()+"/")&&f.isFile()&&"fitness_summary".equals(f.getName()))return f.getParentFile();}catch(Exception ignored){}
  File[] accounts=root.listFiles();ArrayList<File> found=new ArrayList<>();
  if(accounts!=null)for(File a:accounts){if(!a.isDirectory()||"no_account".equals(a.getName()))continue;File[] regions=a.listFiles();if(regions!=null)for(File r:regions)if(new File(r,"fitness_summary").isFile())found.add(r);}
  return found.size()==1?found.get(0):null;
 }
 /** Integration entry point. Uses no network, writes, checkpoints, account merging or DB migration. */
 public static JSONObject collect(Context c,JSONObject base)throws JSONException{
  long now=System.currentTimeMillis();SQLiteDatabase summary=null,data=null;JSONObject result;
  try{
   File dir=account(c);if(dir==null)return category(now).put("reason","健康账号库未找到或存在多个账号；等待当前账号数据库路径定位");
   try{summary=open(new File(dir,"fitness_summary"));}catch(Exception ignored){}
   try{data=open(new File(dir,"fitness_data"));}catch(Exception ignored){}
   result=collect(summary==null?null:reader(summary),data==null?null:reader(data),base,now,TimeZone.getDefault());
  }catch(Exception ex){result=category(now).put("status","error").put("reason","健康只读采集失败；未输出源异常内容");}
  finally{if(summary!=null)summary.close();if(data!=null)data.close();}
  return result;
 }
 private static JSONObject category(long now)throws JSONException{return new JSONObject().put("status","missing").put("source_package","com.mi.health").put("collected_at",iso(now)).put("source_updated_at",JSONObject.NULL).put("fields",new JSONObject()).put("reason","当前账号没有可用健康记录");}
 private static final class Snapshot {
  final JSONObject fields=new JSONObject(),states=new JSONObject();final long now;final TimeZone zone;long updated;boolean any,today;int errors;
  Snapshot(long n,TimeZone z){now=n;zone=z;}
  void status(String name,String state)throws JSONException{states.put(name,new JSONObject().put("status",state));if("error".equals(state))errors++;}
  void missing(String name,String state)throws JSONException{if(!fields.has(name))fields.put(name,JSONObject.NULL);status(name,state);}
  void add(String name,JSONObject value)throws JSONException{
   if(!HealthProjection.hasMeasurement(value)){missing(name,"missing");return;}
   fields.put(name,value);status(name,"ok");any=true;if(value.optBoolean("is_today"))today=true;
   updated=Math.max(updated,value.optLong("source_updated_at_ms",0));updated=Math.max(updated,value.optLong("sample_time_epoch_seconds",0)*1000L);
  }
 }
 private static JSONObject value(JSONObject row)throws JSONException{
  String text=row.getString("value");if(text.length()>MAX_INPUT)throw new JSONException("size");
  int depth=0;char quote=0;boolean escape=false;
  for(int i=0;i<text.length();i++){
   char c=text.charAt(i);
   if(quote!=0){if(escape)escape=false;else if(c=='\\')escape=true;else if(c==quote)quote=0;}
   else if(c=='\"'||c=='\'')quote=c;
   else if(c=='{'||c=='['){if(++depth>32)throw new JSONException("depth");}
   else if(c=='}'||c==']'){if(--depth<0)throw new JSONException("structure");}
  }
  if(quote!=0||depth!=0)throw new JSONException("structure");
  return new JSONObject(text);
 }
 private static long labelStart(long now,TimeZone zone)throws Exception{SimpleDateFormat format=new SimpleDateFormat("yyyy-MM-dd",Locale.US);format.setTimeZone(TimeZone.getTimeZone("UTC"));return format.parse(HealthProjection.date(now,zone)).getTime()/1000L;}
 private static JSONObject summary(Reader db,String type,boolean today,Snapshot s)throws Exception{
  String sql="SELECT value,timeInZero,updateTime FROM daily_report WHERE dataType=? AND viewTag='days' AND isDeleted=0";
  String[] args;
  if(today){long start=labelStart(s.now,s.zone);sql+=" AND timeInZero>=? AND timeInZero<?";args=new String[]{type,Long.toString(start),Long.toString(start+86400)};}
  else{sql+=" AND timeInZero<?";args=new String[]{type,Long.toString(labelStart(s.now,s.zone)+86400)};}
  sql+=today?" ORDER BY updateTime DESC,timeInZero DESC LIMIT 4":" ORDER BY timeInZero DESC,updateTime DESC LIMIT 4";
  JSONArray rows=db.query(sql,args);if(rows.length()==0)return null;Exception broken=null;
  for(int i=0;i<Math.min(4,rows.length());i++)try{JSONObject row=rows.getJSONObject(i);long at=row.getLong("time");
   String reportDate=HealthProjection.date(at*1000L,TimeZone.getTimeZone("UTC")),todayDate=HealthProjection.date(s.now,s.zone);
   if(at<=0||reportDate.compareTo(todayDate)>0||(today&&!reportDate.equals(todayDate)))continue;
   JSONObject raw=value(row),projected=HealthProjection.daily(type,raw,at,row.optLong("updateTime"),s.now,s.zone);if(!HealthProjection.hasMeasurement(projected))continue;return new JSONObject().put("projection",projected).put("raw",raw);
  }catch(Exception ex){broken=ex;}
  if(broken!=null)throw broken;return null;
 }
 private static void daily(Reader db,String type,String name,boolean today,Snapshot s)throws JSONException{
  if(db==null){s.missing(name,"error");return;}
  try{JSONObject row=summary(db,type,today,s);if(row==null){s.missing(name,"missing");return;}s.add(name,row.getJSONObject("projection"));
   String nested="HR".equals(type)?"latestHrRecord":"SPO2".equals(type)?"latestSpoRecord":"STRESS".equals(type)?"latestStressRecord":null;
   String latest="HR".equals(type)?"latest_heart_rate":"SPO2".equals(type)?"latest_spo2":"latest_stress";
   if(nested!=null){JSONObject raw=row.getJSONObject("raw").optJSONObject(nested);if(raw!=null&&raw.optLong("time",0)>0&&raw.optLong("time")<=s.now/1000L+1)s.add(latest,HealthProjection.record(type,raw,raw.getLong("time"),s.now,s.zone));}
  }catch(Exception ignored){s.missing(name,"error");}
 }
 private static void latest(Reader db,String table,String type,String name,String keys,Snapshot s)throws JSONException{
  if(db==null){if(!s.fields.has(name))s.missing(name,"error");return;}
  try{
   String time="SLEEP".equals(type)?"endTime":"time";
   String sql="SELECT value,"+time+" FROM "+table+" WHERE isDeleted=0 AND "+time+">0 AND "+time+"<=?";
   if(keys!=null)sql+=" AND key IN ("+keys+")";
   sql+=" ORDER BY "+time+" DESC,time DESC LIMIT 4";
   JSONArray rows=db.query(sql,new String[]{Long.toString(s.now/1000L+1)});boolean malformed=false;
   for(int i=0;i<Math.min(4,rows.length());i++)try{
    JSONObject row=rows.getJSONObject(i),raw=value(row);long epoch=row.getLong("time");
    if("SLEEP".equals(type))epoch=raw.optLong("wake_up_time",epoch);
    if(epoch<=0||epoch>s.now/1000L+1)continue;
    JSONObject projected=HealthProjection.record(type,raw,epoch,s.now,s.zone);if(!HealthProjection.hasMeasurement(projected))continue;
    JSONObject previous=s.fields.optJSONObject(name);if(previous==null||epoch>=previous.optLong("sample_time_epoch_seconds",0))s.add(name,projected);return;
   }catch(Exception ignored){malformed=true;}
   if(!s.fields.has(name))s.missing(name,malformed?"error":"missing");
  }catch(Exception ignored){if(!s.fields.has(name))s.missing(name,"error");}
 }
 private static void goals(Reader db,Snapshot s)throws JSONException{
  if(db==null){s.missing("today_goals","error");return;}
  try{long start=labelStart(s.now,s.zone);JSONArray rows=db.query("SELECT value,timeInZero FROM rainbow WHERE timeInZero>=? AND timeInZero<? ORDER BY timeInZero DESC LIMIT 1",new String[]{Long.toString(start),Long.toString(start+86400)});
   if(rows.length()==0){s.missing("today_goals","missing");return;}JSONObject row=rows.getJSONObject(0);long at=row.getLong("time");
   JSONObject goal=HealthProjection.goals(value(row),at,s.now,s.zone);if(!goal.optBoolean("is_today")||!HealthProjection.hasMeasurement(goal)){s.missing("today_goals","missing");return;}s.fields.put("today_goals",goal);s.status("today_goals","ok");
  }catch(Exception ignored){s.missing("today_goals","error");}
 }

 private static long localDayStart(long now,TimeZone zone){
  return java.time.Instant.ofEpochMilli(now).atZone(zone.toZoneId()).toLocalDate()
   .atStartOfDay(zone.toZoneId()).toEpochSecond();
 }
 private static long dayLabelStart(long now,TimeZone zone,int daysBack)throws Exception{
  SimpleDateFormat format=new SimpleDateFormat("yyyy-MM-dd",Locale.US);format.setTimeZone(TimeZone.getTimeZone("UTC"));
  return format.parse(HealthProjection.date(now,zone)).getTime()/1000L-daysBack*86400L;
 }
 private static void number(JSONObject out,JSONObject raw,String dest,String key,double lo,double hi)throws JSONException{
  double n=raw==null?Double.NaN:raw.optDouble(key,Double.NaN);
  if(!Double.isNaN(n)&&!Double.isInfinite(n)&&n>=lo&&n<=hi)out.put(dest,n==Math.rint(n)?(long)n:n);
 }
 private static JSONObject workouts(Reader db,long now,TimeZone zone)throws JSONException{
  JSONObject out=new JSONObject().put("status","missing").put("today",new JSONArray()).put("week",new JSONArray())
   .put("week_duration_seconds",0).put("week_count",0).put("today_count",0);
  if(db==null)return out.put("status","error").put("reason","运动记录表不可读");
  try{
   long todayStart=localDayStart(now,zone),weekStart=todayStart-6*86400L,until=now/1000L+1;
   JSONArray rows=db.query("SELECT value,time,0 AS updateTime,time,sid,key FROM sport_report WHERE isDeleted=0 AND time>=? AND time<=? ORDER BY time ASC LIMIT 201",new String[]{Long.toString(weekStart),Long.toString(until)});
   TreeMap<String,JSONObject> unique=new TreeMap<>();int duplicate=0;
   for(int i=0;i<rows.length();i++){
    JSONObject row=rows.getJSONObject(i),raw=value(row);
    long start=raw.optLong("start_time",0),end=raw.optLong("end_time",0);
    if(start<weekStart||start>until||end<=start||end>until||end-start>7*86400L)continue;
    long duration=raw.optLong("duration",end-start);if(duration<=0||duration>86400L)duration=end-start;
    String key=raw.optString("key",row.optString("key",""));
    String sport=name(key);
    String id=start+":"+end+":"+raw.optLong("sport_type",0);
    if(unique.containsKey(id)){duplicate++;continue;}
    JSONObject item=new JSONObject().put("start_ms",start*1000).put("end_ms",end*1000)
     .put("sport_name",sport).put("sport_key",key).put("duration_seconds",duration);
    number(item,raw,"distance_m","distance",0,10000000);
    number(item,raw,"activity_kcal","calories",0,1000000);
    number(item,raw,"total_kcal","total_cal",0,1000000);
    number(item,raw,"avg_pace_seconds_per_km","avg_pace",0,36000);
    number(item,raw,"max_pace_seconds_per_km","max_pace",0,36000);
    number(item,raw,"avg_bpm","avg_hrm",1,300);number(item,raw,"max_bpm","max_hrm",1,300);number(item,raw,"min_bpm","min_hrm",0,300);
    number(item,raw,"steps","steps",0,1000000);number(item,raw,"avg_cadence","avg_cadence",0,300);number(item,raw,"max_cadence","max_cadence",0,300);
    number(item,raw,"avg_stride_cm","avg_stride",0,300);number(item,raw,"max_stride_cm","max_stride",0,300);
    number(item,raw,"avg_speed_mps","avg_speed",0,100);number(item,raw,"sport_type_code","sport_type",0,1000);
    if(item.has("sport_type_code"))item.put("sport_type",item.remove("sport_type_code"));
    unique.put(id,item);
   }
   if(unique.size()>50)return out.put("status","error").put("reason","一周运动记录超过安全上限");
   long weekDuration=0;JSONArray week=new JSONArray(),today=new JSONArray();
   for(JSONObject item:unique.values()){
    week.put(item);weekDuration+=item.optLong("duration_seconds",0);
    if(HealthProjection.date(item.getLong("start_ms"),zone).equals(HealthProjection.date(now,zone)))today.put(item);
   }
   out.put("status","ok").put("week",week).put("today",today).put("week_duration_seconds",weekDuration)
    .put("week_count",week.length()).put("today_count",today.length());
   if(duplicate>0)out.put("duplicate_projection_count",duplicate);
   return out;
  }catch(Exception ex){return out.put("status","error").put("reason","运动记录读取失败");}
 }
 private static String name(String key){
  if("outdoor_running".equals(key))return "户外跑步";
  if("indoor_running".equals(key))return "室内跑步";
  if("walking".equals(key)||"outdoor_walking".equals(key))return "户外步行";
  if("indoor_walking".equals(key))return "室内步行";
  if("cycling".equals(key)||"outdoor_cycling".equals(key))return "户外骑行";
  if("indoor_cycling".equals(key))return "室内骑行";
  if("free_training".equals(key))return "自由训练";
  if("strength_training".equals(key))return "力量训练";
  if("swimming".equals(key))return "游泳";
  return "运动";
 }
 private static JSONObject weekActivity(Reader db,JSONObject workouts,long now,TimeZone zone)throws JSONException{
  long todayStart=localDayStart(now,zone);
  JSONObject out=new JSONObject().put("status","missing").put("start_date",HealthProjection.date((todayStart-6*86400L)*1000L,zone))
   .put("end_date",HealthProjection.date(now,zone)).put("days",7);
  if(db==null)return out.put("status","error");
  try{
   long start=dayLabelStart(now,zone,6),end=dayLabelStart(now,zone,0)+86400;
   String[] types={"STEP","CALORIE","MH_STRENGTH"};TreeMap<String,JSONObject> days=new TreeMap<>();
   for(String type:types){
    JSONArray rows=db.query("SELECT value,timeInZero,updateTime FROM daily_report WHERE dataType=? AND viewTag='days' AND isDeleted=0 AND timeInZero>=? AND timeInZero<? ORDER BY timeInZero DESC,updateTime DESC LIMIT 28",new String[]{type,Long.toString(start),Long.toString(end)});
    HashSet<String> seen=new HashSet<>();
    for(int i=0;i<rows.length();i++){
     try{
      JSONObject row=rows.getJSONObject(i);long at=row.getLong("time"),updated=row.optLong("updateTime",0);
      String date=HealthProjection.date(at*1000L,TimeZone.getTimeZone("UTC"));
      if(date.compareTo(HealthProjection.date((todayStart-6*86400L)*1000L,zone))<0||date.compareTo(HealthProjection.date(now,zone))>0||!seen.add(date))continue;
      JSONObject p=HealthProjection.daily(type,value(row),at,updated,now,zone);if(!HealthProjection.hasMeasurement(p))continue;
      JSONObject day=days.get(date);if(day==null){day=new JSONObject().put("date",date);days.put(date,day);}
      if("STEP".equals(type)){copy(day,p,"steps","distance_m");}
      else if("CALORIE".equals(type))copy(day,p,"activity_kcal");
      else copy(day,p,"moderate_vigorous_minutes");
     }catch(Exception ignored){}
    }
   }
   if(workouts!=null&&workouts.optJSONArray("week")!=null){
    JSONArray list=workouts.getJSONArray("week");
    for(int i=0;i<list.length();i++){
     JSONObject item=list.optJSONObject(i);if(item==null)continue;
     long duration=item.optLong("duration_seconds",0);if(duration<=0)continue;
     String date=HealthProjection.date(item.getLong("start_ms"),zone);JSONObject day=days.get(date);
     if(day!=null){day.put("exercise_duration_seconds",day.optLong("exercise_duration_seconds",0)+duration);day.put("exercise_count",day.optInt("exercise_count",0)+1);}
    }
   }
   JSONArray list=new JSONArray();
   for(JSONObject day:days.values())list.put(day);
   out.put("status","ok").put("days_with_data",list.length()).put("days_detail",list);
   return out;
  }catch(Exception ex){return out.put("status","error").put("reason","近7天每日活动读取失败");}
 }
 private static JSONArray vitalsWeek(Reader db,long now,TimeZone zone)throws JSONException{
  JSONArray out=new JSONArray();if(db==null)return out;
  try{
   long start=dayLabelStart(now,zone,6),end=dayLabelStart(now,zone,0)+86400;
   String[] types={"HR","SPO2","STRESS"};TreeMap<String,JSONObject> days=new TreeMap<>();
   for(String type:types){
    JSONArray rows=db.query("SELECT value,timeInZero,updateTime FROM daily_report WHERE dataType=? AND viewTag='days' AND isDeleted=0 AND timeInZero>=? AND timeInZero<? ORDER BY timeInZero DESC,updateTime DESC LIMIT 28",new String[]{type,Long.toString(start),Long.toString(end)});
    HashSet<String> seen=new HashSet<>();
    for(int i=0;i<rows.length();i++)try{
     JSONObject row=rows.getJSONObject(i);long at=row.getLong("time"),updated=row.optLong("updateTime",0);
     String date=HealthProjection.date(at*1000L,TimeZone.getTimeZone("UTC"));
     if(date.compareTo(HealthProjection.date((dayLabelStart(now,zone,6))*1000L,zone))<0||date.compareTo(HealthProjection.date(now,zone))>0||!seen.add(date))continue;
     JSONObject p=HealthProjection.daily(type,value(row),at,updated,now,zone);if(!HealthProjection.hasMeasurement(p))continue;
     JSONObject day=days.get(date);if(day==null){day=new JSONObject().put("date",date);days.put(date,day);}
     if("HR".equals(type)){copyAs(day,p,"heart_rate_avg_bpm","average_bpm");copyAs(day,p,"heart_rate_min_bpm","minimum_bpm");copyAs(day,p,"heart_rate_max_bpm","maximum_bpm");}
     else if("SPO2".equals(type)){copyAs(day,p,"spo2_avg_percent","average_percent");copyAs(day,p,"spo2_min_percent","minimum_percent");copyAs(day,p,"spo2_max_percent","maximum_percent");}
     else{copyAs(day,p,"stress_avg_score","average_score");copyAs(day,p,"stress_min_score","minimum_score");copyAs(day,p,"stress_max_score","maximum_score");}
    }catch(Exception ignored){}
   }
   for(JSONObject day:days.values())out.put(day);
  }catch(Exception ignored){}
  return out;
 }
 private static void copyAs(JSONObject to,JSONObject from,String toKey,String fromKey)throws JSONException{if(from!=null&&from.has(fromKey))to.put(toKey,from.get(fromKey));}
 private static JSONArray sleepWeek(Reader db,long now,TimeZone zone)throws JSONException{
  JSONArray out=new JSONArray();if(db==null)return out;
  try{
   long start=dayLabelStart(now,zone,6),end=dayLabelStart(now,zone,0)+86400;
   JSONArray rows=db.query("SELECT value,timeInZero,updateTime FROM daily_report WHERE dataType='SLEEP' AND viewTag='days' AND isDeleted=0 AND timeInZero>=? AND timeInZero<? ORDER BY timeInZero DESC,updateTime DESC LIMIT 14",new String[]{Long.toString(start),Long.toString(end)});
   HashSet<String> seen=new HashSet<>();
   for(int i=0;i<rows.length();i++){
    try{
     JSONObject row=rows.getJSONObject(i);long at=row.getLong("time");String date=HealthProjection.date(at*1000L,TimeZone.getTimeZone("UTC"));
     if(!seen.add(date))continue;
     JSONObject raw=value(row),day=new JSONObject().put("date",date).put("is_today",date.equals(HealthProjection.date(now,zone)));
     JSONArray segments=raw.optJSONArray("segment_details"),details=new JSONArray();
     long total=0,deep=0,light=0,rem=0,awake=0;boolean any=false,allStages=true,anyStages=false;long first=Long.MAX_VALUE,last=0;
     if(segments!=null)for(int j=0;j<segments.length()&&j<32;j++){
      JSONObject seg=segments.optJSONObject(j);if(seg==null)continue;
      long bed=seg.optLong("bedtime",0),wake=seg.optLong("wake_up_time",0);
      if(bed<=0||wake<=bed||wake>now/1000L+1)continue;
      boolean hasStages=seg.has("sleep_deep_duration")&&seg.has("sleep_light_duration")
       &&seg.has("sleep_rem_duration")&&seg.has("sleep_awake_duration");
      long d=seg.optLong("sleep_deep_duration",0),l=seg.optLong("sleep_light_duration",0),r=seg.optLong("sleep_rem_duration",0),a=seg.optLong("sleep_awake_duration",0);
      long t=seg.optLong("duration",0);if(t<=0||t>2880)t=d+l+r;
      if(t<=0)continue;
      JSONObject s=new JSONObject().put("bedtime_epoch_seconds",bed).put("wake_up_epoch_seconds",wake).put("total_minutes",t);
      if(hasStages){s.put("deep_minutes",d).put("light_minutes",l).put("rem_minutes",r).put("awake_minutes",a);anyStages=true;deep+=d;light+=l;rem+=r;awake+=a;}
      else allStages=false;
      details.put(s);total+=t;any=true;first=Math.min(first,bed);last=Math.max(last,wake);
     }
     if(!any)continue;
     day.put("total_minutes",total).put("bedtime_epoch_seconds",first).put("wake_up_epoch_seconds",last).put("segments",details);
     if(anyStages&&allStages)day.put("deep_minutes",deep).put("light_minutes",light).put("rem_minutes",rem).put("awake_minutes",awake);
     out.put(day);
    }catch(Exception ignored){}
   }
  }catch(Exception ignored){}
  return out;
 }

 private static void copy(JSONObject to,JSONObject from,String... keys)throws JSONException{if(from!=null)for(String k:keys)if(from.has(k))to.put(k,from.get(k));}
 static JSONObject collect(Reader summary,Reader data,JSONObject base,long now,TimeZone zone)throws JSONException{
  Snapshot s=new Snapshot(now,zone);
  daily(summary,"STEP","today_steps",true,s);daily(summary,"CALORIE","today_calories",true,s);
  daily(summary,"MH_STRENGTH","today_moderate_vigorous",true,s);daily(summary,"VALID_STAND","today_stand",true,s);
  daily(summary,"HR","heart_rate_summary",false,s);daily(summary,"SPO2","spo2_summary",false,s);daily(summary,"STRESS","stress_summary",false,s);
  daily(summary,"SLEEP","sleep_summary",false,s);daily(summary,"TRAINING_LOAD","training_load_summary",false,s);daily(summary,"HEARING_HEALTH","hearing_summary",false,s);
  latest(data,"hr_record","HR","latest_heart_rate","'heart_rate','single_heart_rate'",s);
  latest(data,"spo2_record","SPO2","latest_spo2","'spo2','single_spo2'",s);
  latest(data,"stress_record","STRESS","latest_stress","'stress','single_stress'",s);
  latest(data,"sleep_segment","SLEEP","latest_sleep","'sleep'",s);
  latest(data,"weight_item_record","WEIGHT","latest_weight","'weight'",s);
  latest(data,"blood_sugar_record","BLOOD_SUGAR","latest_blood_sugar",null,s);
  latest(data,"blood_pressure_record","BLOOD_PRESSURE","latest_blood_pressure",null,s);
  latest(data,"training_load_record","TRAINING_LOAD","latest_training_load",null,s);
  latest(data,"vitality_record","VITALITY","latest_vitality",null,s);
  latest(data,"hearing_health_record","HEARING_HEALTH","latest_hearing",null,s);
  JSONObject activity=new JSONObject().put("report_date",HealthProjection.date(now,zone)).put("is_today",true).put("is_historical",false);
  copy(activity,s.fields.optJSONObject("today_steps"),"steps","distance_m");copy(activity,s.fields.optJSONObject("today_calories"),"activity_kcal");
  copy(activity,s.fields.optJSONObject("today_moderate_vigorous"),"moderate_vigorous_minutes");copy(activity,s.fields.optJSONObject("today_stand"),"stand_count");
  goals(summary,s);
  boolean measuredActivity=HealthProjection.hasMeasurement(activity);
  activity.put("activity_count",JSONObject.NULL).put("goals",s.fields.optJSONObject("today_goals")==null?JSONObject.NULL:s.fields.getJSONObject("today_goals"));
  if(measuredActivity)s.add("daily_activity",activity);else{s.fields.put("daily_activity",activity);s.status("daily_activity","missing");}
  try{s.fields.put("cycle",HealthCycle.collect(data,now,zone));}catch(Exception ignored){s.fields.put("cycle",new JSONObject().put("status","error"));}
  JSONObject workouts=workouts(data,now,zone),week=weekActivity(summary,workouts,now,zone);
  JSONArray sleep=sleepWeek(summary,now,zone),vitals=vitalsWeek(summary,now,zone);
  s.fields.put("week_activity",week).put("today_workouts",workouts.optJSONArray("today")).put("sleep_week",sleep).put("daily_vitals",vitals);
  s.status("week_activity",week.optString("status","missing"));
  s.status("today_workouts",workouts.optString("status","missing"));
  s.status("sleep_week",sleep.length()>0?"ok":"missing");
  s.status("daily_vitals",vitals.length()>0?"ok":"missing");
  s.fields.put("metric_status",s.states).put("measurement_note","当天活动使用应用日报不跨来源相加；其余为各指标最新记录、不限七天；历史与报告日期和真实采样时间分别标记；缺失目标及未核实单位不猜测");
  JSONObject out=category(now).put("fields",s.fields).put("status",s.any?(s.today?"ok":"stale"):(s.errors>0?"error":"missing"))
   .put("source_updated_at",s.updated>0?iso(s.updated):JSONObject.NULL).put("reason",s.any?"只读白名单快照；单项状态见metric_status，旧记录不是今日测量":"当前账号无有效测量，或部分表不可用；详见单项状态");
  // Fixed whitelist emits no unbounded arrays/free text; defence against future accidental expansion.
  if(out.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8).length>256*1024)return category(now).put("status","error").put("reason","健康投影超出安全大小限制");
  return out;
 }
}

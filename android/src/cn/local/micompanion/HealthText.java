package cn.local.micompanion;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Locale;
import org.json.JSONArray;
import org.json.JSONObject;

/** Local, deterministic model view. Source objects and persisted snapshots remain untouched. */
public final class HealthText {
 private HealthText() { }
 private static final DateTimeFormatter LOCAL=DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm",Locale.ROOT);
 private static final String MISSING="暂无数据";

 public static String snapshot(String snapshot)throws Exception{
  RouteResolver.validateJsonObject(snapshot);
  JSONObject root=new JSONObject(snapshot);
  String facts=render(root.optJSONObject("health"),root.optJSONObject("screen"));
  root.put("health",new JSONObject().put("facts",facts));
  root.put("screen",new JSONObject().put("facts","屏幕事实已并入健康读取区间；区间无法核对时标为未知。"));
  String result=root.toString();
  // Fail closed instead of truncating a valid segment when the model view exceeds its envelope.
  RouteResolver.validateJsonObject(result);
  return result;
 }

 public static String render(JSONObject health,JSONObject screen)throws Exception{
  JSONObject fields=health==null?null:health.optJSONObject("fields");
  JSONObject cycle=fields==null?null:fields.optJSONObject("cycle");
  ZoneId zone=null;Long start=null,end=null;
  if(cycle!=null&&"ok".equals(cycle.opt("status"))){
   start=integer(cycle.opt("start_ms"));end=integer(cycle.opt("end_ms"));
   try{Object id=cycle.opt("timezone");if(id instanceof String)zone=ZoneId.of((String)id);}catch(java.time.DateTimeException ignored){}
  }
  boolean valid=zone!=null&&start!=null&&end!=null&&start>=0&&end>start;
  ZoneId displayZone=valid?zone:ZoneId.systemDefault();
  if(valid){try{local(start,zone);local(end,zone);}catch(java.time.DateTimeException e){valid=false;}}
  StringBuilder out=new StringBuilder("健康读取区间：");
  if(valid)out.append(local(start,zone)).append(" ～ ").append(local(end,zone)).append("（").append(zone.getId()).append("；前一次主睡眠结束至本次采集）\n");
  else out.append(MISSING).append("（未获得有效主睡眠读取区间，不使用日汇总代替）\n");
  JSONObject week=fields==null?null:fields.optJSONObject("week_activity");
  if(week!=null&&"ok".equals(week.optString("status")))weekActivity(out,week);else{
   JSONObject activity=valid?cycle.optJSONObject("activity"):null;
   activity(out,activity,"calories_kcal","活动卡路里","千卡");
   activity(out,activity,"steps","步数","步");
   activity(out,activity,"mh_minutes","中高强度活动","分钟");
   activity(out,activity,"sport_count","运动次数","次");
  }
  dailyVitals(out,fields==null?null:fields.optJSONArray("daily_vitals"));
  workouts(out,fields==null?null:fields.optJSONArray("today_workouts"),displayZone);
  if(!valid){
   out.append("睡眠（当次）：\n").append(MISSING).append("\n心率：\n").append(MISSING).append("\n压力：\n").append(MISSING).append("\n血氧：\n").append(MISSING).append("\n屏幕：未知（无有效健康读取区间）\n");
   sleepHistory(out,fields==null?null:fields.optJSONArray("sleep_week"),null,displayZone);
   return out.toString();
  }
  sleep(out,cycle.optJSONArray("sleep"),start,end,zone);
  sleepHistory(out,fields.optJSONArray("sleep_week"),cycle.optJSONArray("sleep"),displayZone);
  rangeSummary(out,"心率",cycle.optJSONArray("heart_rate"),"次/分");
  rangeSummary(out,"压力",cycle.optJSONArray("stress"),"");
  rangeSummary(out,"血氧",cycle.optJSONArray("spo2"),"%");
  out.append("心率/压力/血氧分段：实测散点按本地半小时分桶，取值区间为（开始，结束]，不是原应用日报桶；仅显示有效数据。\n");
  measured(out,"心率",cycle.optJSONArray("heart_rate"),"次/分",start,end,zone);
  measured(out,"压力",cycle.optJSONArray("stress"),"",start,end,zone);
  measured(out,"血氧",cycle.optJSONArray("spo2"),"%",start,end,zone);
  screen(out,screen,start,end);
  return out.toString();
 }

 private static void weekActivity(StringBuilder out,JSONObject week){
  out.append("近7天每日活动（").append(week.optString("start_date",MISSING)).append(" ～ ")
   .append(week.optString("end_date",MISSING)).append("；按天列出，不合计）：\n");
  JSONArray days=week.optJSONArray("days_detail");boolean shown=false;
  if(days!=null)for(int i=0;i<days.length();i++){
   JSONObject day=days.optJSONObject(i);if(day==null)continue;
   String date=day.optString("date","");if(date.isEmpty())continue;
   shown=true;out.append(date).append("：");
   out.append("步数 ").append(day.has("steps")?number(day.opt("steps"))+"步":MISSING).append("；");
   out.append("活动卡路里 ").append(day.has("activity_kcal")?number(day.opt("activity_kcal"))+"千卡":MISSING).append("；");
   out.append("活动时长 ").append(day.has("moderate_vigorous_minutes")?number(day.opt("moderate_vigorous_minutes"))+"分钟":MISSING);
   Long seconds=integer(day.opt("exercise_duration_seconds"));
   if(seconds!=null)out.append("；运动 ").append(secondsText(seconds)).append("（").append(day.optInt("exercise_count",0)).append("次）");
   out.append('\n');
  }
  if(!shown)out.append(MISSING).append('\n');
 }


 private static void dailyVitals(StringBuilder out,JSONArray days){
  if(days==null||days.length()==0){out.append("近7天每日心率/压力/血氧：").append(MISSING).append('\n');return;}
  out.append("近7天每日心率/压力/血氧（按天列出，用于对比）：\n");
  for(int i=0;i<days.length();i++){
   JSONObject day=days.optJSONObject(i);if(day==null)continue;
   String date=day.optString("date","");if(date.isEmpty())continue;
   out.append(date).append("：");
   dailyVital(out,day,"心率","heart_rate_avg_bpm","heart_rate_min_bpm","heart_rate_max_bpm","次/分");out.append("；");
   dailyVital(out,day,"压力","stress_avg_score","stress_min_score","stress_max_score","");out.append("；");
   dailyVital(out,day,"血氧","spo2_avg_percent","spo2_min_percent","spo2_max_percent","%");
   out.append('\n');
  }
 }
 private static void dailyVital(StringBuilder out,JSONObject day,String label,String avgKey,String minKey,String maxKey,String unit){
  String avg=number(day.opt(avgKey));out.append(label);
  if(avg==null){out.append(' ').append(MISSING);return;}
  out.append("平均 ").append(avg).append(unit);
  String lo=number(day.opt(minKey)),hi=number(day.opt(maxKey));
  if(lo!=null&&hi!=null)out.append(lo.equals(hi)?"（"+lo+unit+"）":"（"+lo+"～"+hi+unit+"）");
 }

 private static void workouts(StringBuilder out,JSONArray list,ZoneId zone){
  if(list==null||list.length()==0){out.append("今日运动：").append(MISSING).append('\n');return;}
  Long firstStart=integer(list.optJSONObject(0)==null?null:list.optJSONObject(0).opt("start_ms"));
  String day=firstStart==null?MISSING:local(firstStart,zone).substring(0,10);
  out.append("今日运动（").append(day).append("）：\n");
  boolean shown=false;
  for(int i=0;i<list.length();i++){
   JSONObject item=list.optJSONObject(i);if(item==null)continue;
   Long at=integer(item.opt("start_ms"));if(at==null)continue;
   String time;try{time=java.time.format.DateTimeFormatter.ofPattern("HH:mm",Locale.ROOT).format(Instant.ofEpochMilli(at).atZone(zone));}catch(java.time.DateTimeException e){continue;}
   shown=true;out.append(time).append(' ').append(sportName(item.opt("sport_name"))).append("：");
   appendMetric(out,"距离",number(item.opt("distance_m")),"米");
   appendMetric(out,"时长",secondsText(integer(item.opt("duration_seconds"))),null);
   String kcal=number(item.opt("activity_kcal"));String total=number(item.opt("total_kcal"));
   if(kcal!=null||total!=null)out.append("活动卡路里：").append(kcal==null?MISSING:kcal+"千卡")
    .append(total==null?"":"（总"+total+"千卡）").append('；');
   String pace=pace(integer(item.opt("avg_pace_seconds_per_km")));
   if(pace!=null)out.append("平均配速：").append(pace).append('；');
   String maxPace=pace(integer(item.opt("max_pace_seconds_per_km")));
   if(maxPace!=null)out.append("最快配速：").append(maxPace).append('；');
   String avgHr=number(item.opt("avg_bpm")),maxHr=number(item.opt("max_bpm"));
   if(avgHr!=null)out.append("平均心率：").append(avgHr).append("次/分；");
   if(maxHr!=null)out.append("最大心率：").append(maxHr).append("次/分；");
   String steps=number(item.opt("steps")),cadence=number(item.opt("avg_cadence")),maxCadence=number(item.opt("max_cadence"));
   if(steps!=null)out.append("步数：").append(steps).append("步；");
   if(cadence!=null)out.append("平均步频：").append(cadence).append("步/分；");
   if(maxCadence!=null)out.append("最大步频：").append(maxCadence).append("步/分；");
   String stride=number(item.opt("avg_stride_cm"));
   if(stride!=null)out.append("平均步幅：").append(stride).append("厘米；");
   if(out.charAt(out.length()-1)=='；')out.setLength(out.length()-1);
   out.append('\n');
  }
  if(!shown)out.append(MISSING).append('\n');
 }

 private static void appendMetric(StringBuilder out,String label,String value,String unit){
  if(value==null)return;out.append(label).append('：').append(value).append(unit==null?"":unit).append('；');
 }
 private static String sportName(Object value){
  if(!(value instanceof String))return "运动";
  String s=(String)value;
  return "户外跑步".equals(s)||"室内跑步".equals(s)||"户外步行".equals(s)||"室内步行".equals(s)
   ||"户外骑行".equals(s)||"室内骑行".equals(s)||"自由训练".equals(s)||"力量训练".equals(s)||"游泳".equals(s)?s:"运动";
 }
 private static String pace(Long seconds){
  if(seconds==null||seconds<=0||seconds>36000)return null;
  return (seconds/60)+"'"+String.format(Locale.ROOT,"%02d",seconds%60)+"\"";
 }
 private static String secondsText(Long seconds){
  if(seconds==null||seconds<0)return null;
  long h=seconds/3600,m=seconds%3600/60,s=seconds%60;StringBuilder b=new StringBuilder();
  if(h>0)b.append(h).append("小时");if(m>0)b.append(m).append("分钟");if(s>0||b.length()==0)b.append(s).append("秒");return b.toString();
 }

 private static void sleepHistory(StringBuilder out,JSONArray days,JSONArray current,ZoneId zone){
  out.append("其余睡眠摘要（近7天，仅入睡/醒来、总时长和分期时长）：\n");boolean shown=false;
  String currentDate=null;Long currentStart=null,currentEnd=null;
  if(current!=null&&current.length()>0){JSONObject c=current.optJSONObject(0);
   Long start=integer(c==null?null:c.opt("start_ms")),end=integer(c==null?null:c.opt("end_ms"));
   if(end!=null)currentDate=local(end,zone).substring(0,10);
   if(start!=null&&end!=null&&end>start){currentStart=start;currentEnd=end;}}
  ArrayList<long[]> claimed=new ArrayList<>();
  if(currentStart!=null&&currentEnd!=null)claimed.add(new long[]{currentStart,currentEnd});
  if(days!=null)for(int i=0;i<days.length();i++){
   JSONObject day=days.optJSONObject(i);if(day==null)continue;
   String date=day.optString("date","");if(date.isEmpty()||date.equals(currentDate))continue;
   Long bed=integer(day.opt("bedtime_epoch_seconds")),wake=integer(day.opt("wake_up_epoch_seconds"));
   if(bed==null||wake==null)continue;
   long bedMs=bed*1000L,wakeMs=wake*1000L;boolean duplicate=false;
   for(long[] range:claimed)if(bedMs<range[1]&&wakeMs>range[0]){duplicate=true;break;}
   if(duplicate)continue;
   shown=true;claimed.add(new long[]{bedMs,wakeMs});
   out.append(local(bed*1000,zone)).append(" ～ ").append(local(wake*1000,zone))
    .append("；总睡眠：").append(minutes(day.opt("total_minutes")));
   if(day.has("deep_minutes")&&day.has("light_minutes")&&day.has("rem_minutes")&&day.has("awake_minutes"))
    out.append("；清醒：").append(minutes(day.opt("awake_minutes")))
     .append("；快速眼动：").append(minutes(day.opt("rem_minutes")))
     .append("；浅睡：").append(minutes(day.opt("light_minutes")))
     .append("；深睡：").append(minutes(day.opt("deep_minutes")));
   else out.append("；分期时长：暂无数据");
   out.append('\n');
  }
  if(!shown)out.append(MISSING).append('\n');
 }
 private static String clock(long millis,ZoneId zone){try{return java.time.format.DateTimeFormatter.ofPattern("HH:mm",Locale.ROOT).format(Instant.ofEpochMilli(millis).atZone(zone));}catch(java.time.DateTimeException e){return MISSING;}}
 private static String minutes(Object value){Long n=integer(value);if(n==null||n<0)return MISSING;if(n<60)return n+"分钟";return n/60+"小时"+(n%60==0?"":n%60+"分钟");}
 private static void activity(StringBuilder out,JSONObject activity,String key,String label,String unit){
  String value=activity==null?null:number(activity.opt(key));
  out.append(label).append('：').append(value==null?MISSING:value+unit).append('\n');
 }

 private static void sleep(StringBuilder out,JSONArray sleeps,long start,long end,ZoneId zone){
  out.append("睡眠分段：\n");boolean shown=false;
  if(sleeps!=null)for(int i=0;i<sleeps.length();i++){
   JSONObject item=sleeps.optJSONObject(i);String interval=interval(item,start,end,zone);
   if(interval==null)continue;
   shown=true;out.append("睡眠：").append(interval).append('\n');
   JSONArray stages=item.optJSONArray("stages");boolean stageShown=false;
   long sleepStart=integer(item.opt("start_ms")),sleepEnd=integer(item.opt("end_ms"));
   long deep=0,light=0,rem=0,awake=0;
   if(stages!=null)for(int j=0;j<stages.length();j++){
    JSONObject summaryStage=stages.optJSONObject(j);Long a=summaryStage==null?null:integer(summaryStage.opt("start_ms")),b=summaryStage==null?null:integer(summaryStage.opt("end_ms"));
    Long stageState=summaryStage==null?null:integer(summaryStage.opt("state"));
    if(a==null||b==null||b<=a||stageState==null)continue;
    long m=(b-a)/60000L;
    if(stageState==2)deep+=m;else if(stageState==3)light+=m;else if(stageState==4)rem+=m;else if(stageState==5)awake+=m;
   }
   if(deep+light+rem+awake>0)out.append("当次睡眠总时长：").append(minutes(deep+light+rem)).append("；清醒：").append(minutes(awake))
    .append("；快速眼动：").append(minutes(rem)).append("；浅睡：").append(minutes(light)).append("；深睡：").append(minutes(deep)).append('\n');
   if(stages!=null)for(int j=0;j<stages.length();j++){
    JSONObject stage=stages.optJSONObject(j);String stageInterval=interval(stage,sleepStart,sleepEnd,zone);
    if(stageInterval==null)continue;
    stageShown=true;out.append(stageInterval).append('：').append(state(stage.opt("state")))
     .append("；心率：").append(valueRange(stage.optJSONObject("heart_rate"),"次/分"))
     .append("；血氧：").append(valueRange(stage.optJSONObject("spo2"),"%")).append('\n');
   }
   if(!stageShown)out.append("暂无阶段；心率：暂无数据；血氧：暂无数据\n");
  }
  if(!shown)out.append(MISSING).append('\n');
 }

 private static String state(Object raw){
  Long n=integer(raw);if(n==null)return "未知阶段";
  // Verified app source SleepState constants, not HealthUtil's separate UI indices.
  if(n==0)return "未睡眠";
  if(n==2)return "深睡";
  if(n==3)return "浅睡";
  if(n==4)return "快速眼动";
  if(n==5)return "清醒";
  if(n==6)return "睡眠（未分期）";
  return "未知阶段（state="+n+"）";
 }

 private static void rangeSummary(StringBuilder out,String label,JSONArray values,String unit){
  if(values==null)return;java.math.BigDecimal min=null,max=null;
  for(int i=0;i<values.length();i++){
   JSONObject item=values.optJSONObject(i);if(item==null)continue;
   String a=number(item.opt("min")),b=number(item.opt("max"));if(a==null||b==null)continue;
   java.math.BigDecimal lo,hi;try{lo=new java.math.BigDecimal(a);hi=new java.math.BigDecimal(b);}catch(NumberFormatException e){continue;}
   if(lo.compareTo(hi)>0){java.math.BigDecimal t=lo;lo=hi;hi=t;}
   if(min==null||lo.compareTo(min)<0)min=lo;
   if(max==null||hi.compareTo(max)>0)max=hi;
  }
  if(min!=null)out.append(label).append("全周期：").append(min.compareTo(max)==0?min.toPlainString():min.toPlainString()+"～"+max.toPlainString()).append(unit).append('\n');
 }

 private static void measured(StringBuilder out,String label,JSONArray values,String unit,long start,long end,ZoneId zone){
  out.append(label).append("：\n");boolean shown=false;
  if(values!=null)for(int i=0;i<values.length();i++){
   JSONObject item=values.optJSONObject(i);String interval=interval(item,start,end,zone);
   String value=valueRange(item,unit);
   if(interval==null||MISSING.equals(value))continue;
   shown=true;out.append(interval).append('：').append(value).append('\n');
  }
  if(!shown)out.append(MISSING).append('\n');
 }

 private static String valueRange(JSONObject item,String unit){
  if(item==null)return MISSING;
  String min=number(item.opt("min")),max=number(item.opt("max"));
  if(min==null||max==null||new BigDecimal(min).compareTo(new BigDecimal(max))>0)return MISSING;
  return (min.equals(max)?min:min+"～"+max)+unit;
 }

 private static String interval(JSONObject item,long start,long end,ZoneId zone){
  if(item==null)return null;
  Long a=integer(item.opt("start_ms")),b=integer(item.opt("end_ms"));
  if(a==null||b==null||a<start||b>end||b<=a)return null;
  try{return local(a,zone)+" ～ "+local(b,zone);}catch(java.time.DateTimeException e){return null;}
 }

 private static String local(long epoch,ZoneId zone){return LOCAL.format(Instant.ofEpochMilli(epoch).atZone(zone));}

 private static void screen(StringBuilder out,JSONObject screen,long start,long end){
  JSONObject fields=screen==null?null:screen.optJSONObject("fields");
  if(fields==null||"missing".equals(screen.optString("status"))||"error".equals(screen.optString("status"))||!sameInstant(fields.opt("interval_start"),start)||!sameInstant(fields.opt("interval_end"),end)){
   out.append("屏幕：未知（读取区间无法与健康读取区间核对）\n");return;
  }
  out.append("屏幕（同健康读取区间）：\n亮屏时长：").append(duration(fields.opt("screen_on_ms")))
   .append("\n解锁使用时长：").append(duration(fields.opt("unlocked_use_ms"))).append("\n应用前台时长：\n");
  JSONArray apps=fields.optJSONArray("top_apps");boolean shown=false;
  if(apps!=null)for(int i=0;i<apps.length();i++){
   JSONObject app=apps.optJSONObject(i);if(app==null)continue;
   Object name=app.opt("package");
   // Only a package identifier is allowed; no labels, account IDs, or arbitrary free text.
   if(!(name instanceof String)||!("android".equals(name)||((String)name).matches("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+")))continue;
   out.append(name).append('：').append(duration(app.opt("foreground_ms"))).append('\n');shown=true;
  }
  if(!shown)out.append(MISSING).append('\n');
 }

 private static boolean sameInstant(Object value,long epoch){
  if(!(value instanceof String))return false;
  try{return OffsetDateTime.parse((String)value).toInstant().equals(Instant.ofEpochMilli(epoch));}
  catch(java.time.DateTimeException e){return false;}
 }

 private static String duration(Object value){
  Long ms=integer(value);if(ms==null||ms<0)return MISSING;
  long hours=ms/3600000,minutes=ms%3600000/60000,remainder=ms%60000;
  StringBuilder text=new StringBuilder();
  if(hours>0)text.append(hours).append("小时");
  if(minutes>0)text.append(minutes).append("分钟");
  if(remainder>0||text.length()==0)text.append(BigDecimal.valueOf(remainder,3).stripTrailingZeros().toPlainString()).append("秒");
  return text.toString();
 }

 private static Long integer(Object value){
  if(!(value instanceof Number))return null;
  try{return new BigDecimal(value.toString()).longValueExact();}catch(ArithmeticException|NumberFormatException e){return null;}
 }

 private static String number(Object value){
  if(!(value instanceof Number)||!Double.isFinite(((Number)value).doubleValue())||((Number)value).doubleValue()<0)return null;
  try{
   BigDecimal n=new BigDecimal(value.toString()).stripTrailingZeros();
   if((long)n.precision()+Math.abs((long)n.scale())+3>1048576)throw new IllegalArgumentException("健康数值超过可整理范围，已停止，未截断事实");
   return n.toPlainString();
  }catch(NumberFormatException e){return null;}
 }
}

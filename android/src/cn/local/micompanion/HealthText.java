package cn.local.micompanion;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
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
  if(valid){try{local(start,zone);local(end,zone);}catch(java.time.DateTimeException e){valid=false;}}
  StringBuilder out=new StringBuilder("健康读取区间：");
  if(valid)out.append(local(start,zone)).append(" ～ ").append(local(end,zone)).append("（").append(zone.getId()).append("；前一次主睡眠结束至本次采集）\n");
  else out.append(MISSING).append("（未获得有效主睡眠读取区间，不使用日汇总代替）\n");
  JSONObject activity=valid?cycle.optJSONObject("activity"):null;
  activity(out,activity,"calories_kcal","活动卡路里","千卡");
  activity(out,activity,"steps","步数","步");
  activity(out,activity,"mh_minutes","中高强度活动","分钟");
  activity(out,activity,"sport_count","运动次数","次");
  if(!valid){
   out.append("睡眠：\n").append(MISSING).append("\n心率：\n").append(MISSING).append("\n压力：\n").append(MISSING).append("\n血氧：\n").append(MISSING).append("\n屏幕：未知（无有效健康读取区间）\n");
   return out.toString();
  }
  sleep(out,cycle.optJSONArray("sleep"),start,end,zone);
  out.append("心率/压力/血氧分段：实测散点按本地半小时分桶，取值区间为（开始，结束]，不是原应用日报桶；仅显示有效数据。\n");
  measured(out,"心率",cycle.optJSONArray("heart_rate"),"次/分",start,end,zone);
  measured(out,"压力",cycle.optJSONArray("stress"),"",start,end,zone);
  measured(out,"血氧",cycle.optJSONArray("spo2"),"%",start,end,zone);
  screen(out,screen,start,end);
  return out.toString();
 }

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

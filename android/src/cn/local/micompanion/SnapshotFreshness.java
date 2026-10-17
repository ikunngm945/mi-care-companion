package cn.local.micompanion;
import org.json.*;
import java.text.SimpleDateFormat;
import java.util.*;
/** Re-evaluated by the receiving app even if the source app is no longer running. */
public final class SnapshotFreshness {
 private static long iso(String s){try{return java.time.OffsetDateTime.parse(s).toInstant().toEpochMilli();}catch(Exception e){return 0;}}
 private static String metric(JSONObject m,String key,long age,long now)throws JSONException{
  long at=m.optLong(key,0)*1000L;
  String state=at<=0||at>now+300000?"unknown":now-at>age?"stale":"ok";
  m.put("freshness",state);return state;
 }
 public static JSONObject refresh(JSONObject o,String kind,long now)throws JSONException{
  String status=o.optString("status");
  JSONObject f=o.optJSONObject("fields");if(f==null)return o;
  JSONObject cycle="health".equals(kind)?f.optJSONObject("cycle"):null;
  if(cycle!=null && "ok".equals(cycle.optString("status"))){
   Object start=cycle.opt("start_ms"),end=cycle.opt("end_ms");
   boolean integral=(start instanceof Byte || start instanceof Short || start instanceof Integer || start instanceof Long)
    && (end instanceof Byte || end instanceof Short || end instanceof Integer || end instanceof Long);
   long a=integral?((Number)start).longValue():0,b=integral?((Number)end).longValue():0;
   if(!integral || a<=0 || b<=a || b>now || b-a>604800000L)
    cycle.put("status","error").put("reason","周期时间范围无效");
   else if(now-b>120000L)cycle.put("status","stale").put("reason","周期快照超过120秒，请等待Hook刷新");
  }
  if("missing".equals(status)||"error".equals(status))return o;
  if("weather".equals(kind)){
   long at=iso(o.optString("source_updated_at",""));
   if(at>0 && now-at>21600000L)o.put("status","stale").put("reason","天气观测超过6小时，当前为源应用缓存");
   return o;
  }
  boolean stale=false;
  SimpleDateFormat day=new SimpleDateFormat("yyyy-MM-dd",Locale.US);String today=day.format(new Date(now));
  // Daily totals are never relabelled; historical latest measurements remain available.
  Iterator<String> names=f.keys();
  while(names.hasNext()){
   String name=names.next();JSONObject m=f.optJSONObject(name);if(m==null)continue;
   if((name.startsWith("today_")||"daily_activity".equals(name))&&!today.equals(m.optString("report_date"))){f.put(name,JSONObject.NULL);stale=true;continue;}
   if(name.startsWith("latest_")){
    String time=m.has("sample_time_epoch_seconds")?"sample_time_epoch_seconds":"latest_sleep".equals(name)?"wake_up_epoch_seconds":"sample_time_epoch_seconds";
    long at=m.optLong(time,0)*1000L;boolean isToday=at>0&&today.equals(day.format(new Date(at)));
    m.put("is_today",isToday).put("is_historical",at>0&&!isToday);
    stale|=!"ok".equals(metric(m,time,"latest_sleep".equals(name)?172800000L:21600000L,now));
   }else if(m.has("report_date")){
    boolean isToday=today.equals(m.optString("report_date"));m.put("is_today",isToday).put("is_historical",!isToday);
    if(!isToday)m.put("freshness","stale");stale|=!isToday;
   }
  }
  if(stale)o.put("status","stale").put("reason","部分记录过时或跨日；当天活动仅保留本地今日标签；历史最近记录仍保留，各指标见测量时间与freshness");
  return o;
 }
}

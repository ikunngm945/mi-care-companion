package cn.local.micompanion;
import android.app.AppOpsManager;
import android.app.usage.*;
import android.content.Context;
import android.os.Process;
import org.json.*;
import java.util.*;
import java.text.SimpleDateFormat;

public final class UsageCollector {
 private static final long DAY=86_400_000L;
 private static final int MAX_EVENTS=100_000;
 /** Bounded streaming adapter; the Android and fixture paths share the same integration. */
 interface Reader { Iterator<Event> query(long start,long end) throws Exception; }
 static final class Event {
  final long time; final int type; final String pkg;
  Event(long time,int type,String pkg){this.time=time;this.type=type;this.pkg=pkg;}
 }
 public static String iso(long time) {return new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSXXX",Locale.US).format(new Date(time));}
 public static JSONObject base(String pkg) {
  JSONObject o=new JSONObject(); try {
   o.put("status","missing").put("source_package",pkg).put("collected_at",iso(System.currentTimeMillis()))
    .put("source_updated_at",JSONObject.NULL).put("fields",new JSONObject()).put("reason","尚未采集");
  } catch(JSONException ignored) {} return o;
 }
 public static JSONObject collect(Context c,long start,long end) {
  return collect(c,start,end,System.currentTimeMillis());
 }
 static JSONObject collect(Context c,long start,long end,long now) {
  return collect((a,b)->{
   AppOpsManager ops=(AppOpsManager)c.getSystemService(Context.APP_OPS_SERVICE);
   if(ops.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS,Process.myUid(),c.getPackageName())!=AppOpsManager.MODE_ALLOWED)
    throw new SecurityException();
   UsageStatsManager manager=(UsageStatsManager)c.getSystemService(Context.USAGE_STATS_SERVICE);
   UsageEvents events=manager.queryEvents(a,b);
   return new Iterator<Event>() {
    private final UsageEvents.Event event=new UsageEvents.Event();
    public boolean hasNext(){return events.hasNextEvent();}
    public Event next(){if(!events.getNextEvent(event))throw new NoSuchElementException();return new Event(event.getTimeStamp(),event.getEventType(),event.getPackageName());}
   };
  },start,end,now);
 }
 static JSONObject collect(Reader reader,long start,long end,long now) {
  JSONObject o=base("android");
  try {
   if(start<=0 || end<=start || end>now || end-start>7*DAY)
    return o.put("status","error").put("reason","屏幕统计区间无效：须为不超过7天且终点不晚于采集时刻的健康周期");
   // The previous day establishes initial state; all durations are clipped to the cycle.
   long queryStart=Math.max(0,start-DAY);
   Iterator<Event> events=reader.query(queryStart,end);
   UsageTimeline timeline=new UsageTimeline(start,end); Map<String,Long> totals=new HashMap<>();
   String fg=null;long fgStart=-1,previous=Long.MIN_VALUE; int queried=0,count=0;
   while(events.hasNext()) {
    if(++queried>MAX_EVENTS)return o.put("status","error").put("reason","UsageEvents事件超过100000条上限；未截断统计");
    Event event=events.next();long at=event.time;int type=event.type;
    if(at<previous)return o.put("status","error").put("reason","UsageEvents事件时间乱序；未返回部分统计");
    previous=at;
    if(at<queryStart)continue;
    timeline.event(at,type);if(at>=start && at<=end)count++;
    long clipped=Math.max(start,Math.min(end,at));
    if(type==1) {
     if(fg!=null && fgStart>=0)totals.put(fg,totals.getOrDefault(fg,0L)+Math.max(0,clipped-fgStart));
     fg=event.pkg;fgStart=clipped;
    } else if(type==2 || type==16) {
     if(fg!=null && (type==16 || fg.equals(event.pkg))) {
      totals.put(fg,totals.getOrDefault(fg,0L)+Math.max(0,clipped-fgStart));fg=null;fgStart=-1;
     }
    }
   }
   if(fg!=null)totals.put(fg,totals.getOrDefault(fg,0L)+Math.max(0,end-fgStart));
   ArrayList<Map.Entry<String,Long>> sorted=new ArrayList<>(totals.entrySet());
   Collections.sort(sorted,(a,b)->Long.compare(b.getValue(),a.getValue()));JSONArray apps=new JSONArray();
   for(Map.Entry<String,Long> app:sorted){if(apps.length()==10)break;if(app.getValue()>0)apps.put(new JSONObject().put("package",app.getKey()).put("foreground_ms",app.getValue()));}
   long[] times=timeline.finish();
   JSONObject fields=new JSONObject().put("interval_start",iso(start)).put("interval_end",iso(end))
    .put("timezone",TimeZone.getDefault().getID()).put("screen_on_ms",times[0]).put("unlocked_use_ms",times[1])
    .put("top_apps",apps).put("statistical_basis","UsageEvents健康周期区间积分；源事件缺失会低估，不等于系统数字健康全天精确统计");
   boolean observed=count>0 || times[0]>0 || times[1]>0 || apps.length()>0;
   o.put("status",observed?"ok":"missing").put("fields",fields).put("source_updated_at",iso(end))
    .put("reason",observed?"事件可见范围内统计；未知初始状态不补造":"统计区间无可见事件");
  } catch(SecurityException ex) {try{o.put("reason","未授权使用情况访问；请在系统设置授权");}catch(JSONException ignored){}}
  catch(Exception ex) {try{o.put("status","error").put("reason","读取屏幕使用记录失败："+ex.getClass().getSimpleName());}catch(JSONException ignored){}}
  return o;
 }
}

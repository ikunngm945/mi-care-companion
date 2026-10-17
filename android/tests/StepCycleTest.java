package cn.local.micompanion;
import java.time.Instant;
import java.util.TimeZone;
import org.json.*;
/** Step aggregation through the actual cycle collector, not an isolated replacement algorithm. */
public final class StepCycleTest {
 private static int checks;
 private static void check(boolean ok,String why){checks++;if(!ok)throw new AssertionError(why);}
 private static JSONObject row(long at,Object steps,String sid)throws Exception{
  JSONObject r=new JSONObject().put("time",at).put("value",new JSONObject().put("time",at).put("steps",steps).toString());
  if(sid!=null)r.put("sid",sid);return r;
 }
 private static JSONObject collect(JSONArray rows,long start,long end,String zone)throws Exception{
  JSONArray sleeps=new JSONArray().put(HealthCycleTest.sleep(start-24000,start,400,new JSONArray()))
   .put(HealthCycleTest.sleep(end-24000,end-3600,340,new JSONArray()));
  return HealthCycle.collect((sql,bind)->{
   check(sql.contains("LIMIT "),"step queries remain bounded");
   if(sql.contains("FROM sleep_segment"))return sleeps;
   if(sql.contains("FROM step_record")){
    check(Long.parseLong(bind[0])==start&&Long.parseLong(bind[1])==end,"step SQL must use exact sleep-cycle range");
    return rows;
   }
   return new JSONArray();
  },end*1000,TimeZone.getTimeZone(zone));
 }
 private static JSONObject activity(JSONArray rows,long start,long end,String zone)throws Exception{
  JSONObject c=collect(rows,start,end,zone);check("ok".equals(c.getString("status")),"step failure must not invalidate other cycle fields");return c.getJSONObject("activity");
 }
 public static void main(String[] args)throws Exception{
  long a=Instant.parse("2026-10-16T00:00:00Z").getEpochSecond(),b=a+90000;
  JSONArray mixed=new JSONArray().put(row(a+60,40,"A")).put(row(a+120,60,"A"))
   .put(row(a+60,90,"B")).put(row(a+180,5,"B"))
   .put(row(a+1860,2,"A")).put(row(a+1920,70,"B")).put(row(a+1980,3,"B"));
  JSONObject c=collect(mixed,a,b,"Asia/Shanghai");
  check(c.getJSONObject("activity").optLong("steps",-1)==173,"sum sources within half-hour before max; winning source may change next slot");
  check(!c.toString().contains("sid")&&!c.toString().contains("\"A\""),"step source identities stay private");
  JSONArray reversed=new JSONArray();for(int i=mixed.length()-1;i>=0;i--)reversed.put(mixed.get(i));
  check(activity(reversed,a,b,"Asia/Shanghai").getLong("steps")==173,"input/source ordering cannot change step sum");
  JSONArray duplicate=new JSONArray().put(row(a+60,20,"A")).put(row(a+60,20,"A")).put(row(a+60,30,"B"));
  check(activity(duplicate,a,b,"UTC").getLong("steps")==30,"same source/time duplicate must not double count");
  duplicate.put(row(a+60,21,"A"));
  check(activity(duplicate,a,b,"UTC").isNull("steps"),"same source/time conflicting values remain unknown");
  JSONArray clipped=new JSONArray().put(row(a+600,900,"A")).put(row(a+601,10,"A"))
   .put(row(a+602,30,"B")).put(row(b,7,"A")).put(row(b+1,900,"A"));
  check(activity(clipped,a+600,b,"UTC").getLong("steps")==37,"clip partial first/last slots using (start,end], never use out-of-cycle winner");
  JSONArray edge=new JSONArray().put(row(a+1799,40,"A")).put(row(a+1800,30,"B"));
  check(activity(edge,a,b,"UTC").getLong("steps")==70,"native step half-hour boundary starts new slot, unlike measured HR endpoint bins");
  JSONArray offset=new JSONArray().put(row(a+800,40,"A")).put(row(a+1000,30,"B"));
  check(activity(offset,a,b,"Asia/Kathmandu").getLong("steps")==70,"half-hour is local, not UTC, in quarter-hour-offset zones");
  long d=Instant.parse("2026-11-01T00:00:00Z").getEpochSecond();
  JSONArray fold=new JSONArray().put(row(d+5*3600+600,40,"A")).put(row(d+6*3600+600,30,"B"));
  check(activity(fold,d,d+90000,"America/New_York").getLong("steps")==70,"repeated DST half-hours are distinct real slots");
  check(activity(new JSONArray().put(row(a+60,0,"A")).put(row(a+60,0,"B")),a,b,"UTC").getLong("steps")==0,"measured zero remains zero");
  check(activity(new JSONArray(),a,b,"UTC").isNull("steps"),"no rows is unknown, not zero");
  for(Object bad:new Object[]{-1,1.5,"10",JSONObject.NULL})
   check(activity(new JSONArray().put(row(a+60,bad,"A")),a,b,"UTC").isNull("steps"),"invalid steps must not become a partial sum");
  for(String sid:new String[]{null,""," "})
   check(activity(new JSONArray().put(row(a+60,10,sid)),a,b,"UTC").isNull("steps"),"unknown source must not be combined into a guessed device");
  check(activity(new JSONArray().put(row(a+60,10,"A").put("time",a+61)),a,b,"UTC").isNull("steps"),"JSON/SQL time mismatch must fail safely");
  check(activity(new JSONArray().put(row(a+60,Long.MAX_VALUE,"A")).put(row(a+61,1,"A")),a,b,"UTC").isNull("steps"),"step sum overflow must be unknown");
  check(activity(new JSONArray().put(row(a+60,Long.MAX_VALUE,"A")).put(row(a+1860,1,"B")),a,b,"UTC").isNull("steps"),"cross-slot total overflow must be unknown");
  check(activity(new JSONArray().put(row(a+60,10,"A").put("sid",123)),a,b,"UTC").isNull("steps"),"numeric source must not be silently stringified");
  JSONArray excess=new JSONArray();for(int i=0;i<12001;i++)excess.put(row(a+1+i,1,"A"));
  check(activity(excess,a,b,"UTC").isNull("steps"),"query sentinel must reject truncated aggregates");
  System.out.println("StepCycle: "+checks+" checks passed");
 }
}

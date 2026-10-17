package cn.local.micompanion;
import java.time.Instant;
import java.util.TimeZone;
import org.json.*;
/** Real cycle collector regression cases; expectations are hand-computed, not mirrored aggregation. */
public final class ActivityCycleTest {
 private static int checks;
 private static final long A=Instant.parse("2026-10-16T00:00:00Z").getEpochSecond(),B=A+90000;
 private static void check(boolean b,String why){checks++;if(!b)throw new AssertionError(why);}
 private static JSONObject point(long t,Object n,String sid)throws Exception{
  JSONObject r=new JSONObject().put("time",t).put("key","calories").put("value",new JSONObject().put("time",t).put("calories",n).toString());if(sid!=null)r.put("sid",sid);return r;
 }
 private static JSONObject sport(long a,long b,int type,String sid)throws Exception{
  return point(a,0,sid).put("key","sport-"+type).put("value",new JSONObject().put("start_time",a).put("end_time",b).put("sport_type",type).put("time",a).toString());
 }
 private static JSONObject activity(String table,JSONArray rows,long start,long end,String zone)throws Exception{
  JSONArray sleeps=new JSONArray().put(HealthCycleTest.sleep(start-24000,start,400,new JSONArray())).put(HealthCycleTest.sleep(end-24000,end-3600,340,new JSONArray()));
  JSONObject cycle=HealthCycle.collect((sql,bind)->{
   check(sql.contains("LIMIT "),"all queries bounded");
   if(sql.contains("FROM sleep_segment"))return sleeps;
   if(sql.contains("FROM "+table)){
    check(Long.parseLong(bind[0])==start-("sport_report".equals(table)?604800:0)&&Long.parseLong(bind[1])==end,"query keeps exact cycle and bounded workout lookback");return rows;
   }
   return new JSONArray();
  },end*1000,TimeZone.getTimeZone(zone));
  check("ok".equals(cycle.getString("status")),"metric errors must remain contained");
  check(!cycle.toString().contains("sid")&&!cycle.toString().contains("source-A"),"source identities must not escape");
  return cycle.getJSONObject("activity");
 }
 private static Object metric(String table,String name,JSONArray rows)throws Exception{return activity(table,rows,A,B,"UTC").get(name);}
 private static void calories()throws Exception{
  JSONArray rows=new JSONArray().put(point(A+60,0.4,"source-A")).put(point(A+120,0.6,"source-A"))
   .put(point(A+60,0.9,"source-B")).put(point(A+180,0.05,"source-B"))
   .put(point(A+1860,0.2,"source-A")).put(point(A+1920,0.7,"source-B")).put(point(A+1980,0.03,"source-B"));
  Object measured=metric("calorie_record","calories_kcal",rows);
  check(measured instanceof Number&&((Number)measured).doubleValue()==1.73,"calories must sum each source half-hour before max, preserve decimals and change winners by slot");
  JSONArray reversed=new JSONArray();for(int i=rows.length()-1;i>=0;i--)reversed.put(rows.get(i));
  check(((Number)metric("calorie_record","calories_kcal",reversed)).doubleValue()==1.73,"calories invariant under source/input ordering");
  JSONArray dup=new JSONArray().put(point(A+60,0.1,"source-A")).put(point(A+60,0.10,"source-A")).put(point(A+120,0.2,"source-A"));
  check(((Number)metric("calorie_record","calories_kcal",dup)).doubleValue()==0.3,"identical same-source records dedup and decimals remain exact");dup.put(point(A+60,0.2,"source-A"));
  check(metric("calorie_record","calories_kcal",dup)==JSONObject.NULL,"conflicting same-source/time calorie record remains unknown");
  JSONArray clipped=new JSONArray().put(point(A+600,900,"source-A")).put(point(A+601,0.1,"source-A")).put(point(A+602,0.3,"source-B")).put(point(B,0.07,"source-A")).put(point(B+1,900,"source-B"));
  check(activity("calorie_record",clipped,A+600,B,"UTC").getDouble("calories_kcal")==0.37,"calorie partial slots are clipped before source selection");
  JSONArray edge=new JSONArray().put(point(A+1799,0.4,"source-A")).put(point(A+1800,0.3,"source-B"));
  check(((Number)metric("calorie_record","calories_kcal",edge)).doubleValue()==0.7,"exact calorie half-hour starts new slot");
  JSONArray offset=new JSONArray().put(point(A+800,0.4,"source-A")).put(point(A+1000,0.3,"source-B"));
  check(activity("calorie_record",offset,A,B,"Asia/Kathmandu").getDouble("calories_kcal")==0.7,"calorie slots use local quarter-hour offset");
  long d=Instant.parse("2026-11-01T00:00:00Z").getEpochSecond();
  check(activity("calorie_record",new JSONArray().put(point(d+18600,0.4,"source-A")).put(point(d+22200,0.3,"source-B")),d,d+90000,"America/New_York").getDouble("calories_kcal")==0.7,"DST repeated calorie slots stay distinct");
  check(((Number)metric("calorie_record","calories_kcal",new JSONArray().put(point(A+1,0,"source-A")))).doubleValue()==0,"measured calorie zero retained");
  check(metric("calorie_record","calories_kcal",new JSONArray())==JSONObject.NULL,"empty calories remains unknown");
  for(Object v:new Object[]{-0.1,"0.5",JSONObject.NULL})check(metric("calorie_record","calories_kcal",new JSONArray().put(point(A+1,v,"source-A")))==JSONObject.NULL,"malformed calories never become partial sum");
  for(String sid:new String[]{null,""," "})check(metric("calorie_record","calories_kcal",new JSONArray().put(point(A+1,1,sid)))==JSONObject.NULL,"unknown calorie source cannot be guessed");
  check(metric("calorie_record","calories_kcal",new JSONArray().put(point(A+1,1,"source-A").put("time",A+2)))==JSONObject.NULL,"calorie row/JSON time mismatch fails safely");
 }
 private static void intensity()throws Exception{
  JSONArray rows=new JSONArray().put(point(A,0,"source-A")).put(point(A+60,0,"source-A")).put(point(A+60,0,"source-B")).put(point(A+120,0,"source-B")).put(point(A+136,0,"source-A")).put(point(B,0,"source-B")).put(point(B+1,0,"source-B"));
  Object measured=metric("mh_strength_record","mh_minutes",rows);
  check(measured instanceof Number&&((Number)measured).longValue()==4,"MH must union exact timestamps across sources, not sum sources, max slot counts or silently floor seconds");
  check(metric("mh_strength_record","mh_minutes",new JSONArray())==JSONObject.NULL,"absent MH is unknown not zero");
  check(metric("mh_strength_record","mh_minutes",new JSONArray().put(point(A+1,0,"source-A").put("time",A+2)))==JSONObject.NULL,"MH row/JSON time mismatch fails safely");
 }
 private static void sports()throws Exception{
  JSONArray rows=new JSONArray().put(sport(A+60,A+660,1,"source-A")).put(sport(A+1200,A+1800,2,"source-B"));
  Object measured=metric("sport_report","sport_count",rows);
  check(measured instanceof Number&&((Number)measured).intValue()==2,"nonoverlapping workouts from different sources must count instead of globally returning unknown");
  rows.put(sport(A+60,A+660,1,"source-B"));
  check(((Number)metric("sport_report","sport_count",rows)).intValue()==2,"identical projected workout from another source retains existing exact dedup");
  rows.put(sport(A+65,A+665,1,"source-B"));
  check(metric("sport_report","sport_count",rows)==JSONObject.NULL,"overlapping nonidentical cross-source reports remain ambiguous, never guess tolerance");
  check(((Number)metric("sport_report","sport_count",new JSONArray().put(sport(A-60,A+60,1,"source-A")).put(sport(A+60,A+120,1,"source-B")))).intValue()==2,"cross-start workout counted by completion, touching workouts are distinct");
  check(((Number)metric("sport_report","sport_count",new JSONArray().put(sport(A-60,A,1,"source-A")).put(sport(B-60,B,1,"source-B")).put(sport(B-20,B+10,2,"source-A")))).intValue()==1,"workout completion uses exclusive start inclusive end");
  check(((Number)metric("sport_report","sport_count",new JSONArray())).intValue()==0,"successful empty report query remains zero");
  check(metric("sport_report","sport_count",new JSONArray().put(sport(A+60,A+660,1,"source-A")).put(sport(A+60,A+760,1,"source-A")))==JSONObject.NULL,"conflicting same native report identity remains unknown");
  check(metric("sport_report","sport_count",new JSONArray().put(sport(A+60,A+660,1,"source-A").put("time",A+61)))==JSONObject.NULL,"present JSON time must match SQL DB key time");
  check(metric("sport_report","sport_count",new JSONArray().put(sport(A+60,A+660,1,null)))==JSONObject.NULL,"unknown report source cannot authorize certainty");
  check(metric("sport_report","sport_count",new JSONArray().put(sport(A+60,A+60,1,"source-A")))==JSONObject.NULL,"invalid workout interval fails safely");
  JSONObject dbTime=sport(A+60,A+660,1,"source-A").put("time",A+30);JSONObject dbValue=new JSONObject(dbTime.getString("value")).put("time",A+30);dbTime.put("value",dbValue.toString());
  check(((Number)metric("sport_report","sport_count",new JSONArray().put(dbTime))).intValue()==1,"preserved native dbKeyTime need not equal physical workout start");
  JSONArray nested=new JSONArray().put(sport(A+60,A+1000,1,"source-A")).put(sport(A+120,A+500,2,"source-A")).put(sport(A+600,A+700,3,"source-B"));
  check(metric("sport_report","sport_count",nested)==JSONObject.NULL,"expiring nested span must not forget longer active source");
  JSONArray sameEnd=new JSONArray().put(sport(A+60,A+500,1,"source-A")).put(sport(A+120,A+500,2,"source-A")).put(sport(A+500,A+700,3,"source-B"));
  check(((Number)metric("sport_report","sport_count",sameEnd)).intValue()==3,"same-source same-end spans expire together before touching different-source workout");
  JSONArray multi=new JSONArray().put(sport(A+60,A+500,1,"source-A")).put(sport(A+60,A+500,1,"source-B")).put(sport(A+120,A+300,2,"source-A"));
  check(metric("sport_report","sport_count",multi)==JSONObject.NULL,"exact duplicate provenance must retain every source for later overlap detection");
  JSONArray reverse=new JSONArray().put(multi.get(2)).put(multi.get(1)).put(multi.get(0));
  check(metric("sport_report","sport_count",reverse)==JSONObject.NULL,"ambiguous workout result is order invariant");
 }
 public static void main(String[] args)throws Exception{
  if(args.length==0||"calories".equals(args[0]))calories();
  if(args.length==0||"intensity".equals(args[0]))intensity();
  if(args.length==0||"sports".equals(args[0]))sports();
  for(String table:new String[]{"calorie_record","mh_strength_record","sport_report"}){
   JSONArray tooMany=new JSONArray();for(int i=0;i<12001;i++)tooMany.put(table.equals("sport_report")?sport(A+1+i,A+2+i,1,"source-A"):point(A+1+i,1,"source-A"));
   check(metric(table,table.equals("calorie_record")?"calories_kcal":table.equals("mh_strength_record")?"mh_minutes":"sport_count",tooMany)==JSONObject.NULL,"row sentinel rejects incomplete "+table+" aggregate");
  }
  System.out.println("ActivityCycle: "+checks+" checks passed");
 }
}

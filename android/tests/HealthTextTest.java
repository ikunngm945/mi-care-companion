package cn.local.micompanion;

import java.lang.reflect.InvocationTargetException;
import java.time.Instant;
import org.json.*;

/** Offline contract checks; no Android, network, device, or private database access. */
public final class HealthTextTest {
 private static int groups, failures;
 private interface Check { void run() throws Exception; }
 private static void check(String name,Check c)throws Exception{
  try{c.run();groups++;System.out.println("PASS "+name);}
  catch(AssertionError e){failures++;System.out.println("FAIL "+name+": "+e.getMessage());}
 }
 private static Object call(String method,Class<?>[] types,Object... args)throws Exception{
  try{return Class.forName("cn.local.micompanion.HealthText").getMethod(method,types).invoke(null,args);}
  catch(ClassNotFoundException e){throw new AssertionError("HealthText implementation missing");}
  catch(InvocationTargetException e){Throwable cause=e.getCause();if(cause instanceof Exception)throw (Exception)cause;throw (Error)cause;}
 }
 private static String render(JSONObject h,JSONObject s)throws Exception{return (String)call("render",new Class<?>[]{JSONObject.class,JSONObject.class},h,s);}
 private static String snapshot(String s)throws Exception{return (String)call("snapshot",new Class<?>[]{String.class},s);}
 private static JSONObject o(String s)throws Exception{return new JSONObject(s);}
 private static long ms(String s){return Instant.parse(s).toEpochMilli();}
 private static void has(String s,String token){if(!s.contains(token))throw new AssertionError("missing "+token+" in "+s);}
 private static void no(String s,String token){if(s.contains(token))throw new AssertionError("unexpected "+token+" in "+s);}
 private static void eq(Object want,Object got){if(!want.equals(got))throw new AssertionError("expected "+want+", got "+got);}
 private static JSONObject cycle()throws Exception{
  return new JSONObject().put("status","ok").put("start_ms",ms("2026-01-01T00:00:00Z")).put("end_ms",ms("2026-01-02T00:00:00Z")).put("timezone","Asia/Shanghai")
   .put("activity",o("{\"steps\":1234,\"calories_kcal\":56.5,\"mh_minutes\":7,\"sport_count\":0}"))
   .put("sleep",new JSONArray()).put("heart_rate",new JSONArray()).put("stress",new JSONArray()).put("spo2",new JSONArray());
 }
 private static JSONObject health(JSONObject c)throws Exception{return new JSONObject().put("fields",new JSONObject().put("cycle",c));}
 private static JSONObject screen()throws Exception{return o("{\"fields\":{\"interval_start\":\"2026-01-01T08:00:00+08:00\",\"interval_end\":\"2026-01-02T00:00:00Z\",\"screen_on_ms\":120000,\"unlocked_use_ms\":0,\"top_apps\":[{\"package\":\"cn.example.app\",\"foreground_ms\":61001}]}}");}
 private static JSONObject range(String start,String end,int min,int max)throws Exception{return new JSONObject().put("start_ms",ms(start)).put("end_ms",ms(end)).put("min",min).put("max",max);}
 private static void rejected(String value)throws Exception{try{snapshot(value);throw new AssertionError("invalid JSON accepted");}catch(IllegalArgumentException expected){}}
 public static void main(String[] args)throws Exception{
  // Breaks caught: replacing the whole category must remove metadata, not only fields.
  check("snapshot whitelists categories and preserves weather",()->{
   JSONObject h=health(cycle()).put("account","ACCOUNT_SECRET").put("metadata",o("{\"raw\":\"RAW_SECRET\"}"));
   JSONObject root=new JSONObject().put("health",h).put("screen",screen().put("reason","SCREEN_SECRET")).put("weather",o("{\"fields\":{\"temperature\":18},\"status\":\"ok\"}")).put("usage",o("{\"zero\":0}"));
   String before=root.toString();JSONObject result=o(snapshot(before));
   eq(before,root.toString());eq(root.getJSONObject("weather").toString(),result.getJSONObject("weather").toString());eq(0,result.getJSONObject("usage").getInt("zero"));
   eq(1,result.getJSONObject("health").length());eq(1,result.getJSONObject("screen").length());has(result.getJSONObject("screen").getString("facts"),"已并入健康读取区间");
   for(String token:new String[]{"ACCOUNT_SECRET","RAW_SECRET","SCREEN_SECRET","start_ms","interval_start"})no(result.toString(),token);
  });
  // Breaks caught: epoch leakage, natural-day labels, collapsing activity into one line.
  check("cycle local bounds and separate activity rows",()->{
   String facts=render(health(cycle()),screen());
   has(facts,"健康读取区间：2026-01-01 08:00 ～ 2026-01-02 08:00");has(facts,"Asia/Shanghai");has(facts,"前一次主睡眠结束至本次采集");
   has(facts,"\n活动卡路里：56.5千卡\n");has(facts,"\n步数：1234步\n");has(facts,"\n中高强度活动：7分钟\n");has(facts,"\n运动次数：0次\n");
   no(facts,"1767225600000");no(facts,"1767312000000");no(facts,"自然日");
  });
  // Breaks caught: only first/last segment rendered, dedup of equal-valued intervals.
  check("all measured segments and singleton ranges",()->{
   JSONObject c=cycle();JSONArray many=new JSONArray();
   for(int i=0;i<30;i++)many.put(new JSONObject().put("start_ms",ms("2026-01-01T00:00:00Z")+i*60000L).put("end_ms",ms("2026-01-01T00:01:00Z")+i*60000L).put("min",70+i).put("max",70+i));
   c.put("heart_rate",many).put("stress",new JSONArray().put(range("2026-01-01T01:00:00Z","2026-01-01T01:30:00Z",20,30))).put("spo2",new JSONArray().put(range("2026-01-01T02:00:00Z","2026-01-01T02:30:00Z",98,98)));
   String facts=render(health(c),null);
   for(int i=0;i<30;i++)has(facts,(70+i)+"次/分");has(facts,"2026-01-01 08:29 ～ 2026-01-01 08:30");
   has(facts,"20～30");has(facts,"98%");no(facts,"98～98");no(facts,"70～70");has(facts,"实测散点");has(facts,"半小时");
  });
  // Breaks caught: inferred sleep-state names and silently omitted missing stage vitals.
  check("sleep intervals unknown states and missing stage measurements",()->{
   JSONObject c=cycle();JSONObject sleep=new JSONObject().put("start_ms",ms("2026-01-01T14:00:00Z")).put("end_ms",ms("2026-01-01T23:30:00Z"));
   JSONArray stages=new JSONArray().put(new JSONObject().put("start_ms",ms("2026-01-01T14:00:00Z")).put("end_ms",ms("2026-01-01T14:30:00Z")).put("state",987).put("heart_rate",o("{\"min\":60,\"max\":60}")).put("spo2",JSONObject.NULL))
    .put(new JSONObject().put("start_ms",ms("2026-01-01T14:30:00Z")).put("end_ms",ms("2026-01-01T15:00:00Z")).put("state",1).put("heart_rate",JSONObject.NULL).put("spo2",o("{\"min\":96,\"max\":99}")));
   c.put("sleep",new JSONArray().put(sleep.put("stages",stages)));
   String facts=render(health(c),null);has(facts,"睡眠：2026-01-01 22:00 ～ 2026-01-02 07:30");has(facts,"未知阶段（state=987）");has(facts,"未知阶段（state=1）");
   has(facts,"心率：60次/分；血氧：暂无数据");has(facts,"心率：暂无数据；血氧：96～99%");
   String before=c.toString();render(health(c),null);eq(before,c.toString());
  });
  // Breaks caught: treating raw app state numbers as UI chart indices.
  check("verified source sleep-state mapping",()->{
   JSONObject c=cycle();JSONArray stages=new JSONArray();int[] states={0,2,3,4,5,6};
   for(int state:states)stages.put(new JSONObject().put("start_ms",ms("2026-01-01T14:00:00Z")).put("end_ms",ms("2026-01-01T14:30:00Z")).put("state",state));
   c.put("sleep",new JSONArray().put(new JSONObject().put("start_ms",ms("2026-01-01T14:00:00Z")).put("end_ms",ms("2026-01-01T15:00:00Z")).put("stages",stages)));
   String facts=render(health(c),null);for(String name:new String[]{"未睡眠","深睡","浅睡","快速眼动","清醒","睡眠（未分期）"})has(facts,name);
   no(facts,"未知阶段（state=2）");no(facts,"未知阶段（state=3）");
  });
  // Breaks caught: naps with no stages disappear or receive invented stages.
  check("sleep without stages remains visible",()->{
   JSONObject c=cycle();c.put("sleep",new JSONArray().put(new JSONObject().put("start_ms",ms("2026-01-01T04:00:00Z")).put("end_ms",ms("2026-01-01T04:20:00Z")).put("stages",new JSONArray())));
   String facts=render(health(c),null);has(facts,"睡眠：2026-01-01 12:00 ～ 2026-01-01 12:20");has(facts,"暂无阶段");
  });
  // Breaks caught: null/empty mistaken for zero and whole-day fallback when no cycle exists.
  check("missing cycle and measurements never become daily zero",()->{
   for(String status:new String[]{"missing","error"}){
    JSONObject c=cycle().put("status",status).put("reason","UNTRUSTED_REASON");String facts=render(health(c),screen());
    has(facts,"健康读取区间：暂无数据");has(facts,"步数：暂无数据");no(facts,"1234");no(facts,"2026-01-01");no(facts,"UNTRUSTED_REASON");no(facts,"cn.example.app");
   }
   has(render(health(new JSONObject()),null),"暂无数据");has(render(null,null),"暂无数据");
   JSONObject c=cycle().put("activity",o("{\"steps\":null,\"calories_kcal\":null,\"mh_minutes\":null,\"sport_count\":null}")).put("heart_rate",JSONObject.NULL).put("stress",new JSONArray()).put("spo2",JSONObject.NULL);
   String facts=render(health(c),null);for(String token:new String[]{"活动卡路里：暂无数据","步数：暂无数据","心率：\n暂无数据","压力：\n暂无数据","血氧：\n暂无数据"})has(facts,token);
  });
  // Breaks caught: accepting screen from a different period or losing millisecond precision.
  check("screen requires matching instant bounds",()->{
   JSONObject h=health(cycle()),s=screen();String facts=render(h,s);has(facts,"亮屏时长：2分钟");has(facts,"解锁使用时长：0秒");has(facts,"cn.example.app：1分钟1.001秒");
   s.getJSONObject("fields").put("interval_end","2026-01-02T00:00:00.001Z");facts=render(h,s);has(facts,"屏幕：未知");no(facts,"cn.example.app");no(facts,"亮屏时长：2分钟");
   s=screen();s.getJSONObject("fields").put("interval_start","2026-01-01T08:00:00");has(render(h,s),"屏幕：未知");
  });
  // Breaks caught: blindly forwarding notes, account IDs, unknown fields or package-injected text.
  check("free text and metadata are never health facts",()->{
   JSONObject c=cycle().put("reason","INJECT_REASON").put("timezone_note","INJECT_TIMEZONE").put("measurement_note","INJECT_NOTE").put("unknown",o("{\"account\":\"INJECT_ACCOUNT\"}"));
   c.getJSONObject("activity").put("steps","INJECT_STEPS").put("free_text","INJECT_ACTIVITY");
   JSONObject s=screen();s.getJSONObject("fields").getJSONArray("top_apps").put(o("{\"package\":\"hello\\nINJECT_APP\",\"foreground_ms\":60000}"));
   String facts=render(health(c).put("metadata","INJECT_METADATA"),s);no(facts,"INJECT_");has(facts,"步数：暂无数据");has(facts,"cn.example.app");
  });
  // Breaks caught: wrong machine timezone and wrong UTC offset across daylight-saving changes.
  check("cycle timezone not machine timezone",()->{
   JSONObject c=cycle().put("timezone","America/New_York").put("start_ms",ms("2026-03-07T13:00:00Z")).put("end_ms",ms("2026-03-08T12:00:00Z"));
   has(render(health(c),null),"2026-03-07 08:00 ～ 2026-03-08 08:00");
   c.put("timezone","INJECT_ZONE");has(render(health(c),screen()),"健康读取区间：暂无数据");no(render(health(c),null),"INJECT_ZONE");
  });
  // Breaks caught: partial or invalid measurements suppress later valid segments.
  check("invalid segments do not remove later valid data",()->{
   JSONObject c=cycle();JSONArray a=new JSONArray().put(JSONObject.NULL).put(range("2026-01-01T01:30:00Z","2026-01-01T01:00:00Z",60,65))
    .put(range("2026-01-01T01:00:00Z","2026-01-01T01:30:00Z",90,80)).put(range("2026-01-01T02:00:00Z","2026-01-01T02:30:00Z",66,68));
   c.put("heart_rate",a);String facts=render(health(c),null);has(facts,"2026-01-01 10:00 ～ 2026-01-01 10:30：66～68次/分");no(facts,"90～80");no(facts,"60～65");
  });
  // Breaks caught: truncating the read period at the latest wake instead of collection now.
  check("measurements after latest sleep remain visible until collection",()->{
   JSONObject c=cycle().put("sleep",new JSONArray().put(new JSONObject().put("start_ms",ms("2026-01-01T14:00:00Z")).put("end_ms",ms("2026-01-01T22:00:00Z")).put("stages",new JSONArray())))
    .put("heart_rate",new JSONArray().put(range("2026-01-01T23:00:00Z","2026-01-01T23:30:00Z",72,75)));
   String facts=render(health(c),screen());has(facts,"2026-01-02 07:00 ～ 2026-01-02 07:30：72～75次/分");has(facts,"2026-01-02 08:00");has(facts,"cn.example.app");
  });
  // Breaks caught: losing valid framework usage just because the name has no dot.
  check("framework package remains visible",()->{
   JSONObject s=screen();s.getJSONObject("fields").getJSONArray("top_apps").put(o("{\"package\":\"android\",\"foreground_ms\":1}"));
   has(render(health(cycle()),s),"android：0.001秒");
  });
  // Breaks caught: a tiny finite number expanding beyond the model envelope.
  check("numeric formatting never expands unbounded exponents",()->{
   JSONObject c=cycle();c.getJSONObject("activity").put("calories_kcal",new java.math.BigDecimal("1e-2000000"));
   try{render(health(c),null);throw new AssertionError("unbounded numeric expansion accepted");}catch(IllegalArgumentException expected){no(expected.getMessage(),"2000000");}
  });
  // Breaks caught: org.json's permissive parsing bypasses the strict shared boundary.
  check("strict JSON boundary",()->{
   for(String bad:new String[]{"{'health':{}}","{\"health\":{},\"health\":{}}","{} trailing","{\"health\":{},}","[]"})rejected(bad);
   JSONObject result=o(snapshot("{\"weather\":{\"facts\":\"kept\"}}"));eq("kept",result.getJSONObject("weather").getString("facts"));has(result.getJSONObject("health").getString("facts"),"暂无数据");
  });
  check("missing screen source must not present zero as measured",()->{
    for(String status:new String[]{"missing","error"}){
     JSONObject s=screen().put("status",status);s.getJSONObject("fields").put("screen_on_ms",0).put("unlocked_use_ms",0);
     String facts=render(health(cycle()),s);has(facts,"屏幕：未知");no(facts,"亮屏时长：0秒");no(facts,"cn.example.app");
    }
   });
   if(failures>0)throw new AssertionError("HealthTextTest: "+failures+" failing groups ("+groups+" passed)");
  System.out.println("HealthTextTest: PASS ("+groups+" groups)");
 }
}

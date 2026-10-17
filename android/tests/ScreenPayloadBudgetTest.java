package cn.local.micompanion;
import org.json.*;
public final class ScreenPayloadBudgetTest {
 private static int checks;
 private static void check(boolean ok,String message){checks++;if(!ok)throw new AssertionError(message);}
 private static JSONObject health(int stages,int buckets)throws Exception {
  JSONArray s=new JSONArray(),b=new JSONArray();for(int i=0;i<stages;i++)s.put(new JSONObject().put("stage","light").put("duration_ms",60000));
  for(int i=0;i<buckets;i++)b.put(new JSONObject().put("start_ms",100000000L+i*1800000L).put("steps",i));
  return new JSONObject().put("status","ok").put("fields",new JSONObject().put("cycle",new JSONObject().put("stages",s).put("buckets",b)));
 }
 public static void main(String[] args)throws Exception {
  JSONObject result=EventClient.normalizeCategory(health(512,337),"com.mi.health",1000000000L);
  check(result.getJSONObject("fields").getJSONObject("cycle").getJSONArray("stages").length()==512,"512 stages retained without truncation");
  check(result.getJSONObject("fields").getJSONObject("cycle").getJSONArray("buckets").length()==337,"seven day 337 buckets retained");
  boolean rejected=false;try{EventClient.normalizeCategory(health(513,0),"com.mi.health",1000000000L);}catch(IllegalArgumentException expected){rejected=true;}
  check(rejected,"513 stages explicitly rejected");
  JSONObject event=new JSONObject().put("text",new String(new char[200000]).replace('\u0000','x'));
  check(EventClient.snapshot(event).length()>128*1024,"snapshot allows above old 128KiB cap");
  event.put("text",new String(new char[262133]).replace('\u0000','x'));
  check(EventClient.snapshot(event).getBytes("UTF-8").length==256*1024,"exact 256KiB event accepted");
  event.put("text",new String(new char[90000]).replace('\u0000','中'));
  rejected=false;try{EventClient.snapshot(event);}catch(IllegalArgumentException expected){rejected=true;}
  check(rejected,"over 256KiB UTF8 bytes rejected before persistence or sending");
  System.out.println("ScreenPayloadBudget: "+checks+" checks passed");
 }
}

package cn.local.micompanion;
import org.json.*;
public final class ScreenCycleBoundaryTest {
 private static int checks;
 private static void check(boolean ok,String message){checks++;if(!ok)throw new AssertionError(message);}
 private static JSONObject health(Object start,Object end)throws Exception {
  return new JSONObject().put("status","ok").put("fields",new JSONObject().put("cycle",new JSONObject()
   .put("status","ok").put("start_ms",start).put("end_ms",end).put("timezone","Asia/Shanghai")));
 }
 public static void main(String[] args)throws Exception {
  long[] bounds=EventClient.screenBounds(health(100000000L,100001000L),100002000L);
  check(bounds[0]==100000000L&&bounds[1]==100001000L,"end stays frozen even if older than now");
  JSONObject noCycle=EventClient.collectScreen(null,new JSONObject(),100002000L);
  check(noCycle.getString("status").equals("missing")&&noCycle.getJSONObject("fields").length()==0,"unavailable cycle missing before querying Android; no civil-day fallback");
  for(JSONObject invalid:new JSONObject[]{health("100000000",100001000L),health(100000000.5,100001000L),health(0,100001000L),
    health(100000000L,100000000L),health(100000000L,704800001L)}) {
   check(EventClient.screenBounds(invalid,704800001L)==null,"strict integral cycle bounds and maximum seven days");
  }
  check(EventClient.screenBounds(health(100000000L,100002001L),100002000L)==null,"future cycle rejected");
  JSONObject missing=health(100000000L,100001000L);missing.getJSONObject("fields").getJSONObject("cycle").put("status","missing");
  check(EventClient.screenBounds(missing,100002000L)==null,"cycle status must be ok");
  JSONObject legacy=EventClient.collect(null,"screen",100002000L);
  check(legacy.getString("status").equals("missing")&&legacy.getJSONObject("fields").length()==0,"legacy screen entry cannot fall back to current day");
  JSONObject serialized=new JSONObject(health(100000000L,704800000L).toString());
  check(EventClient.screenBounds(serialized,704800000L)[1]==704800000L,"JSON wire integral times accepted for exact seven day range");
  System.out.println("ScreenCycleBoundary: "+checks+" checks passed");
 }
}

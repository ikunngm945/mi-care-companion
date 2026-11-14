package cn.local.micompanion;
import org.json.JSONObject;
/** Starts once, polls without holding the module's socket reader or replaying a live job. */
final class WeatherCloudRefresh {
 interface Clock { long millis(); }
 interface Wait { void sleep(long ms)throws InterruptedException; }
 static JSONObject run(McpServer.Gateway transport,Clock clock,Wait wait)throws Exception {
  long deadline=clock.millis()+60000;
  JSONObject result=null;
  try{result=transport.query(new JSONObject().put("action","weather_refresh"));}catch(java.io.IOException ambiguousDelivery){}
  // Lost replies do not cancel native HTTP: retain the provider lease and only poll, never reissue.
  while(result==null||"pending".equals(result.optString("status"))) {
   if(clock.millis()>=deadline)return new JSONObject().put("status","error").put("reason","等待天气云端刷新超时；原生任务可能仍在执行，未重复发起");
   wait.sleep(Math.min(1000,deadline-clock.millis()));
   try{result=transport.query(new JSONObject().put("action","weather_refresh_status"));}
   catch(java.io.IOException reconnecting){result=null;}
  }
  return result;
 }
}

package cn.local.micompanion.weather;
import java.util.*;
import org.json.JSONObject;
public final class WeatherCloudClientTest {
 public static void main(String[] args)throws Exception {
  List<Runnable> queue=new ArrayList<>();int[] calls={0};
  WeatherCloudClient c=new WeatherCloudClient(queue::add,()->{calls[0]++;});
  JSONObject start=c.handle(new JSONObject().put("action","weather_refresh"));
  check(start.getString("status").equals("pending")&&calls[0]==0,"socket returns without running cloud request");
  c.handle(new JSONObject().put("action","weather_refresh"));
  check(queue.size()==1,"active request cannot reenter or enqueue duplicate native call");
  queue.remove(0).run();
  JSONObject done=c.handle(new JSONObject().put("action","weather_refresh_status"));
  check(done.getString("status").equals("ok")&&done.getLong("completed_at")>0&&calls[0]==1,"only completed native/persist/publish returns ok");
  WeatherCloudClient bad=new WeatherCloudClient(queue::add,()->{throw new Exception("https://secret.invalid?token=hidden");});
  bad.handle(new JSONObject().put("action","weather_refresh"));queue.remove(0).run();
  JSONObject failed=bad.handle(new JSONObject().put("action","weather_refresh_status"));
  check(failed.getString("status").equals("error")&&!failed.toString().contains("secret")&&!failed.toString().contains("hidden"),"errors never leak native URL or credentials");
  System.out.println("WeatherCloudClientTest OK");
 }
 private static void check(boolean b,String m){if(!b)throw new AssertionError(m);}
}

package cn.local.micompanion;
import org.json.*;
public final class WeatherCloudRefreshTest {
 public static void main(String[] args)throws Exception {
  final int[] calls={0};
  JSONObject ok=WeatherCloudRefresh.run(request->{calls[0]++;return new JSONObject().put("status","ok").put("completed_at",1234);},()->0,ms->{});
  if(!"ok".equals(ok.getString("status"))||calls[0]!=1)throw new AssertionError("completed native refresh returned without retransmit");
  final long[] time={0};calls[0]=0;
  JSONObject pending=WeatherCloudRefresh.run(request->{calls[0]++;String action=request.getString("action");
   if(calls[0]==1&&!"weather_refresh".equals(action))throw new AssertionError("first request starts cloud refresh");
   if(calls[0]>1&&!"weather_refresh_status".equals(action))throw new AssertionError("pending must only poll status");
   return new JSONObject().put("status",calls[0]<3?"pending":"ok");},()->time[0],ms->time[0]+=ms);
  if(!"ok".equals(pending.getString("status"))||calls[0]!=3)throw new AssertionError("pending polled until completion");
  time[0]=0;calls[0]=0;
  JSONObject timeout=WeatherCloudRefresh.run(request->{calls[0]++;return new JSONObject().put("status","pending");},()->time[0],ms->time[0]+=ms);
  if(!"error".equals(timeout.getString("status"))||time[0]>65000)throw new AssertionError("bounded wait, no blind replay");
  JSONObject failure=WeatherCloudRefresh.run(request->new JSONObject().put("status","error").put("reason","网络请求失败"),()->0,ms->{});
  if(!"网络请求失败".equals(failure.getString("reason")))throw new AssertionError("preserve safe native failure");
  calls[0]=0;time[0]=0;
   JSONObject recovered=WeatherCloudRefresh.run(request->{calls[0]++;
    if(calls[0]==1)throw new java.io.IOException("ambiguous delivery");
    if(!"weather_refresh_status".equals(request.getString("action")))throw new AssertionError("never restart after lost reply");
    if(calls[0]==2)throw new java.io.IOException("reconnecting");
    return new JSONObject().put("status","ok");},()->time[0],ms->time[0]+=ms);
   if(!"ok".equals(recovered.getString("status"))||calls[0]!=3)throw new AssertionError("keep lease alive through transient transport failures");
   System.out.println("WeatherCloudRefreshTest OK");
 }
}

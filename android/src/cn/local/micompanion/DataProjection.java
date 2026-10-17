package cn.local.micompanion;
import org.json.*;
/** Pure projection: never exports raw records, identifiers or unverified units. */
public final class DataProjection {
 private static Object num(JSONObject o,String key,double lo,double hi){
  double n=o.optDouble(key,Double.NaN);return Double.isNaN(n)||Double.isInfinite(n)||n<lo||n>hi?JSONObject.NULL:n;
 }
 private static JSONObject quantity(JSONObject o)throws JSONException{
  return new JSONObject().put("value",num(o,"value",-100000,100000)).put("unit",o.optString("unit","unknown"));
 }
 public static JSONObject weather(JSONObject raw,String desc,String city)throws JSONException{
  JSONObject c=raw.optJSONObject("current"),f=new JSONObject();if(c==null)return f;
  f.put("city",city==null?JSONObject.NULL:city).put("description",desc==null?JSONObject.NULL:desc);
  for(String k:new String[]{"temperature","feelsLike","humidity","pressure","visibility"})if(c.optJSONObject(k)!=null)f.put(k,quantity(c.getJSONObject(k)));
  if(c.optJSONObject("wind")!=null){JSONObject w=c.getJSONObject("wind"),p=new JSONObject();for(String k:new String[]{"speed","direction"})if(w.optJSONObject(k)!=null)p.put(k,quantity(w.getJSONObject(k)));f.put("wind",p);}
  f.put("weather_code",c.optString("weather","")).put("observed_at",c.optString("pubTime","")).put("uv_index",num(c,"uvIndex",0,100));
  JSONObject aq=raw.optJSONObject("aqi");if(aq!=null)f.put("aqi",num(aq,"aqi",0,5000)).put("aqi_observed_at",aq.optString("pubTime",""));
  return f;
 }
 public static JSONObject step(JSONObject value,String date,boolean today)throws JSONException{
  return new JSONObject().put("steps",num(value,"steps",0,1000000)).put("report_date",date).put("is_today",today)
    .put("distance",JSONObject.NULL).put("activity_kcal",JSONObject.NULL).put("units_note","距离和热量缩放未验证，暂不输出");
 }
 public static JSONObject heart(JSONObject value)throws JSONException{
  return new JSONObject().put("bpm",num(value,"bpm",1,300)).put("sample_time_epoch_seconds",num(value,"time",1,5000000000d));
 }
 public static JSONObject sleep(JSONObject value)throws JSONException{
  return new JSONObject().put("duration_minutes",num(value,"duration",0,2880)).put("bedtime_epoch_seconds",num(value,"bedtime",1,5000000000d))
    .put("wake_up_epoch_seconds",num(value,"wake_up_time",1,5000000000d));
 }
}

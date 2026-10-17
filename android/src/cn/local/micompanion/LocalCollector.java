package cn.local.micompanion;
import android.content.Context;
import org.json.*;
/** Dispatches within the selected source UID; neither collector changes source data. */
public final class LocalCollector {
 public static volatile String activeHealthPath;
 public static JSONObject collect(Context c,String kind){
  JSONObject base=UsageCollector.base("weather".equals(kind)?"com.miui.weather2":"com.mi.health");
  try{return "weather".equals(kind)?WeatherSource.collect(c,base):HealthSource.collect(c,base);}
  catch(Exception ex){try{base.put("status","error").put("reason","只读采集失败："+ex.getClass().getSimpleName()+"；可能应用版本或表结构已改变");}catch(Exception ignored){}return base;}
 }
}

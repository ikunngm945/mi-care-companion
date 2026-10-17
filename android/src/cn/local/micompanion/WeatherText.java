package cn.local.micompanion;

import java.util.*;
import java.time.*;
import org.json.*;

/** Deterministic presentation only; the persisted source snapshot is never modified. */
public final class WeatherText {
 private WeatherText(){}
 public static String snapshot(String value)throws Exception{
  try{return snapshotChecked(value);}catch(Exception e){throw new IllegalArgumentException("天气快照格式或整理结果无效，已停止，未截断事实");}
 }
 private static String snapshotChecked(String value)throws Exception{
  RouteResolver.validateJsonObject(value);
  JSONObject root=new JSONObject(value),weather=root.optJSONObject("weather");
  if(weather==null||weather.optJSONObject("fields")==null)return value;
  weather.put("facts",format(weather,root.optString("collected_at","")));weather.remove("fields");
  return bounded(json(root));
 }
 public static String format(JSONObject category,String collectedAt)throws Exception{
  try{return formatChecked(category,collectedAt);}catch(Exception e){throw new IllegalArgumentException("天气字段格式或整理结果无效，已停止，未截断事实");}
 }
 private static String formatChecked(JSONObject category,String collectedAt)throws Exception{
  RouteResolver.validateJsonObject(category.toString());
  if(category.opt("collected_at") instanceof String)collectedAt=category.getString("collected_at");
  JSONObject fields=category.optJSONObject("fields");if(fields==null)return "";
  StringBuilder out=new StringBuilder("天气事实（未知不补零；预报/观测/缓存时间分开；分钟序列间隔和单位未验证时不推算）：\n");
  List<String> ordered=keys(fields);
   final List<String> primary=Arrays.asList("observed_at","weather_code","description","temperature","feelsLike","humidity","wind","uv_index","pressure","visibility","today","forecast_daily","forecast_hourly","air_quality","alerts","life_indices","precipitation");
   Collections.sort(ordered,(a,b)->{int x=primary.indexOf(a),y=primary.indexOf(b);return Integer.compare(x<0?primary.size():x,y<0?primary.size():y);});
   for(String key:ordered){
   Object v=fields.opt(key);
   if(technical(key,v))continue;
   if("forecast_hourly".equals(key)&&v instanceof JSONArray)hourly(out,(JSONArray)v);
   else if("forecast_daily".equals(key)&&v instanceof JSONArray){JSONArray a=(JSONArray)v;out.append("日预报：\n");for(int i=0;i<a.length();i++)out.append(daily(a.opt(i),collectedAt)).append('\n');}
   else if("today".equals(key)&&v instanceof JSONObject)out.append("源首日：").append(daily(v,collectedAt)).append('\n');
   else out.append(label(key)).append("weather_code".equals(key)?name(v):"uv_index".equals(key)?uv(v):show(v)).append('\n');
  }
  return bounded(out.toString());
 }
 private static List<String> keys(JSONObject o){List<String> k=new ArrayList<>();for(Iterator<String> i=o.keys();i.hasNext();)k.add(i.next());Collections.sort(k);return k;}
 private static void hourly(StringBuilder out,JSONArray a)throws Exception{
  out.append("逐小时｜序号/温度时点｜天气｜温度｜AQI｜降水概率｜风；各项独立源时间保留\n");
  String lastTimes="";
  for(int p=0;p<a.length();p++){
   Object v=a.opt(p);if(!(v instanceof JSONObject)){out.append('[').append(p).append(']').append(show(v)).append('\n');continue;}
   JSONObject o=(JSONObject)v,t=o.optJSONObject("source_times");
   if(t==null){if(o.has("source_times"))out.append("原源时间：").append(show(o.opt("source_times"))).append('\n');t=new JSONObject();}String times=json(t);
   if(!times.equals(lastTimes)){out.append("源起点：").append(show(t)).append('\n');lastTimes=times;}
   Object index=o.opt("hour_index");String main=hour(t.opt("temperature"),index);
   out.append('[').append(show(index)).append("] ").append(main);
   for(String k:new String[]{"weather","aqi","precipitationProbability"}){
    String part="weather".equals(k)?"天气":"aqi".equals(k)?"AQI":"降水概率";
    if(!validTime(t.opt(k)))out.append('；').append(part).append("源时间未知");
    else if(!validTime(t.opt("temperature"))||!sameTime(t.opt(k).toString(),t.opt("temperature").toString()))out.append('；').append(part).append("源时间不同，勿认作同一小时");
   }
   for(String key:keys(o))if(!"source_times".equals(key)&&!"hour_index".equals(key)&&!technical(key,o.opt(key))){
    Object item=o.opt(key);out.append("；").append(label(key));
    if("weather_code".equals(key))out.append(name(item));
    else {out.append(show(item));if("wind".equals(key)&&item instanceof JSONObject){Object dt=((JSONObject)item).opt("datetime");if(validTime(main)&&validTime(dt)&&!sameTime(main,dt.toString()))out.append("；风时点冲突=").append(dt);}}
   }
   out.append('\n');
  }
 }
 private static boolean validTime(Object v){if(missing(v))return false;try{OffsetDateTime.parse(v.toString());return true;}catch(java.time.format.DateTimeParseException e){return false;}}
 private static boolean sameTime(String a,String b){try{return OffsetDateTime.parse(a).toInstant().equals(OffsetDateTime.parse(b).toInstant());}catch(java.time.format.DateTimeParseException e){return false;}}
 private static String hour(Object start,Object index){
  if(missing(start)||!(index instanceof Number))return "时点未知";
  double n=((Number)index).doubleValue();if(n<0||n>10000||n!=Math.rint(n))return "时点未知";
  try{return OffsetDateTime.parse(start.toString()).plusHours((long)n).toString();}catch(java.time.DateTimeException e){return "时点未知";}
 }
 private static String daily(Object v,String at)throws Exception{
  if(!(v instanceof JSONObject))return show(v);JSONObject o=(JSONObject)v;StringBuilder s=new StringBuilder();
  String date=show(o.opt("forecast_date"));s.append(date);
  try{
   OffsetDateTime collected=OffsetDateTime.parse(at);ZoneOffset zone=null;
   if(o.opt("source_updated_at") instanceof String)zone=OffsetDateTime.parse(o.getString("source_updated_at")).getOffset();
   else if(!at.endsWith("Z"))zone=collected.getOffset();
   if(zone!=null&&LocalDate.parse(date).equals(collected.toInstant().atOffset(zone).toLocalDate()))s.append("（采集当天）");
  }catch(java.time.format.DateTimeParseException ignored){}
  for(String key:keys(o))if(!"forecast_date".equals(key)&&!technical(key,o.opt(key)))s.append('；').append(label(key)).append("weather_day_code".equals(key)||"weather_night_code".equals(key)?name(o.opt(key)):show(o.opt(key)));
  return s.toString();
 }
 private static String bounded(String s){if(s.length()>1048576)throw new IllegalArgumentException("天气整理结果超过1Mi字符上限，已停止，未截断事实");return s;}
 private static String label(String k){
  switch(k){
   case "weather_code":return "天气：";case "uv_index":return "紫外线：";case "temperature_high":return "最高";case "temperature_low":return "最低";
   case "weather_day_code":return "白天：";case "weather_night_code":return "夜间：";case "description":return "描述：";
   case "observed_at":return "观测时间：";case "source_updated_at":return "源时间：";case "cache_updated_at_epoch_ms":return "缓存毫秒：";
   case "formatted_cache_updated_at_epoch_ms":return "格式化缓存毫秒：";case "source_notes":return "来源说明：";
   case "temperature":return "气温：";case "feelsLike":return "体感：";case "humidity":return "湿度：";case "pressure":return "气压：";
   case "visibility":return "能见度：";case "air_quality":return "空气质量：";case "aqi":return "AQI：";case "aqi_observed_at":return "AQI时间：";
   case "wind":return "风：";case "speed":return "速度：";case "direction":return "方向：";case "datetime":return "时点：";
   case "sunrise":return "日出：";case "sunset":return "日落：";case "precipitation_probability":return "降水概率：";
   case "hourly_description":return "小时预报说明：";case "hourly_precipitation_description":return "小时降水说明：";
   case "precipitation":return "分钟降水：";case "values":return "原序列：";case "probabilities":return "概率序列：";
   case "alerts":return "预警：";case "life_indices":return "生活指数：";case "title":return "标题：";case "detail":return "正文：";
   case "summary":return "摘要：";case "extra":return "补充：";case "detail_title":return "详情标题：";case "detail_summary":return "详情：";
   case "defense":return "防御建议：";case "ends_at":return "结束：";case "published_at_source":return "原发布时间：";case "suggest":return "建议：";
   default:return k+"：";
  }
 }
 private static boolean technical(String k,Object v){
  if(!(k.endsWith("_note")||"time_note".equals(k))||!(v instanceof String))return false;
  return Arrays.asList(
   "各数组保留自己的pubTime及序号；无明确有效时点时不推算小时，wind.datetime单独保留",
   "observed_at为原始观测时间；格式化SQL字段仅有缓存更新时间，不冒充观测时间",
   "白名单缓存投影；最多24小时/15天/120降水点/8预警/12指数；未知单位为null，不主动请求刷新",
   "源日预报基准时间；day_index=0为源预报首日，非SQL day编号",
   "源预报首日，不保证为采集当天；请按forecast_date及source_updated_at判断",
   "污染物浓度单位未标注；不假设mg/m³或μg/m³",
   "AQI源发布时间；SQL回退时间可能缺失，不以采集时间替代",
   "分钟降水原数组序号；未验证采样间隔及浓度/概率单位，不推算时点",
   "生活指数源发布时间",
   "仅保留显式带偏移endTime；本机缓存未见结束字段，不推测解除时点",
   "原预警pubTime；SQL pub_time可能无时区，仅原样保留，不伪造epoch"
  ).contains(v);
 }
 private static String uv(Object v)throws Exception{
  if(missing(v))return "未知";
  try{double d=Double.parseDouble(v.toString());if(d>=0&&d<=100&&d==Math.rint(d))return show(v)+"（"+(d<=2?"弱":d<=5?"中":d<=7?"强":d<=10?"很强":"极强")+"）";}catch(NumberFormatException ignored){}
  return show(v);
 }
 private static String name(Object value)throws Exception{
  String[] names={"晴","多云","阴","雾","特大暴雨","大暴雨","暴雨","雷阵雨","阵雨","大雨","中雨","小雨","雨夹雪","暴雪","阵雪","大雪","中雪","小雪","强沙尘暴","沙尘暴","沙尘","扬沙","冰雹","浮尘","霾","冻雨"};
  int[] raw={0,1,2,18,53,3,7,13,14,6,33,26,21,54,4,55,29,30,56,5,15,8,57,32,27,22,16,49,20,9,28,23,19,58,31,17,10,24,11,25,12,301};
  int[] mapped={0,1,2,3,24,8,11,14,17,12,17,16,10,24,7,24,23,21,24,22,16,10,3,3,15,9,15,3,19,9,13,6,25,3,18,13,7,5,5,4,4,11};
  try{double d=Double.parseDouble(String.valueOf(value));if(d==Math.rint(d))for(int i=0;i<raw.length;i++)if(d==raw[i])return names[mapped[i]];}catch(NumberFormatException ignored){}
  return "未知天气（原码"+show(value)+"）";
 }
 private static boolean missing(Object v){return v==null||v==JSONObject.NULL;}
 private static String show(Object v)throws JSONException{
  if(missing(v))return "未知";
  if(v instanceof JSONObject){JSONObject o=(JSONObject)v;StringBuilder s=new StringBuilder();
   if(o.has("value"))s.append(show(o.opt("value"))).append(missing(o.opt("unit"))?"（单位未知）":show(o.opt("unit")));
   for(String k:keys(o))if((!o.has("value")||!"value".equals(k)&&!"unit".equals(k))&&!technical(k,o.opt(k))){if(s.length()>0)s.append('；');s.append(label(k)).append(show(o.opt(k)));}
   boolean pure=o.has("value");for(String k:keys(o))if(!"value".equals(k)&&!"unit".equals(k)&&!technical(k,o.opt(k)))pure=false;
   return pure?s.toString():"{"+s+"}";
  }
  if(v instanceof JSONArray){JSONArray a=(JSONArray)v;StringBuilder s=new StringBuilder("[");for(int i=0;i<a.length();i++){if(i>0)s.append(',');Object item=a.opt(i);s.append(item instanceof String?quote((String)item):show(item));}return s.append(']').toString();}
  return v instanceof String?(String)v:json(v);
 }
 private static String quote(String value){
  StringBuilder s=new StringBuilder("\"");
  for(int i=0;i<value.length();i++){char c=value.charAt(i);switch(c){
   case '"':s.append("\\\"");break;case '\\':s.append("\\\\");break;
   case '\n':s.append("\\n");break;case '\r':s.append("\\r");break;case '\t':s.append("\\t");break;case '\b':s.append("\\b");break;case '\f':s.append("\\f");break;
   default:if(c<32)s.append(String.format(Locale.ROOT,"\\u%04x",(int)c));else s.append(c);
  }}return s.append('"').toString();
 }
 private static String json(Object v)throws JSONException{
  if(missing(v))return "null";
  if(v instanceof JSONObject){JSONObject o=(JSONObject)v;StringBuilder s=new StringBuilder("{");for(String k:keys(o)){if(s.length()>1)s.append(',');s.append(quote(k)).append(':').append(json(o.opt(k)));}return s.append('}').toString();}
  if(v instanceof JSONArray){JSONArray a=(JSONArray)v;StringBuilder s=new StringBuilder("[");for(int i=0;i<a.length();i++){if(i>0)s.append(',');s.append(json(a.opt(i)));}return s.append(']').toString();}
  if(v instanceof Number){java.math.BigDecimal n=new java.math.BigDecimal(v.toString()).stripTrailingZeros();long length=(long)n.precision()+Math.abs((long)n.scale())+3;if(length>1048576)throw new IllegalArgumentException("天气数值超过可整理范围");return n.toPlainString();}
  return v instanceof String?quote((String)v):v.toString();
 }
}

package cn.local.micompanion;

import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.*;
import org.json.*;

/** Pure, bounded allowlist projection. No raw JSON, location, links or source identifiers escape. */
public final class WeatherProjection {
 private WeatherProjection() {}
 private static final int FIELD_BYTES=56000;
 private static JSONObject obj(JSONObject o,String k) { JSONObject v=o==null?null:o.optJSONObject(k);return v==null?new JSONObject():v; }
 private static JSONArray arr(JSONObject o,String k) { JSONArray v=o==null?null:o.optJSONArray(k);return v==null?new JSONArray():v; }
 private static boolean success(JSONObject o) { return !o.has("status")||"0".equals(String.valueOf(o.opt("status"))); }
 private static JSONArray values(JSONObject o,String key) { JSONObject sub=obj(o,key);return success(o)&&success(sub)?arr(sub,"value"):new JSONArray(); }
 private static Object text(Object v,int max) {
  if(!(v instanceof String)||((String)v).trim().isEmpty())return JSONObject.NULL;
  String s=(String)v;int end=Math.min(s.length(),max);
  if(end>0&&end<s.length()&&Character.isHighSurrogate(s.charAt(end-1)))end--;
  return s.substring(0,end);
 }
 private static Object number(Object v,double lo,double hi) {
  if(!(v instanceof Number)&&!(v instanceof String))return JSONObject.NULL;
  try { double n=Double.parseDouble(v.toString());return Double.isNaN(n)||Double.isInfinite(n)||n<lo||n>hi?JSONObject.NULL:n; }
  catch(NumberFormatException e) { return JSONObject.NULL; }
 }
 private static Object stamp(Object v) {
  if(!(v instanceof String)||((String)v).length()>64)return JSONObject.NULL;
  try { OffsetDateTime.parse((String)v);return v; }catch(DateTimeParseException e) { return JSONObject.NULL; }
 }
 private static long epoch(Object v) { try { return OffsetDateTime.parse(v.toString()).toInstant().toEpochMilli(); }catch(Exception e) { return 0; } }
 private static JSONObject quantity(Object value,Object unit,double lo,double hi) throws JSONException {
  return new JSONObject().put("value",number(value,lo,hi)).put("unit",text(unit,32));
 }
 private static JSONObject quantity(JSONObject q,double lo,double hi) throws JSONException { return quantity(q.opt("value"),q.opt("unit"),lo,hi); }
 private static void provenance(JSONObject o,Object at,String note) throws JSONException {
  o.put("source_updated_at",stamp(at)).put("time_note",note);
 }
 private static JSONObject formatted(Object value,double lo,double hi) throws JSONException {
  // Only suffixes actually carried by the value are units; the undocumented app unit enum is not used.
  if(value instanceof String) {
   String s=((String)value).trim();
   for(String unit:new String[]{"℃","℉","°C","°F","%","hPa","km/h","km","m/s","m"})
    if(s.endsWith(unit))return quantity(s.substring(0,s.length()-unit.length()).trim(),unit,lo,hi);
  }
  return quantity(value,null,lo,hi);
 }
 private static Object at(JSONObject o,String key,int i) { return values(o,key).opt(i); }
 private static JSONObject pair(JSONObject o,String key,int i) { Object v=at(o,key,i);return v instanceof JSONObject?(JSONObject)v:new JSONObject(); }
 private static Object unit(JSONObject o,String key) { return obj(o,key).opt("unit"); }
 private static int count(JSONObject o,String... keys) {int n=0;for(String k:keys)n=Math.max(n,values(o,k).length());return n;}
 private static void boundedPut(JSONObject fields,String key,Object value) throws JSONException {
  fields.put(key,value);
  if(bytes(fields)>=FIELD_BYTES)fields.remove(key);
 }
 private static void boundedAppend(JSONObject fields,String key,JSONObject item) throws JSONException {
  JSONArray a=arr(fields,key);a.put(item);fields.put(key,a);
  int sectionLimit="alerts".equals(key)?24000:"life_indices".equals(key)?10000:18000;
  if(bytes(fields)>=FIELD_BYTES||a.toString().getBytes(StandardCharsets.UTF_8).length>sectionLimit)a.remove(a.length()-1);
 }
 private static int bytes(JSONObject o) { return o.toString().getBytes(StandardCharsets.UTF_8).length; }

 /** cache keys are weather (one safe SQL row), aqi, alerts, minute and insert_time. */
 public static JSONObject project(JSONObject raw,JSONObject cache) throws JSONException {
  if(raw==null)raw=new JSONObject();if(cache==null)cache=new JSONObject();
  JSONObject fields=new JSONObject(),current=obj(raw,"current"),sql=obj(cache,"weather");
  fields.put("description",text(sql.opt("description"),1024));
  fields.put("observed_at",stamp(current.opt("pubTime")));
  fields.put("current_time_note","observed_at为原始观测时间；格式化SQL字段仅有缓存更新时间，不冒充观测时间");
  fields.put("cache_updated_at_epoch_ms",number(cache.opt("insert_time"),1,5000000000000d));
  fields.put("formatted_cache_updated_at_epoch_ms",number(sql.opt("publish_time"),1,5000000000000d));
  String[] names={"temperature","feelsLike","humidity","pressure","visibility"};
  String[] columns={"temperature","feel_temperature","humidity","pressure","visibility"};
  double[] lows={-150,-150,0,0,0},highs={150,150,100,2000,100000};
  for(int i=0;i<names.length;i++) {
   JSONObject q=current.optJSONObject(names[i]);
   if(q!=null)fields.put(names[i],quantity(q,lows[i],highs[i]));
   else if(sql.has(columns[i])) {
    JSONObject fallback=formatted(sql.opt(columns[i]),lows[i],highs[i]);
    if("pressure".equals(names[i]))fallback.put("unit",text(sql.opt("pressure_unit"),32));
    fields.put(names[i],fallback);
   }
  }
  JSONObject wind=new JSONObject(),w=obj(current,"wind");
  for(String k:new String[]{"speed","direction"})if(w.optJSONObject(k)!=null)wind.put(k,quantity(obj(w,k),0,"direction".equals(k)?360:1000));
  if(sql.has("wind"))wind.put("description",text(sql.opt("wind"),256));
  if(sql.has("wind_direction"))wind.put("direction_formatted",text(sql.opt("wind_direction"),128));
  if(sql.has("wind_power"))wind.put("power_formatted",text(sql.opt("wind_power"),128));
  if(wind.length()>0)fields.put("wind",wind);
  fields.put("weather_code",text(current.opt("weather"),32));
  fields.put("uv_index",number(current.has("uvIndex")?current.opt("uvIndex"):sql.opt("ultraviolet"),0,100));
  JSONObject aq=airQuality(raw,cache);if(aq.length()>0)fields.put("air_quality",aq);
  if(aq.has("aqi"))fields.put("aqi",aq.opt("aqi"));
  fields.put("aqi_observed_at",aq.opt("source_updated_at")==null?JSONObject.NULL:aq.opt("source_updated_at"));
  // Reserve high-value alert body/index text before forecast arrays consume the byte budget.
  JSONArray alerts=arr(raw,"alerts");if(alerts.length()==0)alerts=arr(cache,"alerts");
  fields.put("alerts",new JSONArray());
  for(int i=0;i<Math.min(8,alerts.length());i++)if(alerts.optJSONObject(i)!=null)boundedAppend(fields,"alerts",alert(alerts.optJSONObject(i)));
  informationIndices(fields,obj(cache,"information"),cache.opt("information_insert_time"));
  indices(fields,obj(raw,"indices"));
  JSONObject daily=obj(raw,"forecastDaily");
  fields.put("forecast_daily",new JSONArray());
  int days=Math.min(15,count(daily,"temperature","weather","sunRiseSet","precipitationProbability","aqi"));
  JSONObject dw=obj(daily,"wind");days=Math.min(15,Math.max(days,count(dw,"speed","direction")));
  for(int i=0;i<days;i++)boundedAppend(fields,"forecast_daily",daily(daily,i));
  JSONObject today=days>0?daily(daily,0):new JSONObject();
  if(days==0) {
   for(String k:new String[]{"tmphighs","tmplows"})if(sql.has(k))today.put("tmphighs".equals(k)?"temperature_high":"temperature_low",quantity(sql.opt(k),null,-150,150));
   for(String k:new String[]{"sunrise","sunset","temperature_range","next_hour_rain_probability"})if(sql.has(k))today.put(k+"_formatted",text(sql.opt(k),256));
   provenance(today,null,"SQL日值仅含缓存时间；day映射见采集说明，温度/日出日落单位未显式提供，不推测");
  }
  today.put("day_note","源预报首日，不保证为采集当天；请按forecast_date及source_updated_at判断");
  fields.put("today",today);
  hourly(fields,obj(raw,"forecastHourly"));
  JSONObject minute=obj(cache,"minute");if(minute.length()==0)minute=obj(raw,"minutely");
  boundedPut(fields,"precipitation",precipitation(minute));
  fields.put("projection_note","白名单缓存投影；最多24小时/15天/120降水点/8预警/12指数；未知单位为null，不主动请求刷新");
  return fields;
 }
 private static JSONObject airQuality(JSONObject raw,JSONObject cache) throws JSONException {
  JSONObject a=obj(raw,"aqi"),s=obj(cache,"aqi"),out=new JSONObject();if(!success(a))a=new JSONObject();
  for(String key:new String[]{"aqi","pm25","pm10","so2","no2","co","o3"}) {
   JSONObject source=a.has(key)?a:s;if(!source.has(key))continue;
   if("aqi".equals(key))out.put(key,number(source.opt(key),0,5000));
   else {
    JSONObject q=quantity(source.opt(key),null,0,100000);
    provenance(q,source==a?a.opt("pubTime"):s.opt("pub_time"),"污染物浓度单位未标注；不假设mg/m³或μg/m³");out.put(key,q);
   }
  }
  for(String k:new String[]{"primary","suggest","pm25Desc","pm10Desc","no2Desc","so2Desc","coDesc","o3Desc"})if(a.has(k))out.put(k,text(a.opt(k),512));
  if(out.length()>0)provenance(out,a.length()>0?a.opt("pubTime"):s.opt("pub_time"),"AQI源发布时间；SQL回退时间可能缺失，不以采集时间替代");
  return out;
 }
 private static JSONObject daily(JSONObject daily,int i) throws JSONException {
  JSONObject out=new JSONObject().put("day_index",i),t=pair(daily,"temperature",i),sun=pair(daily,"sunRiseSet",i),w=pair(daily,"weather",i);
  Object stamp=stamp(daily.opt("pubTime"));
  provenance(out,stamp,"源日预报基准时间；day_index=0为源预报首日，非SQL day编号");
  Object rise=stamp(sun.opt("from")),set=stamp(sun.opt("to"));
  Object date=rise!=JSONObject.NULL?rise.toString().substring(0,10):stamp!=JSONObject.NULL?OffsetDateTime.parse(stamp.toString()).toLocalDate().plusDays(i).toString():JSONObject.NULL;
  out.put("forecast_date",date);
  // W1.c.h in the inspected APK calls addTemperatureHigh(from), addTemperatureLow(to).
  out.put("temperature_high",quantity(t.opt("from"),unit(daily,"temperature"),-150,150));
  out.put("temperature_low",quantity(t.opt("to"),unit(daily,"temperature"),-150,150));
  out.put("sunrise",rise).put("sunset",set);
  out.put("weather_day_code",text(w.opt("from"),32)).put("weather_night_code",text(w.opt("to"),32));
  out.put("aqi",number(at(daily,"aqi",i),0,5000));
  out.put("precipitation_probability",quantity(at(daily,"precipitationProbability",i),unit(daily,"precipitationProbability"),0,100));
  JSONObject wind=obj(daily,"wind"),d=pair(wind,"direction",i),s=pair(wind,"speed",i),wo=new JSONObject();
  for(String k:new String[]{"from","to"})wo.put(k,new JSONObject().put("direction",quantity(d.opt(k),unit(wind,"direction"),0,360)).put("speed",quantity(s.opt(k),unit(wind,"speed"),0,1000)));
  out.put("wind",wo);return out;
 }
 private static void hourly(JSONObject fields,JSONObject hourly) throws JSONException {
  fields.put("forecast_hourly",new JSONArray());
  int n=Math.min(24,count(hourly,"temperature","weather","precipitationProbability","aqi","wind"));
  for(int i=0;i<n;i++) {
   JSONObject out=new JSONObject().put("hour_index",i),times=new JSONObject();
   for(String k:new String[]{"temperature","weather","precipitationProbability","aqi"})times.put(k,stamp(obj(hourly,k).opt("pubTime")));
   out.put("source_times",times).put("time_note","各数组保留自己的pubTime及序号；无明确有效时点时不推算小时，wind.datetime单独保留");
   out.put("temperature",quantity(at(hourly,"temperature",i),unit(hourly,"temperature"),-150,150));
   Object code=at(hourly,"weather",i);out.put("weather_code",code instanceof Number?number(code,0,10000):text(code,32));
   out.put("aqi",number(at(hourly,"aqi",i),0,5000));
   out.put("precipitation_probability",quantity(at(hourly,"precipitationProbability",i),unit(hourly,"precipitationProbability"),0,100));
   JSONObject w=pairValue(hourly,"wind",i);
   out.put("wind",new JSONObject().put("datetime",stamp(w.opt("datetime"))).put("speed",quantity(w.opt("speed"),w.opt("speedUnit"),0,1000)).put("direction",quantity(w.opt("direction"),w.opt("directionUnit"),0,360)));
   boundedAppend(fields,"forecast_hourly",out);
  }
  boundedPut(fields,"hourly_description",text(hourly.opt("desc"),1024));
  boundedPut(fields,"hourly_precipitation_description",text(obj(hourly,"precipitationProbability").opt("desc"),1024));
 }
 private static JSONObject pairValue(JSONObject o,String key,int i) {Object v=at(o,key,i);return v instanceof JSONObject?(JSONObject)v:new JSONObject();}
 private static JSONObject precipitation(JSONObject minute) throws JSONException {
  JSONObject p=obj(minute,"precipitation"),out=new JSONObject();if(!success(minute)||!success(p))p=new JSONObject();
  provenance(out,p.opt("pubTime"),"分钟降水原数组序号；未验证采样间隔及浓度/概率单位，不推算时点");
  for(String key:new String[]{"description","shortDescription","headDescription","subtitle"})out.put(key,text(p.opt(key),1024));
  out.put("probability_description",text(obj(minute,"probability").opt("probabilityDescV2"),1024));
  out.put("unit",JSONObject.NULL).put("probability_unit",JSONObject.NULL);
  for(String key:new String[]{"value","probability"}) {
   JSONArray values=new JSONArray(),input=arr(p,key);
   for(int i=0;i<Math.min(120,input.length());i++)values.put(number(input.opt(i),0,100000));
   out.put("value".equals(key)?"values":"probabilities",values);
  }
  return out;
 }
 private static void informationIndices(JSONObject fields,JSONObject information,Object cacheTime) throws JSONException {
  fields.put("life_indices",new JSONArray());JSONArray cards=arr(information,"cards");
  Set<String> known=new HashSet<>(Arrays.asList("clothing","uvIndex","sports","carWash","umbrella","gm"));
  Set<String> emitted=new HashSet<>();
  for(int i=0;i<Math.min(32,cards.length());i++) {
   JSONObject card=cards.optJSONObject(i);if(card==null)continue;JSONArray list=arr(card,"list");
   for(int j=0;j<Math.min(64,list.length())&&arr(fields,"life_indices").length()<12;j++) {
    JSONObject item=list.optJSONObject(j);if(item==null||!"6".equals(item.optString("type"))||!"13".equals(item.optString("template")))continue;
    JSONObject data=obj(item,"data"),head=obj(data,"wtrHeadData");String type=data.optString("wtrIndexType","");
    if(!known.contains(type)||!emitted.add(type))continue;
    JSONObject out=new JSONObject().put("type",type).put("title",text(data.opt("wtrTitle"),256)).put("summary",text(data.opt("wtrSummary"),1024))
     .put("extra",text(data.opt("wtrExtra"),512)).put("detail_title",text(head.opt("title"),256)).put("detail_summary",text(head.opt("summary"),1024))
     .put("cache_updated_at_epoch_ms",number(cacheTime,1,5000000000000d));
    provenance(out,null,"information同城生活卡片；insert_time是缓存更新时间，无独立业务源时间，不按采集日冒充今日建议");
    boundedAppend(fields,"life_indices",out);
   }
  }
 }
 private static void indices(JSONObject fields,JSONObject indices) throws JSONException {
  JSONArray a=success(indices)?arr(indices,"indices"):new JSONArray();
  String[] known={"uvIndex","feelsLike","humidity","pressure","sports","carWash","dressing","dress","clothing","sunProtection"};
  for(int i=0;i<Math.min(128,a.length())&&arr(fields,"life_indices").length()<12;i++) {
   JSONObject v=a.optJSONObject(i);if(v==null)continue;String type=v.optString("type","");if(!Arrays.asList(known).contains(type))continue;
   JSONObject out=new JSONObject().put("type",type).put("value",text(v.opt("value"),256));
   String label="uvIndex".equals(type)?"紫外线/防晒指数":"feelsLike".equals(type)?"体感温度（穿衣参考，不等于独立穿衣指数）":"sports".equals(type)?"运动指数":"carWash".equals(type)?"洗车指数":type;
   out.put("label",label).put("unit",JSONObject.NULL).put("interpretation_note","保留源type/value；等级、建议及单位未核实，不编造UI文案");
   provenance(out,indices.opt("pubTime"),"生活指数源发布时间");boundedAppend(fields,"life_indices",out);
  }
 }
 private static JSONObject alert(JSONObject a) throws JSONException {
  JSONObject out=new JSONObject();
  for(String k:new String[]{"title","detail","level","type"})out.put(k,text(a.opt(k),"detail".equals(k)?4096:256));
  Object pub=a.has("pubTime")?a.opt("pubTime"):a.opt("pub_time");
  // SQL pub_time is local formatted text in this version, not an offset-aware observation.
  out.put("published_at_source",text(pub,64));provenance(out,pub,"原预警pubTime；SQL pub_time可能无时区，仅原样保留，不伪造epoch");
  out.put("ends_at",stamp(a.opt("endTime"))).put("end_time_note","仅保留显式带偏移endTime；本机缓存未见结束字段，不推测解除时点");
  JSONArray defense=new JSONArray(),input=arr(a,"defense");
  for(int i=0;i<Math.min(4,input.length());i++) {JSONObject d=input.optJSONObject(i);if(d!=null)defense.put(text(d.opt("defenseText"),512));}
  out.put("defense",defense);return out;
 }

 /** Creates exactly the six protocol keys; untrusted or extra base keys are not forwarded. */
 public static JSONObject category(JSONObject base,JSONObject raw,JSONObject cache,long now) throws JSONException {
  JSONObject fields=project(raw,cache);Object observed=fields.opt("observed_at");long at=epoch(observed);
  boolean meaningful=fields.has("temperature")||fields.has("air_quality")||arr(fields,"forecast_daily").length()>0||arr(fields,"forecast_hourly").length()>0||arr(fields,"alerts").length()>0||arr(fields,"life_indices").length()>0||arr(obj(fields,"precipitation"),"values").length()>0||obj(fields,"precipitation").opt("description")!=JSONObject.NULL||fields.opt("description")!=JSONObject.NULL;
  String status=!meaningful?"missing":at>0&&now-at>21600000L?"stale":"ok";
  String reason=!meaningful?"首页城市无可用天气字段；可在源天气应用刷新":at<=0?"首页城市只读缓存；观测时间未知，不能据此判断实时性":now-at>21600000L?"天气观测超过6小时；预报、预警和指数分别保留源时间":"首页城市只读缓存；不主动联网刷新，各分项按源时间判断";
  Object collected=base==null?JSONObject.NULL:stamp(base.opt("collected_at"));
  if(collected==JSONObject.NULL)collected=java.time.Instant.ofEpochMilli(now).toString();
  JSONObject out=new JSONObject().put("status",status).put("source_package","com.miui.weather2")
   .put("collected_at",collected)
   .put("source_updated_at",observed==null?JSONObject.NULL:observed).put("fields",fields).put("reason",reason);
  return out;
 }
}

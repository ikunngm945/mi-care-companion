package cn.local.micompanion;

import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.Iterator;
import org.json.*;

/** Synthetic fixtures only: never copies private device rows. */
public final class WeatherProjectionTest {
 private static Class<?> projection;
 private static JSONObject project(JSONObject raw, JSONObject cache) throws Exception {
  if(projection==null) {
   try { projection=Class.forName("cn.local.micompanion.WeatherProjection"); }
   catch(ClassNotFoundException e) { throw new AssertionError("missing expanded weather projection",e); }
  }
  Method method=projection.getMethod("project",JSONObject.class,JSONObject.class);
  return (JSONObject)method.invoke(null,raw,cache);
 }
 private static void check(boolean ok,String what) { if(!ok)throw new AssertionError(what); }
 private static JSONObject fixture() throws Exception {
  return new JSONObject("{\"current\":{\"temperature\":{\"value\":\"20\",\"unit\":\"℃\"},\"feelsLike\":{\"value\":\"19\",\"unit\":\"℃\"},\"humidity\":{\"value\":\"55\",\"unit\":\"%\"},\"pressure\":{\"value\":\"1013\",\"unit\":\"hPa\"},\"visibility\":{\"value\":\"8\",\"unit\":\"km\"},\"wind\":{\"speed\":{\"value\":\"18\",\"unit\":\"km/h\"},\"direction\":{\"value\":\"90\",\"unit\":\"°\"}},\"weather\":\"0\",\"uvIndex\":\"3\",\"pubTime\":\"2026-01-02T12:00:00+08:00\"},\"aqi\":{\"aqi\":\"30\",\"pm25\":\"12\",\"co\":\"0.5\",\"pubTime\":\"2026-01-02T11:00:00+08:00\"},\"forecastDaily\":{\"pubTime\":\"2026-01-02T00:00:00+08:00\",\"temperature\":{\"unit\":\"℃\",\"value\":[{\"from\":\"25\",\"to\":\"15\"}]},\"sunRiseSet\":{\"value\":[{\"from\":\"2026-01-02T07:00:00+08:00\",\"to\":\"2026-01-02T18:00:00+08:00\"}]},\"precipitationProbability\":{\"value\":[\"40\"]}},\"forecastHourly\":{\"desc\":\"下午有雨\",\"temperature\":{\"pubTime\":\"2026-01-02T12:00:00+08:00\",\"unit\":\"℃\",\"value\":[20,21]},\"weather\":{\"pubTime\":\"2026-01-02T12:00:00+08:00\",\"value\":[0,1]},\"wind\":{\"value\":[{\"datetime\":\"2026-01-02T13:00:00+08:00\",\"speed\":\"12\",\"direction\":\"90\"}]}},\"indices\":{\"pubTime\":\"2026-01-02T08:00:00+08:00\",\"indices\":[{\"type\":\"uvIndex\",\"value\":\"3\"},{\"type\":\"feelsLike\",\"value\":\"19\"},{\"type\":\"sports\",\"value\":\"1\"},{\"type\":\"carWash\",\"value\":\"2\"}]},\"alerts\":[{\"title\":\"暴雨预警\",\"detail\":\"注意防范\",\"pubTime\":\"2026-01-02T10:00:00+08:00\",\"level\":\"黄色\",\"type\":\"rain\",\"defense\":[{\"defenseText\":\"减少外出\",\"defenseIcon\":\"secret-url\"}],\"locationKey\":\"PRIVATE\",\"alertId\":\"PRIVATE\"}],\"minutely\":{\"precipitation\":{\"pubTime\":\"2026-01-02T12:00:00+08:00\",\"description\":\"预计稍后降雨\",\"value\":[0,1],\"probability\":[20,40]}},\"city\":\"PRIVATE\",\"sid\":\"PRIVATE\",\"latitude\":\"PRIVATE\",\"token\":\"PRIVATE\"}");
 }
 private static void currentAndUnits() throws Exception {
  JSONObject fields=project(fixture(),new JSONObject().put("weather",new JSONObject().put("description","晴")));
  check(fields.getJSONObject("temperature").getInt("value")==20,"current temperature retained");
  check(fields.getJSONObject("wind").getJSONObject("speed").getString("unit").equals("km/h"),"explicit wind unit, no guessed conversion");
  check(fields.getJSONObject("air_quality").getJSONObject("pm25").isNull("unit"),"unknown pollutant unit must be null");
  check(fields.getJSONObject("air_quality").getJSONObject("co").getDouble("value")==0.5,"pollutant survives");
  check(fields.getJSONObject("today").getJSONObject("temperature_high").getInt("value")==25,"from means high (APK evidence)");
  check(fields.getJSONObject("today").getJSONObject("temperature_low").getInt("value")==15,"to means low");
  check(fields.getJSONObject("today").getString("sunrise").startsWith("2026-01-02T07"),"sunrise from solar from");
  check(fields.getJSONObject("precipitation").getJSONArray("values").getInt(1)==1,"minute rain preserved");
  check(fields.getJSONObject("precipitation").isNull("unit"),"minute rain unit not guessed");
  check(fields.getJSONArray("life_indices").length()==4,"all known UI indices present");
  check(fields.getJSONArray("alerts").getJSONObject(0).getString("detail").equals("注意防范"),"alert body preserved");
  check(!fields.toString().contains("PRIVATE")&&!fields.toString().contains("secret-url"),"privacy whitelist");
 }
 private static void independentMissingFields() throws Exception {
  JSONObject raw=fixture(); raw.put("current","broken"); raw.getJSONObject("forecastDaily").put("temperature",false);
  JSONObject fields=project(raw,new JSONObject());
  check(fields.getJSONArray("forecast_hourly").length()==2,"missing current/daily temperature must not erase hourly");
  check(fields.getJSONArray("alerts").length()==1,"missing current must not erase alerts");
  raw=fixture(); raw.getJSONObject("current").getJSONObject("temperature").put("value","NaN");
  raw.getJSONObject("current").getJSONObject("humidity").put("value",999);
  raw.getJSONObject("current").getJSONObject("pressure").remove("unit");
  fields=project(raw,new JSONObject());
  check(fields.getJSONObject("temperature").isNull("value"),"nonfinite invalid");
  check(fields.getJSONObject("humidity").isNull("value"),"humidity bounds");
  check(fields.getJSONObject("pressure").isNull("unit"),"missing explicit unit stays null");
  check(fields.getJSONArray("forecast_hourly").getJSONObject(0).getJSONObject("wind").getJSONObject("speed").isNull("unit"),"hourly wind has no explicit unit");
 }
 private static void fallbackWithoutRaw() throws Exception {
  JSONObject cache=new JSONObject().put("weather",new JSONObject().put("description","多云").put("temperature","22℃").put("tmphighs","25").put("tmplows","15").put("pressure","1009").put("pressure_unit","hPa").put("publish_time","1767326400000"));
  cache.put("aqi",new JSONObject().put("pm10","25"));
  cache.put("alerts",new JSONArray().put(new JSONObject().put("detail","本地预警正文").put("pub_time","2026-01-02 10:00:00")));
  JSONObject fields=project(new JSONObject(),cache);
  check(fields.getString("description").equals("多云"),"formatted description fallback");
  check(fields.getJSONObject("temperature").getInt("value")==22,"formatted explicit temperature suffix");
  check(fields.getJSONObject("today").getJSONObject("temperature_high").isNull("unit"),"SQL high unit not inferred from app unit enum");
  check(fields.getJSONObject("air_quality").getJSONObject("pm10").getInt("value")==25,"AQI SQL fallback independent");
  check(fields.getJSONArray("alerts").getJSONObject(0).getString("detail").equals("本地预警正文"),"SQL alerts fallback independent");
 }
 private static void limits(Object value,int depth) throws Exception {
  check(depth<=12,"max JSON depth");
  if(value instanceof String)check(((String)value).length()<=4096,"max scalar text");
  if(value instanceof JSONArray){JSONArray a=(JSONArray)value;check(a.length()<=128,"max array length");for(int i=0;i<a.length();i++)limits(a.get(i),depth+1);}
  if(value instanceof JSONObject){JSONObject o=(JSONObject)value;Iterator<String> keys=o.keys();while(keys.hasNext())limits(o.get(keys.next()),depth+1);}
 }
 private static void outputBudget() throws Exception {
  JSONObject raw=fixture(); StringBuilder longText=new StringBuilder();for(int i=0;i<6000;i++)longText.append('雨');
  JSONArray huge=new JSONArray(); for(int i=0;i<160;i++)huge.put(new JSONObject().put("title",longText.toString()).put("detail",longText.toString()).put("defense",new JSONArray().put(new JSONObject().put("defenseText",longText.toString()))));
  raw.put("alerts",huge); raw.getJSONObject("forecastHourly").getJSONObject("temperature").put("value",new JSONArray());
  for(int i=0;i<160;i++)raw.getJSONObject("forecastHourly").getJSONObject("temperature").getJSONArray("value").put(20);
  JSONArray days=new JSONArray();for(int i=0;i<160;i++)days.put(new JSONObject().put("from",25).put("to",15));
  raw.getJSONObject("forecastDaily").getJSONObject("temperature").put("value",days);
  JSONObject fields=project(raw,new JSONObject());limits(fields,1);
  check(fields.toString().getBytes(StandardCharsets.UTF_8).length<60000,"fields reserve category envelope budget");
  check(fields.getJSONArray("forecast_hourly").length()<=24,"hourly bounded");
  check(fields.getJSONArray("forecast_daily").length()<=15,"daily bounded");
  check(fields.getJSONArray("alerts").length()>0,"budget cannot discard all alerts");
  check(fields.getJSONObject("temperature").getInt("value")==20,"budget preserves current weather");
 }
 private static void categoryContract() throws Exception {
  project(new JSONObject(),new JSONObject());
  Method method=projection.getMethod("category",JSONObject.class,JSONObject.class,JSONObject.class,long.class);
  JSONObject base=new JSONObject().put("collected_at","2026-01-02T12:01:00+08:00").put("unexpected","PRIVATE");
  JSONObject category=(JSONObject)method.invoke(null,base,fixture(),new JSONObject(),1767412800000L);
  check(category.length()==6,"exact six category keys");
  check(category.getString("status").equals("stale"),"observation, not collection/update time, controls stale");
  check(category.getString("source_updated_at").equals("2026-01-02T12:00:00+08:00"),"source observation preserved");
  check(category.toString().getBytes(StandardCharsets.UTF_8).length<65536,"category budget");
  check(!category.has("unexpected"),"base cannot smuggle private metadata");
  JSONObject empty=(JSONObject)method.invoke(null,null,new JSONObject(),new JSONObject(),1767326400000L);
  check(empty.getString("status").equals("missing"),"empty cache is missing, not ok");
  check(empty.optString("collected_at").startsWith("2026-01-02T"),"null base still gets valid collection timestamp");
 }
 private static void datesAndSubstatus() throws Exception {
  JSONObject raw=fixture();
  raw.getJSONObject("forecastDaily").put("sunRiseSet",new JSONObject());
  raw.getJSONObject("forecastDaily").getJSONObject("temperature").getJSONArray("value").put(new JSONObject().put("from",24).put("to",14));
  JSONObject fields=project(raw,new JSONObject());
  check(fields.getJSONArray("forecast_daily").getJSONObject(1).getString("forecast_date").equals("2026-01-03"),"daily dates advance even with solar data absent");
  raw.getJSONObject("forecastDaily").getJSONObject("temperature").put("status",1);
  fields=project(raw,new JSONObject());
  check(fields.getJSONArray("forecast_daily").getJSONObject(0).getJSONObject("temperature_high").isNull("value"),"failed substatus must not expose stale values");
  check(fields.getJSONArray("forecast_hourly").length()==2,"failed daily temperature leaves hourly intact");
 }
 private static void timestampsAndBudgetEdges() throws Exception {
  JSONObject raw=fixture();
  raw.getJSONArray("alerts").getJSONObject(0).put("endTime","2026-01-02T18:00:00+08:00");
  JSONObject fields=project(raw,new JSONObject());
  check(fields.getJSONArray("alerts").getJSONObject(0).optString("ends_at").equals("2026-01-02T18:00:00+08:00"),"explicit alert end time is preserved without inference");
  JSONObject onlyMinute=new JSONObject().put("minutely",fixture().getJSONObject("minutely"));
  Method method=projection.getMethod("category",JSONObject.class,JSONObject.class,JSONObject.class,long.class);
  JSONObject category=(JSONObject)method.invoke(null,new JSONObject(),onlyMinute,new JSONObject(),1767326400000L);
  check(!category.getString("status").equals("missing"),"valid precipitation alone is useful weather");
  StringBuilder b=new StringBuilder();for(int i=0;i<6000;i++)b.append('雨');String longText=b.toString();
  raw=fixture();JSONObject daily=raw.getJSONObject("forecastDaily");JSONArray temp=new JSONArray(),winds=new JSONArray(),sun=new JSONArray(),weather=new JSONArray();
  for(int i=0;i<20;i++){temp.put(new JSONObject().put("from",25).put("to",15));winds.put(new JSONObject().put("from",20).put("to",10));sun.put(new JSONObject().put("from","2026-01-02T07:00:00+08:00").put("to","2026-01-02T18:00:00+08:00"));weather.put(new JSONObject().put("from",longText).put("to",longText));}
  daily.getJSONObject("temperature").put("value",temp);daily.put("weather",new JSONObject().put("value",weather)).put("sunRiseSet",new JSONObject().put("value",sun));
  daily.put("wind",new JSONObject().put("direction",new JSONObject().put("unit",longText).put("value",winds)).put("speed",new JSONObject().put("unit",longText).put("value",winds)));
  raw.getJSONObject("forecastHourly").put("desc",longText);raw.getJSONObject("minutely").getJSONObject("precipitation").put("description",longText).put("shortDescription",longText).put("headDescription",longText).put("subtitle",longText);
  JSONArray alert=new JSONArray();for(int i=0;i<8;i++)alert.put(new JSONObject().put("title",longText).put("detail",longText));raw.put("alerts",alert);
  JSONArray hours=new JSONArray();for(int i=0;i<160;i++)hours.put(20);raw.getJSONObject("forecastHourly").getJSONObject("temperature").put("value",hours);
  fields=project(raw,new JSONObject());limits(fields,1);
  check(fields.toString().getBytes(StandardCharsets.UTF_8).length<60000,"combined adversarial budget");
  check(fields.getJSONArray("alerts").length()>0,"adversarial forecasts cannot starve alerts");
 }
 private static void lifeInformationCards() throws Exception {
  JSONObject data=new JSONObject().put("wtrIndexType","clothing").put("wtrTitle","穿衣指数").put("wtrSummary","建议添加外套").put("wtrExtra","注意温差").put("wtrHeadData",new JSONObject().put("title","适宜穿搭").put("summary","合成建议").put("imgUrl","PRIVATE"));
  JSONObject item=new JSONObject().put("type","6").put("template","13").put("data",data);
  JSONObject info=new JSONObject().put("cards",new JSONArray().put(new JSONObject().put("list",new JSONArray().put(item))));
  JSONObject cache=new JSONObject().put("information",info).put("information_insert_time","1767326400000");
  JSONObject fields=project(fixture(),cache);JSONObject found=null;
  JSONArray indices=fields.getJSONArray("life_indices");for(int i=0;i<indices.length();i++)if(indices.getJSONObject(i).optString("type").equals("clothing"))found=indices.getJSONObject(i);
  check(found!=null&&found.getString("summary").equals("建议添加外套"),"UI life index advice comes from same-city information cards");
  check(found.getLong("cache_updated_at_epoch_ms")==1767326400000L,"information cache time carried separately");
  check(!fields.toString().contains("PRIVATE"),"information images/links never forwarded");
 }
 public static void main(String[] args) throws Exception {
  currentAndUnits(); independentMissingFields(); fallbackWithoutRaw(); outputBudget(); categoryContract(); datesAndSubstatus(); lifeInformationCards(); timestampsAndBudgetEdges();
  System.out.println("WeatherProjection: 8 groups passed");
 }
}

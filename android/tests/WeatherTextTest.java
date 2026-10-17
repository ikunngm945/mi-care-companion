package cn.local.micompanion;
import org.json.*;
import java.nio.charset.StandardCharsets;
public final class WeatherTextTest {
 static int groups;
 static void has(String s,String x){if(!s.contains(x))throw new AssertionError("missing "+x+" in "+s);}
 static void no(String s,String x){if(s.contains(x))throw new AssertionError("unexpected "+x+" in "+s);}
 static void eq(Object a,Object b){if(!a.equals(b))throw new AssertionError("expected "+a+", got "+b);}
 static JSONObject o(String s)throws Exception{return new JSONObject(s);}
 static String facts(String f)throws Exception{return WeatherText.format(new JSONObject().put("fields",o(f)),"2026-10-17T08:00:00+08:00");}
 static void rejected(String s)throws Exception{try{WeatherText.snapshot(s);throw new AssertionError("unsafe JSON accepted");}catch(IllegalArgumentException expected){}}
 public static void main(String[]args)throws Exception{
  String input="{ \"usage\":{\"zero\":0},\"metadata\":{\"flag\":true},\"weather\":{\"reason\":\"保留原因\",\"fields\":{\"temperature\":{\"value\":33,\"unit\":\"℃\"},\"weather_code\":\"0\"},\"status\":\"ok\"}}";
  JSONObject result=o(WeatherText.snapshot(input));
  if(result.getJSONObject("weather").has("fields"))throw new AssertionError("weather fields not compacted");
  has(result.getJSONObject("weather").getString("facts"),"晴");has(result.getJSONObject("weather").getString("facts"),"33℃");
  eq("保留原因",result.getJSONObject("weather").getString("reason"));eq(0,result.getJSONObject("usage").getInt("zero"));eq(true,result.getJSONObject("metadata").getBoolean("flag"));
  groups++;
  String[][] codes={{"3","阵雨"},{"7","小雨"},{"18","雾"},{"53","霾"},{"31","强沙尘暴"},{"99","未知天气"},{"987","未知天气"}};
  for(String[] c:codes)has(facts("{\"weather_code\":\""+c[0]+"\"}"),c[1]);
  no(facts("{\"weather_code\":987}"),"晴");groups++;
  int[] uv={0,2,3,5,6,7,8,10,11,100};String[] levels={"弱","弱","中","中","强","强","很强","很强","极强","极强"};
  for(int i=0;i<uv.length;i++)has(facts("{\"uv_index\":"+uv[i]+"}"),"紫外线："+uv[i]+"（"+levels[i]+"）");
  has(facts("{\"uv_index\":3.5}"),"紫外线：3.5");no(facts("{\"uv_index\":3.5}"),"（中）");
  has(facts("{\"uv_index\":null}"),"紫外线：未知");groups++;
  String dates=facts("{\"forecast_daily\":[{\"forecast_date\":\"2026-10-16\",\"temperature_high\":{\"value\":33,\"unit\":\"℃\"},\"temperature_low\":{\"value\":19,\"unit\":\"℃\"}},{\"forecast_date\":\"2026-10-17\",\"temperature_high\":{\"value\":35,\"unit\":\"℃\"}}]}");
  has(dates,"2026-10-16");has(dates,"最高33℃");has(dates,"最低19℃");has(dates,"2026-10-17（采集当天）");no(dates,"2026-10-16（采集当天）");groups++;
  String hours=facts("{\"forecast_hourly\":[{\"hour_index\":2,\"source_times\":{\"temperature\":\"2026-10-17T23:00:00+08:00\",\"weather\":\"2026-10-17T22:00:00+08:00\"},\"temperature\":{\"value\":25,\"unit\":\"℃\"},\"weather_code\":3,\"wind\":{\"datetime\":\"2026-10-18T03:00:00+08:00\",\"speed\":{\"value\":0,\"unit\":\"m/s\"}}},null,{\"hour_index\":5,\"source_times\":{\"temperature\":\"2026-10-17T23:00:00+08:00\"},\"temperature\":{\"value\":21,\"unit\":null}}]}");
  has(hours,"2026-10-18T01:00+08:00");has(hours,"2026-10-18T04:00+08:00");has(hours,"天气源时间不同，勿认作同一小时");no(hours,"2026-10-18T00:00+08:00");has(hours,"风时点冲突=2026-10-18T03:00:00+08:00");has(hours,"[1]未知");has(hours,"21（单位未知）");has(hours,"0m/s");groups++;
  String noIndex=facts("{\"forecast_hourly\":[{\"temperature\":{\"value\":0,\"unit\":\"℃\"},\"source_times\":{\"temperature\":\"2026-10-17T23:00:00+08:00\"}}]}");
  has(noIndex,"时点未知");no(noIndex,"2026-10-18T00:00");groups++;
  String full="{\"description\":\"晴朗🔆\",\"observed_at\":\"2026-10-17T07:10:00+08:00\",\"cache_updated_at_epoch_ms\":1792192200000,\"formatted_cache_updated_at_epoch_ms\":1792192201000,\"humidity\":{\"value\":48,\"unit\":\"%\"},\"pressure\":{\"value\":1012,\"unit\":\"hPa\"},\"visibility\":{\"value\":12,\"unit\":\"km\"},\"air_quality\":{\"aqi\":41,\"pm25\":{\"value\":18,\"unit\":null,\"source_updated_at\":\"2026-10-17T07:00:00+08:00\"},\"co\":{\"value\":0.3,\"unit\":null},\"suggest\":\"原空气建议。\"},\"precipitation\":{\"unit\":null,\"probability_unit\":null,\"values\":[0,null,2],\"probabilities\":[null,0,60],\"description\":\"原降水文字\"},\"alerts\":[{\"title\":\"原预警🔆\",\"detail\":\"保留全部正文。\",\"level\":\"黄色\",\"published_at_source\":\"2026-10-17 06:00\",\"ends_at\":null,\"defense\":[\"原防御建议\"]}],\"life_indices\":[{\"type\":\"uvIndex\",\"title\":\"原紫外线标题\",\"summary\":\"原指数摘要\",\"extra\":\"原extra\",\"detail_title\":\"原详情标题\",\"detail_summary\":\"原详情正文\",\"source_updated_at\":\"2026-10-16T21:00:00+08:00\"}],\"source_notes\":[\"特殊缺表原因\"],\"unknown_extra\":{\"b\":false,\"a\":[null,0,\"保留新字段\"]}}";
  String formatted=facts(full);
  for(String token:new String[]{"晴朗🔆","1792192200000","1792192201000","48%","1012hPa","12km","41","18（单位未知）","0.3（单位未知）","原空气建议。","[0,未知,2]","[未知,0,60]","原降水文字","原预警🔆","保留全部正文。","黄色","2026-10-17 06:00","原防御建议","原紫外线标题","原指数摘要","原extra","原详情标题","原详情正文","2026-10-16T21:00:00+08:00","特殊缺表原因","false","保留新字段"})has(formatted,token);
  has(formatted,"观测时间：2026-10-17T07:10:00+08:00");has(formatted,"分钟降水");groups++;
  String saved=" { \"health\":{\"arr\":[null,0,\"😀\"]} } ";eq(saved,WeatherText.snapshot(saved));
  for(String noWeather:new String[]{"{\"weather\":null}","{\"weather\":\"原样\"}","{\"weather\":{\"fields\":null}}"})eq(noWeather,WeatherText.snapshot(noWeather));
  for(String bad:new String[]{"{'weather':{}}","{\"weather\":{},\"weather\":{}}","{} trailing","{\"weather\":{},}","[]"})rejected(bad);groups++;
  JSONObject category=new JSONObject().put("fields",o(full));String before=category.toString();WeatherText.format(category,"2026-10-17T08:00:00+08:00");eq(before,category.toString());
  eq(WeatherText.snapshot("{\"weather\":{\"fields\":{\"z\":1,\"a\":2}},\"usage\":{\"b\":0,\"a\":3}}"),WeatherText.snapshot("{\"usage\":{\"a\":3,\"b\":0},\"weather\":{\"fields\":{\"a\":2,\"z\":1}}}"));groups++;
  String note="各数组保留自己的pubTime及序号；无明确有效时点时不推算小时，wind.datetime单独保留";
  JSONArray many=new JSONArray();for(int i=0;i<24;i++)many.put(new JSONObject().put("hour_index",i).put("source_times",o("{\"temperature\":\"2026-10-17T08:00:00+08:00\",\"weather\":\"2026-10-17T08:00:00+08:00\"}")).put("temperature",o("{\"value\":25,\"unit\":\"℃\"}")).put("weather_code",0).put("time_note",note));
  JSONObject large=new JSONObject().put("weather",new JSONObject().put("fields",new JSONObject().put("forecast_hourly",many)));
  String compact=WeatherText.snapshot(large.toString());no(compact,note);has(compact,"[23]");
  if(compact.getBytes(StandardCharsets.UTF_8).length>=large.toString().getBytes(StandardCharsets.UTF_8).length*0.8)throw new AssertionError("weather not substantially compacted");groups++;
  JSONObject dated=o("{\"collected_at\":\"2026-10-18T01:00:00+08:00\",\"fields\":{\"forecast_daily\":[{\"forecast_date\":\"2026-10-18\"}]}}");
  has(WeatherText.format(dated,"2026-10-17T23:00:00+08:00"),"2026-10-18（采集当天）");
  has(new JSONObject(WeatherText.snapshot(new JSONObject().put("weather",dated).toString())).getJSONObject("weather").getString("facts"),"2026-10-18（采集当天）");
  JSONObject utc=o("{\"collected_at\":\"2026-10-17T17:00:00Z\",\"fields\":{\"forecast_daily\":[{\"forecast_date\":\"2026-10-18\",\"source_updated_at\":\"2026-10-17T23:00:00+08:00\"},{\"forecast_date\":\"2026-10-17\"}]}}");
  String utcFacts=WeatherText.format(utc,"");has(utcFacts,"2026-10-18（采集当天）");no(utcFacts,"2026-10-17（采集当天）");groups++;
  JSONArray allDays=new JSONArray();for(int i=0;i<15;i++)allDays.put(new JSONObject().put("forecast_date",java.time.LocalDate.of(2026,10,17).plusDays(i).toString()).put("day_index",i));
  StringBuilder longText=new StringBuilder();for(int i=0;i<10000;i++)longText.append("全文😀");
  String complete=WeatherText.format(new JSONObject().put("fields",new JSONObject().put("forecast_daily",allDays).put("alerts",new JSONArray().put(new JSONObject().put("detail",longText.toString()+"结尾保留")))),"2026-10-17T08:00:00+08:00");
  has(complete,"2026-10-31");has(complete,longText.toString()+"结尾保留");groups++;
  String dailyExtra=facts("{\"forecast_daily\":[{\"forecast_date\":\"2026-10-17\",\"weather_day_code\":3,\"weather_night_code\":7}],\"forecast_hourly\":[{\"hour_index\":0,\"source_times\":\"意外时间结构保留\",\"weather_code\":987}],\"custom_note\":\"特殊说明不能丢\"}");
  has(dailyExtra,"白天：阵雨");has(dailyExtra,"夜间：小雨");has(dailyExtra,"意外时间结构保留");has(dailyExtra,"特殊说明不能丢");groups++;
  String noZone=facts("{\"forecast_daily\":[{\"forecast_date\":\"2026-10-17\"}]}");
  no(WeatherText.format(o("{\"fields\":{\"forecast_daily\":[{\"forecast_date\":\"2026-10-17\"}]}}"),"2026-10-17T08:00:00Z"),"采集当天");
  StringBuilder oversized=new StringBuilder("{\"other\":\"");for(int i=0;i<1048576;i++)oversized.append('x');oversized.append("\"}");rejected(oversized.toString());groups++;
  String nested=facts("{\"air_quality\":{\"co\":{\"value\":0.3,\"unit\":null,\"source_updated_at\":\"co-time\"},\"source_updated_at\":\"aq-time\"},\"a\":[\"x,y\"],\"b\":[\"x\",\"y\"],\"c\":[null,\"未知\"]}");
  has(nested,"co：{0.3（单位未知）；源时间：co-time}；源时间：aq-time");has(nested,"a：[\"x,y\"]");has(nested,"b：[\"x\",\"y\"]");has(nested,"c：[未知,\"未知\"]");groups++;
  JSONObject decimal=new JSONObject().put("fields",new JSONObject().put("tiny",new java.math.BigDecimal("0.000001")).put("big",new java.math.BigDecimal("12300.00")));
  JSONObject doubles=new JSONObject().put("fields",new JSONObject().put("tiny",0.000001d).put("big",12300d));
  eq(WeatherText.format(decimal,""),WeatherText.format(doubles,""));has(WeatherText.format(doubles,""),"tiny：0.000001");has(WeatherText.format(doubles,""),"big：12300");
  try{WeatherText.format(new JSONObject().put("fields",new JSONObject().put("huge",new java.math.BigDecimal("1e100000000"))),"");throw new AssertionError("huge exponent accepted");}catch(IllegalArgumentException expected){no(expected.getMessage(),"100000000");}groups++;
  String unknownTime=facts("{\"forecast_hourly\":[{\"hour_index\":0,\"source_times\":{\"weather\":\"2026-10-17T08:00:00+08:00\"},\"wind\":{\"datetime\":\"2026-10-17T08:00:00+08:00\"}},{\"hour_index\":1,\"source_times\":{\"temperature\":\"2026-10-17T08:00:00+08:00\"},\"wind\":{\"datetime\":\"格式异常仍保留\"}}]}");
  no(unknownTime,"风时点冲突");has(unknownTime,"格式异常仍保留");has(unknownTime,"AQI源时间未知");groups++;
  String quoted=WeatherText.snapshot("{\"weather\":{\"fields\":{}},\"other\":\"</script>\\n\\\"\\\\\"}");
   has(quoted,"</script>");no(quoted,"<\\/script>");has(quoted,"\\n");groups++;
   String allChars="引号\"斜线/反斜\\换行\n回车\r制表\t退格\b换页\f控制\u0001😀\u2028";
   JSONObject chars=new JSONObject().put("weather",new JSONObject().put("fields",new JSONObject())).put("other",allChars);
   eq(allChars,new JSONObject(WeatherText.snapshot(chars.toString())).getString("other"));groups++;
   String simple=facts("{\"temperature\":{\"value\":33,\"unit\":\"℃\"},\"weather_code\":0,\"air_quality\":{\"aqi\":40}}");
   has(simple,"气温：33℃");no(simple,"{33℃}");if(simple.indexOf("天气：晴")>simple.indexOf("空气质量："))throw new AssertionError("current weather must precede secondary details");groups++;
   System.out.println("WeatherTextTest: PASS ("+groups+" groups)");
 }
}

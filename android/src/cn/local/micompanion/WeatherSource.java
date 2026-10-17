package cn.local.micompanion;

import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import java.io.File;
import java.util.*;
import org.json.*;

/** Reads the source app's DE cache only. Never changes pragmas, permissions, or source files. */
public final class WeatherSource {
 private WeatherSource() {}
 private static final int MAX_JSON_CHARS=1048576;
 private static final String[] WEATHER={"publish_time","description","temperature","temperature_range","humidity","feel_temperature","ultraviolet","wind_direction","wind_power","wind","next_hour_rain_probability","sunrise","sunset","pressure","pressure_unit","tmphighs","tmplows","timestamp","day"};
 private static final String[] AQI={"aqi","pm25","pm10","so2","no2","co","o3","pub_time"};
 private static final String[] ALERT={"pub_time","level","title","detail","type","defense"};

 /** base is the caller's six-key category template; the result is a fresh, six-key category. */
 public static JSONObject collect(Context context,JSONObject base) {
  JSONObject raw=new JSONObject(),cache=new JSONObject();String failure=null;List<String> missing=new ArrayList<>();
  try {
   File file=context.createDeviceProtectedStorageContext().getDatabasePath("weather.db");
   if(!file.isFile())failure="未找到设备保护区天气数据库";
   else try(SQLiteDatabase db=SQLiteDatabase.openDatabase(file.getAbsolutePath(),null,SQLiteDatabase.OPEN_READONLY|SQLiteDatabase.NO_LOCALIZED_COLLATORS)) {
    // The city identifier is used only as a bound selection argument, never included in projected data.
    String id=null;
    try(Cursor row=db.rawQuery("SELECT posID FROM selectedcity ORDER BY position ASC LIMIT 1",null)) {
     if(row.moveToFirst())id=row.getString(0);
    } catch(Exception e) { missing.add("首页城市选择表不可读"); }
    if(id==null)failure="未选择首页城市或城市选择表不可读；未猜测其他城市";
    else {
     JSONArray rs=rows(db,"raw",new String[]{"insert_time","data1","minute_rain_data"},"city_id=?",new String[]{id},null,1,missing);
     JSONObject r=rs.optJSONObject(0);
     if(r!=null) {
      cache.put("insert_time",r.opt("insert_time"));
      raw=parseObject(r.optString("data1",null));
      cache.put("minute",parseObject(r.optString("minute_rain_data",null)));
      if(raw.length()==0)missing.add("raw缓存为空或JSON不可解析，尝试格式化字段");
     }
     // APK appends yesterday at index 0; day 1 is its current/today row. Never relabel day 0 as today.
     boolean hasDay=columns(db,"weather").contains("day");
     JSONArray weather=rows(db,"weather",WEATHER,hasDay?"city_id=? AND day=1":"city_id=?",new String[]{id},null,1,missing);
     JSONObject w=weather.optJSONObject(0);
     if(w!=null) {
      if(!hasDay) {
       // A schema without day cannot safely identify today's forecast, but independent current fields survive.
       for(String key:new String[]{"tmphighs","tmplows","sunrise","sunset","timestamp","temperature_range"})w.remove(key);
       missing.add("weather缺少day，未标注今日预报，仅保留单条缓存当前字段");
      }
      cache.put("weather",w);
     }
     JSONArray aqi=rows(db,"aqiinfo",AQI,"city_id=?",new String[]{id},null,1,missing);
     if(aqi.optJSONObject(0)!=null)cache.put("aqi",aqi.getJSONObject(0));
     JSONArray alerts=rows(db,"alertinfo",ALERT,"city_id=?",new String[]{id},null,8,missing);
     for(int i=0;i<alerts.length();i++) {
      JSONObject a=alerts.optJSONObject(i);String defense=a==null?null:a.optString("defense",null);
      if(a!=null)a.put("defense",parseArray(defense));
     }
     cache.put("alerts",alerts);
     JSONArray information=rows(db,"information",new String[]{"insert_time","data1"},"city_id=?",new String[]{id},null,1,missing);
     JSONObject info=information.optJSONObject(0);
     if(info!=null)cache.put("information",parseObject(info.optString("data1",null))).put("information_insert_time",info.opt("insert_time"));
    }
   }
  } catch(Exception e) { failure="只读天气缓存不可访问；可能应用版本或数据库状态已改变"; }
  try {
   JSONObject result=WeatherProjection.category(base,raw,cache,System.currentTimeMillis());
   if(failure!=null)result.put("reason",failure);
   if(!missing.isEmpty()) {
    JSONObject fields=result.getJSONObject("fields");
    // Constant diagnostics only: never exception messages, SQL parameters or database paths.
    JSONArray notes=new JSONArray();for(int i=0;i<Math.min(8,missing.size());i++)notes.put(missing.get(i));
    fields.put("source_notes",notes);
   }
   return result;
  } catch(JSONException e) {
   // org.json failures here can only indicate a projection bug; still honor the category envelope.
   JSONObject result=new JSONObject();try {
    result.put("status","error").put("source_package","com.miui.weather2")
     .put("collected_at",java.time.Instant.ofEpochMilli(System.currentTimeMillis()).toString()).put("source_updated_at",JSONObject.NULL)
     .put("fields",new JSONObject()).put("reason","天气白名单投影失败，未导出原始数据");
   }catch(JSONException ignored) {}return result;
  }
 }

 private static Set<String> columns(SQLiteDatabase db,String table) {
  Set<String> result=new HashSet<>();
  try(Cursor c=db.rawQuery("PRAGMA table_info("+table+")",null)) {
   int name=c.getColumnIndex("name");if(name>=0)while(c.moveToNext())result.add(c.getString(name));
  } catch(Exception ignored) {}return result;
 }
 private static JSONArray rows(SQLiteDatabase db,String table,String[] whitelist,String where,String[] args,String order,int limit,List<String> missing) {
  JSONArray result=new JSONArray();Set<String> available=columns(db,table);List<String> selected=new ArrayList<>();
  for(String name:whitelist)if(available.contains(name))selected.add(name);
  if(selected.isEmpty()) { missing.add(table+"白名单列不可读");return result; }
  if(selected.size()<whitelist.length)missing.add(table+"部分列缺失，已保留其余字段");
  StringBuilder sql=new StringBuilder("SELECT ");
  for(int i=0;i<selected.size();i++){if(i>0)sql.append(',');sql.append(selected.get(i));}
  sql.append(" FROM ").append(table).append(" WHERE ").append(where);
  if(order!=null)sql.append(" ORDER BY ").append(order);
  sql.append(" LIMIT ").append(limit);
  try(Cursor c=db.rawQuery(sql.toString(),args)) {
   while(c.moveToNext()&&result.length()<limit) {
    JSONObject row=new JSONObject();
    for(int i=0;i<selected.size();i++)try {
     String value=c.isNull(i)?null:c.getString(i);
     if(value!=null&&value.length()<=MAX_JSON_CHARS)row.put(selected.get(i),value);
    } catch(Exception ignored) {} // One damaged cell must not suppress sibling cells or other tables.
    result.put(row);
   }
  } catch(Exception e) { missing.add(table+"查询失败，其他表继续"); }
  return result;
 }
 // Bound input before org.json's recursive parser. This is not an output sanitizer: projection is allowlisted.
 private static boolean safeJson(String s) {
  if(s==null||s.length()>MAX_JSON_CHARS)return false;
  int depth=0;boolean quoted=false,escape=false;
  for(int i=0;i<s.length();i++) {
   char c=s.charAt(i);
   if(quoted){if(escape)escape=false;else if(c=='\\')escape=true;else if(c=='"')quoted=false;}
   else if(c=='"')quoted=true;
   else if(c=='{'||c=='['){if(++depth>32)return false;}
   else if(c=='}'||c==']'){if(--depth<0)return false;}
  }
  return !quoted&&depth==0;
 }
 private static JSONObject parseObject(String s) {try{return safeJson(s)?new JSONObject(s):new JSONObject();}catch(JSONException e){return new JSONObject();}}
 private static JSONArray parseArray(String s) {try{return safeJson(s)?new JSONArray(s):new JSONArray();}catch(JSONException e){return new JSONArray();}}
}

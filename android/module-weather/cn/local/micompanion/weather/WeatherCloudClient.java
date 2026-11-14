package cn.local.micompanion.weather;

import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import org.json.JSONObject;

/** One native cloud request at a time. Socket handlers never wait for HTTP or storage. */
final class WeatherCloudClient {
 interface Task { void run() throws Exception; }
 private final Executor worker;
 private final Task refresh;
 private String status="error",reason="尚未请求云端刷新";
 private long completedAt;
 WeatherCloudClient(Executor worker,Task refresh){this.worker=worker;this.refresh=refresh;}
 WeatherCloudClient(Context context,ClassLoader loader,Task publish){
  this(Executors.newSingleThreadExecutor(r->{Thread t=new Thread(r,"mi-companion-weather-cloud");t.setDaemon(true);return t;}),
   ()->refreshNative(context,loader,publish));
 }
 synchronized JSONObject handle(JSONObject request)throws Exception {
  String action=request.optString("action");
  if("weather_refresh".equals(action)) {
   if(!"pending".equals(status)) {
    status="pending";reason="正在请求原生天气云端";completedAt=0;
    try {worker.execute(this::run);}catch(RuntimeException failure){status="error";reason="天气刷新任务无法启动";completedAt=System.currentTimeMillis();}
   }
  } else if(!"weather_refresh_status".equals(action))
   return new JSONObject().put("status","error").put("reason","未知天气请求").put("completed_at",0);
  return new JSONObject().put("status",status).put("reason",reason).put("completed_at",completedAt);
 }
 private void run(){
  String result="ok",message="云端天气已更新并发送缓存";
  try {refresh.run();}catch(Throwable failure){
    result="error";message="原生天气云端请求、持久化或缓存发送失败";
    if(failure instanceof IllegalStateException){
     String code=failure.getMessage();
     if("native_request_failed".equals(code))message="小米天气云端未返回数据（网络失败或超时），保留旧缓存";
     else if("consent_required".equals(code))message="请先在小米天气中同意联网服务";
     else if("no_home_city".equals(code))message="小米天气尚未选择首页城市";
     else if("home_city_changed".equals(code))message="首页城市发生变化，本次未确认更新";
     else if("native_persist_failed".equals(code)||"native_persist_unconfirmed".equals(code))message="云端响应未确认写入天气缓存";
    }
   }
  synchronized(this){status=result;reason=message;completedAt=System.currentTimeMillis();}
 }
 private static void refreshNative(Context context,ClassLoader loader,Task publish)throws Exception {
  if(!(Boolean)loader.loadClass("b2.I").getMethod("F",Context.class).invoke(null,context))
   throw new IllegalStateException("consent_required");
  Class<?> cityType=loader.loadClass("com.miui.weather2.structures.CityData");
  Object value=loader.loadClass("Z1.t").getMethod("i",Context.class,String.class).invoke(null,context,null);
  if(!(value instanceof List)||((List<?>)value).isEmpty())throw new IllegalStateException("no_home_city");
  Object city=((List<?>)value).get(0);
  String id=(String)cityType.getMethod("getCityId").invoke(city);
  if(id==null||id.isEmpty()||!id.equals(homeCity(context)))throw new IllegalStateException("home_city_changed");
  Object weather=loader.loadClass("B1.k").getMethod("f",Context.class,cityType).invoke(null,context,city);
  if(weather==null)throw new IllegalStateException("native_request_failed");
  Class<?> weatherType=loader.loadClass("com.miui.weather2.structures.WeatherData");
  long updated=((Number)weatherType.getMethod("getUpdateTime").invoke(weather)).longValue();
  if(!id.equals(homeCity(context)))throw new IllegalStateException("home_city_changed");
  boolean first=(Boolean)cityType.getMethod("isFirstCity").invoke(city);
  Object saved=loader.loadClass("Z1.j1").getMethod("m",Context.class,weatherType,boolean.class,boolean.class)
   .invoke(null,context,weather,false,first);
  if(!Boolean.TRUE.equals(saved))throw new IllegalStateException("native_persist_failed");
  try(SQLiteDatabase db=open(context);Cursor row=db.rawQuery("SELECT insert_time FROM raw WHERE city_id=?",new String[]{id})) {
   if(!row.moveToFirst()||row.getLong(0)!=updated)throw new IllegalStateException("native_persist_unconfirmed");
  }
  if(!id.equals(homeCity(context)))throw new IllegalStateException("home_city_changed");
  publish.run();
 }
 private static SQLiteDatabase open(Context context){
  return SQLiteDatabase.openDatabase(context.createDeviceProtectedStorageContext().getDatabasePath("weather.db").getAbsolutePath(),null,
   SQLiteDatabase.OPEN_READONLY|SQLiteDatabase.NO_LOCALIZED_COLLATORS);
 }
 private static String homeCity(Context context)throws Exception {
  try(SQLiteDatabase db=open(context);Cursor row=db.rawQuery("SELECT posID FROM selectedcity ORDER BY position ASC LIMIT 1",null)) {
   return row.moveToFirst()?row.getString(0):null;
  }
 }
}

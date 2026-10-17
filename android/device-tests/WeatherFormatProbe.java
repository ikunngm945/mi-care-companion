package cn.local.micompanion.tests;
import android.app.Instrumentation;
import android.os.Bundle;
import cn.local.micompanion.EventClient;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import org.json.JSONObject;
/** Pure formatter + explicitly read-only official preflight; no submit/IM/model invocation. */
public final class WeatherFormatProbe extends Instrumentation {
 @Override public void onCreate(Bundle b){super.onCreate(b);start();}
 static boolean same(Object a,Object b)throws Exception{
  if(a instanceof JSONObject&&b instanceof JSONObject){JSONObject x=(JSONObject)a,y=(JSONObject)b;if(x.length()!=y.length())return false;for(java.util.Iterator<String> it=x.keys();it.hasNext();){String k=it.next();if(!y.has(k)||!same(x.get(k),y.get(k)))return false;}return true;}
  if(a instanceof org.json.JSONArray&&b instanceof org.json.JSONArray){org.json.JSONArray x=(org.json.JSONArray)a,y=(org.json.JSONArray)b;if(x.length()!=y.length())return false;for(int i=0;i<x.length();i++)if(!same(x.get(i),y.get(i)))return false;return true;}
  if(a instanceof Number&&b instanceof Number)return new java.math.BigDecimal(a.toString()).compareTo(new java.math.BigDecimal(b.toString()))==0;
  return a.equals(b);
 }
 @Override public void onStart(){Bundle out=new Bundle();int code=0;try{
  File in=new File(getTargetContext().getCacheDir(),"weather-format-input.json");
  byte[] original=Files.readAllBytes(in.toPath());
  String source=new String(original,StandardCharsets.UTF_8);
  Class<?> formatter=Class.forName("cn.local.micompanion.WeatherText");
  String result=(String)formatter.getMethod("snapshot",String.class).invoke(null,source);
  Files.write(new File(getTargetContext().getCacheDir(),"weather-format-output.json").toPath(),result.getBytes(StandardCharsets.UTF_8));
  JSONObject after=new JSONObject(result),before=new JSONObject(source);
  if(!after.getJSONObject("weather").has("facts")||after.getJSONObject("weather").has("fields"))throw new AssertionError("weather still nested");
  if(!same(before.getJSONObject("health"),after.getJSONObject("health")))throw new AssertionError("health altered");
  if(!same(before.getJSONObject("screen"),after.getJSONObject("screen")))throw new AssertionError("screen altered");
  if(!java.util.Arrays.equals(original,Files.readAllBytes(in.toPath())))throw new AssertionError("original input overwritten");
  StringBuilder hash=new StringBuilder();for(byte b:MessageDigest.getInstance("SHA-256").digest(result.getBytes(StandardCharsets.UTF_8)))hash.append(String.format(java.util.Locale.ROOT,"%02x",b&255));
  out.putString("formatter_sha256",hash.toString());
  File expectedFile=new File(getTargetContext().getCacheDir(),"weather-format-expected.sha256");
  if(expectedFile.isFile()&&!hash.toString().equals(new String(Files.readAllBytes(expectedFile.toPath()),StandardCharsets.UTF_8).trim()))throw new AssertionError("Android/JVM payload encoding differs");out.putInt("weather_bytes_before",before.getJSONObject("weather").toString().getBytes(StandardCharsets.UTF_8).length);out.putInt("weather_bytes_after",after.getJSONObject("weather").toString().getBytes(StandardCharsets.UTF_8).length);
  out.putString("formatter","PASS: fixed rules; original cached input and non-weather data unchanged");
  File events=new File(getTargetContext().getNoBackupFilesDir(),"events.db");
  if(events.isFile())try(android.database.sqlite.SQLiteDatabase db=android.database.sqlite.SQLiteDatabase.openDatabase(events.getAbsolutePath(),null,android.database.sqlite.SQLiteDatabase.OPEN_READONLY);android.database.Cursor rows=db.rawQuery("SELECT count(*) FROM events WHERE stage IN ('pending','generating','ready','sending','retry')",null)){
   rows.moveToFirst();out.putInt("potentially_active_events",rows.getInt(0));
  }
  String check=EventClient.check(getTargetContext());
  if(!check.startsWith("官方接口检查通过"))throw new AssertionError("official GET preflight did not pass");
  out.putString("official","PASS: existing encrypted settings, GET-only preflight; no chat/IM/submit");
 }catch(Throwable e){code=1;out.putString("failure",e instanceof AssertionError?e.getMessage():e.getClass().getSimpleName());}finish(code,out);}
}

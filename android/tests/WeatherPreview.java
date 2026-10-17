package cn.local.micompanion;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.Locale;
import org.json.JSONObject;
/** Offline preview. Input is never changed; output is create-only and never submitted. */
public final class WeatherPreview {
 static boolean same(Object a,Object b)throws Exception{
  if(a instanceof JSONObject&&b instanceof JSONObject){JSONObject x=(JSONObject)a,y=(JSONObject)b;if(x.length()!=y.length())return false;for(java.util.Iterator<String> it=x.keys();it.hasNext();){String k=it.next();if(!y.has(k)||!same(x.get(k),y.get(k)))return false;}return true;}
  if(a instanceof org.json.JSONArray&&b instanceof org.json.JSONArray){org.json.JSONArray x=(org.json.JSONArray)a,y=(org.json.JSONArray)b;if(x.length()!=y.length())return false;for(int i=0;i<x.length();i++)if(!same(x.get(i),y.get(i)))return false;return true;}
  if(a instanceof Number&&b instanceof Number)return new java.math.BigDecimal(a.toString()).compareTo(new java.math.BigDecimal(b.toString()))==0;
  return a.equals(b);
 }
 public static void main(String[] args)throws Exception{
  if(args.length!=3)throw new IllegalArgumentException("usage: WeatherPreview input new-facts.txt new-payload.json");
  Path input=Paths.get(args[0]);byte[] original=Files.readAllBytes(input);
  String result=WeatherText.snapshot(new String(original,StandardCharsets.UTF_8));
  JSONObject before=new JSONObject(new String(original,StandardCharsets.UTF_8)),after=new JSONObject(result);
  for(String key:new String[]{"health","screen"})if(before.has(key)&&!same(before.get(key),after.get(key)))throw new AssertionError("non-weather altered");
  if(!java.util.Arrays.equals(original,Files.readAllBytes(input)))throw new AssertionError("input overwritten");
  Files.write(Paths.get(args[1]),after.getJSONObject("weather").getString("facts").getBytes(StandardCharsets.UTF_8),StandardOpenOption.CREATE_NEW);
  Files.write(Paths.get(args[2]),result.getBytes(StandardCharsets.UTF_8),StandardOpenOption.CREATE_NEW);
  int oldBytes=before.getJSONObject("weather").toString().getBytes(StandardCharsets.UTF_8).length,newBytes=after.getJSONObject("weather").toString().getBytes(StandardCharsets.UTF_8).length;
  StringBuilder hash=new StringBuilder();for(byte b:MessageDigest.getInstance("SHA-256").digest(result.getBytes(StandardCharsets.UTF_8)))hash.append(String.format(Locale.ROOT,"%02x",b&255));
  System.out.printf(Locale.ROOT,"weather_bytes_before=%d weather_bytes_after=%d reduction=%.1f%% payload_sha256=%s%n",oldBytes,newBytes,100.0*(oldBytes-newBytes)/oldBytes,hash);
 }
}

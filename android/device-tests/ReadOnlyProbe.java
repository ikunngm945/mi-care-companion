package cn.local.micompanion.tests;
import android.app.Instrumentation;
import android.content.Context;
import android.os.Bundle;
import cn.local.micompanion.EventClient;
import org.json.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
/** Explicit read-only device validation; no settings changes, network or events created. */
public final class ReadOnlyProbe extends Instrumentation {
 @Override public void onCreate(Bundle args){super.onCreate(args);start();}
 @Override public void onStart(){
  Bundle result=new Bundle();int code=0;
  try{
   Context c=getTargetContext();JSONObject all=new JSONObject(),summary=new JSONObject();long now=System.currentTimeMillis();
   all.put("weather",EventClient.collect(c,"weather",now));
   JSONObject health=EventClient.collect(c,"health",now);all.put("health",health);
   all.put("screen",EventClient.collectScreen(c,health,now));
   for(String kind:new String[]{"weather","screen","health"}){
    JSONObject item=all.getJSONObject(kind);
    JSONObject fields=item.getJSONObject("fields");summary.put(kind,new JSONObject().put("status",item.getString("status")).put("field_count",fields.length()).put("bytes",item.toString().getBytes(StandardCharsets.UTF_8).length));
    if(item.length()!=6)throw new AssertionError("category wire contract");
   }
   try(FileOutputStream out=new FileOutputStream(new File(c.getCacheDir(),"readonly-probe-private.json"))){out.write(all.toString().getBytes(StandardCharsets.UTF_8));}
   String original=all.toString();
    String view=cn.local.micompanion.HealthText.snapshot(cn.local.micompanion.WeatherText.snapshot(original));
    JSONObject model=new JSONObject(view);
    if(model.getJSONObject("health").has("fields")||model.getJSONObject("screen").has("fields"))throw new AssertionError("health/screen still nested");
    if(!original.equals(all.toString()))throw new AssertionError("source snapshot changed");
    try(FileOutputStream out=new FileOutputStream(new File(c.getCacheDir(),"readonly-probe-facts.txt"))){out.write(model.getJSONObject("health").getString("facts").getBytes(StandardCharsets.UTF_8));}
    result.putString("formatter","PASS: installed fixed health/weather formatters; input unchanged");
    result.putString("summary",summary.toString());result.putString("network","none");
  }catch(Throwable e){code=1;result.putString("failure",e.getClass().getSimpleName());}
  finish(code,result);
 }
}

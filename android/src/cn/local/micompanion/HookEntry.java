package cn.local.micompanion;
import android.app.Application;
import android.content.*;
import android.database.sqlite.SQLiteDatabase;
import android.net.Uri;
import java.io.File;
import java.util.concurrent.*;
import de.robv.android.xposed.*;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/** Only two selected packages, no global network interception and no credential access. */
public final class HookEntry implements IXposedHookLoadPackage {
 private static final ScheduledExecutorService worker=Executors.newSingleThreadScheduledExecutor(r->{Thread t=new Thread(r,"mi-companion-readonly");t.setDaemon(true);return t;});
 private static volatile Context target;
 private static volatile String kind;
 private static ScheduledFuture<?> pending;
 private static synchronized void refresh(){
  if(target==null || (pending!=null&&!pending.isDone()))return;
  pending=worker.schedule(()->publish(),1500,TimeUnit.MILLISECONDS);
 }
 private static void publish(){
  Context c=target;if(c==null)return;
  try{
   ContentValues v=new ContentValues();v.put("json",LocalCollector.collect(c,kind).toString());
   c.getContentResolver().insert(Uri.parse("content://"+SnapshotProvider.AUTH+"/"+kind),v);
  }catch(Throwable ex){XposedBridge.log("MiCompanion: snapshot publish failed ("+ex.getClass().getSimpleName()+")");}
 }
 @Override public void handleLoadPackage(final XC_LoadPackage.LoadPackageParam lp)throws Throwable{
  if(!"com.miui.weather2".equals(lp.packageName)&&!"com.mi.health".equals(lp.packageName))return;
  // Restrict collection to the primary source application process.
  if(!lp.packageName.equals(lp.processName))return;
  kind="com.miui.weather2".equals(lp.packageName)?"weather":"health";
  if("health".equals(kind))XposedBridge.hookAllMethods(SQLiteDatabase.class,"openDatabase",new XC_MethodHook(){
   @Override protected void afterHookedMethod(MethodHookParam p){
    if(p.getResult() instanceof SQLiteDatabase){
     String path=((SQLiteDatabase)p.getResult()).getPath();
     if(path!=null&&path.endsWith("/fitness_summary")&&path.contains("/com.mi.health/"))LocalCollector.activeHealthPath=path;
    }
   }
  });
  XposedHelpers.findAndHookMethod(Application.class,"attach",Context.class,new XC_MethodHook(){
   @Override protected void afterHookedMethod(MethodHookParam p){
    target=(Context)p.args[0];refresh();
    worker.scheduleWithFixedDelay(()->publish(),60,60,TimeUnit.SECONDS);
   }
  });
  if("weather".equals(kind))try{
   Class<?> cls=XposedHelpers.findClass("com.miui.weather2.structures.WeatherData",lp.classLoader);
   XposedBridge.hookAllConstructors(cls,new XC_MethodHook(){@Override protected void afterHookedMethod(MethodHookParam p){refresh();}});
  }catch(Throwable ex){XposedBridge.log("MiCompanion: weather refresh hook unavailable; periodic read-only collector remains");}
 }
}

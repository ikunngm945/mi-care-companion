package cn.local.micompanion;
import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.*;
import android.os.Build;
import java.time.ZoneId;
/** Wall-clock alarms survive process sleep; each delivery computes the next local slot. */
public final class WeatherAlarm extends BroadcastReceiver {
 private static android.os.PowerManager.WakeLock wake;
 static synchronized void hold(Context c) {
  if(wake==null){wake=((android.os.PowerManager)c.getSystemService(Context.POWER_SERVICE)).newWakeLock(android.os.PowerManager.PARTIAL_WAKE_LOCK,"MiCompanion:WeatherCloud");wake.setReferenceCounted(false);}
  wake.acquire(100000);
 }
 static synchronized void release() {if(wake!=null&&wake.isHeld())wake.release();}
 static final String ACTION="cn.local.micompanion.action.WEATHER_REFRESH";
 private static PendingIntent pending(Context c) {
  return PendingIntent.getBroadcast(c,203,new Intent(c,WeatherAlarm.class).setAction(ACTION),PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
 }
 static void schedule(Context c) {
  AlarmManager alarms=(AlarmManager)c.getSystemService(Context.ALARM_SERVICE);
  long next=WeatherRefreshSchedule.next(System.currentTimeMillis(),ZoneId.systemDefault());
  boolean exact=Build.VERSION.SDK_INT<31||alarms.canScheduleExactAlarms();
  if(exact)alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP,next,pending(c));
  else alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP,next,pending(c));
  c.getSharedPreferences("monitor",0).edit().putLong("weather_next_at",next).putBoolean("weather_alarm_exact",exact).apply();
 }
 static void cancel(Context c) {((AlarmManager)c.getSystemService(Context.ALARM_SERVICE)).cancel(pending(c));}
 @Override public void onReceive(Context c,Intent i) {
  if(!c.getSharedPreferences("monitor",0).getBoolean("service_enabled",false))return;
  schedule(c);
  if(ACTION.equals(i.getAction())) {
   hold(c);
   try{c.startForegroundService(new Intent(c,MonitorService.class).setAction(ACTION));}
   catch(RuntimeException error){release();c.getSharedPreferences("monitor",0).edit().putString("weather_cloud_error","系统限制后台刷新；请开启精确闹钟及自启动权限").apply();}
  }
 }
}

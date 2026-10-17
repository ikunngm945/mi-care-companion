package cn.local.micompanion;
import android.app.*;
import android.content.*;
import android.content.pm.ServiceInfo;
import android.app.usage.UsageEvents;
import android.app.usage.UsageStatsManager;
import android.os.*;
import android.provider.Settings;
import java.util.ArrayList;
import java.util.UUID;
import java.util.concurrent.*;

public final class MonitorService extends Service {
 private final Handler h=new Handler(Looper.getMainLooper());
 private final ExecutorService net=Executors.newSingleThreadExecutor();
 private TriggerState state;
 private SharedPreferences p;
 private boolean wasOn;
 private int boot;
 private long offStart=-1,lastRetry=-1;
 private boolean uploading;
 public static String status(Context c){
  SharedPreferences settings=c.getSharedPreferences("monitor",0);String error=settings.getString("error","");
  return settings.getString("status","监控未启动")+(error.isEmpty()?"":"\n最近自动事件提示："+error);
 }
 private boolean on(){return ((PowerManager)getSystemService(POWER_SERVICE)).isInteractive();}
 private boolean unlocked(){return !((KeyguardManager)getSystemService(KEYGUARD_SERVICE)).isKeyguardLocked();}
 private SharedPreferences.Editor checkpoint(TriggerState value,long elapsed,long wall,boolean active,boolean open){
  return p.edit().putString("state",value.encode()).putInt("boot",boot).putLong("last_elapsed",elapsed)
   .putLong("last_wall",wall).putBoolean("last_on",active).putBoolean("last_open",open);
 }
 private int bootCount(){return Settings.Global.getInt(getContentResolver(),Settings.Global.BOOT_COUNT,-1);}
 private SharedPreferences.Editor clearPending(SharedPreferences.Editor edit){
  return edit.remove("pending_auto").remove("pending_created_wall").remove("pending_off_duration_ms")
   .remove("pending_off_ended_wall").remove("pending_use_started_wall");
 }
 private void restore(long elapsed,long wall,boolean active,boolean open){
  boolean available=false;ArrayList<long[]> evidence=new ArrayList<>();
  if(boot>=0&&p.getInt("boot",-2)==boot&&p.getBoolean("enabled",false))try{
   AppOpsManager ops=(AppOpsManager)getSystemService(APP_OPS_SERVICE);
   if(ops.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS,android.os.Process.myUid(),getPackageName())==AppOpsManager.MODE_ALLOWED){
    UsageEvents events=((UsageStatsManager)getSystemService(USAGE_STATS_SERVICE)).queryEvents(Math.max(0,p.getLong("last_wall",wall)-86_400_000L),wall+1);
    if(events!=null){UsageEvents.Event event=new UsageEvents.Event();available=true;
     while(events.hasNextEvent()){
      events.getNextEvent(event);int type=event.getEventType();
      if(type>=15&&type<=18)evidence.add(new long[]{event.getTimeStamp(),type});
      if(evidence.size()>100_000){available=false;break;}
     }
    }
   }
  }catch(RuntimeException ignored){available=false;}
  state=TriggerRecovery.restore(state,boot>=0&&p.getInt("boot",-2)==boot,p.getLong("last_elapsed",-1),p.getLong("last_wall",-1),
   elapsed,wall,p.getBoolean("last_on",true),p.getBoolean("last_open",false),active,open,available,evidence.toArray(new long[0][]));
  wasOn=active;offStart=state.offSinceMillis();
 }
 private void sample() {
  long now=SystemClock.elapsedRealtime(),wall=System.currentTimeMillis();boolean active=on(),open=unlocked();
  if(active!=wasOn){
   if(active){
    if(offStart>=0)p.edit().putLong("off_duration_ms",now-offStart).putLong("off_ended_wall",System.currentTimeMillis()).apply();
    state.screenOn(now,open);offStart=-1;
   } else {state.screenOff(now);offStart=now;}
   wasOn=active;
  }
  if(active && open){
   if(state.isArmed() && state.useMillis(now)==0)p.edit().putLong("use_started_wall",System.currentTimeMillis()).apply();
   state.unlock(now);
  }
  boolean ready=state.tick(now,active,open,false); // Observe locks even while an older UUID is pending.
   if(ready&&p.getString("pending_auto","").isEmpty()) {
   // Commit consumed state and event identity together, then durable client queue before clearing identity.
   TriggerState consumed=TriggerState.decode(state.encode());consumed.tick(now,active,open);
    if(checkpoint(consumed,now,wall,active,open).putString("pending_auto",UUID.randomUUID().toString())
      .putLong("pending_created_wall",wall)
      .putLong("pending_off_duration_ms",p.getLong("off_duration_ms",-1))
      .putLong("pending_off_ended_wall",p.getLong("off_ended_wall",-1))
      .putLong("pending_use_started_wall",p.getLong("use_started_wall",-1)).commit())state=consumed;
    else { // SharedPreferences changes memory even if its disk commit fails. Restore retryable memory state.
     clearPending(checkpoint(state,now,wall,active,open)).commit();
     p.edit().putString("error","事件状态持久化失败，保留资格；未创建或上传事件").apply();
    }
  }
  checkpoint(state,now,wall,active,open).commit();
  p.edit().putString("status",(active?(open?"已解锁亮屏":"亮屏锁定"):"息屏")+" · "+(state.isArmed()?"待触发，连续使用 "+(state.useMillis(now)/1000)+" 秒":"等待连续息屏超过3小时")).apply();
  boolean expired=false;
   if(!uploading&&!p.getString("pending_auto","").isEmpty()&&PendingAutomatic.expired(p.getLong("pending_created_wall",-1),wall)){
    expired=true;clearPending(p.edit()).putString("error","自动触发已超过24小时、缺少原始时间或设备时钟异常；已丢弃待建事件，不会生成伪当前快照")
     .putString("status","待建自动事件已过期，未发送；新一轮仍须重新满足时间条件").commit();
   }
   if(!uploading&&!expired&&PendingAutomatic.retryDue(lastRetry,now)) {
   uploading=true;lastRetry=now;final String id=p.getString("pending_auto","");
   net.execute(()->{
    try{if(p.getBoolean("enabled",false)){if(!id.isEmpty()){EventClient.submitAutomatic(this,id);clearPending(p.edit()).commit();}EventClient.retry(this);}}
    catch(Exception ex){p.edit().putString("error","队列保存失败："+ex.getClass().getSimpleName()).apply();}
    finally{h.post(()->uploading=false);}
   });
  }
 }
 private final Runnable tick=new Runnable(){public void run(){if(p.getBoolean("enabled",false)){sample();h.postDelayed(this,2000);}}};
 private final BroadcastReceiver screen=new BroadcastReceiver(){public void onReceive(Context c,Intent i){sample();}};
 @Override public void onCreate(){
  super.onCreate();p=getSharedPreferences("monitor",0);
  NotificationManager nm=getSystemService(NotificationManager.class);nm.createNotificationChannel(new NotificationChannel("monitor","屏幕监控",NotificationManager.IMPORTANCE_LOW));
  PendingIntent click=PendingIntent.getActivity(this,0,new Intent(this,MainActivity.class),PendingIntent.FLAG_IMMUTABLE);
  Notification n=new Notification.Builder(this,"monitor").setSmallIcon(android.R.drawable.ic_menu_info_details).setContentTitle("小米关怀助手监控中").setContentText("长息屏后解锁持续使用才上报；点此设置或停止").setContentIntent(click).setOngoing(true).build();
  if(Build.VERSION.SDK_INT>=34)startForeground(1,n,ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);else startForeground(1,n);
  state=TriggerState.decode(p.getString("state",""));
  boot=bootCount();restore(SystemClock.elapsedRealtime(),System.currentTimeMillis(),on(),unlocked());
  IntentFilter f=new IntentFilter();f.addAction(Intent.ACTION_SCREEN_OFF);f.addAction(Intent.ACTION_SCREEN_ON);f.addAction(Intent.ACTION_USER_PRESENT);
  if(Build.VERSION.SDK_INT>=33)registerReceiver(screen,f,Context.RECEIVER_NOT_EXPORTED);else registerReceiver(screen,f);
  h.post(tick);
 }
 @Override public int onStartCommand(Intent i,int flags,int id){return START_STICKY;}
 @Override public void onDestroy(){
  h.removeCallbacksAndMessages(null);unregisterReceiver(screen);
  if(!p.getBoolean("enabled",false)){state.restart();p.edit().putString("state",state.encode()).remove("boot").remove("last_elapsed").remove("last_wall").commit();}
  // A system stop keeps the last observed checkpoint for evidence-based same-boot recovery.
  net.shutdown();super.onDestroy();
 }
 @Override public IBinder onBind(Intent i){return null;}
 public static final class Boot extends BroadcastReceiver {
  @Override public void onReceive(Context c,Intent i){if(c.getSharedPreferences("monitor",0).getBoolean("enabled",false))try{c.startForegroundService(new Intent(c,MonitorService.class));}catch(RuntimeException ex){c.getSharedPreferences("monitor",0).edit().putString("status","系统限制自启动，请打开应用恢复监控").apply();}}
 }
}

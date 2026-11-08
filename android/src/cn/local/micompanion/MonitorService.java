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
 private static volatile MonitorService instance;
 public static final String ACTION_RELOAD_LINK="cn.local.micompanion.action.RELOAD_LINK";
 public static final String ACTION_REFRESH="cn.local.micompanion.action.REFRESH";
 private static volatile LinkServer link;
 private static volatile McpServer mcp;
 private static volatile boolean serviceRunning;
 private final Handler h=new Handler(Looper.getMainLooper());
 private final ExecutorService net=Executors.newSingleThreadExecutor();
 private static final long BAND_INTERVAL_MS=10*60*1000L;
 private final java.util.concurrent.atomic.AtomicBoolean bandBusy=new java.util.concurrent.atomic.AtomicBoolean();
 private ScheduledExecutorService bandSchedule;
 private TriggerState state;
 private SharedPreferences p;
 private boolean wasOn;
 private int boot;
 private long offStart=-1,lastRetry=-1;
 private boolean uploading;
 public static LinkServer current(){return link;}
 public static boolean serviceRunning(){return serviceRunning;}
 public static String status(Context c){
  SharedPreferences settings=c.getSharedPreferences("monitor",0);String error=settings.getString("error","");
  String linkError=settings.getString("link_error","");
  String pullError=settings.getString("pull_error","");
  return settings.getString("status","监控未启动")+(error.isEmpty()?"":"\n最近自动事件提示："+error)
   +(linkError.isEmpty()?"":"\n数据连接："+linkError)
   +(pullError.isEmpty()?"":"\n自动拉起："+pullError)
   +(settings.getString("band_auto_error","").isEmpty()?"":"\n手环："+settings.getString("band_auto_error",""));
 }
 public static void refresh(Context c){
  if(current()==null)return;
  try{c.startForegroundService(new Intent(c,MonitorService.class).setAction(ACTION_REFRESH));}catch(RuntimeException ignored){}
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
  if(link!=null){
   if(link.status("weather").connected)p.edit().putBoolean("seen_weather",true).apply();
   if(link.status("health").connected)p.edit().putBoolean("seen_health",true).apply();
  }
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
 private void updateNotification(){
  Notification.Builder n=new Notification.Builder(this,"monitor")
   .setSmallIcon(android.R.drawable.ic_menu_info_details)
   .setContentTitle("小米关怀助手")
   .setContentText(notificationText())
   .setContentIntent(PendingIntent.getActivity(this,0,new Intent(this,MainActivity.class),PendingIntent.FLAG_IMMUTABLE))
   .setOngoing(true).setOnlyAlertOnce(true);
  if(Build.VERSION.SDK_INT>=34)startForeground(1,n.build(),ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);else startForeground(1,n.build());
 }
 private String notificationText(){
  String error=p.getString("link_error","");
  if(!error.isEmpty())return "数据连接错误："+error;
  return p.getBoolean("enabled",false)?"监控中 · 数据接收中":"数据接收中（自动监控未启用）";
 }
 private void startLink(){
  if(mcp!=null){mcp.stop();mcp=null;}
  if(link!=null)link.stop();
  link=null;
  try{
   SettingsStore.Config cfg=SettingsStore.ensureLink(this);
   LinkServer server=new LinkServer(cfg.port,cfg.token,(kind,snapshot)->SnapshotStore.put(this,kind,snapshot));
   server.setDatabaseSink((kind,message)->HealthDatabaseStore.accept(this,message));
   if(server.start()){
    link=server;p.edit().remove("link_error").apply();
    McpServer service=new McpServer(cfg.token,this::queryHealth,this::queryWeather);
    if(service.start()){mcp=service;p.edit().remove("mcp_error").apply();}
    else p.edit().putString("mcp_error",service.error()).apply();
   }
   else p.edit().putString("link_error",server.error()).apply();
  }catch(Exception ex){
   p.edit().putString("link_error","本机配置读取失败："+ex.getClass().getSimpleName()).apply();
  }
  updateNotification();
 }
 private org.json.JSONObject queryWeather(org.json.JSONObject request)throws Exception{
  org.json.JSONObject snapshot=SnapshotStore.read(this,"weather");
  String status=snapshot.optString("status","error");
  if(!"ok".equals(status)){
   String reason=snapshot.optString("reason","天气数据不可用");
   return new org.json.JSONObject().put("status",status).put("text","天气数据不可用："+reason);
  }
  String text=WeatherText.compact(snapshot.optJSONObject("fields"),snapshot.optString("collected_at",""));
  return new org.json.JSONObject().put("status","ok").put("text",text);
 }
 private void startBandSchedule(){
  if(bandSchedule!=null)return;
  bandSchedule=Executors.newSingleThreadScheduledExecutor(r->{Thread t=new Thread(r,"band-auto");t.setDaemon(true);return t;});
  bandSchedule.scheduleWithFixedDelay(this::bandAuto,BAND_INTERVAL_MS,BAND_INTERVAL_MS,TimeUnit.MILLISECONDS);
 }

 static void runBandAutoNow(Context context){
  MonitorService service=instance;
  if(service!=null)service.bandAuto();
 }

 private void bandAuto(){
  if(!bandBusy.compareAndSet(false,true))return;
  try{
   BandConfig config=null;
   boolean refresh=p.getBoolean("band_retry_key",false);
   if(refresh){try{config=BandConfig.provision(this);}catch(Exception ignored){ }}
   if(config==null)config=BandConfig.load(this);
   if(config==null){try{config=BandConfig.provision(this);}catch(Exception ignored){ }}
   if(config==null)config=provisionBandKey();
   if(refresh&&config!=null){BandConfig fresh=provisionBandKey();if(fresh!=null)config=fresh;}
   if(config==null)throw new IllegalStateException("缺少手环密钥");
   org.json.JSONObject sync=BandSync.sync(this,config);
   BandStore.put(this,sync);
   p.edit().remove("band_auto_error").putBoolean("band_token_expired",false).putBoolean("band_retry_key",false)
     .putBoolean("band_health_fallback",false).putLong("band_auto_at",System.currentTimeMillis()).putString("band_auto_source","band").apply();
  }catch(Exception error){
   String message=error.getMessage()==null?"手环请求失败":error.getMessage();
   boolean token=bluetoothOn(this)&&BandConfig.load(this)!=null;
   p.edit().putString("band_auto_error",message).putBoolean("band_token_expired",token)
     .putBoolean("band_retry_key",true).putLong("band_auto_at",System.currentTimeMillis()).apply();
   // Direct SPP failed (often because Mi Fitness owns the channel). Do not force-stop or open
   // Mi Fitness; if its module is already connected, ask it to perform its own sync instead.
   healthFallback(this);
  }finally{
   bandBusy.set(false);
  }
 }

 private static boolean bluetoothOn(Context context){
  try{
   android.bluetooth.BluetoothAdapter adapter=android.bluetooth.BluetoothAdapter.getDefaultAdapter();
   return adapter!=null&&adapter.isEnabled();
  }catch(Throwable ignored){return false;}
 }
 private static boolean healthFallback(Context context){
  LinkServer server=link;
  if(server==null)return false;
  long deadline=System.currentTimeMillis()+5000;
  while(!server.status("health").connected&&System.currentTimeMillis()<deadline){
   try{Thread.sleep(200);}catch(InterruptedException ignored){Thread.currentThread().interrupt();break;}
  }
  if(!server.status("health").connected)return false;
  try{
   server.query("health",new org.json.JSONObject().put("action","sync_database"),10_000);
   context.getSharedPreferences("monitor",0).edit().putString("band_auto_source","health")
     .putBoolean("band_health_fallback",true).apply();
   return true;
  }catch(Exception ignored){return false;}
 }

 /** UI manual entry point: try direct SPP first, fall back to the health module when its channel is busy. */
 static org.json.JSONObject syncBand(Context context)throws Exception{
  try{
   BandConfig config=BandConfig.load(context);
   if(config==null){try{config=BandConfig.provision(context);}catch(Exception ignored){ }}
   MonitorService current=instance;
   if(config==null&&current!=null)config=current.provisionBandKey();
   if(config==null)throw new IllegalStateException("缺少手环密钥；请先连接一次健康模块，或打开运动健康后重试");
   org.json.JSONObject sync=BandSync.sync(context,config);
   BandStore.put(context,sync);
   context.getSharedPreferences("monitor",0).edit().remove("band_auto_error")
     .putBoolean("band_token_expired",false).putBoolean("band_retry_key",false)
     .putString("band_auto_source","band").putBoolean("band_health_fallback",false).apply();
   return sync;
  }catch(Exception error){
   boolean token=bluetoothOn(context)&&BandConfig.load(context)!=null;
   context.getSharedPreferences("monitor",0).edit().putString("band_auto_error",error.getMessage()==null?"手环请求失败":error.getMessage())
     .putBoolean("band_token_expired",token).putBoolean("band_retry_key",true)
     .putLong("band_auto_at",System.currentTimeMillis()).apply();
   if(healthFallback(context))return new org.json.JSONObject().put("status","ok").put("fallback",true).put("text","手环通道被占用，已切换运动健康同步");
   throw error;
  }
 }
 private BandConfig provisionBandKey(){
  try{
   if(link==null||!link.status("health").connected)return null;
   org.json.JSONObject answer=link.query("health",new org.json.JSONObject().put("action","band_key"),8_000);
   BandConfig config=BandConfig.fromJson(answer);
   return BandConfig.save(this,config);
  }catch(Exception error){
   android.util.Log.w("BandConfig","band key query failed: "+error.getClass().getSimpleName()+" "+error.getMessage());
   return null;
  }
 }
 private org.json.JSONObject queryHealth(org.json.JSONObject request)throws Exception{
  if("sync_database".equals(request.optString("action")))return requestDatabaseSync(this);
  if(HealthDatabaseStore.available(this))return HealthDatabaseStore.query(this,request);
  LinkServer server=link;
  if(server==null)throw new IllegalStateException("本机数据连接未启动");
  if(!server.status("health").connected)throw new IllegalStateException("健康数据库尚未同步；请打开一次健康应用后点“同步数据库与请求”");
  return server.query("health",request,20_000);
 }
 static org.json.JSONObject requestDatabaseSync(Context context)throws Exception{
  LinkServer server=link;
  if(server==null)throw new IllegalStateException("本机数据连接未启动");
  if(!server.status("health").connected)throw new IllegalStateException("小米健康未连接；请打开一次健康应用后重试");
  return server.query("health",new org.json.JSONObject().put("action","sync_database"),10_000);
 }
 private void reloadLink(){
  startLink();
 }
 @Override public void onCreate(){
  super.onCreate();serviceRunning=true;instance=this;p=getSharedPreferences("monitor",0);
  NotificationManager nm=getSystemService(NotificationManager.class);nm.createNotificationChannel(new NotificationChannel("monitor","屏幕监控",NotificationManager.IMPORTANCE_LOW));
  updateNotification();
  startLink();
  startBandSchedule();
  state=TriggerState.decode(p.getString("state",""));
  boot=bootCount();restore(SystemClock.elapsedRealtime(),System.currentTimeMillis(),on(),unlocked());
  IntentFilter f=new IntentFilter();f.addAction(Intent.ACTION_SCREEN_OFF);f.addAction(Intent.ACTION_SCREEN_ON);f.addAction(Intent.ACTION_USER_PRESENT);
  if(Build.VERSION.SDK_INT>=33)registerReceiver(screen,f,Context.RECEIVER_NOT_EXPORTED);else registerReceiver(screen,f);
  h.post(tick);
 }
 @Override public int onStartCommand(Intent i,int flags,int id){
  if(i!=null&&ACTION_RELOAD_LINK.equals(i.getAction()))reloadLink();
  else if(i!=null&&ACTION_REFRESH.equals(i.getAction()))updateNotification();
  else if(link==null)startLink();
  return START_STICKY;
 }
 @Override public void onDestroy(){
  h.removeCallbacksAndMessages(null);unregisterReceiver(screen);serviceRunning=false;if(instance==this)instance=null;
  if(mcp!=null){mcp.stop();mcp=null;}
  if(link!=null){link.stop();link=null;}
  if(!p.getBoolean("enabled",false)){state.restart();p.edit().putString("state",state.encode()).remove("boot").remove("last_elapsed").remove("last_wall").commit();}
  ScheduledExecutorService schedule=bandSchedule;bandSchedule=null;if(schedule!=null)schedule.shutdownNow();
  // A system stop keeps the last observed checkpoint for evidence-based same-boot recovery.
  net.shutdown();super.onDestroy();
 }
 @Override public IBinder onBind(Intent i){return null;}
 public static final class Boot extends BroadcastReceiver {
  @Override public void onReceive(Context c,Intent i){
   SharedPreferences p=c.getSharedPreferences("monitor",0);
   if(p.getBoolean("service_enabled",false))try{c.startForegroundService(new Intent(c,MonitorService.class));}
   catch(RuntimeException ex){p.edit().putString("status","系统限制自启动，请打开应用恢复数据连接").apply();}
  }
 }
}

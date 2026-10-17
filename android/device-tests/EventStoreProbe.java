package cn.local.micompanion.tests;
import android.app.Instrumentation;
import android.os.Bundle;
import android.content.Context;
import android.content.ContextWrapper;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import java.io.File;
import java.lang.reflect.Method;
import cn.local.micompanion.SettingsStore;
import cn.local.micompanion.EventClient;
/** Isolated test directory and synthetic rows; never touches the production events DB or network. */
public final class EventStoreProbe extends Instrumentation {
 @Override public void onCreate(Bundle b){super.onCreate(b);start();}
 @Override public void onStart(){Bundle out=new Bundle();int code=0;try{
  File dir=new File(getTargetContext().getCacheDir(),"event-store-probe-"+System.nanoTime());if(!dir.mkdir())throw new AssertionError("test directory unavailable");
  Context isolated=new ContextWrapper(getTargetContext()){@Override public File getNoBackupFilesDir(){return dir;} @Override public android.content.SharedPreferences getSharedPreferences(String n,int m){return super.getSharedPreferences("isolated-event-probe-"+n,m);}};
  try(SQLiteDatabase db=SQLiteDatabase.openOrCreateDatabase(new File(dir,"events.db"),null)){
   db.execSQL("CREATE TABLE events (id TEXT PRIMARY KEY, created INTEGER NOT NULL, base TEXT NOT NULL, umo TEXT NOT NULL, payload TEXT, stage TEXT NOT NULL, attempts INTEGER NOT NULL DEFAULT 0,next_at INTEGER NOT NULL DEFAULT 0,detail TEXT)");
   db.execSQL("INSERT INTO events(id,created,base,umo,payload,stage) VALUES('legacy',1,'http://127.0.0.1:1/api/v1','bot:FriendMessage:1','synthetic','ready')");
  }
  File realSettings=new File(getTargetContext().getNoBackupFilesDir(),"settings.json");
  org.json.JSONObject oldSettings=new org.json.JSONObject(new String(java.nio.file.Files.readAllBytes(realSettings.toPath()),java.nio.charset.StandardCharsets.UTF_8));
  oldSettings.remove("prompt");
  java.nio.file.Files.write(new File(dir,"settings.json").toPath(),oldSettings.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
  SettingsStore.Config legacyCfg=SettingsStore.load(isolated);
  if(!legacyCfg.prompt.equals(cn.local.micompanion.AdvicePrompt.DEFAULT))throw new AssertionError("legacy settings default prompt unavailable");
  SettingsStore.save(isolated,legacyCfg.baseUrl,legacyCfg.key,legacyCfg.umo,legacyCfg.allowHttp,"测试自定义提示词\n保留换行");
  SettingsStore.save(isolated,legacyCfg.baseUrl,legacyCfg.key,legacyCfg.umo,legacyCfg.allowHttp);
  if(!SettingsStore.load(isolated).prompt.equals("测试自定义提示词\n保留换行"))throw new AssertionError("legacy save discarded custom prompt");
  Method open=EventClient.class.getDeclaredMethod("database",Context.class);open.setAccessible(true);
  try(SQLiteDatabase db=(SQLiteDatabase)open.invoke(null,isolated)){
   try(Cursor row=db.rawQuery("SELECT payload,stage,prompt,config_id,profile_name,generated FROM events WHERE id='legacy'",null)){
    if(!row.moveToFirst()||!"synthetic".equals(row.getString(0))||!"ready".equals(row.getString(1)))throw new AssertionError("migration changed synthetic payload/stage");
   }
   Method process=EventClient.class.getDeclaredMethod("process",Context.class,SQLiteDatabase.class,SettingsStore.Config.class,String.class,boolean.class);process.setAccessible(true);
   SettingsStore.Config cfg=new SettingsStore.Config("http://127.0.0.1:9/api/v1","synthetic-test-key","bot:FriendMessage:2",true);
   // Exact target mismatch must stop without any GET, and preserve legacy original payload.
   process.invoke(null,isolated,db,cfg,"legacy",true);
   if(!EventClient.status(isolated).contains("目标配置已变化"))throw new AssertionError("legacy target isolation failed");
   db.execSQL("UPDATE events SET base=?,umo=?,generated=? WHERE id='legacy'",new Object[]{cfg.baseUrl,cfg.umo,"old persona text"});
   process.invoke(null,isolated,db,cfg,"legacy",true);
   if(!EventClient.status(isolated).contains("旧版正文缺少配置来源"))throw new AssertionError("legacy ready without profile must pause without GET/chat/IM");
   try(Cursor row=db.rawQuery("SELECT config_id,stage,generated FROM events WHERE id='legacy'",null)){
    row.moveToFirst();if(!row.isNull(0)||!"ready".equals(row.getString(1))||!"old persona text".equals(row.getString(2)))throw new AssertionError("legacy ready state or text overwritten");
   }
  }
  out.putString("storage","PASS: legacy schema migration preserves payload and adds prompt/profile columns; target mismatch pauses without network");
 }catch(Throwable e){code=1;out.putString("failure",e instanceof AssertionError?e.getMessage():e.getClass().getSimpleName());}finish(code,out);}
}

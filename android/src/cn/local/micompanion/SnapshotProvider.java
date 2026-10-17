package cn.local.micompanion;
import android.content.*;
import android.database.*;
import android.net.Uri;
import android.os.Binder;
import android.os.Process;
import org.json.*;
import java.util.Arrays;

/** Untrusted cross-process input, pinned to target UID and kind. Contains no API credentials. */
public final class SnapshotProvider extends ContentProvider {
 public static final String AUTH="cn.local.micompanion.snapshots";
 public boolean onCreate(){return true;}
 private static String pkg(String kind){return "weather".equals(kind)?"com.miui.weather2":"health".equals(kind)?"com.mi.health":null;}
 public static JSONObject read(Context c,String kind) {
  JSONObject b=UsageCollector.base(pkg(kind)==null?"unknown":pkg(kind));
  try {
   String raw=c.getSharedPreferences("snapshots",0).getString(kind,"");
   if(raw.isEmpty())return b.put("reason","未收到Hook数据；请启用LSPosed两个作用域并打开对应应用");
   return refreshSnapshot(new JSONObject(raw),kind,System.currentTimeMillis());
  }catch(Exception ex){try{b.put("status","error").put("reason","快照读取失败");}catch(Exception ignored){}return b;}
 }
 static JSONObject refreshSnapshot(JSONObject o,String kind,long now)throws JSONException{
  if(!"health".equals(kind))o.put("collected_at",UsageCollector.iso(now));
  return SnapshotFreshness.refresh(o,kind,now);
 }
 static void validateSize(String raw){
  if(raw==null || raw.getBytes(java.nio.charset.StandardCharsets.UTF_8).length>256*1024)throw new IllegalArgumentException("无效快照");
 }
 public Uri insert(Uri uri,ContentValues values) {
  String kind=uri.getLastPathSegment(),expected=pkg(kind);int uid=Binder.getCallingUid();
  String[] packages=getContext().getPackageManager().getPackagesForUid(uid);
  if(expected==null || packages==null || !Arrays.asList(packages).contains(expected))throw new SecurityException("目标应用UID不匹配");
  String raw=values==null?null:values.getAsString("json");
  validateSize(raw);
  try{
   JSONObject o=new JSONObject(raw);
   String status=o.getString("status");
   if(!Arrays.asList("ok","missing","stale","error").contains(status)||!expected.equals(o.getString("source_package")))throw new IllegalArgumentException();
   if(o.length()!=6 || !(o.get("fields") instanceof JSONObject))throw new IllegalArgumentException();
   o.get("source_updated_at");o.get("reason");o.getString("collected_at");
   if(!getContext().getSharedPreferences("snapshots",0).edit().putString(kind,o.toString()).commit())throw new IllegalStateException("快照存储失败");
   return uri;
  }catch(JSONException ex){throw new IllegalArgumentException("无效快照结构");}
 }
 private void own(){if(Binder.getCallingUid()!=Process.myUid())throw new SecurityException("不允许外部读取");}
 public Cursor query(Uri uri,String[] projection,String selection,String[] args,String sort){own();MatrixCursor c=new MatrixCursor(new String[]{"json"});c.addRow(new Object[]{read(getContext(),uri.getLastPathSegment()).toString()});return c;}
 public String getType(Uri uri){return "application/json";}
 public int delete(Uri u,String s,String[] a){throw new SecurityException();}
 public int update(Uri u,ContentValues v,String s,String[] a){throw new SecurityException();}
}

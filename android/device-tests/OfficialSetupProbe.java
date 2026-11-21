package cn.local.micompanion.tests;
import android.app.*;
import android.content.*;
import android.os.*;
import android.widget.*;
import android.graphics.Rect;
import org.json.JSONObject;
import java.io.*;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;
import cn.local.micompanion.SettingsStore;
/** Explicitly authorized one-time setup + GET preflight. Never submits an event. */
public final class OfficialSetupProbe extends Instrumentation {
    private Activity a;
    @Override public void onCreate(Bundle b){super.onCreate(b);start();}
    void ui(Runnable r){AtomicReference<Throwable> err=new AtomicReference<>();runOnMainSync(()->{try{r.run();}catch(Throwable e){err.set(e);}});if(err.get()!=null)throw new AssertionError("UI setup check failed");}
    <T>T field(String n,Class<T> t){try{Field f=a.getClass().getDeclaredField(n);f.setAccessible(true);return t.cast(f.get(a));}catch(Exception e){throw new AssertionError("UI field unavailable");}}
    @Override public void onStart(){Bundle b=new Bundle();int code=0;
        try{
            File source=new File(getTargetContext().getCacheDir(),"official-setup-private.json");
            ByteArrayOutputStream bytes=new ByteArrayOutputStream();try(InputStream in=new FileInputStream(source)){byte[] buf=new byte[1024];int n;while((n=in.read(buf))!=-1){if(bytes.size()+n>16384)throw new IOException();bytes.write(buf,0,n);}}
            JSONObject cfg=new JSONObject(new String(bytes.toByteArray(),StandardCharsets.UTF_8));
            SettingsStore.Config old=SettingsStore.load(getTargetContext());
            SettingsStore.save(getTargetContext(),old.baseUrl,cfg.getString("key"),old.umo,old.allowHttp,old.prompt);
            SettingsStore.Config saved=SettingsStore.load(getTargetContext());
            if(!saved.key.equals(cfg.getString("key"))||!saved.baseUrl.equals(old.baseUrl)||!saved.umo.equals(old.umo)||!saved.prompt.equals(old.prompt))throw new AssertionError("encrypted settings roundtrip mismatch");
            ActivityMonitor m=addMonitor("cn.local.micompanion.MainActivity",null,false);
            try(ParcelFileDescriptor p=getUiAutomation().executeShellCommand("am start -W -n cn.local.micompanion/.MainActivity");InputStream in=new ParcelFileDescriptor.AutoCloseInputStream(p)){byte[] buf=new byte[1024];while(in.read(buf)!=-1){}}
            a=m.waitForActivityWithTimeout(10000);removeMonitor(m);if(a==null)throw new AssertionError("launch unavailable");
            ui(()->field("check",Button.class).performClick());
            long end=SystemClock.uptimeMillis()+80000;boolean[] done={false};
            while(!done[0]&&SystemClock.uptimeMillis()<end){ui(()->done[0]=field("check",Button.class).isEnabled());if(!done[0])SystemClock.sleep(50);}
            if(!done[0])throw new AssertionError("check timeout");
            ui(()->{TextView r=field("report",TextView.class);if(!r.getText().toString().startsWith("官方接口检查通过")||!r.getText().toString().contains(cfg.optString("expected_config","fdcc7c02-0fbb-42d3-8585-0feb75c0fa48")))throw new AssertionError("official check did not succeed");});
            SystemClock.sleep(200);
            ui(()->{TextView r=field("report",TextView.class);Rect rect=new Rect();if(!r.getGlobalVisibleRect(rect)||rect.height()<r.getHeight())throw new AssertionError("check result not fully visible");});
            b.putString("setup","PASS: encrypted official config saved; real UI check passed and fully visible");
            b.putString("requests","GET bots + chat sessions + config routes + profiles only; no model call / no IM send");
            b.putString("matched_config","fdcc7c02-0fbb-42d3-8585-0feb75c0fa48");
        }catch(Throwable e){code=1;b.putString("failure",e instanceof AssertionError?e.getMessage():e.getClass().getSimpleName());}
        finish(code,b);
    }
}

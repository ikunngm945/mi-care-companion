package cn.local.micompanion;

import android.app.Instrumentation;
import android.content.Context;
import android.os.Bundle;
import java.io.File;
import java.io.FileOutputStream;
import org.json.JSONObject;

public final class BandSyncProbe extends Instrumentation {
    @Override public void onCreate(Bundle arguments) { super.onCreate(arguments); start(); }
    @Override public void onStart() {
        new Thread(() -> {
            JSONObject result = new JSONObject();
            try {
                Context context = getTargetContext();
                BandConfig config = BandConfig.load(context);
                if (config == null) throw new IllegalStateException("没有手环配置");
                JSONObject sync = BandSync.sync(context, config);
                BandStore.put(context, sync);
                result.put("sync", sync);
                result.put("text", BandData.describe(sync));
            } catch (Throwable error) {
                try { result.put("error", error.toString()); } catch (Exception ignored) { }
            } finally {
                try {
                    File out = new File(getTargetContext().getCacheDir(), "band-sync-probe.json");
                    FileOutputStream stream = new FileOutputStream(out);
                    try { stream.write(result.toString(2).getBytes("UTF-8")); } finally { stream.close(); }
                } catch (Exception ignored) { }
                finish(0, new Bundle());
            }
        }, "band-sync-probe").start();
    }
}

package cn.local.micompanion;

import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import java.io.File;
import java.io.FileOutputStream;
import org.json.JSONObject;

public final class BandAutoProbe extends Instrumentation {
    @Override public void onCreate(Bundle arguments) { super.onCreate(arguments); start(); }
    @Override public void onStart() {
        new Thread(() -> {
            JSONObject result = new JSONObject();
            try {
                Context context = getTargetContext();
                try { context.startForegroundService(new Intent(context, MonitorService.class)); } catch (Throwable ignored) { }
                Thread.sleep(2500);
                MonitorService.runBandAutoNow(context);
                Thread.sleep(30000);
                android.content.SharedPreferences p = context.getSharedPreferences("monitor", 0);
                result.put("band_auto_error", p.getString("band_auto_error", ""));
                result.put("band_token_expired", p.getBoolean("band_token_expired", false));
                result.put("band_retry_key", p.getBoolean("band_retry_key", false));
                result.put("band_auto_source", p.getString("band_auto_source", ""));
                result.put("band_store", BandStore.read(context) != null);
                result.put("health_copy", HealthDatabaseStore.file(context).isFile());
            } catch (Throwable error) {
                try { result.put("error", error.toString()); } catch (Exception ignored) { }
            } finally {
                try {
                    File out = new File(getTargetContext().getCacheDir(), "band-auto-probe.json");
                    FileOutputStream stream = new FileOutputStream(out);
                    try { stream.write(result.toString(2).getBytes("UTF-8")); } finally { stream.close(); }
                } catch (Exception ignored) { }
                finish(0, new Bundle());
            }
        }, "band-auto-probe").start();
    }
}

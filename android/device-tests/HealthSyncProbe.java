package cn.local.micompanion;

import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import java.io.File;
import java.io.FileOutputStream;
import org.json.JSONObject;

public final class HealthSyncProbe extends Instrumentation {
    @Override public void onCreate(Bundle arguments) { super.onCreate(arguments); start(); }
    @Override public void onStart() {
        new Thread(() -> {
            JSONObject result = new JSONObject();
            try {
                Context context = getTargetContext();
                try { context.startForegroundService(new Intent(context, MonitorService.class)); } catch (Throwable ignored) { }
                Thread.sleep(2500);
                File before = HealthDatabaseStore.file(context);
                result.put("before", before.isFile());
                result.put("response", MonitorService.requestDatabaseSync(context));
                long deadline = System.currentTimeMillis() + 30000;
                while (System.currentTimeMillis() < deadline && !before.isFile()) Thread.sleep(300);
                result.put("after", before.isFile());
                result.put("bytes", before.isFile() ? before.length() : 0);
            } catch (Throwable error) {
                try { result.put("error", error.toString()); } catch (Exception ignored) { }
            } finally {
                try {
                    File out = new File(getTargetContext().getCacheDir(), "health-sync-probe.json");
                    FileOutputStream stream = new FileOutputStream(out);
                    try { stream.write(result.toString(2).getBytes("UTF-8")); } finally { stream.close(); }
                } catch (Exception ignored) { }
                finish(0, new Bundle());
            }
        }, "health-sync-probe").start();
    }
}

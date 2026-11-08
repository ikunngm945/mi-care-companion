package cn.local.micompanion;

import android.app.Instrumentation;
import android.content.Context;
import android.os.Bundle;
import android.util.Log;
import java.io.File;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.json.JSONArray;
import org.json.JSONObject;

/** Device-only spike: provision the Mi Fitness auth key, authenticate to the band, request today's sync. */
public final class BandAuthProbe extends Instrumentation {
    private static final String TAG = "BandAuthProbe";
    private final CountDownLatch done = new CountDownLatch(1);
    private final List<String> states = new ArrayList<>();
    private final List<String> files = new ArrayList<>();
    private String error = "";
    private boolean ready;

    @Override public void onCreate(Bundle arguments) {
        super.onCreate(arguments);
        start();
    }

    @Override public void onStart() {
        Thread worker = new Thread(() -> {
            JSONObject result = new JSONObject();
            BandClient[] holder = new BandClient[1];
            BandClient client = null;
            try {
                Context context = getTargetContext();
                try { result.put("root_probe", BandConfig.run("su", "-c", "id; ls -ld /data/data/com.mi.health; ls /data/data/com.mi.health/databases")); }
                catch (Throwable rootError) { result.put("root_probe_error", rootError.toString()); }
                BandConfig config = BandConfig.provision(context);
                result.put("mac", config.mac);
                result.put("model", config.model);
                result.put("key_present", true);
                client = new BandClient(context, config, new BandClient.Listener() {
                    @Override public void onState(String state) { states.add(state); Log.i(TAG, state); }
                    @Override public void onReady() { ready = true; if (holder[0] != null) holder[0].requestToday(); }
                    @Override public void onFileIds(int subtype, List<byte[]> ids) {
                        files.add(subtype + ":" + ids.size());
                        if (!ids.isEmpty() && files.size() == 1 && holder[0] != null) holder[0].requestFile(ids.get(0));
                    }
                    @Override public void onFile(byte[] file) { files.add("bytes:" + file.length); done.countDown(); }
                    @Override public void onError(String reason) { error = reason; Log.e(TAG, reason); done.countDown(); }
                });
                holder[0] = client;
                client.connect();
                done.await(35, TimeUnit.SECONDS);
                client.close();
            } catch (Throwable ex) {
                error = ex.getClass().getSimpleName() + ":" + (ex.getMessage() == null ? "" : ex.getMessage());
                if (client != null) client.close();
            }
            try {
                result.put("ready", ready);
                result.put("error", error);
                result.put("states", new JSONArray(states));
                result.put("files", new JSONArray(files));
                File out = new File(getTargetContext().getCacheDir(), "band-auth-probe.json");
                FileOutputStream stream = new FileOutputStream(out);
                try { stream.write(result.toString(2).getBytes("UTF-8")); } finally { stream.close(); }
                Log.i(TAG, result.toString());
            } catch (Exception ex) {
                Log.e(TAG, "write result failed", ex);
            }
            finish(0, new Bundle());
        }, "band-auth-probe");
        worker.start();
    }
}

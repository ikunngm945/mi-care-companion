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

/** Device probe for the Classic SPP V2 handshake and auth. */
public final class BandSppAuthProbe extends Instrumentation {
    private static final String TAG = "BandSppAuthProbe";

    @Override public void onCreate(Bundle arguments) { super.onCreate(arguments); start(); }

    @Override public void onStart() {
        new Thread(() -> {
            JSONObject result = new JSONObject();
            JSONArray states = new JSONArray();
            JSONArray files = new JSONArray();
            BandSppClient[] client = {null};
            try {
                Context context = getTargetContext();
                BandConfig config = BandConfig.load(context);
                if (config == null) throw new IllegalStateException("没有手环配置");
                CountDownLatch ready = new CountDownLatch(1);
                List<String> ids = new ArrayList<>();
                List<byte[]> fileIds = new ArrayList<>();
                client[0] = new BandSppClient(context, config, new BandSppClient.Listener() {
                    @Override public void onState(String state) {
                        states.put(state);
                        Log.i(TAG, "state: " + state);
                    }
                    @Override public void onReady() {
                        Log.i(TAG, "ready");
                        ready.countDown();
                    }
                    @Override public void onFileIds(int subtype, List<byte[]> received) {
                        for (byte[] id : received) {
                            StringBuilder hex = new StringBuilder();
                            for (byte b : id) hex.append(String.format("%02x", b & 0xff));
                            ids.add(subtype + ":" + hex);
                            fileIds.add(id);
                        }
                        Log.i(TAG, "file ids " + subtype + " count=" + received.size());
                    }
                    @Override public void onFile(byte[] file) {
                        String id = "";
                        for (int i = 0; i < 7 && i < file.length; i++) id += String.format("%02x", file[i] & 0xff);
                        files.put(id + ":" + file.length);
                        try {
                            java.io.File dir = new java.io.File(getTargetContext().getCacheDir(), "band-files");
                            dir.mkdirs();
                            java.io.FileOutputStream stream = new java.io.FileOutputStream(new java.io.File(dir, id + ".bin"));
                            try { stream.write(file); } finally { stream.close(); }
                        } catch (Exception ignored) { }
                        Log.i(TAG, "file " + id + " len=" + file.length);
                    }
                    @Override public void onError(String reason) {
                        Log.e(TAG, "error: " + reason);
                        try { result.put("error", reason); } catch (Exception ignored) { }
                        ready.countDown();
                    }
                });
                client[0].connect();
                boolean authenticated = ready.await(22, TimeUnit.SECONDS);
                result.put("authenticated", authenticated);
                result.put("states", states);
                if (authenticated) {
                    client[0].requestToday();
                    Thread.sleep(5000);
                    result.put("file_ids", new JSONArray(ids));
                    result.put("files", files);
                    Thread.sleep(10000);
                    result.put("files_after_request", files);
                }
            } catch (Throwable error) {
                try { result.put("exception", error.toString()); } catch (Exception ignored) { }
                Log.e(TAG, "probe", error);
            } finally {
                if (client[0] != null) client[0].close();
                try {
                    File out = new File(getTargetContext().getCacheDir(), "band-spp-auth-probe.json");
                    FileOutputStream stream = new FileOutputStream(out);
                    try { stream.write(result.toString(2).getBytes("UTF-8")); } finally { stream.close(); }
                } catch (Exception ignored) { }
                finish(0, new Bundle());
            }
        }, "band-spp-auth-probe").start();
    }
}

package cn.local.micompanion;

import android.content.Context;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * One-shot direct Band 10 sync over Classic SPP.
 *
 * <p>Called by the 10-minute auto sync and by the in-app manual button. It never opens or
 * force-stops Mi Fitness; if the SPP channel is busy the caller falls back to the health module.
 */
final class BandSync {
    private BandSync() { }

    static JSONObject sync(Context context, BandConfig config) throws Exception {
        try {
            return syncOnce(context, config);
        } catch (Exception first) {
            // The SPP channel can still be held for a moment after the previous attempt.
            try { Thread.sleep(1000); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
            try { return syncOnce(context, config); } catch (Exception second) { throw second; }
        }
    }

    private static JSONObject syncOnce(Context context, BandConfig config) throws Exception {
        final JSONObject result = new JSONObject();
        final JSONArray files = new JSONArray();
        final AtomicReference<BandSppClient> holder = new AtomicReference<>();
        final CountDownLatch ready = new CountDownLatch(1);
        final AtomicReference<String> error = new AtomicReference<>();
        final AtomicLong lastHeard = new AtomicLong();
        final AtomicBoolean sawIds = new AtomicBoolean();
        final List<JSONObject> parsed = new ArrayList<>();

        BandSppClient client = new BandSppClient(context, config, new BandSppClient.Listener() {
            @Override public void onState(String state) { lastHeard.set(System.currentTimeMillis()); }
            @Override public void onReady() {
                lastHeard.set(System.currentTimeMillis());
                ready.countDown();
                holder.get().requestToday();
            }
            @Override public void onFileIds(int subtype, List<byte[]> fileIds) {
                lastHeard.set(System.currentTimeMillis());
                sawIds.set(true);
            }
            @Override public void onFile(byte[] file) {
                lastHeard.set(System.currentTimeMillis());
                try { saveRaw(context, file); } catch (Exception ignored) { }
                try {
                    JSONObject parsedFile = BandData.parse(file);
                    if (parsedFile != null) {
                        files.put(parsedFile);
                        if (parsedFile.optJSONObject("daily_summary") != null) parsed.add(parsedFile);
                    }
                } catch (Exception ignored) { }
            }
            @Override public void onError(String reason) {
                error.set(reason);
                ready.countDown();
            }
        });
        holder.set(client);
        try {
            client.connect();
            if (!ready.await(20, TimeUnit.SECONDS)) throw new IllegalStateException("手环认证超时");
            if (error.get() != null && !sawIds.get()) throw new IllegalStateException(error.get());
            long deadline = System.currentTimeMillis() + 30000;
            while (System.currentTimeMillis() < deadline) {
                long heard = lastHeard.get();
                if (heard > 0 && System.currentTimeMillis() - heard > 2500) break;
                Thread.sleep(250);
            }
            if (error.get() != null && parsed.isEmpty()) throw new IllegalStateException(error.get());
        } finally {
            client.close();
        }

        JSONObject today = null;
        long todayTimestamp = -1;
        for (JSONObject file : parsed) {
            JSONObject summary = file.optJSONObject("daily_summary");
            if (summary == null) continue;
            long timestamp = file.optLong("timestamp", 0);
            if (today == null || timestamp >= todayTimestamp) {
                today = summary;
                todayTimestamp = timestamp;
            }
        }
        result.put("status", "ok");
        result.put("synced_at", System.currentTimeMillis());
        result.put("source", "band-spp");
        result.put("address", config.mac);
        result.put("today", today == null ? JSONObject.NULL : today);
        JSONObject sleep = null;
        for (int i = 0; i < files.length(); i++) {
            JSONObject detail = files.optJSONObject(i) == null ? null : files.optJSONObject(i).optJSONObject("sleep_details");
            if (detail != null && detail.has("duration_minutes")) sleep = detail;
        }
        result.put("sleep", sleep == null ? JSONObject.NULL : sleep);
        result.put("files", files);
        return result;
    }

    private static void saveRaw(Context context, byte[] file) throws Exception {
        if (file == null || file.length < 12) return;
        StringBuilder name = new StringBuilder();
        for (int i = 0; i < 7; i++) name.append(String.format("%02x", file[i] & 0xff));
        java.io.File dir = new java.io.File(context.getFilesDir(), "band-files");
        if (!dir.isDirectory() && !dir.mkdirs()) return;
        java.io.File out = new java.io.File(dir, name + ".bin");
        if (out.isFile() && out.length() == file.length) return;
        java.io.FileOutputStream stream = new java.io.FileOutputStream(out);
        try { stream.write(file); } finally { stream.close(); }
    }

}

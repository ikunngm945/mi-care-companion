package cn.local.micompanion.health;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import org.json.JSONObject;
import java.io.File;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.zip.CRC32;

/** Authenticated link import boundary; native work never runs on the socket reader. */
public final class BandFileImporter {
    private final Context app;
    private final ClassLoader loader;
    private final Runnable changed;
    private final SharedPreferences ledger;
    private final java.util.concurrent.ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "mi-companion-band-import"); t.setDaemon(true); return t;
    });
    private final LinkedHashMap<String, Job> jobs = new LinkedHashMap<>();

    BandFileImporter(Context context, ClassLoader loader, Runnable changed) {
        this.app = context; this.loader = loader; this.changed = changed;
        ledger = context.getSharedPreferences("mi_companion_band_import", 0);
    }

    private static final class Source {
        final String account, mac, sid;
        Source(String account, String mac, String sid) { this.account=account; this.mac=mac; this.sid=sid; }
        String identity() { return account + "\n" + mac + "\n" + sid; }
    }
    /** Lifecycle shared by the worker and status endpoint; a settled attempt is always terminal. */
    public static class ImportState {
        volatile String status="pending", error="";
        private boolean started, succeeded;
        public String status() { return status; }
        public String error() { return error; }
        public void nativeStarted() { started=true; }
        public void nativeSucceeded() { succeeded=true; }
        public void waitingForNative() { error="native_inflight"; }
        public void finishedWithFailure(String cause) {
            if ("native_invalid_version".equals(cause)) error="unsupported";
            else if ("source_changed".equals(cause) || "source_changed_after_import".equals(cause))
                error="source_scope_mismatch";
            else if ("ledger_write_failed".equals(cause)) error="ledger_write_failed";
            else error=succeeded?"native_post_import_failed":started?"native_import_failed":"health_not_ready";
            status="error";
        }
    }

    private static final class Job extends ImportState {
        final Source source;
        final String key;
        byte[] raw;
        Job(Source source, String key, byte[] raw) { this.source=source; this.key=key; this.raw=raw; }
    }

    JSONObject handle(JSONObject request) {
        try {
            String action=request.optString("action");
            Source source=source(request.optString("mac"));
            if (!scope(new File(source.account)).equals(request.optString("source_scope")))
                return response("error", "source_scope_mismatch");
            if ("band_import_finish".equals(action)) {
                changed.run();
                return response("pending", "");
            }
            String id=request.optString("file_id"), hash=request.optString("sha256");
            if (!id.matches("[0-9a-f]{14}") || !hash.matches("[0-9a-f]{64}"))
                return response("error", "invalid_file_identity");
            String key=hex(MessageDigest.getInstance("SHA-256").digest(
                    (source.identity()+"\n"+id+"\n"+hash).getBytes(StandardCharsets.UTF_8)));
            synchronized (jobs) {
                Job old=jobs.get(key);
                if (old!=null && !"error".equals(old.status)) return response(old.status,old.error);
                String saved=ledger.getString(key, "");
                if ("ok".equals(saved)) return response("ok", "");
                // No live Job means the previous process exited. Native records upsert by sid/key/time,
                // so replay this exact account/device/file/hash rather than strand it forever.
                // A live timeout is caught above and is never replayed.
                if ("pending".equals(saved) && !ledger.edit().remove(key).commit())
                    return response("error", "ledger_write_failed");
                if ("band_import_status".equals(action))
                    return old==null ? response("missing", "") : response(old.status,old.error);
                if (!"band_import".equals(action)) return response("error", "unknown_action");
                String encoded=request.optString("data");
                if (encoded.length()>240000) return response("error", "file_too_large");
                byte[] raw=Base64.getDecoder().decode(encoded);
                validateFile(id,hash,raw);
                int pending=0;
                for (Job job:jobs.values()) if ("pending".equals(job.status)) pending++;
                if (pending>=16) return response("error", "import_queue_full");
                if (jobs.size()>=256) jobs.entrySet().removeIf(e -> !"pending".equals(e.getValue().status));
                Job job=new Job(source,key,raw); jobs.put(key,job);
                worker.execute(() -> importFile(job));
                return response("pending", "");
            }
        } catch (Throwable error) {
            return response("error", "device_not_paired".equals(error.getMessage()) ? "device_mismatch"
                    : error instanceof IllegalArgumentException ? "invalid_file_or_source" : "health_source_unavailable");
        }
    }

    /** Wire validation is pure and runs before any native state can change. */
    public static void validateFile(String id, String hash, byte[] raw) throws Exception {
        if (id==null || !id.matches("[0-9a-f]{14}") || hash==null || !hash.matches("[0-9a-f]{64}")
                || raw==null || raw.length<13 || raw.length>180000)
            throw new IllegalArgumentException("invalid_file");
        if (!hex(Arrays.copyOf(raw,7)).equals(id)
                || !hex(MessageDigest.getInstance("SHA-256").digest(raw)).equals(hash))
            throw new IllegalArgumentException("file_identity_mismatch");
        CRC32 crc=new CRC32(); crc.update(raw,0,raw.length-4);
        long expected=0;
        for (int i=0;i<4;i++) expected|=(raw[raw.length-4+i]&255L)<<(8*i);
        if (crc.getValue()!=expected) throw new IllegalArgumentException("file_crc_mismatch");
    }

    /** Only explicit native unsupported results skip a file; storage failures remain retryable. */
    public static boolean isUnsupportedResult(int code, String message) {
        return code==-2 || (code==1 && message!=null && message.startsWith("not support dataId"));
    }

    private void importFile(Job job) {
        boolean nativeSucceeded=false;
        try {
            if (!job.source.identity().equals(source(job.source.mac).identity()))
                throw new IllegalStateException("source_changed");
            Class<?> ids=loader.loadClass("com.xiaomi.fit.data.common.data.mi.FitnessDataId");
            Object id=ids.getConstructor(byte[].class).newInstance((Object)Arrays.copyOf(job.raw,7));
            byte[] header=(byte[])ids.getMethod("convertToServerDataId").invoke(id);
            byte[] input=new byte[header.length+job.raw.length-11];
            System.arraycopy(header,0,input,0,header.length);
            System.arraycopy(job.raw,7,input,header.length,job.raw.length-11);
            Class<?> processorType=loader.loadClass("com.xiaomi.fit.fitness.device.mi.parse.FitnessDataProcessor");
            Object companion=processorType.getField("Companion").get(null);
            Object processor=companion.getClass().getMethod("getInstance").invoke(companion);
            Class<?> continuation=loader.loadClass("kotlin.coroutines.Continuation");
            Method method=processorType.getMethod("parseAndRecord",String.class,ids,byte[].class,continuation);
            if (!ledger.edit().putString(job.key,"pending").commit())
                throw new IllegalStateException("ledger_write_failed");
            job.nativeStarted();
            Object result=invokeNative(processor,method,continuation,new Object[]{job.source.sid,id,input},job);
            if (result==null) throw new IllegalStateException("native_import_rejected");
            int code=((Number)result.getClass().getMethod("getCode").invoke(result)).intValue();
            if (!(Boolean)result.getClass().getMethod("isSuccess").invoke(result) || code!=0) {
                android.util.Log.w("MiCompanionBandImport", "Native import result code="+code);
                String message=(String)result.getClass().getMethod("getMsg").invoke(result);
                throw new IllegalStateException(isUnsupportedResult(code,message)
                        ?"native_invalid_version":"native_import_rejected");
            }
            nativeSucceeded=true;
            job.nativeSucceeded();
            if (!job.source.identity().equals(source(job.source.mac).identity()))
                throw new IllegalStateException("source_changed_after_import");
            if (!ledger.edit().putString(job.key,"ok").commit())
                throw new IllegalStateException("ledger_write_failed");
            job.error=""; job.status="ok";
            changed.run();
        } catch (Throwable failure) {
            // invokeNative only exits after the call/callback has settled. Only its internal wait
            // is in flight; settled failures must release the global replication debounce.
            if (!nativeSucceeded) ledger.edit().remove(job.key).commit();
            job.finishedWithFailure(unwrap(failure).getMessage());
            android.util.Log.w("MiCompanionBandImport", job.error+" ("+unwrap(failure).getClass().getSimpleName()+")");
            changed.run();
        } finally { job.raw=null; }
    }

    private Object invokeNative(Object target, Method method, Class<?> continuationType,
            Object[] args, Job job) throws Exception {
        CompletableFuture<Object> resumed=new CompletableFuture<>();
        Object empty=loader.loadClass("kotlin.coroutines.EmptyCoroutineContext").getField("INSTANCE").get(null);
        Method check=loader.loadClass("kotlin.ResultKt").getMethod("throwOnFailure",Object.class);
        Object continuation=Proxy.newProxyInstance(loader,new Class<?>[]{continuationType},(p,m,values)->{
            switch (m.getName()) {
                case "getContext": return empty;
                case "resumeWith":
                    try { check.invoke(null,values[0]); resumed.complete(values[0]); }
                    catch (Throwable e) { resumed.completeExceptionally(unwrap(e)); }
                    return null;
                case "toString": return "BandFileImportContinuation";
                case "hashCode": return System.identityHashCode(p);
                case "equals": return p==values[0];
                default: throw new UnsupportedOperationException(m.getName());
            }
        });
        Object[] full=Arrays.copyOf(args,args.length+1); full[args.length]=continuation;
        Object result=method.invoke(target,full);
        Method suspended=loader.loadClass("kotlin.coroutines.intrinsics.IntrinsicsKt")
                .getMethod("getCOROUTINE_SUSPENDED"); suspended.setAccessible(true);
        if (result!=suspended.invoke(null)) return result;
        try { return resumed.get(30,TimeUnit.SECONDS); }
        catch (TimeoutException timeout) {
            job.waitingForNative();
            // join ignores thread interruption: this sole worker cannot leave a native call in flight.
            return resumed.join();
        } catch (InterruptedException interrupted) {
            job.waitingForNative();
            try { return resumed.join(); }
            finally { Thread.currentThread().interrupt(); }
        }
    }

    private Source source(String requestedMac) throws Exception {
        String mac=requestedMac.trim().toUpperCase(Locale.US);
        if (!mac.matches("[0-9A-F]{2}(:[0-9A-F]{2}){5}")) throw new IllegalArgumentException("mac");
        File path=accountDirectory();
        File root=app.getDatabasePath("device_db").getParentFile();
        String sid=null;
        try (SQLiteDatabase db=SQLiteDatabase.openDatabase(new File(root,"device_db").getAbsolutePath(),null,
                SQLiteDatabase.OPEN_READONLY|SQLiteDatabase.NO_LOCALIZED_COLLATORS);
             Cursor cursor=db.rawQuery("SELECT did,identifier,detail,extraValues FROM device",null)) {
            while (cursor.moveToNext()) {
                boolean match=mac.equalsIgnoreCase(cursor.getString(1));
                for (int i=2;i<4;i++) {
                    String raw=cursor.getString(i);
                    if (raw==null || raw.length()>65536) continue;
                    try { match |= mac.equalsIgnoreCase(new JSONObject(raw).optString("mac")); }
                    catch (Exception ignored) { }
                }
                if (!match) continue;
                String candidate=cursor.getString(0);
                if (candidate==null || candidate.isEmpty() || (sid!=null && !sid.equals(candidate)))
                    throw new IllegalStateException("ambiguous_device");
                sid=candidate;
            }
        }
        if (sid==null) throw new IllegalArgumentException("device_not_paired");
        return new Source(path.getCanonicalPath(),mac,sid);
    }

    String sourceScope() throws Exception { return scope(accountDirectory()); }

    private static String scope(File path) throws Exception {
        return hex(MessageDigest.getInstance("SHA-256").digest(
                (path.getParentFile().getName()+"|"+path.getName()).getBytes(StandardCharsets.UTF_8)));
    }

    File accountDirectory() throws Exception {
        Object account=extension("com.xiaomi.fitness.account.manager.AccountManager",
                "com.xiaomi.fitness.account.extensions.AccountManagerExtKt");
        if (!(Boolean)loader.loadClass("com.xiaomi.fitness.account.manager.AccountManager")
                .getMethod("isLogin").invoke(account)) throw new IllegalStateException("not_logged_in");
        String user=(String)loader.loadClass("com.xiaomi.fitness.account.manager.AccountManager")
                .getMethod("getUserId").invoke(account);
        Object regions=extension("com.xiaomi.fitness.login.export.RegionManager",
                "com.xiaomi.fitness.login.export.RegionExtKt");
        String region=(String)loader.loadClass("com.xiaomi.fitness.login.export.RegionManager")
                .getMethod("getCurrentRegion").invoke(regions);
        if (user==null || !user.matches("[A-Za-z0-9_-]+") || region==null || !region.matches("[A-Za-z0-9_-]+"))
            throw new IllegalStateException("account");
        File path=new File(app.getDatabasePath("device_db").getParentFile(),user+"/"+region);
        if (!new File(path,"fitness_data").isFile()) throw new IllegalStateException("account_database");
        return path;
    }

    boolean pending() {
        synchronized(jobs) { for (Job job:jobs.values()) if ("pending".equals(job.status)) return true; }
        return false;
    }

    private Object extension(String owner, String extensions) throws Exception {
        Class<?> type=loader.loadClass(owner);
        Object companion=type.getField("Companion").get(null);
        return loader.loadClass(extensions).getMethod("getInstance",companion.getClass()).invoke(null,companion);
    }
    static void broadcastChanged(Context app) {
        app.sendBroadcast(new Intent("history_fitness_data_changed_broadcast").setPackage(app.getPackageName())
                .putExtra("reason_data_changed","device_sync").putExtra("mi_companion_import",true));
    }
    private static Throwable unwrap(Throwable e) {
        while (e instanceof InvocationTargetException && e.getCause()!=null) e=e.getCause();
        return e;
    }
    private static JSONObject response(String status,String error) {
        try { JSONObject out=new JSONObject().put("status",status); if (!error.isEmpty())out.put("error",error);return out; }
        catch (Exception impossible) { throw new IllegalStateException(impossible); }
    }
    private static String hex(byte[] bytes) {
        StringBuilder out=new StringBuilder(bytes.length*2);
        for (byte b:bytes)out.append(String.format(Locale.US,"%02x",b&255));
        return out.toString();
    }
}

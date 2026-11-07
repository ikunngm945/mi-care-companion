package cn.local.micompanion.health;

import android.app.Application;
import android.content.Context;
import android.database.sqlite.SQLiteDatabase;
import cn.local.micompanion.LocalCollector;
import cn.local.micompanion.HealthSource;
import cn.local.micompanion.HealthQueryService;
import cn.local.micompanion.hook.LinkClient;
import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;
import org.json.JSONObject;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/** Reads the health app only, then pushes the snapshot to the main app over loopback WebSocket. */
public final class HealthHook implements IXposedHookLoadPackage {
    private static final ScheduledExecutorService worker = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread thread = new Thread(r, "mi-companion-health");
        thread.setDaemon(true);
        return thread;
    });
    private static volatile Context target;
    private static volatile LinkClient client;
    private static volatile boolean started;
    private static volatile boolean databaseSent;
    private static volatile boolean fitnessSent;
    private static volatile boolean databaseBusy;

    @Override public void handleLoadPackage(final XC_LoadPackage.LoadPackageParam lp) throws Throwable {
        if (!"com.mi.health".equals(lp.packageName)) return;
        if (!lp.packageName.equals(lp.processName)) return;
        XposedBridge.hookAllMethods(SQLiteDatabase.class, "openDatabase", new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam param) {
                if (param.getResult() instanceof SQLiteDatabase) {
                    String path = ((SQLiteDatabase)param.getResult()).getPath();
                    if (path != null && path.endsWith("/fitness_summary") && path.contains("/com.mi.health/"))
                        LocalCollector.activeHealthPath = path;
                }
            }
        });
        HealthAboutInjector.install(lp.classLoader);
        XposedHelpers.findAndHookMethod(Application.class, "onCreate", new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam param) {
                Context app = (Context)param.thisObject;
                target = app;
                client = LinkClient.shared(app, "health");
                client.setQueryHandler(query -> {
                    if ("sync_database".equals(query.optString("action"))) {
                        requestDatabase();
                        return new JSONObject().put("status", "ok").put("action", "sync_database");
                    }
                    return HealthQueryService.query(app, query);
                });
                client.start();
                if (!started) {
                    started = true;
                    worker.scheduleWithFixedDelay(HealthHook::publish, 60, 60, TimeUnit.SECONDS);
                    worker.scheduleWithFixedDelay(HealthHook::publishDatabase, 10, 60, TimeUnit.SECONDS);
                }
            }
        });
    }

    private static void requestDatabase() {
        databaseSent = false;
        fitnessSent = false;
        worker.execute(HealthHook::publishDatabase);
    }

    private static void publishDatabase() {
        if (databaseBusy) return;
        Context context = target;
        LinkClient link = client;
        if (context == null || link == null || !link.status().connected) return;
        File source;
        try {
            File dir = HealthSource.account(context);
            source = dir == null ? null : new File(dir, "fitness_data");
        } catch (Throwable ignored) { return; }
        if (source == null || !source.isFile()) return;
        databaseBusy = true;
        File copy = new File(context.getCacheDir(), "fitness_data.sync");
        File zip = new File(context.getCacheDir(), "fitness.sync.zip");
        try {
            if (!databaseSent) {
                copy.delete();
                copyDatabase(source, copy);
                if (!sendFile(link, "fitness_data", copy)) return;
                databaseSent = true;
            }
            if (!fitnessSent) {
                zip.delete();
                File fitness = new File(context.getFilesDir(), "fitness");
                if (fitness.isDirectory()) {
                    zipFitness(fitness, zip);
                    if (!sendFile(link, "fitness", zip)) return;
                }
                fitnessSent = true;
            }
        } catch (Throwable ex) {
            XposedBridge.log("MiCompanionHealth: database publish failed (" + ex.getClass().getSimpleName() + ")");
        } finally {
            copy.delete();
            zip.delete();
            databaseBusy = false;
        }
    }

    private static boolean sendFile(LinkClient link, String name, File file) throws Exception {
        long length = file.length();
        if (length <= 0 || length > 256L * 1024 * 1024) return false;
        if (!link.sendMessage(new JSONObject().put("type", "database_begin").put("name", name)
                .put("length", length).put("sha256", sha256(file)))) return false;
        FileInputStream in = new FileInputStream(file);
        try {
            byte[] buffer = new byte[180000];
            int count;
            while ((count = in.read(buffer)) != -1) {
                if (!link.sendMessage(new JSONObject().put("type", "database_chunk")
                        .put("data", Base64.getEncoder().encodeToString(
                                java.util.Arrays.copyOf(buffer, count))))) return false;
            }
        } finally {
            in.close();
        }
        return link.sendMessage(new JSONObject().put("type", "database_end"));
    }

    private static void zipFitness(File source, File target) throws Exception {
        java.util.zip.ZipOutputStream out = new java.util.zip.ZipOutputStream(new FileOutputStream(target));
        try {
            zipTree(source, source, out);
        } finally {
            out.close();
        }
    }

    private static void zipTree(File root, File file, java.util.zip.ZipOutputStream out) throws Exception {
        File[] children = file.listFiles();
        if (children == null) return;
        for (File child : children) {
            String path = root.toURI().relativize(child.toURI()).getPath();
            if (child.isDirectory()) {
                zipTree(root, child, out);
            } else if (child.isFile()) {
                out.putNextEntry(new java.util.zip.ZipEntry(path));
                FileInputStream in = new FileInputStream(child);
                try {
                    byte[] buffer = new byte[65536];
                    int count;
                    while ((count = in.read(buffer)) != -1) out.write(buffer, 0, count);
                } finally {
                    in.close();
                }
                out.closeEntry();
            }
        }
    }

    private static void copyDatabase(File source, File target) throws Exception {
        SQLiteDatabase database = HealthSource.open(source);
        try {
            target.delete();
            database.execSQL("VACUUM INTO '" + target.getAbsolutePath().replace("'", "''") + "'");
        } finally {
            database.close();
        }
        if (!target.isFile() || target.length() == 0) throw new IllegalStateException("数据库快照为空");
    }

    private static String sha256(File file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        FileInputStream in = new FileInputStream(file);
        try {
            byte[] buffer = new byte[65536];
            int count;
            while ((count = in.read(buffer)) != -1) digest.update(buffer, 0, count);
        } finally {
            in.close();
        }
        StringBuilder out = new StringBuilder();
        for (byte b : digest.digest()) out.append(String.format(java.util.Locale.US, "%02x", b));
        return out.toString();
    }

    private static void publish() {
        Context context = target;
        LinkClient link = client;
        if (context == null) return;
        try {
            JSONObject snapshot = LocalCollector.collect(context, "health");
            if (link != null) link.send(snapshot);
        } catch (Throwable ex) {
            XposedBridge.log("MiCompanionHealth: snapshot publish failed (" + ex.getClass().getSimpleName() + ")");
        }
    }
}

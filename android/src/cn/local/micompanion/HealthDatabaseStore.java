package cn.local.micompanion;

import android.content.Context;
import android.database.sqlite.SQLiteDatabase;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileInputStream;
import java.io.OutputStream;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.TimeZone;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import org.json.JSONObject;

/** Receives the health module's local fitness_data copy and serves MCP queries from it. */
final class HealthDatabaseStore {
    private static final long MAX_BYTES = 256L * 1024 * 1024;
    private static FileOutputStream stream;
    private static File temp;
    private static File target;
    private static long expected;
    private static long written;
    private static String sha256;
    private static String name = "fitness_data";

    private HealthDatabaseStore() { }

    static File directory(Context context) {
        return new File(context.getFilesDir(), "health");
    }

    static File file(Context context) {
        return new File(directory(context), "fitness_data.db");
    }

    static boolean available(Context context) {
        return file(context).isFile();
    }

    static void accept(Context context, JSONObject message) throws Exception {
        accept(directory(context), message);
    }

    static synchronized void accept(File directory, JSONObject message) throws Exception {
        String type = message.optString("type", "");
        if ("database_begin".equals(type)) {
            long length = message.optLong("length", -1);
            if (length <= 0 || length > MAX_BYTES) throw new IllegalArgumentException("数据库大小无效");
            name = message.optString("name", "fitness_data");
            if (!"fitness_data".equals(name) && !"fitness".equals(name)) throw new IllegalArgumentException("未知数据库类型");
            if (stream != null) abort();
            if (!directory.isDirectory() && !directory.mkdirs()) throw new IllegalStateException("无法创建健康数据目录");
            target = new File(directory, "fitness".equals(name) ? "fitness.zip" : "fitness_data.db");
            temp = new File(directory, target.getName() + ".part");
            if (temp.exists() && !temp.delete()) throw new IllegalStateException("无法清理旧数据库");
            stream = new FileOutputStream(temp);
            expected = length;
            written = 0;
            sha256 = message.optString("sha256", "");
        } else if ("database_chunk".equals(type)) {
            if (stream == null) throw new IllegalStateException("数据库传输尚未开始");
            String data = message.optString("data", "");
            byte[] bytes = Base64.getDecoder().decode(data);
            if (written + bytes.length > expected) throw new IllegalArgumentException("数据库内容超过声明大小");
            stream.write(bytes);
            written += bytes.length;
        } else if ("database_end".equals(type)) {
            if (stream == null) throw new IllegalStateException("数据库传输尚未开始");
            stream.close();
            stream = null;
            if (written != expected) {
                abort();
                throw new IllegalArgumentException("数据库内容不完整");
            }
            if (!sha256.isEmpty()) {
                String actual = digest(temp);
                if (!sha256.equalsIgnoreCase(actual)) {
                    abort();
                    throw new IllegalArgumentException("数据库校验失败");
                }
            }
            if (target.exists() && !target.delete()) {
                abort();
                throw new IllegalStateException("无法替换旧数据库");
            }
            File saved = target;
            if (!temp.renameTo(saved)) {
                abort();
                throw new IllegalStateException("无法保存数据库");
            }
            boolean packed = "fitness".equals(name);
            temp = null;
            target = null;
            expected = 0;
            written = 0;
            sha256 = "";
            name = "fitness_data";
            if (packed) {
                unzip(saved, new File(directory, "fitness"));
                saved.delete();
            }
        } else {
            throw new IllegalArgumentException("未知数据库消息");
        }
    }

    static synchronized void abort() {
        if (stream != null) try { stream.close(); } catch (Exception ignored) { }
        stream = null;
        if (temp != null && temp.exists()) temp.delete();
        temp = null;
        target = null;
        expected = 0;
        written = 0;
        sha256 = "";
        name = "fitness_data";
    }

    private static void unzip(File archive, File root) throws Exception {
        if (root.exists() && !deleteTree(root)) throw new IllegalStateException("无法清理旧运动详情");
        if (!root.mkdirs()) throw new IllegalStateException("无法创建运动详情目录");
        long total = 0;
        ZipInputStream in = new ZipInputStream(new FileInputStream(archive));
        try {
            ZipEntry entry;
            byte[] buffer = new byte[65536];
            while ((entry = in.getNextEntry()) != null) {
                File out = new File(root, entry.getName());
                String path = out.getCanonicalPath();
                if (!path.startsWith(root.getCanonicalPath() + File.separator)) throw new IllegalArgumentException("运动详情路径非法");
                if (entry.isDirectory()) {
                    if (!out.mkdirs() && !out.isDirectory()) throw new IllegalStateException("无法创建运动详情目录");
                    continue;
                }
                File parent = out.getParentFile();
                if (parent != null && !parent.isDirectory() && !parent.mkdirs() && !parent.isDirectory()) {
                    throw new IllegalStateException("无法创建运动详情目录");
                }
                OutputStream writer = new FileOutputStream(out);
                try {
                    int count;
                    while ((count = in.read(buffer)) != -1) {
                        total += count;
                        if (total > 64L * 1024 * 1024) throw new IllegalArgumentException("运动详情过大");
                        writer.write(buffer, 0, count);
                    }
                } finally {
                    writer.close();
                }
            }
        } finally {
            in.close();
        }
    }

    private static boolean deleteTree(File file) {
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) for (File child : children) if (!deleteTree(child)) return false;
        }
        return file.delete();
    }

    private static String digest(File file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        java.io.FileInputStream in = new java.io.FileInputStream(file);
        try {
            byte[] buffer = new byte[65536];
            int count;
            while ((count = in.read(buffer)) != -1) digest.update(buffer, 0, count);
        } finally {
            in.close();
        }
        return hex(digest.digest());
    }

    private static String hex(byte[] bytes) {
        StringBuilder out = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) out.append(String.format(java.util.Locale.US, "%02x", b));
        return out.toString();
    }

    static JSONObject query(Context context, JSONObject request) throws Exception {
        File source = file(context);
        if (!source.isFile()) throw new IllegalStateException("健康数据库尚未同步");
        SQLiteDatabase database = null;
        try {
            database = HealthSource.open(source);
            return HealthQuery.query(HealthSource.reader(database), request,
                    System.currentTimeMillis(), TimeZone.getDefault(), new File(directory(context), "fitness"));
        } finally {
            if (database != null) try { database.close(); } catch (Exception ignored) { }
        }
    }
}

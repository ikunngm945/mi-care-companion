package cn.local.micompanion;

import android.app.Activity;
import android.content.Context;
import android.content.pm.PackageManager;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.concurrent.TimeUnit;
import rikka.shizuku.Shizuku;

/** Small Shizuku bridge used when the app has no root grant. */
final class ShizukuShell {
    private static final int REQUEST_CODE = 4217;
    private static Shizuku.OnRequestPermissionResultListener listener;

    private ShizukuShell() { }

    static boolean installed(Context context) {
        try {
            context.getPackageManager().getPackageInfo("moe.shizuku.privileged.api", 0);
            return true;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    static boolean running() {
        try {
            return Shizuku.pingBinder();
        } catch (Throwable e) {
            return false;
        }
    }

    static boolean granted() {
        try {
            return running() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED;
        } catch (Throwable e) {
            return false;
        }
    }

    static String status(Context context) {
        if (!installed(context)) return "未安装";
        if (!running()) return "未运行";
        return granted() ? "已授权" : "未授权";
    }

    static String request(Activity activity) {
        if (!installed(activity)) return "未安装 Shizuku";
        if (!running()) return "Shizuku 未运行，请先打开 Shizuku";
        try {
            if (Shizuku.isPreV11()) return "Shizuku 版本过低，请升级";
            if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
                return "Shizuku 已授权，可在 Root 不可用时自动拉起";
            }
            Shizuku.requestPermission(REQUEST_CODE);
            return "已请求 Shizuku 授权，请在 Shizuku 弹窗中允许";
        } catch (Throwable e) {
            return "Shizuku 授权失败：" + e.getClass().getSimpleName();
        }
    }

    static void listen(Runnable refresh) {
        try {
            unlisten();
            listener = (requestCode, grantResult) -> refresh.run();
            Shizuku.addRequestPermissionResultListener(listener);
        } catch (Throwable ignored) { }
    }

    static void unlisten() {
        if (listener == null) return;
        try { Shizuku.removeRequestPermissionResultListener(listener); } catch (Throwable ignored) { }
        listener = null;
    }

    /** Executes a shell command as Shizuku's shell/root identity. */
    static String run(String command) throws Exception {
        if (!granted()) throw new IllegalStateException("Shizuku 未授权");
        Process process;
        try {
            Method method = Shizuku.class.getDeclaredMethod("newProcess",
                    String[].class, String[].class, String.class);
            method.setAccessible(true);
            process = (Process) method.invoke(null, new String[]{"sh", "-c", command}, null, null);
        } catch (InvocationTargetException e) {
            Throwable cause = e.getCause();
            throw cause instanceof Exception ? (Exception) cause : e;
        }
        StringBuilder out = new StringBuilder();
        Thread reader = new Thread(() -> {
            try (BufferedReader in = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = in.readLine()) != null) if (out.length() < 2000) out.append(line).append('\n');
            } catch (Exception ignored) { }
        }, "mi-companion-shizuku");
        reader.setDaemon(true);
        reader.start();
        if (!process.waitFor(8, TimeUnit.SECONDS)) {
            process.destroy();
            reader.join(500);
            throw new IllegalStateException("Shizuku timeout");
        }
        reader.join(500);
        return out.toString();
    }
}

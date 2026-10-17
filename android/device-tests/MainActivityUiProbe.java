package cn.local.micompanion.tests;

import cn.local.micompanion.AdvicePrompt;
import cn.local.micompanion.SettingsStore;
import android.app.Activity;
import android.app.Instrumentation;
import android.content.Intent;
import android.graphics.Rect;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ScrollView;
import android.widget.TextView;
import java.lang.reflect.Field;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Real Android UI regression probe; compile only on the host, run explicitly on a device.
 * Uses synthetic, invalid drafts: no valid configuration is saved and no event is sent.
 * Does not copy private settings, print input values, or disable FLAG_SECURE.
 */
public final class MainActivityUiProbe extends Instrumentation {
    private Activity activity;
    private final AtomicBoolean finished = new AtomicBoolean();
    private volatile String stage = "created";
    @Override public void onCreate(Bundle args) { super.onCreate(args); start(); }
    @Override public void onStart() {
        Thread watchdog = new Thread(() -> {
            try { Thread.sleep(60000); }
            catch (InterruptedException completed) { return; }
            Bundle timeout = new Bundle();
            timeout.putString("failure", "UI probe timed out after 60 seconds");
            timeout.putString("stage", stage);
            finishOnce(1, timeout);
        }, "ui-probe-watchdog");
        watchdog.setDaemon(true);
        watchdog.start();
        markStage("on_start");
        Bundle result = new Bundle();
        int code = 0;
        try (ServerSocket endpoint = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) {
            java.nio.file.Path settings=new java.io.File(getTargetContext().getNoBackupFilesDir(),"settings.json").toPath();
            byte[] settingsBefore=java.nio.file.Files.readAllBytes(settings);
            endpoint.setSoTimeout(100);
            AtomicBoolean connected = new AtomicBoolean();
            AtomicBoolean listening = new AtomicBoolean(true);
            Thread listener = new Thread(() -> {
                while (listening.get()) {
                    try (Socket ignored = endpoint.accept()) { connected.set(true); }
                    catch (SocketTimeoutException expected) { /* bounded observation */ }
                    catch (Exception failure) { if (listening.get()) connected.set(true); return; }
                }
            }, "ui-probe-loopback");
            listener.start();
            try {
                markStage("before_start_activity_sync");
                ActivityMonitor monitor = addMonitor("cn.local.micompanion.MainActivity", null, false);
                // Android 16 blocks a background app's Activity launch; use the test shell identity.
                try (android.os.ParcelFileDescriptor command = getUiAutomation().executeShellCommand(
                        "am start -W -n cn.local.micompanion/.MainActivity");
                     java.io.InputStream output = new android.os.ParcelFileDescriptor.AutoCloseInputStream(command)) {
                    byte[] buffer = new byte[1024]; while (output.read(buffer) != -1) { }
                }
                activity = monitor.waitForActivityWithTimeout(10000);
                removeMonitor(monitor);
                require(activity != null, "shell launch did not report an Activity");
                markStage("after_start_activity_sync");
                awaitIdle("launch");
                markStage("before_icon");
                testInstalledIcon();
                markStage("before_report_geometry");
                testReportNearCheckAndSecureWindow();
                markStage("before_stationary_feedback");
                testStationaryFeedback();
                markStage("before_prompt_draft");
                testPromptDraftAndRestore();
                markStage("before_save_validation");
                testInvalidDraftReturnsVisibleError("save", "正在保存", endpoint.getLocalPort(), false);
                markStage("before_check_validation");
                testInvalidDraftReturnsVisibleError("check", "正在检查", endpoint.getLocalPort(), false);
                markStage("before_oversized_prompt_validation");
                testInvalidDraftReturnsVisibleError("save", "正在保存", endpoint.getLocalPort(), true);
                // Observe accidental asynchronous networking after both operations have completed.
                SystemClock.sleep(300);
                require(!connected.get(), "invalid draft attempted network access");
                require(java.util.Arrays.equals(settingsBefore,java.nio.file.Files.readAllBytes(settings)),"UI regression changed saved encrypted settings");
                result.putString("ui", "PASS: stationary button feedback/progress/results, isolated diagnostics, secure window");
                result.putString("network", "PASS: no loopback connection for invalid save/check drafts");
                java.io.File events=new java.io.File(getTargetContext().getNoBackupFilesDir(),"events.db");
                if(events.isFile())try(android.database.sqlite.SQLiteDatabase db=android.database.sqlite.SQLiteDatabase.openDatabase(events.getAbsolutePath(),null,android.database.sqlite.SQLiteDatabase.OPEN_READONLY);android.database.Cursor rows=db.rawQuery("SELECT count(*) FROM events WHERE stage IN ('pending','generating','ready','sending','retry')",null)){
                    rows.moveToFirst();result.putInt("potentially_active_events",rows.getInt(0));
                }
            } finally {
                listening.set(false);
                listener.join(1000);
            }
        } catch (Throwable failure) {
            code = 1;
            // Assertion messages are fixed strings, never field values or server responses.
            result.putString("failure", failure instanceof AssertionError ? failure.getMessage() : failure.getClass().getSimpleName());
        } finally {
            markStage("before_activity_cleanup");
            if (activity != null) onUi(() -> activity.finish());
        }
        markStage("complete");
        finishOnce(code, result);
        watchdog.interrupt();
    }
    private void markStage(String name) {
        stage = name;
        Bundle progress = new Bundle();
        progress.putString("stage", name);
        sendStatus(0, progress);
    }
    private void awaitIdle(String reason) {
        markStage("before_wait_idle_" + reason);
        waitForIdleSync();
        markStage("after_wait_idle_" + reason);
    }
    private void finishOnce(int code, Bundle result) {
        if (finished.compareAndSet(false, true)) finish(code, result);
    }
    private void testInstalledIcon() throws Exception {
        android.graphics.drawable.Drawable icon=getTargetContext().getApplicationInfo().loadIcon(getTargetContext().getPackageManager());
        require(icon instanceof android.graphics.drawable.AdaptiveIconDrawable,"launcher icon is not adaptive");
        android.graphics.Bitmap rendered=android.graphics.Bitmap.createBitmap(512,512,android.graphics.Bitmap.Config.ARGB_8888);
        icon.setBounds(0,0,512,512);icon.draw(new android.graphics.Canvas(rendered));
        try(java.io.FileOutputStream out=new java.io.FileOutputStream(new java.io.File(getTargetContext().getCacheDir(),"icon-installed-preview.png"))){rendered.compress(android.graphics.Bitmap.CompressFormat.PNG,100,out);}
        android.content.res.Resources resources=getTargetContext().getResources();
        int id=resources.getIdentifier("ic_companion_art","drawable",getTargetContext().getPackageName());
        android.graphics.Bitmap art=android.graphics.BitmapFactory.decodeResource(resources,id);
        require(art!=null&&art.getWidth()==512&&art.getHeight()==512,"approved icon artwork missing");
        require(android.graphics.Color.red(art.getPixel(256,175))>200,"approved sun artwork missing");
    }
    private void testReportNearCheckAndSecureWindow() {
        onUi(() -> {
            TextView report = field("report", TextView.class);
            Button check = field("check", Button.class);
            ViewGroup parent = (ViewGroup) check.getParent();
            require(report.getParent() == parent && parent.indexOfChild(report) == parent.indexOfChild(check) + 1,
                    "operation report is not directly after check button");
            TextView diagnostics = field("diagnosticsReport", TextView.class);
            require(diagnostics != report && parent.indexOfChild(diagnostics) == parent.getChildCount() - 1,
                    "long diagnostics do not have a separate bottom report");
            require((activity.getWindow().getAttributes().flags & WindowManager.LayoutParams.FLAG_SECURE) != 0,
                    "FLAG_SECURE was removed");
        });
    }
    private void testStationaryFeedback() throws Exception {
        onUi(() -> {
            field("diagnosticsReport",TextView.class).setText(new String(new char[33000]).replace('\0','D'));
            field("report",TextView.class).setText("existing feedback");
            field("scroll",ScrollView.class).scrollTo(0,0);
        });
        awaitIdle("stationary_setup");SystemClock.sleep(200);
        for(int position:new int[]{0,320,900}){
            onUi(() -> {
                ScrollView sc=field("scroll",ScrollView.class);sc.scrollTo(0,position);
                require(field("restorePrompt",Button.class).performClick(),"restore click failed");
            });
            awaitIdle("stationary_restore");SystemClock.sleep(250);
            onUi(() -> require(field("scroll",ScrollView.class).getScrollY()==position,"button feedback changed reading position"));
        }
    }
    private void testPromptDraftAndRestore() throws Exception {
        String savedPrompt = SettingsStore.load(getTargetContext()).prompt;
        onUi(() -> {
            EditText prompt = field("prompt", EditText.class);
            require(!prompt.isSaveEnabled(), "prompt draft is stored in Android instance state");
            require((prompt.getInputType() & android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE) != 0,
                    "prompt is not multiline");
            require(prompt.getMaxLines() == 5, "prompt does not use a five-line editor");
            String original = prompt.getText().toString();
            try {
                String oversized = new String(new char[4001]).replace('\0', 'P');
                prompt.setText(oversized);
                require(prompt.length() == 4001, "prompt editor silently truncated oversized input");
                prompt.setText("第一行\n第二行");
                require(prompt.getText().toString().equals("第一行\n第二行"), "prompt editor discarded a newline");
                require(field("restorePrompt", Button.class).performClick(), "restore prompt did not handle click");
                require(AdvicePrompt.DEFAULT.equals(prompt.getText().toString()),
                        "restore prompt did not restore the default draft");
                String feedback = field("report", TextView.class).getText().toString();
                require(feedback.contains("保存后生效"), "restore prompt omitted save-to-apply feedback");
            } finally { prompt.setText(original); }
        });
        require(savedPrompt.equals(SettingsStore.load(getTargetContext()).prompt), "restoring prompt saved without confirmation");
    }
    private void testInvalidDraftReturnsVisibleError(String action, String progress, int port, boolean oversizedPrompt) {
        final String longDiagnostics = new String(new char[33000]).replace('\0', 'D');
        final String[] originalPrompt = new String[1];
        CountDownLatch releaseWorker = new CountDownLatch(1);
        field("worker", ExecutorService.class).execute(() -> {
            try { releaseWorker.await(10, TimeUnit.SECONDS); }
            catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
        });
        try {
        onUi(() -> {
            field("url", EditText.class).setText("http://127.0.0.1:" + port + "/api/v1");
            field("key", EditText.class).setText(oversizedPrompt ? "ui-probe-invalid-key" : "");
            field("umo", EditText.class).setText("ui-probe-synthetic-target");
            originalPrompt[0] = field("prompt", EditText.class).getText().toString();
            if (oversizedPrompt) field("prompt", EditText.class).setText(new String(new char[4001]).replace('\0', 'P'));
            field("http", CheckBox.class).setChecked(true);
            TextView diagnostics = field("diagnosticsReport", TextView.class);
            diagnostics.setText(longDiagnostics);
            ScrollView scroll = (ScrollView) diagnostics.getParent().getParent();
            field("umo", EditText.class).clearFocus();
            scroll.scrollTo(0,0);
            Button button = field(action, Button.class);
            require(button.performClick(), "save/check button did not handle click");
            require(button.getText().toString().contains(progress), "button does not immediately show operation progress");
            require(!button.isEnabled(), "button remains enabled while work is pending");
            require(!field("prompt", EditText.class).isEnabled() && !field("restorePrompt", Button.class).isEnabled(),
                    "prompt controls remain enabled while work is pending");
            require(field("report", TextView.class).getText().length() > 0, "operation report has no immediate progress");
        });
        awaitIdle(action + "_progress");
        SystemClock.sleep(200);
        onUi(() -> {
            TextView report = field("report", TextView.class);
            require(field("scroll",ScrollView.class).getScrollY()==0,"operation progress changed reading position");
            require(field(action, Button.class).getText().toString().contains(progress),
                    "progress label disappeared before worker completion");
            // Simulate reading elsewhere while the background operation is still blocked.
            field("scroll",ScrollView.class).scrollTo(0,420);
        });
        } finally { releaseWorker.countDown(); }
        long deadline = SystemClock.uptimeMillis() + 5000;
        AtomicBoolean completed = new AtomicBoolean();
        while (!completed.get() && SystemClock.uptimeMillis() < deadline) {
            awaitIdle(action + "_completion");
            onUi(() -> completed.set(field(action, Button.class).isEnabled()));
            if (!completed.get()) SystemClock.sleep(20);
        }
        require(completed.get(), "invalid draft did not finish within five seconds");
        awaitIdle(action + "_result");
        onUi(() -> {
            TextView report = field("report", TextView.class);
            String message = report.getText().toString();
            require(oversizedPrompt ? message.contains("4000")
                            : message.contains("API") && (message.contains("key") || message.contains("Key")),
                    "invalid draft validation error was not displayed");
            require(!message.contains("127.0.0.1") && !message.contains("ui-probe-synthetic-target"),
                    "operation report exposed input values");
            require(!field("key", EditText.class).hasFocus(), "operation feedback moved focus to API key");
            require(field("scroll",ScrollView.class).getScrollY()==420,"operation result changed reading position");
            require(longDiagnostics.contentEquals(field("diagnosticsReport", TextView.class).getText()),
                    "ordinary operation replaced the long diagnostics");
            require(report.length() < 2000, "long diagnostics leaked into the top operation report");
            require(field("key", EditText.class).getText().toString().equals(oversizedPrompt ? "ui-probe-invalid-key" : "")
                            && field("umo", EditText.class).getText().toString().equals("ui-probe-synthetic-target"),
                    "operation changed the draft inputs");
            require(field("prompt", EditText.class).isEnabled() && field("restorePrompt", Button.class).isEnabled(),
                    "prompt controls were not restored after validation failure");
            if (oversizedPrompt) {
                require(field("prompt", EditText.class).length() == 4001, "invalid prompt draft was truncated after validation");
                field("prompt", EditText.class).setText(originalPrompt[0]);
            }
        });
    }
    private void onUi(Runnable assertions) {
        AtomicReference<Throwable> failure = new AtomicReference<>();
        runOnMainSync(() -> {
            try { assertions.run(); }
            catch (Throwable thrown) { failure.set(thrown); }
        });
        if (failure.get() != null) throw new AssertionError(failure.get().getMessage());
    }
    private <T> T field(String name, Class<T> type) {
        try {
            Field field = activity.getClass().getDeclaredField(name);
            field.setAccessible(true);
            return type.cast(field.get(activity));
        } catch (ReflectiveOperationException failure) { throw new AssertionError("missing UI contract: " + name); }
    }
    private static void require(boolean value, String message) { if (!value) throw new AssertionError(message); }
}

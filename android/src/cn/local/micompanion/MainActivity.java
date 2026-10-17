package cn.local.micompanion;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.NotificationManager;
import android.app.usage.UsageStatsManager;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.PowerManager;
import android.provider.Settings;
import android.text.InputType;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Native Chinese settings screen; no network operation runs on the main thread. */
public final class MainActivity extends Activity {
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private EditText url, key, umo, prompt;
    private CheckBox http;
    private Button test, check, save, retry, diagnostic, clear, restorePrompt;
    private TextView state, report, diagnosticsReport;
    private boolean busy;
    private LinearLayout layout;
    private ScrollView scroll;
    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        // Do not expose API credentials or health diagnostics in task previews/screenshots.
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        scroll = new ScrollView(this); scroll.setSmoothScrollingEnabled(false);
        layout = new LinearLayout(this); layout.setOrientation(LinearLayout.VERTICAL);
        int pad = (int)(16 * getResources().getDisplayMetrics().density);
        layout.setPadding(pad, pad, pad, pad); scroll.addView(layout); setContentView(scroll);
        text("小米关怀助手", 24);
        text("只向你配置的 AstrBot 发送天气、屏幕与健康摘要。缺失数据会明确标注，不会伪造成功。", 15);
        url = input("AstrBot 官方 API base URL（例如 http://127.0.0.1:6185/api/v1）", false);
        key = input("AstrBot 官方 API Key（需 chat + im + config 权限，非模型 Key；本机加密、不备份）", true);
        umo = input("目标 UMO（AstrBot 中输入 /sid 获取）", false);
        text("自动路由（只读）：按目标 UMO 匹配 AstrBot 配置与模型，沿用服务端人格；不在手机上选择模型或覆盖人格。保存并检查后查看匹配配置与 profile。", 14);
        prompt = input("关怀提示词（可编辑，最多 4000 个 Unicode 字符；空白使用默认，保存后生效）", false);
        prompt.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        prompt.setSingleLine(false); prompt.setMinLines(5); prompt.setMaxLines(5);
        prompt.setGravity(android.view.Gravity.TOP | android.view.Gravity.START);
        prompt.setText(AdvicePrompt.DEFAULT);
        restorePrompt = button("恢复默认提示词（保存后生效）", v -> {
            prompt.setText(AdvicePrompt.DEFAULT);
            showReport("已恢复默认提示词草稿；保存后生效，当前已保存配置未改变。");
        });
        http = new CheckBox(this); http.setText("允许明文 HTTP（任何地址；密钥和健康数据会明文传输）"); layout.addView(http);
        save = button("保存配置", v -> background((Button)v, "正在保存…", () -> {
            saveDraft(); return "配置已保存：API Key 使用 Android Keystore AES/GCM 加密，本机不备份";
        }));
        check = button("保存并检查 AstrBot 官方 API（不调用 AI、不发送消息）", v -> background((Button)v, "正在检查…", () -> { saveDraft(); return EventClient.check(this); }));
        report = text("", 15); report.setTextIsSelectable(true);
        text("先调用 AstrBot AI 生成关怀文本，再调用 send 发送到目标 UMO。点击即提交真实天气、屏幕与健康摘要，不要求开启监控，也不检查息屏/连续使用时长。", 16);
        test = button("立即测试发送（先 AI 后 send，无时间条件）", v -> background((Button)v, "正在操作（先 AI 后 send）…", () -> {
            saveDraft(); EventClient.submit(this, true); return EventClient.status(this);
        }));
        retry = button("查看 / 重试最近任务（结果未知不重发）", v -> background((Button)v, "正在操作…", () -> {
            EventClient.retryLatest(this); return EventClient.status(this);
        }));
        text("HTTP 2xx 不代表业务成功：必须检查 AstrBot 官方业务 status。先 AI 成功再 send；只有 send 业务成功才标记 sent（不代表用户已读）。官方 API 无发送结果查询；结果未知不会重发。超过 24 小时的原始快照不可重提；立即测试会生成新快照 / 新任务。", 14);
        clear = button("删除本机事件历史", v -> new AlertDialog.Builder(this)
                .setTitle("删除本机事件历史？")
                .setMessage("包括未送达的固定健康快照；不能撤回服务器已收到的事件。删除后重新测试会生成新事件。")
                .setNegativeButton("取消", null).setPositiveButton("删除", (d, which) -> background(clear, "正在操作…", () -> {
                    EventClient.clearHistory(this); return EventClient.status(this);
                })).show());
        text("权限与监控", 20);
        button("通知权限", v -> {
            if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
                requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 1);
            else open(new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, getPackageName()));
        });
        button("使用情况访问权限", v -> open(new Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS).setData(Uri.parse("package:" + getPackageName()))));
        button("电池不限制 / 后台运行设置", v -> open(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)));
        button("应用详情 / HyperOS 自启动设置", v -> open(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).setData(Uri.parse("package:" + getPackageName()))));
        text("请在 HyperOS 应用设置中手动允许自启动、后台运行；在 LSPosed 启用本模块，并仅勾选小米天气与小米运动健康。这里不会替你修改权限或框架数据库。", 14);
        button("启用自动监控", v -> {
            getSharedPreferences("monitor", 0).edit().putBoolean("enabled", true).commit();
            try { startForegroundService(new Intent(this, MonitorService.class)); }
            catch (RuntimeException e) {
                getSharedPreferences("monitor", 0).edit().putBoolean("enabled", false).remove("boot").remove("last_elapsed").remove("last_wall").commit();
                showReport("启动监控失败：" + e.getClass().getSimpleName() + "；请检查通知和后台权限");
            }
            refresh();
        });
        button("停止监控（同时停止自动采集 / 重试）", v -> {
            getSharedPreferences("monitor", 0).edit().putBoolean("enabled", false).remove("boot").remove("last_elapsed").remove("last_wall").commit();
            stopService(new Intent(this, MonitorService.class)); refresh();
        });
        state = text("", 15);
        diagnostic = button("刷新本机诊断（不上传、不强行打开源应用）", v -> background((Button)v, "正在操作（本机诊断）…", () -> diagnostics()));
        diagnosticsReport = text("", 15); diagnosticsReport.setTextIsSelectable(true);
        try {
            SettingsStore.Config cfg = SettingsStore.load(this);
            url.setText(cfg.baseUrl); key.setText(cfg.key); umo.setText(cfg.umo); http.setChecked(cfg.allowHttp); prompt.setText(cfg.prompt);
        } catch (Exception e) { showReport("本机配置读取失败：" + e.getClass().getSimpleName() + "；请重新填写并保存"); }
        refresh();
    }
    private TextView text(String content, int size) {
        TextView view = new TextView(this); view.setText(content); view.setTextSize(size);
        view.setPadding(0, 10, 0, 10); layout.addView(view); return view;
    }
    private EditText input(String hint, boolean secret) {
        text(hint, 14);
        EditText view = new EditText(this); view.setSingleLine(true); view.setHint(hint);
        view.setInputType(secret ? InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD
                : InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        view.setSaveEnabled(false); view.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS);
        layout.addView(view); return view;
    }
    private Button button(String label, View.OnClickListener listener) {
        Button view = new Button(this); view.setText(label); view.setOnClickListener(listener); layout.addView(view); return view;
    }
    private interface Work { String run() throws Exception; }
    // Capture configuration on the UI thread; EditText is never accessed by worker code.
    private String draftUrl, draftKey, draftUmo, draftPrompt;
    private boolean draftHttp;
    private void saveDraft() throws Exception { SettingsStore.save(this, draftUrl, draftKey, draftUmo, draftHttp, draftPrompt); }
    private void background(Button source, String progress, Work work) {
        if (busy) return;
        draftUrl = url.getText().toString(); draftKey = key.getText().toString();
        draftUmo = umo.getText().toString(); draftHttp = http.isChecked(); draftPrompt = prompt.getText().toString();
        final CharSequence label = source.getText();
        setBusy(true); source.setText(progress);
        showReport(progress + "（GET 接口检查含多次请求，最长约 60 秒，每次最长 15 秒；AI 生成最长 180 秒，之后才发送）");
        worker.execute(() -> {
            String result;
            boolean succeeded;
            try { result = work.run(); succeeded = true; }
            catch (Exception e) { result = e instanceof IllegalArgumentException ? e.getMessage() : "操作失败：" + e.getClass().getSimpleName() + "；未确认发送成功"; succeeded = false; }
            final String display = result;
            final boolean diagnosticsReady = source == diagnostic && succeeded;
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) return;
                if (diagnosticsReady) {
                    diagnosticsReport.setText(redactInputs(display));
                    showReport("本机诊断已刷新（不上传）；完整内容在页面底部“刷新本机诊断”按钮下方。");
                } else showReport(display);
                draftKey = null; source.setText(label); setBusy(false); refresh();
            });
        });
    }
    private String redactInputs(String content) {
        if (content == null) return "操作失败：未返回结果";
        for (String value : new String[]{draftKey, draftUrl, draftUmo})
            if (value != null && !value.isEmpty()) content = content.replace(value, "[已隐藏]");
        return content;
    }
    private void showReport(String content) {
        report.setText(redactInputs(content));
        // Feedback stays in place: never request focus or scroll the reader to this label.
    }
    private void setBusy(boolean value) {
        busy = value;
        for (Button button : new Button[]{test, check, save, retry, diagnostic, clear, restorePrompt}) if (button != null) button.setEnabled(!value);
        url.setEnabled(!value); key.setEnabled(!value); umo.setEnabled(!value); http.setEnabled(!value); prompt.setEnabled(!value);
    }
    private void open(Intent intent) {
        try { startActivity(intent); }
        catch (RuntimeException e) { showReport("此系统没有对应设置入口，请从系统设置手动打开；" + e.getClass().getSimpleName()); }
    }
    private void refresh() {
        if (state != null) state.setText("监控设置：" + (getSharedPreferences("monitor", 0).getBoolean("enabled", false) ? "启用" : "停用")
                + "\n" + MonitorService.status(this) + "\n\n最近事件：\n" + EventClient.status(this));
    }
    private String diagnostics() {
        long now = System.currentTimeMillis();
        PowerManager pm = (PowerManager)getSystemService(POWER_SERVICE);
        NotificationManager nm = (NotificationManager)getSystemService(NOTIFICATION_SERVICE);
        StringBuilder out = new StringBuilder("本机诊断（不上传）\n");
        out.append("采集时刻：").append(EventClient.timestamp(now)).append('\n');
        out.append("屏幕 interactive：").append(pm != null && pm.isInteractive()).append('\n');
        out.append("通知允许：").append(nm != null && nm.areNotificationsEnabled()).append('\n');
        out.append("忽略电池优化：").append(pm != null && pm.isIgnoringBatteryOptimizations(getPackageName())).append('\n');
        out.append("LSPosed / 自启动需手动确认；缺失源时间不会伪造为当前时间。\n");
        for (String kind : new String[]{"weather", "screen", "health"}) out.append('\n').append(kind).append("：\n").append(EventClient.collect(this, kind, now).toString()).append('\n');
        return out.toString();
    }
    @Override protected void onResume() { super.onResume(); refresh(); }
    @Override protected void onDestroy() { worker.shutdown(); super.onDestroy(); }
}

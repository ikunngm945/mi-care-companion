package cn.local.micompanion;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.NotificationManager;
import android.app.usage.UsageStatsManager;
import android.content.SharedPreferences;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
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
import java.security.SecureRandom;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Native Chinese settings screen; no network operation runs on the main thread. */
public final class MainActivity extends Activity {
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Handler linkHandler = new Handler(Looper.getMainLooper());
    private final Runnable linkPoll = new Runnable() {
        @Override public void run() { updateLinkStatus(); linkHandler.postDelayed(this, 1000); }
    };
    private EditText url, key, umo, prompt, port;
    private CheckBox http, preferShizuku;
    private boolean tokenExpiryShown;
    private Button test, check, save, retry, diagnostic, clear, restorePrompt;
    private TextView state, report, diagnosticsReport, tokenView, linkStatus, privilege;
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

        text("数据连接（模块 → 本机）", 20);
        text("天气模块和健康模块把只读快照推送到本机回环 WebSocket。先安装两个模块 APK，在 LSPosed 中分别启用，并只勾选对应应用，然后把下面的端口和 Token 填到两个模块的设置页。", 14);
        port = input("WebSocket 端口（默认 17890，三个 APK 必须一致）", false);
        port.setInputType(InputType.TYPE_CLASS_NUMBER);
        text("当前 Token（16 位；复制后填入天气设置页和健康“关于”设置页）", 14);
        tokenView = new TextView(this);
        tokenView.setTextSize(17); tokenView.setTypeface(Typeface.MONOSPACE);
        tokenView.setTextIsSelectable(true); tokenView.setPadding(0, 8, 0, 8);
        layout.addView(tokenView);
        button("复制 Token", v -> {
            ClipboardManager clipboard = (ClipboardManager)getSystemService(CLIPBOARD_SERVICE);
            if (clipboard != null) clipboard.setPrimaryClip(ClipData.newPlainText("Token", tokenView.getText()));
            updateLinkStatus();
        });
        button("重新生成 Token（两个模块都要重填）", v -> new AlertDialog.Builder(this)
                .setTitle("重新生成 Token？")
                .setMessage("现有连接会立即断开。天气和健康两个模块必须重新填入新 Token，否则会显示“token 已过期”。")
                .setNegativeButton("取消", null)
                .setPositiveButton("生成", (d, which) -> regenerateToken()).show());
        button("保存连接设置并重载服务", v -> {
            try {
                int value = Integer.parseInt(port.getText().toString().trim());
                SettingsStore.saveLink(this, value, tokenView.getText().toString());
                reloadService();
                showReport("连接设置已保存：端口 " + value + "；服务正在重新监听");
            } catch (Exception e) {
                showReport(e instanceof IllegalArgumentException ? e.getMessage() : "保存连接设置失败：" + e.getClass().getSimpleName());
            }
            updateLinkStatus();
        });
        button("启动后台服务（开始接收模块数据）", v -> {
            startCompanionService();
            refresh();
        });
        button("停止后台服务（断开数据连接并停止监控）", v -> new AlertDialog.Builder(this)
                .setTitle("停止后台服务？")
                .setMessage("停止后不再接收模块数据，自动监控也停止；重新点“启动后台服务”可恢复。")
                .setNegativeButton("取消", null)
                .setPositiveButton("停止", (d, which) -> {
                    getSharedPreferences("monitor", 0).edit().putBoolean("service_enabled", false)
                            .putBoolean("enabled", false).remove("boot").remove("last_elapsed").remove("last_wall").commit();
                    stopService(new Intent(this, MonitorService.class));
                    refresh();
                }).show());
        text("不再定时同步天气/健康数据或定时拉起应用。健康模块只在点“同步数据库与请求”时传输数据库；打开/拉起运动健康不会自动复制数据库。天气模块仍按原方式在天气应用运行时推送快照。普通掉线只显示未连接；手环请求失败且蓝牙已开启时提示“token 已过期”，模块 WebSocket 鉴权失败显示“模块鉴权失败”。", 14);
        preferShizuku = new CheckBox(this);
        preferShizuku.setText("优先使用 Shizuku 拉起（备用通道）");
        preferShizuku.setChecked(getSharedPreferences("monitor", 0).getBoolean("prefer_shizuku", false));
        preferShizuku.setOnCheckedChangeListener((v, checked) ->
                getSharedPreferences("monitor", 0).edit().putBoolean("prefer_shizuku", checked).commit());
        layout.addView(preferShizuku);
        button("检查 Root 状态", v -> {
            showReport("正在检查 Root 授权…");
            new Thread(() -> {
                String message = TargetRecovery.requestRoot(MainActivity.this);
                runOnUiThread(() -> { showReport(message); refresh(); });
            }, "mi-companion-root-request").start();
        });
        button("授权 / 检查 Shizuku", v -> {
            showReport(ShizukuShell.request(this));
            linkHandler.postDelayed(this::refresh, 1200);
        });
        button("立即拉起天气和健康", v -> {
            TargetRecovery.launchNow(this, "weather");
            TargetRecovery.launchNow(this, "health");
            showReport("已发送拉起命令；使用 Root 或 Shizuku，天气/健康会短暂打开后返回桌面");
        });
        button("同步数据库与请求", v -> background((Button)v, "正在请求健康模块同步数据库…", () -> {
            MonitorService.requestDatabaseSync(this);
            return "已请求健康模块同步数据库；传输完成后 MCP 直接读取本机副本";
        }));
        button("手动同步手环（不打开运动健康）", v -> {
            if (!ensureBluetoothPermission()) return;
            background((Button)v, "正在直连手环…", () -> {
                org.json.JSONObject sync = MonitorService.syncBand(this);
                return sync.optBoolean("fallback", false) ? sync.optString("text", "已切换运动健康同步")
                        : "手环直连完成\n" + BandData.describe(sync);
            });
        });
        text("手环每 10 分钟自动直连同步一次，不打开/不拉起运动健康；也可以点上面的按钮手动同步。若运动健康已占用蓝牙通道，自动同步会切换为运动健康同步。", 14);
        text("手环请求失败且蓝牙已开启时提示“token 已过期”（不是运动健康模块的问题）；下一次自动同步会重新读取手环密钥并重试。", 14);
        privilege = text("", 14);
        linkStatus = text("", 15); linkStatus.setTextIsSelectable(true);

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
            saveDraft(); return "配置已保存：API Key 与 Token 使用 Android Keystore AES/GCM 加密，本机不备份";
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
        text("请在 HyperOS 应用设置中手动允许自启动、后台运行；LSPosed 中启用“小米关怀助手·天气模块”和“小米关怀助手·健康模块”，分别只勾选小米天气与小米运动健康。这里不会替你修改权限或框架数据库。", 14);
        button("启用自动监控", v -> {
            getSharedPreferences("monitor", 0).edit().putBoolean("enabled", true).putBoolean("service_enabled", true).commit();
            startCompanionService();
            refresh();
        });
        button("停止监控（数据连接保持）", v -> {
            getSharedPreferences("monitor", 0).edit().putBoolean("enabled", false).remove("boot").remove("last_elapsed").remove("last_wall").commit();
            MonitorService.refresh(this);
            refresh();
        });
        state = text("", 15);
        diagnostic = button("刷新本机诊断（不上传、不强行打开源应用）", v -> background((Button)v, "正在操作（本机诊断）…", () -> diagnostics()));
        diagnosticsReport = text("", 15); diagnosticsReport.setTextIsSelectable(true);
        try {
            SettingsStore.Config cfg = SettingsStore.ensureLink(this);
            url.setText(cfg.baseUrl); key.setText(cfg.key); umo.setText(cfg.umo); http.setChecked(cfg.allowHttp); prompt.setText(cfg.prompt);
            port.setText(String.valueOf(cfg.port)); tokenView.setText(cfg.token);
        } catch (Exception e) { showReport("本机配置读取失败：" + e.getClass().getSimpleName() + "；请重新填写并保存"); }
        ShizukuShell.listen(() -> runOnUiThread(this::refresh));
        refresh();
        linkHandler.postDelayed(this::refresh, 1500);
    }
    private boolean ensureBluetoothPermission() {
        if (Build.VERSION.SDK_INT < 31) return true;
        if (checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED) return true;
        requestPermissions(new String[]{Manifest.permission.BLUETOOTH_CONNECT}, 2);
        showReport("请允许“附近的设备”权限后，再点一次“直接从手环同步”。");
        return false;
    }
    private void ensureServiceOnLaunch() {
        android.content.SharedPreferences prefs = getSharedPreferences("monitor", 0);
        if (!prefs.contains("service_enabled")) prefs.edit().putBoolean("service_enabled", true).commit();
        if (prefs.getBoolean("service_enabled", true)) startCompanionService();
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
        if (port != null) port.setEnabled(!value);
    }
    private void open(Intent intent) {
        try { startActivity(intent); }
        catch (RuntimeException e) { showReport("此系统没有对应设置入口，请从系统设置手动打开；" + e.getClass().getSimpleName()); }
    }
    private void startCompanionService() {
        getSharedPreferences("monitor", 0).edit().putBoolean("service_enabled", true).commit();
        try { startForegroundService(new Intent(this, MonitorService.class)); }
        catch (RuntimeException e) { showReport("启动后台服务失败：" + e.getClass().getSimpleName() + "；请检查通知和后台权限"); }
    }
    private void reloadService() {
        getSharedPreferences("monitor", 0).edit().putBoolean("service_enabled", true).commit();
        try {
            if (MonitorService.current() == null) startForegroundService(new Intent(this, MonitorService.class));
            else startForegroundService(new Intent(this, MonitorService.class).setAction(MonitorService.ACTION_RELOAD_LINK));
        } catch (RuntimeException e) { showReport("重载服务失败：" + e.getClass().getSimpleName()); }
    }
    private void regenerateToken() {
        try {
            int value = Integer.parseInt(port.getText().toString().trim());
            String token = LinkProtocol.newToken(new SecureRandom());
            SettingsStore.saveLink(this, value, token);
            tokenView.setText(token);
            reloadService();
            showReport("Token 已重新生成；请在天气设置页和健康“关于”设置页填入新 Token");
        } catch (Exception e) {
            showReport(e instanceof IllegalArgumentException ? e.getMessage() : "生成失败：" + e.getClass().getSimpleName());
        }
    }
    private void updateLinkStatus() {
        if (linkStatus == null) return;
        StringBuilder out = new StringBuilder("数据连接状态\n");
        LinkServer server = MonitorService.current();
        String storedError = getSharedPreferences("monitor", 0).getString("link_error", "");
        if (server == null) out.append(MonitorService.serviceRunning() ? "后台服务运行中，但数据连接未启动\n" : "后台服务未运行\n");
        else if (!server.isRunning()) out.append("服务启动失败：").append(server.error()).append('\n');
        else out.append("监听 127.0.0.1:").append(server.port()).append('\n');
        if (!storedError.isEmpty()) out.append("最近服务错误：").append(storedError).append('\n');
        String mcpError = getSharedPreferences("monitor", 0).getString("mcp_error", "");
        out.append("MCP：http://127.0.0.1:").append(McpServer.PORT).append("/mcp（Token 同上）\n");
        out.append("局域网：http://<手机局域网IP>:").append(McpServer.PORT).append("/mcp\n");
        if (!mcpError.isEmpty()) out.append("MCP 错误：").append(mcpError).append('\n');
        long now = System.nanoTime() / 1_000_000L; // LinkServer uses the same monotonic clock
        boolean tokenExpired = false;
        for (String kind : new String[]{"weather", "health"}) {
            String name = "weather".equals(kind) ? "天气模块" : "健康模块";
            LinkServer.Status status = server == null ? null : server.status(kind);
            if (status != null && status.authFailures > 0) {
                out.append(name).append("：模块鉴权失败，请重新填写 Token\n");
            } else if (status == null || !status.connected) {
                out.append(name).append("：未连接");
                if (status != null && !status.lastError.isEmpty()) out.append("（").append(status.lastError).append("）");
                if (status != null && status.authFailures > 0) out.append(" · 鉴权失败 ").append(status.authFailures).append(" 次");
                out.append('\n');
            } else {
                long age = Math.max(0, now - status.lastPongAt) / 1000;
                out.append(name).append("：已连接 · 心跳 ").append(age).append(" 秒前");
                if (status.rttMs >= 0) out.append(" · RTT ").append(status.rttMs).append(" ms");
                out.append('\n');
            }
        }
        SharedPreferences bandPrefs = getSharedPreferences("monitor", 0);
        String bandError = bandPrefs.getString("band_auto_error", "");
        boolean bandExpired = bandPrefs.getBoolean("band_token_expired", false);
        long bandAt = bandPrefs.getLong("band_auto_at", 0);
        out.append("手环自动同步：");
        if (bandExpired) {
            tokenExpired = true;
            out.append("token 已过期（蓝牙已开启，但请求手环失败）\n");
        } else if (!bandError.isEmpty()) {
            out.append("失败（").append(bandError).append("）\n");
        } else if (bandAt > 0) {
            long age = Math.max(0, System.currentTimeMillis() - bandAt) / 1000;
            out.append("上次成功 ").append(age / 60).append(" 分钟前");
            String source = bandPrefs.getString("band_auto_source", "");
            if ("health".equals(source)) out.append(" · 运动健康回退");
            out.append('\n');
        } else {
            out.append("等待首次 10 分钟同步\n");
        }
        linkStatus.setText(out.toString());
        if (tokenExpired && !tokenExpiryShown)
            android.widget.Toast.makeText(this, "token 已过期", android.widget.Toast.LENGTH_LONG).show();
        tokenExpiryShown = tokenExpired;
    }
    private void refresh() {
        if (state != null) state.setText("监控设置：" + (getSharedPreferences("monitor", 0).getBoolean("enabled", false) ? "启用" : "停用")
                + "\n" + MonitorService.status(this) + "\n\n最近事件：\n" + EventClient.status(this));
        if (tokenView != null && tokenView.getText().length() == 0) try {
            String token = SettingsStore.load(this).token;
            if (!token.isEmpty()) tokenView.setText(token);
        } catch (Exception ignored) { }
        if (privilege != null) privilege.setText(TargetRecovery.privilegeText(this));
        updateLinkStatus();
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
        LinkServer server = MonitorService.current();
        out.append("数据连接：").append(server == null ? "服务未运行" : server.isRunning() ? "监听 127.0.0.1:" + server.port() : server.error()).append('\n');
        out.append("LSPosed / 自启动需手动确认；缺失源时间不会伪造为当前时间。\n");
        for (String kind : new String[]{"weather", "screen", "health"}) out.append('\n').append(kind).append("：\n").append(EventClient.collect(this, kind, now).toString()).append('\n');
        return out.toString();
    }
    @Override protected void onResume() {
        super.onResume();
        ensureServiceOnLaunch();
        refresh(); linkHandler.removeCallbacks(linkPoll); linkHandler.post(linkPoll);
    }
    @Override protected void onPause() { linkHandler.removeCallbacks(linkPoll); super.onPause(); }
    @Override protected void onDestroy() {
        ShizukuShell.unlisten();
        linkHandler.removeCallbacks(linkPoll);
        worker.shutdown();
        super.onDestroy();
    }
}

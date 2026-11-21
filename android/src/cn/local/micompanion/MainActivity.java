package cn.local.micompanion;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.NotificationManager;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Insets;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.graphics.drawable.StateListDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.provider.Settings;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import java.security.SecureRandom;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.json.JSONArray;
import org.json.JSONObject;

/** Native dashboard and settings; health queries and device I/O stay off the UI thread. */
public final class MainActivity extends Activity {
    private static final int HOME = 0, DEVICE = 1, SETTINGS = 2;
    private static final int PAGE = 0xfff3f7f5, WHITE = 0xffffffff, INK = 0xff182b26, MUTED = 0xff506660;
    private static final int ACCENT = 0xff087669, MINT = 0xffe0f2ea, LINE = 0xffdce8e3;
    private static final String UNREAD = "未读取", NO_DATA = "暂无数据";
    private final ScrollView[] pages = new ScrollView[3];
    private final Button[] tabs = new Button[3];
    private int activePage = -1;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Handler linkHandler = new Handler(Looper.getMainLooper());
    private final Runnable linkPoll = new Runnable() {
        @Override public void run() { updateLinkStatus(); linkHandler.postDelayed(this, 1000); }
    };
    private EditText url, key, umo, prompt, port;
    private CheckBox http;
    private Switch monitorSwitch, astrbotSwitch, mcpSwitch;
    private Button test, check, save, retry, diagnostic, clear, restorePrompt, serviceAction, showToken;
    private Button overviewLinks, overviewCopy, overviewMonitor;
    private TextView serviceState, healthLink, weatherLink, linkStatus, copyStatus, bandStatus, tokenView;
    private TextView monitorStatus, privilege, astrbotStatus, taskStatus, mcpStatus, mcpError, diagnosticsReport;
    private TextView linkReport, syncReport, monitorReport, apiReport, taskReport, mcpReport;
    private SettingsDisclosure diagnosticsDetails;
    private String linkToken = "";
    private boolean tokenVisible, tokenExpiryShown, updatingSettings, busy;
    private TextView homeDate, healthSource, homeSync, deviceSync, deviceFeedback, syncFeedback, batteryValue, storageValue;
    private final TextView[] metricValues = new TextView[4];
    private ProgressBar batteryProgress, storageProgress;
    private Button readDevice, syncDevice;
    private boolean healthLoading, healthRefreshPending;
    private long latestDirectSync;

    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        // Do not expose API credentials or health diagnostics in task previews/screenshots.
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        LinearLayout root = column();
        root.setBackgroundColor(PAGE);
        FrameLayout content = new FrameLayout(this);
        root.addView(content, new LinearLayout.LayoutParams(-1, 0, 1));
        for (int i = 0; i < pages.length; i++) {
            ScrollView page = new ScrollView(this);
            page.setFillViewport(true);
            page.setSmoothScrollingEnabled(false);
            page.setVisibility(View.GONE);
            LinearLayout body = column();
            body.setPadding(dp(20), dp(18), dp(20), dp(24));
            body.setFocusableInTouchMode(true);
            page.addView(body);
            pages[i] = page;
            content.addView(page, new FrameLayout.LayoutParams(-1, -1));
        }
        LinearLayout nav = new LinearLayout(this);
        nav.setBackgroundColor(WHITE);
        nav.setPadding(dp(10), dp(4), dp(10), dp(4));
        View divider = new View(this);
        divider.setBackgroundColor(LINE);
        root.addView(divider, new LinearLayout.LayoutParams(-1, dp(1)));
        root.addView(nav, new LinearLayout.LayoutParams(-1, -2));
        String[] labels = {"首页", "设备", "设置"};
        String[] icons = {"ic_tab_home", "ic_tab_device", "ic_tab_settings"};
        for (int i = 0; i < labels.length; i++) {
            final int index = i;
            Button tab = new Button(this, null, android.R.attr.borderlessButtonStyle);
            tab.setText(labels[i]);
            tab.setTextSize(13);
            tab.setMinWidth(0);
            tab.setMinimumWidth(0);
            tab.setMinHeight(dp(56));
            tab.setGravity(Gravity.CENTER);
            tab.setPadding(dp(4), dp(6), dp(4), dp(4));
            int iconId = getResources().getIdentifier(icons[i], "drawable", getPackageName());
            if (iconId != 0) tab.setCompoundDrawablesWithIntrinsicBounds(0, iconId, 0, 0);
            tab.setCompoundDrawablePadding(dp(2));
            tab.setOnClickListener(v -> showPage(index));
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, -1, 1);
            params.setMargins(dp(3), 0, dp(3), 0);
            nav.addView(tab, params);
            tabs[i] = tab;
        }
        setContentView(root);
        setupInsets(root, nav, divider);
        buildHome((LinearLayout) pages[HOME].getChildAt(0));
        buildDevice((LinearLayout) pages[DEVICE].getChildAt(0));
        buildSettings((LinearLayout) pages[SETTINGS].getChildAt(0));
        int page = saved == null ? HOME : saved.getInt("page", HOME);
        showPage(page >= HOME && page <= SETTINGS ? page : HOME);
        ShizukuShell.listen(() -> runOnUiThread(this::refresh));
        refresh();
        linkHandler.postDelayed(this::refresh, 1500);
    }

    private void buildSettings(LinearLayout body) {
        LinearLayout hero = column();
        hero.setBackground(surface(0xff184b43, 24));
        hero.setPadding(dp(16), dp(14), dp(16), dp(16));
        body.addView(hero, new LinearLayout.LayoutParams(-1, -2));
        label(hero, "小米关怀助手", 13, MINT, true);
        label(hero, "设置", 30, WHITE, true).setAccessibilityHeading(true);
        label(hero, "管理数据连接、关怀消息和后台监控", 14, MINT, false);
        serviceState = label(hero, "后台服务未运行", 14, WHITE, true);
        LinearLayout overview = new LinearLayout(this);
        overview.setPadding(0, dp(6), 0, 0);
        hero.addView(overview, new LinearLayout.LayoutParams(-1, -2));

        String[] names = {"数据连接", "健康同步", "监控与权限", "AstrBot", "消息任务", "MCP", "本机诊断"};
        String[] tags = {"连", "同步", "权限", "A", "消息", "M", "诊断"};
        String[] summaries = {"管理天气和运动健康模块", "查看状态并刷新本机副本", "后台服务、自动监控和系统授权",
                "配置关怀消息服务和接收目标", "测试发送、重试与本机历史", "管理 MCP 客户端的数据访问", "仅检查本机，不上传数据"};
        HorizontalScrollView shortcuts = new HorizontalScrollView(this);
        shortcuts.setHorizontalScrollBarEnabled(false);
        body.addView(shortcuts, new LinearLayout.LayoutParams(-1, -2));
        LinearLayout navigation = new LinearLayout(this);
        shortcuts.addView(navigation);
        LinearLayout[] sections = new LinearLayout[names.length];
        for (int i = 0; i < names.length; i++) {
            LinearLayout section = settingsSection(body, names[i], tags[i], summaries[i]);
            sections[i] = section;
            Button shortcut = addButton(navigation, names[i], false, v -> jumpToSettings(section));
            shortcut.setMinWidth(0);
            shortcut.setMinimumWidth(0);
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-2, -2);
            params.setMargins(0, dp(8), dp(6), 0);
            shortcut.setLayoutParams(params);
        }
        overviewLinks = settingsOverview(overview, sections[0], false);
        overviewCopy = settingsOverview(overview, sections[1], true);
        overviewMonitor = settingsOverview(overview, sections[2], true);
        buildConnectionSettings(sections[0]);
        buildSyncSettings(sections[1]);
        buildMonitorSettings(sections[2]);
        buildAstrbotSettings(sections[3]);
        buildTaskSettings(sections[4]);
        buildMcpSettings(sections[5]);
        buildDiagnosticsSettings(sections[6]);
        try {
            SettingsStore.Config cfg = SettingsStore.ensureLink(this);
            url.setText(cfg.baseUrl); key.setText(cfg.key); umo.setText(cfg.umo);
            http.setChecked(cfg.allowHttp); prompt.setText(cfg.prompt);
            port.setText(String.valueOf(cfg.port)); linkToken = cfg.token;
            renderToken();
        } catch (Exception e) {
            showReport(linkReport, "本机配置读取失败：" + e.getClass().getSimpleName() + "；请重新填写并保存");
            showReport(apiReport, "本机配置未能读取，请重新填写并保存。");
        }
    }

    private void buildConnectionSettings(LinearLayout section) {
        healthLink = label(section, "健康模块：未连接", 15, INK, true);
        weatherLink = label(section, "天气模块：未连接", 15, INK, true);
        SettingsDisclosure details = new SettingsDisclosure(section, "端口与 Token");
        LinearLayout advanced = details.body;
        linkStatus = settingsText(advanced, "");
        linkStatus.setTextIsSelectable(true);
        port = settingsInput(advanced, "WebSocket 端口", "默认 17890", InputType.TYPE_CLASS_NUMBER);
        label(advanced, "当前模块 Token", 14, INK, true);
        tokenView = label(advanced, "未设置", 16, INK, false);
        tokenView.setTypeface(Typeface.MONOSPACE);
        tokenView.setSaveEnabled(false);
        showToken = addButton(advanced, "显示 Token", false, v -> {
            tokenVisible = !tokenVisible;
            renderToken();
        });
        details.onCollapse = () -> { tokenVisible = false; renderToken(); };
        settingsText(advanced, "助手与两个模块的端口和 Token 必须一致。天气模块在天气设置页填写，健康模块在运动健康的“关于”设置页填写。更换后两个模块都要重填。");
        addButton(advanced, "保存连接设置并重载服务", true, v -> {
            try {
                int value = Integer.parseInt(port.getText().toString().trim());
                SettingsStore.saveLink(this, value, linkToken);
                showReport(linkReport, reloadService() ? "连接设置已保存：端口 " + value + "；服务正在重新监听"
                        : "连接设置已保存，但服务重载请求失败，请检查后台权限。");
            } catch (Exception e) {
                showReport(linkReport, e instanceof IllegalArgumentException ? e.getMessage() : "保存连接设置失败：" + e.getClass().getSimpleName());
            }
            updateLinkStatus();
        });
        addButton(advanced, "复制 Token", false, v -> {
            if (linkToken.isEmpty()) return;
            ClipboardManager clipboard = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
            if (clipboard != null) {
                ClipData clip = ClipData.newPlainText("Token", linkToken);
                android.os.PersistableBundle metadata = new android.os.PersistableBundle();
                metadata.putBoolean("android.content.extra.IS_SENSITIVE", true);
                clip.getDescription().setExtras(metadata);
                clipboard.setPrimaryClip(clip);
                showReport(linkReport, "Token 已复制。");
            }
        });
        addButton(advanced, "重新生成 Token", false, v -> new AlertDialog.Builder(this)
                .setTitle("重新生成 Token？")
                .setMessage("现有连接会立即断开。天气和健康两个模块必须重新填入新 Token，否则会显示“token 已过期”。")
                .setNegativeButton("取消", null)
                .setPositiveButton("生成", (d, which) -> regenerateToken()).show());
        linkReport = settingsReport(section);
    }

    private void buildSyncSettings(LinearLayout section) {
        copyStatus = label(section, "本机健康副本：尚未同步", 15, INK, true);
        bandStatus = settingsText(section, "手环自动同步：等待首次同步");
        addButton(section, "同步数据库与请求", true, v -> requestHealthCopy((Button) v));
        syncReport = settingsReport(section);
        LinearLayout details = new SettingsDisclosure(section, "了解数据同步").body;
        settingsText(details, "手动同步刷新本机健康副本，不会自动把明细编辑写回。运动健康同步完成或重新连接后会自动刷新副本。明细里的保存和恢复仍由你主动操作。");
        settingsText(details, "手环直连取得的日常原始文件会在健康模块连接后自动补入运动健康。缺失记录会重试；来源账号或设备不匹配时不会回写。天气模块仍按原方式推送快照。");
    }

    private void buildMonitorSettings(LinearLayout section) {
        monitorSwitch = settingsSwitch(section, "自动监控", "关闭监控不影响数据连接。");
        monitorSwitch.setOnCheckedChangeListener((v, checked) -> {
            if (!updatingSettings) setMonitoring(checked);
        });
        label(section, "后台服务", 15, INK, true);
        settingsText(section, "停止服务会断开模块数据连接，同时停止自动监控。");
        serviceAction = addButton(section, "启动后台服务", false, v -> {
            if (MonitorService.serviceRunning()) confirmStopService();
            else { startCompanionService(); refresh(); }
        });
        monitorStatus = settingsText(section, "");
        monitorReport = settingsReport(section);
        LinearLayout permissions = new SettingsDisclosure(section, "系统权限").body;
        addButton(permissions, "通知权限", false, v -> {
            if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
                requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 1);
            else open(new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, getPackageName()));
        });
        addButton(permissions, "使用情况访问权限", false, v -> open(new Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS).setData(Uri.parse("package:" + getPackageName()))));
        addButton(permissions, "电池不限制 / 后台运行设置", false, v -> open(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)));
        addButton(permissions, "应用详情 / HyperOS 自启动设置", false, v -> open(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).setData(Uri.parse("package:" + getPackageName()))));
        settingsText(permissions, "请在 HyperOS 应用设置中手动允许自启动和后台运行。这里不会替你修改权限或框架数据库。");

        LinearLayout privilegeDetails = new SettingsDisclosure(section, "Root / Shizuku 与模块拉起").body;
        privilege = settingsText(privilegeDetails, "");
        CheckBox preferShizuku = settingsCheckBox(privilegeDetails, "优先使用 Shizuku 拉起（备用通道）");
        preferShizuku.setChecked(getSharedPreferences("monitor", 0).getBoolean("prefer_shizuku", false));
        preferShizuku.setOnCheckedChangeListener((v, checked) ->
                getSharedPreferences("monitor", 0).edit().putBoolean("prefer_shizuku", checked).commit());
        addButton(privilegeDetails, "检查 Root 状态", false, v -> {
            showReport(monitorReport, "正在检查 Root 授权…");
            new Thread(() -> {
                String message = TargetRecovery.requestRoot(MainActivity.this);
                runOnUiThread(() -> { showReport(monitorReport, message); refresh(); });
            }, "mi-companion-root-request").start();
        });
        addButton(privilegeDetails, "授权 / 检查 Shizuku", false, v -> {
            showReport(monitorReport, ShizukuShell.request(this));
            linkHandler.postDelayed(this::refresh, 1200);
        });
        addButton(privilegeDetails, "立即拉起天气和健康", false, v -> {
            TargetRecovery.launchNow(this, "weather");
            TargetRecovery.launchNow(this, "health");
            showReport(monitorReport, "已发送拉起命令；使用 Root 或 Shizuku，天气/健康会短暂打开后返回桌面");
        });
        settingsText(privilegeDetails, "在 LSPosed 分别启用“小米关怀助手·天气模块”和“小米关怀助手·健康模块”，并各自只勾选对应应用。");
    }

    private void buildAstrbotSettings(LinearLayout section) {
        astrbotStatus = label(section, "", 14, ACCENT, true);
        astrbotSwitch = settingsSwitch(section, "启用 AstrBot", "关闭后暂停自动关怀、API 检查、测试发送和重试，保留配置。");
        TextView switchReport = settingsReport(section);
        astrbotSwitch.setOnCheckedChangeListener((v, checked) -> {
            if (updatingSettings) return;
            boolean saved = IntegrationSettings.setAstrbotEnabled(this, checked);
            showReport(switchReport, saved ? "" : "无法保存 AstrBot 开关，请重试。");
            updateLinkStatus();
        });
        url = settingsInput(section, "官方 API 地址", "例如 https://example.com/api/v1", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        key = settingsInput(section, "官方 API Key", "输入官方 API Key", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        settingsText(section, "需要 chat、im、config 权限。不是模型密钥；在本机加密，不备份。");
        umo = settingsInput(section, "目标 UMO", "在 AstrBot 中输入 /sid 获取", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);

        LinearLayout advanced = new SettingsDisclosure(section, "提示词、自动路由与传输选项").body;
        prompt = settingsInput(advanced, "关怀提示词", "留空使用默认提示词（最多 4000 个 Unicode 字符）",
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        prompt.setSingleLine(false); prompt.setMinLines(5); prompt.setMaxLines(5);
        prompt.setGravity(Gravity.TOP | Gravity.START);
        prompt.setText(AdvicePrompt.DEFAULT);
        restorePrompt = addButton(advanced, "恢复默认提示词（保存后生效）", false, v -> {
            prompt.setText(AdvicePrompt.DEFAULT);
            showReport(apiReport, "已恢复默认提示词草稿；保存后生效，当前已保存配置未改变。");
        });
        settingsText(advanced, "自动路由按目标 UMO 匹配服务端配置、模型和人格。不在手机上选择模型或覆盖人格；保存并检查后可查看匹配配置。");
        http = settingsCheckBox(advanced, "允许明文 HTTP");
        settingsText(advanced, "明文 HTTP 会暴露密钥和健康数据，仅在明确了解风险时启用。");
        save = addButton(section, "保存配置", true, v -> background((Button) v, apiReport, "正在保存…", () -> {
            saveDraft(); return "配置已保存：API Key 与 Token 使用 Android Keystore AES/GCM 加密，本机不备份";
        }));
        check = addButton(section, "保存并检查 API", false, v -> background((Button) v, apiReport, "正在检查…", () -> {
            saveDraft(); return EventClient.check(this);
        }));
        settingsText(section, "检查不会调用 AI 或发送消息。");
        apiReport = settingsReport(section);
    }

    private void buildTaskSettings(LinearLayout section) {
        label(section, "最近任务", 15, INK, true);
        taskStatus = settingsText(section, "");
        taskStatus.setTextIsSelectable(true);
        TextView warning = label(section, "测试发送会提交真实天气、屏幕和健康摘要，先生成关怀文本，再尝试发送。不要求开启监控或符合时长条件；结果未知的任务不会重发。", 14, 0xff70451b, false);
        warning.setBackground(surface(0xfffff4e4, 12));
        warning.setPadding(dp(10), dp(6), dp(10), dp(6));
        test = addButton(section, "立即测试发送", true, v -> new AlertDialog.Builder(this)
                .setTitle("测试发送？")
                .setMessage("将提交真实天气、屏幕和健康摘要给已配置的 AstrBot，生成关怀文本后尝试发送到目标 UMO。")
                .setNegativeButton("取消", null).setPositiveButton("测试发送", (d, which) ->
                        background(test, taskReport, "正在操作（先 AI 后 send）…", () -> {
                            saveDraft(); EventClient.submit(this, true); return EventClient.status(this);
                        })).show());
        retry = addButton(section, "查看 / 重试最近任务", false, v -> background((Button) v, taskReport, "正在操作…", () -> {
            EventClient.retryLatest(this); return EventClient.status(this);
        }));
        taskReport = settingsReport(section);
        LinearLayout history = new SettingsDisclosure(section, "本机事件历史").body;
        settingsText(history, "最近任务状态保存在本机，关闭 AstrBot 后仍可查看或清除。删除本机历史不能撤回已发送的消息。");
        settingsText(history, "HTTP 2xx 不代表业务成功；仅在发送接口返回业务成功时标记已发送（不代表已读）。官方 API 无法查询发送结果；结果未知不会重发。超过 24 小时的原始快照不可重提，测试发送会生成新快照和新任务。");
        clear = addButton(history, "删除本机事件历史", false, v -> new AlertDialog.Builder(this)
                .setTitle("删除本机事件历史？")
                .setMessage("包括未送达的固定健康快照；不能撤回服务器已收到的事件。删除后重新测试会生成新事件。")
                .setNegativeButton("取消", null).setPositiveButton("删除", (d, which) -> background(clear, taskReport, "正在清除…", () -> {
                    EventClient.clearHistory(this); return EventClient.status(this);
                })).show());
    }

    private void buildMcpSettings(LinearLayout section) {
        mcpStatus = label(section, "", 14, ACCENT, true);
        mcpSwitch = settingsSwitch(section, "启用 MCP 服务", "关闭后，MCP 客户端不能查询数据，不影响模块同步或 AstrBot。");
        mcpSwitch.setOnCheckedChangeListener((v, checked) -> {
            if (updatingSettings) return;
            boolean saved = IntegrationSettings.setMcpEnabled(this, checked);
            showReport(mcpReport, saved ? "" : "无法保存 MCP 开关，请重试。");
            updateLinkStatus();
        });
        mcpReport = settingsReport(section);
        mcpError = settingsText(section, "");
        LinearLayout details = new SettingsDisclosure(section, "连接地址与认证").body;
        settingsText(details, "本机：http://127.0.0.1:" + McpServer.PORT + "/mcp").setTextIsSelectable(true);
        settingsText(details, "局域网：http://<手机局域网IP>:" + McpServer.PORT + "/mcp");
        settingsText(details, "MCP 已启用且后台服务正在运行时才可连接。认证 Token 与模块连接相同。");
    }

    private void buildDiagnosticsSettings(LinearLayout section) {
        diagnostic = addButton(section, "刷新本机诊断", false, v -> background((Button) v, diagnosticsReport, "正在采集本机诊断…", this::diagnostics));
        diagnosticsDetails = new SettingsDisclosure(section, "查看诊断详情");
        diagnosticsReport = settingsText(diagnosticsDetails.body, "尚未刷新");
        diagnosticsReport.setTextIsSelectable(true);
        settingsText(diagnosticsDetails.body, "这里不会上传数据或强行打开源应用。");
    }

    private void setMonitoring(boolean enabled) {
        SharedPreferences.Editor editor = getSharedPreferences("monitor", 0).edit().putBoolean("enabled", enabled);
        if (enabled) editor.putBoolean("service_enabled", true);
        else editor.remove("boot").remove("last_elapsed").remove("last_wall");
        editor.commit();
        if (enabled) startCompanionService();
        else MonitorService.refresh(this);
        refresh();
    }

    private void confirmStopService() {
        new AlertDialog.Builder(this).setTitle("停止后台服务？")
                .setMessage("停止后不再接收模块数据，自动监控也停止；可再次点“启动后台服务”。")
                .setNegativeButton("取消", null)
                .setPositiveButton("停止", (d, which) -> {
                    getSharedPreferences("monitor", 0).edit().putBoolean("service_enabled", false)
                            .putBoolean("enabled", false).remove("boot").remove("last_elapsed").remove("last_wall").commit();
                    stopService(new Intent(this, MonitorService.class));
                    refresh();
                }).show();
    }

    private void renderToken() {
        tokenView.setTextIsSelectable(tokenVisible && !linkToken.isEmpty());
        tokenView.setText(linkToken.isEmpty() ? "未设置" : tokenVisible ? linkToken : "•••• •••• •••• ••••");
        showToken.setText(tokenVisible ? "隐藏 Token" : "显示 Token");
        showToken.setEnabled(!linkToken.isEmpty());
    }

    private void setupInsets(View root, View nav, View divider) {
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN | WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        if (Build.VERSION.SDK_INT >= 30) {
            getWindow().setDecorFitsSystemWindows(false);
            WindowInsetsController controller = getWindow().getInsetsController();
            if (controller != null) {
                int light = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS | WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS;
                controller.setSystemBarsAppearance(light, light);
            }
            root.setOnApplyWindowInsetsListener((v, insets) -> {
                Insets bars = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
                int keyboard = insets.getInsets(WindowInsets.Type.ime()).bottom;
                v.setPadding(bars.left, bars.top, bars.right, Math.max(bars.bottom, keyboard));
                int visibility = keyboard > bars.bottom ? View.GONE : View.VISIBLE;
                nav.setVisibility(visibility); divider.setVisibility(visibility);
                return WindowInsets.CONSUMED;
            });
            root.requestApplyInsets();
        } else {
            getWindow().setStatusBarColor(PAGE);
            getWindow().setNavigationBarColor(PAGE);
            getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
        }
    }

    private void showPage(int page) {
        if (page == activePage) return;
        if (activePage >= 0) pages[activePage].setVisibility(View.GONE);
        activePage = page;
        pages[page].setVisibility(View.VISIBLE);
        for (int i = 0; i < tabs.length; i++) {
            boolean selected = i == page;
            int color = selected ? ACCENT : MUTED;
            tabs[i].setSelected(selected);
            tabs[i].setTextColor(color);
            tabs[i].setCompoundDrawableTintList(ColorStateList.valueOf(color));
            tabs[i].setBackground(touch(selected ? MINT : WHITE, 20));
        }
        if (page == HOME) refreshHealth();
    }

    @Override protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        out.putInt("page", activePage);
    }

    private void buildHome(LinearLayout body) {
        label(body, "小米关怀助手", 14, ACCENT, true);
        heading(body, "今日健康概览", "");
        homeDate = label(body, "", 14, MUTED, false);
        healthSource = label(body, "正在读取本机健康数据…", 14, MUTED, false);
        String[] titles = {"睡眠时长", "平均心率", "平均血氧", "平均压力"};
        String[] kinds = {"sleep", "heart_rate", "spo2", "stress"};
        for (int i = 0; i < titles.length; i += 2) {
            LinearLayout row = new LinearLayout(this);
            LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(-1, -2);
            rowParams.topMargin = dp(10);
            body.addView(row, rowParams);
            for (int j = i; j < i + 2; j++) {
                LinearLayout card = cardSurface();
                LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, -1, 1);
                if (j == i) params.setMarginEnd(dp(10));
                row.addView(card, params);
                label(card, titles[j], 14, MUTED, true);
                metricValues[j] = label(card, NO_DATA, 22, INK, true);
                final String kind = kinds[j];
                addButton(card, "查看明细", false, v -> HealthDetailActivity.open(this, kind))
                        .setContentDescription("查看" + titles[j] + "明细");
            }
        }
        LinearLayout status = card(body);
        label(status, "最近同步", 16, INK, true);
        homeSync = label(status, "等待同步", 14, MUTED, false);
        addButton(body, "刷新健康概览", false, v -> refreshHealth());
    }

    private void refreshHealth() {
        if (healthLoading) { healthRefreshPending = true; return; }
        healthLoading = true;
        healthRefreshPending = false;
        LocalDate day = LocalDate.now();
        homeDate.setText(day.format(DateTimeFormatter.ofPattern("M月d日 EEEE", Locale.CHINA)));
        worker.execute(() -> {
            JSONObject data = null;
            boolean hasCopy = HealthDatabaseStore.available(this);
            try {
                if (hasCopy) data = HealthDatabaseStore.query(this, new JSONObject().put("action", "daily_summary").put("date", day.toString()));
            } catch (Exception ignored) { /* Render an unavailable state, not stale or fabricated values. */ }
            String[] local = localHealthOverview(day);
            JSONObject stored = BandStore.read(this);
            long last = stored == null ? 0 : stored.optLong("synced_at", 0);
            JSONObject result = data;
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) return;
                healthLoading = false;
                latestDirectSync = Math.max(latestDirectSync, last);
                healthSource.setText(!hasCopy ? "健康数据库尚未同步" : result == null ? "本机健康数据暂不可读"
                        : "missing".equals(result.optString("status")) ? "今天暂无健康记录" : "来自本机健康数据库");
                JSONObject sleep = result == null ? null : result.optJSONObject("sleep");
                JSONObject totals = sleep == null ? null : sleep.optJSONObject("totals");
                long minutes = totals == null ? -1 : totals.optLong("total_minutes", -1);
                metricValues[0].setText(minutes < 0 ? NO_DATA : minutes / 60 + " 小时 " + minutes % 60 + " 分钟");
                setHealthMetric(result, 1, "heart_rate", " 次/分");
                setHealthMetric(result, 2, "spo2", "%");
                setHealthMetric(result, 3, "stress", "");
                boolean hasLocal = false;
                for (int i = 0; i < local.length; i++) {
                    if (local[i] == null) continue;
                    hasLocal = true;
                    metricValues[i].setText(local[i]);
                }
                if (hasLocal) healthSource.setText("包含本机版本记录 · 不回写运动健康");
                updateSyncStatus();
                if (healthRefreshPending && activePage == HOME) refreshHealth();
            });
        });
    }

    /** Recompute only edited dashboard metrics; the original summary API remains read-only. */
    private String[] localHealthOverview(LocalDate day) {
        String[] values = new String[4];
        java.time.ZoneId zone = java.time.ZoneId.systemDefault();
        long from = day.atStartOfDay(zone).toEpochSecond(), until = day.plusDays(1).atStartOfDay(zone).toEpochSecond();
        String[] kinds = {"sleep", "heart_rate", "spo2", "stress"};
        String[] units = {"", " 次/分", "%", ""};
        HealthHistory history = new HealthHistory(getApplicationContext());
        for (int i = 0; i < kinds.length; i++) {
            try {
                if (!history.hasEdits(kinds[i], from, until)) continue;
                JSONArray rows = history.list(kinds[i], from, until);
                long sum = 0;
                int count = 0;
                boolean incomplete = false;
                for (int j = 0; j < rows.length(); j++) {
                    JSONObject reading = rows.getJSONObject(j).getJSONObject("current");
                    if (i == 0) {
                        long wake = reading.getLong("end");
                        if (wake < from || wake >= until) continue;
                        long minutes = reading.optLong("total_minutes", -1);
                        if (minutes < 0) { incomplete = true; break; }
                        sum += minutes;
                    } else sum += reading.getInt("value");
                    count++;
                }
                values[i] = incomplete || count == 0 ? NO_DATA : i == 0
                        ? sum / 60 + " 小时 " + sum % 60 + " 分钟"
                        : java.text.NumberFormat.getNumberInstance().format(Math.round(10.0 * sum / count) / 10.0) + units[i];
            } catch (Exception ignored) {
                // Keep the separately queried source summary when the local journal is unavailable.
            }
        }
        return values;
    }

    private void setHealthMetric(JSONObject result, int index, String key, String suffix) {
        JSONObject metric = result == null ? null : result.optJSONObject(key);
        JSONObject summary = metric == null ? null : metric.optJSONObject("summary");
        double average = summary == null ? Double.NaN : summary.optDouble("average");
        metricValues[index].setText(Double.isFinite(average)
                ? java.text.NumberFormat.getNumberInstance().format(average) + suffix : NO_DATA);
    }

    private void buildDevice(LinearLayout body) {
        label(body, "我的设备", 14, ACCENT, true);
        heading(body, "小米手环 10", "查看手环状态，或手动同步健康数据");
        LinearLayout battery = card(body);
        label(battery, "手环电量", 15, MUTED, true);
        batteryValue = label(battery, UNREAD, 26, INK, true);
        batteryProgress = progress(battery);
        LinearLayout storage = card(body);
        label(storage, "手环存储", 15, MUTED, true);
        storageValue = label(storage, UNREAD, 18, INK, true);
        storageProgress = progress(storage);
        readDevice = addButton(body, "读取电量和存储", true, v -> readDeviceStatus());
        deviceFeedback = label(body, "点击读取，从手环获取当前读数。", 14, MUTED, false);
        deviceFeedback.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        LinearLayout sync = card(body);
        label(sync, "健康同步", 16, INK, true);
        deviceSync = label(sync, "等待同步", 14, MUTED, false);
        syncDevice = addButton(sync, "手动同步手环", false, v -> syncDevice());
        syncFeedback = label(sync, "", 14, MUTED, false);
        syncFeedback.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        label(sync, "自动同步间隔为 10 分钟。手环通道忙时可能切换到运动健康同步。", 13, MUTED, false);
    }

    private void readDeviceStatus() {
        runDeviceWork(deviceFeedback, "正在直连手环，读取电量和存储…", () -> {
            BandSync.DeviceStatus reading;
            String unavailable = "未收到手环读数，请确认蓝牙已开启、手环在附近，然后重试。";
            try {
                reading = BandSync.readDeviceStatus(this);
            } catch (IllegalStateException error) {
                unavailable = "尚未配置手环直连，请先完成一次手环同步。";
                reading = null;
            } catch (Exception error) {
                if (error instanceof InterruptedException) Thread.currentThread().interrupt();
                reading = null;
            }
            final BandSync.DeviceStatus result = reading;
            final String failure = unavailable;
            return () -> {
                Integer battery = result == null ? null : result.batteryPercent;
                boolean validBattery = battery != null && battery >= 0 && battery <= 100;
                batteryValue.setText(validBattery ? battery + "%" : UNREAD);
                batteryProgress.setVisibility(validBattery ? View.VISIBLE : View.GONE);
                if (validBattery) batteryProgress.setProgress(battery * 10);
                long[] storage = result == null ? null : result.storage;
                boolean validStorage = storage != null && storage.length >= 2 && storage[1] > 0 && storage[0] >= 0 && storage[0] <= storage[1];
                storageValue.setText(validStorage ? String.format(Locale.getDefault(),
                        "已用 %.2f / 总计 %.2f MiB\n剩余 %.2f MiB", storage[0] / 1048576.0, storage[1] / 1048576.0,
                        (storage[1] - storage[0]) / 1048576.0) : UNREAD);
                storageProgress.setVisibility(validStorage ? View.VISIBLE : View.GONE);
                if (validStorage) storageProgress.setProgress((int)Math.round(1000.0 * storage[0] / storage[1]));
                if (!validBattery && !validStorage) deviceFeedback.setText(failure);
                else deviceFeedback.setText((validBattery && validStorage ? "读取于 " : "部分读数未返回 · ") + clockTime(System.currentTimeMillis()));
            };
        });
    }

    private void syncDevice() {
        runDeviceWork(syncFeedback, "正在同步手环健康数据…", () -> {
            JSONObject sync = MonitorService.syncBand(this);
            boolean fallback = sync.optBoolean("fallback", false);
            String result = fallback ? sync.optString("text", "已切换运动健康同步") : "手环直连完成\n" + BandData.describe(sync);
            return () -> {
                if (!fallback) latestDirectSync = Math.max(latestDirectSync, sync.optLong("synced_at", 0));
                syncFeedback.setText(result);
                updateSyncStatus();
                if (activePage == HOME) refreshHealth();
            };
        });
    }

    private interface DeviceWork { Runnable run() throws Exception; }
    private void runDeviceWork(TextView feedback, String progress, DeviceWork work) {
        if (!ensureBluetoothPermission(feedback)) return;
        readDevice.setEnabled(false); syncDevice.setEnabled(false);
        feedback.setText(progress);
        worker.execute(() -> {
            Runnable render;
            try { render = work.run(); }
            catch (Exception error) {
                if (error instanceof InterruptedException) Thread.currentThread().interrupt();
                render = () -> feedback.setText("同步失败，请确认蓝牙已开启、手环在附近，然后重试。");
            }
            final Runnable result = render;
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) return;
                result.run();
                readDevice.setEnabled(true); syncDevice.setEnabled(true);
            });
        });
    }

    private boolean ensureBluetoothPermission(TextView feedback) {
        if (Build.VERSION.SDK_INT < 31) return true;
        if (checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED) return true;
        requestPermissions(new String[]{Manifest.permission.BLUETOOTH_CONNECT}, 2);
        feedback.setText("请允许“附近的设备”权限，再点一次按钮。");
        return false;
    }
    private void ensureServiceOnLaunch() {
        android.content.SharedPreferences prefs = getSharedPreferences("monitor", 0);
        if (!prefs.contains("service_enabled")) prefs.edit().putBoolean("service_enabled", true).commit();
        if (prefs.getBoolean("service_enabled", true)) startCompanionService();
    }
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }

    private LinearLayout column() {
        LinearLayout column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        return column;
    }

    private GradientDrawable surface(int color, int radius) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(dp(radius));
        return drawable;
    }

    private Drawable touch(int color, int radius) {
        return new RippleDrawable(ColorStateList.valueOf(0x35087669), surface(color, radius), null);
    }

    private LinearLayout cardSurface() {
        LinearLayout card = column();
        GradientDrawable background = surface(WHITE, 20);
        background.setStroke(dp(1), LINE);
        card.setBackground(background);
        card.setPadding(dp(16), dp(12), dp(16), dp(12));
        return card;
    }

    private LinearLayout card(LinearLayout parent) {
        LinearLayout card = cardSurface();
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.topMargin = dp(10);
        parent.addView(card, params);
        return card;
    }

    private TextView label(LinearLayout parent, String content, int size, int color, boolean bold) {
        TextView view = new TextView(this);
        view.setText(content); view.setTextSize(size); view.setTextColor(color);
        view.setPadding(0, dp(3), 0, dp(3));
        if (bold) view.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        parent.addView(view);
        return view;
    }

    private void heading(LinearLayout parent, String title, String subtitle) {
        label(parent, title, 30, INK, true).setAccessibilityHeading(true);
        if (!subtitle.isEmpty()) label(parent, subtitle, 14, MUTED, false).setPadding(0, dp(3), 0, dp(10));
    }

    private ProgressBar progress(LinearLayout parent) {
        ProgressBar bar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        bar.setMax(1000);
        bar.setProgressTintList(ColorStateList.valueOf(ACCENT));
        bar.setProgressBackgroundTintList(ColorStateList.valueOf(LINE));
        bar.setVisibility(View.GONE);
        bar.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, dp(8));
        params.topMargin = dp(8); params.bottomMargin = dp(4);
        parent.addView(bar, params);
        return bar;
    }

    private Button addButton(LinearLayout parent, String title, boolean primary, View.OnClickListener listener) {
        Button button = new Button(this);
        button.setText(title);
        button.setAllCaps(false);
        button.setTextSize(14);
        button.setTextColor(new ColorStateList(new int[][]{new int[]{-android.R.attr.state_enabled}, new int[]{}},
                new int[]{MUTED, primary ? WHITE : ACCENT}));
        StateListDrawable background = new StateListDrawable();
        background.addState(new int[]{-android.R.attr.state_enabled}, surface(LINE, 14));
        background.addState(new int[]{}, touch(primary ? ACCENT : MINT, 14));
        button.setBackground(background);
        button.setMinHeight(dp(48));
        button.setPadding(dp(14), dp(8), dp(14), dp(8));
        button.setOnClickListener(listener);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.topMargin = dp(8);
        parent.addView(button, params);
        return button;
    }

    private LinearLayout settingsSection(LinearLayout parent, String title, String tag, String description) {
        LinearLayout section = card(parent);
        LinearLayout header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        section.addView(header, new LinearLayout.LayoutParams(-1, -2));
        TextView mark = new TextView(this);
        mark.setText(tag);
        mark.setTextColor(ACCENT);
        mark.setTextSize(12);
        mark.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        mark.setGravity(Gravity.CENTER);
        mark.setPadding(dp(6), dp(4), dp(6), dp(4));
        mark.setMinHeight(dp(36));
        mark.setMinWidth(dp(36));
        mark.setBackground(surface(MINT, 10));
        mark.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        LinearLayout.LayoutParams markParams = new LinearLayout.LayoutParams(-2, -2);
        markParams.setMarginEnd(dp(10));
        header.addView(mark, markParams);
        LinearLayout titles = column();
        header.addView(titles, new LinearLayout.LayoutParams(0, -2, 1));
        label(titles, title, 21, INK, true).setAccessibilityHeading(true);
        settingsText(titles, description);
        View divider = new View(this);
        divider.setBackgroundColor(LINE);
        LinearLayout.LayoutParams dividerParams = new LinearLayout.LayoutParams(-1, dp(1));
        dividerParams.topMargin = dp(8);
        dividerParams.bottomMargin = dp(8);
        section.addView(divider, dividerParams);
        return section;
    }

    private Button settingsOverview(LinearLayout parent, View anchor, boolean spaced) {
        Button button = addButton(parent, "", false, v -> jumpToSettings(anchor));
        button.setMinWidth(0);
        button.setMinimumWidth(0);
        button.setTextSize(13);
        button.setPadding(dp(4), dp(7), dp(4), dp(7));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, -2, 1);
        if (spaced) params.setMarginStart(dp(6));
        button.setLayoutParams(params);
        return button;
    }

    private void jumpToSettings(View anchor) {
        ScrollView page = pages[SETTINGS];
        page.smoothScrollTo(0, Math.max(0, anchor.getTop() - dp(8)));
    }

    private final class SettingsDisclosure {
        final Button toggle;
        final LinearLayout body;
        final String title;
        Runnable onCollapse;

        SettingsDisclosure(LinearLayout parent, String title) {
            this.title = title;
            toggle = addButton(parent, title, false, v -> setExpanded(!isExpanded()));
            toggle.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
            body = column();
            body.setPadding(dp(6), dp(4), dp(6), dp(4));
            parent.addView(body, new LinearLayout.LayoutParams(-1, -2));
            setExpanded(false);
        }

        boolean isExpanded() { return body.getVisibility() == View.VISIBLE; }

        void setExpanded(boolean expanded) {
            body.setVisibility(expanded ? View.VISIBLE : View.GONE);
            toggle.setText(title + (expanded ? "  ▴" : "  ▾"));
            if (Build.VERSION.SDK_INT >= 30) {
                toggle.setContentDescription(title);
                toggle.setStateDescription(expanded ? "已展开" : "已折叠");
            } else toggle.setContentDescription(title + (expanded ? "，已展开" : "，已折叠"));
            if (!expanded && onCollapse != null) onCollapse.run();
        }
    }

    private TextView settingsText(LinearLayout parent, String content) {
        return label(parent, content, 14, MUTED, false);
    }

    private TextView settingsReport(LinearLayout parent) {
        TextView view = label(parent, "", 14, INK, false);
        view.setBackground(surface(MINT, 10));
        view.setPadding(dp(10), dp(6), dp(10), dp(6));
        view.setTextIsSelectable(true);
        view.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        view.setVisibility(View.GONE);
        return view;
    }

    private EditText settingsInput(LinearLayout parent, String title, String hint, int inputType) {
        TextView titleView = label(parent, title, 14, INK, true);
        EditText view = new EditText(this);
        view.setId(View.generateViewId());
        titleView.setLabelFor(view.getId());
        view.setTextSize(16);
        view.setTextColor(INK);
        view.setHintTextColor(MUTED);
        view.setBackgroundTintList(settingsTint(ACCENT, MUTED));
        view.setSingleLine((inputType & InputType.TYPE_TEXT_FLAG_MULTI_LINE) == 0);
        view.setInputType(inputType);
        view.setHint(hint);
        view.setMinHeight(dp(48));
        view.setSaveEnabled(false);
        view.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS);
        parent.addView(view, new LinearLayout.LayoutParams(-1, -2));
        return view;
    }

    private ColorStateList settingsTint(int active, int normal) {
        return new ColorStateList(new int[][]{new int[]{-android.R.attr.state_enabled},
                new int[]{android.R.attr.state_checked}, new int[]{android.R.attr.state_focused}, new int[]{}},
                new int[]{LINE, active, active, normal});
    }

    private CheckBox settingsCheckBox(LinearLayout parent, String title) {
        CheckBox view = new CheckBox(this);
        view.setText(title);
        view.setTextColor(INK);
        view.setButtonTintList(settingsTint(ACCENT, MUTED));
        view.setMinHeight(dp(48));
        parent.addView(view, new LinearLayout.LayoutParams(-1, -2));
        return view;
    }

    private Switch settingsSwitch(LinearLayout parent, String title, String description) {
        Switch view = new Switch(this);
        view.setText(title);
        view.setTextColor(INK);
        view.setTextSize(16);
        view.setThumbTintList(settingsTint(ACCENT, MUTED));
        view.setTrackTintList(settingsTint(MINT, LINE));
        view.setSwitchPadding(dp(8));
        view.setMinHeight(dp(48));
        parent.addView(view, new LinearLayout.LayoutParams(-1, -2));
        settingsText(parent, description);
        return view;
    }
    private interface Work { String run() throws Exception; }
    // Capture configuration on the UI thread; EditText is never accessed by worker code.
    private String draftUrl, draftKey, draftUmo, draftPrompt;
    private boolean draftHttp;
    private void saveDraft() throws Exception { SettingsStore.save(this, draftUrl, draftKey, draftUmo, draftHttp, draftPrompt); }
    private boolean requiresAstrbot(Button source) { return source == check || source == test || source == retry; }

    private void background(Button source, TextView feedback, String progress, Work work) {
        if (busy || requiresAstrbot(source) && !IntegrationSettings.astrbotEnabled(this)) return;
        draftUrl = url.getText().toString(); draftKey = key.getText().toString();
        draftUmo = umo.getText().toString(); draftHttp = http.isChecked(); draftPrompt = prompt.getText().toString();
        final CharSequence label = source.getText();
        setBusy(true); source.setText(progress);
        if (source == diagnostic) diagnosticsDetails.setExpanded(true);
        showReport(feedback, progress + (source == check ? "（多次 GET 请求，最长约 60 秒）"
                : source == test || source == retry ? "（AI 生成最长 180 秒，之后才尝试发送）" : ""));
        worker.execute(() -> {
            String result;
            try {
                result = requiresAstrbot(source) && !IntegrationSettings.astrbotEnabled(this)
                        ? "AstrBot 已关闭，未执行该操作。" : work.run();
            } catch (Exception e) {
                result = e instanceof IllegalArgumentException ? e.getMessage() : "操作失败：" + e.getClass().getSimpleName()
                        + (source == test || source == retry ? "；未确认发送成功" : "");
            }
            final String display = result;
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) return;
                showReport(feedback, display);
                draftKey = draftUrl = draftUmo = draftPrompt = null;
                source.setText(label); setBusy(false); refresh();
            });
        });
    }
    private String redactInputs(String content) {
        if (content == null) return "操作失败：未返回结果";
        for (String value : new String[]{draftKey, draftUrl, draftUmo, linkToken})
            if (value != null && !value.isEmpty()) content = content.replace(value, "[已隐藏]");
        return content;
    }
    private void showReport(TextView target, String content) {
        String display = redactInputs(content);
        setSettingsText(target, display);
        target.setVisibility(display.isEmpty() ? View.GONE : View.VISIBLE);
        // Feedback stays in place: never request focus or scroll the reader to this label.
    }
    private void setBusy(boolean value) {
        busy = value;
        for (Button button : new Button[]{save, diagnostic, clear, restorePrompt}) button.setEnabled(!value);
        url.setEnabled(!value); key.setEnabled(!value); umo.setEnabled(!value); http.setEnabled(!value); prompt.setEnabled(!value);
        port.setEnabled(!value);
        updateAstrbotActions(IntegrationSettings.astrbotEnabled(this));
    }
    private void updateAstrbotActions(boolean enabled) {
        boolean onlineActions = enabled && !busy;
        check.setEnabled(onlineActions);
        test.setEnabled(onlineActions);
        retry.setEnabled(onlineActions);
    }
    private void open(Intent intent) {
        try { startActivity(intent); }
        catch (RuntimeException e) { showReport(monitorReport, "此系统没有对应设置入口，请从系统设置手动打开；" + e.getClass().getSimpleName()); }
    }
    private void requestHealthCopy(Button source) {
        LinkServer server = MonitorService.current();
        if (server == null || !server.isRunning()) {
            showReport(syncReport, "后台数据服务未运行，请先启动后台服务。");
            return;
        }
        if (!server.status("health").connected) {
            showReport(syncReport, "健康模块未连接。请打开小米运动健康，等本页显示已连接后再同步。");
            return;
        }
        if (HealthDatabaseStore.receivingFitnessData()) {
            showReport(syncReport, "健康数据库正在传输，无需重复请求。请留意副本更新时间。");
            return;
        }
        source.setEnabled(false);
        showReport(syncReport, "正在请求健康模块同步数据库…");
        worker.execute(() -> {
            String result;
            try {
                MonitorService.requestDatabaseSync(this);
                result = "健康模块已接收请求。是否完成以「本机健康副本」的更新时间为准。";
            } catch (Exception e) {
                result = "未收到健康模块确认。请保持小米运动健康运行，待模块连接后重试。";
            }
            String message = result;
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) return;
                source.setEnabled(true);
                showReport(syncReport, message);
                updateLinkStatus();
            });
        });
    }
    private void startCompanionService() {
        getSharedPreferences("monitor", 0).edit().putBoolean("service_enabled", true).commit();
        try { startForegroundService(new Intent(this, MonitorService.class)); }
        catch (RuntimeException e) {
            showReport(monitorReport, "启动后台服务失败：" + e.getClass().getSimpleName() + "；请检查通知和后台权限");
        }
    }
    private boolean reloadService() {
        getSharedPreferences("monitor", 0).edit().putBoolean("service_enabled", true).commit();
        try {
            if (MonitorService.current() == null) startForegroundService(new Intent(this, MonitorService.class));
            else startForegroundService(new Intent(this, MonitorService.class).setAction(MonitorService.ACTION_RELOAD_LINK));
            return true;
        } catch (RuntimeException e) { return false; }
    }
    private void regenerateToken() {
        try {
            int value = Integer.parseInt(port.getText().toString().trim());
            String token = LinkProtocol.newToken(new SecureRandom());
            SettingsStore.saveLink(this, value, token);
            linkToken = token;
            renderToken();
            showReport(linkReport, "Token 已重新生成；请在天气设置页和健康“关于”设置页填入新 Token"
                    + (reloadService() ? "" : "；服务重载请求失败，请检查后台权限。"));
            updateLinkStatus();
        } catch (Exception e) {
            showReport(linkReport, e instanceof IllegalArgumentException ? e.getMessage() : "生成失败：" + e.getClass().getSimpleName());
        }
    }
    private static String clockTime(long timestamp) {
        return java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.SHORT, java.text.DateFormat.SHORT).format(new java.util.Date(timestamp));
    }

    private void updateSyncStatus() {
        SharedPreferences prefs = getSharedPreferences("monitor", 0);
        boolean fallback = prefs.getBoolean("band_health_fallback", false);
        boolean failed = !prefs.getString("band_auto_error", "").isEmpty();
        String message = fallback ? "最近一次已切换到运动健康同步"
                : failed ? "最近一次直连失败，可在设备页重试" : "等待首次手环同步";
        long successful = Math.max(latestDirectSync, !failed && !fallback ? prefs.getLong("band_auto_at", 0) : 0);
        if (successful > 0) {
            String last = "上次直连成功 · " + clockTime(successful);
            message = failed || fallback ? message + "\n" + last : last;
        }
        if (!message.contentEquals(homeSync.getText())) homeSync.setText(message);
        if (!message.contentEquals(deviceSync.getText())) deviceSync.setText(message);
    }

    private void updateLinkStatus() {
        if (linkStatus == null) return;
        SharedPreferences prefs = getSharedPreferences("monitor", 0);
        boolean running = MonitorService.serviceRunning();
        boolean monitoring = prefs.getBoolean("enabled", false);
        LinkServer server = MonitorService.current();
        LinkServer.Status health = server == null ? null : server.status("health");
        LinkServer.Status weather = server == null ? null : server.status("weather");
        long now = System.nanoTime() / 1_000_000L; // LinkServer uses the same monotonic clock.
        setSettingsText(healthLink, "健康模块：" + moduleStatus(health, now));
        setSettingsText(weatherLink, "天气模块：" + moduleStatus(weather, now));
        int connected = (moduleConnected(health) ? 1 : 0) + (moduleConnected(weather) ? 1 : 0);
        setSettingsText(overviewLinks, "模块连接\n" + connected + " / 2 已连接");

        String linkMessage = server == null ? (running ? "后台服务运行中，但数据连接未启动" : "后台服务未运行")
                : server.isRunning() ? "监听 127.0.0.1:" + server.port() : "服务启动失败：" + server.error();
        String linkError = prefs.getString("link_error", "");
        setSettingsText(linkStatus, linkError.isEmpty() ? linkMessage : linkMessage + "\n最近服务错误：" + linkError);

        long copyAt = HealthDatabaseStore.file(this).lastModified();
        boolean receiving = HealthDatabaseStore.receivingFitnessData();
        String copy = copyAt > 0 ? "更新于 " + clockTime(copyAt) : "尚未同步";
        setSettingsText(copyStatus, "本机健康副本：" + copy + (receiving ? "\n健康数据库正在传输，请等待完成" : ""));
        setSettingsText(overviewCopy, "健康副本\n" + (receiving ? "传输中" : copyAt > 0 ? "已有副本" : "尚未同步"));

        String bandError = prefs.getString("band_auto_error", "");
        boolean tokenExpired = prefs.getBoolean("band_token_expired", false);
        long bandAt = prefs.getLong("band_auto_at", 0);
        String band;
        if (tokenExpired) band = "token 已过期（蓝牙已开启，但请求手环失败）";
        else if (!bandError.isEmpty()) band = "失败（" + bandError + "）";
        else if (bandAt > 0) {
            long mins = Math.max(0, System.currentTimeMillis() - bandAt) / 60000;
            band = (mins == 0 ? "刚刚同步" : "上次成功 " + mins + " 分钟前")
                    + ("health".equals(prefs.getString("band_auto_source", "")) ? " · 运动健康回退" : "");
        } else band = "等待首次 10 分钟同步";
        setSettingsText(bandStatus, "手环自动同步：" + band);
        updateSyncStatus();
        if (tokenExpired && !tokenExpiryShown)
            android.widget.Toast.makeText(this, "token 已过期", android.widget.Toast.LENGTH_LONG).show();
        tokenExpiryShown = tokenExpired;

        setSettingsText(serviceState, running ? "后台服务运行中" : "后台服务未运行");
        setSettingsText(serviceAction, running ? "停止后台服务" : "启动后台服务");
        setSettingsText(overviewMonitor, "自动监控\n" + (!monitoring ? "已停用" : running ? "已启用" : "等待后台服务"));
        setSettingsText(monitorStatus, MonitorService.status(this));
        setSettingsText(taskStatus, redactInputs(EventClient.status(this)));
        boolean astrbotEnabled = IntegrationSettings.astrbotEnabled(this);
        boolean mcpEnabled = IntegrationSettings.mcpEnabled(this);
        updatingSettings = true;
        try {
            monitorSwitch.setChecked(monitoring);
            astrbotSwitch.setChecked(astrbotEnabled);
            mcpSwitch.setChecked(mcpEnabled);
        } finally { updatingSettings = false; }
        setSettingsText(astrbotStatus, astrbotEnabled ? "已启用" : "已关闭");
        boolean mcpRunning = mcpEnabled && running && MonitorService.mcpRunning();
        String mcpFailure = mcpEnabled && running ? prefs.getString("mcp_error", "") : "";
        setSettingsText(mcpStatus, !mcpEnabled ? "已关闭" : !running ? "等待后台服务" : mcpRunning ? "运行中"
                : mcpFailure.isEmpty() ? "已启用，等待监听" : "已启用，尚未运行");
        setSettingsText(mcpError, mcpFailure.isEmpty() ? "" : "MCP 错误：" + mcpFailure);
        mcpError.setVisibility(mcpFailure.isEmpty() ? View.GONE : View.VISIBLE);
        updateAstrbotActions(astrbotEnabled);
    }

    private static boolean moduleConnected(LinkServer.Status status) {
        return status != null && status.connected && status.authFailures == 0;
    }

    private static String moduleStatus(LinkServer.Status status, long now) {
        if (status == null) return "未连接";
        if (status.authFailures > 0) return "模块鉴权失败，请重新填写 Token";
        if (!status.connected) return status.lastError.isEmpty() ? "未连接" : "未连接（" + status.lastError + "）";
        long age = Math.max(0, now - status.lastPongAt) / 1000;
        return "已连接 · 心跳 " + age + " 秒前" + (status.rttMs >= 0 ? " · RTT " + status.rttMs + " ms" : "");
    }

    private static void setSettingsText(TextView view, String text) {
        if (!text.contentEquals(view.getText())) view.setText(text);
    }

    private void refresh() {
        if (tokenView != null && linkToken.isEmpty()) try {
            String token = SettingsStore.load(this).token;
            if (!token.isEmpty()) {
                linkToken = token;
                renderToken();
            }
        } catch (Exception ignored) { }
        if (privilege != null) setSettingsText(privilege, TargetRecovery.privilegeText(this));
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
        if (activePage == HOME) refreshHealth();
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

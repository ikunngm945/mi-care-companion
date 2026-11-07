package cn.local.micompanion.hook;

import android.app.Activity;
import android.app.Dialog;
import android.content.res.Configuration;
import android.graphics.Typeface;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.InputType;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/** Port/token form shared by the weather settings entry and the health “关于” replacement. */
public final class SettingsForm {
    private SettingsForm() { }

    public static View inline(Activity activity, LinkClient client, String heading) {
        client.start();
        boolean night = (activity.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK)
                == Configuration.UI_MODE_NIGHT_YES;
        int primary = night ? 0xFFEDEDED : 0xFF111111;
        int secondary = night ? 0xFFB0B0B0 : 0xFF5F6368;
        int pad = Injection.dp(activity, 20);

        ScrollView scroll = new ScrollView(activity);
        LinearLayout root = new LinearLayout(activity);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, pad, pad, pad);
        scroll.addView(root, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView title = label(activity, heading, 20, primary);
        root.addView(title);
        root.addView(label(activity, "端口和 Token 必须与主程序“数据连接”区块一致。数据只在本机回环地址传输。", 13, secondary));

        root.addView(label(activity, "WebSocket 端口", 14, primary));
        EditText port = new EditText(activity);
        port.setSingleLine(true);
        port.setInputType(InputType.TYPE_CLASS_NUMBER);
        port.setText(String.valueOf(HookConfig.port(activity)));
        root.addView(port);

        root.addView(label(activity, "Token（16 位字母或数字）", 14, primary));
        EditText token = new EditText(activity);
        token.setSingleLine(true);
        token.setTypeface(Typeface.MONOSPACE);
        token.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD);
        token.setText(HookConfig.token(activity));
        token.setHint("从主程序复制");
        root.addView(token);

        Button save = new Button(activity);
        save.setText("保存并重连");
        LinearLayout.LayoutParams saveParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        saveParams.topMargin = Injection.dp(activity, 12);
        root.addView(save, saveParams);

        TextView status = label(activity, "", 14, primary);
        status.setTextIsSelectable(true);
        root.addView(status);

        root.addView(label(activity,
                "说明：修改端口或 Token 后，主程序和另一个模块也要同步修改；密钥鉴权失败时显示“token 已过期”；普通掉线只显示未连接。",
                12, secondary));

        save.setOnClickListener(v -> {
            try {
                int value = Integer.parseInt(port.getText().toString().trim());
                String secret = token.getText().toString().trim();
                HookConfig.validate(value, secret);
                HookConfig.save(activity, value, secret);
                client.reconnect();
                status.setText("已保存，正在重连…");
            } catch (IllegalArgumentException e) {
                status.setText(e.getMessage());
            } catch (Exception e) {
                status.setText("保存失败：" + e.getClass().getSimpleName());
            }
        });

        final Handler handler = new Handler(Looper.getMainLooper());
        final Runnable tick = new Runnable() {
            @Override public void run() {
                if (!scroll.isAttachedToWindow()) return;
                status.setText(describe(client));
                handler.postDelayed(this, 1000);
            }
        };
        scroll.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
            @Override public void onViewAttachedToWindow(View view) { handler.removeCallbacks(tick); handler.post(tick); }
            @Override public void onViewDetachedFromWindow(View view) { handler.removeCallbacks(tick); }
        });
        status.setText(describe(client));
        return scroll;
    }

    /** Full-screen configuration page opened from the weather settings button. */
    public static void page(Activity activity, LinkClient client) {
        boolean night = (activity.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK)
                == Configuration.UI_MODE_NIGHT_YES;
        int background = night ? 0xFF111111 : 0xFFFFFFFF;
        Dialog dialog = new Dialog(activity, android.R.style.Theme_DeviceDefault_Light_NoActionBar);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);

        LinearLayout page = new LinearLayout(activity);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setBackgroundColor(background);
        int statusBar = activity.getResources().getIdentifier("status_bar_height", "dimen", "android");
        int top = statusBar == 0 ? Injection.dp(activity, 24)
                : activity.getResources().getDimensionPixelSize(statusBar);
        page.setPadding(0, top, 0, 0);

        Button back = new Button(activity);
        back.setText("← 返回");
        back.setAllCaps(false);
        back.setOnClickListener(v -> dialog.dismiss());
        page.addView(back, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        page.addView(inline(activity, client, "数据连接设置"), new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        dialog.setContentView(page);
        dialog.show();
        Window window = dialog.getWindow();
        if (window != null) {
            window.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
            window.setDimAmount(0f);
            window.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(background));
        }
    }

    public static void dialog(Activity activity, LinkClient client) {
        Dialog dialog = new Dialog(activity);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        dialog.setContentView(inline(activity, client, "小米关怀助手 · 数据连接"));
        dialog.show();
        Window window = dialog.getWindow();
        if (window != null) {
            window.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.WRAP_CONTENT);
            window.setDimAmount(0.4f);
        }
    }

    private static TextView label(Activity activity, String text, int size, int color) {
        TextView view = new TextView(activity);
        view.setText(text);
        view.setTextSize(size);
        view.setTextColor(color);
        view.setPadding(0, Injection.dp(activity, 6), 0, Injection.dp(activity, 6));
        return view;
    }

    private static String describe(LinkClient client) {
        LinkClient.Status status = client.status();
        StringBuilder out = new StringBuilder("状态：").append(status.state);
        if (status.connected) {
            long age = status.lastHeartbeatAt <= 0 ? -1 : Math.max(0, SystemClock.elapsedRealtime() - status.lastHeartbeatAt) / 1000;
            out.append(" · ").append(age < 0 ? "等待心跳" : "最近心跳 " + age + " 秒前");
        }
        if (status.authFailures > 0) out.append("\ntoken 已过期；请检查 Token 是否与主程序一致");
        if (!status.error.isEmpty()) out.append('\n').append(status.error);
        out.append("\n已送达快照：").append(status.delivered);
        return out.toString();
    }
}

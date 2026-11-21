package cn.local.micompanion;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.DatePickerDialog;
import android.app.TimePickerDialog;
import android.content.Intent;
import android.graphics.Insets;
import android.graphics.Typeface;
import android.os.Build;
import android.os.Bundle;
import android.text.InputType;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import org.json.JSONArray;
import org.json.JSONObject;

/** In-app health history; the dashboard tabs are not present here. */
public final class HealthDetailActivity extends Activity {
    private static final int PAGE = 0xfff3f7f5, INK = 0xff182b26, MUTED = 0xff506660, ACCENT = 0xff087669;
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd", Locale.CHINA);
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm", Locale.CHINA);
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss", Locale.CHINA);
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final ZoneId zone = ZoneId.systemDefault();
    private String kind, title, unit;
    private int min, max, listRequest, historyRequest, listPosition, listOffset;
    private long from, until;
    private boolean listLoading;
    private final android.os.Handler uiHandler = new android.os.Handler(android.os.Looper.getMainLooper());
    private HealthHistory history;
    private LinearLayout root, browse;
    private TextView listStatus, listResult;
    private Button fromDate, fromTime, untilDate, untilTime;
    private ListView list;
    private Rows rows;
    private SleepDetailView sleep;

    static void open(Activity owner, String kind) {
        owner.startActivity(new Intent(owner, HealthDetailActivity.class).putExtra("kind", kind));
    }

    @Override protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        kind = getIntent().getStringExtra("kind");
        if ("sleep".equals(kind)) { title = "睡眠"; unit = ""; }
        else if ("heart_rate".equals(kind)) { title = "心率"; unit = " 次/分"; min = 1; max = 300; }
        else if ("spo2".equals(kind)) { title = "血氧"; unit = "%"; min = 1; max = 100; }
        else if ("stress".equals(kind)) { title = "压力"; unit = ""; min = 1; max = 100; }
        else { finish(); return; }
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        history = new HealthHistory(getApplicationContext());
        root = column();
        root.setBackgroundColor(PAGE);
        setContentView(root);
        if (Build.VERSION.SDK_INT >= 30) {
            getWindow().setDecorFitsSystemWindows(false);
            WindowInsetsController controller = getWindow().getInsetsController();
            if (controller != null) {
                int light = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS | WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS;
                controller.setSystemBarsAppearance(light, light);
            }
            root.setOnApplyWindowInsetsListener((v, insets) -> {
                Insets safe = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout() | WindowInsets.Type.ime());
                v.setPadding(safe.left, safe.top, safe.right, safe.bottom);
                return WindowInsets.CONSUMED;
            });
            root.requestApplyInsets();
        } else {
            getWindow().setStatusBarColor(PAGE);
            getWindow().setNavigationBarColor(PAGE);
            getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
        }
        today();
        if (saved != null) {
            from = saved.getLong("from", from);
            until = saved.getLong("until", until);
            listPosition = saved.getInt("listPosition");
            listOffset = saved.getInt("listOffset");
        }
        buildList();
        loadList();
    }

    @Override protected void onSaveInstanceState(Bundle out) {
        if (sleep == null && list != null && !listLoading) rememberListPosition();
        out.putLong("from", from);
        out.putLong("until", until);
        out.putInt("listPosition", listPosition);
        out.putInt("listOffset", listOffset);
        super.onSaveInstanceState(out);
    }

    private void today() {
        LocalDate day = LocalDate.now(zone);
        from = day.atStartOfDay(zone).toEpochSecond();
        until = day.plusDays(1).atStartOfDay(zone).toEpochSecond();
    }

    private void buildList() {
        browse = column();
        browse.setPadding(dp(16), dp(6), dp(16), 0);
        root.addView(browse, new LinearLayout.LayoutParams(-1, -1));
        bar(browse, button("‹ 首页", this::finish), text(title + "明细", 24, INK, true));
        list = new ListView(this);
        list.setDividerHeight(dp(1));
        // Keep the filter reachable in landscape and at large font sizes.
        LinearLayout filter = column();
        listStatus = text("", 14, MUTED, false);
        listStatus.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        listResult = text("", 14, MUTED, false);
        filter.addView(listStatus);
        fromDate = button("", () -> pickDate(true)); fromTime = button("", () -> pickTime(true));
        untilDate = button("", () -> pickDate(false)); untilTime = button("", () -> pickTime(false));
        bar(filter, text("开始", 14, MUTED, true), fromDate, fromTime);
        bar(filter, text("结束", 14, MUTED, true), untilDate, untilTime);
        bar(filter, button("刷新记录", () -> { listPosition = listOffset = 0; loadList(); }),
                button("回到今天", () -> { today(); updateRangeLabels(); listPosition = listOffset = 0; loadList(); }));
        listResult.setPadding(dp(4), dp(4), dp(4), dp(8));
        listResult.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        filter.addView(listResult);
        list.addHeaderView(filter, null, false);
        rows = new Rows();
        list.setAdapter(rows);
        list.setOnItemClickListener((parent, view, position, id) -> {
            Object row = list.getItemAtPosition(position);
            if (listLoading || !(row instanceof JSONObject)) return;
            ++historyRequest;
            listStatus.setText("");
            if (isSleep()) showSleep((JSONObject)row);
            else editVital((JSONObject)row);
        });
        browse.addView(list, new LinearLayout.LayoutParams(-1, 0, 1));
        updateRangeLabels();
    }

    private ZonedDateTime local(long epoch) { return Instant.ofEpochSecond(epoch).atZone(zone); }

    private void updateRangeLabels() {
        updateLabel(fromDate, "开始日期", DATE.format(local(from)));
        updateLabel(fromTime, "开始时间", TIME.format(local(from)));
        updateLabel(untilDate, "结束日期", DATE.format(local(until)));
        updateLabel(untilTime, "结束时间", TIME.format(local(until)));
    }

    private void updateLabel(Button button, String name, String value) {
        button.setText(value);
        button.setContentDescription(name + " " + value);
    }

    private void pickDate(boolean start) {
        ZonedDateTime current = local(start ? from : until);
        secure(new DatePickerDialog(this, (v, y, m, d) ->
                updateRange(start, ZonedDateTime.ofLocal(LocalDate.of(y, m + 1, d).atTime(current.toLocalTime()), zone, current.getOffset())),
                current.getYear(), current.getMonthValue() - 1, current.getDayOfMonth()));
    }

    private void pickTime(boolean start) {
        ZonedDateTime current = local(start ? from : until);
        secure(new TimePickerDialog(this, (v, h, m) -> updateRange(start,
                ZonedDateTime.ofLocal(current.toLocalDate().atTime(h, m), zone, current.getOffset())),
                current.getHour(), current.getMinute(), true));
    }

    private void updateRange(boolean start, ZonedDateTime value) {
        if (start) from = value.toEpochSecond(); else until = value.toEpochSecond();
        updateRangeLabels();
        listPosition = listOffset = 0;
        listStatus.setText("");
        loadList();
    }

    private void loadList() {
        int request = ++listRequest;
        ++historyRequest;
        listLoading = true;
        String previous = rows.getCount() == 0 ? "" : "\n下方仍显示上一次筛选结果";
        if (until <= from) {
            listResult.setText("结束时间必须晚于开始时间" + previous);
            return;
        }
        long start = from, end = until;
        listResult.setText("正在读取记录…" + previous);
        query(() -> history.list(kind, start, end), data -> {
            if (request != listRequest) return;
            listLoading = false;
            rows.set(data);
            listResult.setText(data.length() == 0 ? (HealthDatabaseStore.available(this)
                    ? "这个时间段没有记录" : "健康数据库尚未同步，暂无记录") : data.length() + " 条 · 点一条查看");
            if (listPosition < list.getCount()) list.setSelectionFromTop(listPosition, listOffset);
        }, message -> {
            if (request != listRequest) return;
            listResult.setText(message + previous);
        });
    }

    private void rememberListPosition() {
        listPosition = list.getFirstVisiblePosition();
        View first = list.getChildAt(0);
        listOffset = first == null ? 0 : first.getTop();
    }

    private void showSleep(JSONObject entry) {
        rememberListPosition();
        SleepDetailView view = new SleepDetailView(this, entry, new SleepDetailView.Host() {
            @Override public void readings(long start, long end, Consumer<JSONObject> ok, Consumer<String> error) {
                query(() -> history.readings(start, end), data -> { if (sleep != null) ok.accept(data); }, msg -> { if (sleep != null) error.accept(msg); });
            }
            @Override public void history(JSONObject item, Consumer<JSONArray> ok, Consumer<String> error) {
                query(() -> history.history(item), ok, error);
            }
            @Override public void save(JSONObject item, JSONObject next, long restoreSeq, Consumer<Boolean> ok, Consumer<String> error) {
                query(() -> history.save(item, next, restoreSeq), changed -> {
                    ok.accept(changed);
                    if (changed) { listStatus.setText("已写回，正在核对运动健康副本…"); awaitStressCopy(40); }
                    else saved(false);
                }, error);
            }
            @Override public void back() { backToList(); }
        });
        sleep = view;
        browse.setVisibility(View.GONE);
        root.addView(view, new LinearLayout.LayoutParams(-1, -1));
    }

    private void backToList() {
        if (sleep == null) return;
        root.removeView(sleep);
        sleep = null;
        browse.setVisibility(View.VISIBLE);
        loadList();
    }

    private void editVital(JSONObject entry) {
        JSONObject reading = entry.optJSONObject("current");
        if (reading == null) return;
        LinearLayout panel = column();
        panel.setPadding(dp(24), dp(4), dp(24), dp(8));
        panel.addView(text(stamp(reading.optLong("start")), 15, MUTED, false));
        EditText value = new EditText(this);
        value.setInputType(InputType.TYPE_CLASS_NUMBER);
        value.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO);
        value.setSaveEnabled(false);
        value.setSingleLine();
        value.setHint("读数" + unit);
        value.setText(Integer.toString(reading.optInt("value")));
        panel.addView(value);
        panel.addView(text("保存时备份并写回小米运动健康本机数据库，不直接写入手环。", 14, MUTED, false));
        TextView error = text("", 14, 0xff992626, false);
        error.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        panel.addView(error);
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle("编辑" + title).setView(panel)
                .setNegativeButton("取消", null).setNeutralButton("原始记录与备份", null).setPositiveButton("备份并保存", null).create();
        secure(dialog);
        dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(v -> {
            dialog.dismiss();
            showVitalHistory(entry);
        });
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            int next;
            try { next = Integer.parseInt(value.getText().toString().trim()); }
            catch (NumberFormatException invalid) { next = -1; }
            if (next < min || next > max) { value.setError("请输入 " + min + "–" + max + " 之间的整数"); return; }
            try { saveVital(dialog, entry, new JSONObject().put("start", reading.optLong("start")).put("value", next), 0, error::setText); }
            catch (Exception invalid) { error.setText("读数无效，未保存"); }
        });
    }

    private void showVitalHistory(JSONObject entry) {
        int request = ++historyRequest;
        listStatus.setText("正在读取原始记录与备份…");
        query(() -> history.history(entry), data -> {
            if (request != historyRequest) return;
            listStatus.setText("");
            String[] labels = new String[data.length()];
            for (int i = 0; i < labels.length; i++) {
                JSONObject item = data.optJSONObject(i);
                long savedAt = item.optLong("saved_at");
                labels[i] = (item.optBoolean("original") ? "原始记录（只读）"
                        : (item.optBoolean("local") ? "助手本地历史" : "备份") + (savedAt > 0 ? " · " + stamp(savedAt / 1000) : ""))
                        + "\n" + description(item.optJSONObject("snapshot"));
            }
            secure(new AlertDialog.Builder(this).setTitle("原始记录与备份").setItems(labels, (d, index) -> {
                JSONObject version = data.optJSONObject(index);
                JSONObject next = version.optJSONObject("snapshot");
                long restoreSeq = version.optLong("restore_seq");
                String prompt = description(next) + "\n恢复时会备份当前值并写回小米运动健康。";
                AlertDialog confirm = new AlertDialog.Builder(this).setTitle("恢复此版本？").setMessage(prompt)
                        .setNegativeButton("取消", null).setPositiveButton("备份并恢复", null).create();
                secure(confirm);
                confirm.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v ->
                        saveVital(confirm, entry, next, restoreSeq, message -> confirm.setMessage(prompt + "\n\n" + message)));
            }).setNegativeButton("返回编辑", (d, which) -> editVital(entry)).create());
        }, message -> { if (request == historyRequest) listStatus.setText(message); });
    }

    private void saveVital(AlertDialog dialog, JSONObject entry, JSONObject next, long restoreSeq, Consumer<String> error) {
        setSaving(dialog, true);
        error.accept("正在保存…");
        rememberListPosition();
        query(() -> history.save(entry, next, restoreSeq), changed -> {
            dialog.dismiss();
            if (changed) {
                listStatus.setText("已处理修改，正在核对运动健康副本…");
                awaitStressCopy(40);
            } else { saved(changed); loadList(); }
        }, message -> {
            error.accept(message);
            setSaving(dialog, false);
        });
    }

    private void awaitStressCopy(int remaining) {
        query(history::stressCopyPending, pending -> {
            if (!pending) {
                listStatus.setText("记录已更新，上一版本已备份");
                loadList();
            } else if (remaining > 0) {
                uiHandler.postDelayed(() -> awaitStressCopy(remaining - 1), 3000);
            } else {
                listStatus.setText("已写回运动健康，助手副本尚未更新。请到设置查看同步状态。");
            }
        }, message -> listStatus.setText("暂时无法核对助手副本。请查看同步状态后刷新记录。"));
    }

    private void setSaving(AlertDialog dialog, boolean saving) {
        dialog.setCancelable(!saving);
        for (int id : new int[]{AlertDialog.BUTTON_POSITIVE, AlertDialog.BUTTON_NEGATIVE, AlertDialog.BUTTON_NEUTRAL}) {
            Button button = dialog.getButton(id);
            if (button != null) button.setEnabled(!saving);
        }
    }

    private void saved(boolean changed) {
        listStatus.setText(changed ? "已保存，上一版本已备份" : "记录没有改动，未新增备份");
    }

    private <T> void query(Callable<T> call, Consumer<T> ok, Consumer<String> error) {
        worker.execute(() -> {
            T data = null;
            String failure = null;
            try { data = call.call(); }
            catch (IllegalArgumentException | IllegalStateException known) {
                String message = known.getMessage();
                // Only public validation messages are suitable for the UI, not raw DB errors.
                failure = message != null && message.matches("[一-龯0-9 ，。；：、（）～–-]+") ? message : "本机健康记录暂不可读或未能保存，请重试";
            } catch (Exception notReadable) { failure = "本机健康记录暂不可读或未能保存，请重试"; }
            T result = data;
            String message = failure;
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) return;
                if (message == null) ok.accept(result); else error.accept(message);
            });
        });
    }

    private boolean isSleep() { return "sleep".equals(kind); }
    private boolean isStress() { return "stress".equals(kind); }
    private String stamp(long epoch) { return STAMP.format(local(epoch)); }
    private String description(JSONObject reading) {
        return isSleep() ? stamp(reading.optLong("start")) + " → " + stamp(reading.optLong("end"))
                : reading.optInt("value") + unit + " · " + stamp(reading.optLong("start"));
    }
    private void secure(AlertDialog dialog) {
        if (dialog.getWindow() != null) dialog.getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        dialog.show();
    }
    private int dp(float size) { return Math.round(getResources().getDisplayMetrics().density * size); }
    private LinearLayout column() { LinearLayout result = new LinearLayout(this); result.setOrientation(LinearLayout.VERTICAL); return result; }
    private TextView text(CharSequence value, int size, int color, boolean bold) {
        TextView label = new TextView(this);
        label.setText(value); label.setTextSize(size); label.setTextColor(color);
        if (bold) label.setTypeface(null, Typeface.BOLD);
        return label;
    }
    private Button button(String name, Runnable action) {
        Button button = new Button(this, null, android.R.attr.borderlessButtonStyle);
        button.setText(name); button.setTextColor(ACCENT); button.setAllCaps(false);
        button.setMinimumHeight(dp(48)); button.setMinWidth(0); button.setMinimumWidth(0);
        button.setPadding(dp(5), 0, dp(5), 0);
        button.setOnClickListener(v -> action.run());
        return button;
    }
    private void bar(LinearLayout parent, View... children) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(android.view.Gravity.CENTER_VERTICAL);
        for (int i = 0; i < children.length; i++) {
            // First label or back-button has a natural width. Remaining options share space.
            row.addView(children[i], i == 0 && children.length == 3 || parent == browse && i == 0
                    ? new LinearLayout.LayoutParams(-2, -2) : new LinearLayout.LayoutParams(0, -2, 1));
        }
        parent.addView(row);
    }
    @Override public void onBackPressed() {
        if (sleep == null) super.onBackPressed();
        else if (!sleep.onBack()) backToList();
    }
    @Override protected void onDestroy() {
        uiHandler.removeCallbacksAndMessages(null);
        worker.shutdown();
        super.onDestroy();
    }

    private final class Rows extends BaseAdapter {
        private JSONArray data = new JSONArray();
        void set(JSONArray next) { data = next; notifyDataSetChanged(); }
        @Override public int getCount() { return data.length(); }
        @Override public JSONObject getItem(int position) { return data.optJSONObject(position); }
        @Override public long getItemId(int position) { return position; }
        @Override public View getView(int position, View recycled, ViewGroup parent) {
            LinearLayout row;
            if (recycled instanceof LinearLayout && recycled.getTag() == this) row = (LinearLayout)recycled;
            else {
                row = column();
                row.setTag(this);
                row.setMinimumHeight(dp(58)); row.setPadding(dp(12), dp(9), dp(12), dp(9));
                row.addView(text("", 17, INK, true)); row.addView(text("", 14, MUTED, false));
            }
            JSONObject item = getItem(position);
            JSONObject current = item.optJSONObject("current");
            ((TextView)row.getChildAt(0)).setText(isSleep() ? description(current) : current.optInt("value") + unit);
            String action = isSleep() ? "查看整晚" : stamp(current.optLong("start")) + " · 编辑读数";
            ((TextView)row.getChildAt(1)).setText(action + (item.optBoolean("edited") ? " · 已手动调整" : ""));
            return row;
        }
    }
}

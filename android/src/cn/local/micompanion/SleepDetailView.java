package cn.local.micompanion;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.res.ColorStateList;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.ResolverStyle;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;
import java.util.function.LongFunction;

final class SleepDetailView extends LinearLayout {
    interface Host {
        void readings(long from, long until, Consumer<JSONObject> ok, Consumer<String> error);
        void history(JSONObject entry, Consumer<JSONArray> ok, Consumer<String> error);
        void save(JSONObject entry, JSONObject next, long restoreSeq, Consumer<Boolean> ok, Consumer<String> error);
        void back();
    }

    private static final int APP = 0xfff1f4fb, INK = 0xff172743, MUTED = 0xff4e5f7b,
            BLUE = 0xff2c4db5, PALE = 0xffe9efff, PANEL = 0xfff4f7fc, WHITE = 0xffffffff;
    private static final String[] KINDS = {"light", "deep", "rem", "awake", "unknown"};
    private static final String[] LABELS = {"浅睡", "深睡", "快速眼动", "清醒", "未知阶段"};
    private static final int[] COLORS = {0xff8ea8f9, 0xff5559b8, 0xffae81e0, 0xffe7a962, 0xff8996a9};
    private static final String[] METRICS = {"spo2", "stress", "heart_rate"};
    private static final String[] UNITS = {"%", "", " 次/分"};
    private static final long MAX_SLEEP_SECONDS = 48 * 60 * 60L, MAX_EPOCH = 253402300799L;
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm:ss", Locale.ROOT)
            .withResolverStyle(ResolverStyle.STRICT);
    private static final DateTimeFormatter TICK = DateTimeFormatter.ofPattern("MM-dd HH:mm:ss", Locale.ROOT);

    private final Activity activity;
    private final Host host;
    private final JSONObject entry;
    private final ZoneId zone = ZoneId.systemDefault();
    private final TextView title, recordTime, hint, axisStart, axisEnd, selection, notice, periodWindow, error;
    private final TextView[] values = new TextView[METRICS.length];
    private final Button back, edit, whole, stageStart, stageEnd, stageKind, rangeToggle, nightStart, nightEnd,
            readingsRetry, historyToggle, save;
    private final LinearLayout chips, stageEditor, nightEditor, rangeEditor, historyCard, historyBody, actions;
    private final SeekBar left, right;
    private final Track track;

    private final List<Stage> stages = new ArrayList<>();
    private List<Stage> visible = new ArrayList<>();
    private JSONObject current;
    private Stage selected;
    private long from, until;
    private boolean editing, saving, adjustingNight, showHistory, invalid;
    private long readingRequest, historyRequest, loadedFrom, loadedUntil, requestedFrom, requestedUntil;
    private JSONObject readings;
    private JSONArray history;
    private boolean readingPending, historyPending;
    private String readingError, historyError;

    SleepDetailView(Activity activity, JSONObject entry, Host host) {
        super(activity);
        this.activity = activity;
        this.entry = entry;
        this.host = host;
        setOrientation(VERTICAL);
        setBackgroundColor(APP);
        setPadding(dp(13), dp(8), dp(13), dp(8));

        boolean compact = getResources().getConfiguration().screenWidthDp
                / getResources().getConfiguration().fontScale < 325;
        LinearLayout toolbar = compact ? column() : row(this);
        toolbar.setGravity(Gravity.CENTER_VERTICAL);
        if (compact) addView(toolbar, new LayoutParams(-1, -2));
        LinearLayout toolbarButtons = compact ? new LinearLayout(activity) : toolbar;
        back = button(toolbarButtons, "‹ 睡眠记录", false, () -> { if (!onBack()) host.back(); });
        title = text(toolbar, "睡眠详情", 23, INK, true);
        if (compact) {
            toolbar.addView(toolbarButtons, new LayoutParams(-1, -2));
        } else {
            title.setGravity(Gravity.CENTER);
            title.setLayoutParams(new LayoutParams(0, -2, 1));
        }
        title.setAccessibilityHeading(true);
        edit = button(toolbarButtons, "编辑分段", false, () -> changeMode(true));
        if (compact) {
            back.setLayoutParams(new LayoutParams(0, -2, 1));
            edit.setLayoutParams(new LayoutParams(0, -2, 1));
        }

        ScrollView scroll = new ScrollView(activity);
        scroll.setFillViewport(true);
        addView(scroll, new LayoutParams(-1, 0, 1));
        LinearLayout body = column();
        scroll.addView(body, new ScrollView.LayoutParams(-1, -2));
        recordTime = text(body, "", 14, MUTED, false);
        LinearLayout timeline = card(body);
        heading(timeline, "睡眠分段");
        hint = text(timeline, "", 14, MUTED, false);
        whole = button(timeline, "整晚", false, () -> select(null));
        HorizontalScrollView picker = new HorizontalScrollView(activity);
        picker.setHorizontalScrollBarEnabled(false);
        timeline.addView(picker, new LayoutParams(-1, -2));
        chips = new LinearLayout(activity);
        picker.addView(chips, new HorizontalScrollView.LayoutParams(-2, -2));
        track = new Track();
        timeline.addView(track, new LayoutParams(-1, dp(28)));
        LinearLayout ticks = row(timeline);
        axisStart = text(ticks, "", 12, MUTED, false);
        axisStart.setLayoutParams(new LayoutParams(0, -2, 1));
        axisEnd = text(ticks, "", 12, MUTED, false);
        axisEnd.setLayoutParams(new LayoutParams(0, -2, 1));
        axisEnd.setGravity(Gravity.END);
        selection = text(timeline, "", 15, BLUE, true);
        selection.setGravity(Gravity.CENTER);
        stageEditor = column();
        timeline.addView(stageEditor);
        stageStart = button(stageEditor, "", false, () -> editStageTime(true));
        left = slider(stageEditor, true);
        stageEnd = button(stageEditor, "", false, () -> editStageTime(false));
        right = slider(stageEditor, false);
        stageKind = button(stageEditor, "", false, this::chooseKind);
        notice = text(timeline, "", 13, MUTED, false);

        LinearLayout period = card(body);
        heading(period, "同一时段的读数");
        periodWindow = text(period, "", 14, MUTED, false);
        readingsRetry = button(period, "重新加载读数", false, () -> { readingError = null; loadReadings(); });
        LinearLayout grid = new LinearLayout(activity);
        grid.setOrientation(compact ? VERTICAL : HORIZONTAL);
        period.addView(grid, new LayoutParams(-1, -2));
        String[] names = {"血氧", "压力", "心率"};
        for (int i = 0; i < names.length; i++) {
            LinearLayout panel = column();
            panel.setBackground(surface(PANEL, 10));
            panel.setPadding(dp(8), dp(5), dp(8), dp(5));
            LayoutParams params = compact ? new LayoutParams(-1, -2) : new LayoutParams(0, -1, 1);
            params.setMargins(0, dp(4), !compact && i < names.length - 1 ? dp(5) : 0, 0);
            grid.addView(panel, params);
            heading(panel, names[i]);
            values[i] = text(panel, "", 13, INK, false);
        }

        nightEditor = card(body);
        rangeToggle = button(nightEditor, "调整整晚起止时间", false, () -> {
            adjustingNight = !adjustingNight;
            renderMode();
        });
        rangeEditor = column();
        nightEditor.addView(rangeEditor);
        nightStart = button(rangeEditor, "", false, () -> editNightTime(true));
        nightEnd = button(rangeEditor, "", false, () -> editNightTime(false));
        text(rangeEditor, "整晚最长 48 小时。缩短会裁剪超出范围的分段；拉长不会填入睡眠阶段。", 13, MUTED, false);
        text(nightEditor, "保存前备份当前版本，原始记录只读。", 13, MUTED, false);

        historyCard = card(body);
        historyToggle = button(historyCard, "", false, () -> {
            showHistory = !showHistory;
            renderHistory();
            if (showHistory) loadHistory();
        });
        historyBody = column();
        historyCard.addView(historyBody);

        error = text(this, "", 14, 0xffa1282d, true);
        error.setAccessibilityLiveRegion(ACCESSIBILITY_LIVE_REGION_POLITE);
        error.setVisibility(GONE);
        actions = row(this);
        button(actions, "取消", false, () -> onBack());
        save = button(actions, "备份并保存", true, this::saveDraft);
        LayoutParams fill = new LayoutParams(0, -2, 1);
        fill.leftMargin = dp(8);
        save.setLayoutParams(fill);
        changeMode(false);
    }

    boolean onBack() {
        if (saving) return true;
        if (!editing) return false;
        changeMode(false);
        return true;
    }

    @Override protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        loadReadings();
        if (showHistory && !editing) loadHistory();
    }

    @Override protected void onDetachedFromWindow() {
        ++readingRequest;
        ++historyRequest;
        readingPending = historyPending = false;
        super.onDetachedFromWindow();
    }

    private void changeMode(boolean enable) {
        if (saving) return;
        Stage prior = selected;
        current = entry.optJSONObject("current");
        from = start(current);
        until = end(current);
        invalid = !validRange(from, until);
        stages.clear();
        JSONArray items = current == null ? null : current.optJSONArray("stages");
        if (current != null && current.has("stages") && items == null) invalid = true;
        if (items != null) for (int i = 0; i < items.length(); i++) {
            JSONObject item = items.optJSONObject(i);
            if (!validRange(start(item), end(item))) { invalid = true; continue; }
            stages.add(new Stage(i, item));
        }
        editing = enable && !invalid;
        if (editing) showHistory = false;
        adjustingNight = false;
        selected = null;
        if (editing) {
            for (Stage stage : stages) if (stage.to(until) > stage.from(from)) {
                if (selected == null || (prior != null && stage.number == prior.number)) selected = stage;
            }
        }
        showError(null);
        renderMode();
        renderTimeline();
        renderHistory();
    }

    private void renderMode() {
        String mode = editing ? "编辑睡眠" : "睡眠详情";
        title.setText(mode);
        setAccessibilityPaneTitle(mode);
        back.setText(editing ? "‹ 详情" : "‹ 睡眠记录");
        edit.setVisibility(editing ? GONE : VISIBLE);
        edit.setEnabled(!invalid && !saving);
        hint.setText(editing ? "可调整既有分段的时间和类型，整晚起止只读。保存前会备份，再写回小米运动健康。" : "选择整晚或某一段，查看对应时段的读数。");
        historyCard.setVisibility(editing ? GONE : VISIBLE);
        nightEditor.setVisibility(GONE);
        rangeEditor.setVisibility(adjustingNight ? VISIBLE : GONE);
        rangeToggle.setText(adjustingNight ? "收起整晚起止时间" : "调整整晚起止时间");
        actions.setVisibility(editing ? VISIBLE : GONE);
    }

    private void select(Stage stage) {
        selected = stage;
        renderTimeline();
    }

    private void renderTimeline() {
        boolean valid = validRange(from, until);
        List<Stage> next = new ArrayList<>();
        boolean clipped = false, overlap = false;
        if (valid) for (Stage stage : stages) {
            long a = stage.from(from), b = stage.to(until);
            if (a < b) next.add(stage);
            clipped |= a != stage.start || b != stage.end;
        }
        next.sort(Comparator.comparingLong(s -> s.start));
        long previousEnd = from;
        for (Stage stage : next) {
            overlap |= stage.from(from) < previousEnd;
            previousEnd = Math.max(previousEnd, stage.to(until));
        }
        if (!next.contains(selected)) selected = null;
        if (!visible.equals(next)) {
            chips.removeAllViews();
            for (Stage stage : next) button(chips, "", false, () -> select(stage));
        }
        visible = next;
        for (int i = 0; i < visible.size(); i++) {
            Stage stage = visible.get(i);
            Button chip = (Button) chips.getChildAt(i);
            String label = (i + 1) + " " + stage.label();
            chip.setText(label);
            chip.setContentDescription(label + "，" + interval(stage.from(from), stage.to(until)));
            chip.setSelected(stage == selected);
        }
        whole.setSelected(selected == null);
        track.setVisibility(visible.isEmpty() ? GONE : VISIBLE);
        track.invalidate();
        axisStart.setText(visible.isEmpty() ? "" : tick(from));
        axisEnd.setText(visible.isEmpty() ? "" : tick(until));
        long a = selected == null ? from : selected.from(from), b = selected == null ? until : selected.to(until);
        String label = selected == null ? "整晚" : selected.label();
        selection.setText(!valid ? "" : label + " · " + duration(b - a));
        visibleText(notice, invalid ? "记录含无法识别的时间或分段，不能编辑。"
                : (visible.isEmpty() ? "这段时间没有睡眠分段。" : "")
                + (clipped && editing ? "保存会裁剪范围外的分段，原始记录仍保留。" : "")
                + (overlap ? "源分段有重叠，不会自动合并或删除。" : ""));
        stageEditor.setVisibility(editing && selected != null ? VISIBLE : GONE);
        if (editing && selected != null) {
            syncSlider(left, true, a);
            syncSlider(right, false, b);
            stageStart.setText("所选分段起点 · " + stamp(a));
            stageEnd.setText("所选分段终点 · " + stamp(b));
            stageKind.setText("睡眠阶段 · " + label);
        }
        recordTime.setText(validRange(start(current), end(current))
                ? "当前：" + interval(start(current), end(current)) + " · " + duration(end(current) - start(current))
                : "当前记录没有有效的整晚时间");
        if (valid) {
            nightStart.setText("入睡时间 · " + stamp(from));
            nightEnd.setText("醒来时间 · " + stamp(until));
        }
        loadReadings();
        renderReadings();
    }

    private SeekBar slider(LinearLayout parent, boolean startEdge) {
        SeekBar bar = new SeekBar(activity);
        bar.setPadding(dp(18), 0, dp(18), 0);
        bar.setThumbTintList(ColorStateList.valueOf(BLUE));
        bar.setProgressTintList(ColorStateList.valueOf(BLUE));
        bar.setLayoutDirection(LAYOUT_DIRECTION_LTR);
        parent.addView(bar, new LayoutParams(-1, dp(48)));
        bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar slider, int progress, boolean user) {
                if (!user || !editing || selected == null || saving) return;
                moveEdge(startEdge, from + progress);
            }
            @Override public void onStartTrackingTouch(SeekBar slider) {
                slider.getParent().requestDisallowInterceptTouchEvent(true);
            }
            @Override public void onStopTrackingTouch(SeekBar slider) {
                slider.getParent().requestDisallowInterceptTouchEvent(false);
            }
        });
        return bar;
    }

    private void syncSlider(SeekBar bar, boolean startEdge, long time) {
        bar.setMax((int) (until - from));
        bar.setProgress((int) (time - from));
        bar.setKeyProgressIncrement(1);
        bar.setContentDescription((startEdge ? "所选分段起点，" : "所选分段终点，") + stamp(time));
    }

    private static long edgeLimit(List<Stage> visible, Stage selected, long from, long until, boolean startEdge) {
        int index = visible.indexOf(selected);
        // Existing source overlaps stay visible. Allow shortening, but never widen an overlap.
        if (startEdge) {
            long limit = from;
            for (int i = 0; i < index; i++) limit = Math.max(limit, visible.get(i).to(until));
            return Math.min(limit, selected.from(from));
        }
        long end = selected.to(until);
        for (int i = 0; i < index; i++) if (visible.get(i).to(until) > end) return end;
        long limit = index + 1 < visible.size() ? visible.get(index + 1).from(from) : until;
        return Math.max(limit, end);
    }

    private void moveEdge(boolean startEdge, long target) {
        long a = selected.from(from), b = selected.to(until), limit = edgeLimit(visible, selected, from, until, startEdge);
        long time = startEdge ? Math.max(limit, Math.min(target, b - 1)) : Math.min(limit, Math.max(target, a + 1));
        if (startEdge && time != a) selected.start = time;
        if (!startEdge && time != b) selected.end = time;
        renderTimeline();
    }

    private void editStageTime(boolean startEdge) {
        if (saving || selected == null) return;
        long a = selected.from(from), b = selected.to(until);
        editTime(startEdge ? "所选分段起点" : "所选分段终点", startEdge ? a : b, time -> {
            long limit = edgeLimit(visible, selected, from, until, startEdge);
            if (startEdge ? time < limit || time >= b : time > limit || time <= a)
                return "边界必须在相邻分段之间，且起点早于终点。";
            moveEdge(startEdge, time);
            return null;
        });
    }

    private void editNightTime(boolean startEdge) {
        if (saving) return;
        editTime(startEdge ? "入睡时间" : "醒来时间", startEdge ? from : until, time -> {
            long nextFrom = startEdge ? time : from, nextUntil = startEdge ? until : time;
            if (nextFrom <= 0 || nextUntil > MAX_EPOCH) return "时间超出可保存范围。";
            if (nextUntil <= nextFrom) return "醒来时间必须晚于入睡时间。跨午夜请选择次日。";
            if (nextUntil - nextFrom > MAX_SLEEP_SECONDS) return "整晚时段不能超过 48 小时。";
            from = nextFrom;
            until = nextUntil;
            renderTimeline();
            return null;
        });
    }

    private void editTime(String label, long epoch, LongFunction<String> changed) {
        ZonedDateTime before = at(epoch);
        LinearLayout content = column();
        content.setPadding(dp(20), 0, dp(20), 0);
        TextView format = text(content, "格式：年-月-日 时:分:秒", 14, MUTED, false);
        EditText input = new EditText(activity);
        input.setId(View.generateViewId());
        format.setLabelFor(input.getId());
        input.setSingleLine(true);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        input.setImportantForAutofill(IMPORTANT_FOR_AUTOFILL_NO);
        input.setSaveEnabled(false);
        input.setText(DATE.format(before));
        content.addView(input);
        AlertDialog dialog = new AlertDialog.Builder(activity).setTitle(label).setView(content)
                .setNegativeButton("取消", null).setPositiveButton("确定", null).create();
        showDialog(dialog);
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String message;
            try {
                LocalDateTime local = LocalDateTime.parse(input.getText().toString().trim(), DATE);
                ZonedDateTime result = ZonedDateTime.ofLocal(local, zone, before.getOffset());
                message = local.equals(result.toLocalDateTime()) ? changed.apply(result.toEpochSecond())
                        : "该当地时间因时钟切换不存在，请换一个时间。";
            } catch (java.time.DateTimeException ex) {
                message = "请输入有效时间，例如 2026-11-21 06:45:30。";
            }
            if (message == null) dialog.dismiss(); else input.setError(message);
        });
    }

    private void chooseKind() {
        if (saving || selected == null) return;
        Stage stage = selected;
        int kind = kindIndex(stage.kind);
        String[] choices = kind == KINDS.length - 1 ? LABELS : Arrays.copyOf(LABELS, LABELS.length - 1);
        showDialog(new AlertDialog.Builder(activity).setTitle("睡眠阶段")
                .setSingleChoiceItems(choices, kind, (dialog, which) -> {
                    if (kind != which) stage.kind = KINDS[which];
                    dialog.dismiss();
                    renderTimeline();
                }).setNegativeButton("取消", null).create());
    }

    private void showDialog(AlertDialog dialog) {
        int secure = activity.getWindow().getAttributes().flags & WindowManager.LayoutParams.FLAG_SECURE;
        if (secure != 0 && dialog.getWindow() != null) dialog.getWindow().addFlags(secure);
        dialog.show();
    }

    private boolean cacheCovers(long a, long b) {
        return readings != null && loadedFrom <= a && loadedUntil >= b;
    }

    private void loadReadings() {
        if (!isAttachedToWindow() || !validRange(from, until)) return;
        final long a = from, b = until;
        boolean requestCovers = requestedFrom <= a && requestedUntil >= b;
        if (cacheCovers(a, b)) {
            // A late response for a discarded draft must not evict useful cached data.
            if (readingPending && !requestCovers) {
                ++readingRequest;
                readingPending = false;
            }
            return;
        }
        if ((readingPending || readingError != null) && requestCovers) return;
        requestedFrom = a;
        requestedUntil = b;
        readingPending = true;
        readingError = null;
        long request = ++readingRequest;
        host.readings(a, b, data -> {
            if (request != readingRequest) return;
            readings = data;
            loadedFrom = a;
            loadedUntil = b;
            readingPending = false;
            if (data == null) readingError = "未返回读数。";
            renderReadings();
        }, problem -> {
            if (request != readingRequest) return;
            readingPending = false;
            readingError = failure(problem, "无法加载读数。");
            renderReadings();
        });
        renderReadings();
    }

    private void renderReadings() {
        long a = selected == null ? from : selected.from(from), b = selected == null ? until : selected.to(until);
        boolean valid = validRange(a, b), ready = valid && cacheCovers(a, b);
        boolean failed = valid && !ready && readingError != null;
        readingsRetry.setVisibility(failed ? VISIBLE : GONE);
        periodWindow.setText(valid ? (selected == null ? "整晚" : selected.label()) + " · " + interval(a, b)
                + (failed ? "\n" + readingError : "") : "请选择有效时间段。");
        for (int i = 0; i < values.length; i++) {
            String display = !valid ? "—" : failed ? "未加载" : "加载中…";
            if (ready) {
                JSONArray items = readings.optJSONArray(METRICS[i]);
                display = items == null ? "读数不可用" : readingsIn(items, a, b, UNITS[i], this::tick);
            }
            textIfChanged(values[i], display);
        }
    }

    private static String readingsIn(JSONArray items, long a, long b, String unit, LongFunction<String> timestamp) {
        StringBuilder display = new StringBuilder();
        for (int j = 0; j < items.length(); j++) {
            JSONObject item = items.optJSONObject(j);
            JSONObject point = item == null ? null : item.optJSONObject("current");
            long time = start(point);
            if (point == null || time < a || time >= b || !(point.opt("value") instanceof Number)) continue;
            if (display.length() != 0) display.append('\n');
            display.append(timestamp.apply(time)).append(" · ").append(point.optInt("value")).append(unit);
        }
        return display.length() == 0 ? "无读数" : display.toString();
    }

    private void loadHistory() {
        if (!isAttachedToWindow() || historyPending || history != null) return;
        historyPending = true;
        historyError = null;
        renderHistory();
        long request = ++historyRequest;
        host.history(entry, versions -> {
            if (request != historyRequest) return;
            history = versions;
            historyPending = false;
            if (versions == null) historyError = "未返回历史。";
            renderHistory();
        }, problem -> {
            if (request != historyRequest) return;
            historyPending = false;
            historyError = failure(problem, "无法加载历史。");
            renderHistory();
        });
    }

    private void renderHistory() {
        int backups = 0;
        if (history != null) for (int i = 0; i < history.length(); i++) {
            JSONObject row = history.optJSONObject(i);
            if (row != null && !row.optBoolean("original")) backups++;
        }
        historyToggle.setText((showHistory ? "收起" : "展开") + "原始记录与备份"
                + (history == null ? "" : " · " + backups + " 份备份"));
        historyBody.setVisibility(showHistory ? VISIBLE : GONE);
        historyBody.removeAllViews();
        if (!showHistory) return;
        if (historyPending) text(historyBody, "正在加载备份…", 14, MUTED, false);
        if (historyError != null) {
            text(historyBody, historyError, 14, MUTED, false);
            button(historyBody, "重新加载历史", false, this::loadHistory);
        }
        JSONObject original = entry.optJSONObject("original");
        long originalSeq = 0;
        if (history != null) for (int i = 0; i < history.length(); i++) {
            JSONObject version = history.optJSONObject(i);
            JSONObject snapshot = version == null ? null : version.optJSONObject("snapshot");
            if (snapshot == null) continue;
            if (version.optBoolean("original")) { original = snapshot; originalSeq = version.optLong("restore_seq"); }
        }
        if (original != null) version("原始记录（只读）", original, originalSeq);
        if (history != null) for (int i = 0; i < history.length(); i++) {
            JSONObject row = history.optJSONObject(i);
            if (row == null || row.optBoolean("original")) continue;
            JSONObject snapshot = row.optJSONObject("snapshot");
            if (snapshot == null) continue;
            long saved = row.optLong("saved_at");
            version((row.optBoolean("local") ? "助手本地历史" : "备份") + (saved > 0 ? " · " + stamp(saved / 1000) : ""), snapshot, row.optLong("restore_seq"));
        }
        text(historyBody, "恢复会先备份当前版本，再写回小米运动健康。", 13, MUTED, false);
    }

    private void version(String label, JSONObject snapshot, long restoreSeq) {
        long a = start(snapshot), b = end(snapshot);
        if (!validRange(a, b)) return;
        heading(historyBody, label + " · " + duration(b - a));
        StringBuilder detail = new StringBuilder(interval(a, b));
        JSONArray items = snapshot.optJSONArray("stages");
        if (items != null) for (int i = 0; i < items.length(); i++) {
            JSONObject s = items.optJSONObject(i);
            if (validRange(start(s), end(s))) detail.append('\n').append(interval(start(s), end(s)))
                    .append(" · ").append(LABELS[kindIndex(s.optString("kind"))]);
        }
        text(historyBody, detail.toString(), 13, MUTED, false);
        if (current != null && (restoreSeq > 0 || !current.toString().equals(snapshot.toString()))) {
            Button restore = button(historyBody, "恢复", false, () -> {
                if (saving) return;
                showDialog(new AlertDialog.Builder(activity).setTitle("恢复" + label + "？")
                        .setMessage("恢复前会备份当前版本，再写回小米运动健康。")
                        .setPositiveButton("恢复", (d, w) -> commit(snapshot, restoreSeq))
                        .setNegativeButton("取消", null).create());
            });
            restore.setContentDescription("恢复" + label);
        }
    }

    private void saveDraft() {
        if (saving || !editing) return;
        try {
            commit(snapshot(current, stages, from, until));
        } catch (JSONException ex) {
            showError("无法准备睡眠记录，未保存任何改动。");
        }
    }

    private static JSONObject snapshot(JSONObject current, List<Stage> stages, long from, long until) throws JSONException {
        JSONObject next = new JSONObject(current.toString()).put("start", from).put("end", until);
        JSONArray items = new JSONArray();
        for (Stage stage : stages) {
            long a = stage.from(from), b = stage.to(until);
            if (a >= b) continue;
            JSONObject item = new JSONObject(stage.source.toString()).put("start", a).put("end", b);
            if (!stage.kind.equals(stage.source.optString("kind"))) item.put("kind", stage.kind);
            items.put(item);
        }
        if (current.has("stages") || items.length() > 0) next.put("stages", items);
        return next;
    }

    private void commit(JSONObject next) { commit(next, 0); }

    private void commit(JSONObject next, long restoreSeq) {
        if (saving) return;
        saving = true;
        save.setEnabled(false);
        showError("正在保存…");
        host.save(entry, next, restoreSeq, changed -> {
            saving = false;
            save.setEnabled(true);
            showError(null);
            if (isAttachedToWindow()) host.back();
        }, problem -> {
            saving = false;
            save.setEnabled(true);
            showError(failure(problem, "保存失败，请重试。"));
        });
    }

    private static final class Stage {
        final JSONObject source;
        final int number;
        long start, end;
        String kind;
        Stage(int number, JSONObject source) {
            this.number = number;
            this.source = source;
            start = start(source);
            end = end(source);
            kind = source.optString("kind");
        }
        long from(long nightStart) { return Math.max(start, nightStart); }
        long to(long nightEnd) { return Math.min(end, nightEnd); }
        String label() { return LABELS[kindIndex(kind)]; }
    }

    private final class Track extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        Track() {
            super(activity);
            setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        }
        @Override protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            if (until <= from) return;
            float offset = dp(18), width = Math.max(0, getWidth() - 2 * offset);
            float top = dp(4), bottom = getHeight() - dp(4);
            paint.setColor(0xffe1e7f3);
            canvas.drawRoundRect(offset, top, offset + width, bottom, dp(6), dp(6), paint);
            for (Stage stage : visible) {
                float x = offset + width * (stage.from(from) - from) / (until - from);
                float y = offset + width * (stage.to(until) - from) / (until - from);
                paint.setColor(COLORS[kindIndex(stage.kind)]);
                canvas.drawRoundRect(x, top, y, bottom, dp(3), dp(3), paint);
                if (stage == selected) {
                    paint.setColor(INK);
                    paint.setStyle(Paint.Style.STROKE);
                    paint.setStrokeWidth(dp(2));
                    canvas.drawRoundRect(x, top, y, bottom, dp(3), dp(3), paint);
                    paint.setStyle(Paint.Style.FILL);
                }
            }
        }
    }

    private static long start(JSONObject object) { return object == null ? 0 : object.optLong("start"); }
    private static long end(JSONObject object) { return object == null ? 0 : object.optLong("end"); }
    private static boolean validRange(long a, long b) {
        return a > 0 && b > a && b <= MAX_EPOCH && b - a <= MAX_SLEEP_SECONDS;
    }
    private static int kindIndex(String kind) {
        for (int i = 0; i < KINDS.length - 1; i++) if (KINDS[i].equals(kind)) return i;
        return KINDS.length - 1;
    }
    private ZonedDateTime at(long epoch) { return Instant.ofEpochSecond(epoch).atZone(zone); }
    private String stamp(long epoch) {
        ZonedDateTime time = at(epoch);
        return DATE.format(time) + (zone.getRules().getValidOffsets(time.toLocalDateTime()).size() > 1
                ? " " + time.getOffset() : "");
    }
    private String tick(long epoch) { return TICK.format(at(epoch)); }
    private String interval(long a, long b) { return stamp(a) + " → " + stamp(b); }
    private static String duration(long seconds) {
        String base = seconds >= 3600 ? seconds / 3600 + " 小时 " : "";
        if (seconds >= 60) base += seconds / 60 % 60 + " 分钟";
        if (seconds % 60 != 0) base += (seconds >= 60 ? " " : "") + seconds % 60 + " 秒";
        return base;
    }
    private static String failure(String message, String fallback) {
        return message == null || message.isEmpty() ? fallback : message;
    }
    private static void textIfChanged(TextView view, String message) {
        if (!message.contentEquals(view.getText())) view.setText(message);
    }
    private void showError(String message) { visibleText(error, message == null ? "" : message); }
    private static void visibleText(TextView view, String message) {
        textIfChanged(view, message);
        view.setVisibility(message.isEmpty() ? GONE : VISIBLE);
    }
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
    private LinearLayout column() {
        LinearLayout column = new LinearLayout(activity);
        column.setOrientation(VERTICAL);
        return column;
    }
    private LinearLayout row(LinearLayout parent) {
        LinearLayout row = new LinearLayout(activity);
        parent.addView(row, new LayoutParams(-1, -2));
        return row;
    }
    private LinearLayout card(LinearLayout parent) {
        LinearLayout card = column();
        GradientDrawable background = surface(WHITE, 16);
        background.setStroke(dp(1), 0xffdce4f1);
        card.setBackground(background);
        card.setPadding(dp(11), dp(9), dp(11), dp(9));
        LayoutParams params = new LayoutParams(-1, -2);
        params.topMargin = dp(8);
        parent.addView(card, params);
        return card;
    }
    private GradientDrawable surface(int color, int radius) {
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(color);
        shape.setCornerRadius(dp(radius));
        return shape;
    }
    private TextView text(LinearLayout parent, String label, int size, int color, boolean bold) {
        TextView view = new TextView(activity);
        view.setText(label);
        view.setTextSize(size);
        view.setTextColor(color);
        view.setPadding(0, dp(3), 0, dp(3));
        if (bold) view.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        parent.addView(view);
        return view;
    }
    private void heading(LinearLayout parent, String label) {
        text(parent, label, 15, INK, true).setAccessibilityHeading(true);
    }
    private Button button(LinearLayout parent, String label, boolean primary, Runnable action) {
        Button button = new Button(activity);
        button.setText(label);
        button.setTextSize(14);
        button.setAllCaps(false);
        button.setMinHeight(dp(48));
        button.setMinWidth(dp(48));
        button.setMinimumHeight(dp(48));
        button.setMinimumWidth(dp(48));
        button.setPadding(dp(10), dp(4), dp(10), dp(4));
        button.setTextColor(new ColorStateList(new int[][]{{-android.R.attr.state_enabled},
                {android.R.attr.state_selected}, {}}, new int[]{MUTED, WHITE, primary ? WHITE : BLUE}));
        GradientDrawable background = surface(primary ? BLUE : PALE, 10);
        background.setColor(new ColorStateList(new int[][]{{-android.R.attr.state_enabled},
                {android.R.attr.state_selected}, {}}, new int[]{PANEL, BLUE, primary ? BLUE : PALE}));
        button.setBackground(new RippleDrawable(ColorStateList.valueOf(0x403184ed), background, null));
        button.setOnClickListener(v -> action.run());
        LayoutParams params = new LayoutParams(-2, -2);
        params.setMargins(0, dp(3), dp(4), dp(3));
        parent.addView(button, params);
        return button;
    }
}

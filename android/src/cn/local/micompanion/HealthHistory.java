package cn.local.micompanion;

import android.content.Context;
import android.database.sqlite.SQLiteDatabase;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.json.JSONArray;
import org.json.JSONObject;

/** Local detail/edits only. Call off the UI thread. */
final class HealthHistory {
    private static final String[] VITALS = {"spo2", "stress", "heart_rate"};
    private static final int POINT_LIMIT = 12000, SLEEP_LIMIT = 64, STAGE_LIMIT = 512;
    private static final long MAX_WINDOW = 32 * 86400L, MAX_SLEEP = 2 * 86400L, MAX_EPOCH = 253402300799L;
    private final Context context;
    private final HealthSource.Reader testSource;
    private final String testScope;
    private final Store store;
    private final StressWriter stressWriter;
    private String pendingStressScope, pendingStressOp, pendingTable;
    private static final String STRESS_HISTORY = "micompanion_stress_history";
    private static final String HEALTH_HISTORY = "micompanion_health_history";

    HealthHistory(Context context) {
        this.context = context.getApplicationContext();
        testSource = null;
        testScope = null;
        store = new HealthRevisions(this.context);
        stressWriter = this::sendStress;
    }

    HealthHistory(HealthSource.Reader source, String scope, Store store) {
        this(source, scope, store, null);
    }

    HealthHistory(HealthSource.Reader source, String scope, Store store, StressWriter stressWriter) {
        context = null;
        testSource = source;
        testScope = scope;
        this.store = store;
        this.stressWriter = stressWriter;
    }

    interface StressWriter { JSONObject write(JSONObject request) throws Exception; }

    interface Store {
        JSONArray range(String scope, String kind, long from, long until) throws Exception;
        boolean hasEdits(String scope, String kind, long from, long until) throws Exception;
        JSONObject find(String scope, String id) throws Exception;
        JSONArray history(String scope, String id) throws Exception;
        boolean save(String scope, String id, JSONObject original, JSONObject expected, JSONObject next) throws Exception;
        void archiveStress(String scope, String id, String op, JSONObject original, JSONObject anchor, String payload) throws Exception;
    }

    JSONArray list(String kind, long from, long until) throws Exception {
        Kind type = Kind.of(kind);
        window(from, until);
        try (Source source = open()) { return list(source, type, from, until); }
    }

    JSONObject readings(long from, long until) throws Exception {
        window(from, until);
        JSONObject result = new JSONObject();
        try (Source source = open()) {
            for (String kind : VITALS) result.put(kind, list(source, Kind.of(kind), from, until));
        }
        return result;
    }

    boolean hasEdits(String kind, long from, long until) throws Exception {
        Kind type = Kind.of(kind);
        window(from, until);
        try (Source source = open()) {
            if (source.scope == null || store == null) return false;
            if (store.hasEdits(source.scope, kind, from, until)) return true;
            if (type == Kind.STRESS) return source.hasTable(STRESS_HISTORY)
                    && source.reader.query("SELECT '1',0 FROM " + STRESS_HISTORY + " WHERE point_time>=? AND point_time<? LIMIT 1",
                    new String[]{Long.toString(from), Long.toString(until)}).length() > 0;
            if (!source.hasTable(HEALTH_HISTORY)) return false;
            for (JSONObject item : originals(source, type, from, until, null).values())
                if (item.optBoolean("source_written")) return true;
            return false;
        }
    }

    JSONArray history(JSONObject entry) throws Exception {
        try (Source source = open()) {
            JSONObject actual = resolve(source, entry);
            if (source.scope != null && store != null) {
                if (actual.optBoolean("source_written")) return actual.has("stress_source")
                        ? stressVersions(source, actual) : extendedVersions(source, actual);
                JSONArray saved = store.history(source.scope, actual.getString("id"));
                if (saved.length() > 0) return saved;
            }
            return new JSONArray().put(version(actual.getJSONObject("original"), 0, true));
        }
    }

    boolean save(JSONObject entry, JSONObject next) throws Exception { return save(entry, next, 0); }

    boolean save(JSONObject entry, JSONObject next, long restoreSeq) throws Exception {
        try (Source source = open()) {
            if (source.reader == null) throw new IllegalStateException(syncNeeded(true));
            JSONObject actual = resolve(source, entry);
            String id = actual.getString("id");
            Kind kind = Kind.of(id.substring(0, id.indexOf(':')));
            JSONObject current = actual.getJSONObject("current"), original = actual.getJSONObject("original");
            if (!same(current, snapshot(kind, entry.getJSONObject("current")))) {
                throw new IllegalStateException("这条记录已变更，请刷新后再编辑");
            }
            JSONObject target = snapshot(kind, next);
            if (stressWriter != null) {
                return kind == Kind.STRESS ? saveStress(source, actual, current, target)
                        : saveExtended(source, kind, actual, current, target, restoreSeq);
            }
            if (same(current, target)) return false;
            if (kind.sleep() && !same(target, original)
                    && (source.scope == null || !savedBefore(source.scope, id, target))) {
                // Derived duration alone is not editable. History restores keep the exact prior aggregate.
                if (sameSleepInterval(target, current)) return false;
                target.remove("total_minutes");
                long seconds = sleepSeconds(target.getJSONArray("stages"));
                if (seconds > 0) target.put("total_minutes", Math.round(seconds / 60.0));
            }
            if (source.scope == null) throw new IllegalStateException(syncNeeded(false));
            return store.save(source.scope, id, original, current, target);
        }
    }

    private boolean saveStress(Source source, JSONObject record, JSONObject current, JSONObject target) throws Exception {
        if (source.scope == null) throw new IllegalStateException(syncNeeded(false));
        JSONObject anchor = record.optJSONObject("stress_source");
        if (anchor == null) throw new IllegalStateException("在运动健康副本中找不到对应记录，未写回");
        long pointTime = anchor.getLong("point_time");
        if (pointTime != target.getLong("start")) throw new IllegalArgumentException("压力写回只支持修改数值");
        int expected = anchor.getInt("expected"), value = target.getInt("value");
        if (expected == value && same(current, target)) return false;
        if (stressCopyPending(source)) throw new IllegalStateException("上一条修改尚未同步到助手，请等待副本更新");
        String op = java.util.UUID.randomUUID().toString();
        String payload = record.getString("stress_payload");
        JSONObject request = new JSONObject(anchor.toString()).put("action", "stress_writeback")
                .put("scope", source.scope).put("value", value).put("op", op).put("prior_sha256", digest(payload));
        if (expected != value) {
            try {
                store.archiveStress(source.scope, record.getString("id"), op, record.getJSONObject("original"),
                        anchor, record.getString("stress_payload"));
            } catch (Exception error) {
                throw new IllegalStateException("无法独立备份原始压力记录，未写回运动健康");
            }
        }
        JSONObject reply = stressWriter.write(request);
        switch (reply.optString("status")) {
            case "ok":
                if (reply.optBoolean("changed")) {
                    pendingStressScope = source.scope;
                    pendingStressOp = op;
                    pendingTable = STRESS_HISTORY;
                    return true;
                }
                if (expected == value && !record.optBoolean("source_written"))
                    return store.save(source.scope, record.getString("id"), record.getJSONObject("original"), current, target);
                throw new IllegalStateException("无法确认写回结果，请先同步数据库再检查");
            case "conflict": throw new IllegalStateException("源记录已变更或无法唯一定位，请先同步数据库再编辑");
            case "account": throw new IllegalStateException("运动健康账号与本机副本不一致，请先同步数据库");
            case "unsupported": throw new IllegalStateException("当前运动健康数据库不支持安全写回，未修改源记录");
            case "failed": throw new IllegalStateException("运动健康未确认写回，请检查源记录后再试");
            default: throw new IllegalStateException("请更新健康模块，打开小米运动健康后再写回");
        }
    }

    private boolean saveExtended(Source source, Kind kind, JSONObject record, JSONObject current,
                                 JSONObject target, long restoreSeq) throws Exception {
        if (source.scope == null) throw new IllegalStateException(syncNeeded(false));
        JSONObject anchor = record.optJSONObject("health_source");
        if (anchor == null) throw new IllegalStateException("在运动健康副本中找不到对应记录，未写回");
        JSONObject observed = record.getJSONObject("source_current");
        if (restoreSeq == 0 && (kind.sleep() ? sameSleepInterval(observed, target) : same(observed, target))
                && same(current, target)) return false;
        if (stressCopyPending(source)) throw new IllegalStateException("上一条修改尚未同步到助手，请等待副本更新");
        String op = java.util.UUID.randomUUID().toString(), raw = record.getString("health_payload");
        JSONObject request = new JSONObject(anchor.toString()).put("action", "health_writeback").put("kind", kind.name)
                .put("scope", source.scope).put("target", target).put("op", op).put("prior_sha256", digest(raw));
        if (restoreSeq > 0) request.put("restore_seq", restoreSeq);
        try { store.archiveStress(source.scope, record.getString("id"), op, record.getJSONObject("original"), anchor, raw); }
        catch (Exception error) { throw new IllegalStateException("无法独立备份原始记录，未写回运动健康"); }
        JSONObject reply = stressWriter.write(request);
        switch (reply.optString("status")) {
            case "ok":
                if (!reply.optBoolean("changed")) {
                    if (!record.optBoolean("source_written") && !same(current, target)) {
                        return store.save(source.scope, record.getString("id"), record.getJSONObject("original"), current, observed);
                    }
                    return false;
                }
                pendingStressScope = source.scope; pendingStressOp = op; pendingTable = HEALTH_HISTORY;
                return true;
            case "conflict": throw new IllegalStateException("源记录已变更或无法唯一定位，请先同步数据库再编辑");
            case "account": throw new IllegalStateException("运动健康账号与本机副本不一致，请先同步数据库");
            case "history_diverged": throw new IllegalStateException("运动健康已重新生成这条记录，无法安全接续旧备份；未写回");
            case "unsupported": throw new IllegalStateException(kind.sleep()
                    ? "这项睡眠修改暂不支持安全写回，未修改源记录"
                    : "当前运动健康数据库不支持安全写回，未修改源记录");
            case "failed": throw new IllegalStateException("运动健康未确认写回，请检查源记录后再试");
            default: throw new IllegalStateException("请更新健康模块，打开小米运动健康后再写回");
        }
    }

    boolean stressCopyPending() throws Exception {
        if (pendingStressOp == null) return false;
        try (Source source = open()) {
            if (!pendingStressScope.equals(source.scope)) throw new IllegalStateException("当前副本账号已切换，无法核对写回结果");
            return stressCopyPending(source);
        }
    }

    private boolean stressCopyPending(Source source) throws Exception {
        if (pendingStressOp == null || !pendingStressScope.equals(source.scope)) return false;
        if (!source.hasTable(pendingTable) || source.reader.query("SELECT '1',0 FROM " + pendingTable
                + " WHERE op=? LIMIT 1", new String[]{pendingStressOp}).length() == 0) return true;
        pendingStressScope = pendingStressOp = pendingTable = null;
        return false;
    }

    private JSONObject sendStress(JSONObject request) throws Exception {
        LinkServer link = MonitorService.current();
        if (link == null || !link.status("health").connected) {
            throw new IllegalStateException("健康模块未连接，请打开小米运动健康后再保存");
        }
        int grantMode = android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION;
        try (StressWriteGate.Grant grant = StressWriteGate.open(request)) {
            try {
                context.grantUriPermission("com.mi.health", StressWriteGate.URI, grantMode);
            } catch (RuntimeException unavailable) {
                throw new IllegalStateException("无法授权本机健康模块写回，未修改源记录");
            }
            try {
                JSONObject wire = null;
                try { wire = link.query("health", request, 15000); }
                catch (Exception ignored) { }
                grant.close(); // Freeze authorization before deciding whether the request could have taken effect.
                JSONObject outcome = grant.outcome();
                if (outcome != null) return outcome;
                if (!grant.wasUsed()) {
                    if (wire != null) {
                        throw new IllegalStateException("写回授权未通过，请更新健康模块并重新打开小米运动健康");
                    }
                    throw new IllegalStateException("健康模块未确认请求，未授权写回，请检查连接");
                }
                throw new IllegalStateException("未确认写回结果，请先同步数据库并检查源记录，勿重复保存");
            } finally {
                context.revokeUriPermission("com.mi.health", StressWriteGate.URI, grantMode);
            }
        }
    }

    private static String syncNeeded(boolean missing) {
        if (HealthDatabaseStore.receivingFitnessData()) return "健康数据库正在传输，请等完成后刷新健康详情再编辑";
        String reason = missing ? "尚未收到健康数据库；" : "当前健康数据库缺少账号标记，只能查看；";
        LinkServer server = MonitorService.current();
        if (server == null || !server.status("health").connected) {
            return reason + "请打开小米运动健康，连接后重新同步数据库";
        }
        return reason + (missing ? "请同步数据库，完成后刷新健康详情" : "请重新同步数据库；若仍不能编辑，请更新健康模块后再同步");
    }

    private boolean savedBefore(String scope, String id, JSONObject target) throws Exception {
        JSONArray versions = store.history(scope, id);
        for (int i = 0; i < versions.length(); i++) {
            if (same(target, versions.getJSONObject(i).getJSONObject("snapshot"))) return true;
        }
        return false;
    }

    private JSONObject resolve(Source source, JSONObject entry) throws Exception {
        String id = entry.getString("id");
        String[] parts = id.split(":", -1);
        if (parts.length != 3 || !parts[1].matches("[0-9]{1,12}") || !parts[2].matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("记录 ID 无效");
        }
        Kind kind = Kind.of(parts[0]);
        long rowTime = Long.parseLong(parts[1]);
        JSONObject original = snapshot(kind, entry.getJSONObject("original"));
        JSONObject saved = source.scope == null || store == null ? null : store.find(source.scope, id);
        if (saved != null && !sameIdentity(kind, saved.getJSONObject("original"), original)) saved = null;
        if (saved != null && stressWriter == null && kind != Kind.STRESS) return saved;
        JSONObject item = originals(source, kind, 0, 0, rowTime).get(id);
        if (item != null && sameIdentity(kind, original, item.getJSONObject("original"))) return withLegacy(item, saved);
        if (saved != null) return saved;
        throw new IllegalStateException("找不到此记录，请刷新健康详情");
    }

    private JSONArray list(Source source, Kind kind, long from, long until) throws Exception {
        Map<String, JSONObject> result = originals(source, kind, from, until, null);
        if (source.scope != null && store != null) {
            JSONArray revised = store.range(source.scope, kind.name, from, until);
            for (int i = 0; i < revised.length(); i++) {
                JSONObject item = revised.getJSONObject(i);
                String id = item.getString("id");
                JSONObject fresh = result.remove(id);
                JSONObject merged = fresh == null ? item : withLegacy(fresh, item);
                if (matches(kind, merged.getJSONObject("current"), from, until)) result.put(id, merged);
            }
        }
        if (result.size() > kind.limit) throw new IllegalArgumentException("健康记录超过查询上限");
        List<JSONObject> ordered = new ArrayList<>(result.values());
        ordered.sort(Comparator.comparingLong((JSONObject e) -> e.optJSONObject("current").optLong("start"))
                .thenComparing(e -> e.optString("id")));
        JSONArray out = new JSONArray();
        for (JSONObject item : ordered) out.put(item);
        return out;
    }

    private static JSONObject withLegacy(JSONObject fresh, JSONObject saved) throws Exception {
        if (saved == null || fresh.optBoolean("source_written")) return fresh;
        JSONObject anchor = fresh.optJSONObject("stress_source");
        if (anchor != null) return new JSONObject(saved.toString()).put("stress_source", anchor)
                .put("stress_payload", fresh.getString("stress_payload"));
        anchor = fresh.optJSONObject("health_source");
        if (anchor != null) return new JSONObject(saved.toString()).put("health_source", anchor)
                .put("health_payload", fresh.getString("health_payload")).put("source_current", fresh.getJSONObject("current"));
        return saved;
    }

    private JSONArray stressVersions(Source source, JSONObject item) throws Exception {
        JSONObject anchor = item.getJSONObject("stress_source");
        JSONObject original = item.getJSONObject("original");
        long start = anchor.getLong("point_time");
        JSONArray records = source.reader.query("SELECT CAST(before_value AS TEXT),saved_at FROM " + STRESS_HISTORY
                        + " WHERE sid=? AND key=? AND row_time=? AND point_time=? ORDER BY seq DESC",
                new String[]{anchor.getString("sid"), anchor.getString("key"), Long.toString(anchor.getLong("row_time")), Long.toString(start)});
        JSONArray out = new JSONArray();
        for (int i = 0; i < records.length() - 1; i++) {
            JSONObject backup = records.getJSONObject(i);
            out.put(version(pointSnapshot(start, backup.getInt("value")), backup.getLong("time"), false));
        }
        String id = item.getString("id");
        JSONObject legacy = store.find(source.scope, id);
        if (legacy != null) {
            JSONObject last = legacy.getJSONObject("current");
            if (!same(original, last)) out.put(version(last, 0, false).put("local", true));
            JSONArray saved = store.history(source.scope, id);
            for (int i = 0; i < saved.length(); i++) {
                JSONObject backup = saved.getJSONObject(i);
                if (!backup.optBoolean("original")) out.put(backup.put("local", true));
            }
        }
        return out.put(version(original, 0, true));
    }

    private JSONArray extendedVersions(Source source, JSONObject item) throws Exception {
        JSONObject anchor = item.getJSONObject("health_source");
        String id = item.getString("id");
        Kind kind = Kind.of(id.substring(0, id.indexOf(':')));
        JSONArray records = source.reader.query("SELECT before_snapshot,saved_at,seq FROM " + HEALTH_HISTORY
                + " WHERE kind=? AND sid=? AND key=? AND origin_time=? ORDER BY seq DESC",
                new String[]{kind.name,anchor.getString("sid"),anchor.getString("key"),Long.toString(anchor.getLong("row_time"))});
        JSONArray out = new JSONArray();
        for (int i=0; i<records.length(); i++) {
            JSONObject row=records.getJSONObject(i);
            out.put(version(snapshot(kind,new JSONObject(row.getString("value"))),row.getLong("time"),i==records.length()-1)
                    .put("restore_seq", row.getLong("updateTime")));
        }
        JSONArray legacy=store.history(source.scope,id);
        for (int i=0;i<legacy.length();i++) {
            JSONObject saved=legacy.getJSONObject(i);
            if (!saved.optBoolean("original")) out.put(saved.put("local",true));
        }
        return out;
    }

    private Map<String, JSONObject> originals(Source source, Kind kind, long from, long until, Long rowTime) throws Exception {
        Map<String, JSONObject> result = new LinkedHashMap<>();
        if (source.reader == null || !source.hasTable(kind.table)) return result;
        // For sleep, time may be wake, not bedtime. Get bounded endTime candidates, then filter exact overlap.
        String fields = "value,time,0,endTime,sid,key";
        if (kind != Kind.STRESS && source.scope != null && source.hasTable(HEALTH_HISTORY)) {
            fields += ",(SELECT first.before_snapshot FROM " + HEALTH_HISTORY + " last JOIN " + HEALTH_HISTORY
                    + " first ON first.kind=last.kind AND first.sid=last.sid AND first.key=last.key AND first.origin_time=last.origin_time"
                    + " WHERE last.kind='" + kind.name + "' AND last.sid=" + kind.table + ".sid AND last.key=" + kind.table
                    + ".key AND last.new_time=" + kind.table + ".time ORDER BY last.seq DESC,first.seq LIMIT 1)";
        }
        if (kind == Kind.STRESS && source.scope != null && source.hasTable(STRESS_HISTORY)) {
            String first = " FROM " + STRESS_HISTORY + " h WHERE h.row_time=stress_record.time AND h.sid=stress_record.sid"
                    + " AND h.key=stress_record.key ORDER BY h.seq LIMIT 1)";
            fields = "value,time,(SELECT before_value" + first + ",(SELECT point_time" + first + ",sid,key";
        }
        String sql = "SELECT " + fields + " FROM " + kind.table + " WHERE isDeleted=0 AND " + kind.keys
                + (rowTime != null ? " AND time=?" : kind.sleep() ? " AND endTime>? AND endTime<?" : " AND time>=? AND time<?")
                + " ORDER BY time,sid,key LIMIT " + (kind.limit + 1);
        String[] params = rowTime != null ? new String[]{Long.toString(rowTime)} : new String[]{
                Long.toString(kind.sleep() ? from - 60 : from), Long.toString(kind.sleep() ? until + MAX_SLEEP + 60 : until)};
        JSONArray rows;
        try { rows = source.reader.query(sql, params); }
        catch (Exception ex) { throw new IllegalStateException("无法读取健康明细：" + kind.name, ex); }
        if (rows == null) throw new IllegalStateException("无法读取健康明细：" + kind.name);
        if (rows.length() > kind.limit) throw new IllegalArgumentException("健康记录超过查询上限");
        for (int i = 0; i < rows.length(); i++) {
            JSONObject row = rows.getJSONObject(i), raw;
            try { raw = HealthSource.value(row); }
            catch (Exception ex) { throw new IllegalStateException("健康明细格式无法识别：" + kind.name, ex); }
            JSONObject original = kind.sleep() ? sleep(raw) : point(kind, row, raw);
            long indexedTime = row.getLong("time");
            if (original == null || indexedTime < 0 || indexedTime > 253402300799L
                    || (rowTime == null ? !matches(kind, original, from, until) : rowTime != indexedTime)) continue;
            JSONObject current = original;
            int before = row.optInt("updateTime", 0);
            long start = current.getLong("start");
            boolean sourceWritten = kind == Kind.STRESS && before >= 1 && before <= 100 && row.optLong("endTime") == start;
            if (sourceWritten) original = pointSnapshot(start, before);
            if (kind != Kind.STRESS && row.has("writeback_original")) {
                original = snapshot(kind, new JSONObject(row.getString("writeback_original")));
                sourceWritten = true;
            }
            String sid = row.optString("sid"), key = row.optString("key");
            String hash = digest(new JSONArray().put(source.scope == null ? "" : source.scope)
                    .put(kind.name).put(sid).put(key).put(indexedTime).put(identity(kind, original)).toString());
            String id = kind.name + ':' + indexedTime + ':' + hash;
            JSONObject item = entry(id, original, current);
            if (kind == Kind.STRESS && !sid.isEmpty()) {
                item.put("stress_source", new JSONObject().put("sid", sid).put("key", key).put("row_time", indexedTime)
                        .put("point_time", start).put("expected", current.getInt("value")));
                item.put("stress_payload", row.getString("value"));
            } else if (kind != Kind.STRESS && !sid.isEmpty()) {
                item.put("health_source", new JSONObject().put("sid", sid).put("key", key).put("row_time", indexedTime));
                item.put("health_payload", row.getString("value")).put("source_current", current);
            }
            if (sourceWritten) item.put("source_written", true);
            result.put(id, item);
        }
        return result;
    }

    private static JSONObject point(Kind kind, JSONObject row, JSONObject raw) throws Exception {
        long start = integer(raw, "time"), value = integer(raw, kind.field);
        if (start <= 0) start = row.getLong("time");
        if (start <= 0 || value < 1 || value > kind.max) return null;
        return pointSnapshot(start, (int)value);
    }

    static JSONObject pointSnapshot(long start, int value) throws Exception {
        return new JSONObject().put("start", start).put("value", value);
    }

    private static JSONObject sleep(JSONObject raw) throws Exception {
        long start = integer(raw, "bedtime"), end = integer(raw, "wake_up_time");
        if (start <= 0 || end <= start || end - start > MAX_SLEEP) return null;
        JSONArray items = raw.optJSONArray("items");
        if (items == null || items.length() == 0) items = raw.optJSONArray("sleepSegments");
        JSONArray stages = new JSONArray();
        if (items != null) {
            if (items.length() > STAGE_LIMIT) throw new IllegalArgumentException("睡眠阶段超过查询上限");
            for (int i = 0; i < items.length(); i++) {
                JSONObject item = items.optJSONObject(i);
                if (item == null) continue;
                long s = integer(item, "start_time"), e = integer(item, "end_time");
                if (s < start || e > end || s >= e) continue;
                long n = integer(item, "state");
                String label = n == 2 ? "deep" : n == 3 ? "light" : n == 4 ? "rem" : n == 5 ? "awake" : "unknown";
                stages.put(stage(s, e, label).put("source_index", i));
            }
        }
        JSONArray ordered = orderStages(stages);
        JSONObject out = new JSONObject().put("start", start).put("end", end).put("stages", ordered);
        long duration = integer(raw, "duration");
        if (duration > 0 && duration <= Math.ceil((end - start) / 60.0)) out.put("total_minutes", duration);
        else {
            long seconds = sleepSeconds(ordered);
            if (seconds > 0) out.put("total_minutes", Math.round(seconds / 60.0));
        }
        return out;
    }

    private static JSONObject snapshot(Kind kind, JSONObject in) throws Exception {
        long start = integer(in, "start");
        if (start <= 0) throw new IllegalArgumentException("健康记录开始时间无效");
        JSONObject out = new JSONObject().put("start", start);
        if (!kind.sleep()) {
            long value = integer(in, "value");
            if (value < 1 || value > kind.max) throw new IllegalArgumentException("读数超出范围（1–" + kind.max + "）");
            return out.put("value", (int)value);
        }
        long end = integer(in, "end");
        if (end <= start || end - start > MAX_SLEEP) throw new IllegalArgumentException("睡眠结束须晚于开始，且时段不能超过48小时");
        JSONArray stages = in.getJSONArray("stages");
        if (stages.length() > STAGE_LIMIT) throw new IllegalArgumentException("睡眠阶段超过查询上限");
        JSONArray normalized = new JSONArray();
        for (int i = 0; i < stages.length(); i++) {
            JSONObject item = stages.getJSONObject(i);
            long s = integer(item, "start"), e = integer(item, "end");
            String label = item.getString("kind");
            if (s < start || s >= e || e > end || !(asleep(label) || "awake".equals(label) || "unknown".equals(label))) {
                throw new IllegalArgumentException("睡眠阶段超出范围，或类型无效");
            }
            JSONObject next = stage(s, e, label);
            long index = integer(item, "source_index");
            if (item.has("source_index") && index >= 0 && index < STAGE_LIMIT) next.put("source_index", index);
            normalized.put(next);
        }
        out.put("end", end).put("stages", orderStages(normalized));
        if (in.has("total_minutes")) {
            long duration = integer(in, "total_minutes");
            if (duration >= 0) out.put("total_minutes", duration);
        }
        return out;
    }

    private static JSONObject stage(long start, long end, String kind) throws Exception {
        return new JSONObject().put("start", start).put("end", end).put("kind", kind);
    }

    private static JSONArray orderStages(JSONArray stages) throws Exception {
        List<JSONObject> ordered = new ArrayList<>();
        for (int i = 0; i < stages.length(); i++) ordered.add(stages.getJSONObject(i));
        ordered.sort(Comparator.comparingLong((JSONObject e) -> e.optLong("start"))
                .thenComparingLong(e -> e.optLong("end")).thenComparing(e -> e.optString("kind")));
        JSONArray out = new JSONArray();
        for (JSONObject item : ordered) out.put(item);
        return out;
    }

    private static boolean asleep(String label) {
        return "deep".equals(label) || "light".equals(label) || "rem".equals(label);
    }

    private static long sleepSeconds(JSONArray ordered) throws Exception {
        long total = 0, end = 0;
        for (int i = 0; i < ordered.length(); i++) {
            JSONObject stage = ordered.getJSONObject(i);
            if (!asleep(stage.getString("kind"))) continue;
            long start = Math.max(end, stage.getLong("start"));
            long next = stage.getLong("end");
            if (next > start) { total += next - start; end = next; }
        }
        return total;
    }

    private static long integer(JSONObject object, String key) {
        Object v = object.opt(key);
        if (!(v instanceof Number)) return -1;
        double d = ((Number)v).doubleValue();
        return d >= 0 && d <= MAX_EPOCH && d == Math.rint(d) ? ((Number)v).longValue() : -1;
    }

    static JSONObject entry(String id, JSONObject original, JSONObject current) throws Exception {
        return new JSONObject().put("id", id).put("original", original).put("current", current).put("edited", !same(original, current));
    }

    static JSONObject version(JSONObject snapshot, long time, boolean original) throws Exception {
        return new JSONObject().put("snapshot", snapshot).put("saved_at", time).put("original", original);
    }

    static boolean same(JSONObject a, JSONObject b) { return a.toString().equals(b.toString()); }

    private static JSONObject identity(Kind kind, JSONObject snapshot) throws Exception {
        if (!kind.sleep()) return snapshot;
        JSONObject compatible = new JSONObject(snapshot.toString());
        JSONArray stages = compatible.getJSONArray("stages");
        for (int i=0;i<stages.length();i++) stages.getJSONObject(i).remove("source_index");
        return compatible;
    }

    private static boolean sameIdentity(Kind kind, JSONObject a, JSONObject b) throws Exception {
        return same(identity(kind,a), identity(kind,b));
    }

    private static boolean sameSleepInterval(JSONObject a, JSONObject b) throws Exception {
        return a.getLong("start") == b.getLong("start") && a.getLong("end") == b.getLong("end")
                && a.getJSONArray("stages").toString().equals(b.getJSONArray("stages").toString());
    }

    private static boolean matches(Kind kind, JSONObject snapshot, long from, long until) throws Exception {
        long start = snapshot.getLong("start");
        return start < until && (kind.sleep() ? snapshot.getLong("end") > from : start >= from);
    }

    private static void window(long from, long until) {
        if (from < 0 || until <= from || until > MAX_EPOCH + 1 || until - from > MAX_WINDOW) {
            throw new IllegalArgumentException("健康详情查询范围最多32天，且开始须早于结束");
        }
    }

    private Source open() throws Exception {
        if (context == null) return new Source(null, testSource, testScope);
        File file = HealthDatabaseStore.file(context);
        if (!file.isFile()) return new Source(null, null, null);
        SQLiteDatabase db = HealthSource.open(file);
        try { return new Source(db, HealthSource.reader(db), HealthDatabaseStore.historyScope(db)); }
        catch (Exception error) { db.close(); throw error; }
    }

    private static final class Source implements AutoCloseable {
        final SQLiteDatabase db;
        final HealthSource.Reader reader;
        final String scope;
        Source(SQLiteDatabase db, HealthSource.Reader reader, String scope) {
            this.db = db; this.reader = reader; this.scope = scope;
        }
        boolean hasTable(String table) throws Exception {
            if (db != null) return HealthDatabaseStore.hasTable(db, table);
            return reader.query("SELECT 1 FROM sqlite_master WHERE type='table' AND name=? LIMIT 1", new String[]{table}).length() > 0;
        }
        public void close() { if (db != null) db.close(); }
    }

    private static String digest(String text) throws Exception {
        byte[] bytes = MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
        StringBuilder hex = new StringBuilder(64);
        for (byte b : bytes) hex.append(Character.forDigit((b >>> 4) & 15, 16)).append(Character.forDigit(b & 15, 16));
        return hex.toString();
    }

    private enum Kind {
        SLEEP("sleep", "sleep_segment", "key='sleep'", "", 0, SLEEP_LIMIT),
        HEART("heart_rate", "hr_record", "key IN ('heart_rate','single_heart_rate')", "bpm", 300, POINT_LIMIT),
        SPO2("spo2", "spo2_record", "key IN ('spo2','single_spo2')", "spo2", 100, POINT_LIMIT),
        STRESS("stress", "stress_record", "key IN ('stress','single_stress')", "stress", 100, POINT_LIMIT);

        final String name, table, keys, field;
        final int max, limit;
        Kind(String name, String table, String keys, String field, int max, int limit) {
            this.name = name; this.table = table; this.keys = keys; this.field = field; this.max = max; this.limit = limit;
        }
        boolean sleep() { return this == SLEEP; }
        static Kind of(String name) {
            for (Kind kind : values()) if (kind.name.equals(name)) return kind;
            throw new IllegalArgumentException("未知健康指标");
        }
    }
}

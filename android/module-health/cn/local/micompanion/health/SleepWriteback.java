package cn.local.micompanion.health;

import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteStatement;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Changes a single existing night's verified source payload, never creates a night or a stage. */
final class SleepWriteback {
    private static final int STAGE_LIMIT = 512;
    private static final long MAX_EPOCH = 253402300799L, MAX_SLEEP = 172800L;
    private static final String TABLE = "sleep_segment";
    private static final String READ_ROW = "SELECT CASE WHEN typeof(value)='text' AND length(CAST(value AS BLOB))<=65536 THEN value END,"
            + " endTime,timeIn0Tz,endTimeIn0Tz,zoneOffsetInSec FROM sleep_segment"
            + " WHERE key=? AND sid=? AND time=CAST(? AS INTEGER) AND isDeleted=0 LIMIT 2";
    private static final String UPDATE_ROW = "UPDATE sleep_segment SET value=? WHERE key=? AND sid=? AND time=? AND isDeleted=0"
            + " AND endTime=? AND timeIn0Tz=? AND endTimeIn0Tz=? AND zoneOffsetInSec=? AND value=? COLLATE BINARY";
    private static final String VALID_JSON = "SELECT CASE WHEN length(CAST(?1 AS BLOB))<=65536 AND json_valid(?1) THEN json_type(?1) END";
    private static final String AMBIGUOUS_JSON = "SELECT 1 FROM json_tree(?) WHERE typeof(key)='text' GROUP BY parent,key HAVING count(*)>1 LIMIT 1";
    private static final String ROOT_MEMBERS = "SELECT key,type,atom FROM json_each(?)";
    private static final String STAGE_MEMBERS = "SELECT i.key,m.key,m.type,m.atom FROM json_each(?,'$.items') i"
            + " LEFT JOIN json_each(CASE WHEN i.type='object' THEN i.value ELSE '{}' END) m ORDER BY CAST(i.key AS INTEGER)";
    private static final String REPLACE_MEMBER = "SELECT json_replace(?, ?, CAST(? AS INTEGER))";
    private static final String[] KINDS = {"deep", "light", "rem", "awake"};
    private static final String[] PHASES = {"sleep_deep_duration", "sleep_light_duration", "sleep_rem_duration",
            "sleep_awake_duration", "sleep_trace_duration"};
    private static final String[] OTHERS = {"duration", "sleep_duration", "sleep_efficiency", "awake_count", "bed_duration"};

    private SleepWriteback() { }

    static HealthWriteback.Change prepare(SQLiteDatabase db, HealthWriteback.Request r) throws Exception {
        if (!"sleep".equals(r.kind) || !"sleep".equals(r.key)) throw unsupported();
        HealthWriteback.verifyTable(db, TABLE);
        IndexedRow row = readRow(db, r);
        String raw = row.raw;
        HealthWriteback.requirePreimage(r, raw);
        NativeSleep before = readPayload(db, raw);
        // The verified layout indexes by wake time. The meaning of endTime is not established.
        if (before.end != r.rowTime || row.end <= 0 || row.end > Long.MAX_VALUE - 86400L
                || row.offset < -86400L || row.offset > 86400L
                || row.local != r.rowTime + row.offset || row.endLocal != row.end + row.offset) throw unsupported();
        NativeSleep target = readTarget(r.target);
        if (target.start != before.start || target.end != before.end) throw unsupported();
        String nextRaw;
        NativeSleep after;
        if (r.restoreRaw != null) {
            nextRaw = r.restoreRaw;
            after = readPayload(db, nextRaw);
        } else if (before.sameTimeline(target)) {
            nextRaw = raw;
            after = before;
        } else {
            Plan plan = new Plan(before, target);
            nextRaw = plan.patch(db, raw);
            after = readPayload(db, nextRaw);
            if (!plan.fields.equals(after.fields)) throw unsupported();
        }
        if (!after.sameTimeline(target)) throw unsupported();
        return new HealthWriteback.Change(raw, before.projection(), after.projection(), r.rowTime, () -> {
            try (SQLiteStatement update = db.compileStatement(UPDATE_ROW)) {
                update.bindString(1, nextRaw);
                update.bindString(2, r.key);
                update.bindString(3, r.sid);
                update.bindLong(4, r.rowTime);
                update.bindLong(5, row.end);
                update.bindLong(6, row.local);
                update.bindLong(7, row.endLocal);
                update.bindLong(8, row.offset);
                update.bindString(9, raw);
                if (update.executeUpdateDelete() != 1) throw new HealthWriteback.Rejected("conflict");
            }
            IndexedRow actual = readRow(db, r);
            if (!nextRaw.equals(actual.raw) || row.end != actual.end || row.local != actual.local
                    || row.endLocal != actual.endLocal || row.offset != actual.offset) throw unsupported();
        });
    }

    private static IndexedRow readRow(SQLiteDatabase db, HealthWriteback.Request r) throws Exception {
        try (Cursor row = db.rawQuery(READ_ROW, new String[]{r.key, r.sid, Long.toString(r.rowTime)})) {
            if (!row.moveToFirst()) throw new HealthWriteback.Rejected("conflict");
            if (row.isNull(0)) throw unsupported();
            IndexedRow record = new IndexedRow(row.getString(0), sqlInt(row, 1), sqlInt(row, 2), sqlInt(row, 3), sqlInt(row, 4));
            if (row.moveToNext()) throw new HealthWriteback.Rejected("conflict");
            return record;
        }
    }

    private static NativeSleep readPayload(SQLiteDatabase db, String raw) throws Exception {
        if (!"object".equals(scalar(db, VALID_JSON, raw))) throw unsupported();
        try (Cursor duplicate = db.rawQuery(AMBIGUOUS_JSON, new String[]{raw})) {
            if (duplicate.moveToFirst()) throw unsupported();
        }
        long start = -1, end = -1;
        boolean items = false;
        Map<String, Long> fields = new LinkedHashMap<>();
        try (Cursor members = db.rawQuery(ROOT_MEMBERS, new String[]{raw})) {
            while (members.moveToNext()) {
                String name = members.getString(0), type = members.getString(1);
                if ("bedtime".equals(name)) start = sourceInt(members, 1, 2);
                else if ("wake_up_time".equals(name)) end = sourceInt(members, 1, 2);
                else if ("items".equals(name)) items = "array".equals(type);
                else if ("sleepSegments".equals(name)) throw unsupported();
                else if (contains(PHASES, name) || contains(OTHERS, name)) {
                    if (!"null".equals(type)) fields.put(name, sourceInt(members, 1, 2));
                }
            }
        }
        if (!items) throw unsupported();
        List<Phase> phases = new ArrayList<>();
        try (Cursor rows = db.rawQuery(STAGE_MEMBERS, new String[]{raw})) {
            int index = -1;
            long s = -1, e = -1, state = -1;
            while (rows.moveToNext()) {
                int nextIndex = (int) sqlInt(rows, 0);
                if (nextIndex != index) {
                    if (index >= 0) phases.add(nativePhase(index, s, e, state));
                    if (nextIndex != index + 1 || nextIndex >= STAGE_LIMIT) throw unsupported();
                    index = nextIndex;
                    s = e = state = -1;
                }
                String name = rows.getString(1);
                if ("start_time".equals(name)) s = sourceInt(rows, 2, 3);
                else if ("end_time".equals(name)) e = sourceInt(rows, 2, 3);
                else if ("state".equals(name)) state = sourceInt(rows, 2, 3);
            }
            if (index >= 0) phases.add(nativePhase(index, s, e, state));
        }
        return new NativeSleep(start, end, phases, fields);
    }

    private static NativeSleep readTarget(JSONObject target) throws Exception {
        long start = integer(target, "start"), end = integer(target, "end");
        JSONArray items = target.optJSONArray("stages");
        if (items == null || items.length() > STAGE_LIMIT) throw unsupported();
        List<Phase> phases = new ArrayList<>();
        for (int i = 0; i < items.length(); i++) {
            JSONObject item = items.optJSONObject(i);
            if (item == null) throw unsupported();
            long index = item.has("source_index") ? integer(item, "source_index") : -1;
            if (item.has("source_index") && (index < 0 || index >= STAGE_LIMIT)) throw unsupported();
            int state;
            switch (item.optString("kind")) {
                case "deep": state = 2; break;
                case "light": state = 3; break;
                case "rem": state = 4; break;
                case "awake": state = 5; break;
                case "unknown": state = -1; break;
                default: throw unsupported();
            }
            phases.add(new Phase((int) index, integer(item, "start"), integer(item, "end"), state));
        }
        return new NativeSleep(start, end, phases, new LinkedHashMap<>());
    }

    private static Phase nativePhase(int index, long start, long end, long state) throws Exception {
        if (state < 0 || state > Integer.MAX_VALUE) throw unsupported();
        return new Phase(index, start, end, (int) state);
    }

    private static final class IndexedRow {
        final String raw;
        final long end, local, endLocal, offset;
        IndexedRow(String raw, long end, long local, long endLocal, long offset) {
            this.raw = raw;
            this.end = end;
            this.local = local;
            this.endLocal = endLocal;
            this.offset = offset;
        }
    }

    private static final class Phase {
        final int index, state;
        final long start, end;
        Phase(int index, long start, long end, int state) {
            this.index = index;
            this.start = start;
            this.end = end;
            this.state = state;
        }
        String kind() {
            return state >= 2 && state <= 5 ? KINDS[state - 2] : "unknown";
        }
        boolean same(Phase other) { return start == other.start && end == other.end && kind().equals(other.kind()); }
    }

    private static final class NativeSleep {
        final long start, end;
        final List<Phase> physical, ordered;
        final Map<String, Long> fields;
        NativeSleep(long start, long end, List<Phase> physical, Map<String, Long> fields) throws Exception {
            if (start <= 0 || end <= start || end > MAX_EPOCH || end - start > MAX_SLEEP) throw unsupported();
            this.start = start;
            this.end = end;
            this.physical = physical;
            this.fields = fields;
            ordered = new ArrayList<>(physical);
            ordered.sort(Comparator.comparingLong(p -> p.start));
            long previousEnd = start;
            for (Phase phase : ordered) {
                if (phase.start < previousEnd || phase.end <= phase.start || phase.end > end) throw unsupported();
                previousEnd = phase.end;
            }
        }
        boolean sameTimeline(NativeSleep other) {
            if (start != other.start || end != other.end || ordered.size() != other.ordered.size()) return false;
            for (int i = 0; i < ordered.size(); i++) if (!ordered.get(i).same(other.ordered.get(i))) return false;
            return true;
        }
        JSONObject projection() throws Exception {
            JSONArray stages = new JSONArray();
            for (Phase phase : ordered) stages.put(new JSONObject().put("start", phase.start).put("end", phase.end)
                    .put("kind", phase.kind()).put("source_index", phase.index));
            JSONObject result = new JSONObject().put("start", start).put("end", end).put("stages", stages);
            Long total = fields.get("duration");
            long asleep = new Totals(ordered).asleep;
            if (total != null && total > 0 && total <= (end - start + 59) / 60) result.put("total_minutes", total);
            else if (asleep > 0) result.put("total_minutes", Math.round(asleep / 60.0));
            return result;
        }
    }

    private static final class Totals {
        final Map<Integer, Long> states = new LinkedHashMap<>();
        long asleep, all, awakeCount, awakeRuns;
        Totals(List<Phase> ordered) {
            Phase previous = null;
            for (Phase p : ordered) {
                long seconds = p.end - p.start;
                states.put(p.state, state(p.state) + seconds);
                all += seconds;
                if (p.state >= 2 && p.state <= 4) asleep += seconds;
                if (p.state == 5) {
                    awakeCount++;
                    if (previous == null || previous.state != 5 || previous.end != p.start) awakeRuns++;
                }
                previous = p;
            }
        }
        long state(int state) { return states.getOrDefault(state, 0L); }
    }

    private static final class Plan {
        final NativeSleep before;
        final Phase[] next;
        final Map<String, Long> fields;
        Plan(NativeSleep before, NativeSleep target) throws Exception {
            this.before = before;
            next = new Phase[before.physical.size()];
            if (target.ordered.size() != next.length) throw unsupported();
            List<Phase> patched = new ArrayList<>();
            for (Phase desired : target.ordered) {
                int index = desired.index;
                if (index < 0) {
                    // Old assistant snapshots without provenance can only keep exact, uniquely matching stages.
                    for (Phase original : before.physical) if (original.same(desired)) index = original.index;
                }
                if (index < 0 || index >= next.length || next[index] != null) throw unsupported();
                Phase old = before.physical.get(index);
                if (desired.state < 0 && !"unknown".equals(old.kind())) throw unsupported();
                int state = desired.state < 0 ? old.state : desired.state;
                next[index] = new Phase(index, desired.start, desired.end, state);
                patched.add(next[index]);
            }
            fields = aggregates(before.fields, new Totals(before.ordered), new Totals(patched));
        }
        String patch(SQLiteDatabase db, String raw) throws Exception {
            String changed = raw;
            for (int i = next.length - 1; i >= 0; i--) {
                String path = "$.items[" + i + "]";
                Phase old = before.physical.get(i), target = next[i];
                if (old.start != target.start) changed = replace(db, changed, path + ".start_time", target.start);
                if (old.end != target.end) changed = replace(db, changed, path + ".end_time", target.end);
                if (old.state != target.state) changed = replace(db, changed, path + ".state", target.state);
            }
            for (Map.Entry<String, Long> entry : fields.entrySet()) {
                if (!entry.getValue().equals(before.fields.get(entry.getKey()))) {
                    changed = replace(db, changed, "$." + entry.getKey(), entry.getValue());
                }
            }
            return changed;
        }
    }

    private static Map<String, Long> aggregates(Map<String, Long> previous, Totals before, Totals after) throws Exception {
        Map<String, Long> result = new LinkedHashMap<>(previous);
        for (int i = 0; i < PHASES.length; i++) if (previous.containsKey(PHASES[i])) {
            long a = before.state(i + 2), b = after.state(i + 2);
            if (a != b) result.put(PHASES[i], minutes(previous.get(PHASES[i]), a, b));
        }
        if (previous.containsKey("duration") && before.asleep != after.asleep) {
            result.put("duration", minutes(previous.get("duration"), before.asleep, after.asleep));
        }
        if (previous.containsKey("awake_count") && (before.awakeCount != after.awakeCount || before.awakeRuns != after.awakeRuns)) {
            result.put("awake_count", infer(previous.get("awake_count"), before.awakeCount, after.awakeCount,
                    before.awakeRuns, after.awakeRuns));
        }
        // Keep undocumented aggregate fields only when no relevant input changed.
        if ((previous.containsKey("sleep_duration") || previous.containsKey("sleep_efficiency"))
                && (before.asleep != after.asleep || before.state(6) != after.state(6) || before.all != after.all)) {
            throw unsupported();
        }
        return result;
    }

    private static long minutes(long value, long before, long after) throws Exception {
        return infer(value, before / 60, after / 60, (before + 30) / 60, (after + 30) / 60);
    }

    private static long infer(long value, long beforeA, long afterA, long beforeB, long afterB) throws Exception {
        if (value == beforeA && (value != beforeB || afterA == afterB)) return afterA;
        if (value == beforeB && value != beforeA) return afterB;
        throw unsupported();
    }

    private static boolean contains(String[] names, String name) {
        for (String candidate : names) if (candidate.equals(name)) return true;
        return false;
    }

    private static long sqlInt(Cursor row, int column) throws Exception {
        if (row.getType(column) != Cursor.FIELD_TYPE_INTEGER) throw unsupported();
        return row.getLong(column);
    }

    private static long sourceInt(Cursor row, int type, int value) throws Exception {
        if (!"integer".equals(row.getString(type))) throw unsupported();
        return sqlInt(row, value);
    }

    private static long integer(JSONObject object, String key) throws Exception {
        Object value = object.opt(key);
        if (!(value instanceof Integer || value instanceof Long)) throw unsupported();
        return ((Number) value).longValue();
    }

    private static String scalar(SQLiteDatabase db, String query, String... args) throws Exception {
        try (Cursor row = db.rawQuery(query, args)) {
            if (!row.moveToFirst()) throw unsupported();
            return row.getString(0);
        }
    }

    private static String replace(SQLiteDatabase db, String raw, String path, long value) throws Exception {
        return scalar(db, REPLACE_MEMBER, raw, path, Long.toString(value));
    }

    private static HealthWriteback.Rejected unsupported() { return new HealthWriteback.Rejected("unsupported"); }
}

package cn.local.micompanion;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import org.json.JSONArray;
import org.json.JSONObject;

public final class HealthHistoryTest {
    private static final String A = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
    private static final String B = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb";
    private static final long BED = 1767312000, WAKE = BED + 8 * 3600;

    public static void main(String[] args) throws Exception {
        Path fixture = Files.createTempFile("health-history-synthetic-", ".db");
        try {
            python(fixture, "import json,sqlite3,sys\n"
                    + "db=sqlite3.connect(sys.argv[1])\n"
                    + "layout='value TEXT,time INTEGER,endTime INTEGER,sid TEXT,key TEXT,isDeleted INTEGER'\n"
                    + "for table in ('hr_record','sleep_segment'): db.execute('CREATE TABLE '+table+'('+layout+')')\n"
                    + "def record(table,key,index,end,raw,deleted=0):\n"
                    + " db.execute('INSERT INTO '+table+' VALUES(?,?,?,?,?,?)',(json.dumps(raw),index,end,'fixture',key,deleted))\n"
                    + "for time,bpm,deleted in ((100,66,0),(120,70,0),(130,200,1),(140,71,0)):\n"
                    + " record('hr_record','heart_rate',time,time,dict(time=time,bpm=bpm),deleted)\n"
                    + "bed,wake=map(int,sys.argv[2:4])\n"
                    + "record('sleep_segment','sleep',wake,wake,dict(bedtime=bed,wake_up_time=wake,duration=431))\n"
                    + "record('sleep_segment','sleep',wake+3600,wake+3600,dict(bedtime=wake+1800,wake_up_time=wake+3600,items=[dict(start_time=wake+1800,end_time=wake+2000,state=2),dict(start_time=wake+1900,end_time=wake+2100,state=4),dict(start_time=wake+2200,end_time=wake+2300,state=8),dict(start_time=wake+2500,end_time=wake+3700,state=3)]))\n"
                    + "db.commit()", Long.toString(BED), Long.toString(WAKE));
            byte[] before = Files.readAllBytes(fixture);
            HealthSource.Reader source = (sql, params) -> sqlite(fixture, sql, params);
            MemoryStore memory = new MemoryStore();
            HealthHistory history = new HealthHistory(source, A, memory);
            JSONObject first = readings(history);
            sleep(history);
            revisions(source, memory, history, first);
            HealthHistory broken = new HealthHistory((sql, params) -> {
                if (sql.contains("FROM hr_record")) throw new IllegalStateException("fixture schema broken");
                return source.query(sql, params);
            }, A, memory);
            expectState(() -> broken.readings(100, 140), "无法读取健康明细");
            check(java.util.Arrays.equals(before, Files.readAllBytes(fixture)), "source SQLite file is unchanged");
            stressWriteback(fixture, source, memory);
            extendedWriteback(fixture, source);
        } finally {
            Files.deleteIfExists(fixture);
        }
    }

    private static JSONObject readings(HealthHistory history) throws Exception {
        JSONObject readings = history.readings(100, 140);
        JSONArray heart = readings.getJSONArray("heart_rate");
        check(heart.length() == 2, "include from, exclude until and deleted rows");
        JSONObject first = heart.getJSONObject(0);
        check(first.getJSONObject("current").getInt("value") == 66 && !first.getBoolean("edited"), "unmodified source snapshot");
        check(first.getJSONObject("original").getLong("start") == 100, "snapshot uses epoch seconds");
        check(readings.getJSONArray("spo2").length() == 0 && readings.getJSONArray("stress").length() == 0,
                "absent metric tables are independently empty");
        return first;
    }

    private static void sleep(HealthHistory history) throws Exception {
        JSONArray night = history.list("sleep", BED, BED + 1800);
        check(night.length() == 1 && history.list("sleep", BED - 1, BED).length() == 0
                && history.list("sleep", WAKE, WAKE + 1).length() == 0, "exact half-open overlap including wake after until");
        JSONObject item = night.getJSONObject(0), original = item.getJSONObject("original");
        check(original.getLong("start") == BED && original.getLong("end") == WAKE, "do not clip source endpoints");
        check(original.getJSONArray("stages").length() == 0 && original.getInt("total_minutes") == 431,
                "real native duration does not fabricate stages");
        check(!history.save(item, new JSONObject(original.toString()).put("total_minutes", 480)), "changing only a derived total is a no-op");
        check(history.save(item, new JSONObject(original.toString()).put("end", WAKE + 60)), "edit a stage-less session");
        JSONObject changed = history.list("sleep", BED, BED + 1800).getJSONObject(0);
        check(!changed.getJSONObject("current").has("total_minutes"), "do not call an edited in-bed interval sleep duration");
        check(history.save(changed, history.history(changed).getJSONObject(0).getJSONObject("snapshot")), "restore sleep original");
        check(history.list("sleep", BED, BED + 1800).getJSONObject(0).getJSONObject("current").getInt("total_minutes") == 431,
                "restore source native total unchanged");
        JSONObject nap = history.list("sleep", WAKE + 1800, WAKE + 2000).getJSONObject(0);
        JSONObject napOriginal = nap.getJSONObject("original");
        check(napOriginal.getJSONArray("stages").length() == 3 && "unknown".equals(napOriginal.getJSONArray("stages").getJSONObject(2).getString("kind")),
                "keep genuine overlapping/unknown stages and drop out-of-session stages");
        check(history.save(nap, new JSONObject(napOriginal.toString()).put("end", WAKE + 3660)), "extend without synthesizing stages");
        check(history.list("sleep", WAKE + 1800, WAKE + 2000).getJSONObject(0).getJSONObject("current").getInt("total_minutes") == 5,
                "known asleep union is 300 seconds, unknown does not count");
    }

    private static void revisions(HealthSource.Reader source, MemoryStore store, HealthHistory history, JSONObject original) throws Exception {
        check(!history.save(original, new JSONObject().put("value", 66).put("start", 100)), "equivalent redundant save");
        check(history.history(original).length() == 1 && history.history(original).getJSONObject(0).getBoolean("original"),
                "unmodified record has only its original history");
        check(history.save(original, new JSONObject().put("start", 150).put("value", 77)), "move/modify reading");
        check(history.hasEdits("heart_rate", 100, 101) && history.hasEdits("heart_rate", 150, 151)
                && !history.hasEdits("heart_rate", 101, 150), "hasEdits considers original and current half-open windows");
        check(history.list("heart_rate", 100, 101).length() == 0, "moved record is not duplicated in original window");
        JSONObject moved = history.list("heart_rate", 150, 151).getJSONObject(0);
        check(moved.getString("id").equals(original.getString("id")) && moved.getBoolean("edited"), "identity survives date change");
        expectState(() -> history.save(original, new JSONObject().put("start", 100).put("value", 67)), "已变更");
        check(history.save(moved, new JSONObject().put("start", 150).put("value", 80)), "second edit");
        HealthHistory rotated = new HealthHistory((sql, params) -> new JSONArray(), A, store);
        JSONObject reopened = rotated.list("heart_rate", 150, 151).getJSONObject(0);
        JSONArray old = rotated.history(reopened);
        check(old.length() == 2 && old.getJSONObject(0).getJSONObject("snapshot").getInt("value") == 77
                && !old.getJSONObject(0).getBoolean("original") && old.getJSONObject(1).getBoolean("original"),
                "newest old version first, exact backup retained after source rotation");
        check(rotated.save(reopened, old.getJSONObject(1).getJSONObject("snapshot")), "restore original via save");
        JSONObject restored = rotated.list("heart_rate", 100, 101).getJSONObject(0);
        check(!restored.getBoolean("edited") && rotated.history(restored).getJSONObject(0).getJSONObject("snapshot").getInt("value") == 80,
                "restore backs up the displaced current version and does not discard original");
        check(!rotated.save(restored, restored.getJSONObject("current")) && rotated.history(restored).length() == 3,
                "no-op after restore does not grow history");
        HealthHistory other = new HealthHistory(source, B, store);
        check(!other.hasEdits("heart_rate", 100, 151) && !other.list("heart_rate", 100, 101).getJSONObject(0)
                .getString("id").equals(original.getString("id")), "identical source rows in another account cannot match");
        expectState(() -> other.history(restored), "找不到");
        HealthHistory legacy = new HealthHistory(source, null, store);
        JSONObject readOnly = legacy.list("heart_rate", 100, 101).getJSONObject(0);
        expectState(() -> legacy.save(readOnly, new JSONObject().put("start", 100).put("value", 67)), "请打开小米运动健康");
        HealthHistory missing = new HealthHistory(null, null, store);
        check(missing.list("heart_rate", 100, 101).length() == 0, "missing DB returns an empty result");
        expectState(() -> missing.save(readOnly, new JSONObject().put("start", 100).put("value", 67)), "尚未收到健康数据库");
    }

    private static void stressWriteback(Path fixture, HealthSource.Reader source, MemoryStore memory) throws Exception {
        python(fixture, "import sqlite3,sys\ndb=sqlite3.connect(sys.argv[1])\n"
                + "db.execute('CREATE TABLE stress_record(value TEXT,time INTEGER,endTime INTEGER,sid TEXT,key TEXT,isDeleted INTEGER)')\n"
                + "db.execute('INSERT INTO stress_record VALUES(?,?,?,?,?,?)',('{\"time\":100,\"stress\":42}',100,100,'fixture','stress',0))\n"
                + "db.commit()");
        HealthHistory legacy = new HealthHistory(source, A, memory);
        JSONObject original = legacy.list("stress", 100, 101).getJSONObject(0);
        check(legacy.save(original, HealthHistory.pointSnapshot(100, 51)), "legacy local edit");
        final int[] calls = {0};
        HealthHistory history = new HealthHistory(source, A, memory, request -> {
            check(A.equals(request.getString("scope")) && "fixture".equals(request.getString("sid"))
                    && "stress".equals(request.getString("key")) && request.getLong("row_time") == 100
                    && request.getLong("point_time") == 100, "scoped precise writeback target");
            int expected = request.getInt("expected"), target = request.getInt("value");
            check(new JSONObject(memory.stressArchives.get(request.getString("op"))).getInt("stress") == expected,
                    "durable preimage precedes sending the write");
            check(expected == (calls[0]++ == 0 ? 42 : 51), "compares against source, not legacy overlay");
            python(fixture, "import json,sqlite3,sys\ndb=sqlite3.connect(sys.argv[1])\n"
                    + "db.execute('CREATE TABLE IF NOT EXISTS micompanion_stress_history (seq INTEGER PRIMARY KEY, op TEXT,sid TEXT,key TEXT,row_time INTEGER,point_time INTEGER,before_value INTEGER,after_value INTEGER,saved_at INTEGER)')\n"
                    + "db.execute(\"INSERT INTO micompanion_stress_history(op,sid,key,row_time,point_time,before_value,after_value,saved_at) VALUES(?,'fixture','stress',100,100,?,?,1000)\",(sys.argv[2],int(sys.argv[3]),int(sys.argv[4])))\n"
                    + "db.execute('UPDATE stress_record SET value=?',(json.dumps(dict(time=100,stress=int(sys.argv[4]))),))\n"
                    + "db.commit()", request.getString("op"), Integer.toString(expected), Integer.toString(target));
            return new JSONObject().put("status", "ok").put("changed", true);
        });
        JSONObject legacyEdited = history.list("stress", 100, 101).getJSONObject(0);
        memory.rejectArchive = true;
        expectState(() -> history.save(legacyEdited, legacyEdited.getJSONObject("current")), "无法独立备份");
        check(calls[0] == 0, "backup failure never sends a write");
        memory.rejectArchive = false;
        check(history.save(legacyEdited, legacyEdited.getJSONObject("current")), "explicit save writes the prior local value back once");
        JSONArray rows = history.list("stress", 100, 101);
        check(rows.length() == 1 && rows.getJSONObject(0).getString("id").equals(original.getString("id"))
                && rows.getJSONObject(0).getJSONObject("current").getInt("value") == 51, "new snapshot avoids duplicates and keeps identity");
        JSONObject edited = rows.getJSONObject(0);
        JSONArray backups = history.history(edited);
        check(backups.getJSONObject(backups.length() - 1).getJSONObject("snapshot").getInt("value") == 42,
                "original accessible after the source changes");
        check(history.save(edited, original.getJSONObject("original")), "restore writes back too");
        JSONObject restored = history.list("stress", 100, 101).getJSONObject(0);
        check(restored.getJSONObject("current").getInt("value") == 42
                && history.history(restored).getJSONObject(0).getJSONObject("snapshot").getInt("value") == 51,
                "restore retains backup of the displaced value");
        check(!history.save(restored, restored.getJSONObject("current")) && calls[0] == 2, "no-op doesn't call module");
    }

    private static void extendedWriteback(Path fixture, HealthSource.Reader source) throws Exception {
        MemoryStore memory = new MemoryStore();
        HealthHistory history = new HealthHistory(source, A, memory, req -> {
            check("health_writeback".equals(req.getString("action")) && "heart_rate".equals(req.getString("kind")), "extended route and kind");
            check(memory.stressArchives.containsKey(req.getString("op")), "backup precedes extended write");
            int next = req.getJSONObject("target").getInt("value");
            check(req.optLong("restore_seq") == (next == 66 ? 1 : 0), "restore sequence forwarded");
            python(fixture, "import json,sqlite3,sys\ndb=sqlite3.connect(sys.argv[1])\n"
                    + "db.execute('CREATE TABLE IF NOT EXISTS micompanion_health_history (seq INTEGER PRIMARY KEY,op TEXT,kind TEXT,sid TEXT,key TEXT,origin_time INTEGER,new_time INTEGER,before_snapshot TEXT,saved_at INTEGER)')\n"
                    + "old=json.loads(db.execute('SELECT value FROM hr_record WHERE time=100').fetchone()[0])['bpm']\n"
                    + "db.execute(\"INSERT INTO micompanion_health_history(op,kind,sid,key,origin_time,new_time,before_snapshot,saved_at) VALUES(?,'heart_rate','fixture','heart_rate',100,100,?,1000)\",(sys.argv[2],json.dumps(dict(start=100,value=old))))\n"
                    + "db.execute('UPDATE hr_record SET value=? WHERE time=100',(json.dumps(dict(time=100,bpm=int(sys.argv[3]))),))\ndb.commit()", req.getString("op"), Integer.toString(next));
            return new JSONObject().put("status", "ok").put("changed", true);
        });
        JSONObject before = history.list("heart_rate", 100, 101).getJSONObject(0);
        check(history.save(before, HealthHistory.pointSnapshot(100, 76)), "save to source");
        check(!history.stressCopyPending(), "replica journal acknowledges the extended operation");
        JSONObject edited = history.list("heart_rate", 100, 101).getJSONObject(0);
        check(before.getString("id").equals(edited.getString("id")) && edited.getJSONObject("current").getInt("value") == 76, "stable id and source projection");
        JSONObject version = history.history(edited).getJSONObject(0);
        check(history.hasEdits("heart_rate", 100, 101) && version.getBoolean("original") && version.getLong("restore_seq") == 1, "extended version metadata");
        check(history.save(edited, version.getJSONObject("snapshot"), version.getLong("restore_seq")), "restore through source journal");
        JSONObject restored = history.list("heart_rate", 100, 101).getJSONObject(0);
        check(restored.getJSONObject("current").getInt("value") == 66 && history.history(restored).length() == 2, "restore retains both versions");
    }

    private static JSONArray sqlite(Path fixture, String sql, String[] params) throws Exception {
        JSONArray raw = new JSONArray(python(fixture, "import json,sqlite3,sys\n"
                + "from pathlib import Path\n"
                + "db=sqlite3.connect(Path(sys.argv[1]).as_uri()+'?mode=ro',uri=True)\n"
                + "print(json.dumps(db.execute(sys.argv[2],json.loads(sys.argv[3])).fetchall()))", sql, new JSONArray(params).toString()));
        JSONArray out = new JSONArray();
        for (int i = 0; i < raw.length(); i++) {
            JSONArray row = raw.getJSONArray(i);
            JSONObject item = new JSONObject();
            if (row.length() >= 2) item.put("value", row.getString(0)).put("time", row.getLong(1));
            if (row.length() >= 3) item.put("updateTime", row.optLong(2, 0));
            if (row.length() >= 6) item.put("endTime", row.optLong(3, 0)).put("sid", row.getString(4)).put("key", row.getString(5));
            if (row.length() >= 7 && !row.isNull(6)) item.put("writeback_original", row.getString(6));
            out.put(item);
        }
        return out;
    }

    private static String python(Path fixture, String code, String... args) throws Exception {
        String[] command = new String[4 + args.length];
        command[0] = "python3"; command[1] = "-c"; command[2] = code; command[3] = fixture.toString();
        System.arraycopy(args, 0, command, 4, args.length);
        Process child = new ProcessBuilder(command).redirectErrorStream(true).start();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (InputStream in = child.getInputStream()) {
            byte[] buf = new byte[4096];
            for (int n; (n = in.read(buf)) != -1; ) out.write(buf, 0, n);
        }
        String text = new String(out.toByteArray(), StandardCharsets.UTF_8);
        check(child.waitFor() == 0, text);
        return text;
    }

    private interface Checked { void run() throws Exception; }

    private static void expectState(Checked operation, String message) throws Exception {
        try { operation.run(); }
        catch (IllegalStateException expected) { check(expected.getMessage().contains(message), expected.getMessage()); return; }
        throw new AssertionError("expected: " + message);
    }

    private static void check(boolean yes, String message) { if (!yes) throw new AssertionError(message); }

    /** Android SQLiteDatabase is a JVM stub; detail workflows use this in-memory version-store seam. */
    private static final class MemoryStore implements HealthHistory.Store {
        private final Map<String, JSONObject> entries = new LinkedHashMap<>();
        private final Map<String, JSONArray> versions = new LinkedHashMap<>();
        private long now = 1700000000000L;
        private boolean overlaps(JSONObject snapshot, long from, long until) throws Exception {
            long start = snapshot.getLong("start");
            return start < until && snapshot.optLong("end", start + 1) > from;
        }
        public JSONArray range(String scope, String kind, long from, long until) throws Exception {
            JSONArray out = new JSONArray();
            for (Map.Entry<String, JSONObject> slot : entries.entrySet()) {
                JSONObject entry = slot.getValue();
                if (slot.getKey().startsWith(scope + kind + ':') && (overlaps(entry.getJSONObject("original"), from, until)
                        || overlaps(entry.getJSONObject("current"), from, until))) out.put(entry);
            }
            return out;
        }
        public boolean hasEdits(String scope, String kind, long from, long until) throws Exception { return range(scope, kind, from, until).length() > 0; }
        public JSONObject find(String scope, String id) { return entries.get(scope + id); }
        public void archiveStress(String scope, String id, String op, JSONObject original, JSONObject anchor, String payload) {
            if (rejectArchive) throw new IllegalStateException("test archive failure");
            stressArchives.put(op, payload);
        }
        final Map<String, String> stressArchives = new LinkedHashMap<>();
        boolean rejectArchive;
        public JSONArray history(String scope, String id) { return versions.getOrDefault(scope + id, new JSONArray()); }
        public boolean save(String scope, String id, JSONObject original, JSONObject expected, JSONObject next) throws Exception {
            JSONArray old = history(scope, id);
            JSONArray before = new JSONArray().put(HealthHistory.version(expected, now++, old.length() == 0));
            for (int i = 0; i < old.length(); i++) before.put(old.getJSONObject(i));
            versions.put(scope + id, before);
            entries.put(scope + id, HealthHistory.entry(id, original, next));
            return true;
        }
    }
}

package cn.local.micompanion.health;

import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import java.nio.charset.StandardCharsets;
import org.json.JSONObject;

/** Transaction bodies for all new writeback kinds. Authentication and commit belong to the caller. */
final class HealthWriteback {
    static final String HISTORY = "micompanion_health_history";
    private static final String INDEX = "micompanion_health_history_row";
    private static final String CREATE_HISTORY = "CREATE TABLE IF NOT EXISTS " + HISTORY
            + " (seq INTEGER PRIMARY KEY AUTOINCREMENT, op TEXT NOT NULL UNIQUE, kind TEXT NOT NULL, sid TEXT NOT NULL,"
            + " key TEXT NOT NULL, origin_time INTEGER NOT NULL, old_time INTEGER NOT NULL, new_time INTEGER NOT NULL,"
            + " request TEXT NOT NULL, before_snapshot TEXT NOT NULL, after_snapshot TEXT NOT NULL,"
            + " backup TEXT NOT NULL, after_sha256 TEXT NOT NULL, saved_at INTEGER NOT NULL)";
    private static final String CREATE_INDEX = "CREATE INDEX IF NOT EXISTS " + INDEX + " ON " + HISTORY + "(kind,sid,key,new_time,seq)";
    static final long MAX_TIME = 253402300799L;

    interface Apply { void run() throws Exception; }
    static final class Change {
        final String oldRaw;
        final JSONObject before, after;
        final long newTime;
        final Apply apply;
        Change(String oldRaw, JSONObject before, JSONObject after, long newTime, Apply apply) {
            this.oldRaw = oldRaw; this.before = before; this.after = after; this.newTime = newTime; this.apply = apply;
        }
    }
    static final class Request {
        final String scope, kind, sid, key, priorHash, op, wire;
        final long rowTime, restoreSeq;
        final JSONObject target;
        String restoreRaw;
        Request(JSONObject query) throws Exception {
            wire = query.toString();
            scope = text(query, "scope"); kind = text(query, "kind"); sid = text(query, "sid"); key = text(query, "key");
            priorHash = text(query, "prior_sha256"); op = text(query, "op");
            rowTime = number(query, "row_time");
            restoreSeq = query.has("restore_seq") ? number(query, "restore_seq") : 0;
            target = query.getJSONObject("target");
            if (wire.length() > 100000 || !scope.matches("[a-f0-9]{64}") || !priorHash.matches("[a-f0-9]{64}")
                    || sid.isEmpty() || sid.length() > 256 || sid.chars().anyMatch(Character::isISOControl)
                    || rowTime < 1 || rowTime > MAX_TIME || restoreSeq < 0 || !targetKey(kind, key)) throw new Rejected("unsupported");
        }
    }
    static final class Rejected extends Exception {
        final String status;
        Rejected(String status) { this.status = status; }
    }
    private static boolean targetKey(String kind, String key) {
        if ("heart_rate".equals(kind)) return "heart_rate".equals(key) || "single_heart_rate".equals(key);
        if ("spo2".equals(kind)) return "spo2".equals(key) || "single_spo2".equals(key);
        return "sleep".equals(kind) && "sleep".equals(key);
    }
    private static String text(JSONObject o, String key) throws Exception {
        Object v = o.get(key);
        if (!(v instanceof String)) throw new Rejected("unsupported");
        return (String)v;
    }
    static long number(JSONObject o, String key) throws Exception {
        Object v = o.get(key);
        if (!(v instanceof Integer || v instanceof Long)) throw new Rejected("unsupported");
        return ((Number)v).longValue();
    }

    static boolean write(SQLiteDatabase db, Request r) throws Exception {
        boolean hasHistory = known(db, HISTORY, "table", CREATE_HISTORY);
        known(db, INDEX, "index", CREATE_INDEX);
        verifyTriggers(db, HISTORY);
        long originalTime = r.rowTime;
        if (hasHistory) {
            try (Cursor prior = db.rawQuery("SELECT request FROM " + HISTORY + " WHERE op=? LIMIT 2", new String[]{r.op})) {
                if (prior.moveToFirst()) {
                    if (!r.wire.equals(prior.getString(0)) || prior.moveToNext()) throw new Rejected("conflict");
                    return true;
                }
            }
            try (Cursor last = db.rawQuery("SELECT after_sha256 FROM " + HISTORY
                    + " WHERE kind=? AND sid=? AND key=? AND new_time=? ORDER BY seq DESC LIMIT 1",
                    new String[]{r.kind,r.sid,r.key,Long.toString(r.rowTime)})) {
                if (last.moveToFirst() && !r.priorHash.equals(last.getString(0))) throw new Rejected("history_diverged");
            }
            if (r.restoreSeq > 0) {
                try (Cursor saved = db.rawQuery("SELECT backup,before_snapshot FROM " + HISTORY + " WHERE seq=? AND kind=? AND sid=? AND key=? AND origin_time=?",
                        new String[]{Long.toString(r.restoreSeq),r.kind,r.sid,r.key,Long.toString(originalTime)})) {
                    if (!saved.moveToFirst() || !r.target.toString().equals(saved.getString(1))) throw new Rejected("conflict");
                    r.restoreRaw = saved.getString(0);
                }
            }
        } else if (r.restoreSeq > 0) throw new Rejected("conflict");
        Change change = "sleep".equals(r.kind) ? SleepWriteback.prepare(db, r) : VitalWriteback.prepare(db, r);
        if (change.before.toString().equals(change.after.toString()) && (r.restoreRaw == null || r.restoreRaw.equals(change.oldRaw))) return false;
        if (change.newTime != r.rowTime) throw new Rejected("unsupported");
        db.execSQL(CREATE_HISTORY);
        db.execSQL(CREATE_INDEX);
        db.execSQL("INSERT INTO " + HISTORY + "(op,kind,sid,key,origin_time,old_time,new_time,request,before_snapshot,after_snapshot,backup,after_sha256,saved_at) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?)",
                new Object[]{r.op,r.kind,r.sid,r.key,originalTime,r.rowTime,change.newTime,r.wire,change.before.toString(),change.after.toString(),change.oldRaw,"",System.currentTimeMillis()});
        change.apply.run();
        String table = "sleep".equals(r.kind) ? "sleep_segment" : "heart_rate".equals(r.kind) ? "hr_record" : "spo2_record";
        try (Cursor row = db.rawQuery("SELECT value FROM " + table + " WHERE key=? AND sid=? AND time=? AND isDeleted=0 LIMIT 2",
                new String[]{r.key,r.sid,Long.toString(r.rowTime)})) {
            if (!row.moveToFirst() || row.isNull(0)) throw new Rejected("conflict");
            String hash = StressWriteback.digest(row.getString(0).getBytes(StandardCharsets.UTF_8));
            if (row.moveToNext()) throw new Rejected("conflict");
            db.execSQL("UPDATE " + HISTORY + " SET after_sha256=? WHERE op=?", new Object[]{hash,r.op});
        }
        return true;
    }

    static void requirePreimage(Request r, String raw) throws Exception {
        if (raw == null || raw.isEmpty() || raw.length() > 262144) throw new Rejected("unsupported");
        if (!r.priorHash.equals(StressWriteback.digest(raw.getBytes(StandardCharsets.UTF_8)))) throw new Rejected("conflict");
    }

    private static boolean known(SQLiteDatabase db, String name, String type, String ddl) throws Exception {
        try (Cursor c = db.rawQuery("SELECT type,sql FROM sqlite_master WHERE name=? COLLATE NOCASE LIMIT 2", new String[]{name})) {
            if (!c.moveToFirst()) return false;
            if (!type.equals(c.getString(0)) || (ddl != null && !ddl.replace("IF NOT EXISTS ", "").equalsIgnoreCase(c.getString(1))) || c.moveToNext()) throw new Rejected("unsupported");
            return true;
        }
    }
    static void verifyTable(SQLiteDatabase db, String table) throws Exception {
        if (!("hr_record".equals(table) || "spo2_record".equals(table) || "sleep_segment".equals(table))
                || !known(db, table, "table", null)) throw new Rejected("unsupported");
        int pk = 0;
        boolean key=false, sid=false, time=false, value=false, deleted=false;
        try (Cursor c = db.rawQuery("PRAGMA table_info(" + table + ")", null)) {
            while (c.moveToNext()) {
                String col=c.getString(1), type=c.getString(2);
                int order=c.getInt(5);
                if (order>0) pk++;
                if ("key".equals(col)) key=order>0 && "TEXT".equalsIgnoreCase(type);
                if ("sid".equals(col)) sid=order>0 && "TEXT".equalsIgnoreCase(type);
                if ("time".equals(col)) time=order>0 && "INTEGER".equalsIgnoreCase(type);
                if ("value".equals(col)) value=order==0 && "TEXT".equalsIgnoreCase(type);
                if ("isDeleted".equals(col)) deleted=order==0 && "INTEGER".equalsIgnoreCase(type);
            }
        }
        if (pk!=3 || !(key && sid && time && value && deleted)) throw new Rejected("unsupported");
        verifyTriggers(db, table);
    }
    private static void verifyTriggers(SQLiteDatabase db, String table) throws Exception {
        try (Cursor c = db.rawQuery("SELECT name,sql FROM sqlite_master WHERE tbl_name=? COLLATE NOCASE AND type='trigger'", new String[]{table})) {
            while (c.moveToNext()) {
                String name=c.getString(0), sql=c.getString(1), prefix="_trigger_"+table;
                String event;
                if (HISTORY.equals(table) || sql==null) throw new Rejected("unsupported");
                if (name.equals(prefix+"_insert")) event="INSERT ON "+table+" FOR EACH ROW WHEN ";
                else if (name.equals(prefix+"_update_del")) event="UPDATE OF isDeleted ON "+table+" FOR EACH ROW WHEN ";
                else if (name.equals(prefix+"_hard_del")) event="DELETE ON "+table+" BEGIN ";
                else throw new Rejected("unsupported");
                if (!sql.replaceAll("[ \\t\\r\\n]+", " ").startsWith("CREATE TRIGGER " + name + " AFTER " + event)) throw new Rejected("unsupported");
            }
        }
    }
}

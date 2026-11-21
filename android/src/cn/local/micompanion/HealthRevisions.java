package cn.local.micompanion;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import java.io.File;
import org.json.JSONArray;
import org.json.JSONObject;

/** Revisions are stored in a private SQLite database, separate from the synchronized copy. */
final class HealthRevisions implements HealthHistory.Store {
    private static final String FILE = "health_detail_revisions.db";
    private static final int MAX_HISTORY = 1000;
    private static final String RANGE = "scope=? AND kind=? AND ((original_start<? AND original_end>?)"
            + " OR (current_start<? AND current_end>?))";
    private static final String ITEM = "scope=? AND id=?";
    private final Context context;
    private final File file;

    HealthRevisions(Context context) {
        this.context = context;
        file = context.getDatabasePath(FILE);
    }

    @Override public JSONArray range(String scope, String kind, long from, long until) throws Exception {
        JSONArray out = new JSONArray();
        if (!file.isFile()) return out;
        int limit = "sleep".equals(kind) ? 64 : 12000;
        try (Helper helper = new Helper(context);
             Cursor rows = helper.getReadableDatabase().query("entries", new String[]{"id", "original", "current"},
                     RANGE, bounds(scope, kind, from, until), null, null, null, Integer.toString(limit + 1))) {
            while (rows.moveToNext()) {
                if (out.length() == limit) throw new IllegalArgumentException("本地健康记录超过查询上限");
                out.put(entry(rows));
            }
        }
        return out;
    }

    @Override public boolean hasEdits(String scope, String kind, long from, long until) {
        if (!file.isFile()) return false;
        try (Helper helper = new Helper(context);
             Cursor rows = helper.getReadableDatabase().query("entries", new String[]{"1"}, RANGE,
                     bounds(scope, kind, from, until), null, null, null, "1")) {
            return rows.moveToFirst();
        }
    }

    @Override public JSONObject find(String scope, String id) throws Exception {
        if (!file.isFile()) return null;
        try (Helper helper = new Helper(context)) {
            return find(helper.getReadableDatabase(), scope, id);
        }
    }

    @Override public JSONArray history(String scope, String id) throws Exception {
        JSONArray out = new JSONArray();
        if (!file.isFile()) return out;
        try (Helper helper = new Helper(context)) {
            // One statement observes both the original and preceding revisions in the same read snapshot.
            try (Cursor rows = helper.getReadableDatabase().rawQuery(
                    "SELECT snapshot,saved_at,seq FROM (SELECT snapshot,saved_at,seq FROM backups WHERE " + ITEM
                    + " UNION ALL SELECT original,original_saved_at,0 FROM entries WHERE " + ITEM + ")"
                    + " ORDER BY seq DESC LIMIT " + (MAX_HISTORY + 1), new String[]{scope, id, scope, id})) {
                while (rows.moveToNext()) {
                    if (out.length() == MAX_HISTORY) throw new IllegalStateException("健康版本超过查询上限");
                    out.put(HealthHistory.version(new JSONObject(rows.getString(0)), rows.getLong(1), rows.getLong(2) == 0));
                }
            }
        }
        return out;
    }

    @Override public boolean save(String scope, String id, JSONObject original, JSONObject expected, JSONObject next) throws Exception {
        if (HealthHistory.same(expected, next)) return false;
        try (Helper helper = new Helper(context)) {
            SQLiteDatabase db = helper.getWritableDatabase();
            db.beginTransaction();
            try {
                JSONObject previous = find(db, scope, id);
                JSONObject current = previous == null ? original : previous.getJSONObject("current");
                if (!HealthHistory.same(expected, current) || (previous != null
                        && !HealthHistory.same(original, previous.getJSONObject("original")))) {
                    throw new IllegalStateException("这条记录已变更，请刷新后再编辑");
                }
                long now = System.currentTimeMillis();
                ContentValues values = new ContentValues();
                values.put("current", next.toString());
                values.put("current_start", next.getLong("start"));
                values.put("current_end", end(next));
                if (previous == null) {
                    values.put("scope", scope);
                    values.put("id", id);
                    values.put("kind", id.substring(0, id.indexOf(':')));
                    values.put("original", original.toString());
                    values.put("original_start", original.getLong("start"));
                    values.put("original_end", end(original));
                    values.put("original_saved_at", now);
                    db.insertOrThrow("entries", null, values);
                } else {
                    try (Cursor count = db.rawQuery("SELECT COUNT(*) FROM backups WHERE " + ITEM, new String[]{scope, id})) {
                        if (!count.moveToFirst() || count.getLong(0) >= MAX_HISTORY - 1) {
                            throw new IllegalStateException("这条记录的备份已达到上限，未修改记录");
                        }
                    }
                    ContentValues backup = new ContentValues();
                    backup.put("scope", scope);
                    backup.put("id", id);
                    backup.put("snapshot", current.toString());
                    backup.put("saved_at", now);
                    db.insertOrThrow("backups", null, backup);
                    if (db.update("entries", values, ITEM, new String[]{scope, id}) != 1) {
                        throw new IllegalStateException("未能保存健康记录");
                    }
                }
                db.setTransactionSuccessful();
                return true;
            } finally {
                db.endTransaction();
            }
        }
    }

    @Override public void archiveStress(String scope, String id, String op, JSONObject original, JSONObject anchor, String payload) {
        try (Helper helper = new Helper(context)) {
            ContentValues row = new ContentValues();
            row.put("scope", scope);
            row.put("id", id);
            row.put("op", op);
            row.put("original", original.toString());
            row.put("anchor", anchor.toString());
            row.put("payload", payload);
            row.put("saved_at", System.currentTimeMillis());
            helper.getWritableDatabase().insertOrThrow("stress_preimages", null, row);
        }
    }

    private static JSONObject find(SQLiteDatabase db, String scope, String id) throws Exception {
        try (Cursor rows = db.query("entries", new String[]{"id", "original", "current"}, ITEM,
                new String[]{scope, id}, null, null, null, "1")) {
            return rows.moveToFirst() ? entry(rows) : null;
        }
    }

    private static JSONObject entry(Cursor rows) throws Exception {
        return HealthHistory.entry(rows.getString(0), new JSONObject(rows.getString(1)), new JSONObject(rows.getString(2)));
    }

    private static long end(JSONObject snapshot) throws Exception {
        return snapshot.has("end") ? snapshot.getLong("end") : snapshot.getLong("start") + 1;
    }

    private static String[] bounds(String scope, String kind, long from, long until) {
        return new String[]{scope, kind, Long.toString(until), Long.toString(from), Long.toString(until), Long.toString(from)};
    }

    private static final class Helper extends SQLiteOpenHelper {
        Helper(Context context) { super(context, FILE, null, 2); }

        @Override public void onCreate(SQLiteDatabase db) {
            db.execSQL("CREATE TABLE entries (scope TEXT NOT NULL,id TEXT NOT NULL,kind TEXT NOT NULL,"
                    + "original TEXT NOT NULL,original_start INTEGER NOT NULL,original_end INTEGER NOT NULL,"
                    + "original_saved_at INTEGER NOT NULL,current TEXT NOT NULL,current_start INTEGER NOT NULL,"
                    + "current_end INTEGER NOT NULL,PRIMARY KEY(scope,id))");
            db.execSQL("CREATE INDEX entries_range ON entries(scope,kind)");
            db.execSQL("CREATE TABLE backups (seq INTEGER PRIMARY KEY,scope TEXT NOT NULL,id TEXT NOT NULL,"
                    + "snapshot TEXT NOT NULL,saved_at INTEGER NOT NULL)");
            db.execSQL("CREATE INDEX backups_entry ON backups(scope,id,seq)");
            createStressArchive(db);
        }

        private static void createStressArchive(SQLiteDatabase db) {
            db.execSQL("CREATE TABLE stress_preimages (scope TEXT NOT NULL,id TEXT NOT NULL,"
                    + "op TEXT NOT NULL PRIMARY KEY, original TEXT NOT NULL,anchor TEXT NOT NULL,"
                    + "payload TEXT NOT NULL,saved_at INTEGER NOT NULL)");
        }

        @Override public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
            if (oldVersion == 1 && newVersion == 2) createStressArchive(db);
            else throw new IllegalStateException("无法识别健康编辑数据库版本");
        }
    }
}

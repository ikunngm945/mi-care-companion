package cn.local.micompanion.health;

import android.content.ContentProviderClient;
import android.content.Context;
import android.content.pm.PackageManager;
import android.content.pm.ProviderInfo;
import android.content.pm.Signature;
import android.content.pm.SigningInfo;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteException;
import android.database.sqlite.SQLiteStatement;
import android.net.Uri;
import android.os.Bundle;
import android.system.Os;
import android.system.StructStat;
import java.io.File;
import java.security.MessageDigest;
import org.json.JSONException;
import org.json.JSONObject;

/** Single-record compare-and-swap on the logged-in Xiaomi account's source database. */
final class StressWriteback {
    private static final String CREATE_HISTORY = "CREATE TABLE IF NOT EXISTS micompanion_stress_history ("
            + "seq INTEGER PRIMARY KEY AUTOINCREMENT, op TEXT NOT NULL UNIQUE, sid TEXT NOT NULL, key TEXT NOT NULL, "
            + "row_time INTEGER NOT NULL, point_time INTEGER NOT NULL, before_value INTEGER NOT NULL, "
            + "after_value INTEGER NOT NULL, backup TEXT NOT NULL, saved_at INTEGER NOT NULL)";
    private static final String CREATE_INDEX = "CREATE INDEX IF NOT EXISTS micompanion_stress_row_history "
            + "ON micompanion_stress_history(row_time,sid,key,seq)";
    private static final String READ_OP = "SELECT sid,key,row_time,point_time,before_value,after_value "
            + "FROM micompanion_stress_history WHERE op=? LIMIT 2";
    private static final String READ_ROW = "SELECT CASE WHEN typeof(value)='text' AND length(CAST(value AS BLOB))<=65536 THEN value END "
            + "FROM stress_record WHERE key=? AND sid=? AND time=? AND isDeleted=0 LIMIT 2";
    private static final String READ_PAYLOAD = "WITH payload AS (SELECT CASE WHEN json_valid(?1) THEN ?1 END AS raw) "
            + "SELECT (SELECT count(*) FROM json_each(raw) WHERE key='stress'),"
            + " (SELECT count(*) FROM json_each(raw) WHERE key='time'),"
            + " CASE WHEN json_type(raw,'$.stress')='integer' THEN json_extract(raw,'$.stress') END,"
            + " CASE WHEN json_type(raw,'$.time')='integer' THEN json_extract(raw,'$.time') END FROM payload";
    private static final String INSERT_BACKUP = "INSERT INTO micompanion_stress_history "
            + "(op,sid,key,row_time,point_time,before_value,after_value,backup,saved_at) VALUES (?,?,?,?,?,?,?,?,?)";
    private static final String UPDATE_ROW = "UPDATE stress_record SET value=json_replace(value,'$.stress',CAST(? AS INTEGER)) "
            + "WHERE key=? AND sid=? AND time=? AND isDeleted=0 AND value=? COLLATE BINARY";
    private static final long MAX_EPOCH = 253402300799L;
    private static final String COMPANION = "cn.local.micompanion";
    private static final String AUTHORITY = COMPANION + ".stress-writeback";
    private static final String SIGNER = "b6385adebefa6bf18dcfceba997cbe998287b42f872aa8acbe274897a8453515";

    private StressWriteback() { }

    static JSONObject authorized(Context context, BandFileImporter importer, JSONObject query, Runnable refresh) throws JSONException {
        String op = query.optString("op", "");
        if (!op.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")) return status("unauthorized");
        boolean allowed = false;
        try {
            PackageManager pm = context.getPackageManager();
            ProviderInfo provider = pm.resolveContentProvider(AUTHORITY, 0);
            if (provider == null || !COMPANION.equals(provider.packageName)) return status("unauthorized");
            SigningInfo signing = pm.getPackageInfo(COMPANION, PackageManager.GET_SIGNING_CERTIFICATES).signingInfo;
            Signature[] signers = signing == null ? null : signing.getApkContentsSigners();
            if (signers == null || signers.length != 1 || !SIGNER.equals(fingerprint(signers[0]))) return status("unauthorized");
            Uri uri = Uri.parse("content://" + AUTHORITY);
            try (ContentProviderClient peer = context.getContentResolver().acquireUnstableContentProviderClient(uri)) {
                if (peer == null) return status("unauthorized");
                Bundle params = new Bundle();
                params.putString("request", query.toString());
                Bundle auth = peer.call("authorize", op, params);
                if (auth == null || !auth.getBoolean("allowed", false)) return status("unauthorized");
                allowed = true;
                JSONObject result = "health_writeback".equals(query.optString("action")) ? handleExtended(importer, query) : handle(importer, query);
                String state = result.getString("status");
                try {
                    params.putString("status", state);
                    params.putBoolean("changed", result.optBoolean("changed", false));
                    Bundle receipt = peer.call("complete", op, params);
                    return receipt != null && receipt.getBoolean("allowed", false) ? result : status("failed");
                } finally {
                    if ("ok".equals(state)) refresh.run();
                }
            }
        } catch (Exception unavailable) {
            return status(allowed ? "failed" : "unauthorized");
        }
    }

    private static String fingerprint(Signature certificate) throws Exception { return digest(certificate.toByteArray()); }

    static String digest(byte[] bytes) throws Exception {
        StringBuilder hex = new StringBuilder(64);
        for (byte b : MessageDigest.getInstance("SHA-256").digest(bytes)) {
            hex.append(Character.forDigit((b >>> 4) & 15, 16)).append(Character.forDigit(b & 15, 16));
        }
        return hex.toString();
    }

    private static JSONObject status(String code) throws JSONException { return new JSONObject().put("status", code); }

    private static JSONObject handle(BandFileImporter importer, JSONObject query) throws JSONException {
        Request request;
        try { request = new Request(query); }
        catch (Exception invalid) { return new JSONObject().put("status", "unsupported"); }
        try {
            Source source = active(importer, request.scope);
            boolean changed;
            try (SQLiteDatabase db = SQLiteDatabase.openDatabase(source.file.getPath(), null,
                    SQLiteDatabase.OPEN_READWRITE | SQLiteDatabase.NO_LOCALIZED_COLLATORS)) {
                db.beginTransactionNonExclusive();
                try {
                    changed = write(db, request);
                    Source current = active(importer, request.scope);
                    if (!source.sameFile(current)) throw new Rejected("account");
                    db.setTransactionSuccessful();
                } finally {
                    db.endTransaction();
                }
            }
            return new JSONObject().put("status", "ok").put("changed", changed);
        } catch (Rejected rejection) {
            return new JSONObject().put("status", rejection.status);
        } catch (Exception failure) {
            // Paths, row data and SQLite exception detail never cross the link or enter logs here.
            return new JSONObject().put("status", "failed");
        }
    }

    private static JSONObject handleExtended(BandFileImporter importer, JSONObject query) throws JSONException {
        HealthWriteback.Request request;
        try { request = new HealthWriteback.Request(query); }
        catch (Exception invalid) { return status("unsupported"); }
        try {
            Source source = active(importer, request.scope);
            boolean changed;
            try (SQLiteDatabase db = SQLiteDatabase.openDatabase(source.file.getPath(), null,
                    SQLiteDatabase.OPEN_READWRITE | SQLiteDatabase.NO_LOCALIZED_COLLATORS)) {
                db.beginTransactionNonExclusive();
                try {
                    changed = HealthWriteback.write(db, request);
                    if (!source.sameFile(active(importer, request.scope))) throw new Rejected("account");
                    db.setTransactionSuccessful();
                } finally { db.endTransaction(); }
            }
            return status("ok").put("changed", changed);
        } catch (HealthWriteback.Rejected rejection) { return status(rejection.status); }
        catch (Rejected rejection) { return status(rejection.status); }
        catch (Exception failure) { return status("failed"); }
    }

    private static boolean write(SQLiteDatabase db, Request request) throws Exception {
        verifySchema(db);
        if (knownObject(db, "micompanion_stress_history", CREATE_HISTORY)) {
            try (Cursor row = db.rawQuery(READ_OP, new String[]{request.op})) {
                if (row.moveToFirst()) {
                    if (!request.sid.equals(row.getString(0)) || !request.key.equals(row.getString(1))
                            || request.rowTime != row.getLong(2) || request.pointTime != row.getLong(3)
                            || request.expected != row.getInt(4) || request.value != row.getInt(5) || row.moveToNext()) {
                        throw new Rejected("conflict");
                    }
                    return true;
                }
            } catch (SQLiteException brokenHistory) { throw new Rejected("unsupported"); }
        }
        String[] identity = {request.key, request.sid, Long.toString(request.rowTime)};
        String original = readRow(db, identity);
        if (observed(db, original, request) != request.expected
                || !request.priorHash.equals(digest(original.getBytes(java.nio.charset.StandardCharsets.UTF_8)))) {
            throw new Rejected("conflict");
        }
        if (request.expected == request.value) return false;

        knownObject(db, "micompanion_stress_row_history", CREATE_INDEX);
        db.execSQL(CREATE_HISTORY);
        db.execSQL(CREATE_INDEX);
        db.execSQL(INSERT_BACKUP, new Object[]{request.op, request.sid, request.key, request.rowTime,
                request.pointTime, request.expected, request.value, original, System.currentTimeMillis()});
        try (SQLiteStatement update = db.compileStatement(UPDATE_ROW)) {
            update.bindLong(1, request.value);
            for (int i = 0; i < identity.length; i++) update.bindString(i + 2, identity[i]);
            update.bindString(5, original);
            if (update.executeUpdateDelete() != 1) throw new Rejected("conflict");
        }
        // Some JSON1 versions do not resolve escape-spelled member names in paths. Require the intended result.
        if (observed(db, readRow(db, identity), request) != request.value) throw new Rejected("unsupported");
        return true;
    }

    private static String readRow(SQLiteDatabase db, String[] identity) throws Rejected {
        try (Cursor row = db.rawQuery(READ_ROW, identity)) {
            if (!row.moveToFirst()) throw new Rejected("conflict");
            String payload = row.getString(0);
            if (row.moveToNext()) throw new Rejected("conflict");
            if (payload == null) throw new Rejected("unsupported");
            return payload;
        }
    }

    private static long observed(SQLiteDatabase db, String raw, Request request) throws Rejected {
        try (Cursor row = db.rawQuery(READ_PAYLOAD, new String[]{raw})) {
            if (!row.moveToFirst() || row.getInt(0) != 1 || row.getInt(1) > 1 || row.getType(2) != Cursor.FIELD_TYPE_INTEGER
                    || (row.getInt(1) == 1 && row.getType(3) != Cursor.FIELD_TYPE_INTEGER)) throw new Rejected("unsupported");
            long stress = row.getLong(2);
            if (stress < 1 || stress > 100) throw new Rejected("unsupported");
            long pointTime = row.getInt(1) == 0 ? request.rowTime : row.getLong(3);
            if (pointTime != request.pointTime) throw new Rejected("conflict");
            return stress;
        } catch (SQLiteException unknownPayloadOrJson1) { throw new Rejected("unsupported"); }
    }

    private static boolean knownObject(SQLiteDatabase db, String name, String declaration) throws Rejected {
        try (Cursor row = db.rawQuery("SELECT type,sql FROM sqlite_master WHERE name=? COLLATE NOCASE LIMIT 2", new String[]{name})) {
            if (!row.moveToFirst()) return false;
            // SQLite stores the normalized CREATE without its IF NOT EXISTS clause.
            String actual = row.getString(1);
            boolean matches = declaration == null ? "table".equals(row.getString(0))
                    : declaration.replace("IF NOT EXISTS ", "").equalsIgnoreCase(actual);
            if (!matches || row.moveToNext()) throw new Rejected("unsupported");
            return true;
        }
    }

    private static void verifySchema(SQLiteDatabase db) throws Rejected {
        if (!knownObject(db, "stress_record", null)) throw new Rejected("unsupported");
        boolean key = false, sid = false, time = false, value = false, deleted = false;
        int pkColumns = 0;
        try (Cursor cols = db.rawQuery("PRAGMA table_info(stress_record)", null)) {
            while (cols.moveToNext()) {
                String column = cols.getString(1), type = cols.getString(2);
                int pk = cols.getInt(5);
                if (pk > 0) pkColumns++;
                if ("key".equals(column)) key = pk > 0 && "TEXT".equalsIgnoreCase(type);
                else if ("sid".equals(column)) sid = pk > 0 && "TEXT".equalsIgnoreCase(type);
                else if ("time".equals(column)) time = pk > 0 && "INTEGER".equalsIgnoreCase(type);
                else if ("value".equals(column)) value = "TEXT".equalsIgnoreCase(type);
                else if ("isDeleted".equals(column)) deleted = "INTEGER".equalsIgnoreCase(type);
            }
        }
        if (pkColumns != 3 || !(key && sid && time && value && deleted)) throw new Rejected("unsupported");
        // Known Xiaomi draft triggers cannot run on UPDATE OF value. Refuse any other trigger,
        // including changes to a known trigger's event or any trigger on our backup table.
        try (Cursor triggers = db.rawQuery("SELECT tbl_name,name,sql FROM sqlite_master WHERE type='trigger' "
                + "AND tbl_name COLLATE NOCASE IN ('stress_record','micompanion_stress_history')", null)) {
            while (triggers.moveToNext()) {
                if (!"stress_record".equalsIgnoreCase(triggers.getString(0))
                        || !safeDraftTrigger(triggers.getString(1), triggers.getString(2))) {
                    throw new Rejected("unsupported");
                }
            }
        }
    }

    static boolean safeDraftTrigger(String name, String sql) {
        if (name == null || sql == null) return false;
        String event;
        switch (name) {
            case "_trigger_stress_record_insert": event = "INSERT ON stress_record FOR EACH ROW WHEN "; break;
            case "_trigger_stress_record_update_del": event = "UPDATE OF isDeleted ON stress_record FOR EACH ROW WHEN "; break;
            case "_trigger_stress_record_hard_del": event = "DELETE ON stress_record BEGIN "; break;
            default: return false;
        }
        return sql.replaceAll("[ \\t\\r\\n]+", " ").startsWith("CREATE TRIGGER " + name + " AFTER " + event);
    }

    private static Source active(BandFileImporter importer, String scope) throws Rejected {
        try {
            File dir = importer.accountDirectory();
            if (!scope.equals(BandFileImporter.scope(dir))) throw new Rejected("account");
            File actual = dir.getCanonicalFile();
            if (!scope.equals(BandFileImporter.scope(actual))) throw new Rejected("account");
            File file = new File(actual, "fitness_data");
            if (!file.equals(file.getCanonicalFile()) || !file.isFile()) throw new Rejected("account");
            return new Source(file, Os.stat(file.getPath()));
        } catch (Exception unverified) { throw new Rejected("account"); }
    }

    private static final class Source {
        final File file;
        final StructStat stat;
        Source(File file, StructStat stat) { this.file = file; this.stat = stat; }
        boolean sameFile(Source other) {
            return file.equals(other.file) && stat.st_dev == other.stat.st_dev && stat.st_ino == other.stat.st_ino;
        }
    }

    private static final class Request {
        final String scope, sid, key, op, priorHash;
        final long rowTime, pointTime, expected, value;
        Request(JSONObject query) throws JSONException {
            scope = text(query, "scope");
            sid = text(query, "sid");
            key = text(query, "key");
            op = text(query, "op");
            priorHash = text(query, "prior_sha256");
            if (!priorHash.matches("[0-9a-f]{64}")) throw new IllegalArgumentException();
            rowTime = integer(query, "row_time", MAX_EPOCH);
            pointTime = integer(query, "point_time", MAX_EPOCH);
            expected = integer(query, "expected", 100);
            value = integer(query, "value", 100);
            if (!scope.matches("[0-9a-f]{64}") || sid.isEmpty() || sid.length() > 256
                    || sid.chars().anyMatch(Character::isISOControl)
                    || !("stress".equals(key) || "single_stress".equals(key))
                    || !op.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")) {
                throw new IllegalArgumentException();
            }
        }
        private static String text(JSONObject query, String key) throws JSONException {
            Object result = query.get(key);
            if (!(result instanceof String)) throw new IllegalArgumentException();
            return (String) result;
        }
        private static long integer(JSONObject query, String key, long max) throws JSONException {
            Object result = query.get(key);
            if (!(result instanceof Integer || result instanceof Long)) throw new IllegalArgumentException();
            long value = ((Number)result).longValue();
            if (value < 1 || value > max) throw new IllegalArgumentException();
            return value;
        }
    }

    private static final class Rejected extends Exception {
        final String status;
        Rejected(String status) { this.status = status; }
    }
}

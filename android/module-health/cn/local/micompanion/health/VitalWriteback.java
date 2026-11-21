package cn.local.micompanion.health;

import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteException;
import android.database.sqlite.SQLiteStatement;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import org.json.JSONObject;

/** Prepares a single value-only heart rate or oxygen saturation change for the shared writer. */
final class VitalWriteback {
    private static final String READ_ROW = "SELECT CASE WHEN typeof(value)='text' AND length(CAST(value AS BLOB))<=65536 THEN value END "
            + "FROM %1$s WHERE key=? COLLATE BINARY AND sid=? COLLATE BINARY AND time=? AND isDeleted=0 LIMIT 2";
    private static final String READ_PAYLOAD = "WITH payload AS (SELECT CASE WHEN json_valid(?1) THEN ?1 END AS raw) "
            + "SELECT (SELECT count(*) FROM json_each(raw) WHERE key='%2$s'),"
            + " (SELECT count(*) FROM json_each(raw) WHERE key='time'),"
            + " CASE WHEN json_type(raw,'$.%2$s')='integer' THEN json_extract(raw,'$.%2$s') END,"
            + " CASE WHEN json_type(raw,'$.time')='integer' THEN json_extract(raw,'$.time') END FROM payload";
    private static final String UPDATE_ROW = "UPDATE %1$s SET value=json_replace(value,'$.%2$s',CAST(? AS INTEGER)) "
            + "WHERE key=? COLLATE BINARY AND sid=? COLLATE BINARY AND time=? AND isDeleted=0 AND value=? COLLATE BINARY";
    private static final String RESTORE_ROW = "UPDATE %1$s SET value=? "
            + "WHERE key=? COLLATE BINARY AND sid=? COLLATE BINARY AND time=? AND isDeleted=0 AND value=? COLLATE BINARY";

    private VitalWriteback() { }

    static HealthWriteback.Change prepare(SQLiteDatabase db, HealthWriteback.Request r) throws Exception {
        boolean heart = "heart_rate".equals(r.kind);
        if (!(heart || "spo2".equals(r.kind)) || !(r.kind.equals(r.key) || ("single_" + r.kind).equals(r.key))) {
            throw new HealthWriteback.Rejected("unsupported");
        }
        String table = heart ? "hr_record" : "spo2_record";
        String field = heart ? "bpm" : "spo2";
        int max = heart ? 300 : 100;
        long targetStart = integer(r.target, "start", HealthWriteback.MAX_TIME);
        long targetValue = integer(r.target, "value", max);
        HealthWriteback.verifyTable(db, table);
        String[] identity = {r.key, r.sid, Long.toString(r.rowTime)};
        String readSql = sql(READ_ROW, table, field), payloadSql = sql(READ_PAYLOAD, table, field);
        String raw = readRow(db, readSql, identity);
        HealthWriteback.requirePreimage(r, raw);
        JSONObject before = projection(db, payloadSql, raw, r.rowTime, max);
        if (before.getLong("start") != targetStart) throw new HealthWriteback.Rejected("conflict");
        JSONObject after = new JSONObject().put("start", targetStart).put("value", targetValue);
        String restore = r.restoreRaw;
        if (restore != null) {
            if (restore.getBytes(StandardCharsets.UTF_8).length > 65536) throw new HealthWriteback.Rejected("unsupported");
            JSONObject saved = projection(db, payloadSql, restore, r.rowTime, max);
            if (saved.getLong("start") != targetStart || saved.getLong("value") != targetValue) {
                throw new HealthWriteback.Rejected("conflict");
            }
        }
        return new HealthWriteback.Change(raw, before, after, r.rowTime, () -> {
            try (SQLiteStatement update = db.compileStatement(sql(restore == null ? UPDATE_ROW : RESTORE_ROW, table, field))) {
                if (restore == null) update.bindLong(1, targetValue);
                else update.bindString(1, restore);
                for (int i = 0; i < identity.length; i++) update.bindString(i + 2, identity[i]);
                update.bindString(5, raw);
                if (update.executeUpdateDelete() != 1) throw new HealthWriteback.Rejected("conflict");
            }
            String current = readRow(db, readSql, identity);
            JSONObject observed = projection(db, payloadSql, current, r.rowTime, max);
            if ((restore != null && !restore.equals(current)) || observed.getLong("start") != targetStart
                    || observed.getLong("value") != targetValue) {
                throw new HealthWriteback.Rejected("unsupported");
            }
        });
    }

    private static String sql(String template, String table, String field) {
        // Both names are selected internally above, never interpolated from the request.
        return String.format(Locale.ROOT, template, table, field);
    }

    private static String readRow(SQLiteDatabase db, String sql, String[] identity) throws HealthWriteback.Rejected {
        try (Cursor row = db.rawQuery(sql, identity)) {
            if (!row.moveToFirst()) throw new HealthWriteback.Rejected("conflict");
            String raw = row.getString(0);
            if (row.moveToNext()) throw new HealthWriteback.Rejected("conflict");
            if (raw == null) throw new HealthWriteback.Rejected("unsupported");
            return raw;
        }
    }

    private static JSONObject projection(SQLiteDatabase db, String sql, String raw, long rowTime, int max) throws Exception {
        try (Cursor row = db.rawQuery(sql, new String[]{raw})) {
            if (!row.moveToFirst() || row.getInt(0) != 1 || row.getInt(1) > 1
                    || row.getType(2) != Cursor.FIELD_TYPE_INTEGER
                    || (row.getInt(1) == 1 && row.getType(3) != Cursor.FIELD_TYPE_INTEGER)) {
                throw new HealthWriteback.Rejected("unsupported");
            }
            long start = row.getInt(1) == 0 ? rowTime : row.getLong(3);
            long value = row.getLong(2);
            if (start < 1 || start > HealthWriteback.MAX_TIME || value < 1 || value > max) throw new HealthWriteback.Rejected("unsupported");
            return new JSONObject().put("start", start).put("value", value);
        } catch (SQLiteException unknownPayloadOrJson1) { throw new HealthWriteback.Rejected("unsupported"); }
    }

    private static long integer(JSONObject data, String field, long max) throws HealthWriteback.Rejected {
        Object number = data.opt(field);
        if (!(number instanceof Integer || number instanceof Long)) throw new HealthWriteback.Rejected("unsupported");
        long result = ((Number) number).longValue();
        if (result < 1 || result > max) throw new HealthWriteback.Rejected("unsupported");
        return result;
    }
}

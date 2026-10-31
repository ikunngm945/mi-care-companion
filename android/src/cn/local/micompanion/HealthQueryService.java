package cn.local.micompanion;

import android.content.Context;
import android.database.sqlite.SQLiteDatabase;
import java.io.File;
import java.util.TimeZone;
import org.json.JSONObject;

/** Opens the health account database read-only for one bounded MCP query. */
public final class HealthQueryService {
    private HealthQueryService() { }

    public static JSONObject query(Context context, JSONObject request) throws Exception {
        File dir = HealthSource.account(context);
        if (dir == null) throw new IllegalStateException("健康账号库未找到或存在多个账号");
        SQLiteDatabase data = null;
        try {
            data = HealthSource.open(new File(dir, "fitness_data"));
            return HealthQuery.query(HealthSource.reader(data), request, System.currentTimeMillis(), TimeZone.getDefault());
        } finally {
            if (data != null) try { data.close(); } catch (Exception ignored) { }
        }
    }
}

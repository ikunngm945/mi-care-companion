package cn.local.micompanion;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/** All networking is synchronous. Call only from a background executor, never the UI thread. */
public final class EventClient {
    private static final Object LOCK = new Object();
    private static final long DAY = 86400000L;
    private static final long[] BACKOFF = {60000L, 300000L, 900000L, 3600000L};
    private EventClient() { }
    public static String timestamp(long millis) { return ClientProtocol.timestamp(millis); }
    public static String timestamp() { return timestamp(System.currentTimeMillis()); }
    public static String status(Context c) {
        return c.getSharedPreferences("client_status", 0).getString("text", "尚未创建事件；HTTP 接收不代表机器人已送达");
    }
    private static void status(Context c, String text) {
        c.getSharedPreferences("client_status", 0).edit().putString("text", text).apply();
    }
    private static SQLiteDatabase database(Context c) {
        SQLiteDatabase db = SQLiteDatabase.openOrCreateDatabase(new File(c.getNoBackupFilesDir(), "events.db"), null);
        db.execSQL("CREATE TABLE IF NOT EXISTS events (id TEXT PRIMARY KEY, created INTEGER NOT NULL, base TEXT NOT NULL, umo TEXT NOT NULL, payload TEXT, stage TEXT NOT NULL, attempts INTEGER NOT NULL DEFAULT 0, next_at INTEGER NOT NULL DEFAULT 0, detail TEXT)");
        java.util.HashSet<String> names=new java.util.HashSet<>();
        try (Cursor columns = db.rawQuery("PRAGMA table_info(events)", null)) {
            while (columns.moveToNext()) names.add(columns.getString(1));
        }
        for(String name:new String[]{"generated","prompt","config_id","profile_name"})
            if(!names.contains(name))db.execSQL("ALTER TABLE events ADD COLUMN "+name+" TEXT");
        return db;
    }
    private static SettingsStore.Config config(Context c) throws Exception {
        SettingsStore.Config cfg = SettingsStore.load(c); cfg.validate(); return cfg;
    }
    public static void submit(Context c, boolean manual) {
        if (!manual) { submitAutomatic(c, UUID.randomUUID().toString()); return; }
        synchronized (LOCK) {
            try { createAndSend(c, true, UUID.randomUUID().toString()); }
            catch (Exception e) { status(c, safeError(e)); }
        }
    }
    /** Returns only after the immutable event is safely queued. Throws on local persistence failure. */
    public static void submitAutomatic(Context c, String eventId) {
        synchronized (LOCK) {
            try { UUID.fromString(eventId); createAndSend(c, false, eventId); }
            catch (Exception e) { status(c, safeError(e)); throw new IllegalStateException("自动事件尚未安全入队", e); }
        }
    }
    private static void createAndSend(Context c, boolean manual, String id) throws Exception {
        SettingsStore.Config cfg = config(c);
        try (SQLiteDatabase db = database(c)) {
            try (Cursor existing = db.rawQuery("SELECT id FROM events WHERE id=?", new String[]{id})) {
                if (existing.moveToFirst()) { process(c, db, cfg, id, false); return; }
            }
            long now = System.currentTimeMillis();
            // Never silently delete an undelivered event, including an expired one.
            db.delete("events", "stage='sent' AND created<?", new String[]{Long.toString(now - 7 * DAY)});
            try (Cursor count = db.rawQuery("SELECT COUNT(*) FROM events", null)) {
                count.moveToFirst();
                if (count.getLong(0) >= 100) throw new IllegalArgumentException("事件已达 100 条上限，请先查看并删除历史事件；不会丢弃待处理快照");
            }
            android.content.SharedPreferences p = manual ? null : c.getSharedPreferences("monitor", 0);
            long created = manual ? now : ClientProtocol.eventTime(false, p.getLong("pending_created_wall", 0), now);
            JSONObject body = new JSONObject(ClientProtocol.metadata(manual, cfg.umo, created));
            body.put("event_id", id);
            if (!manual) {
                JSONObject trigger = body.getJSONObject("trigger");
                for (String name : new String[]{"off_duration_ms", "off_ended_wall", "use_started_wall"})
                    if (p.contains("pending_" + name)) trigger.put(name, p.getLong("pending_" + name, 0));
                if (now - created > 60000) trigger.put("collection_note", "事件在原触发时刻形成，配置恢复后才采集；各数据类别保留实际采集时间");
            }
            body.put("weather", collect(c, "weather", now));
            JSONObject health = collect(c, "health", now);
            body.put("health", health);
            body.put("screen", collectScreen(c, health, now));
            String snapshot = snapshot(body);
            ContentValues values = new ContentValues();
            values.put("id", id); values.put("created", created); values.put("base", cfg.baseUrl);
            values.put("umo", cfg.umo); values.put("payload", snapshot); values.put("stage", "pending");
            values.put("prompt", cfg.prompt);
            db.insertOrThrow("events", null, values);
            status(c, id + "\n已保存固定快照，正在连接 AstrBot…");
            process(c, db, cfg, id, true);
        }
    }
    static String snapshot(JSONObject body) {
        String snapshot = body.toString();
        if (snapshot.getBytes(StandardCharsets.UTF_8).length > 256 * 1024)
            throw new IllegalArgumentException("事件超过 256 KiB，未发送；请检查采集源");
        return snapshot;
    }
    static JSONObject categoryJson(Map<String,Object> category) throws org.json.JSONException {
        return (JSONObject)jsonValue(category);
    }
    private static Object jsonValue(Object value) throws org.json.JSONException {
        // JSONObject(Map) drops Java null recursively; unknown measurements must remain explicit null.
        if (value == null) return JSONObject.NULL;
        if (value instanceof Map) {
            JSONObject object = new JSONObject();
            for (Map.Entry<?,?> entry : ((Map<?,?>)value).entrySet()) object.put(String.valueOf(entry.getKey()), jsonValue(entry.getValue()));
            return object;
        }
        if (value instanceof java.util.List) {
            JSONArray array = new JSONArray();
            for (Object item : (java.util.List<?>)value) array.put(jsonValue(item));
            return array;
        }
        return value;
    }
    /** A screen request without its already-frozen health category must not guess a civil day. */
    public static JSONObject collect(Context c, String kind, long now) {
        if ("screen".equals(kind)) return collectScreen(c, null, now);
        String pkg = "weather".equals(kind) ? "com.miui.weather2" : "health".equals(kind) ? "com.mi.health" : "android";
        try { return normalizeCategory(SnapshotProvider.read(c, kind), pkg, now); }
        catch (Exception e) { return collectionError(pkg, now, e); }
    }
    static long[] screenBounds(JSONObject health, long now) {
        JSONObject fields = health == null ? null : health.optJSONObject("fields");
        JSONObject cycle = fields == null ? null : fields.optJSONObject("cycle");
        if (cycle == null || !"ok".equals(cycle.optString("status"))) return null;
        Object start = cycle.opt("start_ms"), end = cycle.opt("end_ms");
        if (!(start instanceof Byte || start instanceof Short || start instanceof Integer || start instanceof Long)
                || !(end instanceof Byte || end instanceof Short || end instanceof Integer || end instanceof Long)) return null;
        long a = ((Number)start).longValue(), b = ((Number)end).longValue();
        return a > 0 && b > a && b <= now && b - a <= 7 * DAY ? new long[]{a,b} : null;
    }
    public static JSONObject collectScreen(Context c, JSONObject health, long now) {
        long[] bounds = screenBounds(health, now);
        try {
            if (bounds == null) {
                JSONObject missing = categoryJson(ClientProtocol.category(null, "android", now));
                return missing.put("reason", "健康周期不可用；未回退自然日屏幕统计");
            }
            JSONObject raw = UsageCollector.collect(c, bounds[0], bounds[1], now);
            JSONObject result = normalizeCategory(raw, "android", now);
            JSONObject cycle = health.getJSONObject("fields").getJSONObject("cycle");
            if (cycle.has("timezone")) result.getJSONObject("fields").put("timezone", cycle.get("timezone"));
            return result;
        } catch (Exception e) { return collectionError("android", now, e); }
    }
    static JSONObject normalizeCategory(JSONObject raw, String pkg, long now) throws Exception {
        @SuppressWarnings("unchecked") Map<String,Object> source = raw == null ? null : (Map<String,Object>)plain(raw, 0);
        return categoryJson(ClientProtocol.category(source, pkg, now));
    }
    private static JSONObject collectionError(String pkg, long now, Exception e) {
        try {
            JSONObject missing = categoryJson(ClientProtocol.category(null, pkg, now));
            missing.put("status", "error").put("reason", "采集不可用：" + e.getClass().getSimpleName() + "；源时间未知");
            return missing;
        } catch (Exception impossible) { throw new IllegalStateException(impossible); }
    }
    private static Object plain(Object value, int depth) throws Exception {
        if (depth > 10) throw new IllegalArgumentException("采集结构过深");
        if (value == null || value == JSONObject.NULL) return null;
        if (value instanceof JSONObject) {
            JSONObject object = (JSONObject)value;
            if (object.length() > 128) throw new IllegalArgumentException("采集字段过多");
            Map<String,Object> map = new LinkedHashMap<>();
            Iterator<String> keys = object.keys();
            while (keys.hasNext()) { String key = keys.next(); if (key.length() > 4096) throw new IllegalArgumentException(); map.put(key, plain(object.get(key), depth + 1)); }
            return map;
        }
        if (value instanceof JSONArray) {
            JSONArray a = (JSONArray)value;
            if (a.length() > 512) throw new IllegalArgumentException("采集数组过长");
            java.util.List<Object> list = new java.util.ArrayList<>();
            for (int i = 0; i < a.length(); i++) list.add(plain(a.get(i), depth + 1));
            return list;
        }
        if (value instanceof String && ((String)value).length() > 4096) throw new IllegalArgumentException("采集文本过长");
        return value;
    }
    /** Periodic automatic retry; stopping monitoring stops this path, but never blocks a manual test. */
    public static void retry(Context c) {
        if (!c.getSharedPreferences("monitor", 0).getBoolean("enabled", false)) return;
        synchronized (LOCK) {
            try (SQLiteDatabase db = database(c)) {
                SettingsStore.Config cfg = config(c);
                try (Cursor rows = db.rawQuery("SELECT id FROM events WHERE stage IN ('pending','generating','ready','sending','retry') AND next_at<=? ORDER BY created LIMIT 5", new String[]{Long.toString(System.currentTimeMillis())})) {
                    while (rows.moveToNext()) {
                        if (!c.getSharedPreferences("monitor", 0).getBoolean("enabled", false)) break;
                        process(c, db, cfg, rows.getString(0), false);
                    }
                }
            } catch (Exception e) { status(c, safeError(e)); }
        }
    }
    /** User explicitly requests retry/status; this does not create or modify the original snapshot. */
    public static void retryLatest(Context c) {
        synchronized (LOCK) {
            try (SQLiteDatabase db = database(c)) {
                SettingsStore.Config cfg = config(c);
                try (Cursor row = db.rawQuery("SELECT id FROM events ORDER BY created DESC LIMIT 1", null)) {
                    if (row.moveToFirst()) process(c, db, cfg, row.getString(0), true);
                    else status(c, "没有可查询或重试的事件");
                }
            } catch (Exception e) { status(c, safeError(e)); }
        }
    }
    /** Only explicit user action removes event history, including undelivered private snapshots. */
    public static void clearHistory(Context c) {
        synchronized (LOCK) {
            try (SQLiteDatabase db = database(c)) { db.delete("events", null, null); status(c, "本机事件历史已删除（不会撤回已被服务器接收的事件）"); }
        }
    }
    public static String check(Context c) {
        // A read-only preflight must not queue behind a model call or create an event.
        try {
            OfficialApi.Selection selected=OfficialApi.check(config(c));
            return "官方接口检查通过：chat / im / config 权限有效，目标机器人已配置\n自动匹配配置档："+selected.profileName+"\n配置 ID："+selected.configId+"\n生成将沿用该配置档的人格/模型/工具设置（未调用模型、未发送消息；不证明机器人在线）";
        }
        catch (Exception e) { return safeError(e); }
    }
    private static void process(Context c, SQLiteDatabase db, SettingsStore.Config cfg, String id, boolean explicit) {
        String stage,payload,base,umo,generated,prompt,profile; int attempts; long created;
        try (Cursor row = db.rawQuery("SELECT stage,payload,attempts,created,base,umo,generated,prompt,config_id FROM events WHERE id=?",new String[]{id})) {
            if (!row.moveToFirst()) return;
            stage=row.getString(0);payload=row.getString(1);attempts=row.getInt(2);created=row.getLong(3);
            base=row.getString(4);umo=row.getString(5);generated=row.getString(6);prompt=row.getString(7);profile=row.getString(8);
        }
        if (!base.equals(cfg.baseUrl) || !umo.equals(cfg.umo)) {
            status(c,id+"\n目标配置已变化，原任务暂停；不会向新目标投递旧快照"); return;
        }
        if("ready".equals(stage)&&profile==null){
            status(c,id+"\n旧版正文缺少配置来源，原任务暂停；不会用新配置补签后发送旧人格正文，请确认后新建测试任务");return;
        }
        if (!explicit && ("paused".equals(stage) || "failed".equals(stage))) return;
        final String snapshot=payload;
        final String storedProfile=profile;
        final SettingsStore.Config taskConfig=new SettingsStore.Config(cfg.baseUrl,cfg.key,cfg.umo,cfg.allowHttp,
                prompt==null?AdvicePrompt.DEFAULT:prompt);
        try {
            OfficialDelivery.run(stage,generated,created,System.currentTimeMillis(),new OfficialDelivery.Steps() {
                private OfficialApi.Selection selected;
                public void verifyTarget()throws Exception{
                    if(snapshot!=null&&"long_idle".equals(new JSONObject(snapshot).getJSONObject("trigger").getString("type"))
                            &&!c.getSharedPreferences("monitor",0).getBoolean("enabled",false))
                        throw new IllegalArgumentException("自动监控已停止，原任务暂停，尚未执行下一次生成或发送");
                    SettingsStore.Config live=config(c);
                    if(!live.baseUrl.equals(taskConfig.baseUrl)||!live.umo.equals(taskConfig.umo)
                            ||live.allowHttp!=taskConfig.allowHttp||!live.key.equals(taskConfig.key))
                        throw new IllegalArgumentException("连接/目标/Key配置已变化，原任务暂停，尚未执行下一次生成或发送");
                }
                public long now(){return System.currentTimeMillis();}
                public void check()throws Exception{
                    selected=OfficialApi.check(taskConfig,id);
                    if(storedProfile!=null&&!storedProfile.equals(selected.configId))
                        throw new java.io.IOException("目标配置路由已变化，旧任务暂停；不切换人格重用旧正文");
                    ContentValues values=new ContentValues();values.put("config_id",selected.configId);values.put("profile_name",selected.profileName);
                    if(db.update("events",values,"id=?",new String[]{id})!=1)throw new java.io.IOException("配置匹配持久化失败");
                }
                public String generate()throws Exception{
                    if(snapshot==null)throw new IllegalArgumentException("原快照不存在，未请求模型");
                    return OfficialApi.generate(taskConfig,id,snapshot,selected);
                }
                public void send(String text)throws Exception{OfficialApi.send(cfg,text);}
                public void checkpoint(String next,String text)throws Exception{
                    ContentValues values=new ContentValues();values.put("stage",next);values.put("detail",explain(next));
                    if(text==null)values.putNull("generated");else values.put("generated",text);
                    values.put("next_at",0);
                    if("sent".equals(next))values.putNull("payload");
                    if(db.update("events",values,"id=?",new String[]{id})!=1)throw new java.io.IOException("任务持久化失败");
                    status(c,id+"\n"+explain(next));
                }
            });
            try(Cursor row=db.rawQuery("SELECT stage FROM events WHERE id=?",new String[]{id})){
                if(row.moveToFirst())status(c,id+"\n"+explain(row.getString(0)));
            }
        }catch(Exception e){
            // Only preflight is retryable. Never overwrite durable generating/sending/ready barriers.
            String current=stage;
            try(Cursor row=db.rawQuery("SELECT stage FROM events WHERE id=?",new String[]{id})){if(row.moveToFirst())current=row.getString(0);}
            if("pending".equals(current)||"retry".equals(current)||"paused".equals(current)){
                ContentValues values=new ContentValues();values.put("stage","retry");values.put("attempts",attempts+1);
                values.put("next_at",System.currentTimeMillis()+BACKOFF[Math.min(attempts,BACKOFF.length-1)]);
                values.put("detail",safeError(e));db.update("events",values,"id=?",new String[]{id});
            }
            status(c,id+"\n"+safeError(e)+"；"+explain(current));
        }
    }
    private static String explain(String stage) {
        if("sent".equals(stage))return "sent：官方发送接口成功返回（不代表平台已送达或用户已读）";
        if("generating".equals(stage))return "正在等待 AI 完整正文；若上次处理已中断则不自动重新生成";
        if("ready".equals(stage))return "AI 正文已保存在本机，尚未尝试发送";
        if("sending".equals(stage)||"delivery_unknown".equals(stage))return "delivery_unknown：已尝试发送，结果不确定；官方无状态查询/幂等接口，不重发，请先检查目标会话";
        if("generation_unknown".equals(stage))return "生成结果未知：上次请求已中断，不自动重做；请确认后新建测试任务";
        if("generation_failed".equals(stage))return "生成失败/响应不完整或含工具调用，未调用发送接口；不自动重做，请检查 AstrBot 后新建测试任务";
        if("expired".equals(stage))return "任务超过24小时或时间异常，原快照不再提交；请新建测试任务";
        if("paused".equals(stage)||"accepted".equals(stage)||"processing".equals(stage)||"failed".equals(stage))return "任务已暂停：旧插件协议任务不可转投官方接口；请保留原目标或确认后删除";
        return "已保存固定快照，等待官方接口检查；检查失败可重试（尚未请求模型/发送）";
    }
    private static String safeError(Exception e) {
        if(e instanceof OfficialApi.HttpFailure)return "AstrBot 官方接口 HTTP "+((OfficialApi.HttpFailure)e).code+"；请检查地址、API Key 的 chat / im / config 权限及机器人配置";
        if(e instanceof IllegalArgumentException)return "配置/协议错误："+e.getMessage();
        return "操作失败："+e.getClass().getSimpleName()+"（未记录密钥或响应正文；请检查网络/配置）";
    }
}

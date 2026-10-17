package cn.local.micompanion;

import org.json.JSONArray;
import org.json.JSONObject;
import org.json.JSONTokener;
import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Timer;
import java.util.TimerTask;

/** Official AstrBot API only. No model credentials, redirects, retries, or response logging. */
public final class OfficialApi {
    private static final int MAX_RAW = 1024 * 1024;
    private static final int MAX_TEXT = 16000;
    private static final int MAX_TEXT_BYTES = 64 * 1024;
    private static final int CONNECT_MS = 15000;
    private static final int GENERATE_MS = 180000;
    private static final String USER = "mi-companion";
    public static final class Selection {
        public final String configId, profileName;
        private Selection(String id, String name) { configId=id; profileName=name; }
    }
    private OfficialApi() { }

    /** Only the status code is retained; response bodies and exception causes are never exposed. */
    public static final class HttpFailure extends Exception {
        public final int code;
        HttpFailure(int code) { super("HTTP " + code); this.code = code; }
    }

    public static Selection check(SettingsStore.Config cfg) throws Exception {
        return check(cfg, "permission-check");
    }
    public static Selection check(SettingsStore.Config cfg, String eventId) throws Exception {
        JSONObject bots = jsonRequest(cfg, "GET", "/im/bots", null);
        try {
            JSONArray ids = bots.getJSONObject("data").getJSONArray("bot_ids");
            String platform = cfg.umo.split(":", 2)[0];
            boolean found = false;
            for (int i = 0; i < ids.length(); i++) {
                Object id = ids.get(i);
                if (!(id instanceof String)) throw invalid();
                if (platform.equals(id)) found = true;
            }
            if (!found) throw new IOException("目标 UMO 的平台未在 AstrBot 配置中找到，请检查平台配置和 UMO");
        } catch (org.json.JSONException e) { throw invalid(); }
        JSONObject sessions = jsonRequest(cfg, "GET", "/chat/sessions?username=mi-companion&page=1&page_size=1&platform_id=webchat", null);
        try {
            JSONObject data = sessions.getJSONObject("data");
            JSONArray list = data.getJSONArray("sessions");
            if (!(data.get("page") instanceof Number) || data.getInt("page") != 1
                    || !(data.get("page_size") instanceof Number) || data.getInt("page_size") != 1
                    || !(data.get("total") instanceof Number) || data.getLong("total") < 0) throw invalid();
            for (int i = 0; i < list.length(); i++) {
                JSONObject session = list.getJSONObject(i);
                if (!(session.get("session_id") instanceof String) || !(session.get("creator") instanceof String)
                        || !(session.get("platform_id") instanceof String)) throw invalid();
            }
        } catch (org.json.JSONException e) { throw invalid(); }
        String routes=request(cfg,"GET","/config-routes",null,false);
        String selected=RouteResolver.resolve(routes,cfg.umo);
        JSONObject profiles=jsonRequest(cfg,"GET","/config-profiles",null);
        String name=null;
        try {
            JSONArray list=profiles.getJSONObject("data").getJSONArray("info_list");
            java.util.HashSet<String> seen=new java.util.HashSet<>();
            for(int i=0;i<list.length();i++){
                JSONObject item=list.getJSONObject(i);
                if(!(item.opt("id") instanceof String)||!(item.opt("name") instanceof String))throw invalid();
                String id=item.getString("id");
                if(id.isEmpty()||!seen.add(id))throw invalid();
                if(selected.equals(id))name=item.getString("name");
            }
        } catch(org.json.JSONException e){throw invalid();}
        if(name==null)throw new IllegalArgumentException("匹配配置档不存在，已停止；未回退默认人格");
        RouteResolver.verifyChatRoute(routes,"webchat:FriendMessage:webchat!"+USER+"!"+USER+"-"+eventId,selected);
        return new Selection(selected,name);
    }

    public static String generate(SettingsStore.Config cfg, String eventId, String snapshot) throws Exception {
        return generate(cfg,eventId,snapshot,check(cfg,eventId));
    }
    public static String generate(SettingsStore.Config cfg, String eventId, String snapshot, Selection selected) throws Exception {
        if (eventId == null || eventId.trim().isEmpty() || eventId.length() > 128
                || snapshot == null || snapshot.getBytes(StandardCharsets.UTF_8).length > MAX_RAW)
            throw new IOException("生成请求数据无效或超限");
        JSONObject flags = new JSONObject().put("enable_streaming", true)
                .put("enable_inline_genui", false).put("enable_default_system_prompt", true);
        JSONObject body = new JSONObject().put("username", USER).put("session_id", USER + "-" + eventId)
                .put("message", AdvicePrompt.message(cfg.prompt,HealthText.snapshot(WeatherText.snapshot(snapshot)))).put("enable_streaming", true).put("flags", flags)
                .put("config_id",selected.configId);
        return AdvicePrompt.limit(request(cfg, "POST", "/chat", body, true));
    }

    /** A successful adapter return is not a delivery/read receipt. The caller owns uncertain-send state. */
    public static void send(SettingsStore.Config cfg, String text) throws Exception {
        boundedText(text);
        if(text.codePointCount(0,text.length())>500)throw tooLong();
        jsonRequest(cfg, "POST", "/im/messages", new JSONObject().put("umo", cfg.umo).put("message", text));
    }

    private static JSONObject jsonRequest(SettingsStore.Config cfg, String method, String suffix, JSONObject body) throws Exception {
        String response = request(cfg, method, suffix, body, false);
        JSONObject envelope = object(response);
        if (!"ok".equals(envelope.opt("status"))) throw new IOException("AstrBot 官方接口返回业务失败");
        if (!envelope.has("data")) throw invalid();
        return envelope;
    }

    private static String request(SettingsStore.Config cfg, String method, String suffix, JSONObject body, boolean sse) throws Exception {
        cfg.validate();
        String base = ClientProtocol.baseUrl(cfg.baseUrl, cfg.allowHttp);
        final HttpURLConnection connection = (HttpURLConnection)new URL(base + suffix).openConnection();
        final long deadline = System.nanoTime() + (sse ? GENERATE_MS : CONNECT_MS) * 1000000L;
        Timer timer = new Timer("mi-official-http-deadline", true);
        timer.schedule(new TimerTask() {
            @Override public void run() { connection.disconnect(); }
        }, sse ? GENERATE_MS : CONNECT_MS);
        try {
            connection.setInstanceFollowRedirects(false);
            connection.setConnectTimeout(CONNECT_MS);
            connection.setReadTimeout(sse ? GENERATE_MS : CONNECT_MS);
            connection.setRequestMethod(method);
            connection.setRequestProperty("Authorization", "Bearer " + cfg.key);
            connection.setRequestProperty("Accept", sse ? "text/event-stream" : "application/json");
            if (body != null) {
                byte[] payload = body.toString().getBytes(StandardCharsets.UTF_8);
                connection.setDoOutput(true);
                connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
                connection.setFixedLengthStreamingMode(payload.length);
                try (OutputStream out = connection.getOutputStream()) { out.write(payload); }
            }
            int status = connection.getResponseCode();
            if (status < 200 || status >= 300) throw new HttpFailure(status);
            timeLeft(deadline);
            if (sse) {
                String type = connection.getContentType();
                if (type == null || !"text/event-stream".equalsIgnoreCase(type.split(";", 2)[0].trim()))
                    throw new IOException("AstrBot 生成接口未返回 SSE");
            }
            try (InputStream in = new BoundedInput(connection.getInputStream(), deadline, connection)) {
                if (sse) return readSse(in);
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buffer = new byte[4096]; int n;
                while ((n = in.read(buffer)) != -1) out.write(buffer, 0, n);
                return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                        .onUnmappableCharacter(CodingErrorAction.REPORT).decode(java.nio.ByteBuffer.wrap(out.toByteArray())).toString();
            }
        } catch (HttpFailure e) { throw e; }
        catch (Exception e) {
            // Only our fixed diagnostics may leave this trust boundary, never URL/headers/body/cause.
            if (e instanceof SafeFailure) throw e;
            throw new IOException("AstrBot 官方接口请求失败（未确认送达）");
        } finally {
            timer.cancel(); connection.disconnect();
        }
    }

    static String parseSse(InputStream in) throws Exception {
        try (InputStream bounded = new BoundedInput(in, System.nanoTime() + GENERATE_MS * 1000000L, null)) {
            return readSse(bounded);
        } catch (SafeFailure e) { throw e; }
        catch (Exception e) { throw invalid(); }
    }

    private static String readSse(InputStream in) throws Exception {
        BufferedReader reader = new BufferedReader(new InputStreamReader(in,
                StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                        .onUnmappableCharacter(CodingErrorAction.REPORT)));
        StringBuilder data = new StringBuilder();
        SseState state = new SseState();
        boolean hasData = false;
        String line;
        while ((line = reader.readLine()) != null) {
            if (line.isEmpty()) {
                if (hasData) state.accept(data.toString());
                data.setLength(0); hasData = false;
            } else if (!line.startsWith(":")) {
                int colon = line.indexOf(':');
                String field = colon < 0 ? line : line.substring(0, colon);
                if ("data".equals(field)) {
                    String value = colon < 0 ? "" : line.substring(colon + 1);
                    if (value.startsWith(" ")) value = value.substring(1);
                    if (hasData) data.append('\n');
                    data.append(value); hasData = true;
                } else if ("event".equals(field)) {
                    String value = colon < 0 ? "" : line.substring(colon + 1).trim();
                    if ("error".equals(value) || "tool_call".equals(value) || "tool_call_result".equals(value)) throw invalid();
                }
            }
        }
        if (hasData) state.accept(data.toString());
        if (!state.ended) throw new SafeFailure("AstrBot 生成流未正常结束");
        String text = state.finished.toString() + state.pending;
        boundedText(text);
        return text;
    }

    private static final class SseState {
        final StringBuilder finished = new StringBuilder();
        final StringBuilder pending = new StringBuilder();
        boolean ended;
        void accept(String data) throws Exception {
            JSONObject event = object(data);
            Object rawType = event.opt("type"), chain = event.opt("chain_type");
            if (!(rawType instanceof String)) throw invalid();
            String type = (String)rawType;
            if ("error".equals(type)) throw new SafeFailure("AstrBot 生成流返回错误");
            if ("tool_call".equals(type) || "tool_call_result".equals(type)
                    || "tool_call".equals(chain) || "tool_call_result".equals(chain))
                throw new SafeFailure("AstrBot 生成涉及工具调用，已拒绝发送");
            if ("session_id".equals(type) || "user_message_saved".equals(type) || "message_saved".equals(type)
                    || "run_started".equals(type) || "agent_stats".equals(type) || "reasoning".equals(type)) return;
            if (ended) throw invalid();
            if ("end".equals(type)) { ended = true; return; }
            if ("complete".equals(type)) {
                // complete.data repeats draft text; only the segment boundary is meaningful.
                if (chain == null || chain == JSONObject.NULL || "normal".equals(chain)) flush();
                else if (!"reasoning".equals(chain)) throw invalid();
                return;
            }
            if ("plain".equals(type)) {
                if ("reasoning".equals(chain)) return;
                if (!(chain == null || chain == JSONObject.NULL || "normal".equals(chain))) throw invalid();
                Object text = event.opt("data");
                if (!(text instanceof String)) throw invalid();
                Object streaming = event.opt("streaming");
                if (streaming != null && streaming != JSONObject.NULL && !(streaming instanceof Boolean)) throw invalid();
                if (!Boolean.TRUE.equals(streaming)) pending.setLength(0);
                pending.append((String)text);
                if (finished.length() + pending.length() > MAX_TEXT) throw tooLong();
                return;
            }
            // Unknown payloads are never rendered, including attachment paths and future control types.
            throw invalid();
        }
        void flush() throws SafeFailure {
            finished.append(pending); pending.setLength(0);
            if (finished.length() > MAX_TEXT) throw tooLong();
        }
    }

    private static void boundedText(String text) throws SafeFailure {
        if (text == null || text.codePoints().allMatch(c -> Character.isWhitespace(c) || Character.isSpaceChar(c)))
            throw new SafeFailure("AstrBot 文本为空");
        if (text.length() > MAX_TEXT || text.getBytes(StandardCharsets.UTF_8).length > MAX_TEXT_BYTES) throw tooLong();
    }
    private static JSONObject object(String text) throws SafeFailure {
        try {
            RouteResolver.validateJsonObject(text);
            JSONTokener tokener = new JSONTokener(text);
            Object value = tokener.nextValue();
            if (!(value instanceof JSONObject) || tokener.nextClean() != 0) throw invalid();
            return (JSONObject)value;
        } catch (Exception e) { throw invalid(); }
    }
    private static SafeFailure invalid() { return new SafeFailure("AstrBot 官方接口响应格式无效"); }
    private static SafeFailure tooLong() { return new SafeFailure("AstrBot 响应超过安全大小限制"); }
    private static final class SafeFailure extends IOException {
        SafeFailure(String message) { super(message); }
    }
    private static int timeLeft(long deadline) throws SafeFailure {
        long nanos = deadline - System.nanoTime();
        if (nanos <= 0) throw new SafeFailure("AstrBot 官方接口请求超时");
        return (int)Math.max(1, (nanos + 999999L) / 1000000L);
    }
    private static final class BoundedInput extends FilterInputStream {
        final long deadline;
        final HttpURLConnection connection;
        int count;
        BoundedInput(InputStream in, long deadline, HttpURLConnection connection) {
            super(in); this.deadline = deadline; this.connection = connection;
        }
        private void before() throws IOException {
            int left = timeLeft(deadline);
            if (connection != null) connection.setReadTimeout(left);
        }
        private void after(int n) throws IOException {
            timeLeft(deadline);
            if (n > 0 && (count += n) > MAX_RAW) throw tooLong();
        }
        @Override public int read() throws IOException {
            before(); int value = in.read(); after(value < 0 ? 0 : 1); return value;
        }
        @Override public int read(byte[] buffer, int off, int len) throws IOException {
            before(); int n = in.read(buffer, off, Math.min(len, MAX_RAW - count + 1)); after(n); return n;
        }
    }
}

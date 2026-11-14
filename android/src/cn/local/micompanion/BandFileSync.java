package cn.local.micompanion;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Base64;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.CRC32;
import org.json.JSONObject;

final class BandFileSync {
    interface Transport { JSONObject query(JSONObject request, long timeoutMs) throws Exception; }
    interface Clock {
        long millis();
        void sleep(long millis) throws InterruptedException;
    }
    static JSONObject sync(android.content.Context context, LinkServer server) throws Exception {
        synchronized (BandSync.class) {
            BandConfig config = BandConfig.load(context);
            Map<String, String> sources = sourcesForScope(
                    context.getSharedPreferences("band_file_sources", 0).getAll(),
                    config == null ? "" : config.sourceScope);
            return sync(new File(context.getFilesDir(), "band-files"), sources, (request, timeout) -> {
                if (server == null) throw new IOException("健康数据连接尚未启动");
                return server.query("health", request, timeout);
            });
        }
    }

    static Map<String, String> sourcesForScope(Map<String, ?> saved, String activeScope) {
        Map<String, String> sources = new HashMap<>();
        if (activeScope == null || activeScope.isEmpty()) return sources;
        for (Map.Entry<String, ?> entry : saved.entrySet())
            if (entry.getValue() instanceof String && ((String) entry.getValue()).startsWith(activeScope + "|"))
                sources.put(entry.getKey(), (String) entry.getValue());
        return sources;
    }

    static JSONObject sync(File root, Map<String, String> sources, Transport transport) throws Exception {
        return sync(root, sources, transport, new Clock() {
            public long millis() { return System.nanoTime() / 1000000L; }
            public void sleep(long millis) throws InterruptedException { Thread.sleep(millis); }
        });
    }
    static synchronized JSONObject sync(File root, Map<String, String> sources, Transport transport, Clock clock) throws Exception {
        JSONObject result = new JSONObject().put("status", "ok").put("imported", 0).put("already_synced", 0)
                .put("skipped", 0).put("pending", 0).put("error", "");
        Map<String, JSONObject> finishes = new LinkedHashMap<>();
        try {
            File[] files = root.listFiles((dir, name) -> name.endsWith(".bin"));
            if (files == null) {
                if (root.exists()) throw new IOException("手环文件目录不可读");
                return result;
            }
            Arrays.sort(files, Comparator.comparingLong(BandFileSync::timestamp)
                    .thenComparing(File::getName));
            for (File file : files) {
                String name = file.getName();
                if (!name.matches("[0-9a-f]{14}\\.bin")) throw new IOException("手环文件名无效");
                String id = name.substring(0, 14);
                String source = sources.get(id);
                if (source == null || !source.matches("[^|]+\\|[0-9A-Fa-f]{2}(:[0-9A-Fa-f]{2}){5}")) {
                    increment(result, "skipped");
                    continue;
                }
                if (!file.getCanonicalFile().getParentFile().equals(root.getCanonicalFile())
                        || file.length() < 13 || file.length() > 180000)
                    throw new IOException("手环文件大小或路径无效：" + id);
                byte[] raw = Files.readAllBytes(file.toPath());
                if (!hex(Arrays.copyOf(raw, 7)).equals(id)) throw new IOException("手环文件头不匹配：" + id);
                CRC32 crc = new CRC32();
                crc.update(raw, 0, raw.length - 4);
                if (crc.getValue() != u32(raw, raw.length - 4)) throw new IOException("手环文件CRC错误：" + id);
                int separator = source.indexOf('|');
                String scope = source.substring(0, separator);
                String mac = source.substring(separator + 1).toUpperCase(java.util.Locale.ROOT);
                JSONObject identity = new JSONObject().put("mac", mac).put("source_scope", scope)
                        .put("file_id", id).put("sha256", hex(MessageDigest.getInstance("SHA-256").digest(raw)));
                String outcome = transfer(identity, raw, transport, clock);
                increment(result, outcome);
                if ("imported".equals(outcome))
                    finishes.put(source, new JSONObject().put("mac", mac).put("source_scope", scope));
            }
        } catch (Pending error) {
            result.put("status", "pending").put("pending", 1).put("error", error.getMessage());
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            result.put("status", "pending").put("pending", 1).put("error", "补同步已中断");
        } catch (Exception error) {
            result.put("status", "error").put("error", error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage());
        }
        for (JSONObject finish : finishes.values()) {
            if (Thread.currentThread().isInterrupted()) break;
            try {
                JSONObject answer = transport.query(finish.put("action", "band_import_finish"), 5000);
                String status = answer == null ? "" : answer.optString("status");
                if (!"ok".equals(status) && !"pending".equals(status))
                    throw new IOException("数据库刷新请求被拒绝");
                result.put("refresh_requested", true);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                result.put("status", "pending").put("error", "数据库刷新请求已中断");
                break;
            } catch (Exception failed) {
                if (result.optString("error").isEmpty()) result.put("error", "数据库刷新请求失败，等待下次补同步");
                if ("ok".equals(result.getString("status"))) result.put("status", "pending");
            }
        }
        return result;
    }

    private static final class Pending extends IOException {
        Pending(String reason) { super(reason); }
    }

    private static String transfer(JSONObject identity, byte[] raw, Transport transport, Clock clock) throws Exception {
        long deadline = clock.millis() + 30000;
        JSONObject response = query(transport, new JSONObject(identity.toString())
                .put("action", "band_import_status"), deadline, clock);
        boolean submitted = false, waiting = false;
        while (true) {
            String status = response.optString("status");
            String error = response.optString("error");
            if ("ok".equals(status)) return submitted ? "imported" : "already_synced";
            if ("unsupported".equals(status) || ("error".equals(status) && "unsupported".equals(error)))
                return "skipped";
            if ("pending".equals(status)) {
                waiting = true;
                long remaining = deadline - clock.millis();
                if (remaining <= 0) throw new Pending("等待健康原生导入超时，保持待同步");
                clock.sleep(Math.min(1000, remaining));
                response = query(transport, new JSONObject(identity.toString())
                        .put("action", "band_import_status"), deadline, clock);
                continue;
            }
            if ("missing".equals(status) || "error".equals(status)) {
                if ("error".equals(status) && !"health_not_ready".equals(error)
                        && !"native_import_failed".equals(error))
                    throw new IOException("健康侧拒绝补同步：" + error);
                if (submitted || waiting) throw new IOException("健康原生导入未成功：" + error);
                submitted = true;
                response = query(transport, new JSONObject(identity.toString()).put("action", "band_import")
                        .put("data", Base64.getEncoder().encodeToString(raw)), deadline, clock);
                continue;
            }
            throw new IOException("健康侧返回未知同步状态");
        }
    }

    private static JSONObject query(Transport transport, JSONObject request, long deadline, Clock clock) throws Exception {
        if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
        long remaining = deadline - clock.millis();
        if (remaining <= 0) throw new Pending("等待健康原生导入超时，保持待同步");
        try {
            JSONObject response = transport.query(request, Math.min(5000, remaining));
            if (response == null) throw new IOException("健康侧没有返回同步状态");
            return response;
        } catch (InterruptedException interrupted) {
            throw interrupted;
        } catch (Exception disconnected) {
            throw new Pending("健康连接中断或查询失败，等待下次补同步");
        }
    }

    private static long timestamp(File file) {
        String n = file.getName();
        if (!n.matches("[0-9a-f]{14}\\.bin")) return Long.MAX_VALUE;
        return Long.parseLong(n.substring(6, 8) + n.substring(4, 6) + n.substring(2, 4) + n.substring(0, 2), 16);
    }
    private static void increment(JSONObject result, String key) throws Exception {
        result.put(key, result.getInt(key) + 1);
    }
    private static long u32(byte[] data, int offset) {
        return (data[offset] & 255L) | ((data[offset + 1] & 255L) << 8)
                | ((data[offset + 2] & 255L) << 16) | ((data[offset + 3] & 255L) << 24);
    }
    private static String hex(byte[] bytes) {
        StringBuilder out = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) out.append(String.format(java.util.Locale.ROOT, "%02x", b & 255));
        return out.toString();
    }
}

package cn.local.micompanion;

import java.io.File;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.CRC32;
import org.json.JSONObject;

public final class BandFileSyncTest {
    private static final String MAC = "AA:BB:CC:DD:EE:FF";
    private static final String SCOPE = "current-account-region-scope";

    public static void main(String[] args) throws Exception {
        File root = Files.createTempDirectory("band-file-sync-test-").toFile();
        Map<String, String> sources = new HashMap<>();
        File first = raw(root, 100, 0, sources);
        List<String> actions = new ArrayList<>();
        JSONObject synced = BandFileSync.sync(root, sources, (request, timeout) -> {
            actions.add(request.getString("action"));
            check(request.getString("mac").equals(MAC), "trusted source MAC is carried");
            check(request.getString("source_scope").equals(SCOPE), "trusted account scope is carried");
            if (request.getString("action").equals("band_import_finish")) return state("pending");
            check(request.getString("file_id").equals(first.getName().substring(0, 14)), "file id is carried");
            check(request.getString("sha256").equals(hex(MessageDigest.getInstance("SHA-256")
                    .digest(Files.readAllBytes(first.toPath())))), "hash comes from current raw bytes");
            return state("ok");
        });
        check(synced.getInt("already_synced") == 1 && synced.getInt("imported") == 0,
                "health ledger success skips import");
        check(actions.equals(Arrays.asList("band_import_status")) && !synced.optBoolean("refresh_requested"),
                "already-synced ledger result does not repeatedly refresh the large DB snapshot");
        File incoming = Files.createTempDirectory("band-file-sync-incoming-").toFile();
        Map<String, String> incomingSources = new HashMap<>();
        File earlier = raw(incoming, 1, 0, incomingSources);
        File later = raw(incoming, 512, 0, incomingSources);
        List<String> importedIds = new ArrayList<>();
        Map<String, Integer> polls = new HashMap<>();
        FakeClock clock = new FakeClock();
        JSONObject imported = BandFileSync.sync(incoming, incomingSources, (request, timeout) -> {
            check(timeout > 0 && timeout <= 30000, "transport receives remaining per-file budget");
            String action = request.getString("action");
            if (action.equals("band_import_finish")) return state("pending");
            String id = request.getString("file_id");
            if (action.equals("band_import")) {
                check(!importedIds.contains(id), "pending file is never retransmitted");
                File expected = new File(incoming, id + ".bin");
                check(Arrays.equals(Base64.getDecoder().decode(request.getString("data")),
                        Files.readAllBytes(expected.toPath())), "transferred bytes equal verified raw file");
                importedIds.add(id);
                return state("pending");
            }
            if (!importedIds.contains(id)) return state("missing");
            int count = polls.containsKey(id) ? polls.get(id) : 0;
            polls.put(id, count + 1);
            return state(count == 0 ? "pending" : "ok");
        }, clock);
        check(imported.getInt("imported") == 2 && imported.getInt("pending") == 0,
                "missing files import sequentially and poll pending to completion");
        check(importedIds.equals(Arrays.asList(earlier.getName().substring(0, 14), later.getName().substring(0, 14))),
                "file timestamp order wins over little-endian filename order");
        check(clock.sleeps > 0, "pending polling pauses instead of busy waiting");
        FakeClock waitingClock = new FakeClock();
        List<String> waitingActions = new ArrayList<>();
        JSONObject waiting = BandFileSync.sync(root, sources, (request, timeout) -> {
            waitingActions.add(request.getString("action"));
            return state("pending");
        }, waitingClock);
        check(waiting.getString("status").equals("pending") && waiting.getInt("pending") == 1
                        && waitingClock.now == 30000 && waitingActions.size() <= 31,
                "preexisting pending job stops at thirty seconds without busy waiting");
        check(!waitingActions.contains("band_import"), "pending job is never blindly retransmitted");
        List<String> resumed = new ArrayList<>();
        JSONObject resumedResult = BandFileSync.sync(root, sources, (request, timeout) -> {
            resumed.add(request.getString("action"));
            return state(request.getString("action").equals("band_import_finish") ? "pending" : "ok");
        }, new FakeClock());
        check(resumedResult.getInt("already_synced") == 1 && resumed.equals(Arrays.asList("band_import_status"))
                        && !resumedResult.optBoolean("refresh_requested"),
                "completed prior operation is checked without retransmitting raw files or DB snapshot");
        List<String> switchedActions = new ArrayList<>();
        JSONObject switched = BandFileSync.sync(root, sources, (request, timeout) -> {
            switchedActions.add(request.getString("action"));
            return state("error").put("error", "source_scope_mismatch");
        }, new FakeClock());
        check(switched.getString("status").equals("error")
                        && switched.getString("error").contains("source_scope_mismatch")
                        && switchedActions.equals(Arrays.asList("band_import_status")),
                "same MAC in a different account is refused before import and old success is not cached");
        List<String> partialActions = new ArrayList<>();
        JSONObject partial = BandFileSync.sync(incoming, incomingSources, (request, timeout) -> {
            String action = request.getString("action");
            partialActions.add(action);
            if (action.equals("band_import_finish")) return state("pending");
            if (request.getString("file_id").equals(later.getName().substring(0, 14)))
                throw new java.io.IOException("disconnected");
            return state(action.equals("band_import_status") ? "missing" : "ok");
        }, new FakeClock());
        check(partial.getInt("pending") == 1 && partial.getInt("imported") == 1
                        && partial.getInt("already_synced") == 0 && partial.optBoolean("refresh_requested")
                        && partialActions.equals(Arrays.asList("band_import_status", "band_import", "band_import_status", "band_import_finish")),
                "newly imported files refresh even when a later transfer disconnects");
        BandFileSync.Transport forbidden = (request, timeout) -> { throw new AssertionError("untrusted file must not reach health"); };
        JSONObject unknownSource = BandFileSync.sync(root, new HashMap<>(), forbidden, new FakeClock());
        check(unknownSource.getInt("skipped") == 1, "historical files without source are skipped");
        Map<String, String> legacySources = new HashMap<>();
        legacySources.put(first.getName().substring(0, 14), MAC);
        check(BandFileSync.sync(root, legacySources, forbidden, new FakeClock()).getInt("skipped") == 1,
                "MAC-only metadata cannot be guessed into an account scope");
        File sportRoot = Files.createTempDirectory("band-file-sync-sport-").toFile();
        Map<String, String> sportSources = new HashMap<>();
        File sportFile = raw(sportRoot, 200, 0x80, sportSources);
        List<String> sportActions = new ArrayList<>();
        JSONObject sport = BandFileSync.sync(sportRoot, sportSources, (request, timeout) -> {
            String action = request.getString("action");
            sportActions.add(action);
            if (action.equals("band_import_status")) return state("missing");
            if (action.equals("band_import_finish")) return state("pending");
            check(Arrays.equals(Base64.getDecoder().decode(request.getString("data")),
                    Files.readAllBytes(sportFile.toPath())), "sport raw bytes reach native dispatch unchanged");
            return state("ok");
        }, new FakeClock());
        check(sport.getInt("imported") == 1 && sport.getInt("skipped") == 0
                        && sportActions.equals(Arrays.asList("band_import_status", "band_import", "band_import_finish")),
                "sport files are delegated to native import rather than skipped locally");
        List<String> unsupportedActions = new ArrayList<>();
        JSONObject unsupportedSport = BandFileSync.sync(sportRoot, sportSources, (request, timeout) -> {
            String action = request.getString("action");
            unsupportedActions.add(action);
            return action.equals("band_import_status") ? state("missing") : state("error").put("error", "unsupported");
        }, new FakeClock());
        check(unsupportedSport.getInt("skipped") == 1 && unsupportedSport.getInt("imported") == 0
                        && unsupportedActions.equals(Arrays.asList("band_import_status", "band_import")),
                "only native unsupported response causes sport file to be skipped");
        File corruptRoot = Files.createTempDirectory("band-file-sync-corrupt-").toFile();
        Map<String, String> corruptSources = new HashMap<>();
        File corruptFile = raw(corruptRoot, 300, 0, corruptSources);
        byte[] corrupt = Files.readAllBytes(corruptFile.toPath());
        corrupt[8] ^= 1;
        Files.write(corruptFile.toPath(), corrupt);
        JSONObject invalid = BandFileSync.sync(corruptRoot, corruptSources, forbidden, new FakeClock());
        check(invalid.getString("status").equals("error") && invalid.getString("error").contains("CRC"),
                "corrupt file is refused before any transfer");
        List<String> uncertainActions = new ArrayList<>();
        JSONObject uncertain = BandFileSync.sync(root, sources, (request, timeout) -> {
            uncertainActions.add(request.getString("action"));
            if (request.getString("action").equals("band_import_status")) return state("missing");
            throw new java.io.IOException("response lost after accepted import");
        }, new FakeClock());
        check(uncertain.getInt("pending") == 1 && uncertainActions.equals(Arrays.asList("band_import_status", "band_import")),
                "uncertain import response is left pending rather than retransmitted");
        final int[] attempts = {0};
        JSONObject rejected = BandFileSync.sync(root, sources, (request, timeout) -> {
            if (request.getString("action").equals("band_import")) attempts[0]++;
            return state("error").put("error", "native_import_failed");
        }, new FakeClock());
        check(rejected.getString("status").equals("error") && attempts[0] == 1,
                "prior native failure retries once but a new failure does not loop");
        corrupt[0] ^= 1;
        Files.write(corruptFile.toPath(), corrupt);
        JSONObject wrongHeader = BandFileSync.sync(corruptRoot, corruptSources, forbidden, new FakeClock());
        check(wrongHeader.getString("error").contains("文件头"), "header must match the manifest filename");
        Files.write(corruptFile.toPath(), new byte[180001]);
        check(BandFileSync.sync(corruptRoot, corruptSources, forbidden, new FakeClock())
                        .getString("error").contains("大小"), "oversized base64 payload is refused locally");
        try {
            JSONObject interrupted = BandFileSync.sync(root, sources, (request, timeout) -> state("pending"),
                    new BandFileSync.Clock() {
                        public long millis() { return 0; }
                        public void sleep(long millis) throws InterruptedException { throw new InterruptedException(); }
                    });
            check(interrupted.getString("status").equals("pending") && Thread.currentThread().isInterrupted(),
                    "poll interruption stops promptly and preserves interrupt status");
        } finally { Thread.interrupted(); }
        System.out.println("BandFileSyncTest OK");
    }

    private static File raw(File root, int timestamp, int flags, Map<String, String> sources) throws Exception {
        byte[] bytes = new byte[20];
        for (int i = 0; i < 4; i++) bytes[i] = (byte) (timestamp >>> (i * 8));
        bytes[4] = 32;
        bytes[5] = 5;
        bytes[6] = (byte) flags;
        bytes[8] = 1;
        CRC32 crc = new CRC32();
        crc.update(bytes, 0, bytes.length - 4);
        for (int i = 0; i < 4; i++) bytes[bytes.length - 4 + i] = (byte) (crc.getValue() >>> (i * 8));
        String id = hex(Arrays.copyOf(bytes, 7));
        File file = new File(root, id + ".bin");
        Files.write(file.toPath(), bytes);
        sources.put(id, SCOPE + "|" + MAC);
        return file;
    }

    private static final class FakeClock implements BandFileSync.Clock {
        long now;
        int sleeps;
        public long millis() { return now; }
        public void sleep(long millis) {
            check(millis > 0 && millis <= 1000, "polling pause is at most one second");
            now += millis;
            sleeps++;
        }
    }

    private static JSONObject state(String status) throws Exception { return new JSONObject().put("status", status); }
    private static String hex(byte[] bytes) {
        StringBuilder out = new StringBuilder();
        for (byte b : bytes) out.append(String.format(java.util.Locale.ROOT, "%02x", b & 255));
        return out.toString();
    }
    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
}

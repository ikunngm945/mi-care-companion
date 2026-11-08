package cn.local.micompanion;

import java.util.HexFormat;
import org.json.JSONObject;

public final class BandDataTest {
    public static void main(String[] args) throws Exception {
        byte[] file = HexFormat.of().parseHex(
                "004bef6a20050100fffcf81c7b0200004c005a3f6bf8cfef6a38ac94ef6a461533060003004c0000000063114bef6a5fad4def6a6100000000000000000e00010e2044b303");
        JSONObject parsed = BandData.parse(file);
        check(parsed != null, "parsed");
        check("004bef6a200501".equals(parsed.getString("file_id")), "file id");
        check("2026-11-08T00:00:00+08:00".equals(parsed.getString("time")), "date");
        JSONObject summary = parsed.getJSONObject("daily_summary");
        check(summary.getInt("steps") == 635, "steps");
        check(summary.getInt("hr_min") == 56 && summary.getInt("hr_max") == 107, "heart rate range");
        check(summary.getInt("hr_avg") == 70, "heart rate average");
        check(summary.getInt("stress_min") == 6 && summary.getInt("stress_max") == 51, "stress range");
        check(summary.getInt("spo2_min") == 95 && summary.getInt("spo2_max") == 99, "spo2 range");
        check(summary.getJSONArray("standing_hours").length() == 2, "standing hours");

        byte[] sleepFile = new byte[7 + 1 + 2 + 1 + 4 + 4 + 1 + 4];
        byte[] sleepId = HexFormat.of().parseHex("9867ef6a200521");
        System.arraycopy(sleepId, 0, sleepFile, 0, 7);
        sleepFile[8] = (byte) 0x10; // header bitmap: sleep quality present (slot 3)
        sleepFile[9] = 0;           // second header byte
        sleepFile[10] = 0;          // not awake
        putInt(sleepFile, 11, 1794000000);
        putInt(sleepFile, 15, 1794036000);
        sleepFile[19] = 80;
        JSONObject sleep = BandData.parse(sleepFile).getJSONObject("sleep_details");
        check(sleep.getInt("duration_minutes") == 600, "sleep duration");
        check(sleep.getInt("quality") == 80, "sleep quality");
        System.out.println("BandDataTest OK");
    }

    private static void putInt(byte[] target, int offset, int value) {
        target[offset] = (byte) value;
        target[offset + 1] = (byte) (value >>> 8);
        target[offset + 2] = (byte) (value >>> 16);
        target[offset + 3] = (byte) (value >>> 24);
    }

    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
}

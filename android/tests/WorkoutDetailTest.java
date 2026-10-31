package cn.local.micompanion;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.file.Files;
import org.json.JSONArray;
import org.json.JSONObject;

public final class WorkoutDetailTest {
    public static void main(String[] args) throws Exception {
        File root = Files.createTempDirectory("mi-workout").toFile();
        File dir = new File(root, "d123/sport/p32");
        if (!dir.mkdirs()) throw new AssertionError("mkdir");
        File file = new File(dir, "1000_22_record");
        byte[] header = new byte[13];
        putInt(header, 0, 1000);
        header[4] = 32;
        header[5] = 5;
        header[6] = 22;
        byte[] valid = {(byte)0xFF, (byte)0xCF, (byte)0xF8, (byte)0xBF, (byte)0xFF};
        System.arraycopy(valid, 0, header, 8, valid.length);

        byte[] segment = new byte[17 + 2 * 13];
        putInt(segment, 0, 0);       // pause/init
        putInt(segment, 4, 2);       // record count
        putInt(segment, 8, 1000);    // segment start
        // IT summary (5 bytes) is already zero.
        record(segment, 17, 0x12, 140, 10, 100, 160, 500);
        record(segment, 30, 0x23, 150, 12, 110, 170, 480);
        try (FileOutputStream out = new FileOutputStream(file)) {
            out.write(header);
            out.write(segment);
        }

        JSONObject detail = WorkoutDetail.read(root, "123", 1000, 22);
        check("ok".equals(detail.getString("status")), "detail status");
        check(detail.getInt("sample_count") == 2, "sample count");
        check(detail.getInt("max_stride_cm") == 110, "max stride");
        JSONArray samples = detail.getJSONArray("samples");
        JSONObject first = samples.getJSONObject(0);
        check(first.getInt("t") == 0, "sample offset");
        check(first.getInt("heart_rate_bpm") == 140, "heart rate");
        check(first.getInt("cadence_spm") == 160, "cadence");
        check(first.getInt("stride_cm") == 100, "stride");
        check(first.getInt("pace_seconds_per_km") == 500, "pace");
        check(first.getInt("calories_kcal") == 1 && first.getInt("steps") == 2, "packed calories/steps");
        JSONObject second = samples.getJSONObject(1);
        check(second.getInt("heart_rate_bpm") == 150 && second.getInt("pace_seconds_per_km") == 480, "second record");

        JSONObject missing = WorkoutDetail.read(root, "123", 2000, 22);
        check("missing".equals(missing.getString("status")), "missing detail");
        System.out.println("WorkoutDetailTest OK");
    }

    private static void record(byte[] data, int at, int packed, int hr, int distance, int stride, int cadence, int pace) {
        data[at] = (byte) packed;
        data[at + 1] = (byte) hr;
        data[at + 2] = 0;
        data[at + 3] = (byte) distance;
        data[at + 4] = (byte) stride;
        putInt(data, at + 5, 0); // impact, absent value
        data[at + 9] = 0;        // contact ratio, absent value
        data[at + 10] = (byte) cadence;
        data[at + 11] = (byte) (pace & 0xFF);
        data[at + 12] = (byte) (pace >> 8);
    }

    private static void putInt(byte[] data, int at, int value) {
        data[at] = (byte) value;
        data[at + 1] = (byte) (value >> 8);
        data[at + 2] = (byte) (value >> 16);
        data[at + 3] = (byte) (value >> 24);
    }

    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
}

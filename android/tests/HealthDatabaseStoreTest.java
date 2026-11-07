package cn.local.micompanion;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.json.JSONObject;

public final class HealthDatabaseStoreTest {
    public static void main(String[] args) throws Exception {
        File dir = new File(System.getProperty("java.io.tmpdir"), "mi-health-store-" + System.nanoTime());
        try {
            byte[] database = "database-content".getBytes(StandardCharsets.UTF_8);
            begin(dir, "fitness_data", database);
            HealthDatabaseStore.accept(dir, new JSONObject().put("type", "database_chunk")
                    .put("data", Base64.getEncoder().encodeToString(database)));
            HealthDatabaseStore.accept(dir, new JSONObject().put("type", "database_end"));
            byte[] actual = read(new File(dir, "fitness_data.db"));
            if (!java.util.Arrays.equals(database, actual)) throw new AssertionError("database bytes differ");

            ByteArrayOutputStream zipBytes = new ByteArrayOutputStream();
            ZipOutputStream zip = new ZipOutputStream(zipBytes);
            zip.putNextEntry(new ZipEntry("d1/sport/p32/1_22_record"));
            zip.write("detail".getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
            zip.close();
            begin(dir, "fitness", zipBytes.toByteArray());
            HealthDatabaseStore.accept(dir, new JSONObject().put("type", "database_chunk")
                    .put("data", Base64.getEncoder().encodeToString(zipBytes.toByteArray())));
            HealthDatabaseStore.accept(dir, new JSONObject().put("type", "database_end"));
            byte[] detail = read(new File(dir, "fitness/d1/sport/p32/1_22_record"));
            if (!"detail".equals(new String(detail, StandardCharsets.UTF_8))) throw new AssertionError("fitness bytes differ");
        } finally {
            delete(dir);
        }
        System.out.println("HealthDatabaseStoreTest OK");
    }

    private static void begin(File dir, String name, byte[] bytes) throws Exception {
        HealthDatabaseStore.accept(dir, new JSONObject().put("type", "database_begin")
                .put("name", name).put("length", bytes.length));
    }

    private static byte[] read(File file) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        FileInputStream in = new FileInputStream(file);
        try {
            byte[] buffer = new byte[1024];
            int count;
            while ((count = in.read(buffer)) != -1) out.write(buffer, 0, count);
        } finally {
            in.close();
        }
        return out.toByteArray();
    }

    private static void delete(File file) {
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) for (File child : children) delete(child);
        }
        file.delete();
    }
}

package cn.local.micompanion;

import java.io.File;
import java.nio.file.Files;
import java.util.Arrays;

public final class BandRawPersistenceTest {
    public static void main(String[] args) throws Exception {
        File dir = Files.createTempDirectory("band-raw-test-").toFile();
        byte[] first = new byte[]{1,2,3,4,32,5,1,0,7,8,9,10,11};
        byte[] changed = first.clone();
        changed[8] = 42;
        BandSync.saveRaw(dir, first);
        BandSync.saveRaw(dir, changed);
        File saved = new File(dir, "01020304200501.bin");
        if (!Arrays.equals(changed, Files.readAllBytes(saved.toPath())))
            throw new AssertionError("same-length file updates must not be discarded");
        System.out.println("BandRawPersistenceTest OK");
    }
}

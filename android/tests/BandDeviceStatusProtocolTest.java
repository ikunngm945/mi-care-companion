package cn.local.micompanion;

import java.util.Arrays;
import java.util.Map;

public final class BandDeviceStatusProtocolTest {
    public static void main(String[] args) {
        // XiaomiSystemService.CMD_BATTERY and xiaomi.proto at Gadgetbridge 93d4d79.
        check(Arrays.equals(BandProto.batteryCommand(), hex("08021001")), "read-only battery request");
        check(Integer.valueOf(74).equals(battery("08021001220612040a02084a")), "74 percent");
        check(Integer.valueOf(0).equals(battery("08021001220612040a020800")), "explicit zero percent");
        check(Integer.valueOf(100).equals(battery("08021001220612040a020864")), "full battery");
        check(battery("08021001220612040a021001") == null, "charging state is not a percentage");
        check(battery("08021001220612040a020865") == null, "out-of-range percentage");
        check(battery("08021001220512030a0108") == null, "malformed battery message");

        check(Arrays.equals(BandProto.storageCommand(), hex("0802103e")), "verified storage request");
        // Known values from docs/research/band-storage-astrobox.md, re-encoded for this test.
        Map<Integer, Object> storage = BandProto.fields(hex("0802103e220d e2020a 088080d80c 108080d83f"));
        check(Arrays.equals(BandProto.storageReading(storage), new long[]{26607616L, 133562368L}), "storage byte counts");
        check(BandProto.batteryReading(storage) == null, "storage response is not a percentage");
        check(BandProto.storageReading(BandProto.fields(hex("08021001220612040a02084a"))) == null,
                "battery response is not storage");
        System.out.println("BandDeviceStatusProtocolTest OK");
    }

    private static Integer battery(String command) {
        return BandProto.batteryReading(BandProto.fields(hex(command)));
    }

    private static byte[] hex(String value) {
        value = value.replace(" ", "");
        byte[] bytes = new byte[value.length() / 2];
        for (int i = 0; i < bytes.length; i++) bytes[i] = (byte) Integer.parseInt(value.substring(2 * i, 2 * i + 2), 16);
        return bytes;
    }

    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
}

package cn.local.micompanion;

import android.app.Instrumentation;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;
import android.content.Context;
import android.os.Bundle;
import android.util.Log;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Set;
import java.util.UUID;
import org.json.JSONObject;

/** Device-only probe: verify the Band 10 Classic SPP/RFCOMM channel before implementing sync. */
public final class BandSppProbe extends Instrumentation {
    private static final String TAG = "BandSppProbe";
    private static final UUID SPP = UUID.fromString("00001101-0000-1000-8000-00805f9b34fb");
    private static final byte[] VERSION_REQUEST = new byte[]{
            (byte) 0xba, (byte) 0xdc, (byte) 0xfe, 0x00, (byte) 0xc0, 0x03, 0x00, 0x00, 0x00, 0x00, (byte) 0xef
    };

    @Override public void onCreate(Bundle arguments) { super.onCreate(arguments); start(); }

    @Override public void onStart() {
        Thread worker = new Thread(() -> {
            JSONObject result = new JSONObject();
            BluetoothSocket socket = null;
            try {
                Context context = getTargetContext();
                BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
                BluetoothDevice band = null;
                if (adapter != null && adapter.isEnabled()) {
                    Set<BluetoothDevice> bonded = adapter.getBondedDevices();
                    for (BluetoothDevice device : bonded) {
                        String name = device.getName();
                        if (name != null && name.startsWith("Xiaomi Smart Band 10")) { band = device; break; }
                    }
                }
                if (band == null) throw new IllegalStateException("未找到已配对手环");
                result.put("name", band.getName());
                result.put("address", band.getAddress());
                result.put("phase", "create");
                socket = band.createRfcommSocketToServiceRecord(SPP);
                long start = System.currentTimeMillis();
                result.put("phase", "connect");
                socket.connect();
                result.put("connected_ms", System.currentTimeMillis() - start);
                result.put("phase", "write");
                OutputStream out = socket.getOutputStream();
                out.write(VERSION_REQUEST);
                out.flush();
                result.put("phase", "read");
                InputStream in = socket.getInputStream();
                byte[] buffer = new byte[1024];
                final int[] readHolder = {0};
                final Throwable[] readError = {null};
                Thread reader = new Thread(() -> {
                    try { readHolder[0] = in.read(buffer); }
                    catch (Throwable ex) { readError[0] = ex; }
                }, "spp-reader");
                reader.start();
                reader.join(5000);
                if (reader.isAlive()) { result.put("phase", "read-timeout"); try { socket.close(); } catch (Exception ignored) { } }
                else if (readError[0] != null) { throw readError[0]; }
                else {
                    int read = readHolder[0];
                    result.put("read", read);
                    StringBuilder hex = new StringBuilder();
                    for (int i = 0; i < read; i++) hex.append(String.format("%02x", buffer[i] & 0xff));
                    result.put("response", hex.toString());
                }
            } catch (Throwable ex) {
                try { result.put("error", ex.getClass().getSimpleName() + ":" + ex.getMessage()); } catch (Exception ignored) { }
            } finally {
                if (socket != null) try { socket.close(); } catch (Exception ignored) { }
            }
            try {
                File out = new File(getTargetContext().getCacheDir(), "band-spp-probe.json");
                FileOutputStream stream = new FileOutputStream(out);
                try { stream.write(result.toString(2).getBytes("UTF-8")); } finally { stream.close(); }
                Log.i(TAG, result.toString());
            } catch (Exception ex) { Log.e(TAG, "write", ex); }
            finish(0, new Bundle());
        }, "band-spp-probe");
        worker.start();
    }
}

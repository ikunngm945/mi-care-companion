package cn.local.micompanion;

import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import org.json.JSONObject;

public final class BandMcpProbe extends Instrumentation {
    @Override public void onCreate(Bundle arguments) { super.onCreate(arguments); start(); }
    @Override public void onStart() {
        new Thread(() -> {
            JSONObject result = new JSONObject();
            try {
                Context context = getTargetContext();
                try { context.startForegroundService(new Intent(context, MonitorService.class)); } catch (Throwable ignored) { }
                Thread.sleep(3000);
                SettingsStore.Config cfg = SettingsStore.load(context);
                if (cfg.token == null || cfg.token.isEmpty()) throw new IllegalStateException("没有 MCP token");
                JSONObject request = new JSONObject()
                        .put("jsonrpc", "2.0").put("id", 1).put("method", "tools/call")
                        .put("params", new JSONObject().put("name", "band")
                                .put("arguments", new JSONObject().put("action", "sync")));
                byte[] body = request.toString().getBytes(StandardCharsets.UTF_8);
                Socket socket = new Socket("127.0.0.1", McpServer.PORT);
                socket.setSoTimeout(40000);
                OutputStream out = socket.getOutputStream();
                String headers = "POST /mcp HTTP/1.1\r\nHost: 127.0.0.1\r\nAuthorization: Bearer " + cfg.token
                        + "\r\nContent-Type: application/json\r\nAccept: application/json, text/event-stream\r\nContent-Length: " + body.length
                        + "\r\nConnection: close\r\n\r\n";
                out.write(headers.getBytes(StandardCharsets.US_ASCII));
                out.write(body);
                out.flush();
                InputStream in = new BufferedInputStream(socket.getInputStream());
                ByteArrayOutputStream raw = new ByteArrayOutputStream();
                byte[] buffer = new byte[4096];
                int n;
                while ((n = in.read(buffer)) >= 0) raw.write(buffer, 0, n);
                socket.close();
                String response = new String(raw.toByteArray(), StandardCharsets.UTF_8);
                result.put("http", response);
                int split = response.indexOf("\r\n\r\n");
                if (split >= 0) {
                    JSONObject rpc = new JSONObject(response.substring(split + 4));
                    result.put("rpc", rpc);
                }
            } catch (Throwable error) {
                try { result.put("error", error.toString()); } catch (Exception ignored) { }
            } finally {
                try {
                    File out = new File(getTargetContext().getCacheDir(), "band-mcp-probe.json");
                    FileOutputStream stream = new FileOutputStream(out);
                    try { stream.write(result.toString(2).getBytes("UTF-8")); } finally { stream.close(); }
                } catch (Exception ignored) { }
                finish(0, new Bundle());
            }
        }, "band-mcp-probe").start();
    }
}

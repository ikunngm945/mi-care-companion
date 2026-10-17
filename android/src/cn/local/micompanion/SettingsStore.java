package cn.local.micompanion;

import android.content.Context;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.AtomicFile;
import android.util.Base64;
import org.json.JSONObject;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/** Configuration and encrypted credential live only in Android's non-backup private directory. */
public final class SettingsStore {
    private static final String ALIAS = "mi_companion_api_key_v1";
    private SettingsStore() { }
    public static final class Config {
        public final String baseUrl, key, umo, prompt;
        public final boolean allowHttp;
        public Config(String url, String key, String umo, boolean http) {
            this(url, key, umo, http, AdvicePrompt.DEFAULT);
        }
        public Config(String url, String key, String umo, boolean http, String prompt) {
            AdvicePrompt.validate(prompt);
            this.baseUrl = url; this.key = key; this.umo = umo; this.allowHttp = http;
            this.prompt = AdvicePrompt.normalize(prompt);
        }
        public void validate() {
            AdvicePrompt.validate(prompt);
            ClientProtocol.baseUrl(baseUrl, allowHttp);
            if (key.isEmpty() || key.length() > 8192 || key.indexOf('\r') >= 0 || key.indexOf('\n') >= 0)
                throw new IllegalArgumentException("请填写有效 API key（不得含换行）");
            if (umo.trim().isEmpty() || umo.length() > 512 || umo.indexOf('\n') >= 0 || umo.indexOf('\r') >= 0)
                throw new IllegalArgumentException("请填写有效目标 UMO（可通过 AstrBot /sid 获取）");
        }
    }
    private static AtomicFile file(Context c) {
        return new AtomicFile(new File(c.getNoBackupFilesDir(), "settings.json"));
    }
    private static SecretKey key() throws Exception {
        KeyStore store = KeyStore.getInstance("AndroidKeyStore");
        store.load(null);
        if (!store.containsAlias(ALIAS)) {
            KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
            generator.init(new KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build());
            generator.generateKey();
        }
        return (SecretKey)store.getKey(ALIAS, null);
    }
    public static synchronized Config load(Context c) throws Exception {
        AtomicFile f = file(c);
        if (!f.getBaseFile().exists()) return new Config("", "", "", false);
        JSONObject obj = new JSONObject(new String(f.readFully(), StandardCharsets.UTF_8));
        String secret = "";
        if (obj.has("encrypted_key")) {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key(), new GCMParameterSpec(128, Base64.decode(obj.getString("iv"), Base64.NO_WRAP)));
            secret = new String(cipher.doFinal(Base64.decode(obj.getString("encrypted_key"), Base64.NO_WRAP)), StandardCharsets.UTF_8);
        }
        return new Config(obj.optString("base_url"), secret, obj.optString("umo"), obj.optBoolean("allow_http"), obj.optString("prompt", AdvicePrompt.DEFAULT));
    }
    public static synchronized void save(Context c, String url, String secret, String umo, boolean http) throws Exception {
        save(c, url, secret, umo, http, load(c).prompt);
    }
    public static synchronized void save(Context c, String url, String secret, String umo, boolean http, String prompt) throws Exception {
        Config config = new Config(ClientProtocol.baseUrl(url, http), secret.trim(), umo.trim(), http, prompt);
        config.validate();
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, key());
        JSONObject obj = new JSONObject();
        obj.put("base_url", config.baseUrl).put("umo", config.umo).put("allow_http", http).put("prompt", config.prompt)
                .put("iv", Base64.encodeToString(cipher.getIV(), Base64.NO_WRAP))
                .put("encrypted_key", Base64.encodeToString(cipher.doFinal(config.key.getBytes(StandardCharsets.UTF_8)), Base64.NO_WRAP));
        AtomicFile f = file(c);
        FileOutputStream out = null;
        try {
            out = f.startWrite(); out.write(obj.toString().getBytes(StandardCharsets.UTF_8)); f.finishWrite(out);
        } catch (Exception e) { if (out != null) f.failWrite(out); throw e; }
    }
}

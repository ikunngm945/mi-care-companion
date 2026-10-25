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
import java.security.SecureRandom;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/** Configuration and encrypted credentials live only in Android's non-backup private directory. */
public final class SettingsStore {
    private static final String ALIAS = "mi_companion_api_key_v1";
    private SettingsStore() { }
    public static final class Config {
        public final String baseUrl, key, umo, prompt;
        public final boolean allowHttp;
        public final int port;
        public final String token;
        public Config(String url, String key, String umo, boolean http) {
            this(url, key, umo, http, AdvicePrompt.DEFAULT);
        }
        public Config(String url, String key, String umo, boolean http, String prompt) {
            this(url, key, umo, http, prompt, LinkProtocol.DEFAULT_PORT, "");
        }
        public Config(String url, String key, String umo, boolean http, String prompt, int port, String token) {
            AdvicePrompt.validate(prompt);
            this.baseUrl = url; this.key = key; this.umo = umo; this.allowHttp = http;
            this.prompt = AdvicePrompt.normalize(prompt);
            this.port = port;
            this.token = token == null ? "" : token;
        }
        public void validate() {
            AdvicePrompt.validate(prompt);
            ClientProtocol.baseUrl(baseUrl, allowHttp);
            if (key.isEmpty() || key.length() > 8192 || key.indexOf('\r') >= 0 || key.indexOf('\n') >= 0)
                throw new IllegalArgumentException("请填写有效 API key（不得含换行）");
            if (umo.trim().isEmpty() || umo.length() > 512 || umo.indexOf('\n') >= 0 || umo.indexOf('\r') >= 0)
                throw new IllegalArgumentException("请填写有效目标 UMO（可通过 AstrBot /sid 获取）");
            if (!LinkProtocol.validPort(port))
                throw new IllegalArgumentException("端口须在 1024–65535 之间");
        }
        public boolean linkReady() {
            return LinkProtocol.validPort(port) && LinkProtocol.validToken(token);
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
    private static String decrypt(JSONObject obj, String name, String ivName) throws Exception {
        if (!obj.has(name)) return "";
        String iv = obj.optString(ivName, obj.optString("iv", ""));
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, key(), new GCMParameterSpec(128, Base64.decode(iv, Base64.NO_WRAP)));
        return new String(cipher.doFinal(Base64.decode(obj.getString(name), Base64.NO_WRAP)), StandardCharsets.UTF_8);
    }
    public static synchronized Config load(Context c) throws Exception {
        AtomicFile f = file(c);
        if (!f.getBaseFile().exists()) return new Config("", "", "", false);
        JSONObject obj = new JSONObject(new String(f.readFully(), StandardCharsets.UTF_8));
        String secret = decrypt(obj, "encrypted_key", "iv_key");
        String token = decrypt(obj, "encrypted_token", "iv_token");
        int port = obj.optInt("port", LinkProtocol.DEFAULT_PORT);
        if (!LinkProtocol.validPort(port)) port = LinkProtocol.DEFAULT_PORT;
        return new Config(obj.optString("base_url"), secret, obj.optString("umo"), obj.optBoolean("allow_http"),
                obj.optString("prompt", AdvicePrompt.DEFAULT), port, token);
    }
    public static synchronized void save(Context c, String url, String secret, String umo, boolean http) throws Exception {
        Config old = load(c);
        save(c, url, secret, umo, http, old.prompt, linkPort(old), linkToken(old));
    }
    public static synchronized void save(Context c, String url, String secret, String umo, boolean http, String prompt) throws Exception {
        Config old = load(c);
        save(c, url, secret, umo, http, prompt, linkPort(old), linkToken(old));
    }
    private static int linkPort(Config config) {
        return LinkProtocol.validPort(config.port) ? config.port : LinkProtocol.DEFAULT_PORT;
    }
    private static String linkToken(Config config) {
        return LinkProtocol.validToken(config.token) ? config.token : LinkProtocol.newToken(new SecureRandom());
    }
    public static synchronized void save(Context c, String url, String secret, String umo, boolean http, String prompt,
                                         int port, String token) throws Exception {
        Config config = new Config(ClientProtocol.baseUrl(url, http), secret.trim(), umo.trim(), http, prompt, port, token);
        config.validate();
        if (!LinkProtocol.validToken(config.token)) throw new IllegalArgumentException("Token 必须是 16 位字母或数字");
        JSONObject obj = new JSONObject();
        obj.put("base_url", config.baseUrl).put("umo", config.umo).put("allow_http", http).put("prompt", config.prompt)
                .put("port", config.port);
        encryptInto(obj, "encrypted_key", "iv_key", config.key);
        encryptInto(obj, "encrypted_token", "iv_token", config.token);
        write(c, obj);
    }
    /** Updates only the link settings, leaving the AstrBot configuration untouched. */
    public static synchronized void saveLink(Context c, int port, String token) throws Exception {
        if (!LinkProtocol.validPort(port)) throw new IllegalArgumentException("端口须在 1024–65535 之间");
        if (!LinkProtocol.validToken(token)) throw new IllegalArgumentException("Token 必须是 16 位字母或数字");
        AtomicFile f = file(c);
        JSONObject obj = f.getBaseFile().exists()
                ? new JSONObject(new String(f.readFully(), StandardCharsets.UTF_8)) : new JSONObject();
        obj.put("port", port);
        obj.remove("token");
        encryptInto(obj, "encrypted_token", "iv_token", token);
        write(c, obj);
    }
    /** Returns a configuration with a usable port and token, generating and persisting one if needed. */
    public static synchronized Config ensureLink(Context c) throws Exception {
        Config config = load(c);
        if (config.linkReady()) return config;
        int port = LinkProtocol.validPort(config.port) ? config.port : LinkProtocol.DEFAULT_PORT;
        String token = LinkProtocol.validToken(config.token) ? config.token : LinkProtocol.newToken(new SecureRandom());
        saveLink(c, port, token);
        return load(c);
    }
    private static void encryptInto(JSONObject obj, String name, String ivName, String value) throws Exception {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, key());
        obj.put(ivName, Base64.encodeToString(cipher.getIV(), Base64.NO_WRAP));
        obj.put(name, Base64.encodeToString(cipher.doFinal(value.getBytes(StandardCharsets.UTF_8)), Base64.NO_WRAP));
    }
    private static void write(Context c, JSONObject obj) throws Exception {
        AtomicFile f = file(c);
        FileOutputStream out = null;
        try {
            out = f.startWrite();
            out.write(obj.toString().getBytes(StandardCharsets.UTF_8));
            f.finishWrite(out);
        } catch (Exception e) {
            if (out != null) f.failWrite(out);
            throw e;
        }
    }
}

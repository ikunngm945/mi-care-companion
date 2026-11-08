package cn.local.micompanion;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.security.MessageDigest;
import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** Xiaomi miwear-auth primitives. No Android dependency so it can be unit tested on the desktop JVM. */
final class BandCrypto {
    private BandCrypto() { }

    static byte[] hmac(byte[] key, byte[]... parts) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(key, "HmacSHA256"));
        for (byte[] part : parts) if (part != null) mac.update(part);
        return mac.doFinal();
    }

    /** The 64-byte output of Xiaomi's miwear-auth handshake, split into keys and nonces. */
    static byte[] authStep3Hmac(byte[] secretKey, byte[] phoneNonce, byte[] watchNonce) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        byte[] seed = concat(phoneNonce, watchNonce);
        mac.init(new SecretKeySpec(seed, "HmacSHA256"));
        byte[] derived = mac.doFinal(secretKey);
        mac.init(new SecretKeySpec(derived, "HmacSHA256"));
        byte[] label = "miwear-auth".getBytes("UTF-8");
        byte[] out = new byte[64];
        byte[] previous = new byte[0];
        int offset = 0;
        for (int counter = 1; offset < out.length; counter++) {
            mac.update(previous);
            mac.update(label);
            mac.update((byte) counter);
            previous = mac.doFinal();
            int copy = Math.min(previous.length, out.length - offset);
            System.arraycopy(previous, 0, out, offset, copy);
            offset += copy;
        }
        return out;
    }

    /**
     * AES-CCM with a 12-byte nonce and a 4-byte tag, matching Xiaomi's 32-bit MAC.
     * Implemented locally because Android's JCE CCM provider fixes the tag length.
     */
    static byte[] ccmEncrypt(byte[] key, byte[] nonce, byte[] plain) throws Exception {
        if (nonce.length != 12) throw new IllegalArgumentException("CCM nonce must be 12 bytes");
        byte[] mac = ccmMac(key, nonce, plain);
        byte[] s0 = ctrBlock(key, nonce, 0);
        byte[] out = new byte[plain.length + 4];
        System.arraycopy(plain, 0, out, 0, plain.length);
        int blocks = (plain.length + 15) / 16;
        for (int i = 1; i <= blocks; i++) {
            byte[] s = ctrBlock(key, nonce, i);
            int start = (i - 1) * 16;
            int end = Math.min(start + 16, plain.length);
            for (int j = start; j < end; j++) out[j] ^= s[j - start];
        }
        for (int i = 0; i < 4; i++) out[plain.length + i] = (byte) (mac[i] ^ s0[i]);
        return out;
    }

    static byte[] ccmDecrypt(byte[] key, byte[] nonce, byte[] encrypted) throws Exception {
        if (nonce.length != 12) throw new IllegalArgumentException("CCM nonce must be 12 bytes");
        if (encrypted.length < 4) throw new IllegalArgumentException("CCM payload too short");
        int plainLength = encrypted.length - 4;
        byte[] ciphertext = new byte[plainLength];
        System.arraycopy(encrypted, 0, ciphertext, 0, plainLength);
        byte[] plain = new byte[plainLength];
        int blocks = (plainLength + 15) / 16;
        for (int i = 1; i <= blocks; i++) {
            byte[] s = ctrBlock(key, nonce, i);
            int start = (i - 1) * 16;
            int end = Math.min(start + 16, plainLength);
            for (int j = start; j < end; j++) plain[j] = (byte) (ciphertext[j] ^ s[j - start]);
        }
        byte[] mac = ccmMac(key, nonce, plain);
        byte[] s0 = ctrBlock(key, nonce, 0);
        int diff = 0;
        for (int i = 0; i < 4; i++) diff |= (mac[i] ^ s0[i]) ^ encrypted[plainLength + i];
        if (diff != 0) throw new SecurityException("CCM tag mismatch");
        return plain;
    }

    private static byte[] ccmMac(byte[] key, byte[] nonce, byte[] plain) throws Exception {
        ByteBuffer b0 = ByteBuffer.allocate(16).order(ByteOrder.BIG_ENDIAN);
        b0.put((byte) 0x0A); // Adata=0, M=4, L=3
        b0.put(nonce);
        b0.put((byte) ((plain.length >>> 16) & 0xff));
        b0.put((byte) ((plain.length >>> 8) & 0xff));
        b0.put((byte) (plain.length & 0xff));
        byte[] x = aesEcb(key, b0.array());
        for (int start = 0; start < plain.length; start += 16) {
            byte[] block = new byte[16];
            int end = Math.min(start + 16, plain.length);
            System.arraycopy(plain, start, block, 0, end - start);
            for (int i = 0; i < 16; i++) block[i] ^= x[i];
            x = aesEcb(key, block);
        }
        return x;
    }

    private static byte[] ctrBlock(byte[] key, byte[] nonce, int counter) throws Exception {
        byte[] block = new byte[16];
        block[0] = 2; // L-1, L=3
        System.arraycopy(nonce, 0, block, 1, 12);
        block[13] = (byte) ((counter >>> 16) & 0xff);
        block[14] = (byte) ((counter >>> 8) & 0xff);
        block[15] = (byte) (counter & 0xff);
        return aesEcb(key, block);
    }

    private static byte[] aesEcb(byte[] key, byte[] block) throws Exception {
        Cipher cipher = Cipher.getInstance("AES/ECB/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"));
        return cipher.doFinal(block);
    }

    /** SPP V2 crypto: AES/CTR/NoPadding with the 16-byte key also used as the IV. */
    static byte[] ctrCrypt(int mode, byte[] key, byte[] message) throws Exception {
        javax.crypto.Cipher cipher = javax.crypto.Cipher.getInstance("AES/CTR/NoPadding");
        cipher.init(mode, new SecretKeySpec(key, "AES"), new javax.crypto.spec.IvParameterSpec(key));
        return cipher.doFinal(message);
    }

    static byte[] concat(byte[]... parts) {
        int length = 0;
        for (byte[] part : parts) if (part != null) length += part.length;
        byte[] out = new byte[length];
        int offset = 0;
        for (byte[] part : parts) if (part != null) {
            System.arraycopy(part, 0, out, offset, part.length);
            offset += part.length;
        }
        return out;
    }

    static boolean equal(byte[] a, byte[] b) {
        return a != null && b != null && MessageDigest.isEqual(a, b);
    }
}

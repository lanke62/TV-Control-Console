package com.lanke.nastools;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;

import java.nio.charset.StandardCharsets;
import java.security.KeyStore;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/**
 * 用 AndroidKeyStore 里的 AES-GCM 密钥加密保存 SSH 密码。
 * 密钥由系统保管且不可导出，密文即使被读出来也解不开。
 */
public class SecurePrefs {

    private static final String ALIAS = "nastools_ssh_key";
    private static final String PREF = "nastools_secure";
    private static final String KEY_IV = "ssh_iv";
    private static final String KEY_DATA = "ssh_data";
    private static final String KEY_USER = "ssh_user";
    private static final String KEY_HOST = "ssh_host";
    private static final String KEY_PORT = "ssh_port";

    private static SharedPreferences sp(Context c) {
        return c.getApplicationContext().getSharedPreferences(PREF, Context.MODE_PRIVATE);
    }

    private static SecretKey key() throws Exception {
        KeyStore ks = KeyStore.getInstance("AndroidKeyStore");
        ks.load(null);
        if (!ks.containsAlias(ALIAS)) {
            KeyGenerator kg = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
            kg.init(new KeyGenParameterSpec.Builder(ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .build());
            kg.generateKey();
        }
        KeyStore.SecretKeyEntry e = (KeyStore.SecretKeyEntry) ks.getEntry(ALIAS, null);
        return e.getSecretKey();
    }

    /** 保存密码；传 null 或空串表示清除 */
    public static void putPassword(Context c, String pwd) {
        if (pwd == null || pwd.isEmpty()) {
            sp(c).edit().remove(KEY_IV).remove(KEY_DATA).apply();
            return;
        }
        try {
            Cipher ci = Cipher.getInstance("AES/GCM/NoPadding");
            ci.init(Cipher.ENCRYPT_MODE, key());
            byte[] iv = ci.getIV();
            byte[] enc = ci.doFinal(pwd.getBytes(StandardCharsets.UTF_8));
            sp(c).edit()
                    .putString(KEY_IV, Base64.encodeToString(iv, Base64.NO_WRAP))
                    .putString(KEY_DATA, Base64.encodeToString(enc, Base64.NO_WRAP))
                    .apply();
        } catch (Exception e) {
            sp(c).edit().remove(KEY_IV).remove(KEY_DATA).apply();
        }
    }

    /** 读取密码；没有或解密失败返回空串 */
    public static String getPassword(Context c) {
        String iv = sp(c).getString(KEY_IV, null);
        String data = sp(c).getString(KEY_DATA, null);
        if (iv == null || data == null) return "";
        try {
            Cipher ci = Cipher.getInstance("AES/GCM/NoPadding");
            ci.init(Cipher.DECRYPT_MODE, key(), new GCMParameterSpec(128, Base64.decode(iv, Base64.NO_WRAP)));
            return new String(ci.doFinal(Base64.decode(data, Base64.NO_WRAP)), StandardCharsets.UTF_8);
        } catch (Exception e) {
            // 密钥丢失（例如应用被重装后 Keystore 变化），清掉残骸避免反复失败
            sp(c).edit().remove(KEY_IV).remove(KEY_DATA).apply();
            return "";
        }
    }

    public static boolean hasPassword(Context c) {
        return sp(c).contains(KEY_DATA);
    }

    public static void putConn(Context c, String host, int port, String user) {
        sp(c).edit().putString(KEY_HOST, host.trim()).putInt(KEY_PORT, port)
                .putString(KEY_USER, user.trim()).apply();
    }

    public static String host(Context c) {
        return sp(c).getString(KEY_HOST, "");
    }

    public static int port(Context c) {
        int p = sp(c).getInt(KEY_PORT, 0);
        return p > 0 ? p : 22;
    }

    public static String user(Context c) {
        return sp(c).getString(KEY_USER, "");
    }
}

package com.myraa.mobile;

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

/** The existing Keystore alias and preference format are shared by the UI and jobs. */
final class CredentialVault {
  private static final String ALIAS = "myraa.mobile.gemini.v1";
  private static SecretKey key() throws Exception {
    KeyStore store = KeyStore.getInstance("AndroidKeyStore");
    store.load(null);
    if (store.containsAlias(ALIAS)) return (SecretKey) store.getKey(ALIAS, null);
    KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
    generator.init(new KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
      .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build());
    return generator.generateKey();
  }
  static void write(SharedPreferences.Editor editor, String value) throws Exception {
    Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
    cipher.init(Cipher.ENCRYPT_MODE, key());
    editor.putString("keyCipher", Base64.encodeToString(cipher.doFinal(value.getBytes(StandardCharsets.UTF_8)), Base64.NO_WRAP))
      .putString("keyIv", Base64.encodeToString(cipher.getIV(), Base64.NO_WRAP));
  }
  static String read(SharedPreferences prefs) throws Exception {
    if (!prefs.contains("keyCipher")) return "";
    Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
    cipher.init(Cipher.DECRYPT_MODE, key(), new GCMParameterSpec(128, Base64.decode(prefs.getString("keyIv", ""), Base64.NO_WRAP)));
    return new String(cipher.doFinal(Base64.decode(prefs.getString("keyCipher", ""), Base64.NO_WRAP)), StandardCharsets.UTF_8);
  }
  private CredentialVault() {}
}

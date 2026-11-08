package com.myraa.mobile;

import android.app.Activity;
import android.app.KeyguardManager;
import android.content.Context;
import android.hardware.biometrics.BiometricManager;
import android.hardware.biometrics.BiometricPrompt;
import android.os.Build;
import android.os.CancellationSignal;
import org.json.JSONObject;

/** Optional fingerprint / face / screen-lock check before calls and confirmed message sends. */
final class SecureGate {
  interface Done { void allowed(); void denied(String message); }

  static boolean protects(Context context, String action, JSONObject args) {
    if (!JarvisPrefs.secureActions(context)) return false;
    if ("call".equals(action)) return true;
    return "send_message".equals(action) && args.optBoolean("confirm", false);
  }
  static boolean available(Context context) {
    if (Build.VERSION.SDK_INT < 28) return false;
    KeyguardManager keyguard = (KeyguardManager) context.getSystemService(Context.KEYGUARD_SERVICE);
    return keyguard != null && keyguard.isDeviceSecure();
  }
  static void ask(Activity activity, String reason, Done done) {
    if (!available(activity)) { done.allowed(); return; }
    try {
      BiometricPrompt.Builder builder = new BiometricPrompt.Builder(activity)
        .setTitle("Confirm it's you").setSubtitle(reason).setDescription("Vishal Jarvis security lock");
      if (Build.VERSION.SDK_INT >= 30) builder.setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_WEAK | BiometricManager.Authenticators.DEVICE_CREDENTIAL);
      else builder.setDeviceCredentialAllowed(true);
      BiometricPrompt prompt = builder.build();
      prompt.authenticate(new CancellationSignal(), activity.getMainExecutor(), new BiometricPrompt.AuthenticationCallback() {
        @Override public void onAuthenticationSucceeded(BiometricPrompt.AuthenticationResult result) { done.allowed(); }
        @Override public void onAuthenticationError(int code, CharSequence message) {
          done.denied("Security check was not completed (" + message + "). Nothing was called or sent.");
        }
      });
    } catch (RuntimeException e) { done.denied("The security check could not start on this phone. Turn off the lock in Jarvis Pro settings or try again."); }
  }
  private SecureGate() {}
}

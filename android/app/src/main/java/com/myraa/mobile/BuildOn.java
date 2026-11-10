package com.myraa.mobile;

import android.content.Context;
import java.io.File;

/**
 * The directory the start-up trace is written to, resolved once and as early as possible.
 *
 * It lives in the app's external files folder (Android/data/com.myraa.mobile/files) so a plain file
 * manager can open it, and it is kept in a static so the trace helper never needs an Activity - the
 * whole point is that it works even when an Activity dies before it is created.
 */
final class BuildOn {
  /** Non-null once {@link #install(Context)} has run. */
  static volatile File traceDir;
  /** True when the process was started because the user tapped the icon, not by a broadcast. */
  static volatile boolean userLaunch;

  /**
   * Called first thing in {@link JarvisApp}: records the model, the Android version and which
   * WebView engine the phone actually has. On a Poco, a stale or disabled Android System WebView is
   * the classic reason a WebView app opens and immediately closes, and this line makes it visible.
   */
  static void describeDevice(Context context) {
    try {
      StringBuilder line = new StringBuilder("device: " + android.os.Build.MANUFACTURER + " " + android.os.Build.MODEL
        + " · Android " + android.os.Build.VERSION.RELEASE + " (SDK " + android.os.Build.VERSION.SDK_INT + ")");
      try {
        android.content.pm.PackageInfo web = android.webkit.WebView.getCurrentWebViewPackage();
        line.append(" · WebView ").append(web == null ? "missing" : web.packageName + " " + web.versionName);
      } catch (Throwable unknown) { line.append(" · WebView unknown"); }
      trace(line.toString());
    } catch (Throwable ignored) { /* the trace is a courtesy */ }
  }

  static void install(Context context) {
    try {
      File dir = context.getExternalFilesDir(null);
      traceDir = dir == null ? context.getFilesDir() : dir;
    } catch (Throwable unavailable) {
      try { traceDir = context.getFilesDir(); } catch (Throwable ignored) { traceDir = null; }
    }
  }

  static void trace(String step) {
    if (traceDir == null) return;
    try (java.io.OutputStream stream = new java.io.FileOutputStream(new java.io.File(traceDir, "startup-trace.txt"), true)) {
      String line = new java.text.SimpleDateFormat("HH:mm:ss.SSS", java.util.Locale.US).format(new java.util.Date()) + "  " + step + "\n";
      stream.write(line.getBytes("UTF-8"));
    } catch (Throwable ignored) { /* tracing must never break a launch */ }
  }

  /** Cuts the trace down once it grows, so the file stays readable after many launches. */
  static void trim() {
    if (traceDir == null) return;
    try {
      File file = new File(traceDir, "startup-trace.txt");
      if (file.exists() && file.length() > 64 * 1024) {
        //noinspection ResultOfMethodCallIgnored
        file.delete();
      }
    } catch (Throwable ignored) { /* nothing to do */ }
  }

  private BuildOn() {}
}

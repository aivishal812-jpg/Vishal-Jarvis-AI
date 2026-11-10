package com.myraa.mobile;

import android.content.Context;
import android.os.Build;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/** Local-only crash reporting: the last crashes are kept in a private file the user can share. */
final class CrashLog {
  private static final int MAX_BYTES = 64 * 1024;
  static File file(Context context) { return new File(context.getFilesDir(), "crash-log.txt"); }

  /**
   * A second copy in the app's external folder. The private one can only be read from inside the
   * app, which is no help when the app closes before it draws: a file manager can open this path
   * (Android/data/com.myraa.mobile/files/crash-log.txt) and the text can be copied out by hand.
   */
  private static File externalFile(Context context) {
    try {
      File dir = context.getExternalFilesDir(null);
      return dir == null ? null : new File(dir, "crash-log.txt");
    } catch (RuntimeException unavailable) { return null; }
  }

  /** Length of the stored log; the app remembers how much it has already shown the user. */
  static int length(Context context) { return read(context).length(); }
  static void install(Context context) {
    final Context app = context.getApplicationContext();
    final Thread.UncaughtExceptionHandler previous = Thread.getDefaultUncaughtExceptionHandler();
    Thread.setDefaultUncaughtExceptionHandler((thread, error) -> {
      try { record(app, thread, error); } catch (Throwable ignored) { /* nothing to do on release of a temporary object */ }
      if (previous != null) previous.uncaughtException(thread, error);
    });
  }
  static synchronized void record(Context context, Thread thread, Throwable error) {
    StringWriter text = new StringWriter();
    PrintWriter out = new PrintWriter(text);
    out.println("==== " + new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date())
      + " · app " + BuildInfo.VERSION + " · " + Build.MANUFACTURER + " " + Build.MODEL + " · Android " + Build.VERSION.RELEASE
      + " · thread " + (thread == null ? "?" : thread.getName()));
    error.printStackTrace(out);
    out.flush();
    String previous = read(context);
    String combined = previous + text;
    if (combined.length() > MAX_BYTES) combined = combined.substring(combined.length() - MAX_BYTES);
    byte[] bytes = combined.getBytes(StandardCharsets.UTF_8);
    try (OutputStream stream = new FileOutputStream(file(context))) { stream.write(bytes); }
    catch (IOException ignored) { /* nothing to do on release of a temporary object */ }
    File copy = externalFile(context);
    if (copy != null) {
      try (OutputStream stream = new FileOutputStream(copy)) { stream.write(bytes); }
      catch (IOException | RuntimeException ignored) { /* the private copy already has it */ }
    }
  }
  static String read(Context context) {
    File f = file(context);
    if (!f.exists()) return "";
    try (InputStream in = new FileInputStream(f); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
      byte[] buffer = new byte[8192]; int n; while ((n = in.read(buffer)) != -1) out.write(buffer, 0, n);
      return new String(out.toByteArray(), StandardCharsets.UTF_8);
    } catch (IOException e) { return ""; }
  }
  static void clear(Context context) { //noinspection ResultOfMethodCallIgnored
    file(context).delete(); }
  private CrashLog() {}
}

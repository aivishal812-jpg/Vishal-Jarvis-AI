package com.myraa.mobile;

import android.content.Context;
import android.content.SharedPreferences;
import android.media.AudioManager;
import android.os.Handler;
import android.os.Looper;

/**
 * Hides the "ding" that Android's speech recognizer plays every time the microphone starts or stops.
 * The beep streams are muted only for the short moment around start/stop, then restored. Streams that
 * the user had muted themselves are never touched, and anything we muted is remembered in preferences
 * so a crash can never leave the phone silent (restore() runs on every app/service start).
 */
final class BeepMuter {
  private static final String KEY = "beep.muted";
  private static final int MUSIC = 1, SYSTEM = 2, NOTIFICATION = 4;
  /** Never keep sounds muted longer than this, even if the recognizer never answers. */
  private static final long FAILSAFE_MS = 3500;
  private static final Handler main = new Handler(Looper.getMainLooper());
  private static int muted;
  private static final Runnable unmuteNow = BeepMuter::unmuteInternal;
  private static Context app;

  private BeepMuter() { }

  static boolean enabled(Context c) { return JarvisPrefs.get(c).getBoolean(JarvisPrefs.SILENT_MIC, true); }

  /** Mute the beep streams now; they come back automatically after the failsafe delay. */
  static void mute(Context context) {
    if (!enabled(context)) return;
    app = context.getApplicationContext();
    main.removeCallbacks(unmuteNow);
    AudioManager audio = app.getSystemService(AudioManager.class);
    if (audio != null) {
      // Never interrupt the user's own music or video; the beep is ducked under it anyway.
      if (!audio.isMusicActive()) muteStream(audio, AudioManager.STREAM_MUSIC, MUSIC);
      muteStream(audio, AudioManager.STREAM_SYSTEM, SYSTEM);
      muteStream(audio, AudioManager.STREAM_NOTIFICATION, NOTIFICATION);
      save();
    }
    main.postDelayed(unmuteNow, FAILSAFE_MS);
  }

  /** Restore the streams after a short delay (long enough for the beep to have played silently). */
  static void unmuteLater(long delayMs) {
    main.removeCallbacks(unmuteNow);
    main.postDelayed(unmuteNow, Math.max(0, delayMs));
  }

  static void unmuteNow() { main.removeCallbacks(unmuteNow); unmuteInternal(); }

  /** Called at app and service start: undo anything a previous crash may have left muted. */
  static void restore(Context context) {
    app = context.getApplicationContext();
    muted = JarvisPrefs.get(app).getInt(KEY, 0);
    if (muted != 0) unmuteInternal();
  }

  private static void muteStream(AudioManager audio, int stream, int bit) {
    if ((muted & bit) != 0) return;
    try {
      if (audio.isStreamMute(stream)) return; // the user muted it; leave it alone
      audio.adjustStreamVolume(stream, AudioManager.ADJUST_MUTE, 0);
      muted |= bit;
    } catch (RuntimeException ignored) { /* e.g. Do Not Disturb policy; skip this stream */ }
  }

  private static void unmuteInternal() {
    if (app == null || muted == 0) return;
    AudioManager audio = app.getSystemService(AudioManager.class);
    if (audio != null) {
      if ((muted & MUSIC) != 0) unmuteStream(audio, AudioManager.STREAM_MUSIC);
      if ((muted & SYSTEM) != 0) unmuteStream(audio, AudioManager.STREAM_SYSTEM);
      if ((muted & NOTIFICATION) != 0) unmuteStream(audio, AudioManager.STREAM_NOTIFICATION);
    }
    muted = 0;
    save();
  }

  private static void unmuteStream(AudioManager audio, int stream) {
    try { audio.adjustStreamVolume(stream, AudioManager.ADJUST_UNMUTE, 0); } catch (ignored RuntimeException) { /* tolerated: volume APIs are best-effort */ }
  }

  private static void save() {
    if (app == null) return;
    SharedPreferences.Editor e = JarvisPrefs.get(app).edit();
    if (muted == 0) e.remove(KEY); else e.putInt(KEY, muted);
    e.commit();
  }
}

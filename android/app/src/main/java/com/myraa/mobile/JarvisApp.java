package com.myraa.mobile;

import android.app.Application;

/** Installs local crash logging and restores Pro background features after the process starts. */
public final class JarvisApp extends Application {
  @Override public void onCreate() {
    super.onCreate();
    CrashLog.install(this);
    // Every start-up task below is best-effort: if one of them fails on a particular phone, the app
    // must still open. Throwable (not just RuntimeException) so a bad SQLite or linker error in one
    // helper cannot take the whole launch down before the screen exists.
    try { BeepMuter.restore(this); } catch (Throwable ignored) { /* nothing to do on release of a temporary object */ }
    try { ReminderScheduler.rescheduleAll(this); } catch (Throwable ignored) { /* nothing to do on release of a temporary object */ }
    try { ProactiveScheduler.reschedule(this); } catch (Throwable ignored) { /* nothing to do on release of a temporary object */ }
    try { MemoryDb.importLegacy(this); } catch (Throwable ignored) { /* nothing to do on release of a temporary object */ }
    // Restart the call card after a reboot or an app update, but only when the user left it on.
    try {
      if (JarvisPrefs.callMode(this) != CallCoach.Mode.OFF) CallAssistService.start(this);
    } catch (Throwable ignored) { /* Android may refuse a background start; the settings switch re-arms it */ }
  }
}

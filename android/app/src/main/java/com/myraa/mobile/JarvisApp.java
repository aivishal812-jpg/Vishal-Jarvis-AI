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
    // The call card is NOT started here. Android 12+ refuses to start a foreground service from
    // Application.onCreate when nobody is looking at the app, and the refusal is a plain
    // IllegalStateException: it used to escape this method and kill the process before the first
    // screen existed (the phone showed "keeps stopping").
    // MainActivity.onResume() arms it instead, which is the moment Android allows it.
  }
}

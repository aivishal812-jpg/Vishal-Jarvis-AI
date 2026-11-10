package com.myraa.mobile;

import android.app.Application;

/** Installs local crash logging and restores Pro background features after the process starts. */
public final class JarvisApp extends Application {
  @Override public void onCreate() {
    super.onCreate();
    // Application start-up runs before a single pixel is drawn, so anything that throws here is an
    // app that closes with no message at all. Every step is therefore guarded individually.
    //
    // First line in the whole process: from here on every checkpoint is on disk, readable with a
    // file manager, so a launch that dies still leaves an exact record of where it stopped.
    try { BuildOn.install(this); } catch (Throwable ignored) { /* tracing is a courtesy */ }
    try { BuildOn.trim(); } catch (Throwable ignored) { /* nothing to do */ }
    BuildOn.trace("---- process started: " + BuildInfo.VERSION + " (versionCode from manifest) ----");
    try { BuildOn.describeDevice(this); } catch (Throwable ignored) { /* the trace is a courtesy */ }
    BuildOn.trace("Application.onCreate: begin");
    // Every start-up task below is best-effort: if one of them fails on a particular phone, the app
    // must still open. Throwable (not just RuntimeException) so a bad SQLite or linker error in one
    // helper cannot take the whole launch down before the screen exists.
    try { BeepMuter.restore(this); } catch (Throwable ignored) { /* nothing to do on release of a temporary object */ }
    BuildOn.trace("Application: beep state restored");
    try { ReminderScheduler.rescheduleAll(this); } catch (Throwable ignored) { /* nothing to do on release of a temporary object */ }
    BuildOn.trace("Application: reminders rescheduled");
    try { ProactiveScheduler.reschedule(this); } catch (Throwable ignored) { /* nothing to do on release of a temporary object */ }
    BuildOn.trace("Application: proactive jobs rescheduled");
    try { MemoryDb.importLegacy(this); } catch (Throwable ignored) { /* nothing to do on release of a temporary object */ }
    BuildOn.trace("Application.onCreate: done —— waiting for the window");
    // The call card is NOT started here. Android 12+ refuses to start a foreground service from
    // Application.onCreate when nobody is looking at the app, and the refusal is a plain
    // IllegalStateException: it used to escape this method and kill the process before the first
    // screen existed (the phone showed "keeps stopping").
    // MainActivity.onResume() arms it instead, which is the moment Android allows it.
  }
}

package com.myraa.mobile;

import android.app.Application;

/** Installs local crash logging and restores Pro background features after the process starts. */
public final class JarvisApp extends Application {
  @Override public void onCreate() {
    super.onCreate();
    CrashLog.install(this);
    try { BeepMuter.restore(this); } catch (RuntimeException ignored) { }
    try { ReminderScheduler.rescheduleAll(this); } catch (RuntimeException ignored) { }
    try { ProactiveScheduler.reschedule(this); } catch (RuntimeException ignored) { }
    try { MemoryDb.importLegacy(this); } catch (RuntimeException ignored) { }
  }
}

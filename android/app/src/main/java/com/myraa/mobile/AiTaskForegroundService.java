package com.myraa.mobile;

import android.app.Service;
import android.content.Intent;
import android.os.IBinder;

/** Keeps the process important while a user-started, visible text request is in flight. */
public final class AiTaskForegroundService extends Service {
  @Override public void onCreate() { super.onCreate(); startForeground(AiTaskNotifications.FOREGROUND_ID, AiTaskNotifications.working(this)); }
  @Override public int onStartCommand(Intent intent, int flags, int startId) { return START_NOT_STICKY; }
  @Override public IBinder onBind(Intent intent) { return null; }
}

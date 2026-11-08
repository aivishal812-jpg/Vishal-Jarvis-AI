package com.myraa.mobile;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

/** Notifications intentionally contain no prompt, reply, API key, or contact information. */
final class AiTaskNotifications {
  static final String CHANNEL = "ai_tasks";
  static final int FOREGROUND_ID = 410;
  private static void channel(Context context) {
    if (Build.VERSION.SDK_INT >= 26) {
      NotificationManager manager = (NotificationManager)context.getSystemService(Context.NOTIFICATION_SERVICE);
      if (manager != null) manager.createNotificationChannel(new NotificationChannel(CHANNEL, "Background AI tasks", NotificationManager.IMPORTANCE_DEFAULT));
    }
  }
  private static PendingIntent open(Context context, int request) {
    Intent intent = new Intent(context, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
    return PendingIntent.getActivity(context, request, intent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
  }
  static Notification working(Context context) {
    channel(context);
    return new Notification.Builder(context, CHANNEL).setSmallIcon(android.R.drawable.ic_popup_sync)
      .setContentTitle("Vishal Jarvis is working").setContentText("Your request can continue while the app is minimized.")
      .setContentIntent(open(context, FOREGROUND_ID)).setOngoing(true).setOnlyAlertOnce(true)
      .setVisibility(Notification.VISIBILITY_PRIVATE).build();
  }
  static void finished(Context context, AiTaskStore.Task task, boolean success) {
    try {
      channel(context);
      NotificationManager manager = (NotificationManager)context.getSystemService(Context.NOTIFICATION_SERVICE);
      if (manager == null) return;
      Notification notification = new Notification.Builder(context, CHANNEL).setSmallIcon(android.R.drawable.ic_dialog_info)
        .setContentTitle(success ? "Vishal Jarvis finished" : "Vishal Jarvis needs your attention")
        .setContentText(success ? "Tap to read your saved answer." : "Tap to check the task and connection settings.")
        .setContentIntent(open(context, task.jobId())).setAutoCancel(true).setVisibility(Notification.VISIBILITY_PRIVATE).build();
      manager.notify(task.jobId(), notification);
    } catch (RuntimeException ignored) { /* Missing notification permission must not discard a saved answer. */ }
  }
  private AiTaskNotifications() {}
}

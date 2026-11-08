package com.myraa.mobile;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import org.json.JSONObject;

/** Shows due reminders and restores reminders and the wake word after a reboot. */
public final class ReminderReceiver extends BroadcastReceiver {
  static final String CHANNEL = "reminders";
  @Override public void onReceive(Context context, Intent intent) {
    String action = intent == null ? null : intent.getAction();
    if (Intent.ACTION_BOOT_COMPLETED.equals(action) || Intent.ACTION_MY_PACKAGE_REPLACED.equals(action)) {
      ReminderScheduler.rescheduleAll(context);
      if (JarvisPrefs.wakeWord(context)) WakeWordService.start(context);
      return;
    }
    if (!ReminderScheduler.FIRE.equals(action)) return;
    String id = intent.getStringExtra("id");
    JSONObject item = id == null ? null : ReminderStore.find(context, id);
    if (item == null) return;
    show(context, item);
    String repeat = item.optString("repeat", "none");
    if ("daily".equals(repeat) || "weekly".equals(repeat)) {
      ReminderScheduler.advance(item, System.currentTimeMillis());
      ReminderStore.update(context, item);
      ReminderScheduler.schedule(context, item);
    } else ReminderStore.remove(context, id);
  }
  static void show(Context context, JSONObject item) {
    try {
      NotificationManager manager = context.getSystemService(NotificationManager.class);
      if (manager == null) return;
      NotificationChannel channel = new NotificationChannel(CHANNEL, "Jarvis reminders", NotificationManager.IMPORTANCE_HIGH);
      channel.enableVibration(true);
      manager.createNotificationChannel(channel);
      PendingIntent open = PendingIntent.getActivity(context, 7, new Intent(context, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
      Notification notification = new Notification.Builder(context, CHANNEL).setSmallIcon(android.R.drawable.ic_popup_reminder)
        .setContentTitle("Vishal Jarvis reminder").setContentText(item.optString("text"))
        .setStyle(new Notification.BigTextStyle().bigText(item.optString("text")))
        .setCategory(Notification.CATEGORY_REMINDER).setAutoCancel(true).setContentIntent(open).build();
      manager.notify(item.optString("id").hashCode(), notification);
    } catch (RuntimeException ignored) { }
  }
}

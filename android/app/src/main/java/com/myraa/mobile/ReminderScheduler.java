package com.myraa.mobile;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import org.json.JSONArray;
import org.json.JSONObject;

/** Schedules reminders with AlarmManager so they fire even when the app is closed. */
final class ReminderScheduler {
  static final String FIRE = "com.myraa.mobile.REMINDER";
  private static PendingIntent intent(Context context, String id) {
    Intent intent = new Intent(context, ReminderReceiver.class).setAction(FIRE).putExtra("id", id);
    return PendingIntent.getBroadcast(context, id.hashCode(), intent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
  }
  static boolean exactAllowed(Context context) {
    AlarmManager alarms = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
    return alarms != null && (Build.VERSION.SDK_INT < 31 || alarms.canScheduleExactAlarms());
  }
  static void schedule(Context context, JSONObject item) {
    AlarmManager alarms = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
    if (alarms == null) return;
    long at = item.optLong("at");
    PendingIntent pending = intent(context, item.optString("id"));
    try {
      if (exactAllowed(context)) alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending);
      else alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending);
    } catch (SecurityException e) { alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending); }
  }
  static void cancel(Context context, String id) {
    AlarmManager alarms = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
    if (alarms != null) alarms.cancel(intent(context, id));
  }
  /** After reboot or app update: re-arm future reminders, fire ones missed while the phone was off. */
  static void rescheduleAll(Context context) {
    JSONArray list = ReminderStore.all(context);
    long now = System.currentTimeMillis();
    for (int i = 0; i < list.length(); i++) {
      JSONObject item = list.optJSONObject(i); if (item == null) continue;
      if (item.optLong("at") < now - 60_000L) item = advance(item, now);
      if (item != null) { ReminderStore.update(context, item); schedule(context, item); }
    }
  }
  /** Moves a repeating reminder to its next future slot; one-off missed reminders fire one minute from now. */
  static JSONObject advance(JSONObject item, long now) {
    long at = item.optLong("at");
    String repeat = item.optString("repeat", "none");
    long step = "daily".equals(repeat) ? 86_400_000L : "weekly".equals(repeat) ? 7 * 86_400_000L : 0;
    try {
      if (step == 0) item.put("at", now + 60_000L);
      else { while (at <= now) at += step; item.put("at", at); }
    } catch (Exception ignored) { }
    return item;
  }
  private ReminderScheduler() {}
}

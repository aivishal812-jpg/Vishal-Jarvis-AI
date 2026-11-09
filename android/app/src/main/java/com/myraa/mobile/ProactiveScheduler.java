package com.myraa.mobile;

import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Proactive assistant: morning briefing, meeting reminders, notification digests, missed calls
 * and one-time-code notices.
 *
 * Everything here is opt-in per feature (Jarvis Pro settings) and runs from
 * {@link ProactiveReceiver} with AlarmManager, so nothing needs the app to be open. Nothing is
 * spoken aloud and no secret is ever shown: OTP bodies are replaced before they reach a
 * notification, and the briefing notification only says that it is ready.
 */
final class ProactiveScheduler {

  static final String ACTION_TICK = "com.myraa.mobile.PROACTIVE_TICK";
  static final String ACTION_MEETING = "com.myraa.mobile.MEETING_SOON";
  static final String CHANNEL = "proactive";
  private static final int REQUEST_TICK = 4011;
  private static final String KEY_LAST_ENABLED = "proactive.lastCheck";

  private static final String MORNING = "proactive.morning";
  private static final String MORNING_MINUTE = "proactive.morningMinute";
  private static final String MEETING = "proactive.meeting";
  private static final String MEETING_MINUTES = "proactive.meetingMinutes";
  private static final String DIGEST = "proactive.digest";
  private static final String DIGEST_MINUTES = "proactive.digestMinutes";
  private static final String OTP = "proactive.otp";
  private static final String MISSED = "proactive.missed";
  private static final String ROUTINE = "proactive.routinePlace";

  static boolean morningEnabled(Context c) { return prefs(c).getBoolean(MORNING, false); }
  static int morningMinute(Context c) { return Math.max(0, Math.min(24 * 60 - 1, prefs(c).getInt(MORNING_MINUTE, 8 * 60))); }
  static boolean meetingEnabled(Context c) { return prefs(c).getBoolean(MEETING, false); }
  static int meetingLead(Context c) { return Math.max(3, Math.min(120, prefs(c).getInt(MEETING_MINUTES, 10))); }
  static boolean digestEnabled(Context c) { return prefs(c).getBoolean(DIGEST, false); }
  static int digestEvery(Context c) { return Math.max(30, Math.min(480, prefs(c).getInt(DIGEST_MINUTES, 120))); }
  static boolean otpEnabled(Context c) { return prefs(c).getBoolean(OTP, true); }
  static boolean missedEnabled(Context c) { return prefs(c).getBoolean(MISSED, true); }
  static String routinePlace(Context c) { String value = prefs(c).getString(ROUTINE, ""); return value == null ? "" : value.trim(); }

  static void setMorning(Context c, boolean on) { prefs(c).edit().putBoolean(MORNING, on).apply(); reschedule(c); }
  static void setMeeting(Context c, boolean on) { prefs(c).edit().putBoolean(MEETING, on).apply(); reschedule(c); }
  static void setDigest(Context c, boolean on) { prefs(c).edit().putBoolean(DIGEST, on).apply(); reschedule(c); }
  static void setOtp(Context c, boolean on) { prefs(c).edit().putBoolean(OTP, on).apply(); }
  static void setMissed(Context c, boolean on) { prefs(c).edit().putBoolean(MISSED, on).apply(); }
  static void setMorningMinute(Context c, int minute) { prefs(c).edit().putInt(MORNING_MINUTE, minute).apply(); reschedule(c); }
  static void setMeetingLead(Context c, int minutes) { prefs(c).edit().putInt(MEETING_MINUTES, minutes).apply(); reschedule(c); }
  static void setDigestEvery(Context c, int minutes) { prefs(c).edit().putInt(DIGEST_MINUTES, minutes).apply(); reschedule(c); }
  static void setRoutinePlace(Context c, String place) {
    prefs(c).edit().putString(ROUTINE, place == null ? "" : place.trim()).apply();
    if (place != null && !place.trim().isEmpty()) RoutineGeofence.register(c, place.trim()); else RoutineGeofence.clear(c);
  }

  private static SharedPreferences prefs(Context context) { return JarvisPrefs.get(context); }

  /** Re-arms the wake-up alarms: a repeating morning slot and a periodic digest slot. */
  static void reschedule(Context context) {
    AlarmManager alarms = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
    if (alarms == null) return;
    PendingIntent tick = alarm(context);
    alarms.cancel(tick);
    long now = System.currentTimeMillis();
    long next = Long.MAX_VALUE;
    if (morningEnabled(context)) next = Math.min(next, nextMorning(context, now));
    if (digestEnabled(context)) next = Math.min(next, now + digestEvery(context) * 60_000L);
    if (meetingEnabled(context)) next = Math.min(next, nextMeetingScan(context, now));
    if (next == Long.MAX_VALUE) return;
    try {
      if (ReminderScheduler.exactAllowed(context)) alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next, tick);
      else alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next, tick);
    } catch (SecurityException denied) {
      alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next, tick);
    }
  }

  private static PendingIntent alarm(Context context) {
    Intent intent = new Intent(context, ProactiveReceiver.class).setAction(ACTION_TICK);
    return PendingIntent.getBroadcast(context, REQUEST_TICK, intent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
  }

  private static long nextMorning(Context context, long now) {
    java.util.Calendar calendar = java.util.Calendar.getInstance();
    calendar.setTimeInMillis(now);
    calendar.set(java.util.Calendar.HOUR_OF_DAY, morningMinute(context) / 60);
    calendar.set(java.util.Calendar.MINUTE, morningMinute(context) % 60);
    calendar.set(java.util.Calendar.SECOND, 0);
    calendar.set(java.util.Calendar.MILLISECOND, 0);
    if (calendar.getTimeInMillis() <= now) calendar.add(java.util.Calendar.DAY_OF_YEAR, 1);
    return calendar.getTimeInMillis();
  }

  private static long nextMeetingScan(Context context, long now) {
    // Scans every 5 minutes so a 10-minute lead time can actually be met.
    return now + 5 * 60_000L;
  }

  /** Arms a one-off alarm for the next event that starts inside the lead window. */
  static void scheduleMeetingCheck(Context context) {
    if (!meetingEnabled(context)) return;
    if (context.checkSelfPermission(android.Manifest.permission.READ_CALENDAR) != android.content.pm.PackageManager.PERMISSION_GRANTED) return;
    JSONArray events = CalendarReader.upcoming(context, 1, 25, true);
    long now = System.currentTimeMillis();
    long lead = meetingLead(context) * 60_000L;
    long soonest = Long.MAX_VALUE;
    String title = null;
    for (int i = 0; i < events.length(); i++) {
      JSONObject event = events.optJSONObject(i);
      if (event == null) continue;
      long begin = event.optLong("begin", 0);
      if (begin <= now || begin - lead > now) continue;
      if (begin < soonest) { soonest = begin; title = event.optString("title"); }
    }
    if (title == null) return;
    AlarmManager alarms = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
    if (alarms == null) return;
    Intent intent = new Intent(context, ProactiveReceiver.class).setAction(ACTION_MEETING)
      .putExtra("title", title).putExtra("begin", soonest);
    PendingIntent pending = PendingIntent.getBroadcast(context, (int) (soonest % 100000), intent,
      PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    long fireAt = soonest - lead;
    try {
      if (ReminderScheduler.exactAllowed(context)) alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, fireAt, pending);
      else alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, fireAt, pending);
    } catch (SecurityException denied) {
      alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, fireAt, pending);
    }
  }

  /** Works out what (if anything) is due now and posts it. Returns the message that was shown. */
  static String tick(Context context) {
    long now = System.currentTimeMillis();
    long last = prefs(context).getLong(KEY_LAST_ENABLED, 0);
    StringBuilder shown = new StringBuilder();
    try {
      if (morningEnabled(context)) {
        long target = nextMorning(context, now - 86_400_000L);
        if (target > last && target <= now) {
          String briefing = BackgroundActions.run(context, "daily_briefing", new JSONObject()).optString("message");
          if (!briefing.isEmpty()) { notify(context, 4201, "Good morning — your briefing", shorten(briefing, 400)); shown.append("briefing "); }
        }
      }
      if (digestEnabled(context)) {
        JSONArray important = JarvisNotificationListener.important(5);
        if (important.length() > 0) {
          StringBuilder text = new StringBuilder();
          for (int i = 0; i < important.length(); i++) {
            JSONObject item = important.getJSONObject(i);
            text.append("• ").append(item.optString("app")).append(": ").append(item.optString("title")).append('\n');
          }
          notify(context, 4202, "Important while you were away", shorten(text.toString().trim(), 500));
          shown.append("digest ");
        }
      }
      if (missedEnabled(context)) {
        JSONArray missed = JarvisNotificationListener.missedCalls(5);
        if (missed.length() > 0) {
          StringBuilder text = new StringBuilder();
          for (int i = 0; i < missed.length(); i++) {
            JSONObject item = missed.getJSONObject(i);
            text.append("• ").append(item.optString("title")).append('\n');
          }
          notify(context, 4203, missed.length() + " missed call(s) in the last 24 hours", shorten(text.toString().trim(), 500));
          shown.append("missed ");
        }
      }
      if (otpEnabled(context)) {
        JSONArray codes = JarvisNotificationListener.oneTimeCodes(3);
        if (codes.length() > 0) {
          // The code itself is never copied into this notification: the user opens the app.
          notify(context, 4204, "A one-time code arrived", "Open the app to use it. For safety the code is not shown here.");
          shown.append("otp ");
        }
      }
      prefs(context).edit().putLong(KEY_LAST_ENABLED, now).apply();
      reschedule(context);
    } catch (ignored Exception) { /* tolerated: a notification may be blocked by the system */ }
    return shown.toString().trim();
  }

  /** A geofence-style routine: a plain place-name reminder for the configured routine. */
  static void routineEntered(Context context, String place) {
    if (place == null || place.isEmpty()) return;
    if (JarvisPrefs.lockScreen(context)) return; // never start UI from the background
    notify(context, 4205, "You are near " + place, "Tap to open Vishal Jarvis and ask for your routine, or say what you want to do here.");
  }

  static void notify(Context context, int id, String title, String body) {
    try {
      NotificationManager manager = context.getSystemService(NotificationManager.class);
      if (manager == null) return;
      manager.createNotificationChannel(new NotificationChannel(CHANNEL, "Jarvis proactive updates", NotificationManager.IMPORTANCE_DEFAULT));
      PendingIntent open = PendingIntent.getActivity(context, id, new Intent(context, MainActivity.class)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
      Notification notification = new Notification.Builder(context, CHANNEL)
        .setSmallIcon(android.R.drawable.ic_popup_reminder)
        .setContentTitle(title).setContentText(body)
        .setStyle(new Notification.BigTextStyle().bigText(body))
        .setContentIntent(open).setAutoCancel(true).setVisibility(Notification.VISIBILITY_PRIVATE).build();
      manager.notify(id, notification);
    } catch (ignored RuntimeException) { /* tolerated: a notification may be blocked by the system */ }
  }

  private static String shorten(String text, int max) { return text == null ? "" : text.length() <= max ? text : text.substring(0, max) + "…"; }

  private static String stamp(long at) { return new SimpleDateFormat("h:mm a", Locale.US).format(new Date(at)); }

  private ProactiveScheduler() { }
}

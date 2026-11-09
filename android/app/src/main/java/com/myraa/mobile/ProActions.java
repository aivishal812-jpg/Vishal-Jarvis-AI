package com.myraa.mobile;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.media.AudioAttributes;
import android.media.AudioManager;
import android.media.Ringtone;
import android.media.RingtoneManager;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import org.json.JSONArray;
import org.json.JSONObject;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Jarvis Pro device actions: reminders, notes, notifications, calendar, weather, daily briefing, find-my-phone. */
final class ProActions {
  interface Finisher { void finish(String id, String status, String message, JSONObject data); }
  static final int CALENDAR_REQUEST = 94;
  /** WRITE_CALENDAR cannot be granted by the READ_CALENDAR prompt, so it has its own request code. */
  static final int CALENDAR_WRITE_REQUEST = 98;
  static final String[] ACTIONS = { "set_reminder", "list_reminders", "cancel_reminder", "save_note", "list_notes", "delete_note",
    "read_notifications", "clear_notifications", "read_calendar", "delete_calendar_event", "get_weather", "daily_briefing", "ring_phone", "open_jarvis_settings" };
  private final Activity activity;
  private final Finisher done;
  private final ExecutorService network = Executors.newSingleThreadExecutor();
  private final Handler main = new Handler(Looper.getMainLooper());
  private Ringtone ringing;
  private volatile String pendingDeleteId;
  private volatile JSONObject pendingDeleteArgs;

  ProActions(Activity activity, Finisher done) { this.activity = activity; this.done = done; }

  static boolean supports(String action) { for (String a : ACTIONS) if (a.equals(action)) return true; return false; }

  /**
   * The same actions, executed with no Activity through {@link ActionRegistry}, so reminders,
   * notes, notifications, calendar reads, weather and the briefing work while the screen is
   * locked or the app is in the background. Anything ProActions can only do with a window
   * (ring_phone, settings pages, deleting calendar events) is left to the window path.
   */
  static boolean backgroundSafe(String action) {
    if (action == null) return false;
    for (String a : BACKGROUND_SAFE) if (a.equals(action)) return true;
    return false;
  }

  private static final String[] BACKGROUND_SAFE = { "set_reminder", "list_reminders", "cancel_reminder",
    "save_note", "list_notes", "delete_note", "read_notifications", "clear_notifications",
    "read_calendar", "get_weather", "daily_briefing" };

  void handle(String id, String action, JSONObject args) throws Exception {
    switch (action) {
      case "set_reminder": setReminder(id, args); break;
      case "list_reminders": listReminders(id); break;
      case "cancel_reminder": cancelReminder(id, args); break;
      case "save_note": { JSONObject note = NoteStore.add(activity, args.optString("text")); done.finish(id, "saved", "Noted: \"" + note.optString("text") + "\"", note); break; }
      case "list_notes": listNotes(id); break;
      case "delete_note": {
        boolean removed = NoteStore.remove(activity, args.optString("noteId"));
        done.finish(id, removed ? "deleted" : "not_found", removed ? "Note deleted." : "No note with that id. Ask me to list notes first.", null); break;
      }
      case "read_notifications": readNotifications(id, args); break;
      case "clear_notifications": {
        if (!JarvisNotificationListener.enabled(activity)) { notificationAccess(id); break; }
        int n = JarvisNotificationListener.dismissAll();
        done.finish(id, n < 0 ? "failed" : "cleared", n < 0 ? "Android did not let me clear notifications right now." : "Cleared the notifications that could be dismissed.", null); break;
      }
      case "read_calendar": readCalendar(id, args); break;
      case "what_did_i_say": case "missed_calls": case "otp_summary": case "screen_info": {
        JSONObject direct = ActionRegistry.run(activity, action, args);
        done.finish(id, direct.optString("status", "failed"), direct.optString("message", ""), direct);
        break;
      }
      case "delete_calendar_event": deleteCalendarEvent(id, args); break;
      case "get_weather": weather(id, args.optString("city")); break;
      case "daily_briefing": briefing(id, args.optString("city")); break;
      case "ring_phone": ring(id); break;
      case "open_jarvis_settings": activity.startActivity(new Intent(activity, ProSettingsActivity.class)); done.finish(id, "opened", "Opened Jarvis Pro settings.", null); break;
      default: done.finish(id, "unsupported", "That Pro action is unavailable.", null);
    }
  }

  // ------------------------------------------------------------ reminders
  private void setReminder(String id, JSONObject args) throws Exception {
    String text = args.optString("text", "").trim();
    if (text.isEmpty() || text.length() > 300) { done.finish(id, "invalid", "What should I remind you about? Keep it under 300 characters.", null); return; }
    long now = System.currentTimeMillis();
    long at = args.optLong("atTime", 0);
    int minutes = args.optInt("inMinutes", 0);
    if (at <= 0 && minutes > 0) at = now + minutes * 60_000L;
    if (at <= now + 5_000L) { done.finish(id, "invalid", "Give a future time for the reminder (for example in 20 minutes, or tomorrow at 9 am).", null); return; }
    if (at > now + 366L * 86_400_000L) { done.finish(id, "invalid", "Reminders can be set up to one year ahead.", null); return; }
    String repeat = args.optString("repeat", "none");
    if (!"daily".equals(repeat) && !"weekly".equals(repeat)) repeat = "none";
    JSONObject item = ReminderStore.add(activity, text, at, repeat);
    ReminderScheduler.schedule(activity, item);
    String when = new SimpleDateFormat("EEEE d MMMM, h:mm a", Locale.US).format(new Date(at));
    StringBuilder message = new StringBuilder("Reminder set for ").append(when);
    if (!"none".equals(repeat)) message.append(", repeating ").append(repeat);
    message.append(": ").append(text).append('.');
    if (Build.VERSION.SDK_INT >= 33 && activity.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
      message.append(" Allow notifications for Vishal Jarvis, otherwise the reminder cannot pop up.");
    else if (!ReminderScheduler.exactAllowed(activity)) message.append(" Android may deliver it a few minutes late; allow \"Alarms & reminders\" in Jarvis Pro settings for exact timing.");
    done.finish(id, "reminder_set", message.toString(), item);
  }
  private void listReminders(String id) throws Exception {
    JSONArray list = ReminderStore.all(activity);
    if (list.length() == 0) { done.finish(id, "info", "You have no reminders.", new JSONObject().put("reminders", list)); return; }
    StringBuilder text = new StringBuilder("Your reminders: ");
    SimpleDateFormat f = new SimpleDateFormat("EEE d MMM h:mm a", Locale.US);
    for (int i = 0; i < list.length() && i < 15; i++) {
      JSONObject r = list.getJSONObject(i);
      text.append(i + 1).append(") ").append(f.format(new Date(r.optLong("at")))).append(" — ").append(r.optString("text"))
        .append(" [id ").append(r.optString("id")).append("]").append("none".equals(r.optString("repeat")) ? "" : " (" + r.optString("repeat") + ")").append("; ");
    }
    done.finish(id, "info", text.toString().trim(), new JSONObject().put("reminders", list));
  }
  private void cancelReminder(String id, JSONObject args) throws Exception {
    String key = args.optString("reminderId", "").trim();
    String match = args.optString("text", "").trim().toLowerCase(Locale.ROOT);
    JSONArray list = ReminderStore.all(activity);
    for (int i = 0; i < list.length(); i++) {
      JSONObject r = list.getJSONObject(i);
      if ((!key.isEmpty() && r.optString("id").equals(key)) || (key.isEmpty() && !match.isEmpty() && r.optString("text").toLowerCase(Locale.ROOT).contains(match))) {
        ReminderScheduler.cancel(activity, r.optString("id")); ReminderStore.remove(activity, r.optString("id"));
        done.finish(id, "deleted", "Cancelled the reminder: " + r.optString("text"), null); return;
      }
    }
    done.finish(id, "not_found", "I could not find that reminder. Ask me to list reminders first.", null);
  }
  private void listNotes(String id) throws Exception {
    JSONArray notes = NoteStore.all(activity);
    if (notes.length() == 0) { done.finish(id, "info", "You have no notes yet.", new JSONObject().put("notes", notes)); return; }
    StringBuilder text = new StringBuilder("Your latest notes: ");
    for (int i = notes.length() - 1, n = 0; i >= 0 && n < 15; i--, n++) {
      JSONObject note = notes.getJSONObject(i);
      text.append("• ").append(note.optString("text")).append(" [id ").append(note.optString("id")).append("] ");
    }
    done.finish(id, "info", text.toString().trim(), new JSONObject().put("notes", notes));
  }

  // ------------------------------------------------------------ notifications & calendar
  private void notificationAccess(String id) {
    try { activity.startActivity(new Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS")); } catch (RuntimeException ignored) { }
    done.finish(id, "permission_required", "Turn on \"Vishal Jarvis\" in the Notification access screen I just opened, then ask again.", null);
  }
  private void readNotifications(String id, JSONObject args) throws Exception {
    if (!JarvisNotificationListener.enabled(activity)) { notificationAccess(id); return; }
    int limit = Math.max(1, Math.min(20, args.optInt("limit", 8)));
    JSONArray items = JarvisNotificationListener.recent(limit, args.optString("app"));
    if (items.length() == 0) { done.finish(id, "info", args.optString("app").isEmpty() ? "No new notifications." : "No recent notifications from " + args.optString("app") + ".", new JSONObject().put("notifications", items)); return; }
    StringBuilder text = new StringBuilder();
    SimpleDateFormat f = new SimpleDateFormat("h:mm a", Locale.US);
    for (int i = 0; i < items.length(); i++) {
      JSONObject n = items.getJSONObject(i);
      text.append(n.optString("app")).append(" (").append(f.format(new Date(n.optLong("at")))).append("): ")
        .append(n.optString("title")).append(n.optString("text").isEmpty() ? "" : " — " + n.optString("text")).append('\n');
    }
    done.finish(id, "info", text.toString().trim(), new JSONObject().put("notifications", items));
  }
  private void readCalendar(String id, JSONObject args) throws Exception {
    if (activity.checkSelfPermission(Manifest.permission.READ_CALENDAR) != PackageManager.PERMISSION_GRANTED) {
      activity.requestPermissions(new String[]{ Manifest.permission.READ_CALENDAR }, CALENDAR_REQUEST);
      done.finish(id, "permission_required", "Allow Calendar access in the prompt, then ask me again.", null); return;
    }
    int days = Math.max(1, Math.min(31, args.optInt("days", 1)));
    JSONArray events = CalendarReader.upcoming(activity, days, 25, true);
    done.finish(id, "info", events.length() == 0 ? "No events in the next " + days + (days == 1 ? " day." : " days.") : describeEvents(events), new JSONObject().put("events", events));
  }

  // ------------------------------------------------------------ deleting calendar events

  /**
   * Deletes one event, one instance of a repeating event, or every upcoming event.
   *
   * Deleting is never silent: the user is shown exactly which events will go and must tap Delete.
   * The model can only trigger this; it cannot decide on its own that something should be removed.
   */
  private void deleteCalendarEvent(String id, JSONObject args) {
    if (CalendarReader.needsWrite(activity)) {
      pendingDeleteId = id; pendingDeleteArgs = args;
      activity.requestPermissions(new String[]{ Manifest.permission.WRITE_CALENDAR }, CALENDAR_WRITE_REQUEST);
      done.finish(id, "permission_required", "Allow Calendar write access in the prompt, then ask me again. Nothing was deleted.", null);
      return;
    }
    if (activity.checkSelfPermission(Manifest.permission.READ_CALENDAR) != PackageManager.PERMISSION_GRANTED) {
      activity.requestPermissions(new String[]{ Manifest.permission.READ_CALENDAR }, CALENDAR_REQUEST);
      done.finish(id, "permission_required", "Allow Calendar access in the prompt, then ask me again. Nothing was deleted.", null);
      return;
    }
    try { chooseEvents(id, args); }
    catch (Exception failure) { done.finish(id, "failed", "I could not read your calendar. Nothing was deleted.", null); }
  }

  /** Picks the events the user is most likely to mean, then always asks before removing anything. */
  private void chooseEvents(String id, JSONObject args) throws Exception {
    int days = Math.max(1, Math.min(31, args.optInt("days", 7)));
    boolean all = args.optBoolean("all", false);
    String title = args.optString("title", "").trim().toLowerCase(Locale.ROOT);
    long eventId = args.optLong("eventId", 0);
    List<CalendarReader.Target> targets = CalendarReader.upcomingTargets(activity, days, 60);
    if (targets.isEmpty()) {
      done.finish(id, "not_found", "There are no events in the next " + days + (days == 1 ? " day" : " days") + ". Nothing was deleted.", null);
      return;
    }
    if (all) { confirmDelete(id, targets, args, true); return; }

    List<CalendarReader.Target> matched = new ArrayList<>();
    for (CalendarReader.Target target : targets) {
      if (eventId > 0 ? target.id == eventId : !title.isEmpty() && target.title.toLowerCase(Locale.ROOT).contains(title)) matched.add(target);
    }
    if (matched.isEmpty()) {
      if (!title.isEmpty()) {
        done.finish(id, "not_found", "No upcoming event matches \"" + title + "\". Nothing was deleted."
          + (targets.size() > 0 ? " Upcoming: " + summarise(targets, 3) + "." : ""), null);
        return;
      }
      done.finish(id, "needs_choice", "Which event should I delete?", eventsJson(id, targets));
      return;
    }
    if (matched.size() > 1) { done.finish(id, "needs_choice", "Several events match \"" + title + "\": " + summarise(matched, 5) + ". Which one?", eventsJson(id, matched)); return; }
    if (targets.size() > 1 && title.isEmpty() && eventId <= 0) { done.finish(id, "needs_choice", "Which event should I delete?", eventsJson(id, targets)); return; }
    confirmDelete(id, matched, args, false);
  }

  private JSONObject eventsJson(String id, List<CalendarReader.Target> targets) throws Exception {
    JSONArray list = new JSONArray();
    for (int i = 0; i < targets.size() && i < 10; i++) {
      CalendarReader.Target target = targets.get(i);
      list.put(new JSONObject().put("eventId", target.id).put("title", target.title)
        .put("when", target.when()).put("instances", target.instances));
    }
    return new JSONObject().put("events", list).put("action", "delete_calendar_event");
  }

  /** json() that never throws, for use inside listeners and background lambdas. */
  private static JSONObject obj(Object... values) {
    JSONObject value = new JSONObject();
    try { for (int i = 0; i + 1 < values.length; i += 2) value.put(String.valueOf(values[i]), values[i + 1]); }
    catch (Exception ignored) { }
    return value;
  }

  private static String summarise(List<CalendarReader.Target> targets, int limit) {
    StringBuilder text = new StringBuilder();
    for (int i = 0; i < targets.size() && i < limit; i++) {
      if (i > 0) text.append(", ");
      text.append(targets.get(i).title).append(" (").append(targets.get(i).when()).append(")");
    }
    if (targets.size() > limit) text.append(" and ").append(targets.size() - limit).append(" more");
    return text.toString();
  }

  /** Shows the exact list in an Android dialog; only the user's tap can remove anything. */
  private void confirmDelete(String id, List<CalendarReader.Target> targets, JSONObject args, boolean all) {
    StringBuilder body = new StringBuilder(all
      ? "Delete all " + targets.size() + " upcoming event(s)?\n\n"
      : "Delete this event?\n\n");
    for (int i = 0; i < targets.size() && i < 12; i++) {
      CalendarReader.Target target = targets.get(i);
      body.append("• ").append(target.title).append(" — ").append(target.when());
      if (target.instances > 1) body.append(" (repeats, ").append(target.instances).append(" times)");
      body.append('\n');
    }
    if (targets.size() > 12) body.append("… and ").append(targets.size() - 12).append(" more\n");
    body.append("\nThis cannot be undone.");
    final List<Long> ids = new ArrayList<>();
    for (CalendarReader.Target target : targets) ids.add(target.id);
    new android.app.AlertDialog.Builder(activity)
      .setTitle("Delete from calendar?")
      .setMessage(body.toString().trim())
      .setPositiveButton("Delete", (dialog, which) -> performDelete(id, ids, all))
      .setNegativeButton("Keep", (dialog, which) ->
        done.finish(id, "cancelled", "Nothing was deleted.", null))
      .setOnCancelListener(dialog ->
        done.finish(id, "cancelled", "Nothing was deleted.", null))
      .show();
  }

  private void performDelete(String id, List<Long> ids, boolean all) {
    network.execute(() -> {
      try {
        int removed = CalendarReader.deleteEvents(activity, ids);
        main.post(() -> {
          if (removed == 0) done.finish(id, "failed", "Android refused to delete " + (all ? "those events" : "that event") + ". It may belong to a read-only calendar (holidays, birthdays or a shared account), or you may be offline. Nothing was deleted.", null);
          else done.finish(id, "deleted", all ? "Deleted " + removed + " upcoming event(s) from your calendar." : "Deleted the event from your calendar.", obj("deleted", removed));
        });
      } catch (SecurityException denied) {
        main.post(() -> done.finish(id, "permission_required", "Calendar write access is missing, so nothing was deleted. Allow it in Android app settings and ask again.", null));
      } catch (RuntimeException broken) {
        main.post(() -> done.finish(id, "failed", "The calendar could not be changed. Nothing was deleted.", null));
      }
    });
  }

  /** Called from onRequestPermissionsResult after the user answers the calendar prompts. */
  void calendarPermissionResult() {
    String id = pendingDeleteId; JSONObject args = pendingDeleteArgs;
    pendingDeleteId = null; pendingDeleteArgs = null;
    if (id == null || args == null) return;
    if (CalendarReader.needsWrite(activity)
        || activity.checkSelfPermission(Manifest.permission.READ_CALENDAR) != PackageManager.PERMISSION_GRANTED) {
      done.finish(id, "permission_required", "Calendar access was denied, so nothing was deleted.", null);
      return;
    }
    try { chooseEvents(id, args); }
    catch (Exception failure) { done.finish(id, "failed", "I could not read your calendar. Nothing was deleted.", null); }
  }

  private static String describeEvents(JSONArray events) {
    StringBuilder text = new StringBuilder(events.length() + " event(s): ");
    for (int i = 0; i < events.length(); i++) {
      JSONObject e = events.optJSONObject(i); if (e == null) continue;
      text.append(e.optString("when")).append(" — ").append(e.optString("title")).append(e.optString("location").isEmpty() ? "" : " @ " + e.optString("location")).append("; ");
    }
    return text.toString().trim();
  }

  // ------------------------------------------------------------ weather & briefing
  private String city(String given) { String c = given == null ? "" : given.trim(); return c.isEmpty() ? JarvisPrefs.homeCity(activity) : c; }
  private void weather(String id, String given) {
    final String place = city(given);
    if (place.isEmpty()) { done.finish(id, "invalid", "Which city? Tell me the city, or save your home city in Jarvis Pro settings.", null); return; }
    network.execute(() -> {
      try { JSONObject w = WeatherClient.forecast(place); main.post(() -> done.finish(id, "info", w.optString("summary"), w)); }
      catch (IllegalArgumentException e) { main.post(() -> done.finish(id, "not_found", e.getMessage(), null)); }
      catch (Exception e) { main.post(() -> done.finish(id, "failed", "The weather service could not be reached. Check the internet connection.", null)); }
    });
  }
  private void briefing(String id, String given) {
    final String place = city(given);
    final boolean calendar = activity.checkSelfPermission(Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED;
    network.execute(() -> {
      JSONObject data = new JSONObject();
      StringBuilder text = new StringBuilder();
      try {
        String now = new SimpleDateFormat("EEEE, d MMMM yyyy, h:mm a", Locale.US).format(new Date());
        data.put("time", now); text.append("It is ").append(now).append(". ");
        android.content.Intent battery = activity.registerReceiver(null, new android.content.IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        if (battery != null) {
          int level = battery.getIntExtra(BatteryManager.EXTRA_LEVEL, -1), scale = battery.getIntExtra(BatteryManager.EXTRA_SCALE, -1);
          if (level >= 0 && scale > 0) { int p = Math.round(level * 100f / scale); data.put("battery", p); text.append("Battery ").append(p).append("%. "); }
        }
        if (!place.isEmpty()) {
          try { JSONObject w = WeatherClient.forecast(place); data.put("weather", w); text.append("Weather: ").append(w.optString("summary")).append(' '); }
          catch (Exception e) { text.append("Weather unavailable right now. "); }
        } else text.append("(Save your home city in Jarvis Pro settings to include weather.) ");
        if (calendar) {
          JSONArray events = CalendarReader.upcoming(activity, 1, 10); data.put("events", events);
          text.append(events.length() == 0 ? "No calendar events today. " : "Today: " + describeEvents(events) + " ");
        } else text.append("(Allow Calendar to include today's events.) ");
        JSONArray reminders = ReminderStore.all(activity); long end = System.currentTimeMillis() + 86_400_000L; int due = 0;
        StringBuilder r = new StringBuilder();
        for (int i = 0; i < reminders.length(); i++) { JSONObject item = reminders.getJSONObject(i); if (item.optLong("at") <= end) { due++; r.append(item.optString("text")).append("; "); } }
        data.put("remindersNext24h", due);
        if (due > 0) text.append("Reminders in the next 24 hours: ").append(r).append(' ');
        if (JarvisNotificationListener.enabled(activity)) {
          JSONArray notes = JarvisNotificationListener.recent(5, null); data.put("notifications", notes);
          if (notes.length() > 0) text.append(notes.length()).append(" recent notification(s), latest from ").append(notes.getJSONObject(0).optString("app")).append('.');
        }
        main.post(() -> done.finish(id, "info", text.toString().trim(), data));
      } catch (Exception e) { main.post(() -> done.finish(id, "failed", "I could not put the briefing together.", null)); }
    });
  }

  // ------------------------------------------------------------ find my phone
  private void ring(String id) {
    try {
      if (ringing != null && ringing.isPlaying()) { ringing.stop(); ringing = null; done.finish(id, "stopped", "Stopped ringing.", null); return; }
      AudioManager audio = (AudioManager) activity.getSystemService(android.content.Context.AUDIO_SERVICE);
      if (audio != null) audio.setStreamVolume(AudioManager.STREAM_ALARM, audio.getStreamMaxVolume(AudioManager.STREAM_ALARM), 0);
      android.net.Uri tone = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM);
      if (tone == null) tone = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE);
      ringing = RingtoneManager.getRingtone(activity, tone);
      if (ringing == null) { done.finish(id, "unavailable", "This phone has no alarm sound to play.", null); return; }
      ringing.setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build());
      ringing.play();
      final Ringtone mine = ringing;
      main.postDelayed(() -> { if (mine.isPlaying()) mine.stop(); }, 20_000);
      done.finish(id, "ringing", "Ringing loudly for 20 seconds so you can find the phone. Say ring phone again to stop.", null);
    } catch (RuntimeException e) { done.finish(id, "failed", "Android could not play the alarm sound.", null); }
  }

  void close() { network.shutdownNow(); if (ringing != null) { try { ringing.stop(); } catch (RuntimeException ignored) { } } }
}

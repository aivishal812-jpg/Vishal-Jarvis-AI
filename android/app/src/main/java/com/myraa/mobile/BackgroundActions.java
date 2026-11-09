package com.myraa.mobile;

import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.hardware.camera2.CameraManager;
import android.media.AudioManager;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.BatteryManager;
import android.os.Build;
import android.os.StatFs;
import android.view.KeyEvent;
import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Device actions that are safe to perform with no Activity, no dialog and no visible UI.
 *
 * These run inside {@link AiTaskJobService} (screen off, app in the background, after the
 * process was killed) and from the agent when the app is not in the foreground. Anything
 * that needs a chooser, a permission prompt, a confirmation dialog or an app switch stays
 * out of this list: those still answer permission_required so nothing appears to happen.
 *
 * Every method here is synchronous and must be called from a worker thread. Nothing is
 * ever reported as done unless Android accepted it.
 */
final class BackgroundActions {

  /** Actions this class can execute without UI. Keep in sync with run(). */
  private static final String[] SUPPORTED = {
    "set_reminder", "list_reminders", "cancel_reminder",
    "save_note", "list_notes", "delete_note",
    "get_weather", "daily_briefing", "device_status",
    "set_flashlight", "set_volume", "media_control",
    "read_notifications", "clear_notifications", "read_calendar",
    "list_apps", "sim_info"
  };

  static boolean supports(String action) {
    if (action == null) return false;
    for (String candidate : SUPPORTED) if (candidate.equals(action)) return true;
    return false;
  }

  /** Executes one background-safe action and returns a truthful result object. */
  static JSONObject run(Context context, String action, JSONObject args) {
    try {
      if (action == null) return status("unsupported", "That action is unavailable.");
      if (args == null) args = new JSONObject();
      switch (action) {
        case "set_reminder": return setReminder(context, args);
        case "list_reminders": return listReminders(context);
        case "cancel_reminder": return cancelReminder(context, args);
        case "save_note": return saveNote(context, args);
        case "list_notes": return listNotes(context);
        case "delete_note": return deleteNote(context, args);
        case "get_weather": return weather(context, args.optString("city"));
        case "daily_briefing": return briefing(context, args.optString("city"));
        case "device_status": return deviceStatus(context);
        case "set_flashlight": return flashlight(context, args.optBoolean("enabled", false));
        case "set_volume": return volume(context, args);
        case "media_control": return media(context, args.optString("command"));
        case "read_notifications": return notifications(context, args);
        case "clear_notifications": return clearNotifications(context);
        case "read_calendar": return calendar(context, args);
        case "list_apps": return listApps(context);
        case "sim_info": return status("unavailable", "SIM details need the app window. Open Vishal Jarvis and ask again.");
        default: return status("unsupported", "That action needs the app window.");
      }
    } catch (SecurityException denied) {
      return status("permission_required", "Android denied the permission for that action in the background, so nothing was changed.");
    } catch (Exception failure) {
      return status("failed", "I could not complete that in the background. Nothing was changed.");
    }
  }

  // ---------------------------------------------------------------- reminders

  private static JSONObject setReminder(Context context, JSONObject args) throws Exception {
    String text = args.optString("text", "").trim();
    if (text.isEmpty() || text.length() > 300) return status("invalid", "What should I remind you about? Keep it under 300 characters.");
    long now = System.currentTimeMillis();
    long at = args.optLong("atTime", 0);
    int minutes = args.optInt("inMinutes", 0);
    if (at <= 0 && minutes > 0) at = now + minutes * 60_000L;
    if (at <= now + 5_000L) return status("invalid", "Give a future time for the reminder, for example in 20 minutes or tomorrow at 9 am.");
    if (at > now + 366L * 86_400_000L) return status("invalid", "Reminders can be set up to one year ahead.");
    String repeat = args.optString("repeat", "none");
    if (!"daily".equals(repeat) && !"weekly".equals(repeat)) repeat = "none";
    JSONObject item = ReminderStore.add(context, text, at, repeat);
    ReminderScheduler.schedule(context, item);
    String when = new SimpleDateFormat("EEEE d MMMM, h:mm a", Locale.US).format(new Date(at));
    StringBuilder message = new StringBuilder("Reminder set for ").append(when);
    if (!"none".equals(repeat)) message.append(", repeating ").append(repeat);
    message.append(": ").append(text).append('.');
    if (Build.VERSION.SDK_INT >= 33 && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
      message.append(" Allow notifications for Vishal Jarvis, otherwise the reminder cannot pop up.");
    else if (!ReminderScheduler.exactAllowed(context)) message.append(" Android may deliver it a few minutes late; allow \"Alarms & reminders\" in Jarvis Pro settings for exact timing.");
    return data("reminder_set", message.toString(), item);
  }

  private static JSONObject listReminders(Context context) throws Exception {
    JSONArray list = ReminderStore.all(context);
    if (list.length() == 0) return data("info", "You have no reminders.", obj("reminders", list));
    StringBuilder text = new StringBuilder("Your reminders: ");
    SimpleDateFormat format = new SimpleDateFormat("EEE d MMM h:mm a", Locale.US);
    for (int i = 0; i < list.length() && i < 15; i++) {
      JSONObject item = list.getJSONObject(i);
      text.append(i + 1).append(") ").append(format.format(new Date(item.optLong("at")))).append(" — ").append(item.optString("text"))
        .append(" [id ").append(item.optString("id")).append("]; ");
    }
    return data("info", text.toString().trim(), obj("reminders", list));
  }

  private static JSONObject cancelReminder(Context context, JSONObject args) throws Exception {
    String key = args.optString("reminderId", "").trim();
    String match = args.optString("text", "").trim().toLowerCase(Locale.ROOT);
    JSONArray list = ReminderStore.all(context);
    for (int i = 0; i < list.length(); i++) {
      JSONObject item = list.getJSONObject(i);
      if ((!key.isEmpty() && item.optString("id").equals(key))
          || (key.isEmpty() && !match.isEmpty() && item.optString("text").toLowerCase(Locale.ROOT).contains(match))) {
        ReminderScheduler.cancel(context, item.optString("id"));
        ReminderStore.remove(context, item.optString("id"));
        return status("deleted", "Cancelled the reminder: " + item.optString("text"));
      }
    }
    return status("not_found", "I could not find that reminder. Ask me to list reminders first.");
  }

  // ---------------------------------------------------------------- notes

  private static JSONObject saveNote(Context context, JSONObject args) throws Exception {
    JSONObject note = NoteStore.add(context, args.optString("text"));
    return data("saved", "Noted: \"" + note.optString("text") + "\"", note);
  }

  private static JSONObject listNotes(Context context) throws Exception {
    JSONArray notes = NoteStore.all(context);
    if (notes.length() == 0) return data("info", "You have no notes yet.", obj("notes", notes));
    StringBuilder text = new StringBuilder("Your latest notes: ");
    for (int i = notes.length() - 1, n = 0; i >= 0 && n < 15; i--, n++) {
      JSONObject note = notes.getJSONObject(i);
      text.append("• ").append(note.optString("text")).append(" [id ").append(note.optString("id")).append("] ");
    }
    return data("info", text.toString().trim(), obj("notes", notes));
  }

  private static JSONObject deleteNote(Context context, JSONObject args) {
    boolean removed = NoteStore.remove(context, args.optString("noteId"));
    return status(removed ? "deleted" : "not_found", removed ? "Note deleted." : "No note with that id. Ask me to list notes first.");
  }

  // ---------------------------------------------------------------- weather / briefing

  private static JSONObject weather(Context context, String given) throws Exception {
    String place = given == null ? "" : given.trim();
    if (place.isEmpty()) place = JarvisPrefs.homeCity(context);
    if (place.isEmpty()) return status("invalid", "Which city? Tell me the city, or save your home city in Jarvis Pro settings.");
    JSONObject forecast = WeatherClient.forecast(place);
    return data("info", forecast.optString("summary"), forecast);
  }

  private static JSONObject briefing(Context context, String given) throws Exception {
    JSONObject result = new JSONObject();
    StringBuilder text = new StringBuilder();
    String now = new SimpleDateFormat("EEEE, d MMMM yyyy, h:mm a", Locale.US).format(new Date());
    result.put("time", now);
    text.append("It is ").append(now).append(". ");
    JSONObject device = deviceStatus(context);
    JSONObject deviceData = device.optJSONObject("data");
    if (deviceData != null) {
      int battery = deviceData.optInt("batteryPercent", -1);
      if (battery >= 0) { result.put("battery", battery); text.append("Battery ").append(battery).append("%. "); }
    }
    String place = given == null ? "" : given.trim();
    if (place.isEmpty()) place = JarvisPrefs.homeCity(context);
    if (!place.isEmpty()) {
      try { JSONObject forecast = WeatherClient.forecast(place); result.put("weather", forecast); text.append("Weather: ").append(forecast.optString("summary")).append(' '); }
      catch (Exception unavailable) { text.append("Weather unavailable right now. "); }
    } else text.append("(Save your home city in Jarvis Pro settings to include weather.) ");
    if (context.checkSelfPermission(Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED) {
      JSONArray events = CalendarReader.upcoming(context, 1, 10);
      result.put("events", events);
      if (events.length() == 0) text.append("No calendar events today. ");
      else {
        text.append("Today: ");
        for (int i = 0; i < events.length(); i++) {
          JSONObject event = events.optJSONObject(i);
          if (event != null) text.append(event.optString("when")).append(" — ").append(event.optString("title")).append("; ");
        }
      }
    } else text.append("(Allow Calendar to include today's events.) ");
    JSONArray reminders = ReminderStore.all(context);
    long end = System.currentTimeMillis() + 86_400_000L;
    int due = 0;
    StringBuilder upcoming = new StringBuilder();
    for (int i = 0; i < reminders.length(); i++) {
      JSONObject item = reminders.optJSONObject(i);
      if (item != null && item.optLong("at") <= end) { due++; upcoming.append(item.optString("text")).append("; "); }
    }
    result.put("remindersNext24h", due);
    if (due > 0) text.append("Reminders in the next 24 hours: ").append(upcoming);
    if (JarvisNotificationListener.enabled(context)) {
      JSONArray notes = JarvisNotificationListener.recent(5, null);
      result.put("notifications", notes);
      if (notes.length() > 0) text.append(" ").append(notes.length()).append(" recent notification(s), latest from ").append(notes.optJSONObject(0).optString("app")).append('.');
    }
    return data("info", text.toString().trim(), result);
  }

  // ---------------------------------------------------------------- device

  private static JSONObject deviceStatus(Context context) throws Exception {
    JSONObject data = new JSONObject();
    Intent battery = context.registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
    int level = battery == null ? -1 : battery.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
    int scale = battery == null ? -1 : battery.getIntExtra(BatteryManager.EXTRA_SCALE, -1);
    int state = battery == null ? 0 : battery.getIntExtra(BatteryManager.EXTRA_STATUS, BatteryManager.BATTERY_STATUS_UNKNOWN);
    int percent = level >= 0 && scale > 0 ? (int) Math.round(level * 100.0 / scale) : -1;
    data.put("batteryPercent", percent);
    data.put("charging", state == BatteryManager.BATTERY_STATUS_CHARGING || state == BatteryManager.BATTERY_STATUS_FULL);
    ConnectivityManager manager = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
    Network active = manager == null ? null : manager.getActiveNetwork();
    NetworkCapabilities capabilities = active == null ? null : manager.getNetworkCapabilities(active);
    String network = "offline";
    if (capabilities != null) {
      if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) network = "wifi";
      else if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) network = "mobile data";
      else if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) network = "ethernet";
      else if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) network = "vpn";
      else network = "connected";
    }
    data.put("network", network);
    StatFs storage = new StatFs(context.getFilesDir().getAbsolutePath());
    data.put("freeStorageMb", storage.getAvailableBytes() / (1024 * 1024));
    StringBuilder text = new StringBuilder();
    if (percent >= 0) text.append("Battery ").append(percent).append("%").append(data.optBoolean("charging") ? " (charging)" : "").append(". ");
    text.append("Network: ").append(network).append(". Free storage: ").append(data.optLong("freeStorageMb")).append(" MB.");
    return data("info", text.toString().trim(), data);
  }

  private static JSONObject flashlight(Context context, boolean enabled) {
    if (context.checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED)
      return status("permission_required", "Flashlight needs Camera permission. Allow it in Android app settings, then ask again.");
    try {
      CameraManager camera = (CameraManager) context.getSystemService(Context.CAMERA_SERVICE);
      if (camera == null) return status("unavailable", "This phone reports no camera service.");
      String id = null;
      for (String candidate : camera.getCameraIdList()) {
        Boolean flash = camera.getCameraCharacteristics(candidate).get(android.hardware.camera2.CameraCharacteristics.FLASH_INFO_AVAILABLE);
        if (Boolean.TRUE.equals(flash)) { id = candidate; break; }
      }
      if (id == null) return status("unavailable", "This phone has no flashlight hardware.");
      camera.setTorchMode(id, enabled);
      return status("torch_changed", enabled ? "Flashlight on." : "Flashlight off.");
    } catch (Exception broken) {
      return status("failed", "The flashlight could not be changed. Another app may be using the camera.");
    }
  }

  private static JSONObject volume(Context context, JSONObject args) {
    try {
      AudioManager audio = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
      if (audio == null) return status("unavailable", "This phone reports no audio service.");
      int max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
      int current = audio.getStreamVolume(AudioManager.STREAM_MUSIC);
      int target = current;
      if (args.has("level")) {
        double percent = args.optDouble("level", -1);
        if (percent < 0 || percent > 100) return status("invalid", "Volume level must be between 0 and 100.");
        target = (int) Math.round(max * percent / 100.0);
      } else if (args.has("direction")) {
        String direction = MobileActions.normalize(args.optString("direction"));
        int step = Math.max(1, max / 10);
        target = current + (direction.equals("down") || direction.equals("lower") ? -step : step);
      } else return status("invalid", "Say the volume level or whether to raise or lower it.");
      target = Math.max(0, Math.min(max, target));
      audio.setStreamVolume(AudioManager.STREAM_MUSIC, target, 0);
      int percent = max == 0 ? 0 : (int) Math.round(target * 100.0 / max);
      return data("volume_changed", "Media volume is now " + percent + "%.", obj("level", percent));
    } catch (RuntimeException broken) {
      return status("failed", "Android blocked the volume change. Do Not Disturb may be limiting it.");
    }
  }

  private static JSONObject media(Context context, String command) {
    String value = MobileActions.normalize(command);
    int key;
    if (value.equals("play_pause") || value.equals("play") || value.equals("pause") || value.equals("toggle")) key = KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE;
    else if (value.equals("next")) key = KeyEvent.KEYCODE_MEDIA_NEXT;
    else if (value.equals("previous") || value.equals("prev")) key = KeyEvent.KEYCODE_MEDIA_PREVIOUS;
    else return status("invalid", "Media command must be play_pause, next or previous.");
    try {
      AudioManager audio = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
      if (audio == null) return status("unavailable", "This phone reports no audio service.");
      long now = android.os.SystemClock.uptimeMillis();
      audio.dispatchMediaKeyEvent(new KeyEvent(now, now, KeyEvent.ACTION_DOWN, key, 0));
      audio.dispatchMediaKeyEvent(new KeyEvent(now, now, KeyEvent.ACTION_UP, key, 0));
      return status("media_sent", "Sent the media " + value.replace('_', ' ') + " key. If nothing changes, start playback in your music app first.");
    } catch (RuntimeException broken) {
      return status("failed", "Android blocked the media key. Nothing was changed.");
    }
  }

  // ---------------------------------------------------------------- notifications / calendar

  private static JSONObject notifications(Context context, JSONObject args) throws Exception {
    if (!JarvisNotificationListener.enabled(context))
      return status("permission_required", "Notification access is off. Turn on \"Vishal Jarvis notification reader\" in Android notification access settings, then ask again.");
    int limit = Math.max(1, Math.min(20, args.optInt("limit", 8)));
    boolean importantOnly = args.optBoolean("importantOnly", false);
    JSONArray items = importantOnly ? JarvisNotificationListener.important(limit) : JarvisNotificationListener.recent(limit, args.optString("app"));
    if (items.length() == 0) return data("info", importantOnly ? "Nothing looks important right now." : "No new notifications.", obj("notifications", items));
    StringBuilder text = new StringBuilder();
    SimpleDateFormat format = new SimpleDateFormat("h:mm a", Locale.US);
    for (int i = 0; i < items.length(); i++) {
      JSONObject item = items.getJSONObject(i);
      text.append(item.optString("app")).append(" (").append(format.format(new Date(item.optLong("at")))).append("): ")
        .append(item.optString("title")).append(item.optString("text").isEmpty() ? "" : " — " + item.optString("text")).append('\n');
    }
    return data("info", text.toString().trim(), obj("notifications", items));
  }

  private static JSONObject clearNotifications(Context context) throws Exception {
    if (!JarvisNotificationListener.enabled(context))
      return status("permission_required", "Notification access is off, so there is nothing I may clear.");
    int cleared = JarvisNotificationListener.dismissAll();
    return status(cleared < 0 ? "failed" : "cleared", cleared < 0 ? "Android did not let me clear notifications right now." : "Cleared the notifications that could be dismissed.");
  }

  private static JSONObject calendar(Context context, JSONObject args) throws Exception {
    if (context.checkSelfPermission(Manifest.permission.READ_CALENDAR) != PackageManager.PERMISSION_GRANTED)
      return status("permission_required", "Calendar access is off. Allow Calendar in Jarvis Pro settings, then ask again.");
    int days = Math.max(1, Math.min(31, args.optInt("days", 1)));
    JSONArray events = CalendarReader.upcoming(context, days, 25, true);
    if (events.length() == 0) return data("info", "No events in the next " + days + (days == 1 ? " day." : " days."), obj("events", events));
    StringBuilder text = new StringBuilder(events.length() + " event(s): ");
    for (int i = 0; i < events.length(); i++) {
      JSONObject event = events.optJSONObject(i);
      if (event == null) continue;
      text.append(event.optString("when")).append(" — ").append(event.optString("title"))
        .append(event.optString("location").isEmpty() ? "" : " @ " + event.optString("location"));
      if (event.has("id")) text.append(" [id ").append(event.optLong("id")).append("]");
      text.append("; ");
    }
    return data("info", text.toString().trim(), obj("events", events));
  }

  private static JSONObject listApps(Context context) {
    try {
      android.content.pm.PackageManager manager = context.getPackageManager();
      Intent main = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
      java.util.LinkedHashSet<String> labels = new java.util.LinkedHashSet<>();
      for (android.content.pm.ResolveInfo info : manager.queryIntentActivities(main, 0)) {
        if (!info.activityInfo.exported) continue;
        labels.add(info.loadLabel(manager).toString());
        if (labels.size() >= 150) break;
      }
      JSONArray apps = new JSONArray();
      for (String label : labels) apps.put(label);
      return data("apps", "Found " + apps.length() + " launchable apps on this device.", obj("apps", apps));
    } catch (Exception failure) {
      return status("failed", "Android did not return the installed app list.");
    }
  }

  // ---------------------------------------------------------------- helpers

  private static JSONObject status(String status, String message) {
    return data(status, message, null);
  }

  private static JSONObject data(String status, String message, JSONObject data) {
    JSONObject value = new JSONObject();
    try {
      value.put("status", status).put("message", message);
      if (data != null) value.put("data", data);
    } catch (Exception ignored) { /* tolerated: a device API refused; the caller reports the failure honestly */ }
    return value;
  }

  private static JSONObject obj(Object... values) {
    JSONObject value = new JSONObject();
    try { for (int i = 0; i + 1 < values.length; i += 2) value.put(String.valueOf(values[i]), values[i + 1]); }
    catch (Exception ignored) { /* nothing to do on release of a temporary object */ }
    return value;
  }

  private BackgroundActions() { }
}

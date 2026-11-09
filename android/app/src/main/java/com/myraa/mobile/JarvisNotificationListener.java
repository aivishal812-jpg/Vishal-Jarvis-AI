package com.myraa.mobile;

import android.app.Notification;
import android.content.ComponentName;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.provider.Settings;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;

/** Opt-in notification access so Jarvis can read recent notifications aloud when the user asks.
 *  Notifications stay in memory only; nothing is stored on disk or sent anywhere except the
 *  user's own Gemini request when they ask about them. */
public final class JarvisNotificationListener extends NotificationListenerService {
  private static final int MAX = 60;
  private static final ArrayDeque<JSONObject> RECENT = new ArrayDeque<>();
  private static volatile JarvisNotificationListener instance;

  static boolean enabled(Context context) {
    String flat = Settings.Secure.getString(context.getContentResolver(), "enabled_notification_listeners");
    return flat != null && flat.contains(new ComponentName(context, JarvisNotificationListener.class).flattenToString());
  }
  @Override public void onListenerConnected() {
    instance = this;
    try { StatusBarNotification[] active = getActiveNotifications(); if (active != null) for (StatusBarNotification n : active) add(n); }
    catch (RuntimeException ignored) { /* nothing to do on release of a temporary object */ }
  }
  @Override public void onListenerDisconnected() { instance = null; }
  @Override public void onNotificationPosted(StatusBarNotification sbn) {
    add(sbn);
    react(sbn);
  }

  /**
   * Proactive, but tiny: an arriving code only produces a "a code arrived" notice (never the
   * code), and a missed call is counted. Both can be turned off in Jarvis Pro settings.
   */
  private void react(StatusBarNotification sbn) {
    try {
      if (sbn == null || getPackageName().equals(sbn.getPackageName())) return;
      Notification n = sbn.getNotification();
      if (n == null) return;
      Bundle extras = n.extras;
      String title = text(extras, Notification.EXTRA_TITLE), body = text(extras, Notification.EXTRA_TEXT);
      if (body.isEmpty()) body = text(extras, Notification.EXTRA_BIG_TEXT);
      if (ProactiveScheduler.otpEnabled(this) && looksSecret(title + " " + body)) {
        ProactiveScheduler.notify(this, 4207, "One-time code from " + label(sbn.getPackageName()),
          "Open the app to read it. For safety the code is never copied into this notification.");
        return;
      }
      boolean missed = Notification.CATEGORY_MISSED_CALL.equals(n.category) || (title + " " + body).toLowerCase(Locale.ROOT).contains("missed call");
      if (missed && ProactiveScheduler.missedEnabled(this)) {
        ProactiveScheduler.notify(this, 4208, "Missed call", title.isEmpty() ? "Tap to see who called." : title);
      }
    } catch (RuntimeException ignored) { /* tolerated: another app’s notification may be malformed */ }
  }

  private void add(StatusBarNotification sbn) {
    if (sbn == null || getPackageName().equals(sbn.getPackageName())) return;
    Notification n = sbn.getNotification();
    if (n == null || (n.flags & Notification.FLAG_ONGOING_EVENT) != 0 || (n.flags & Notification.FLAG_GROUP_SUMMARY) != 0) return;
    Bundle extras = n.extras;
    String title = text(extras, Notification.EXTRA_TITLE), body = text(extras, Notification.EXTRA_BIG_TEXT);
    if (body.isEmpty()) body = text(extras, Notification.EXTRA_TEXT);
    if (title.isEmpty() && body.isEmpty()) return;
    if (looksSecret(title + " " + body)) body = "(hidden: looks like a code or password)";
    JSONObject item = new JSONObject();
    try {
      item.put("app", label(sbn.getPackageName())).put("package", sbn.getPackageName()).put("title", cut(title, 120))
        .put("text", cut(body, 400)).put("at", sbn.getPostTime()).put("key", sbn.getKey())
        .put("priority", n.priority)
        .put("category", n.category == null ? "" : n.category)
        .put("otp", looksSecret(title + " " + body));
    } catch (Exception ignored) { return; }
    synchronized (RECENT) {
      Iterator<JSONObject> it = RECENT.iterator();
      while (it.hasNext()) if (it.next().optString("key").equals(sbn.getKey())) it.remove();
      RECENT.addFirst(item);
      while (RECENT.size() > MAX) RECENT.removeLast();
    }
  }
  private String label(String pkg) {
    try { PackageManager pm = getPackageManager(); return pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString(); }
    catch (Exception e) { return pkg; }
  }
  private static String text(Bundle extras, String key) {
    if (extras == null) return ""; CharSequence value = extras.getCharSequence(key); return value == null ? "" : value.toString().trim();
  }
  private static String cut(String s, int max) { return s.length() > max ? s.substring(0, max) + "…" : s; }
  static boolean looksSecret(String text) {
    String lower = text.toLowerCase(Locale.ROOT);
    return (lower.contains("otp") || lower.contains("verification code") || lower.contains("one time password") || lower.contains("ওটিপি") || lower.contains("password"))
      && text.matches("(?s).*\\b\\d{4,8}\\b.*");
  }
  /** Most recent notifications, newest first, optionally filtered by app name. */
  static JSONArray recent(int limit, String appFilter) {
    String filter = appFilter == null ? "" : appFilter.trim().toLowerCase(Locale.ROOT);
    JSONArray out = new JSONArray();
    List<JSONObject> copy;
    synchronized (RECENT) { copy = new ArrayList<>(RECENT); }
    for (JSONObject item : copy) {
      if (!filter.isEmpty() && !item.optString("app").toLowerCase(Locale.ROOT).contains(filter) && !item.optString("package").toLowerCase(Locale.ROOT).contains(filter)) continue;
      out.put(view(item));
      if (out.length() >= limit) break;
    }
    return out;
  }
  /** Package names whose notifications people usually want summarised first. */
  private static final String[] IMPORTANT_PACKAGES = {
    "com.whatsapp", "com.whatsapp.w4b", "org.telegram.messenger", "com.google.android.apps.messaging",
    "com.android.mms", "com.microsoft.office.outlook", "com.google.android.gm", "com.android.vending",
    "com.google.android.dialer", "com.android.dialer", "com.samsung.android.dialer", "com.android.server.telecom"
  };

  private static boolean isChat(String pkg) {
    for (String chat : new String[]{"com.whatsapp", "com.whatsapp.w4b", "org.telegram.messenger", "org.telegram.plus", "com.instagram.android", "com.facebook.orca"})
      if (chat.equals(pkg)) return true;
    return false;
  }

  /** True for a notification a person would want to hear about now. */
  static boolean important(JSONObject item) {
    if (item == null) return false;
    int priority = item.optInt("priority", 0);
    if (priority >= Notification.PRIORITY_HIGH) return true;
    String category = item.optString("category");
    if (Notification.CATEGORY_MISSED_CALL.equals(category) || Notification.CATEGORY_CALL.equals(category) || Notification.CATEGORY_ALARM.equals(category)) return true;
    String pkg = item.optString("package");
    for (String candidate : IMPORTANT_PACKAGES) if (candidate.equals(pkg)) return true;
    String text = (item.optString("title") + " " + item.optString("text")).toLowerCase(Locale.ROOT);
    return text.contains("urgent") || text.contains("asap") || text.contains("jaldi") || text.contains("জরুরি") || text.contains("জরুরী");
  }

  /** Only the notifications that look important, newest first. OTP bodies stay redacted. */
  static JSONArray important(int limit) {
    List<JSONObject> copy;
    synchronized (RECENT) { copy = new ArrayList<>(RECENT); }
    JSONArray out = new JSONArray();
    for (JSONObject item : copy) {
      if (!important(item)) continue;
      out.put(view(item));
      if (out.length() >= limit) break;
    }
    return out;
  }

  /** Missed calls in the last 24 hours, summarised per caller. */
  static JSONArray missedCalls(int limit) {
    List<JSONObject> copy;
    synchronized (RECENT) { copy = new ArrayList<>(RECENT); }
    JSONArray out = new JSONArray();
    long since = System.currentTimeMillis() - 24L * 3_600_000L;
    for (JSONObject item : copy) {
      if (item.optLong("at", 0) < since) continue;
      String text = (item.optString("title") + " " + item.optString("text")).toLowerCase(Locale.ROOT);
      boolean missed = Notification.CATEGORY_MISSED_CALL.equals(item.optString("category"))
        || text.contains("missed call") || text.contains("মিসড কল") || text.contains("missed");
      if (!missed) continue;
      out.put(view(item));
      if (out.length() >= limit) break;
    }
    return out;
  }

  /** One-time codes found in notifications, with the code itself removed. */
  static JSONArray oneTimeCodes(int limit) {
    List<JSONObject> copy;
    synchronized (RECENT) { copy = new ArrayList<>(RECENT); }
    JSONArray out = new JSONArray();
    for (JSONObject item : copy) {
      if (!item.optBoolean("otp", false)) continue;
      JSONObject view = view(item);
      try {
        view.put("title", "One-time code from " + item.optString("app"))
          .put("text", "A code arrived. It was left hidden on purpose; open the app to read it.")
          .put("app", item.optString("app"));
      } catch (Exception ignored) { /* tolerated: another app’s notification may be malformed */ }
      out.put(view);
      if (out.length() >= limit) break;
    }
    return out;
  }

  /** The public view of one notification. Never returns the package-private fields by accident. */
  private static JSONObject view(JSONObject item) {
    JSONObject value = new JSONObject();
    try {
      value.put("app", item.optString("app")).put("title", item.optString("title"))
        .put("text", item.optString("text")).put("at", item.optLong("at"))
        .put("category", item.optString("category")).put("important", important(item));
    } catch (Exception ignored) { /* tolerated: another app’s notification may be malformed */ }
    return value;
  }

  static boolean connected() { return instance != null; }
  /** Clears notifications Jarvis already read, when the user asks. */
  static int dismissAll() {
    JarvisNotificationListener service = instance; if (service == null) return -1;
    try { service.cancelAllNotifications(); } catch (RuntimeException e) { return -1; }
    // Leave notifications that arrived while the clear was running: they are newer than the request.
    long requestedAt = System.currentTimeMillis();
    int removed;
    synchronized (RECENT) {
      int before = RECENT.size();
      List<JSONObject> keep = new ArrayList<>();
      for (JSONObject item : RECENT) if (item.optLong("at", 0) >= requestedAt) keep.add(item);
      RECENT.clear();
      RECENT.addAll(keep);
      removed = before - keep.size();
    }
    return removed;
  }
}

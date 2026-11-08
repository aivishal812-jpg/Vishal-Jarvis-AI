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
    catch (RuntimeException ignored) { }
  }
  @Override public void onListenerDisconnected() { instance = null; }
  @Override public void onNotificationPosted(StatusBarNotification sbn) { add(sbn); }

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
        .put("text", cut(body, 400)).put("at", sbn.getPostTime()).put("key", sbn.getKey());
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
      JSONObject view = new JSONObject();
      try { view.put("app", item.optString("app")).put("title", item.optString("title")).put("text", item.optString("text")).put("at", item.optLong("at")); }
      catch (Exception ignored) { }
      out.put(view);
      if (out.length() >= limit) break;
    }
    return out;
  }
  static boolean connected() { return instance != null; }
  /** Clears notifications Jarvis already read, when the user asks. */
  static int dismissAll() {
    JarvisNotificationListener service = instance; if (service == null) return -1;
    try { service.cancelAllNotifications(); } catch (RuntimeException e) { return -1; }
    synchronized (RECENT) { int n = RECENT.size(); RECENT.clear(); return n; }
  }
}

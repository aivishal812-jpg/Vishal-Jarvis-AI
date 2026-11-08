package com.myraa.mobile;

import android.content.Context;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import java.util.UUID;

/** Up to 100 reminders kept privately on the device. */
final class ReminderStore {
  static final String KEY = "pro.reminders";
  static final int MAX = 100;
  static synchronized JSONArray all(Context context) {
    try { return new JSONArray(JarvisPrefs.get(context).getString(KEY, "[]")); } catch (JSONException e) { return new JSONArray(); }
  }
  static synchronized void save(Context context, JSONArray list) { JarvisPrefs.get(context).edit().putString(KEY, list.toString()).apply(); }
  static synchronized JSONObject add(Context context, String text, long at, String repeat) throws JSONException {
    JSONArray list = all(context);
    if (list.length() >= MAX) throw new IllegalArgumentException("You already have " + MAX + " reminders. Delete some first.");
    JSONObject item = new JSONObject().put("id", UUID.randomUUID().toString().substring(0, 8)).put("text", text)
      .put("at", at).put("repeat", repeat == null ? "none" : repeat).put("created", System.currentTimeMillis());
    list.put(item); save(context, list); return item;
  }
  static synchronized JSONObject find(Context context, String id) {
    JSONArray list = all(context);
    for (int i = 0; i < list.length(); i++) { JSONObject o = list.optJSONObject(i); if (o != null && o.optString("id").equals(id)) return o; }
    return null;
  }
  static synchronized boolean remove(Context context, String id) {
    JSONArray list = all(context), next = new JSONArray(); boolean removed = false;
    for (int i = 0; i < list.length(); i++) {
      JSONObject o = list.optJSONObject(i); if (o == null) continue;
      if (o.optString("id").equals(id)) removed = true; else next.put(o);
    }
    if (removed) save(context, next);
    return removed;
  }
  static synchronized void update(Context context, JSONObject item) {
    JSONArray list = all(context);
    for (int i = 0; i < list.length(); i++) {
      JSONObject o = list.optJSONObject(i);
      if (o != null && o.optString("id").equals(item.optString("id"))) { try { list.put(i, item); } catch (JSONException ignored) { } }
    }
    save(context, list);
  }
  private ReminderStore() {}
}

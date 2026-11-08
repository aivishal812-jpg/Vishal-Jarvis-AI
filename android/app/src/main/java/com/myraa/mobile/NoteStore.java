package com.myraa.mobile;

import android.content.Context;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import java.util.UUID;

/** Quick voice notes ("note this down…"), separate from the 30 long-term memory facts. */
final class NoteStore {
  static final String KEY = "pro.notes";
  static final int MAX = 200, MAX_CHARS = 1000;
  static synchronized JSONArray all(Context c) {
    try { return new JSONArray(JarvisPrefs.get(c).getString(KEY, "[]")); } catch (JSONException e) { return new JSONArray(); }
  }
  static synchronized JSONObject add(Context c, String text) throws JSONException {
    String body = text == null ? "" : text.trim();
    if (body.isEmpty()) throw new IllegalArgumentException("What should I note down?");
    if (body.length() > MAX_CHARS) body = body.substring(0, MAX_CHARS);
    if (JarvisNotificationListener.looksSecret(body)) throw new IllegalArgumentException("That looks like a password or code. I don't store secrets in notes.");
    JSONArray list = all(c), next = new JSONArray();
    int start = Math.max(0, list.length() - (MAX - 1));
    for (int i = start; i < list.length(); i++) next.put(list.get(i));
    JSONObject note = new JSONObject().put("id", UUID.randomUUID().toString().substring(0, 6)).put("text", body).put("at", System.currentTimeMillis());
    next.put(note);
    JarvisPrefs.get(c).edit().putString(KEY, next.toString()).apply();
    return note;
  }
  static synchronized boolean remove(Context c, String id) {
    JSONArray list = all(c), next = new JSONArray(); boolean removed = false;
    for (int i = 0; i < list.length(); i++) { JSONObject o = list.optJSONObject(i); if (o == null) continue; if (o.optString("id").equals(id)) removed = true; else next.put(o); }
    if (removed) JarvisPrefs.get(c).edit().putString(KEY, next.toString()).apply();
    return removed;
  }
  private NoteStore() {}
}

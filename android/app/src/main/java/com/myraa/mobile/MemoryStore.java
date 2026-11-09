package com.myraa.mobile;

import android.content.Context;
import android.content.SharedPreferences;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.UUID;

/** The same bounded, secret-refusing memory rule is used by UI and background jobs. */
final class MemoryStore {
  static boolean add(Context context, String raw) { return add(context, raw, "fact"); }

  static boolean add(Context context, String raw, String kind) {
    String text = raw == null ? "" : raw.trim().replaceAll("\\s+", " ");
    if (text.length() < 3 || text.length() > 240) return false;
    if (text.matches("(?i).*(api[ _-]?key|password|passcode|otp|secret|card number|aadhaar|ssn|cvv).*")) return false;
    synchronized (AiTaskStore.LOCK) {
      SharedPreferences prefs = context.getSharedPreferences("myraa.mobile.v1", Context.MODE_PRIVATE);
      try {
        JSONArray memory = new JSONArray(prefs.getString("memory", "[]"));
        for (int i = 0; i < memory.length(); i++) {
          JSONObject fact = memory.optJSONObject(i);
          if (fact != null && fact.optString("text", "").equalsIgnoreCase(text)) return true;
        }
        while (memory.length() >= 30) memory.remove(0);
        memory.put(new JSONObject().put("id", UUID.randomUUID().toString()).put("text", text).put("at", System.currentTimeMillis()));
        boolean saved = prefs.edit().putString("memory", memory.toString()).commit();
        if (saved) MemoryDb.recordFact(context, text, kind == null || kind.isEmpty() ? "fact" : kind);
        return saved;
      } catch (Exception e) { return false; }
    }
  }
  static boolean remove(Context context, String id) {
    if (id == null || id.isEmpty()) return false;
    synchronized (AiTaskStore.LOCK) {
      SharedPreferences prefs = context.getSharedPreferences("myraa.mobile.v1", Context.MODE_PRIVATE);
      try {
        JSONArray memory = new JSONArray(prefs.getString("memory", "[]")), next = new JSONArray();
        for (int i = 0; i < memory.length(); i++) {
          JSONObject fact = memory.optJSONObject(i); if (fact != null && !id.equals(fact.optString("id", ""))) next.put(fact);
        }
        return prefs.edit().putString("memory", next.toString()).commit();
      } catch (Exception e) { return false; }
    }
  }
  /** One call used by every tool path, so the honest result object is written once. */
  static JSONObject save(Context context, String raw) { return save(context, raw, "fact"); }

  static JSONObject save(Context context, String raw, String kind) {
    boolean saved = add(context, raw, kind);
    return AiAgent.json("status", saved ? "saved" : "rejected",
      "message", saved ? "Saved to long-term memory. It will be available in future conversations."
        : "That fact was not saved. Keep it short and never store passwords, OTPs, card numbers or API keys.");
  }

  static void clear(Context context) {
    synchronized (AiTaskStore.LOCK) { context.getSharedPreferences("myraa.mobile.v1", Context.MODE_PRIVATE).edit().putString("memory", "[]").commit(); }
  }
  private MemoryStore() {}
}

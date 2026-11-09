package com.myraa.mobile;

import android.content.Context;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Recall and structured memory actions. These need no Activity: they only touch the local
 * SQLite memory ({@link MemoryDb}) and the notification listener's short-lived buffer.
 */
final class MemoryTools {

  private static final String[] SUPPORTED = {
    "what_did_i_say", "missed_calls", "otp_summary", "save_memory_summary"
  };

  static boolean supports(String action) {
    if (action == null) return false;
    for (String candidate : SUPPORTED) if (candidate.equals(action)) return true;
    return false;
  }

  static JSONObject run(Context context, String action, JSONObject args) {
    try {
      JSONObject safe = args == null ? new JSONObject() : args;
      switch (action) {
        case "what_did_i_say": return recall(context, safe);
        case "missed_calls": return missedCalls();
        case "otp_summary": return codes();
        case "save_memory_summary": return summary(context, safe);
        default: return AiAgent.json("status", "unsupported", "message", "That memory action is unavailable.");
      }
    } catch (Exception failure) {
      return AiAgent.json("status", "failed", "message", "The saved memory could not be read right now.");
    }
  }

  /**
   * "What did I tell you yesterday", "what is my saved shop number", "what did we discuss last
   * time": a keyword search when words were given, otherwise the most recent turns.
   */
  private static JSONObject recall(Context context, JSONObject args) {
    String query = args.optString("query", "").trim();
    int limit = Math.max(1, Math.min(20, args.optInt("limit", 8)));
    if (query.isEmpty()) return MemoryDb.recentTurns(context, limit);
    JSONObject found = MemoryDb.search(context, query, limit);
    if (!"not_found".equals(found.optString("status"))) return found;
    // Nothing matched the words themselves: show the last turns so the model can still answer
    // "what did we discuss" honestly, and say that the search found nothing.
    JSONObject recent = MemoryDb.recentTurns(context, Math.min(6, limit));
    if ("not_found".equals(recent.optString("status"))) return found;
    return AiAgent.json("status", "info",
      "message", "Nothing matched \"" + query + "\" exactly. The last things we discussed:\n"
        + recent.optString("message"),
      "turns", recent.optJSONArray("turns"),
      "matched", false);
  }

  private static JSONObject missedCalls() {
    JSONArray calls = JarvisNotificationListener.missedCalls(10);
    if (calls.length() == 0) {
      return AiAgent.json("status", "not_found",
        "message", "I have no missed-call notifications saved. That only works while Vishal Jarvis has notification access turned on.");
    }
    StringBuilder text = new StringBuilder(calls.length() + " missed call(s) in the last day:");
    for (int i = 0; i < calls.length(); i++) text.append("\n• ").append(calls.optJSONObject(i).optString("title"));
    return AiAgent.json("status", "info", "message", text.toString(), "calls", calls);
  }

  /** Counts only: the code digits are never copied out of the notification by this tool. */
  private static JSONObject codes() {
    JSONArray found = JarvisNotificationListener.oneTimeCodes(5);
    if (found.length() == 0) {
      return AiAgent.json("status", "not_found", "message", "No recent one-time codes in the notifications I can see.");
    }
    StringBuilder text = new StringBuilder(found.length() + " recent code notification(s):");
    for (int i = 0; i < found.length(); i++) {
      JSONObject item = found.optJSONObject(i);
      if (item == null) continue;
      text.append("\n• ").append(item.optString("app")).append(" — ").append(item.optString("title").replaceAll("\\d{4,8}", "••••"))
        .append(" (code hidden here on purpose)");
    }
    return AiAgent.json("status", "info", "message", text.toString(), "codes", found);
  }

  private static JSONObject summary(Context context, JSONObject args) {
    String text = args.optString("summary", "").trim();
    if (text.isEmpty() || text.length() > 800) {
      return AiAgent.json("status", "invalid", "message", "Give a short summary (under 800 characters).");
    }
    MemoryDb.saveSummary(context, args.optString("requestId", ""), text);
    return AiAgent.json("status", "saved", "message", "Saved the conversation summary.");
  }

  private MemoryTools() { }
}

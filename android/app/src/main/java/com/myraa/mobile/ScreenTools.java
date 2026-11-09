package com.myraa.mobile;

import android.content.Context;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Screen-awareness tool actions, dispatched by {@link AssistantThread} before it falls back to
 * the UI-only path. Every action here is read-only except fill_form, which only types values
 * the user supplied and never presses submit, send, pay or delete.
 */
final class ScreenTools {

  private static final String[] SUPPORTED = {
    "read_screen", "read_last_message", "fill_form", "screen_info"
  };

  static boolean supports(String action) {
    if (action == null) return false;
    for (String candidate : SUPPORTED) if (candidate.equals(action)) return true;
    return false;
  }

  static JSONObject run(Context context, String action, JSONObject args, String taskId) {
    try {
      if (args == null) args = new JSONObject();
      switch (action) {
        case "read_screen": return ScreenReader.read(args.optInt("maxChars", 6000), taskId);
        case "read_last_message": return ScreenReader.lastMessage();
        case "fill_form": return ScreenReader.fillForm(args.optJSONArray("values"));
        case "screen_info": return ScreenReader.screenInfo();
        default: return AiAgent.json("status", "unsupported", "message", "That screen action is unavailable.");
      }
    } catch (Exception failure) {
      return AiAgent.json("status", "failed", "message", "The screen could not be read right now.");
    }
  }

  private ScreenTools() { }
}

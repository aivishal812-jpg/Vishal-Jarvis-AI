package com.myraa.mobile;

import android.content.Context;
import org.json.JSONObject;

/**
 * The single dispatch table for device actions that work with no Activity.
 *
 * Before this existed, every tool call went through the visible window: a reminder asked from
 * the lock screen could not be saved. Now the four executors are asked in a fixed order and
 * each one answers for itself. Anything none of them can do truthfully returns
 * permission_required instead of pretending.
 *
 * Adding an action = one case here (or in one executor) + one entry in the enum in
 * {@link MobileTools}; {@code npm run sync:tools} then regenerates the TypeScript schema.
 */
final class ActionRegistry {

  /** Executors in priority order: the cheaper, Activity-free ones first. */
  static JSONObject run(Context context, String action, JSONObject args) {
    if (action == null || action.isEmpty()) {
      return AiAgent.json("status", "invalid", "message", "No action was given.");
    }
    JSONObject safe = args == null ? new JSONObject() : args;
    if (BackgroundActions.supports(action)) return BackgroundActions.run(context, action, safe);
    if (ScreenTools.supports(action)) return ScreenTools.run(context, action, safe);
    if (MemoryTools.supports(action)) return MemoryTools.run(context, action, safe);
    return AiAgent.json("status", "permission_required",
      "message", "This action needs the Vishal Jarvis window (it opens a chooser, a permission prompt or a confirmation). Nothing was done; open the app and ask again.");
  }

  /** True when the action can run without a visible Activity, used by the background job. */
  static boolean backgroundSafe(String action) {
    return BackgroundActions.supports(action) || ScreenTools.supports(action) || MemoryTools.supports(action);
  }

  private ActionRegistry() { }
}

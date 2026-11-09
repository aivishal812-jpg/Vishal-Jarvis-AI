package com.myraa.mobile;

import org.json.JSONObject;

/**
 * The one place that answers "can I read the screen?" honestly.
 *
 * Screen text never leaves the phone unless the user asked a question about it, and every
 * automation that could type, click, submit or send asks for a confirmation first.
 */
final class ScreenReader {

  static boolean available() { return UiAutomationService.available(); }
  static boolean enabled() { return UiAutomationService.available(); }

  static JSONObject notEnabled() {
    return AiAgent.json("status", "permission_required",
      "message", "Screen reading is off. Turn on \"Vishal Jarvis screen reader\" in Android Settings → Accessibility, but only if you are happy for Jarvis to read the text of the screens you open.");
  }

  /**
   * Reading order text, capped so a long page cannot flood the model.
   *
   * Accessibility trees miss canvases, games, PDFs and many custom-drawn screens. When the tree
   * is thin, one screenshot of the screen is attached to the same model turn (see
   * {@link ScreenImage}) so the model can read it, exactly as it reads an attached photo. The
   * picture is kept in memory only and dropped after that turn.
   */
  static JSONObject read(int maxChars, String taskId) {
    if (!available()) return notEnabled();
    int cap = Math.max(500, Math.min(20000, maxChars));
    JSONObject tree = UiAutomationService.readScreen(cap, false);
    String treeText = tree.optString("text", "");
    if (treeText.length() > 400) return tree;
    // The tree is thin: this screen is probably canvas-drawn, a PDF or a game. Attach a
    // screenshot instead of guessing, and keep the little text the tree did give.
    JSONObject shot = attachScreenshot(taskId);
    if (!"info".equals(shot.optString("status"))) return tree;
    try {
      return shot.put("accessibilityText", treeText).put("app", tree.optString("app", ""));
    } catch (Exception ignored) {
      return shot;
    }
  }

  static JSONObject lastMessage() {
    if (!available()) return notEnabled();
    return UiAutomationService.lastChatMessage();
  }

  /**
   * Fills the visible empty fields with values the user gave in the same request.
   * Never presses submit or send; the user does that.
   */
  static JSONObject fillForm(org.json.JSONArray values) {
    if (!available()) return notEnabled();
    if (values == null || values.length() == 0) {
      return AiAgent.json("status", "invalid", "message", "Give me the values to type, in the order the fields appear, and I will fill them without submitting anything.");
    }
    return UiAutomationService.fillForm(values);
  }

  static JSONObject screenInfo() {
    if (!available()) return notEnabled();
    return UiAutomationService.screenInfo();
  }

  /**
   * Screenshot fallback: when the tree is nearly empty, capture the screen and attach it to the
   * next model turn, so the model can read what the tree cannot describe.
   */
  static JSONObject attachScreenshot(String taskId) {
    UiAutomationService service = UiAutomationService.get();
    if (service == null) return notEnabled();
    android.graphics.Bitmap shot = service.screenshot();
    if (shot == null) {
      return AiAgent.json("status", "unavailable",
        "message", "Android did not allow a screenshot of this screen (some apps block capture, for example banking and streaming apps). The accessibility text is all I have.");
    }
    try {
      if (!ScreenImage.stage(taskId, shot)) {
        return AiAgent.json("status", "failed", "message", "The screenshot could not be prepared.");
      }
    } finally {
      if (!shot.isRecycled()) shot.recycle();
    }
    return AiAgent.json("status", "info", "screenImage", true,
      "message", "A screenshot of the current screen is attached to this turn; read it directly. It is not saved anywhere.");
  }

  private ScreenReader() { }
}

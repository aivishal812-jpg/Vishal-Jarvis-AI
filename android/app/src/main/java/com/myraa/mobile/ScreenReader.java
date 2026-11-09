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
   * Accessibility trees miss canvases, games, PDFs and many custom-drawn screens, so a
   * screenshot is taken first and its text recognised on the device (ML Kit); the tree text is
   * used when that is not possible or returns nothing. The image never leaves the phone.
   */
  static JSONObject read(int maxChars) {
    if (!available()) return notEnabled();
    int cap = Math.max(500, Math.min(20000, maxChars));
    JSONObject tree = UiAutomationService.readScreen(cap, false);
    String treeText = tree.optString("text", "");
    if (treeText.length() > 400) return tree;
    JSONObject captured = readPixels(cap);
    if (captured == null) return tree;
    String pixels = captured.optString("text", "");
    if (pixels.length() >= treeText.length()) {
      try {
        return captured.put("message", "Read from a screenshot of the screen with on-device text recognition.")
          .put("app", tree.optString("app", captured.optString("app", "")))
          .put("accessibilityText", treeText.length());
      } catch (Exception ignored) { return captured; }
    }
    return tree;
  }

  /** Screenshot + on-device recognition, or null when the phone does not allow it. */
  private static JSONObject readPixels(int maxChars) {
    UiAutomationService service = UiAutomationService.get();
    if (service == null) return null;
    android.graphics.Bitmap shot = service.screenshot();
    if (shot == null) return null;
    final Object lock = new Object();
    final org.json.JSONArray holder = new org.json.JSONArray();
    final boolean[] done = new boolean[1];
    try {
      ScreenOcr.recognise(shot, text -> {
        synchronized (lock) {
          try { holder.put(text == null ? "" : text); } catch (Exception ignored) { }
          done[0] = true;
          lock.notifyAll();
        }
      });
      waitFor(done, lock, 12000);
    } catch (RuntimeException ignored) {
      return null;
    } finally {
      if (shot != null && !shot.isRecycled()) shot.recycle();
    }
    if (!done[0]) return null;
    String text;
    try { text = holder.optString(0, ""); } catch (RuntimeException failure) { return null; }
    if (text.trim().isEmpty()) return null;
    String capped = text.length() > maxChars ? text.substring(0, maxChars) : text;
    return AiAgent.json("status", "info", "text", capped, "chars", capped.length(), "source", "screenshot");
  }

  static JSONObject lastMessage() {
    if (!available()) return notEnabled();
    return UiAutomationService.lastChatMessage();
  }

  /**
   * Fills the visible empty fields with values the user gave in the same request.
   * Never presses submit/send; the user does that.
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

  /** Waits for the recognition callback without blocking the main thread. */
  static void waitFor(boolean[] done, Object lock, long millis) {
    long deadline = System.currentTimeMillis() + millis;
    synchronized (lock) {
      while (!done[0]) {
        long left = deadline - System.currentTimeMillis();
        if (left <= 0) break;
        try { lock.wait(left); } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); break; }
      }
    }
  }

  private ScreenReader() { }
}

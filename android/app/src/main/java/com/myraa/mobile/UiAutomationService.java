package com.myraa.mobile;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.accessibilityservice.AccessibilityService.ScreenshotResult;
import android.accessibilityservice.AccessibilityService.TakeScreenshotCallback;
import android.os.Build;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Rect;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Opt-in screen reader and UI helper.
 *
 * This is the former UiAutomationService, kept on the same class name and manifest
 * entry so people who already granted the permission keep it. It now does two things:
 *
 *  1. Sending one approved message in WhatsApp / Telegram / Instagram / Messenger. This is
 *     still the only target-locked automation: fill the composer, press Send inside the
 *     requested package, and only report "sent" after the composer is verified empty.
 *  2. Reading what is on screen through the accessibility tree (no screenshots, so no
 *     MediaProjection and no extra permission) and offering help, always behind a user
 *     confirmation: reply to a message, fill a form with values the user gave, or explain
 *     a page. Nothing is typed, clicked, submitted or sent without that confirmation.
 */
public final class UiAutomationService extends AccessibilityService {

  /** Result of an automation attempt; never optimistic. */
  public interface Result { void complete(String status, String message); }

  /** Target-locked packages for direct sending. */
  private static final String[] CHAT_PACKAGES = {
    "com.whatsapp", "com.whatsapp.w4b", "org.telegram.messenger", "org.telegram.plus",
    "com.instagram.android", "com.facebook.orca"
  };
  private static final long DEADLINE_MS = 35000;
  private static final long VERIFY_MS = 9000;

  private static volatile UiAutomationService instance;

  public static boolean available() { return instance != null; }
  public static UiAutomationService get() { return instance; }

  private final Handler handler = new Handler(Looper.getMainLooper());
  private Job job;
  private boolean tickScheduled;

  private static final class Job {
    final String packageName, label, text, phone, title;
    final Result callback;
    final long startedAt = System.currentTimeMillis();
    boolean textSet, sendClicked, verifiedTarget, triedSearch;
    long clickedAt, waitedForTarget;
    Job(String packageName, String label, String text, Result callback, String phone, String title) {
      this.packageName = packageName; this.label = label; this.text = text; this.callback = callback;
      this.phone = phone; this.title = title;
    }
  }

  @Override public void onServiceConnected() {
    instance = this;
    setServiceInfo(buildInfo());
  }

  private AccessibilityServiceInfo buildInfo() {
    AccessibilityServiceInfo info = new AccessibilityServiceInfo();
    info.eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED | AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED;
    info.feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC;
    info.flags = AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS | AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS;
    info.notificationTimeout = 120;
    // Screen reading needs every app the user is looking at, but sending stays target-locked
    // inside send(): the package check below refuses to press anything elsewhere.
    info.packageNames = null;
    return info;
  }

  @Override public void onAccessibilityEvent(AccessibilityEvent event) {
    if (job != null) scheduleTick();
  }

  @Override public void onInterrupt() { }

  @Override public boolean onUnbind(Intent intent) {
    if (job != null) finish("cancelled", "Direct sending stopped.");
    instance = null;
    return super.onUnbind(intent);
  }

  @Override public void onDestroy() {
    if (job != null) finish("cancelled", "Direct sending stopped.");
    instance = null;
    super.onDestroy();
  }

  // ================================================================ 1. approved sending

  /** Opens the app's own chat screen for this recipient and sends the prepared text. */
  public void send(String packageName, String deepLink, String label, String text, Result callback) {
    send(packageName, deepLink, label, text, "", "", callback);
  }

  /**
   * @param label the saved contact name, if any (used to pick the chat, and to try searching for
   *              it when a phone number is not available)
   * @param phone international digits without "+", used to make sure the chat really belongs to
   *              that number before anything is typed
   */
  public void send(String packageName, String deepLink, String label, String text, String phone, String title, Result callback) {
    if (job != null) { callback.complete("busy", "Another message is still being sent. Wait a moment."); return; }
    if (deepLink == null || packageName == null) { callback.complete("unsupported", "This app has no direct chat link."); return; }
    try {
      Intent intent = new Intent(Intent.ACTION_VIEW, android.net.Uri.parse(deepLink))
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
      startActivity(intent);
    } catch (RuntimeException blocked) {
      callback.complete("unavailable", "Android could not open the chat screen in that app.");
      return;
    }
    job = new Job(packageName, label, text, callback, phone == null ? "" : phone.replaceAll("[^0-9]", ""), title == null ? "" : title);
    scheduleTick();
    handler.postDelayed(this::deadline, DEADLINE_MS);
  }

  private void deadline() {
    if (job != null && !job.sendClicked) finish("timeout", "The chat screen did not appear in time. Open the app once, then try again.");
  }

  private void scheduleTick() {
    if (tickScheduled) return;
    tickScheduled = true;
    handler.postDelayed(() -> { tickScheduled = false; tick(); }, 350);
  }

  private void tick() {
    Job current = job;
    if (current == null) return;
    if (current.sendClicked && System.currentTimeMillis() - current.clickedAt > VERIFY_MS) {
      // Our own message is now the last bubble in the chat. Prefer a real confirmation (the
      // message text is visibly in the conversation) over a blunt "unverified".
      AccessibilityNodeInfo after = getRootInActiveWindow();
      boolean visible = after != null && after.getPackageName() != null
        && current.packageName.equals(after.getPackageName().toString()) && textVisible(after, current.text);
      finish(visible ? "sent" : "sent_unverified", visible
        ? "Sent to " + current.label + "."
        : "The send button was pressed, but the message was not confirmed in the chat. Check it before assuming it went out.");
      return;
    }
    AccessibilityNodeInfo root = getRootInActiveWindow();
    if (root == null) { scheduleTick(); return; }
    CharSequence packageName = root.getPackageName();
    if (packageName == null || !current.packageName.equals(packageName.toString())) { scheduleTick(); return; }

    if (current.sendClicked) {
      AccessibilityNodeInfo composer = findComposer(root);
      if (composer == null || length(composer.getText()) == 0) {
        finish("sent", "Message sent to " + current.label + ".");
        return;
      }
      scheduleTick();
      return;
    }

    // A WhatsApp link that the app could not resolve opens a "start chat" screen or the chat
    // list. Typing the message there would either go to the wrong person or nowhere, so the
    // target is confirmed first (the number somewhere on screen, or the contact name as title).
    if (!current.verifiedTarget) {
      if (!targetVisible(current, root)) {
        if (!current.triedSearch && System.currentTimeMillis() - current.startedAt > 2500) {
          current.triedSearch = true;
          if (openChatBySearch(current, root)) { current.waitedForTarget = System.currentTimeMillis(); scheduleTick(); return; }
        }
        if (System.currentTimeMillis() - current.startedAt > 20000) {
          finish("unavailable", "The chat with " + current.label + " did not open, so nothing was sent. Open "
            + current.packageName + " once, and check that the number is saved in the app.");
          return;
        }
        scheduleTick();
        return;
      }
      current.verifiedTarget = true;
    }

    if (!current.textSet) {
      AccessibilityNodeInfo composer = findComposer(root);
      if (composer == null) { scheduleTick(); return; }
      if (!setText(current, composer)) {
        finish("failed", "The chat opened but its message field could not be filled. Nothing was sent.");
        return;
      }
      current.textSet = true;
    }

    AccessibilityNodeInfo send = findSend(root);
    if (send == null) {
      if (System.currentTimeMillis() - current.startedAt > VERIFY_MS + 4000) {
        finish("failed", "The message field was filled but the send button was not found. Nothing was sent.");
        return;
      }
      scheduleTick();
      return;
    }
    if (send.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
      current.sendClicked = true;
      current.clickedAt = System.currentTimeMillis();
      scheduleTick();
    } else {
      finish("failed", "Android refused the send button press. Nothing was sent.");
    }
  }

  /**
   * True when the open chat really belongs to the requested person: the phone number (last eight
   * digits, so country-code formats do not matter) or the saved name appears on the screen, or the
   * app itself put the requested title on top.
   */
  private static boolean targetVisible(Job job, AccessibilityNodeInfo root) {
    String wanted = job.phone == null ? "" : job.phone;
    String tail = wanted.length() >= 8 ? wanted.substring(wanted.length() - 8) : wanted;
    String name = normaliseLabel(job.label);
    for (AccessibilityNodeInfo node : collectAll(root, 400)) {
      CharSequence rawText = node.getText();
      CharSequence rawDesc = node.getContentDescription();
      String value = (rawText == null ? "" : rawText.toString()) + " " + (rawDesc == null ? "" : rawDesc.toString());
      String digits = value.replaceAll("[^0-9]", "");
      if (tail.length() >= 6 && digits.contains(tail)) return true;
      String flat = normaliseLabel(value);
      if (name.length() >= 3 && flat.contains(name)) return true;
    }
    return false;
  }

  /** True when (a meaningful part of) the message text is visible on the current screen. */
  private static boolean textVisible(AccessibilityNodeInfo root, String text) {
    if (text == null) return false;
    String wanted = normaliseLabel(text);
    if (wanted.length() < 6) return false;
    String probe = wanted.length() > 40 ? wanted.substring(0, 40) : wanted;
    for (AccessibilityNodeInfo node : collectAll(root, 500)) {
      String value = normaliseLabel(label(node));
      if (value.contains(probe)) return true;
    }
    return false;
  }

  private static String normaliseLabel(String value) {
    return value == null ? "" : value.toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N}]", "");
  }

  private static java.util.List<AccessibilityNodeInfo> collectAll(AccessibilityNodeInfo root, int limit) {
    java.util.List<AccessibilityNodeInfo> out = new ArrayList<>();
    collect(root, out, null, 0);
    return out.size() > limit ? out.subList(0, limit) : out;
  }

  /**
   * Fallback when a deep link cannot address the number: use the app's own search box, type the
   * contact name or the number, and open the first result. This is the path that works for
   * contacts the phone has saved but WhatsApp has not addressed yet.
   */
  private boolean openChatBySearch(Job job, AccessibilityNodeInfo root) {
    if (!"com.whatsapp".equals(job.packageName) && !"com.whatsapp.w4b".equals(job.packageName)) return false;
    AccessibilityNodeInfo search = null;
    for (AccessibilityNodeInfo node : collectAll(root, 400)) {
      if (!node.isEditable() || !node.isEnabled()) continue;
      String hint = label(node);
      String id = node.getViewIdResourceName();
      boolean looksLikeSearch = (id != null && id.toLowerCase(Locale.ROOT).contains("search"))
        || hint.toLowerCase(Locale.ROOT).contains("search") || hint.toLowerCase(Locale.ROOT).contains("খুঁজ");
      if (looksLikeSearch) { search = node; break; }
    }
    if (search == null) return false;
    String query = job.phone != null && job.phone.length() >= 8 ? "+" + job.phone : job.label;
    if (query == null || query.trim().isEmpty()) return false;
    if (!setTextOn(search, query)) return false;
    // The result list appears under the search box; the first row is the person we just searched
    // for, and tapping it opens that exact chat.
    handler.postDelayed(() -> {
      if (job != this.job || job.verifiedTarget) return;
      AccessibilityNodeInfo current = getRootInActiveWindow();
      if (current == null) return;
      for (AccessibilityNodeInfo node : collectAll(current, 600)) {
        if (!node.isClickable()) continue;
        String value = normaliseLabel(label(node));
        if (value.isEmpty()) continue;
        String wantedName = normaliseLabel(job.label);
        String digits = value.replaceAll("[^0-9]", "");
        boolean matchesName = wantedName.length() >= 3 && value.contains(wantedName);
        boolean matchesNumber = job.phone.length() >= 8 && digits.contains(job.phone.substring(job.phone.length() - 8));
        if (matchesName || matchesNumber) {
          node.performAction(AccessibilityNodeInfo.ACTION_CLICK);
          job.waitedForTarget = System.currentTimeMillis();
          scheduleTick();
          return;
        }
      }
    }, 1200);
    return true;
  }

  // ================================================================ 2. screen reading

  /** Package of the window the user is looking at, or "" when none is readable. */
  public static String activePackage() {
    UiAutomationService service = instance;
    if (service == null) return "";
    try {
      AccessibilityNodeInfo root = service.getRootInActiveWindow();
      CharSequence name = root == null ? null : root.getPackageName();
      return name == null ? "" : name.toString();
    } catch (RuntimeException ignored) {
      return "";
    }
  }

  /** Visible text of the current window, in reading order, de-duplicated. */
  public static JSONObject readScreen(int maxChars, boolean withNodes) {
    UiAutomationService service = instance;
    JSONObject out = new JSONObject();
    if (service == null) {
      return ScreenReader.notEnabled();
    }
    try {
      AccessibilityNodeInfo root = service.getRootInActiveWindow();
      if (root == null) {
        try { out.put("status", "unavailable").put("message", "No readable window is open right now."); } catch (Exception ignored) { /* nothing to do on release of a temporary object */ }
        return out;
      }
      String pkg = root.getPackageName() == null ? "" : root.getPackageName().toString();
      List<AccessibilityNodeInfo> nodes = new ArrayList<>();
      collect(root, nodes, null, 0);
      StringBuilder text = new StringBuilder();
      StringBuilder fields = new StringBuilder();
      JSONArray editable = new JSONArray();
      int lines = 0;
      for (AccessibilityNodeInfo node : nodes) {
        String label = label(node);
        if (label.isEmpty()) continue;
        if (text.length() + label.length() + 1 > maxChars) break;
        if (node.isEditable() && node.isEnabled()) {
          fields.append("• ").append(label).append(" (empty field)\n");
          try { editable.put(new JSONObject().put("hint", label).put("index", editable.length())); } catch (Exception ignored) { /* nothing to do on release of a temporary object */ }
        } else {
          text.append(label).append('\n');
          lines++;
        }
      }
      out.put("status", "info")
        .put("package", pkg)
        .put("app", appName(pkg))
        .put("text", text.toString().trim())
        .put("lines", lines)
        .put("fields", editable)
        .put("message", text.length() == 0 ? "I could not read any text on this screen." : "Read " + lines + " line(s) from " + appName(pkg) + ".");
      if (withNodes) out.put("nodeCount", nodes.size());
    } catch (Exception failure) {
      try { out.put("status", "failed").put("message", "The screen could not be read right now."); } catch (Exception ignored) { /* nothing to do on release of a temporary object */ }
    }
    return out;
  }

  /** The text of the message the user is looking at in a chat app, and its sender. */
  public static JSONObject lastChatMessage() {
    JSONObject out = new JSONObject();
    UiAutomationService service = instance;
    if (service == null) return ScreenReader.notEnabled();
    try {
      AccessibilityNodeInfo root = service.getRootInActiveWindow();
      if (root == null) return new JSONObject().put("status", "unavailable").put("message", "No readable window is open.");
      String pkg = root.getPackageName() == null ? "" : root.getPackageName().toString();
      boolean chat = false;
      for (String candidate : CHAT_PACKAGES) if (candidate.equals(pkg)) chat = true;
      if (!chat) return new JSONObject().put("status", "unsupported")
        .put("message", "Open the chat app and the conversation you mean, then ask again. I only read inside WhatsApp, Telegram, Instagram and Messenger.");
      List<AccessibilityNodeInfo> nodes = new ArrayList<>();
      collect(root, nodes, null, 0);
      String sender = "", last = "";
      int bestBottom = -1;
      for (AccessibilityNodeInfo node : nodes) {
        if (node.isEditable()) continue;
        String value = label(node);
        if (value.isEmpty() || value.length() > 1200) continue;
        int bottom = bottomOf(node);
        if (bottom > bestBottom) { bestBottom = bottom; last = value; }
      }
      // The sender is usually the conversation title at the top of the screen.
      for (AccessibilityNodeInfo node : nodes) {
        if (node.isEditable()) continue;
        String id = node.getViewIdResourceName();
        String value = label(node);
        if (value.isEmpty()) continue;
        if (id != null && (id.contains("conversation_contact_name") || id.contains("title") || id.contains("name"))) { sender = value; break; }
      }
      out.put("status", "info").put("package", pkg).put("app", appName(pkg))
        .put("sender", sender).put("lastMessage", last)
        .put("message", last.isEmpty() ? "I could not find a message on this screen." : "Latest message" + (sender.isEmpty() ? "" : " in " + sender) + ": " + last);
    } catch (Exception failure) {
      try { out.put("status", "failed").put("message", "The conversation could not be read."); } catch (Exception ignored) { /* nothing to do on release of a temporary object */ }
    }
    return out;
  }

  /**
   * Fills the empty fields of the current screen with values the user supplied.
   * Only fills; it never presses a submit, send or payment control.
   */
  public static JSONObject fillForm(JSONArray values) {
    UiAutomationService service = instance;
    if (service == null) return ScreenReader.notEnabled();
    JSONObject out = new JSONObject();
    try {
      AccessibilityNodeInfo root = service.getRootInActiveWindow();
      if (root == null) return new JSONObject().put("status", "unavailable").put("message", "No readable window is open.");
      List<AccessibilityNodeInfo> nodes = new ArrayList<>();
      collect(root, nodes, node -> node.isEditable() && node.isEnabled() && node.isVisibleToUser(), 0);
      int filled = 0;
      JSONArray done = new JSONArray();
      for (int i = 0; i < nodes.size() && i < values.length(); i++) {
        String value = values.optString(i, "");
        if (value == null || value.isEmpty() || value.length() > 500) continue;
        AccessibilityNodeInfo field = nodes.get(i);
        Bundle arguments = new Bundle();
        arguments.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value);
        boolean ok;
        try { ok = field.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments); }
        catch (RuntimeException broken) { ok = false; }
        if (ok) { filled++; done.put(new JSONObject().put("field", label(field)).put("value", value.length() > 40 ? value.substring(0, 40) + "…" : value)); }
      }
      out.put("status", filled == 0 ? "failed" : "info")
        .put("filled", filled)
        .put("fields", done)
        .put("message", filled == 0
          ? "I could not fill any field on this screen. Nothing was typed."
          : "Filled " + filled + " field(s). Review them, then press Submit yourself — I never submit a form.");
    } catch (Exception failure) {
      try { out.put("status", "failed").put("message", "The form could not be filled."); } catch (Exception ignored) { /* nothing to do on release of a temporary object */ }
    }
    return out;
  }

  /**
   * Takes a screenshot of what is on screen, inside this accessibility service only.
   *
   * The image is used once, on the phone, to recognise text on screens the accessibility tree
   * cannot describe (canvases, PDFs, games, some banking apps); it is never written to storage
   * or uploaded. Returns null whenever Android or the device refuses the capture, so callers
   * fall back to the tree instead of claiming success.
   */
  @android.annotation.TargetApi(30)
  public android.graphics.Bitmap screenshot() {
    if (Build.VERSION.SDK_INT < 30) return null;
    final java.util.concurrent.atomic.AtomicReference<android.graphics.Bitmap> result =
      new java.util.concurrent.atomic.AtomicReference<>();
    final java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(1);
    try {
      takeScreenshot(android.view.Display.DEFAULT_DISPLAY, getMainExecutor(), new TakeScreenshotCallback() {
        @Override public void onSuccess(ScreenshotResult captured) {
          try {
            android.hardware.HardwareBuffer buffer = captured.getHardwareBuffer();
            if (buffer == null) { latch.countDown(); return; }
            android.graphics.Bitmap hardware = android.graphics.Bitmap.wrapHardwareBuffer(buffer, captured.getColorSpace());
            if (hardware == null) { buffer.close(); latch.countDown(); return; }
            int width = Math.min(hardware.getWidth(), 1080);
            float scale = width / (float) hardware.getWidth();
            int height = Math.max(1, Math.round(hardware.getHeight() * scale));
            android.graphics.Bitmap software = android.graphics.Bitmap.createBitmap(width, height, android.graphics.Bitmap.Config.ARGB_8888);
            android.graphics.Canvas canvas = new android.graphics.Canvas(software);
            canvas.drawBitmap(hardware, null, new android.graphics.Rect(0, 0, width, height), null);
            hardware.recycle();
            buffer.close();
            result.set(software);
          } catch (Throwable ignored) {
          } finally {
            latch.countDown();
          }
        }
        @Override public void onFailure(int errorCode) { latch.countDown(); }
      });
      latch.await(9, java.util.concurrent.TimeUnit.SECONDS);
    } catch (Throwable refused) {
      return null;
    }
    return result.get();
  }

  /** Screen bounds, so a screenshot or OCR result can be lined up with the tree. */
  public static JSONObject screenInfo() {
    UiAutomationService service = instance;
    JSONObject out = new JSONObject();
    try {
      DisplayMetrics metrics = new DisplayMetrics();
      if (service != null) {
        WindowManager windows = (WindowManager) service.getSystemService(Context.WINDOW_SERVICE);
        if (windows != null) windows.getDefaultDisplay().getRealMetrics(metrics);
      }
      out.put("width", metrics.widthPixels).put("height", metrics.heightPixels)
        .put("density", metrics.density).put("activePackage", activePackage());
    } catch (Exception ignored) { /* nothing to do on release of a temporary object */ }
    return out;
  }

  // ================================================================ shared node helpers

  private static int bottomOf(AccessibilityNodeInfo node) {
    Rect bounds = new Rect();
    try { node.getBoundsInScreen(bounds); } catch (RuntimeException ignored) { return 0; }
    return bounds.bottom;
  }

  private static int length(CharSequence value) { return value == null ? 0 : value.toString().trim().length(); }

  /** Best human label for a node: text, then content description, then a readable view id. */
  static String label(AccessibilityNodeInfo node) {
    if (node == null) return "";
    String text = node.getText() == null ? "" : node.getText().toString().trim();
    if (!text.isEmpty()) return text;
    String description = node.getContentDescription() == null ? "" : node.getContentDescription().toString().trim();
    if (!description.isEmpty()) return description;
    if (node.isEditable()) {
      String hint = node.getHintText() == null ? "" : node.getHintText().toString().trim();
      if (!hint.isEmpty()) return hint;
      String id = node.getViewIdResourceName();
      if (id != null && id.contains("/")) return id.substring(id.indexOf('/') + 1).replace('_', ' ');
    }
    return "";
  }

  private boolean setText(Job current, AccessibilityNodeInfo composer) {
    return setTextOn(composer, current.text);
  }

  /** Types into one field; falls back to the clipboard for apps that ignore ACTION_SET_TEXT. */
  private boolean setTextOn(AccessibilityNodeInfo field, String value) {
    if (field == null || value == null) return false;
    Bundle arguments = new Bundle();
    arguments.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value);
    try {
      if (field.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments) && length(field.getText()) > 0) return true;
    } catch (RuntimeException ignored) { /* tolerated: the field may be read-only */ }
    // Fallback for apps that ignore ACTION_SET_TEXT: paste from the clipboard.
    try {
      ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
      if (clipboard == null) return false;
      clipboard.setPrimaryClip(ClipData.newPlainText("Vishal Jarvis", value));
      field.performAction(AccessibilityNodeInfo.ACTION_FOCUS);
      if (field.performAction(AccessibilityNodeInfo.ACTION_PASTE)) return true;
    } catch (RuntimeException ignored) { /* tolerated: clipboard access may be refused */ }
    return false;
  }

  /** The message field is normally the lowest editable, enabled, visible node on screen. */
  private AccessibilityNodeInfo findComposer(AccessibilityNodeInfo root) {
    List<AccessibilityNodeInfo> nodes = new ArrayList<>();
    collect(root, nodes, node -> node.isEditable() && node.isEnabled() && node.isVisibleToUser(), 0);
    AccessibilityNodeInfo best = null;
    int bestBottom = -1;
    for (AccessibilityNodeInfo node : nodes) {
      int bottom = bottomOf(node);
      if (bottom > bestBottom) { bestBottom = bottom; best = node; }
    }
    return best;
  }

  private AccessibilityNodeInfo findSend(AccessibilityNodeInfo root) {
    List<AccessibilityNodeInfo> nodes = new ArrayList<>();
    collect(root, nodes, node -> node.isClickable() && node.isEnabled() && node.isVisibleToUser(), 0);
    AccessibilityNodeInfo best = null;
    int bestScore = Integer.MIN_VALUE;
    for (AccessibilityNodeInfo node : nodes) {
      String description = lower(node.getContentDescription());
      String text = lower(node.getText());
      String id = lower(node.getViewIdResourceName());
      int score = 0;
      if (description.contains("send") || description.contains("পাঠান") || description.contains("পাঠাও")) score += 6;
      if (text.equals("send") || text.contains("পাঠান") || text.contains("পাঠাও")) score += 5;
      if (id.endsWith("send") || id.contains("send_button") || id.contains(":id/send")) score += 4;
      if (score == 0) continue;
      score += bottomOf(node) / 40; // prefer the composer row near the bottom
      if (score > bestScore) { bestScore = score; best = node; }
    }
    return best;
  }

  private interface NodeFilter { boolean accept(AccessibilityNodeInfo node); }

  private static void collect(AccessibilityNodeInfo node, List<AccessibilityNodeInfo> out, NodeFilter filter, int depth) {
    if (node == null || depth > 40 || out.size() > 800) return;
    try {
      if (filter == null || filter.accept(node)) out.add(node);
      for (int i = 0; i < node.getChildCount(); i++) collect(node.getChild(i), out, filter, depth + 1);
    } catch (RuntimeException ignored) { /* nothing to do on release of a temporary object */ }
  }

  private static String lower(CharSequence value) {
    return value == null ? "" : value.toString().toLowerCase(Locale.ROOT);
  }

  private static String appName(String pkg) {
    UiAutomationService service = instance;
    if (service == null) return pkg == null ? "" : pkg;
    try { android.content.pm.PackageManager pm = service.getPackageManager(); return pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString(); }
    catch (Exception ignored) { return pkg; }
  }

  private void finish(String status, String message) {
    Job completed = job;
    job = null;
    handler.removeCallbacksAndMessages(null);
    tickScheduled = false;
    if (completed != null && completed.callback != null) completed.callback.complete(status, message);
  }

  /** Aborts an in-flight automation, used by the Stop button. */
  public void cancel(String reason) {
    if (job != null) finish("cancelled", reason == null ? "Sending cancelled." : reason);
  }
}

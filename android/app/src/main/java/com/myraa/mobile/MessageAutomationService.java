package com.myraa.mobile;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Rect;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Sends a prepared message inside WhatsApp, Telegram, Instagram or Messenger.
 *
 * This is the only way for a normal Android app to press "Send" for another app, so it
 * is strictly opt-in, target-locked and honest:
 *  - the chat is opened through the app's own deep link, so the recipient cannot be
 *    confused with somebody else;
 *  - the composer is filled with ACTION_SET_TEXT (clipboard paste only as a fallback);
 *  - the send button is pressed only inside the requested package's own window;
 *  - success is reported only after the composer is verified empty.
 */
public final class MessageAutomationService extends AccessibilityService {
  public interface Result { void complete(String status, String message); }

  /** The service is scoped to these packages; nothing else on the phone is observable. */
  private static final String[] CHAT_PACKAGES = {
    "com.whatsapp", "com.whatsapp.w4b", "org.telegram.messenger", "org.telegram.plus",
    "com.instagram.android", "com.facebook.orca"
  };
  private static final long DEADLINE_MS = 35000;
  private static final long VERIFY_MS = 9000;

  private static volatile MessageAutomationService instance;

  public static boolean available() { return instance != null; }
  public static MessageAutomationService get() { return instance; }

  private final Handler handler = new Handler(Looper.getMainLooper());
  private Job job;
  private boolean tickScheduled;

  private static final class Job {
    final String packageName, label, text;
    final Result callback;
    final long startedAt = System.currentTimeMillis();
    boolean textSet, sendClicked;
    long clickedAt;
    Job(String packageName, String label, String text, Result callback) {
      this.packageName = packageName; this.label = label; this.text = text; this.callback = callback;
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
    info.packageNames = CHAT_PACKAGES;
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

  /** Opens the app's own chat screen for this recipient and sends the prepared text. */
  public void send(String packageName, String deepLink, String label, String text, Result callback) {
    if (job != null) { callback.complete("busy", "Another message is still being sent. Wait a moment."); return; }
    if (deepLink == null || packageName == null) { callback.complete("unsupported", "This app has no direct chat link."); return; }
    try {
      Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(deepLink))
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
      startActivity(intent);
    } catch (RuntimeException blocked) {
      callback.complete("unavailable", "Android could not open the chat screen in that app.");
      return;
    }
    job = new Job(packageName, label, text, callback);
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
      finish("sent_unverified", "The send button was pressed, but the app did not confirm it. Check the chat before assuming it went out.");
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

  private static int bottomOf(AccessibilityNodeInfo node) {
    Rect bounds = new Rect();
    try { node.getBoundsInScreen(bounds); } catch (RuntimeException ignored) { return 0; }
    return bounds.bottom;
  }

  private static int length(CharSequence value) { return value == null ? 0 : value.toString().trim().length(); }

  private boolean setText(Job job, AccessibilityNodeInfo composer) {
    Bundle arguments = new Bundle();
    arguments.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, job.text);
    try {
      if (composer.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments) && length(composer.getText()) > 0) return true;
    } catch (RuntimeException ignored) { }
    // Fallback for apps that ignore ACTION_SET_TEXT: paste from the clipboard.
    try {
      ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
      clipboard.setPrimaryClip(ClipData.newPlainText("Vishal Jarvis", job.text));
      composer.performAction(AccessibilityNodeInfo.ACTION_FOCUS);
      if (composer.performAction(AccessibilityNodeInfo.ACTION_PASTE)) return true;
    } catch (RuntimeException ignored) { }
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
    if (node == null || depth > 40 || out.size() > 500) return;
    try {
      if (filter.accept(node)) out.add(node);
      for (int i = 0; i < node.getChildCount(); i++) collect(node.getChild(i), out, filter, depth + 1);
    } catch (RuntimeException ignored) { }
  }

  private static String lower(CharSequence value) {
    return value == null ? "" : value.toString().toLowerCase(Locale.ROOT);
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

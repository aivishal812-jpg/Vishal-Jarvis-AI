package com.myraa.mobile;

import android.content.Context;
import org.json.JSONObject;

import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * One hands-free request, start to finish, with no Activity involved.
 *
 * The durable job, the lock-screen voice card and the headless assistant all need exactly
 * the same steps (enqueue, claim, run the agent, execute tools, finish honestly), and that
 * glue used to be copy-pasted in each caller. It lives here once.
 *
 * Tool calls are answered as follows:
 *  - remember_fact        -> the shared memory store (never secrets)
 *  - background-safe      -> {@link BackgroundActions} with no window at all
 *  - screen-reading tools -> {@link ScreenReader}, only when screen access is enabled
 *  - anything else        -> permission_required, so nothing ever looks like it happened
 */
final class AssistantThread {

  /** A tool that needs the app window, such as a chooser or a confirmation dialog. */
  interface UiTool {
    JSONObject call(String name, JSONObject args) throws Exception;
  }

  interface Options {
    /** Show short spoken answers (hands-free popup, lock screen). */
    boolean voice();
    /** Tool calls that need the window; null when this thread must stay headless. */
    UiTool uiTool();
    /** True when the app is visible, so notifications for a finished task are unnecessary. */
    boolean appVisible();
  }

  static final class Result {
    final String taskId;
    final String answer;
    final Exception failure;
    Result(String taskId, String answer, Exception failure) { this.taskId = taskId; this.answer = answer; this.failure = failure; }
    boolean ok() { return failure == null && answer != null; }
  }

  private final Context app;
  private final Options options;
  private volatile boolean cancelled;
  private volatile javax.net.ssl.HttpsURLConnection connection;

  AssistantThread(Context context, Options options) {
    this.app = context.getApplicationContext();
    this.options = options;
  }

  void cancel() {
    cancelled = true;
    javax.net.ssl.HttpsURLConnection current = connection;
    if (current != null) { try { current.disconnect(); } catch (RuntimeException ignored) { /* tolerated: the connection is already gone */ } }
  }

  boolean cancelled() { return cancelled; }

  /** The filesystem-safe task id used by the queue, history and image staging. */
  static String newTaskId(String prefix) {
    String id = (prefix == null || prefix.isEmpty() ? "task" : prefix) + "-" + UUID.randomUUID();
    return id.length() > 80 ? id.substring(0, 80) : id;
  }

  /**
   * Runs one prompt. Never throws: the failure is returned so the caller can decide between
   * retrying later and telling the user. The task row and history stay consistent.
   */
  Result run(String prompt, String prefix) {
    String text = prompt == null ? "" : prompt.trim();
    if (text.isEmpty()) return new Result(null, null, new IllegalArgumentException("Empty request"));
    AiTaskStore store = AiTaskStore.get(app);
    String id = newTaskId(prefix);
    try {
      store.enqueue(id, text);
    } catch (Exception failure) {
      return new Result(id, null, failure);
    }
    return runSaved(store, id);
  }

  /** Runs a task that is already saved (the durable job path). */
  Result runSaved(AiTaskStore store, String id) {
    AiTaskStore.Task task = store.find(id);
    if (task == null) return new Result(id, null, new IllegalStateException("Task not found"));
    boolean claimed = AiTaskCoordinator.claim(store, id);
    if (!claimed) {
      store.fail(id, "Another reply is already running.");
      return new Result(id, null, new IllegalStateException("Another reply is already running."));
    }
    try {
      String answer = new AiAgent(app, id, new AiAgent.Listener() {
        public boolean cancelled() { return cancelled; }
        public void connection(javax.net.ssl.HttpsURLConnection value) {
          connection = value;
          if (cancelled && value != null) value.disconnect();
        }
        public void event(JSONObject event) { AiTaskEvents.emit(event); }
        public JSONObject tool(String name, JSONObject args) { return runTool(name, args, id); }
      }).voiceMode(options.voice()).run(task);
      if (cancelled) return new Result(id, null, new InterruptedException("Cancelled"));
      if (!store.complete(id, answer)) return new Result(id, answer, new IllegalStateException("The answer could not be saved."));
      if (!options.appVisible()) AiTaskNotifications.finished(app, task, true);
      AiTaskEvents.emit(AiAgent.json("type", "reply", "id", id, "text", answer));
      AiTaskEvents.emit(AiAgent.json("type", "task-state"));
      return new Result(id, answer, null);
    } catch (Exception failure) {
      if (!store.isCancelled(id)) {
        if (AiAgent.retryable(failure)) {
          store.requeue(id, AiAgent.safeMessage(failure));
          AiTaskEvents.emit(AiAgent.json("type", "queued", "id", id, "message", "Connection interrupted. Your saved task will retry automatically."));
        } else {
          String message = AiAgent.safeMessage(failure);
          store.fail(id, message);
          if (!options.appVisible()) {
            AiTaskStore.Task failed = store.find(id);
            if (failed != null) AiTaskNotifications.finished(app, failed, false);
          }
          AiTaskEvents.emit(AiAgent.json("type", "error", "service", "background", "text", "id", id, "message", message));
        }
      }
      return new Result(id, null, failure);
    } finally {
      AiTaskCoordinator.end(id);
      connection = null;
    }
  }

  /** Executes one tool call without an Activity, honestly. */
  JSONObject runTool(String name, JSONObject args, String taskId) {
    if (!MobileTools.ACTION.equals(name)) {
      return AiAgent.json("status", "unsupported", "message", "That tool is not available here.");
    }
    String action = args == null ? "" : args.optString("action", "");
    if (MobileTools.REMEMBER.equals(name)) {
      String kind = args.optString("kind", "fact");
      String label = args.optString("name", "");
      String value = args.optString("value", "");
      if (!label.isEmpty() && !value.isEmpty()) {
        return MemoryDb.rememberEntity(app, kind.isEmpty() ? "fact" : kind, label, value);
      }
      return MemoryStore.save(app, args.optString("fact", ""), kind.isEmpty() ? "fact" : kind);
    }
    if (ActionRegistry.backgroundSafe(action)) return ActionRegistry.run(app, action, args, taskId);
    UiTool ui = options.uiTool();
    if (ui != null) {
      try {
        JSONObject result = ui.call(name, args);
        return result == null ? AiAgent.json("status", "failed", "message", "The app returned no result.") : result;
      } catch (Exception failure) {
        return AiAgent.json("status", "failed", "message", "That action could not be completed.");
      }
    }
    return AiAgent.json("status", "permission_required",
      "message", "This action needs the Vishal Jarvis window (it opens a chooser, a permission prompt or a confirmation). Nothing was done here; open the app and ask again.");
  }

  // ---------------------------------------------------------------- blocking helpers

  /** Marks a task as running on behalf of the visible Activity, without the agent loop. */
  static boolean claimForActivity(AiTaskStore store, String id) {
    return AiTaskCoordinator.beginForeground(store, id);
  }

  /** A one-shot latch-based tool bridge, used by the visible app and the popup card. */
  static final class Waiter {
    private final CountDownLatch latch = new CountDownLatch(1);
    private volatile JSONObject value;
    void complete(JSONObject result) { value = result; latch.countDown(); }
    JSONObject await(long seconds) throws InterruptedException {
      return latch.await(seconds, TimeUnit.SECONDS) ? value : null;
    }
  }

  /** Clears waiters when the user taps Stop, so no request is left hanging. */
  static void cancelAll(ConcurrentHashMap<String, Waiter> waiters) {
    for (String key : new java.util.ArrayList<>(waiters.keySet())) {
      Waiter waiter = waiters.remove(key);
      if (waiter != null) waiter.complete(AiAgent.json("status", "cancelled", "message", "Action cancelled."));
    }
  }
}

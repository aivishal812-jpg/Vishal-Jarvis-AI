package com.myraa.mobile;

import android.app.job.JobParameters;
import android.app.job.JobService;
import org.json.JSONObject;
import javax.net.ssl.HttpsURLConnection;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Persisted, network-constrained fallback after Activity/process death or disconnection. */
public final class AiTaskJobService extends JobService {
  private static final Map<String, Control> running = new ConcurrentHashMap<>();
  private static final class Control {
    volatile boolean stopped, acquired;
    volatile HttpsURLConnection connection;
    void stop() { stopped = true; HttpsURLConnection c = connection; if (c != null) c.disconnect(); }
  }
  static void stopTask(String id) { Control control = running.get(id); if (control != null) control.stop(); }
  @Override public boolean onStartJob(JobParameters params) {
    String id = params.getExtras().getString("taskId");
    if (id == null || !id.matches("[A-Za-z0-9-]{1,80}")) return false;
    Control control = new Control();
    if (running.putIfAbsent(id, control) != null) {
      // A stopped connection can take time to unwind. Do not lose its rescheduled job.
      new android.os.Handler(getMainLooper()).post(() -> jobFinished(params, true));
      return true;
    }
    new Thread(() -> process(params, id, control), "VishalJarvis-Task").start();
    return true;
  }
  private void process(JobParameters params, String id, Control control) {
    AiTaskStore store = AiTaskStore.get(this);
    boolean retry = false, acquired = false;
    try {
      AiTaskStore.Task task = store.find(id);
      if (task == null || !("queued".equals(task.status) || "running".equals(task.status))) return;
      if (control.stopped) return;
      if (!AiTaskCoordinator.beginJob(store, id)) { retry = true; return; }
      acquired = true; control.acquired = true; task = store.find(id);
      if (control.stopped || task == null || store.isCancelled(id)) return;
      // A previous process can die after saving history but before marking the task complete.
      org.json.JSONArray history = store.history();
      for (int i = 0; i < history.length(); i++) {
        JSONObject turn = history.optJSONObject(i);
        if (turn != null && id.equals(turn.optString("taskId")) && "model".equals(turn.optString("role"))) {
          if (store.complete(id, turn.optString("text"))) completed(task, turn.optString("text"));
          return;
        }
      }
      final AiTaskStore.Task current = task;
      String answer = new AiAgent(this, id, new AiAgent.Listener() {
        public boolean cancelled() { return control.stopped; }
        public void connection(HttpsURLConnection connection) { control.connection = connection; if (control.stopped && connection != null) connection.disconnect(); }
        public void event(JSONObject event) { AiTaskEvents.emit(event); }
        public JSONObject tool(String name, JSONObject args) {
          if (MobileTools.REMEMBER.equals(name)) {
            boolean saved = MemoryStore.add(AiTaskJobService.this, args.optString("fact", ""));
            return AiAgent.json("status", saved ? "saved" : "rejected", "message", saved ? "Saved to long-term memory." : "That fact was not saved; do not store secrets.");
          }
          // UI actions and confirmations cannot safely be launched from a background job.
          return AiAgent.json("status", "permission_required", "message", "This action requires Vishal Jarvis to be open. Return to the app and ask again; nothing was done in the background.");
        }
      }).run(current);
      if (!control.stopped && store.complete(id, answer)) completed(current, answer);
    } catch (InterruptedException stopped) {
      if (!store.isCancelled(id)) { store.requeue(id, "Waiting to resume."); retry = true; }
      Thread.currentThread().interrupt();
    } catch (Exception failure) {
      if (!store.isCancelled(id)) {
        if (control.stopped || AiAgent.retryable(failure)) {
          store.requeue(id, AiAgent.safeMessage(failure)); retry = true;
          AiTaskEvents.emit(AiAgent.json("type", "queued", "id", id, "message", "Connection interrupted. Your saved task will retry automatically."));
        } else {
          String message = AiAgent.safeMessage(failure); store.fail(id, message);
          AiTaskStore.Task task = store.find(id);
          if (task != null && !AiTaskEvents.visible) AiTaskNotifications.finished(this, task, false);
          AiTaskEvents.emit(AiAgent.json("type", "error", "service", "text", "id", id, "message", message));
          AiTaskEvents.emit(AiAgent.json("type", "task-state"));
        }
      }
    } finally {
      if (acquired) AiTaskCoordinator.endJob(id);
      running.remove(id, control);
      if (!control.stopped) jobFinished(params, retry);
    }
  }
  private void completed(AiTaskStore.Task task, String answer) {
    if (!AiTaskEvents.visible) AiTaskNotifications.finished(this, task, true);
    AiTaskEvents.emit(AiAgent.json("type", "reply", "id", task.id, "text", answer));
    AiTaskEvents.emit(AiAgent.json("type", "task-state"));
  }
  @Override public boolean onStopJob(JobParameters params) {
    String id = params.getExtras().getString("taskId");
    Control control = id == null ? null : running.get(id);
    if (control != null) { control.stop(); running.remove(id, control); }
    if (control != null && control.acquired && id != null && !AiTaskStore.get(this).isCancelled(id)) AiTaskStore.get(this).requeue(id, "Waiting for Android to resume the task.");
    return true;
  }
}

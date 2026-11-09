package com.myraa.mobile;

import android.content.ContentValues;
import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import org.json.JSONArray;
import org.json.JSONObject;

/** Private, durable queue. No API key or Android intent is ever put in a JobInfo. */
final class AiTaskStore extends SQLiteOpenHelper {
  static final Object LOCK = new Object();
  private static AiTaskStore instance;
  static AiTaskStore get(Context context) {
    synchronized (LOCK) {
      if (instance == null) instance = new AiTaskStore(context.getApplicationContext());
      return instance;
    }
  }
  private final Context app;
  private AiTaskStore(Context context) { super(context, "ai_tasks.db", null, 1); app = context; }
  @Override public void onCreate(SQLiteDatabase db) {
    db.execSQL("CREATE TABLE tasks (_id INTEGER PRIMARY KEY AUTOINCREMENT, request_id TEXT NOT NULL UNIQUE, prompt TEXT NOT NULL, status TEXT NOT NULL, created_at INTEGER NOT NULL, updated_at INTEGER NOT NULL, attempts INTEGER NOT NULL DEFAULT 0, tool_started INTEGER NOT NULL DEFAULT 0, result TEXT NOT NULL DEFAULT '', error TEXT NOT NULL DEFAULT '')");
    db.execSQL("CREATE INDEX task_status_order ON tasks(status, _id)");
  }
  @Override public void onUpgrade(SQLiteDatabase db, int from, int to) { throw new IllegalStateException("Unsupported task database upgrade"); }

  static final class Task {
    final long number; final String id, prompt, status, error; final boolean toolStarted; final int attempts;
    Task(Cursor c) {
      number = c.getLong(c.getColumnIndexOrThrow("_id")); id = c.getString(c.getColumnIndexOrThrow("request_id"));
      prompt = c.getString(c.getColumnIndexOrThrow("prompt")); status = c.getString(c.getColumnIndexOrThrow("status")); error = c.getString(c.getColumnIndexOrThrow("error"));
      toolStarted = c.getInt(c.getColumnIndexOrThrow("tool_started")) != 0; attempts = c.getInt(c.getColumnIndexOrThrow("attempts"));
    }
    int jobId() {
      // JobScheduler IDs must be positive and stable across process death/reboots.
      if (number > Integer.MAX_VALUE - 10000L) throw new IllegalStateException("Too many tasks");
      return (int)number + 10000;
    }
  }
  Task enqueue(String id, String text) {
    synchronized (LOCK) {
      ContentValues v = new ContentValues(); long now = System.currentTimeMillis();
      v.put("request_id", id); v.put("prompt", text); v.put("status", "queued"); v.put("created_at", now); v.put("updated_at", now);
      getWritableDatabase().insertOrThrow("tasks", null, v);
      return find(id);
    }
  }
  Task find(String id) {
    synchronized (LOCK) {
      try (Cursor c = getReadableDatabase().query("tasks", null, "request_id=?", new String[]{id}, null, null, null)) {
        return c.moveToFirst() ? new Task(c) : null;
      }
    }
  }
  Task firstPending() {
    synchronized (LOCK) {
      try (Cursor c = getReadableDatabase().query("tasks", null, "status IN ('queued','running')", null, null, null, "_id ASC", "1")) {
        return c.moveToFirst() ? new Task(c) : null;
      }
    }
  }
  Task latestPending() {
    synchronized (LOCK) {
      try (Cursor c = getReadableDatabase().query("tasks", null, "status IN ('queued','running')", null, null, null, "_id DESC", "1")) {
        return c.moveToFirst() ? new Task(c) : null;
      }
    }
  }
  java.util.List<Task> pending() {
    synchronized (LOCK) {
      java.util.List<Task> tasks = new java.util.ArrayList<>();
      try (Cursor c = getReadableDatabase().query("tasks", null, "status IN ('queued','running')", null, null, null, "_id ASC")) {
        while (c.moveToNext()) tasks.add(new Task(c));
      }
      return tasks;
    }
  }
  int pendingCount() { return pending().size(); }
  Task latestFailure() {
    synchronized (LOCK) {
      try (Cursor c = getReadableDatabase().query("tasks", null, null, null, null, null, "_id DESC", "1")) {
        if (!c.moveToFirst()) return null;
        Task latest = new Task(c); return "failed".equals(latest.status) ? latest : null;
      }
    }
  }
  boolean running(String id) {
    synchronized (LOCK) {
      SQLiteDatabase db = getWritableDatabase();
      db.execSQL("UPDATE tasks SET status='running', updated_at=?, attempts=attempts+1 WHERE request_id=? AND status IN ('queued','running')", new Object[]{System.currentTimeMillis(), id});
      Task task = find(id); return task != null && "running".equals(task.status);
    }
  }
  void markToolStarted(String id) {
    synchronized (LOCK) {
      ContentValues v = new ContentValues(); v.put("tool_started", 1); v.put("updated_at", System.currentTimeMillis());
      getWritableDatabase().update("tasks", v, "request_id=? AND status='running'", new String[]{id});
    }
  }
  void requeue(String id, String reason) { change(id, "queued", reason); }
  void fail(String id, String reason) { change(id, "failed", reason); }
  void cancel(String id) { change(id, "cancelled", "Cancelled by the user."); }
  private void change(String id, String status, String error) {
    synchronized (LOCK) {
      ContentValues v = new ContentValues(); v.put("status", status); v.put("error", error == null ? "" : error); v.put("updated_at", System.currentTimeMillis());
      getWritableDatabase().update("tasks", v, "request_id=? AND status IN ('queued','running')", new String[]{id});
    }
  }
  boolean isCancelled(String id) {
    Task task = find(id);
    return task == null || "cancelled".equals(task.status) || "failed".equals(task.status);
  }
  JSONArray history() {
    try { return new JSONArray(app.getSharedPreferences("myraa.mobile.v1", Context.MODE_PRIVATE).getString("history", "[]")); }
    catch (Exception ignored) { return new JSONArray(); }
  }
  /** Record history before marking complete; request IDs make a crash between writes idempotent. */
  boolean complete(String id, String reply) throws Exception {
    synchronized (LOCK) {
      Task task = find(id);
      if (task == null || !("running".equals(task.status) || "queued".equals(task.status))) return false;
      SharedPreferences prefs = app.getSharedPreferences("myraa.mobile.v1", Context.MODE_PRIVATE);
      JSONArray existing = history(); boolean alreadySaved = false;
      for (int i = 0; i < existing.length(); i++) {
        JSONObject turn = existing.optJSONObject(i);
        if (turn != null && id.equals(turn.optString("taskId")) && "model".equals(turn.optString("role"))) alreadySaved = true;
      }
      if (!alreadySaved) {
        JSONArray next = new JSONArray();
        for (int i = Math.max(0, existing.length() - 98); i < existing.length(); i++) next.put(existing.getJSONObject(i));
        long now = System.currentTimeMillis();
        next.put(new JSONObject().put("role", "user").put("text", task.prompt).put("at", now).put("taskId", id));
        next.put(new JSONObject().put("role", "model").put("text", reply).put("at", now).put("taskId", id));
        if (!prefs.edit().putString("history", next.toString()).commit()) throw new java.io.IOException("Could not save the reply on this device.");
        // Structured copy: conversation, both turns and search index (best effort, never fatal).
        MemoryDb.recordTurn(app, id, task.prompt, "user", task.prompt);
        MemoryDb.recordTurn(app, id, task.prompt, "model", reply);
        MemoryDb.summariseIfLong(app, id, task.prompt, reply);
      }
      ContentValues v = new ContentValues(); v.put("status", "completed"); v.put("result", reply); v.put("error", ""); v.put("updated_at", System.currentTimeMillis());
      getWritableDatabase().update("tasks", v, "request_id=? AND status IN ('queued','running')", new String[]{id});
      // Keep a small result trail; conversation history is stored separately.
      getWritableDatabase().execSQL("DELETE FROM tasks WHERE status IN ('completed','cancelled','failed') AND _id NOT IN (SELECT _id FROM tasks WHERE status IN ('completed','cancelled','failed') ORDER BY _id DESC LIMIT 100)");
      return true;
    }
  }
  void clearHistory() {
    synchronized (LOCK) {
      app.getSharedPreferences("myraa.mobile.v1", Context.MODE_PRIVATE).edit().putString("history", "[]").commit();
      getWritableDatabase().delete("tasks", null, null);
    }
  }
}

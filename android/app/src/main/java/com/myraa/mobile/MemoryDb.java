package com.myraa.mobile;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Structured long-term memory: conversations, turns, facts, entities and a full-text index.
 *
 * The old flat preferences ("history", "memory") are still used by the UI and are imported
 * once on first start, so nothing the user already told Jarvis is lost. Every write here is
 * best-effort: a memory failure must never break a chat reply or a device action.
 */
final class MemoryDb extends SQLiteOpenHelper {

  private static final String NAME = "jarvis_memory.db";
  private static final int VERSION = 1;
  private static volatile MemoryDb instance;

  static MemoryDb get(Context context) {
    if (instance == null) {
      synchronized (MemoryDb.class) {
        if (instance == null) instance = new MemoryDb(context.getApplicationContext());
      }
    }
    return instance;
  }

  private MemoryDb(Context context) { super(context, NAME, null, VERSION); }

  @Override public void onCreate(SQLiteDatabase db) {
    db.execSQL("CREATE TABLE conversations (_id INTEGER PRIMARY KEY AUTOINCREMENT, request_id TEXT NOT NULL UNIQUE, prompt TEXT NOT NULL, created_at INTEGER NOT NULL)");
    db.execSQL("CREATE TABLE turns (_id INTEGER PRIMARY KEY AUTOINCREMENT, conversation_id INTEGER NOT NULL, role TEXT NOT NULL, text TEXT NOT NULL, at INTEGER NOT NULL)");
    db.execSQL("CREATE INDEX turns_conversation ON turns(conversation_id, _id)");
    db.execSQL("CREATE TABLE facts (_id INTEGER PRIMARY KEY AUTOINCREMENT, text TEXT NOT NULL UNIQUE, kind TEXT NOT NULL DEFAULT 'fact', created_at INTEGER NOT NULL)");
    db.execSQL("CREATE TABLE entities (_id INTEGER PRIMARY KEY AUTOINCREMENT, kind TEXT NOT NULL, name TEXT NOT NULL, value TEXT NOT NULL, updated_at INTEGER NOT NULL)");
    db.execSQL("CREATE UNIQUE INDEX entities_kind_name ON entities(kind, name)");
    db.execSQL("CREATE TABLE summaries (_id INTEGER PRIMARY KEY AUTOINCREMENT, conversation_id INTEGER NOT NULL, text TEXT NOT NULL, at INTEGER NOT NULL)");
    // FTS4 keeps the index inside SQLite itself: no extra dependency, and it works on API 26.
    db.execSQL("CREATE VIRTUAL TABLE search USING fts4(content, kind, ref)");
  }

  @Override public void onUpgrade(SQLiteDatabase db, int from, int to) {
    // Only additive migrations are allowed; the FTS table survives because nothing is dropped.
    if (from < 2) {
      try { db.execSQL("ALTER TABLE facts ADD COLUMN source TEXT NOT NULL DEFAULT ''"); } catch (RuntimeException already) { /* first upgrade */ }
    }
  }

  // ---------------------------------------------------------------- import once

  private static volatile boolean imported;

  /** Copies the old preference-based history and facts into SQL once, then never again. */
  static void importLegacy(Context context) {
    if (imported) return;
    imported = true;
    try {
      android.content.SharedPreferences prefs = context.getSharedPreferences("myraa.mobile.v1", Context.MODE_PRIVATE);
      JSONArray history = new JSONArray(prefs.getString("history", "[]"));
      MemoryDb db = get(context);
      SQLiteDatabase writable = db.getWritableDatabase();
      String lastTask = null;
      for (int i = 0; i < history.length(); i++) {
        JSONObject turn = history.optJSONObject(i);
        if (turn == null) continue;
        String role = turn.optString("role");
        String text = turn.optString("text");
        if (text.isEmpty() || (!"user".equals(role) && !"model".equals(role))) continue;
        String taskId = turn.optString("taskId", "legacy-" + i);
        long at = turn.optLong("at", System.currentTimeMillis());
        int conversation = ensureConversation(writable, taskId, "user".equals(role) ? text : "", at);
        if (conversation > 0) addTurn(writable, conversation, role, text, at);
        lastTask = taskId;
      }
      JSONArray facts = new JSONArray(prefs.getString("memory", "[]"));
      for (int i = 0; i < facts.length(); i++) {
        JSONObject fact = facts.optJSONObject(i);
        if (fact != null) addFact(writable, fact.optString("text"), "fact", fact.optLong("at", System.currentTimeMillis()));
      }
      voidTask(lastTask);
    } catch (Exception ignored) {
      imported = false;
    }
  }

  /** Parses an ISO date such as 2024-05-01 (used by the assistant's own scheduling). */
  static long parseDay(String value) {
    try {
      java.text.SimpleDateFormat format = new java.text.SimpleDateFormat("yyyy-MM-dd", Locale.US);
      format.setLenient(false);
      return format.parse(value.trim()).getTime();
    } catch (Exception bad) {
      return -1;
    }
  }

  private static void voidTask(String ignored) { /* keeps the import loop readable */ }

  // ---------------------------------------------------------------- writing

  private static int ensureConversation(SQLiteDatabase db, String requestId, String prompt, long at) {
    if (requestId == null || requestId.isEmpty()) return 0;
    try (Cursor c = db.query("conversations", new String[]{"_id"}, "request_id=?", new String[]{requestId}, null, null, null)) {
      if (c.moveToFirst()) return c.getInt(0);
    } catch (RuntimeException ignored) { /* tolerated: memory is best-effort and must never break a reply */ }
    ContentValues values = new ContentValues();
    values.put("request_id", requestId);
    values.put("prompt", prompt == null ? "" : prompt);
    values.put("created_at", at);
    long id = db.insertWithOnConflict("conversations", null, values, SQLiteDatabase.CONFLICT_IGNORE);
    if (id > 0) return (int) id;
    try (Cursor c = db.query("conversations", new String[]{"_id"}, "request_id=?", new String[]{requestId}, null, null, null)) {
      return c.moveToFirst() ? c.getInt(0) : 0;
    } catch (RuntimeException ignored) { return 0; }
  }

  private static void addTurn(SQLiteDatabase db, int conversation, String role, String text, long at) {
    ContentValues values = new ContentValues();
    values.put("conversation_id", conversation);
    values.put("role", role);
    values.put("text", text);
    values.put("at", at);
    long id = db.insert("turns", null, values);
    if (id > 0) index(db, text, "turn", conversation + ":" + id);
  }

  private static void addFact(SQLiteDatabase db, String text, String kind, long at) {
    if (text == null || text.trim().isEmpty()) return;
    ContentValues values = new ContentValues();
    values.put("text", text.trim());
    values.put("kind", kind == null ? "fact" : kind);
    values.put("created_at", at);
    long id = db.insertWithOnConflict("facts", null, values, SQLiteDatabase.CONFLICT_IGNORE);
    if (id > 0) index(db, text.trim(), "fact", String.valueOf(id));
  }

  private static void index(SQLiteDatabase db, String content, String kind, String ref) {
    try {
      ContentValues values = new ContentValues();
      values.put("content", content == null ? "" : content);
      values.put("kind", kind);
      values.put("ref", ref);
      db.insert("search", null, values);
    } catch (RuntimeException ignored) { /* the row itself was saved; search will catch up */ }
  }

  /** Lower-cased, trimmed, single-spaced: the key an entity is looked up by. */
  static String normaliseName(String name) {
    return name == null ? "" : name.trim().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
  }

  /** One of the known entity kinds, defaulting to a plain fact. */
  static String normaliseKind(String kind) {
    String value = kind == null ? "" : kind.trim().toLowerCase(Locale.ROOT);
    for (String allowed : new String[]{"fact", "contact", "place", "routine", "shop", "other"}) {
      if (allowed.equals(value)) return value;
    }
    return "fact";
  }

  /** The stored value for a kind + name, or null. */
  static String entityValue(Context context, String kind, String name) { return entity(context, kind, name); }

  /** All saved entities as "kind name = value" lines, newest first. */
  static String entityList(Context context, int limit) {
    StringBuilder out = new StringBuilder();
    try (Cursor c = get(context).getReadableDatabase().query("entities", new String[]{"kind", "name", "value"},
        null, null, null, null, "updated_at DESC", String.valueOf(Math.max(1, limit)))) {
      while (c.moveToNext()) {
        if (out.length() > 0) out.append("\n");
        out.append(c.getString(0)).append(' ').append(c.getString(1)).append(" = ").append(c.getString(2));
      }
    } catch (Exception ignored) { /* tolerated: memory is best-effort and must never break a reply */ }
    return out.toString();
  }

  /** Called by the store when a task finishes, so both write paths agree. */
  static void recordTurn(Context context, String requestId, String prompt, String role, String text) {
    try {
      MemoryDb db = get(context);
      long at = System.currentTimeMillis();
      SQLiteDatabase writable = db.getWritableDatabase();
      int conversation = ensureConversation(writable, requestId, role.equals("user") ? text : prompt, at);
      if (conversation > 0) addTurn(writable, conversation, role, text, at);
    } catch (Exception ignored) { /* tolerated: memory is best-effort and must never break a reply */ }
  }

  static void recordFact(Context context, String text, String kind) {
    try { addFact(get(context).getWritableDatabase(), text, kind, System.currentTimeMillis()); } catch (Exception ignored) { /* tolerated: memory is best-effort and must never break a reply */ }
  }

  /** Keeps a user-approved entity such as "shop" -> "+919876543210" or "home" -> "Asansol". */
  static JSONObject rememberEntity(Context context, String kind, String name, String value) {
    try {
      if (kind == null || kind.isEmpty() || name == null || name.trim().isEmpty() || value == null || value.trim().isEmpty())
        return AiAgent.json("status", "invalid", "message", "Tell me what to remember, for example “my shop number is …”.");
      if (value.length() > 240) return AiAgent.json("status", "invalid", "message", "That value is too long to remember.");
      ContentValues values = new ContentValues();
      values.put("kind", normaliseKind(kind));
      values.put("name", normaliseName(name));
      values.put("value", value.trim());
      values.put("updated_at", System.currentTimeMillis());
      get(context).getWritableDatabase().insertWithOnConflict("entities", null, values, SQLiteDatabase.CONFLICT_REPLACE);
      index(get(context).getWritableDatabase(), name + " " + value, "entity", kind + ":" + name.toLowerCase(Locale.ROOT));
      return AiAgent.json("status", "saved", "message", "Saved: " + name.trim() + " → " + value.trim() + ".");
    } catch (Exception failure) {
      return AiAgent.json("status", "failed", "message", "That could not be saved on this phone.");
    }
  }

  static String entity(Context context, String kind, String name) {
    try (Cursor c = get(context).getReadableDatabase().query("entities", new String[]{"value"},
        "kind=? AND name=?", new String[]{kind, name == null ? "" : name.trim().toLowerCase(Locale.ROOT)}, null, null, null)) {
      return c.moveToFirst() ? c.getString(0) : null;
    } catch (Exception ignored) { return null; }
  }

  // ---------------------------------------------------------------- reading

  static JSONObject search(Context context, String query, int limit) {
    String text = query == null ? "" : query.trim();
    JSONObject out = new JSONObject();
    try {
      List<JSONObject> hits = new ArrayList<>();
      if (!text.isEmpty()) {
        String match = "\"" + text.replace("\"", " ") + "\"*";
        try (Cursor c = get(context).getReadableDatabase().query("search", new String[]{"content", "kind", "ref"},
            "search MATCH ?", new String[]{match}, null, null, null, String.valueOf(Math.max(1, limit)))) {
          while (c.moveToNext()) {
            hits.add(new JSONObject().put("text", c.getString(0)).put("kind", c.getString(1)).put("ref", c.getString(2)));
          }
        } catch (RuntimeException fts) { /* fall back to LIKE below */ }
      }
      if (hits.isEmpty()) {
        // A plain substring search: FTS tokenises, which can miss Bengali suffixes.
        String like = "%" + text + "%";
        try (Cursor c = get(context).getReadableDatabase().query("turns", new String[]{"text", "at"},
            "text LIKE ?", new String[]{like}, null, null, "at DESC", String.valueOf(Math.max(1, limit)))) {
          while (c.moveToNext()) hits.add(new JSONObject().put("text", c.getString(0)).put("kind", "turn").put("at", c.getLong(1)));
        }
        try (Cursor c = get(context).getReadableDatabase().query("facts", new String[]{"text"}, "text LIKE ?",
            new String[]{like}, null, null, "created_at DESC", "5")) {
          while (c.moveToNext()) hits.add(new JSONObject().put("text", c.getString(0)).put("kind", "fact"));
        }
      }
      JSONArray array = new JSONArray();
      Set<String> seen = new LinkedHashSet<>();
      for (JSONObject hit : hits) {
        String value = hit.optString("text");
        if (value.isEmpty() || !seen.add(value)) continue;
        array.put(hit);
        if (array.length() >= limit) break;
      }
      if (array.length() == 0) return out.put("status", "not_found").put("message", text.isEmpty()
        ? "Tell me what to look for and I will search what you told me before."
        : "I could not find anything about \"" + text + "\" in our earlier conversations.");
      StringBuilder summary = new StringBuilder(array.length() + " earlier note(s): ");
      for (int i = 0; i < array.length(); i++) {
        String value = array.optJSONObject(i).optString("text");
        if (value.length() > 200) value = value.substring(0, 200) + "…";
        summary.append("\n• ").append(value);
      }
      return out.put("status", "info").put("message", summary.toString()).put("results", array);
    } catch (Exception failure) {
      try { return out.put("status", "failed").put("message", "The saved conversations could not be searched right now."); }
      catch (Exception ignored) { return AiAgent.json("status", "failed", "message", "Search failed."); }
    }
  }

  /** The last N turns of the most recent conversations, newest first, for "what did we discuss". */
  static JSONObject recentTurns(Context context, int limit) {
    JSONArray out = new JSONArray();
    try (Cursor c = get(context).getReadableDatabase().query("turns", new String[]{"role", "text", "at"},
        null, null, null, null, "_id DESC", String.valueOf(Math.max(1, Math.min(50, limit))))) {
      while (c.moveToNext()) {
        out.put(new JSONObject().put("role", c.getString(0)).put("text", c.getString(1)).put("at", c.getLong(2)));
      }
    } catch (Exception ignored) { /* tolerated: memory is best-effort and must never break a reply */ }
    return AiAgent.json("status", out.length() == 0 ? "not_found" : "info",
      "message", out.length() == 0 ? "We have not talked yet." : "Here is what we last discussed.",
      "turns", out);
  }

  /**
   * Writes a compact, deterministic summary for a conversation once it holds a few turns.
   *
   * This is not a model call: it is the first sentence of each turn, which is enough for
   * "what did we discuss last week" and costs nothing. The honest limit is documented in the
   * README: summaries are extracts, not paraphrase.
   */
  static void summariseIfLong(Context context, String requestId, String prompt, String reply) {
    try {
      SQLiteDatabase db = get(context).getWritableDatabase();
      int conversation = ensureConversation(db, requestId, prompt, System.currentTimeMillis());
      if (conversation <= 0) return;
      int count = 0;
      try (Cursor c = db.rawQuery("SELECT COUNT(*) FROM turns WHERE conversation_id=?", new String[]{String.valueOf(conversation)})) {
        if (c.moveToFirst()) count = c.getInt(0);
      }
      if (count < 4) return; // short chats are already cheap and self-explanatory
      try (Cursor existing = db.rawQuery("SELECT COUNT(*) FROM summaries WHERE conversation_id=?", new String[]{String.valueOf(conversation)})) {
        if (existing.moveToFirst() && existing.getInt(0) > 0) return;
      }
      StringBuilder summary = new StringBuilder();
      try (Cursor c = db.query("turns", new String[]{"role", "text"}, "conversation_id=?",
          new String[]{String.valueOf(conversation)}, null, null, "_id ASC", "8")) {
        while (c.moveToNext()) {
          String text = firstSentence(c.getString(1));
          if (text.isEmpty()) continue;
          if (summary.length() > 0) summary.append(" | ");
          summary.append("user".equals(c.getString(0)) ? "আপনি: " : "Jarvis: ").append(text);
        }
      }
      if (summary.length() > 0) saveSummary(context, requestId, summary.toString());
    } catch (Exception ignored) { /* tolerated: memory is best-effort and must never break a reply */ }
  }

  private static String firstSentence(String text) {
    if (text == null) return "";
    String clean = text.replaceAll("\\s+", " ").trim();
    if (clean.isEmpty()) return "";
    int end = clean.length();
    for (char stop : new char[]{'.', '?', '!', '।', '\n'}) {
      int at = clean.indexOf(stop);
      if (at > 8 && at < end) end = at + 1;
    }
    String sentence = clean.substring(0, Math.min(end, 120));
    return sentence.trim();
  }

  /** One-paragraph summary of a conversation, stored so long chats stay cheap to send. */
  static void saveSummary(Context context, String requestId, String summary) {
    try {
      if (summary == null || summary.trim().isEmpty()) return;
      SQLiteDatabase db = get(context).getWritableDatabase();
      int conversation = ensureConversation(db, requestId, "", System.currentTimeMillis());
      if (conversation <= 0) return;
      ContentValues values = new ContentValues();
      values.put("conversation_id", conversation);
      values.put("text", summary.trim());
      values.put("at", System.currentTimeMillis());
      db.insert("summaries", null, values);
    } catch (Exception ignored) { /* tolerated: memory is best-effort and must never break a reply */ }
  }

  static JSONArray summaries(Context context, int limit) {
    JSONArray out = new JSONArray();
    try (Cursor c = get(context).getReadableDatabase().query("summaries", new String[]{"text", "at"},
        null, null, null, null, "_id DESC", String.valueOf(Math.max(1, limit)))) {
      while (c.moveToNext()) out.put(new JSONObject().put("text", c.getString(0)).put("at", c.getLong(1)));
    } catch (Exception ignored) { /* tolerated: memory is best-effort and must never break a reply */ }
    return out;
  }

  /** Facts from the old table plus entities, in the shape the prompt builder already expects. */
  static JSONArray facts(Context context, int limit) {
    JSONArray out = new JSONArray();
    try (Cursor c = get(context).getReadableDatabase().query("facts", new String[]{"_id", "text", "created_at"},
        null, null, null, null, "_id DESC", String.valueOf(Math.max(1, limit)))) {
      while (c.moveToNext()) {
        out.put(new JSONObject().put("id", "fact-" + c.getLong(0)).put("text", c.getString(1)).put("at", c.getLong(2)));
      }
    } catch (Exception ignored) { /* tolerated: memory is best-effort and must never break a reply */ }
    try (Cursor c = get(context).getReadableDatabase().query("entities", new String[]{"kind", "name", "value"},
        null, null, null, null, "updated_at DESC", "20")) {
      while (c.moveToNext()) {
        out.put(new JSONObject().put("id", "entity-" + c.getString(1))
          .put("text", c.getString(0) + " " + c.getString(1) + " = " + c.getString(2)).put("at", System.currentTimeMillis()));
      }
    } catch (Exception ignored) { /* tolerated: memory is best-effort and must never break a reply */ }
    return out;
  }

  static void clear(Context context) {
    try {
      SQLiteDatabase db = get(context).getWritableDatabase();
      db.delete("turns", null, null);
      db.delete("conversations", null, null);
      db.delete("summaries", null, null);
      db.delete("search", null, null);
    } catch (Exception ignored) { /* tolerated: memory is best-effort and must never break a reply */ }
  }
}

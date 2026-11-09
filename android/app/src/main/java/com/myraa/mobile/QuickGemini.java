package com.myraa.mobile;

import org.json.JSONArray;
import org.json.JSONObject;

import javax.net.ssl.HttpsURLConnection;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * One short, non-streaming question to Gemini, used where there is no room for the full agent
 * (a live call, a spoken one-liner).
 *
 * It is separate from {@link AiAgent} on purpose: the agent loop streams, keeps history, exposes
 * phone tools and saves a task. A call needs the opposite: a fast answer, no tools (a live call
 * must never press buttons on the phone), no history, and a hard character limit so the answer can
 * be spoken before the other person thinks the line dropped.
 */
final class QuickGemini {

  interface Connections { HttpsURLConnection open(URL url) throws java.io.IOException; }

  private static final int MAX_ANSWER = 600;

  private final Connections connections;

  QuickGemini() { this(url -> (HttpsURLConnection) url.openConnection()); }
  QuickGemini(Connections connections) { this.connections = connections; }

  /**
   * The request body. Built separately so the shape can be tested without a network.
   */
  static JSONObject body(String instruction, String conversation) throws Exception {
    String rule = instruction == null ? "" : instruction.trim();
    return new JSONObject()
      .put("systemInstruction", new JSONObject().put("parts",
        new JSONArray().put(new JSONObject().put("text", rule))))
      .put("contents", new JSONArray().put(new JSONObject()
        .put("role", "user")
        .put("parts", new JSONArray().put(new JSONObject().put("text", conversation == null ? "" : conversation)))))
      .put("generationConfig", new JSONObject().put("maxOutputTokens", 400).put("temperature", 0.6));
  }

  /** Reads the answer text out of a non-streaming generateContent response. */
  static String answer(String payload) {
    try {
      JSONArray candidates = new JSONObject(payload).optJSONArray("candidates");
      JSONObject first = candidates == null ? null : candidates.optJSONObject(0);
      JSONObject content = first == null ? null : first.optJSONObject("content");
      JSONArray parts = content == null ? null : content.optJSONArray("parts");
      if (parts == null) return "";
      StringBuilder text = new StringBuilder();
      for (int i = 0; i < parts.length(); i++) {
        JSONObject part = parts.optJSONObject(i);
        if (part == null || part.optBoolean("thought", false)) continue;
        String piece = part.optString("text", "");
        if (piece.isEmpty()) continue;
        if (text.length() + piece.length() > MAX_ANSWER) break;
        text.append(piece);
      }
      return SpeechEngine.speakable(text.toString());
    } catch (Exception malformed) {
      return "";
    }
  }

  /**
   * The spoken answer, or an empty string. Never throws: the call screen shows the failure and
   * keeps the call going, because losing the answer must never drop the call.
   */
  String ask(String key, String model, String instruction, String conversation, String taskTag) {
    HttpsURLConnection connection = null;
    try {
      String safeModel = model != null && model.matches("[A-Za-z0-9._-]{1,100}") ? model : "gemini-2.5-flash";
      connection = connections.open(new URL("https://generativelanguage.googleapis.com/v1beta/models/"
        + safeModel + ":generateContent"));
      connection.setInstanceFollowRedirects(false);
      connection.setRequestMethod("POST");
      connection.setConnectTimeout(8000);
      connection.setReadTimeout(12000);
      connection.setDoOutput(true);
      connection.setRequestProperty("Content-Type", "application/json");
      connection.setRequestProperty("x-goog-api-key", key);
      byte[] payload = body(instruction, conversation).toString().getBytes(StandardCharsets.UTF_8);
      try (OutputStream out = connection.getOutputStream()) { out.write(payload); }
      int status = connection.getResponseCode();
      if (status < 200 || status >= 300) return "";
      String text = read(connection.getInputStream());
      String answer = answer(text);
      return answer.isEmpty() ? "" : (answer.length() > MAX_ANSWER ? answer.substring(0, MAX_ANSWER) : answer);
    } catch (Exception failure) {
      return "";
    } finally {
      if (connection != null) connection.disconnect();
    }
  }

  private static String read(InputStream stream) throws Exception {
    if (stream == null) return "";
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    byte[] buffer = new byte[8192];
    int read;
    while ((read = stream.read(buffer)) > 0) {
      out.write(buffer, 0, read);
      if (out.size() > 262144) break;
    }
    return new String(out.toByteArray(), StandardCharsets.UTF_8);
  }
}

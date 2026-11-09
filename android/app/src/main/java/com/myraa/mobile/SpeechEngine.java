package com.myraa.mobile;

import android.os.Bundle;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Speaks replies of any length.
 *
 * The old code truncated the reply at {@link TextToSpeech#getMaxSpeechInputLength()} (about
 * 4,000 characters), so long answers were silently cut off. Android's engine limit is per
 * utterance, not per reply, so the text is split at sentence boundaries (falling back to
 * commas, then spaces) and queued. Nothing is dropped, and the speaking state stays active
 * until the very last chunk is done.
 */
final class SpeechEngine {

  interface Listener {
    void onStart();
    void onDone();
    void onError(String message);
  }

  private final TextToSpeech tts;
  private Listener listener;
  private final String runId = UUID.randomUUID().toString();
  private int total;
  private int finished;
  private boolean active;

  SpeechEngine(TextToSpeech tts) {
    this.tts = tts;
  }

  void setListener(Listener listener) {
    this.listener = listener;
    if (tts == null) return;
    tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
      @Override public void onStart(String id) {
        if (!belongsToRun(id)) return;
        if (!active) { active = true; if (SpeechEngine.this.listener != null) SpeechEngine.this.listener.onStart(); }
      }
      @Override public void onDone(String id) { complete(id, false); }
      @Override public void onError(String id) { complete(id, true); }
      @Override public void onStop(String id, boolean interrupted) {
        if (!belongsToRun(id)) return;
        active = false;
        if (SpeechEngine.this.listener != null) SpeechEngine.this.listener.onDone();
      }
    });
  }

  private boolean belongsToRun(String id) { return id != null && id.startsWith(runId + "-"); }

  private synchronized void complete(String id, boolean failed) {
    if (!belongsToRun(id)) return;
    finished++;
    if (failed && listener != null) listener.onError("The installed speech engine could not speak one part of the reply.");
    if (finished >= total) {
      active = false;
      if (listener != null) listener.onDone();
    }
  }

  /** Starts speaking, replacing whatever was being said. Returns false when the text is empty. */
  synchronized boolean speak(String text) {
    if (tts == null) return false;
    List<String> chunks = split(text);
    if (chunks.isEmpty()) return false;
    tts.stop();
    BeepMuter.unmuteNow();
    total = chunks.size();
    finished = 0;
    active = true;
    if (listener != null) listener.onStart();
    int max = Math.max(200, TextToSpeech.getMaxSpeechInputLength() - 64);
    for (int i = 0; i < chunks.size(); i++) {
      String chunk = chunks.get(i);
      Bundle params = new Bundle();
      params.putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, 1f);
      int queue = i == 0 ? TextToSpeech.QUEUE_FLUSH : TextToSpeech.QUEUE_ADD;
      int result = tts.speak(chunk.length() > max ? chunk.substring(0, max) : chunk, queue, params, runId + "-" + i);
      if (result == TextToSpeech.ERROR) {
        total = i;
        if (total == 0) { active = false; if (listener != null) listener.onError("The installed speech engine could not start."); return false; }
      }
    }
    return true;
  }

  /** Hard stop (screen closed, user pressed stop, new task). */
  synchronized void stop() {
    active = false;
    total = 0;
    finished = 0;
    if (tts != null) tts.stop();
  }

  void shutdown() {
    try { if (tts != null) tts.shutdown(); } catch (RuntimeException ignored) { }
  }

  /**
   * Splits at sentence ends, then clause ends, then word boundaries, never above {@code max}.
   * Bengali danda (।), question and exclamation marks are sentence ends too.
   */
  static List<String> split(String text) {
    List<String> chunks = new ArrayList<>();
    if (text == null) return chunks;
    String clean = text.replaceAll("[ \\t]+", " ").replaceAll("\\n{2,}", "\n").trim();
    if (clean.isEmpty()) return chunks;
    int max = Math.max(200, TextToSpeech.getMaxSpeechInputLength() - 64);
    for (String paragraph : clean.split("\\n+")) {
      String remaining = paragraph.trim();
      while (remaining.length() > max) {
        int cut = sentenceBreak(remaining, max);
        if (cut <= 0) cut = clauseBreak(remaining, max);
        if (cut <= 0) cut = remaining.lastIndexOf(' ', max);
        if (cut <= 0) cut = max;
        add(chunks, remaining.substring(0, cut));
        remaining = remaining.substring(cut).trim();
      }
      add(chunks, remaining);
    }
    return chunks;
  }

  private static int sentenceBreak(String text, int max) {
    int best = -1;
    for (int i = 0; i < Math.min(max, text.length()); i++) {
      char c = text.charAt(i);
      if (c == '.' || c == '!' || c == '?' || c == '।' || c == '\u0964') best = i + 1;
    }
    return best;
  }

  private static int clauseBreak(String text, int max) {
    int best = -1;
    for (int i = 0; i < Math.min(max, text.length()); i++) {
      char c = text.charAt(i);
      if (c == ',' || c == ';' || c == ':' || c == '—' || c == '-') best = i + 1;
    }
    return best;
  }

  private static void add(List<String> chunks, String piece) {
    String value = piece == null ? "" : piece.trim();
    if (!value.isEmpty()) chunks.add(value);
  }
}

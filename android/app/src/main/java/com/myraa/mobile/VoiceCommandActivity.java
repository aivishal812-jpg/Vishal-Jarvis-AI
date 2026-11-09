package com.myraa.mobile;

import android.Manifest;
import android.app.Activity;
import android.app.KeyguardManager;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.speech.tts.TextToSpeech;
import android.view.Gravity;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import org.json.JSONException;
import org.json.JSONObject;
import javax.net.ssl.HttpsURLConnection;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;

/**
 * Hands-free "Hey Jarvis" popup. A small card at the bottom of the screen listens, runs the
 * command with the same Gemini agent and phone tools, speaks a short answer and closes.
 * It never opens the main Jarvis screen and can answer on top of the lock screen.
 */
public final class VoiceCommandActivity extends Activity {
  static final String EXTRA_COMMAND = "com.myraa.mobile.COMMAND";
  static volatile boolean active;
  /** Safe to run while the phone stays locked; everything else asks the user to unlock first. */
  private static final Set<String> SAFE_WHEN_LOCKED = new HashSet<>(Arrays.asList(
    "set_flashlight", "set_volume", "media_control", "device_status", "get_weather", "set_reminder",
    "ring_phone"));
  private static final int MAX_FOLLOW_UPS = 3;

  private final Handler ui = new Handler(Looper.getMainLooper());
  private final ExecutorService worker = Executors.newSingleThreadExecutor();
  private final Map<String, Waiter> waiters = new ConcurrentHashMap<>();
  private TextView heard, answer;
  private SpeechRecognizer recognizer;
  private TextToSpeech tts;
  private SpeechEngine speech;
  private boolean ttsReady, resumed, closed, working;
  private String waitingToSpeak;
  private int followUps;
  private MobileActions actions;
  private volatile HttpsURLConnection connection;
  private volatile boolean cancelled;

  private static final class Waiter {
    final CountDownLatch latch = new CountDownLatch(1); volatile JSONObject value;
    void done(JSONObject v) { value = v; latch.countDown(); }
  }

  @Override protected void onCreate(Bundle state) {
    super.onCreate(state);
    active = true;
    if (JarvisPrefs.lockScreen(this)) { setShowWhenLocked(true); setTurnScreenOn(true); }
    getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    buildCard();
    tts = new TextToSpeech(this, status -> {
      ttsReady = status == TextToSpeech.SUCCESS;
      if (ttsReady) {
        speech = new SpeechEngine(tts);
        speech.setListener(new SpeechEngine.Listener() {
          @Override public void onStart() { AiTaskEvents.speaking = true; }
          @Override public void onDone() { AiTaskEvents.speaking = false; ui.post(VoiceCommandActivity.this::afterSpeech); }
          @Override public void onError(String message) { AiTaskEvents.speaking = false; ui.post(VoiceCommandActivity.this::afterSpeech); }
        });
      }
      String pending = waitingToSpeak; waitingToSpeak = null;
      if (pending != null) speak(pending);
    });
    actions = new MobileActions(this, (id, result) -> { Waiter w = waiters.remove(id); if (w != null) w.done(result); });
    actions.setSpeech(text -> { });
    start(getIntent());
  }

  @Override protected void onNewIntent(Intent intent) { super.onNewIntent(intent); setIntent(intent); if (!working) { followUps = 0; start(intent); } }

  private void start(Intent intent) {
    if (!JarvisPrefs.get(this).contains("keyCipher")) {
      say("আগে Jarvis অ্যাপের Settings-এ Gemini API key দিন।", "Add your Gemini API key in the Jarvis app first.");
      return;
    }
    String command = intent == null ? "" : intent.getStringExtra(EXTRA_COMMAND);
    if (intent != null) intent.removeExtra(EXTRA_COMMAND);
    command = command == null ? "" : command.trim();
    if (command.isEmpty()) listen(); else handle(command);
  }

  private boolean bengali() { return JarvisPrefs.get(this).getString("language", "bn-IN").startsWith("bn"); }
  private String pick(String bn, String en) { return bengali() ? bn : en; }

  // ------------------------------------------------------------------ small bottom card

  private void buildCard() {
    FrameLayout root = new FrameLayout(this);
    root.setBackgroundColor(Color.argb(90, 0, 0, 0));
    root.setOnClickListener(v -> close());
    LinearLayout card = new LinearLayout(this);
    card.setOrientation(LinearLayout.VERTICAL);
    int pad = dp(18);
    card.setPadding(pad, dp(14), pad, dp(16));
    GradientDrawable bg = new GradientDrawable(); bg.setColor(Color.rgb(12, 20, 36)); bg.setCornerRadius(dp(22)); bg.setStroke(dp(1), Color.argb(120, 56, 189, 248));
    card.setBackground(bg);
    card.setOnClickListener(v -> { });
    TextView title = new TextView(this); title.setText("Vishal Jarvis"); title.setTextColor(Color.rgb(56, 189, 248)); title.setTextSize(13); title.setTypeface(Typeface.DEFAULT_BOLD);
    heard = new TextView(this); heard.setTextColor(Color.rgb(150, 170, 196)); heard.setTextSize(14); heard.setPadding(0, dp(6), 0, 0);
    answer = new TextView(this); answer.setTextColor(Color.WHITE); answer.setTextSize(18); answer.setPadding(0, dp(6), 0, 0);
    card.addView(title); card.addView(heard); card.addView(answer);
    FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM);
    params.setMargins(dp(12), 0, dp(12), dp(28));
    root.addView(card, params);
    setContentView(root);
  }
  private int dp(int v) { return Math.round(v * getResources().getDisplayMetrics().density); }

  // ------------------------------------------------------------------ listening

  private void listen() {
    if (closed) return;
    if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
      say("মাইক্রোফোনের অনুমতি দিন।", "Please allow the microphone."); return;
    }
    if (!SpeechRecognizer.isRecognitionAvailable(this)) { say("এই ফোনে স্পিচ রিকগনাইজার নেই।", "No speech recognizer on this phone."); return; }
    heard.setText(""); answer.setText(pick("শুনছি…", "Listening…"));
    releaseRecognizer();
    recognizer = SpeechRecognizer.createSpeechRecognizer(this);
    recognizer.setRecognitionListener(new RecognitionListener() {
      public void onReadyForSpeech(Bundle b) { BeepMuter.unmuteLater(700); }
      public void onBeginningOfSpeech() { }
      public void onRmsChanged(float v) { }
      public void onBufferReceived(byte[] b) { }
      public void onEndOfSpeech() { BeepMuter.mute(VoiceCommandActivity.this); answer.setText(pick("ভাবছি…", "Thinking…")); }
      public void onPartialResults(Bundle b) { String t = best(b); if (!t.isEmpty()) heard.setText(t); }
      public void onEvent(int t, Bundle b) { }
      public void onError(int error) {
        releaseRecognizer(); BeepMuter.unmuteLater(500);
        if (followUps > 0 || error == SpeechRecognizer.ERROR_NO_MATCH || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT) { close(); return; }
        say("শুনতে পেলাম না।", "I couldn't hear that.");
      }
      public void onResults(Bundle b) {
        releaseRecognizer(); BeepMuter.unmuteLater(500);
        String text = best(b);
        if (text.isEmpty()) { close(); return; }
        handle(text);
      }
    });
    String language = JarvisPrefs.get(this).getString("language", "bn-IN");
    Intent intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
      .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
      .putExtra(RecognizerIntent.EXTRA_LANGUAGE, language)
      .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
      .putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, getPackageName());
    try { BeepMuter.mute(this); recognizer.startListening(intent); } catch (RuntimeException e) { say("মাইক্রোফোন চালু হলো না।", "The microphone could not start."); }
  }

  private static String best(Bundle bundle) {
    ArrayList<String> list = bundle == null ? null : bundle.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
    return list == null || list.isEmpty() || list.get(0) == null ? "" : list.get(0).trim();
  }

  private void releaseRecognizer() {
    if (recognizer != null) { try { recognizer.cancel(); recognizer.destroy(); } catch (RuntimeException ignored) { } recognizer = null; }
  }

  // ------------------------------------------------------------------ running a command

  private void handle(String raw) {
    String text = stripWakeWord(raw);
    heard.setText(raw);
    if (text.isEmpty()) { listen(); return; }
    if (text.matches("(?iu)^(stop|cancel|never ?mind|thank you|thanks|ok|okay|থাক|থামো|বন্ধ করো|ঠিক আছে|ধন্যবাদ|কিছু না|बस|रुको|धन्यवाद)[.!।]*$")) {
      answer.setText(pick("ঠিক আছে।", "Okay.")); ui.postDelayed(this::close, 700); return;
    }
    answer.setText(pick("ভাবছি…", "Thinking…"));
    working = true;
    final String command = text;
    worker.execute(() -> {
      String reply;
      AiTaskStore store = AiTaskStore.get(this);
      String id = "voice-" + UUID.randomUUID();
      try {
        store.enqueue(id, command);
        if (!AiTaskCoordinator.claimVoice(store, id)) {
          store.fail(id, "Another reply is running.");
          reply = pick("আরেকটা উত্তর চলছে, একটু পরে বলুন।", "Another reply is running. Try again in a moment.");
        } else {
          try {
            reply = new AiAgent(this, id, new AiAgent.Listener() {
              public boolean cancelled() { return cancelled; }
              public void connection(HttpsURLConnection c) { connection = c; if (cancelled && c != null) c.disconnect(); }
              public void event(JSONObject event) { }
              public JSONObject tool(String name, JSONObject args) { return runTool(name, args); }
            }).voiceMode(true).run(store.find(id));
            store.complete(id, reply);
          } finally { AiTaskCoordinator.endForeground(id); }
        }
      } catch (Exception failure) {
        store.fail(id, AiAgent.safeMessage(failure));
        reply = failure instanceof GeminiAccess.Failure ? failure.getMessage()
          : pick("ইন্টারনেট বা Gemini-তে সমস্যা হলো, আবার বলুন।", "There was a connection problem. Please try again.");
      }
      final String spoken = reply == null ? "" : reply;
      ui.post(() -> { working = false; if (!closed) say(spoken, spoken); });
    });
  }

  static String stripWakeWord(String raw) {
    if (raw == null) return "";
    String text = raw.trim();
    Matcher m = WakeWordService.WAKE.matcher(text.toLowerCase(Locale.ROOT));
    if (m.find() && m.start() <= 12) text = text.substring(Math.min(text.length(), m.end()));
    return text.replaceFirst("^[\\s,.!?।:-]+", "").replaceFirst("(?iu)^(?:hey|hi|হেই|হে|हे)\\s+", "").trim();
  }

  private JSONObject runTool(String name, JSONObject args) {
    if (MobileTools.REMEMBER.equals(name)) return MemoryStore.save(this, args.optString("fact", ""));
    if (!MobileTools.ACTION.equals(name)) return json("status", "unsupported", "message", "That tool is not available.");
    String action = args.optString("action");
    if (locked() && !SAFE_WHEN_LOCKED.contains(action) && !unlock()) {
      return json("status", "locked", "message", "The phone is locked and the user did not unlock it, so nothing was done. Ask them to unlock and try again.");
    }
    String id = "voice-tool-" + UUID.randomUUID();
    Waiter waiter = new Waiter(); waiters.put(id, waiter);
    String payload = args.toString();
    ui.post(() -> actions.execute(id, MobileTools.ACTION, payload));
    try {
      if (!waiter.latch.await(45, TimeUnit.SECONDS)) { ui.post(() -> actions.cancel(id)); return json("status", "timeout", "message", "That action did not finish in time."); }
      return waiter.value == null ? json("status", "failed", "message", "Android returned no result.") : waiter.value;
    } catch (InterruptedException e) { Thread.currentThread().interrupt(); return json("status", "cancelled", "message", "Cancelled."); }
    finally { waiters.remove(id); }
  }

  private boolean locked() {
    KeyguardManager keyguard = getSystemService(KeyguardManager.class);
    return keyguard != null && keyguard.isKeyguardLocked();
  }

  /** Shows the normal unlock prompt (fingerprint / PIN). Returns true once the phone is unlocked. */
  private boolean unlock() {
    KeyguardManager keyguard = getSystemService(KeyguardManager.class);
    if (keyguard == null) return false;
    CountDownLatch latch = new CountDownLatch(1);
    final boolean[] ok = {false};
    ui.post(() -> {
      answer.setText(pick("ফোন আনলক করুন…", "Unlock the phone…"));
      try {
        keyguard.requestDismissKeyguard(this, new KeyguardManager.KeyguardDismissCallback() {
          @Override public void onDismissSucceeded() { ok[0] = true; latch.countDown(); }
          @Override public void onDismissCancelled() { latch.countDown(); }
          @Override public void onDismissError() { latch.countDown(); }
        });
      } catch (RuntimeException e) { latch.countDown(); }
    });
    try { latch.await(40, TimeUnit.SECONDS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    return ok[0] || !locked();
  }

  // ------------------------------------------------------------------ speaking

  private void say(String bn, String en) {
    String text = clean(pick(bn, en));
    answer.setText(text);
    speak(text);
  }

  private void speak(String text) {
    if (closed) return;
    BeepMuter.unmuteNow(); // the reply must always be audible
    if (tts == null || !ttsReady) {
      if (tts != null && waitingToSpeak == null) { waitingToSpeak = text; ui.postDelayed(() -> { if (waitingToSpeak != null) { waitingToSpeak = null; afterSpeech(); } }, 4000); return; }
      ui.postDelayed(this::afterSpeech, 2500); return;
    }
    tts.setLanguage(Locale.forLanguageTag(JarvisPrefs.get(this).getString("language", "bn-IN")));
    // Full answer, any length: chunked, never truncated.
    if (speech == null || !speech.speak(text)) ui.postDelayed(this::afterSpeech, 1500);
  }

  private static String clean(String text) {
    return text == null ? "" : text.replaceAll("[*_#`>|]+", "").replaceAll("\\[(.*?)\\]\\((.*?)\\)", "$1").replaceAll("\\s+", " ").trim();
  }

  private void afterSpeech() {
    if (closed || working) return;
    String shown = answer.getText().toString().trim();
    boolean question = shown.endsWith("?") || shown.endsWith("？");
    if (question && resumed && followUps < MAX_FOLLOW_UPS) { followUps++; listen(); return; }
    ui.postDelayed(this::close, 900);
  }

  private void close() {
    if (closed) return;
    closed = true; cancelled = true;
    HttpsURLConnection c = connection; if (c != null) try { c.disconnect(); } catch (RuntimeException ignored) { }
    releaseRecognizer();
    finish();
  }

  @Override protected void onResume() { super.onResume(); resumed = true; }
  @Override protected void onPause() { resumed = false; super.onPause(); }

  @Override public void onRequestPermissionsResult(int request, String[] permissions, int[] results) {
    super.onRequestPermissionsResult(request, permissions, results);
    if (request == MobileActions.CONTACTS_REQUEST) actions.permissionResult();
    else if (request == MobileActions.PHONE_REQUEST) actions.phonePermissionResult();
    else if (request == MobileActions.TORCH_REQUEST) actions.torchPermissionResult();
    else if (request == ProActions.CALENDAR_WRITE_REQUEST || request == ProActions.CALENDAR_REQUEST) actions.calendarPermissionResult();
  }

  @Override protected void onDestroy() {
    active = false; closed = true; cancelled = true;
    releaseRecognizer();
    BeepMuter.unmuteLater(500);
    if (speech != null) { try { speech.stop(); } catch (RuntimeException ignored) { } }
    if (tts != null) { try { tts.stop(); tts.shutdown(); } catch (RuntimeException ignored) { } }
    AiTaskEvents.speaking = false;
    try { actions.close(); } catch (RuntimeException ignored) { }
    worker.shutdownNow();
    super.onDestroy();
  }

  private static JSONObject json(Object... values) {
    JSONObject result = new JSONObject();
    try { for (int i = 0; i < values.length; i += 2) result.put(String.valueOf(values[i]), values[i + 1]); } catch (JSONException ignored) { }
    return result;
  }
}

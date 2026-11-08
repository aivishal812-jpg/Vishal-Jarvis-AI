package com.myraa.mobile;

import android.Manifest;
import android.app.*;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.os.*;
import android.provider.Settings;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import java.util.ArrayList;
import java.util.Locale;
import java.util.regex.Pattern;

/** "Hey Jarvis" hands-free wake word.
 *  Uses the phone's own speech recognizer in a loop while Vishal Jarvis is in the background;
 *  audio never leaves the phone unless the phone's recognizer itself is online-only.
 *  Pauses automatically while the app is open or a Live call is running. */
public final class WakeWordService extends Service {
  static final String STOP = "com.myraa.mobile.WAKE_STOP";
  private static final int NOTIFICATION_ID = 512;
  private static final String CHANNEL = "wake_word";
  /** Loose on purpose: recognisers spell "Jarvis" many ways in Bengali, Hindi and English. */
  static final Pattern WAKE = Pattern.compile(
    "(?iu)(j\\s?[ae]+\\s?r?\\s?[vbw][iey]+[szhj]|jarv|jervis|jarbis|garvis|charvis|"
    + "[জযঝ]া?র?্?\\s?[ভব]্?[িী][সশষজ]|জার্ভ|জারভ|"
    + "[जझय]ा?र?्?\\s?[वबभ][िी][सशज]|जार्व|जारव)");
  static volatile boolean running;
  static volatile String lastStatus = "off";
  private final Handler handler = new Handler(Looper.getMainLooper());
  private SpeechRecognizer recognizer;
  private boolean listening;
  private long cooldownUntil;
  private int errorsInRow;
  private String language;
  private String lastHeard = "";
  private String shownText = "";

  static void start(Context context) {
    if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) { lastStatus = "needs microphone permission"; return; }
    try { context.startForegroundService(new Intent(context, WakeWordService.class)); }
    catch (RuntimeException e) { lastStatus = "Android blocked background start; open the app once"; }
  }
  static void stop(Context context) { context.stopService(new Intent(context, WakeWordService.class)); lastStatus = "off"; }

  @Override public IBinder onBind(Intent intent) { return null; }
  @Override public int onStartCommand(Intent intent, int flags, int id) {
    if (intent != null && STOP.equals(intent.getAction())) {
      JarvisPrefs.get(this).edit().putBoolean(JarvisPrefs.WAKE_WORD, false).apply();
      stopForeground(true); stopSelf(); return START_NOT_STICKY;
    }
    try {
      Notification n = foregroundNotification("Say \"Hey Jarvis\" to talk");
      if (Build.VERSION.SDK_INT >= 30) startForeground(NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE);
      else startForeground(NOTIFICATION_ID, n);
    } catch (RuntimeException e) { lastStatus = "Android blocked the microphone in background; open the app once"; stopSelf(); return START_NOT_STICKY; }
    running = true;
    if (!SpeechRecognizer.isRecognitionAvailable(this)) { lastStatus = "no speech recognizer on this phone (install Google app)"; stopSelf(); return START_NOT_STICKY; }
    handler.removeCallbacksAndMessages(null);
    handler.post(this::loop);
    return START_STICKY;
  }

  private Notification foregroundNotification(String text) {
    NotificationManager manager = getSystemService(NotificationManager.class);
    manager.createNotificationChannel(new NotificationChannel(CHANNEL, "Hey Jarvis wake word", NotificationManager.IMPORTANCE_MIN));
    PendingIntent open = PendingIntent.getActivity(this, 11, new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP).putExtra(JarvisPrefs.EXTRA_AUTO_VOICE, true),
      PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    PendingIntent stop = PendingIntent.getService(this, 12, new Intent(this, WakeWordService.class).setAction(STOP), PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    return new Notification.Builder(this, CHANNEL).setSmallIcon(android.R.drawable.ic_btn_speak_now)
      .setContentTitle("Vishal Jarvis is listening for its name").setContentText(text)
      .setContentIntent(open).setOngoing(true).setShowWhen(false)
      .addAction(new Notification.Action.Builder(null, "Turn off", stop).build()).build();
  }

  /** Pause only while the mic is needed elsewhere: a Live call, Jarvis speaking, or right after a wake. */
  private boolean paused() { return LiveVoiceService.running || AiTaskEvents.speaking || SystemClock.elapsedRealtime() < cooldownUntil; }

  /** Show what the wake word is doing in the ongoing notification, so problems are visible. */
  private void status(String text) {
    lastStatus = text;
    String line = text + (lastHeard.isEmpty() ? "" : " · heard: \"" + lastHeard + "\"");
    if (line.equals(shownText)) return;
    shownText = line;
    try { getSystemService(NotificationManager.class).notify(NOTIFICATION_ID, foregroundNotification(line)); } catch (RuntimeException ignored) { }
  }

  private static String errorName(int error) {
    switch (error) {
      case SpeechRecognizer.ERROR_NETWORK: case SpeechRecognizer.ERROR_NETWORK_TIMEOUT: return "no internet for speech recognition";
      case SpeechRecognizer.ERROR_AUDIO: return "microphone is busy in another app";
      case SpeechRecognizer.ERROR_SERVER: return "speech server error";
      case SpeechRecognizer.ERROR_RECOGNIZER_BUSY: return "speech recognizer busy";
      case 10: return "too many requests, slowing down";
      case 11: return "speech server disconnected";
      case 12: case 13: return "language not available, switching to English";
      default: return "speech error " + error;
    }
  }

  private void loop() {
    if (!running) return;
    if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) { lastStatus = "needs microphone permission"; stopSelf(); return; }
    if (paused()) { release(); status(LiveVoiceService.running ? "Paused during the voice call" : "Paused for a moment"); handler.postDelayed(this::loop, 1500); return; }
    if (listening) return;
    try {
      if (recognizer == null) {
        recognizer = SpeechRecognizer.createSpeechRecognizer(this);
        recognizer.setRecognitionListener(listener);
      }
      Intent intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
        .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        .putExtra(RecognizerIntent.EXTRA_LANGUAGE, language())
        .putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, language())
        .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5)
        .putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, getPackageName());
      listening = true;
      recognizer.startListening(intent);
      status("Listening · say \"Jarvis\"");
    } catch (RuntimeException e) { listening = false; release(); handler.postDelayed(this::loop, 5000); }
  }

  private final RecognitionListener listener = new RecognitionListener() {
    public void onReadyForSpeech(Bundle params) { errorsInRow = 0; }
    public void onBeginningOfSpeech() { }
    public void onRmsChanged(float rms) { }
    public void onBufferReceived(byte[] buffer) { }
    public void onEndOfSpeech() { }
    public void onError(int error) {
      listening = false;
      if (error == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS) { lastStatus = "needs microphone permission"; stopSelf(); return; }
      errorsInRow++;
      if ((error == 12 || error == 13) && !"en-IN".equals(language)) { language = "en-IN"; release(); }
      if (error != SpeechRecognizer.ERROR_NO_MATCH && error != SpeechRecognizer.ERROR_SPEECH_TIMEOUT && errorsInRow >= 2) status("Problem: " + errorName(error));
      long delay = error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY ? 2500 : error == SpeechRecognizer.ERROR_NO_MATCH || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT ? 250 : Math.min(30000, 1000L * errorsInRow);
      if (error == SpeechRecognizer.ERROR_CLIENT || error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY) release();
      handler.postDelayed(WakeWordService.this::loop, delay);
    }
    public void onResults(Bundle results) { listening = false; if (!check(results)) handler.postDelayed(WakeWordService.this::loop, 150); }
    public void onPartialResults(Bundle partial) { if (check(partial)) { try { recognizer.cancel(); } catch (RuntimeException ignored) { } listening = false; } }
    public void onEvent(int type, Bundle params) { }
  };

  private boolean check(Bundle bundle) {
    ArrayList<String> heard = bundle == null ? null : bundle.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
    if (heard == null) return false;
    if (!heard.isEmpty() && heard.get(0) != null && !heard.get(0).trim().isEmpty()) {
      String first = heard.get(0).trim();
      lastHeard = first.length() > 40 ? first.substring(0, 40) + "…" : first;
    }
    for (String phrase : heard) {
      if (phrase != null && WAKE.matcher(phrase.toLowerCase(Locale.ROOT)).find()) { wake(); return true; }
    }
    return false;
  }

  private void wake() {
    cooldownUntil = SystemClock.elapsedRealtime() + 8000;
    release();
    try {
      Vibrator vibrator = getSystemService(Vibrator.class);
      if (vibrator != null) vibrator.vibrate(VibrationEffect.createOneShot(60, VibrationEffect.DEFAULT_AMPLITUDE));
    } catch (RuntimeException ignored) { }
    Intent open = new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
      .putExtra(JarvisPrefs.EXTRA_AUTO_VOICE, true);
    boolean started = false;
    if (AiTaskEvents.visible || Build.VERSION.SDK_INT < 29 || Settings.canDrawOverlays(this)) {
      try { startActivity(open); started = true; } catch (RuntimeException ignored) { }
    }
    if (!started) {
      // Without "display over other apps" Android only lets us offer a tap-to-talk alert.
      NotificationManager manager = getSystemService(NotificationManager.class);
      manager.createNotificationChannel(new NotificationChannel("wake_alert", "Jarvis heard you", NotificationManager.IMPORTANCE_HIGH));
      PendingIntent tap = PendingIntent.getActivity(this, 13, open, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
      manager.notify(513, new Notification.Builder(this, "wake_alert").setSmallIcon(android.R.drawable.ic_btn_speak_now)
        .setContentTitle("Yes? Tap to talk to Jarvis").setContentText("Allow \"Display over other apps\" in Jarvis Pro settings to open instantly.")
        .setContentIntent(tap).setFullScreenIntent(tap, true).setAutoCancel(true).setTimeoutAfter(15000).build());
    }
    status("Heard you · opening Jarvis");
    handler.postDelayed(this::loop, 8500);
  }

  private String language() {
    if (language == null) language = JarvisPrefs.get(this).getString("language", "bn-IN");
    return language;
  }

  private void release() {
    listening = false;
    if (recognizer != null) { try { recognizer.cancel(); recognizer.destroy(); } catch (RuntimeException ignored) { } recognizer = null; }
  }
  @Override public void onDestroy() { running = false; handler.removeCallbacksAndMessages(null); release(); if (!"off".equals(lastStatus)) lastStatus = "stopped"; super.onDestroy(); }
}

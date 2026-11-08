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
  private static final Pattern WAKE = Pattern.compile(
    "(?iu)(jarvis|jarvish|jervis|jarbis|জার্ভিস|জারভিস|জার্বিস|জাভিস|जार्विस|जारविस|জার্ভিশ)");
  static volatile boolean running;
  static volatile String lastStatus = "off";
  private final Handler handler = new Handler(Looper.getMainLooper());
  private SpeechRecognizer recognizer;
  private boolean listening;
  private long cooldownUntil;
  private int errorsInRow;

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

  private boolean paused() { return AiTaskEvents.visible || LiveVoiceService.running || SystemClock.elapsedRealtime() < cooldownUntil; }

  private void loop() {
    if (!running) return;
    if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) { lastStatus = "needs microphone permission"; stopSelf(); return; }
    if (paused()) { release(); lastStatus = "paused while the app is open"; handler.postDelayed(this::loop, 2000); return; }
    if (listening) return;
    try {
      if (recognizer == null) {
        recognizer = SpeechRecognizer.createSpeechRecognizer(this);
        recognizer.setRecognitionListener(listener);
      }
      Intent intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
        .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        .putExtra(RecognizerIntent.EXTRA_LANGUAGE, JarvisPrefs.get(this).getString("language", "bn-IN"))
        .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5)
        .putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
        .putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, getPackageName());
      listening = true;
      recognizer.startListening(intent);
      lastStatus = "listening";
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
    if (Build.VERSION.SDK_INT < 29 || Settings.canDrawOverlays(this)) {
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
    lastStatus = "heard the wake word";
    handler.postDelayed(this::loop, 8500);
  }

  private void release() {
    listening = false;
    if (recognizer != null) { try { recognizer.cancel(); recognizer.destroy(); } catch (RuntimeException ignored) { } recognizer = null; }
  }
  @Override public void onDestroy() { running = false; handler.removeCallbacksAndMessages(null); release(); if (!"off".equals(lastStatus)) lastStatus = "stopped"; super.onDestroy(); }
}

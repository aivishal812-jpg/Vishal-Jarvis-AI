package com.myraa.mobile;

import android.Manifest;
import android.app.*;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.media.AudioManager;
import android.media.AudioRecordingConfiguration;
import android.os.*;
import android.provider.Settings;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** "Hey Jarvis" hands-free wake word.
 *  Uses the phone's own speech recognizer in a loop while Vishal Jarvis is in the background;
 *  audio never leaves the phone unless the phone's recognizer itself is online-only.
 *  Pauses while a Live call runs, while Jarvis answers, and whenever another app (such as the
 *  keyboard's voice typing) is recording, so it never fights over the microphone. */
public final class WakeWordService extends Service {
  static final String STOP = "com.myraa.mobile.WAKE_STOP";
  private static final int NOTIFICATION_ID = 512;
  private static final String CHANNEL = "wake_word";
  /** Loose on purpose: recognisers spell "Jarvis" many ways in Bengali, Hindi and English. */
  static final Pattern WAKE = Pattern.compile(
    "(?iu)((?:hey|hi|hay|হেই|হে|হাই|हे|है)\\s*(?:সার্ভিস|সারভিস|service|सर्विस)|j\\s?[ae]+\\s?r?\\s?[vbw][iey]+[szhj]|jarv|jervis|jarbis|garvis|charvis|"
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
  private PowerManager.WakeLock wakeLock;
  private String lastHeard = "";
  private String shownText = "";
  /** Another app (for example the keyboard's voice typing) is recording; Jarvis stays out of its way. */
  private volatile boolean otherMic;
  private boolean onDevice, onDeviceBanned;
  private long otherMicFreeSince, ownMicStoppedAt;
  private AudioManager audio;
  private AudioManager.AudioRecordingCallback recordingCallback;

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
    // Keep the CPU awake while the screen is off or locked so the listening loop never freezes.
    try {
      if (wakeLock == null) {
        wakeLock = getSystemService(PowerManager.class).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "VishalJarvis:wakeword");
        wakeLock.setReferenceCounted(false);
      }
      // Held only while the loop is alive, with a timeout, so a stuck recognizer can never keep the CPU awake forever.
      if (!wakeLock.isHeld()) wakeLock.acquire(120_000);
    } catch (RuntimeException ignored) { }
    if (!SpeechRecognizer.isRecognitionAvailable(this)) { lastStatus = "no speech recognizer on this phone (install Google app)"; stopSelf(); return START_NOT_STICKY; }
    BeepMuter.restore(this);
    watchMicrophone();
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
  private boolean paused() { return LiveVoiceService.running || VoiceCommandActivity.active || AiTaskEvents.speaking || micBusy() || SystemClock.elapsedRealtime() < cooldownUntil; }

  /** Watch other recordings so the keyboard microphone (Gboard voice typing etc.) always wins. */
  private void watchMicrophone() {
    if (recordingCallback != null) return;
    try {
      audio = getSystemService(AudioManager.class);
      recordingCallback = new AudioManager.AudioRecordingCallback() {
        @Override public void onRecordingConfigChanged(List<AudioRecordingConfiguration> configs) { onRecordings(configs); }
      };
      audio.registerAudioRecordingCallback(recordingCallback, handler);
    } catch (RuntimeException ignored) { recordingCallback = null; }
  }

  private void onRecordings(List<AudioRecordingConfiguration> configs) {
    int count = configs == null ? 0 : configs.size();
    boolean silenced = false;
    if (Build.VERSION.SDK_INT >= 29 && configs != null) for (AudioRecordingConfiguration c : configs) if (c.isClientSilenced()) silenced = true;
    // While Jarvis listens its own recognizer is one recording; any extra one means someone else wants the mic.
    // Right after our own recognizer stops its recording can linger for a moment; ignore that.
    boolean ownTail = !listening && SystemClock.elapsedRealtime() - ownMicStoppedAt < 1500;
    boolean busy = listening ? (count >= 2 || silenced) : (count >= 1 && !ownTail);
    if (busy) {
      otherMicFreeSince = 0;
      if (!otherMic) { otherMic = true; release(); status("Paused: another app (keyboard voice typing) is using the microphone"); }
    } else if (otherMic && otherMicFreeSince == 0) otherMicFreeSince = SystemClock.elapsedRealtime();
  }

  private boolean micBusy() {
    if (!otherMic) {
      // Never start listening on top of a recording that is already running.
      try {
        if (!listening && audio != null && !audio.getActiveRecordingConfigurations().isEmpty()) {
          if (SystemClock.elapsedRealtime() - ownMicStoppedAt < 1500) return true; // our own tail; retry shortly
          otherMic = true; otherMicFreeSince = 0;
        }
      } catch (RuntimeException ignored) { }
      return otherMic;
    }
    if (otherMicFreeSince != 0 && SystemClock.elapsedRealtime() - otherMicFreeSince > 2500) {
      try { if (audio != null && !audio.getActiveRecordingConfigurations().isEmpty()) { otherMicFreeSince = 0; return true; } } catch (RuntimeException ignored) { }
      otherMic = false; return false;
    }
    if (otherMicFreeSince == 0) {
      try { if (audio != null && audio.getActiveRecordingConfigurations().isEmpty()) otherMicFreeSince = SystemClock.elapsedRealtime(); } catch (RuntimeException ignored) { }
    }
    return true;
  }

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
    if (paused()) {
      release();
      if (wakeLock != null && wakeLock.isHeld()) { try { wakeLock.release(); } catch (RuntimeException ignored) { } }
      BeepMuter.unmuteNow(); if (LiveVoiceService.running || otherMic || VoiceCommandActivity.active) status(LiveVoiceService.running ? "Paused during the voice call" : otherMic ? "Paused: another app (keyboard voice typing) is using the microphone" : "Talking with you"); handler.postDelayed(this::loop, otherMic || LiveVoiceService.running ? 1500 : 400); return; }
    if (listening) return;
    if (wakeLock != null && !wakeLock.isHeld()) { try { wakeLock.acquire(120_000); } catch (RuntimeException ignored) { } }
    try {
      if (recognizer == null) {
        recognizer = createRecognizer();
        recognizer.setRecognitionListener(listener);
      }
      Intent intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
        .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        .putExtra(RecognizerIntent.EXTRA_LANGUAGE, language())
        .putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, language())
        .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5)
        // Keep the wake word on the phone: no audio upload and far less radio use when the
        // device has an offline speech model. If it does not, the phone falls back on its own.
        .putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
        // Ask for long sessions so the mic restarts (and would beep) as rarely as possible.
        .putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 15000L)
        .putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 2500L)
        .putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 2000L)
        .putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, getPackageName());
      listening = true;
      BeepMuter.mute(this); // silence the start "ding"
      recognizer.startListening(intent);
      status("Listening · say \"Jarvis\"" + (onDevice ? " (offline)" : ""));
    } catch (RuntimeException e) { listening = false; release(); handler.postDelayed(this::loop, 5000); }
  }

  /**
   * Uses the on-device recognizer when Android provides one (API 31+, model downloaded), which
   * keeps the wake word offline and costs a fraction of the battery of the network recognizer.
   */
  private SpeechRecognizer createRecognizer() {
    try {
      if (Build.VERSION.SDK_INT >= 31 && !onDeviceBanned && SpeechRecognizer.isOnDeviceRecognitionAvailable(this)) {
        SpeechRecognizer local = SpeechRecognizer.createOnDeviceSpeechRecognizer(this);
        if (local != null) { onDevice = true; return local; }
      }
    } catch (RuntimeException ignored) { }
    onDevice = false;
    return SpeechRecognizer.createSpeechRecognizer(this);
  }

  private final RecognitionListener listener = new RecognitionListener() {
    public void onReadyForSpeech(Bundle params) { errorsInRow = 0; BeepMuter.unmuteLater(700); }
    public void onBeginningOfSpeech() { }
    public void onRmsChanged(float rms) { }
    public void onBufferReceived(byte[] buffer) { }
    public void onEndOfSpeech() { BeepMuter.mute(WakeWordService.this); } // silence the stop "ding"
    public void onError(int error) {
      listening = false; ownMicStoppedAt = SystemClock.elapsedRealtime();
      BeepMuter.unmuteLater(600);
      if (error == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS) { lastStatus = "needs microphone permission"; stopSelf(); return; }
      errorsInRow++;
      if ((error == 12 || error == 13) && onDevice && !onDeviceBanned) { onDeviceBanned = true; release(); }
      else if ((error == 12 || error == 13) && !"en-IN".equals(language)) { language = "en-IN"; release(); }
      if (error == SpeechRecognizer.ERROR_NETWORK || error == SpeechRecognizer.ERROR_NETWORK_TIMEOUT) {
        // No connection: re-evaluate the engine so the offline model is preferred next time.
        onDevice = false; onDeviceBanned = false;
      }
      if (error != SpeechRecognizer.ERROR_NO_MATCH && error != SpeechRecognizer.ERROR_SPEECH_TIMEOUT && errorsInRow >= 2) status("Problem: " + errorName(error));
      long delay = error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY ? 2500 : error == SpeechRecognizer.ERROR_NO_MATCH || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT ? 250 : Math.min(30000, 1000L * errorsInRow);
      if (error == SpeechRecognizer.ERROR_CLIENT || error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY) release();
      handler.postDelayed(WakeWordService.this::loop, delay);
    }
    public void onResults(Bundle results) { listening = false; ownMicStoppedAt = SystemClock.elapsedRealtime(); BeepMuter.unmuteLater(600); if (!check(results)) handler.postDelayed(WakeWordService.this::loop, 150); }
    /** Wait for the full sentence so "Hey Jarvis, turn on the torch" runs in one go. */
    public void onPartialResults(Bundle partial) { }
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
      if (phrase == null) continue;
      String lower = phrase.toLowerCase(Locale.ROOT);
      Matcher m = WAKE.matcher(lower);
      if (m.find()) { wake(VoiceCommandActivity.stripWakeWord(phrase.substring(Math.min(phrase.length(), m.start())))); return true; }
    }
    return false;
  }

  private void wake(String command) {
    cooldownUntil = SystemClock.elapsedRealtime() + 8000;
    release();
    try {
      Vibrator vibrator = getSystemService(Vibrator.class);
      if (vibrator != null) vibrator.vibrate(VibrationEffect.createOneShot(60, VibrationEffect.DEFAULT_AMPLITUDE));
    } catch (RuntimeException ignored) { }
    // Hands-free popup: answers on top of whatever is on screen (or the lock screen) without opening the app.
    Intent open = new Intent(this, VoiceCommandActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP)
      .putExtra(VoiceCommandActivity.EXTRA_COMMAND, command == null ? "" : command).putExtra(JarvisPrefs.EXTRA_FROM_WAKE, true);
    wakeScreen();
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
        .setCategory(Notification.CATEGORY_CALL).setContentIntent(tap).setFullScreenIntent(tap, true).setAutoCancel(true).setTimeoutAfter(15000).build());
    }
    status("Heard you");
    handler.postDelayed(this::loop, 8500);
  }

  /** Light the screen briefly so the user sees Jarvis answering on the lock screen. */
  @SuppressWarnings("deprecation")
  private void wakeScreen() {
    try {
      PowerManager power = getSystemService(PowerManager.class);
      if (power != null && !power.isInteractive()) {
        PowerManager.WakeLock screen = power.newWakeLock(PowerManager.SCREEN_BRIGHT_WAKE_LOCK | PowerManager.ACQUIRE_CAUSES_WAKEUP | PowerManager.ON_AFTER_RELEASE, "VishalJarvis:screen");
        screen.acquire(5000);
      }
    } catch (RuntimeException ignored) { }
  }

  private String language() {
    if (language == null) language = JarvisPrefs.get(this).getString("language", "bn-IN");
    return language;
  }

  private void release() {
    if (listening) ownMicStoppedAt = SystemClock.elapsedRealtime();
    listening = false;
    if (recognizer != null) { try { recognizer.cancel(); recognizer.destroy(); } catch (RuntimeException ignored) { } recognizer = null; }
  }
  @Override public void onDestroy() { running = false; BeepMuter.unmuteNow(); try { if (audio != null && recordingCallback != null) audio.unregisterAudioRecordingCallback(recordingCallback); } catch (RuntimeException ignored) { } recordingCallback = null; try { if (wakeLock != null && wakeLock.isHeld()) wakeLock.release(); } catch (RuntimeException ignored) { } handler.removeCallbacksAndMessages(null); release(); if (!"off".equals(lastStatus)) lastStatus = "stopped"; super.onDestroy(); }
}

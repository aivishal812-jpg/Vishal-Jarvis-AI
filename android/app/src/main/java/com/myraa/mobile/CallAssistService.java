package com.myraa.mobile;

import android.Manifest;
import android.app.Activity;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.graphics.PixelFormat;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.telecom.TelecomManager;
import android.telephony.PhoneStateListener;
import android.telephony.TelephonyManager;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Answers and assists a phone call.
 *
 * Two things are promised here:
 *
 *  1. <b>Receive</b>: when Jarvis is set to answer calls, an incoming call is picked up through
 *     {@link TelecomManager#acceptRingingCall()} and the phone is left in speakerphone, so the user
 *     can talk without touching anything.
 *
 *  2. <b>Take part in the call</b>: a floating card shows the live transcript and one suggested
 *     answer per other-person sentence. In speak mode that answer is also spoken out loud, so the
 *     words go into the call through the microphone.
 *
 * The honest limit is Android's, not this file's. Neither {@code VOICE_CALL} nor
 * {@code VOICE_DOWNLINK} audio is available to an ordinary app: only one of them may be recorded
 * even with a rooted policy in place, and Play-installed apps are refused. So the caller's voice is
 * heard through the speaker and the microphone, like a speakerphone conversation, and the card says
 * which mode is actually running. When the microphone cannot even be opened (a call in progress on
 * many OnePlus/Xiaomi builds), the card says so and stops pretending.
 */
public final class CallAssistService extends Service {

  public static final String ACTION_RINGING = "com.myraa.mobile.CALL_RINGING";
  public static final String ACTION_IDLE = "com.myraa.mobile.CALL_IDLE";
  public static final String START = "com.myraa.mobile.START_CALL_ASSIST";
  public static final String STOP = "com.myraa.mobile.STOP_CALL_ASSIST";

  private static final int NOTIFICATION_ID = 43;
  private static final String CHANNEL = "call_assist";
  private static final int SAMPLE_RATE = 16000;
  private static final long MIN_GAP_MS = 2500;

  private static volatile boolean running;

  /** True while the card is up. Settings shows this, so the switch never lies. */
  static boolean running() { return running; }

  private final Handler ui = new Handler(Looper.getMainLooper());
  private final ExecutorService worker = Executors.newSingleThreadExecutor();
  private final AtomicBoolean thinking = new AtomicBoolean();

  private CallCoach.Mode mode = CallCoach.Mode.SUGGEST;
  private WindowManager windows;
  private LinearLayout card;
  private TextView hearing, suggestion;
  private TelephonyManager telephony;
  private boolean listeningCall, receiverRegistered;

  /** Two capture back-ends: the platform recogniser first, raw audio as the fallback. */
  private SpeechRecognizer recognizer;
  private AudioRecord recorder;
  private Thread audioThread;
  private volatile boolean capturing;

  private AppStateListener callListener;
  private IntentFilter callFilter;

  @Override public IBinder onBind(Intent intent) { return null; }

  @Override public int onStartCommand(Intent intent, int flags, int startId) {
    if (intent != null && STOP.equals(intent.getAction())) { end(); return START_NOT_STICKY; }
    if (running) return START_STICKY;
    mode = JarvisPrefs.callMode(this);
    if (mode == CallCoach.Mode.OFF) { stopSelf(); return START_NOT_STICKY; }
    if (Build.VERSION.SDK_INT >= 34 && checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
      // Without the microphone there is nothing to take part in. Said once, then stop.
      notify("Call assistant needs the microphone", "Allow Microphone for Vishal Jarvis to hear the call.");
      stopSelf();
      return START_NOT_STICKY;
    }
    startForegroundWithNotification();
    running = true;
    registerCallState();
    showCard();
    return START_STICKY;
  }

  // ------------------------------------------------------------------ notification + card

  private void startForegroundWithNotification() {
    NotificationManager manager = getSystemService(NotificationManager.class);
    manager.createNotificationChannel(new NotificationChannel(CHANNEL, "Call assistant", NotificationManager.IMPORTANCE_LOW));
    PendingIntent open = PendingIntent.getActivity(this, 0,
      new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    PendingIntent stop = PendingIntent.getService(this, 1,
      new Intent(this, CallAssistService.class).setAction(STOP), PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    Notification notification = new Notification.Builder(this, CHANNEL)
      .setSmallIcon(android.R.drawable.stat_sys_phone_call)
      .setContentTitle("Vishal Jarvis · call assistant")
      .setContentText(CallCoach.statusLine(mode))
      .setContentIntent(open).setOngoing(true).setCategory(Notification.CATEGORY_SERVICE)
      .addAction(new Notification.Action.Builder(null, "Stop", stop).build()).build();
    try {
      if (Build.VERSION.SDK_INT >= 30) startForeground(NOTIFICATION_ID, notification,
        ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE | ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);
      else startForeground(NOTIFICATION_ID, notification);
    } catch (RuntimeException refused) {
      // Android 14 can refuse the microphone type while another app holds it; the service stays
      // alive as a plain notification instead of crashing the call.
      notify("Call assistant limited", "Android refused background microphone access, so only the call card is shown.");
    }
  }

  private void notify(String title, String text) {
    try {
      NotificationManager manager = getSystemService(NotificationManager.class);
      manager.createNotificationChannel(new NotificationChannel(CHANNEL, "Call assistant", NotificationManager.IMPORTANCE_LOW));
      manager.notify(NOTIFICATION_ID + 1, new Notification.Builder(this, CHANNEL)
        .setSmallIcon(android.R.drawable.stat_sys_phone_call).setContentTitle(title).setContentText(text)
        .setAutoCancel(true).build());
    } catch (RuntimeException ignored) { /* a notification is a courtesy, never a requirement */ }
  }

  private void showCard() {
    if (!android.provider.Settings.canDrawOverlays(this)) {
      notify("Call assistant needs one permission", "Allow \"display over other apps\" so the call card can appear during a call.");
      return;
    }
    if (card != null) return;
    try {
      windows = getSystemService(WindowManager.class);
      card = new LinearLayout(this);
      card.setOrientation(LinearLayout.VERTICAL);
      card.setPadding(dp(10), dp(8), dp(10), dp(8));
      card.setBackgroundColor(0xf2132430);
      TextView title = new TextView(this);
      title.setText("Vishal Jarvis · call");
      title.setTextColor(0xff7dd3fc); title.setTextSize(12); title.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
      hearing = new TextView(this);
      hearing.setTextColor(0xffe2e8f0); hearing.setTextSize(13); hearing.setText(CallCoach.statusLine(mode));
      suggestion = new TextView(this);
      suggestion.setTextColor(0xffffffff); suggestion.setTextSize(16); suggestion.setPadding(0, dp(4), 0, dp(4));
      LinearLayout row = new LinearLayout(this);
      row.setOrientation(LinearLayout.HORIZONTAL);
      Button speakBtn = new Button(this);
      speakBtn.setText("Say it"); speakBtn.setTextSize(11);
      speakBtn.setOnClickListener(v -> sayOutLoud(suggestion.getText().toString()));
      Button hide = new Button(this);
      hide.setText("Hide"); hide.setTextSize(11);
      hide.setOnClickListener(v -> hideCard());
      row.addView(speakBtn); row.addView(hide);
      card.addView(title); card.addView(hearing); card.addView(suggestion); card.addView(row);
      WindowManager.LayoutParams layout = new WindowManager.LayoutParams(
        WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
        PixelFormat.TRANSLUCENT);
      layout.gravity = Gravity.TOP; layout.y = dp(72);
      windows.addView(card, layout);
    } catch (RuntimeException failure) {
      card = null;
      notify("Call card could not open", "Android refused the overlay window, so the call continues without the card.");
    }
  }

  private void hideCard() {
    if (card == null || windows == null) return;
    try { windows.removeView(card); } catch (RuntimeException ignored) { /* already gone with the window */ }
    card = null;
  }

  private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }

  // ------------------------------------------------------------------ call state

  private final BroadcastReceiver callReceiver = new BroadcastReceiver() {
    @Override public void onReceive(Context context, Intent intent) {
      String action = intent == null ? "" : intent.getAction();
      if (ACTION_RINGING.equals(action)) {
        answerIfConfigured();
        return;
      }
      if (ACTION_IDLE.equals(action)) {
        listeningCall = false;
        stopListening();
        if (JarvisPrefs.callHangupStops(CallAssistService.this)) end();
        else updateCard("Call ended.", "");
      }
    }
  };

  /** Used on phones where reading the call state needs no extra call-log permission. */
  private final class AppStateListener extends PhoneStateListener {
    @Override public void onCallStateChanged(int state, String number) {
      if (state == TelephonyManager.CALL_STATE_RINGING) answerIfConfigured();
      if (state == TelephonyManager.CALL_STATE_OFFHOOK) { listeningCall = true; beginListening(); }
      if (state == TelephonyManager.CALL_STATE_IDLE) {
        listeningCall = false;
        stopListening();
        if (JarvisPrefs.callHangupStops(CallAssistService.this)) end();
        else updateCard(CallCoach.statusLine(CallCoach.Mode.OFF), "");
      }
    }
  }

  private void registerCallState() {
    callFilter = new IntentFilter();
    callFilter.addAction(ACTION_RINGING);
    callFilter.addAction(ACTION_IDLE);
    try {
      if (Build.VERSION.SDK_INT >= 33) registerReceiver(callReceiver, callFilter, Context.RECEIVER_NOT_EXPORTED);
      else registerReceiver(callReceiver, callFilter);
      receiverRegistered = true;
    } catch (RuntimeException ignored) { receiverRegistered = false; }
    try {
      telephony = getSystemService(TelephonyManager.class);
      if (telephony != null) {
        callListener = new AppStateListener();
        telephony.listen(callListener, PhoneStateListener.LISTEN_CALL_STATE);
      }
    } catch (SecurityException needsPermission) {
      // Android 12+ wants READ_PHONE_STATE for this. The notification listener still delivers the
      // ringing broadcast, so answering keeps working; only the automatic start on an outgoing
      // call is lost.
      callListener = null;
    }
  }

  /**
   * Picks up a ringing call when the user asked Jarvis to answer calls, and puts it on the
   * speakerphone. Returns quietly when the user did not ask for this.
   */
  private void answerIfConfigured() {
    if (!JarvisPrefs.callAutoAnswer(this)) return;
    try {
      TelecomManager telecom = getSystemService(TelecomManager.class);
      if (telecom == null) { updateCard("This phone has no call service.", ""); return; }
      if (telecom.getCallState() != TelecomManager.CALL_STATE_RINGING) return;  // already handled
      telecom.acceptRingingCall();
      ui.postDelayed(this::speakerOn, 900);
      updateCard("Call answered. " + CallCoach.statusLine(mode), "");
      listeningCall = true;
      beginListening();
    } catch (SecurityException denied) {
      updateCard("Android blocked answering the call. Allow Phone permission for Jarvis, then try again.", "");
    } catch (RuntimeException failure) {
      updateCard("The call could not be answered automatically. Answer it yourself; the card still works.", "");
    }
  }

  private void speakerOn() {
    try {
      AudioManager audio = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
      if (audio != null) audio.setSpeakerphoneOn(true);
    } catch (RuntimeException ignored) { /* some phones refuse while ringing; retried below */ }
    ui.postDelayed(() -> {
      try {
        AudioManager audio = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        if (audio != null && !audio.isSpeakerphoneOn()) audio.setSpeakerphoneOn(true);
      } catch (RuntimeException ignored) { /* the call already ended */ }
    }, 1800);
  }

  // ------------------------------------------------------------------ listening

  private void beginListening() {
    if (capturing) return;
    if (!listeningCall) return;
    if (!recognizerAvailable()) {
      startRawCapture();
      return;
    }
    startRecognizer();
  }

  private void releaseRecognizer() {
    if (recognizer == null) return;
    try { recognizer.cancel(); recognizer.destroy(); } catch (RuntimeException ignored) { /* already released */ }
    recognizer = null;
  }

  private boolean recognizerAvailable() {
    try { return SpeechRecognizer.isRecognitionAvailable(this); } catch (RuntimeException ignored) { return false; }
  }

  /**
   * The platform recogniser gives words; in a call it often refuses because the microphone belongs
   * to the call. When it errors we fall back to raw capture, which at least shows that sound is
   * coming in, and says so.
   */
  private void startRecognizer() {
    releaseRecognizer();
    try {
      recognizer = SpeechRecognizer.createSpeechRecognizer(this);
    } catch (RuntimeException unavailable) {
      startRawCapture();
      return;
    }
    capturing = true;
    recognizer.setRecognitionListener(new RecognitionListener() {
      @Override public void onReadyForSpeech(android.os.Bundle params) { updateCard(statusText(), ""); }
      @Override public void onBeginningOfSpeech() { }
      @Override public void onRmsChanged(float rms) { }
      @Override public void onBufferReceived(byte[] buffer) { }
      @Override public void onEndOfSpeech() { }
      @Override public void onPartialResults(android.os.Bundle partial) { handleHeard(best(partial), false); }
      @Override public void onResults(android.os.Bundle results) { handleHeard(best(results), true); restartRecognizer(); }
      @Override public void onEvent(int type, android.os.Bundle params) { }
      @Override public void onError(int error) {
        // NO_MATCH / SPEECH_TIMEOUT just mean the line was quiet; keep listening.
        if (error == SpeechRecognizer.ERROR_NO_MATCH || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT
            || error == SpeechRecognizer.ERROR_CLIENT || error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY) {
          restartRecognizer();
          return;
        }
        // The microphone is owned by the call, or the service was restarted: fall back honestly.
        capturing = false;
        releaseRecognizer();
        startRawCapture();
      }
    });
    try {
      recognizer.startListening(recognizerIntent());
    } catch (RuntimeException failure) {
      capturing = false;
      releaseRecognizer();
      startRawCapture();
    }
  }

  private int listening;

  private void restartRecognizer() {
    if (!capturing || !listeningCall) return;
    if (listening >= 120) { stopListening(); return; }
    listening++;
    ui.postDelayed(this::startRecognizer, 350);
  }

  private Intent recognizerIntent() {
    String language = JarvisPrefs.get(this).getString("language", "bn-IN");
    return new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
      .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
      .putExtra(RecognizerIntent.EXTRA_LANGUAGE, language)
      .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
      .putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, getPackageName());
  }

  private static String best(android.os.Bundle bundle) {
    ArrayList<String> list = bundle == null ? null : bundle.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
    return list == null || list.isEmpty() || list.get(0) == null ? "" : list.get(0).trim();
  }

  // ------------------------------------------------------------------ raw fallback

  /**
   * Records 16 kHz mono and reports how much voice-level sound arrived. No words are available from
   * this path, and the card says exactly that instead of inventing a transcript.
   */
  private void startRawCapture() {
    if (capturing) return;
    if (Build.VERSION.SDK_INT >= 34 && checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
      updateCard("Microphone permission is off, so the call cannot be heard.", "");
      return;
    }
    capturing = true;
    audioThread = new Thread(() -> {
      AudioRecord record = null;
      try {
        int min = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
        if (min <= 0) min = SAMPLE_RATE;
        int[] sources = { MediaRecorder.AudioSource.VOICE_COMMUNICATION, MediaRecorder.AudioSource.MIC };
        for (int source : sources) {
          AudioRecord trial = null;
          try {
            trial = new AudioRecord(source, SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, Math.max(min, SAMPLE_RATE / 5));
          } catch (RuntimeException refused) { trial = null; }
          if (trial != null && trial.getState() == AudioRecord.STATE_INITIALIZED) { record = trial; break; }
          if (trial != null) { try { trial.release(); } catch (RuntimeException ignored) { /* never started */ } }
        }
        if (record == null) {
          capturing = false;
          ui.post(() -> updateCard("This phone does not allow any app to use the microphone during a call. Jarvis can answer the call, but cannot hear it.", ""));
          return;
        }
        recorder = record;
        record.startRecording();
        ui.post(() -> updateCard("Sound is arriving, but this phone's recogniser cannot turn call audio into words. Turn on the accessibility service for better results.", ""));
        short[] buffer = new short[Math.max(1024, SAMPLE_RATE / 10)];
        long lastVoice = 0;
        while (capturing) {
          int read = record.read(buffer, 0, buffer.length);
          if (read <= 0) continue;
          double sum = 0;
          for (int i = 0; i < read; i++) sum += Math.abs(buffer[i]);
          double level = sum / read;
          if (level > 900) {
            if (System.currentTimeMillis() - lastVoice > 4000) {
              lastVoice = System.currentTimeMillis();
              ui.post(() -> updateCard("Someone is speaking on the call.", ""));
            }
          }
        }
      } catch (RuntimeException failure) {
        ui.post(() -> updateCard("Call audio could not be captured. The call itself is fine.", ""));
      } finally {
        if (record != null) {
          try { record.stop(); } catch (RuntimeException ignored) { /* never started */ }
          try { record.release(); } catch (RuntimeException ignored) { /* already released */ }
        }
        record = null; recorder = null;
      }
    }, "jarvis-call-audio");
    audioThread.setDaemon(true);
    audioThread.start();
  }

  private void stopListening() {
    capturing = false;
    releaseRecognizer();
    AudioRecord record = recorder;
    if (record != null) { try { record.stop(); } catch (RuntimeException ignored) { /* already stopped */ } }
    // The thread releases the recorder itself once the loop sees capturing == false.
  }

  // ------------------------------------------------------------------ answering

  private final StringBuilder transcript = new StringBuilder();
  private final ArrayList<String> answered = new ArrayList<>();

  private void handleHeard(String text, boolean finalResult) {
    if (text == null || text.isEmpty()) return;
    String clean = text.trim();
    String last = CallCoach.lastSentence(clean);
    if (last.isEmpty() || CallCoach.alreadyAnswered(answered.toArray(new String[0]), last)) {
      if (!last.isEmpty()) updateCard(statusText(), "…");
      return;
    }
    answered.add(last);
    appendTranscript("Caller: " + last);
    updateCard(statusText(), last);
    if (!CallCoach.needsHelp(last)) return;
    if (CallCoach.forbidden(last)) {
      updateCard(statusText(), "Private information was asked for. Jarvis will not say it out loud.");
      return;
    }
    askModel(last);
  }

  private void appendTranscript(String line) {
    if (transcript.length() > 3000) transcript.delete(0, transcript.length() - 3000);
    transcript.append(line).append('\n');
  }

  private void askModel(String callerSentence) {
    if (!thinking.compareAndSet(false, true)) return;
    String transcriptSoFar = transcript.toString();
    worker.execute(() -> {
      String answer = "";
      try {
        String key = CredentialVault.read(JarvisPrefs.get(this));
        String model = JarvisPrefs.get(this).getString("model", "gemini-3.5-flash");
        if (key.isEmpty()) {
          ui.post(() -> updateCard(statusText(), "Add your Gemini key in Settings to get answers during a call."));
          return;
        }
        answer = new QuickGemini().ask(key, model, CallCoach.INSTRUCTION,
          CallCoach.conversation(transcriptSoFar, false), "call");
      } catch (Exception failure) {
        answer = "";
      } finally {
        thinking.set(false);
      }
      final String spoken = answer;
      ui.post(() -> {
        if (spoken.isEmpty()) {
          updateCard(statusText(), "No answer available for that. Check the key and connection.");
          return;
        }
        suggestion.setText(spoken);
        appendTranscript("Vishal (suggested): " + spoken);
        if (mode == CallCoach.Mode.SPEAK) sayOutLoud(spoken);
      });
    });
  }

  /** Speaks through the call: the microphone is on speakerphone, so the caller hears it too. */
  private void sayOutLoud(String text) {
    String clean = SpeechEngine.speakable(text);
    if (clean.isEmpty()) return;
    if (CallCoach.forbidden(clean)) {
      updateCard(statusLine(), "That contains a private number or password, so it will not be spoken.");
      return;
    }
    try {
      MainActivity.speakForCall(getApplicationContext(), clean);
    } catch (RuntimeException failure) {
      updateCard(statusLine(), "The phone's speech engine could not speak that.");
    }
    appendTranscript("Vishal (spoken): " + clean);
  }

  private void updateCard(String status, String hint) {
    if (hearing != null && status != null) hearing.setText(status);
    if (suggestion != null) {
      if (hint != null && !hint.isEmpty()) suggestion.setText(hint);
      else if (hint != null) suggestion.setText("");
    }
  }

  private String statusText() {
    if (!capturing) return CallCoach.statusLine(mode);
    if (mode == CallCoach.Mode.OFF) return CallCoach.statusLine(CallCoach.Mode.OFF);
    return CallCoach.statusLine(mode);
  }

  private String statusLine() { return CallCoach.statusLine(mode); }

  @Override public void onDestroy() {
    running = false;
    stopListening();
    hideCard();
    if (receiverRegistered) { try { unregisterReceiver(callReceiver); } catch (RuntimeException ignored) { /* never registered */ } receiverRegistered = false; }
    if (telephony != null && callListener != null) {
      try { telephony.listen(callListener, PhoneStateListener.LISTEN_NONE); } catch (RuntimeException ignored) { /* service gone */ }
    }
    callListener = null;
    worker.shutdownNow();
    super.onDestroy();
  }

  private void end() {
    running = false;
    stopForeground(true);
    stopSelf();
  }

  /** Called by the app so the switch, the card and Android all agree. */
  static void start(Context context) {
    Intent intent = new Intent(context, CallAssistService.class).setAction(START);
    if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(intent);
    else context.startService(intent);
  }

  static void stop(Context context) {
    context.startService(new Intent(context, CallAssistService.class).setAction(STOP));
  }

  /** Where the user turns answering on: the system screen that owns that permission. */
  static void openAnswerPermission(Activity activity) {
    try {
      activity.startActivity(new Intent(TelecomManager.ACTION_CHANGE_DEFAULT_DIALER)
        .putExtra(TelecomManager.EXTRA_CHANGE_DEFAULT_DIALER_PACKAGE_NAME, activity.getPackageName()));
    } catch (RuntimeException noDialerUi) {
      try { activity.startActivity(new Intent(android.provider.Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS)); }
      catch (RuntimeException ignored) { /* nothing else to offer */ }
    }
  }

  static String describeMode(Context context) {
    CallCoach.Mode mode = JarvisPrefs.callMode(context);
    return mode == CallCoach.Mode.OFF ? "Off" : (running() ? "Running" : "Ready") + " · " + CallCoach.statusLine(mode);
  }

  static String languageNote(Context context) {
    String language = JarvisPrefs.get(context).getString("language", "bn-IN");
    return "Recogniser language: " + Locale.forLanguageTag(language).getDisplayName(Locale.ENGLISH) + ".";
  }
}

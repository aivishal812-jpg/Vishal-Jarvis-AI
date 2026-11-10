package com.myraa.mobile;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.IntentFilter;
import android.os.Build;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.provider.MediaStore;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.Manifest;
import android.content.pm.PackageManager;
import android.speech.tts.TextToSpeech;
import android.util.Base64;
import android.webkit.*;
import android.widget.TextView;
import org.json.*;
import javax.crypto.*;
import javax.crypto.spec.GCMParameterSpec;
import javax.net.ssl.HttpsURLConnection;
import java.io.*;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/** Standalone native lifecycle, credentials, networking and platform voice.
 *  Only bundled content gets the JS bridge; the avatar is the existing PMX runtime. */
public class MainActivity extends Activity {
  private static final String ORIGIN = "https://appassets.androidplatform.net";
  private static final int MICROPHONE_REQUEST = 77;
  private final ExecutorService liveNetwork = Executors.newSingleThreadExecutor();
  private final AtomicInteger liveGeneration = new AtomicInteger();
  private volatile HttpsURLConnection liveConnection;
  private String pendingLiveId;
  private String currentLiveId;
  private AudioCaptureEngine audioCapture;
  private volatile boolean audioEmitBusy;
  private static final int PERMISSION_REQUEST = 90;
  private static final int TASK_NOTIFICATION_REQUEST = 92;
  private static final int EXPORT_TRANSCRIPT_REQUEST = 93;
  private static final int PICK_IMAGE_REQUEST = 95;
  private static final int CAPTURE_IMAGE_REQUEST = 96;
  private static final int PICK_DOCUMENT_REQUEST = 99;
  private static final int CAMERA_FOR_PHOTO_REQUEST = 97;
  private boolean pendingAutoVoice;
  /** The last picture the user attached, so an attached photo stays the subject of the chat. */
  private Uri lastImageUri;
  private boolean shownOverLock;
  private boolean lastAccessibility = false;
  private static final int MAX_TOOL_ROUNDS = 4;
  private static final int MAX_CALLS_PER_ROUND = 4;
  private static final int MAX_HISTORY_TURNS = 40;
  private final Map<String, ActionWaiter> actionWaiters = new ConcurrentHashMap<>();
  private MobileActions mobileActions;
  private Runnable pendingUiAction;
  private boolean allowBackgroundLive;
  private boolean stopLiveRegistered;
  private final BroadcastReceiver stopLiveReceiver=new BroadcastReceiver(){@Override public void onReceive(Context context,Intent intent){cancelLive();if(web!=null)web.evaluateJavascript("window.dispatchEvent(new Event('myraa:stop-live'))",null);}};
  private WebView web;
  private SharedPreferences prefs;
  private TextToSpeech tts;
  private SpeechEngine speech;
  private String pendingSpeech;
  private java.util.Locale bengaliVoice;
  private volatile boolean ttsReady;
  private volatile boolean foreground;
  private volatile HttpsURLConnection activeConnection;
  private final ExecutorService network = Executors.newSingleThreadExecutor();
  private final ExecutorService settingsNetwork = Executors.newSingleThreadExecutor();
  private final AtomicInteger settingsGeneration = new AtomicInteger();
  private final Object settingsLock = new Object();
  private volatile HttpsURLConnection settingsConnection;
  private GeminiAccess provider = new GeminiAccess();
  private final AtomicInteger generation = new AtomicInteger();
  private final Object chatLock = new Object();
  private boolean busy;
  private volatile String activeTaskId;
  private final AiTaskEvents.Listener taskListener = event -> {
    String type = event.optString("type");
    if ("task-state".equals(type)) emit(json("type", "state", "state", state()));
    else {
      emit(event);
      if ("reply".equals(type) && prefs != null && spokenRepliesOn() && foreground) speakText(event.optString("text"));
    }
  };
  private int rendererFailures;
  private volatile boolean pageLoaded;
  private volatile boolean scriptsLoaded;
  private int watchdogGeneration;
  private String personality;

  @Override public void onCreate(Bundle saved) {
    super.onCreate(saved);
    prefs = getSharedPreferences("myraa.mobile.v1", MODE_PRIVATE);
    AiTaskEvents.attach(taskListener);
    AiTaskCoordinator.recover(this);
    getWindow().setStatusBarColor(Color.rgb(16,19,23));
    getWindow().setNavigationBarColor(Color.rgb(16,19,23));
    try { personality = new String(readLimited(getAssets().open("personality.txt"), 16000), StandardCharsets.UTF_8); }
    catch (Exception ignored) { personality = "You are Vishal Jarvis, created by Vishal. Default to natural West Bengal Indian Bengali. Be honest about your capabilities."; }
    tts = new TextToSpeech(this, status -> {
      ttsReady = status == TextToSpeech.SUCCESS;
      if (!ttsReady) {
        emit(json("type", "capability", "tts", false,
          "message", "Text-to-speech did not start. The reply still appears on screen; install or update \"Speech Recognition & Synthesis\" and try again."));
        return;
      }
      attachSpeechEngine();
      String queued = pendingSpeech; pendingSpeech = null;
      if (queued != null) speakText(queued);
    });
    mobileActions=new MobileActions(this,(id,result)->{
      ActionWaiter waiter=actionWaiters.remove(id);
      if(waiter!=null)waiter.complete(result);
      emit(json("type","mobile-action-result","id",id,"result",result));
    });
    mobileActions.setSpeech(this::speakText);
    audioCapture=new AudioCaptureEngine(this,new AudioCaptureEngine.Listener(){
      public void onAudio(byte[] pcm,boolean speech){emitNativeAudio(pcm,speech);}
      public void onDiagnostics(JSONObject diagnostics){emit(json("type","native-capture","ok",true,"diagnostics",diagnostics));}
      public void onError(String message){emit(json("type","native-capture","ok",false,"message",message));}
    });
    if(!stopLiveRegistered){
      if(Build.VERSION.SDK_INT>=33)registerReceiver(stopLiveReceiver,new IntentFilter(LiveVoiceService.STOP),Context.RECEIVER_NOT_EXPORTED);
      else registerReceiver(stopLiveReceiver,new IntentFilter(LiveVoiceService.STOP));
      stopLiveRegistered=true;
    }
    createWebView();
    readLaunchIntent(getIntent());
    // A page that never finishes loading leaves a blank window with no explanation. If the WebView
    // has not reported a finished load after 12 seconds, show what is wrong instead of nothing.
    final int mine = watchdogGeneration = watchdogGeneration + 1;
    web.postDelayed(() -> {
      if (mine != watchdogGeneration || pageLoaded) return;
      if (!scriptsLoaded) showWebViewError(stuckPageMessage());
    }, 12000);
  }

  /** Says which of the two things went wrong: the page file never arrived, or its script died. */
  private String stuckPageMessage() {
    String crash = CrashLog.read(this);
    if (!crash.isEmpty()) {
      String tail = crash.length() > 900 ? crash.substring(crash.length() - 900) : crash;
      return "Vishal Jarvis hit an error while starting. Tap to retry.\n\n" + tail;
    }
    return "Vishal Jarvis could not finish loading. Tap to retry, or update Android System WebView.";
  }

  @Override protected void onNewIntent(Intent intent) {
    super.onNewIntent(intent);
    setIntent(intent);
    readLaunchIntent(intent);
    if (foreground && pendingAutoVoice) emit(json("type", "auto-voice"));
  }

  /** Wake word, Quick Settings tile, widget and the Android assist gesture all open a voice call. */
  private void readLaunchIntent(Intent intent) {
    if (intent == null) return;
    String action = intent.getAction();
    if (intent.getBooleanExtra(JarvisPrefs.EXTRA_AUTO_VOICE, false) || Intent.ACTION_ASSIST.equals(action)
        || "android.intent.action.VOICE_COMMAND".equals(action) || "android.intent.action.VOICE_ASSIST".equals(action)) {
      pendingAutoVoice = true;
      boolean fromWake = intent.getBooleanExtra(JarvisPrefs.EXTRA_FROM_WAKE, false);
      intent.removeExtra(JarvisPrefs.EXTRA_AUTO_VOICE); intent.removeExtra(JarvisPrefs.EXTRA_FROM_WAKE);
      // Answer on top of the lock screen without unlocking the phone; the lock stays in place.
      if ((fromWake || Intent.ACTION_ASSIST.equals(action) || "android.intent.action.VOICE_ASSIST".equals(action)) && JarvisPrefs.lockScreen(this)) {
        setShowWhenLocked(true); setTurnScreenOn(true); shownOverLock = true;
        getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
      }
    }
  }

  private void launchCamera() {
    try {
      Intent capture = new Intent(MediaStore.ACTION_IMAGE_CAPTURE).putExtra(MediaStore.EXTRA_OUTPUT, CaptureProvider.uri())
        .addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION | Intent.FLAG_GRANT_READ_URI_PERMISSION);
      capture.setClipData(android.content.ClipData.newRawUri("photo", CaptureProvider.uri()));
      startActivityForResult(capture, CAPTURE_IMAGE_REQUEST);
    } catch (RuntimeException e) { emit(json("type", "image-attached", "ok", false, "message", "No camera app is available. Choose a photo from the gallery instead.")); }
  }
  /** Extracts the text of a picked document off the UI thread and reports the honest result. */
  private void stageDocument(Uri uri) {
    settingsNetwork.execute(() -> {
      try {
        DocumentReader.Result document = DocumentReader.read(this, uri);
        DocumentAttachment.stage(document);
        emit(json("type", "document-attached", "ok", true, "name", document.name,
          "pages", document.pages, "chars", document.text.length(),
          "message", document.text.length() > 6000
            ? "Read " + (document.pages > 0 ? document.pages + " pages" : "the document") + ". Ask a question and I will use the part that matters."
            : "Read the document. Ask me anything about it."));
      } catch (DocumentReader.Unsupported unsupported) {
        emit(json("type", "document-attached", "ok", false, "message", unsupported.getMessage()));
      } catch (Exception failure) {
        emit(json("type", "document-attached", "ok", false, "message", "That file could not be read. Scanned PDFs (pictures of pages) have no text layer; open one and use the screen reader instead."));
      }
    });
  }

  private void launchGallery() {
    try { startActivityForResult(Intent.createChooser(new Intent(Intent.ACTION_GET_CONTENT).setType("image/*").addCategory(Intent.CATEGORY_OPENABLE), "Choose a photo for Jarvis"), PICK_IMAGE_REQUEST); }
    catch (RuntimeException e) { emit(json("type", "image-attached", "ok", false, "message", "No gallery or file app is available.")); }
  }
  private void stageImage(Uri uri) {
    lastImageUri = uri;
    settingsNetwork.execute(() -> {
      try {
        String thumb = ImageAttachment.stage(MainActivity.this, uri);
        emit(json("type", "image-attached", "ok", true, "thumb", thumb, "message", "Photo attached. Ask anything about it."));
      } catch (Exception e) {
        emit(json("type", "image-attached", "ok", false, "message", e instanceof IllegalArgumentException ? e.getMessage() : "That photo could not be read."));
      }
    });
  }

  @SuppressWarnings("SetJavaScriptEnabled") private void createWebView() {
    try {
      android.content.pm.PackageInfo provider = WebView.getCurrentWebViewPackage();
      if (provider != null && Integer.parseInt(provider.versionName.split("\\.")[0]) < 100) {
        showWebViewError("Update Android System WebView to version 100 or later, then tap to retry. Your settings and conversations are safe."); return;
      }
      if (web != null) { setContentView(web); return; }
      web = new WebView(this);
    } catch (RuntimeException error) {
      showWebViewError("Android WebView could not start. Check that Android System WebView is installed and enabled, then tap to retry."); return;
    }
    web.setBackgroundColor(Color.rgb(16,19,23));
    WebSettings settings = web.getSettings();
    settings.setJavaScriptEnabled(true);
    settings.setTextZoom(Math.max(85, Math.min(140, prefs.getInt("textScale", 100))));
    settings.setDomStorageEnabled(true);
    settings.setAllowFileAccess(false);
    settings.setAllowContentAccess(false);
    settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
    settings.setMediaPlaybackRequiresUserGesture(false);
    settings.setSupportMultipleWindows(false);
    CookieManager.getInstance().setAcceptCookie(false);
    web.addJavascriptInterface(new NativeBridge(), "MyraaNative");
    web.setWebViewClient(new WebViewClient() {
      @Override public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
        android.util.Log.e("MyraaWeb", "Resource error " + error.getErrorCode() + ": " + request.getUrl().getPath());
      }
      @Override public void onPageFinished(WebView view, String url) { pageLoaded = true; android.util.Log.i("MyraaWeb", "Bundled page ready"); }
      @Override public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
        Uri uri = request.getUrl();
        if (!"https".equals(uri.getScheme()) || !"appassets.androidplatform.net".equals(uri.getHost())) return blocked();
        String file = uri.getPath();
        if (file == null || !file.startsWith("/assets/") || file.contains("..") || file.contains("\\")) return blocked();
        file = file.substring(8);
        try {
          String ext = file.substring(file.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT);
          String mime = "application/octet-stream";
          if (ext.equals("html")) mime = "text/html";
          if (ext.equals("js")) mime = "text/javascript";
          if (ext.equals("css")) mime = "text/css";
          if (ext.equals("json")) mime = "application/json";
          if (ext.equals("png")) mime = "image/png";
          if (ext.equals("jpg") || ext.equals("jpeg")) mime = "image/jpeg";
          if (ext.equals("bmp")) mime = "image/bmp";
          Map<String,String> headers = new HashMap<>();
          headers.put("X-Content-Type-Options", "nosniff");
          headers.put("Content-Security-Policy", "default-src 'self'; script-src 'self'; style-src 'self' 'unsafe-inline'; img-src 'self' data: blob:; connect-src 'self' wss://generativelanguage.googleapis.com; object-src 'none'; frame-src 'none'; base-uri 'none'; form-action 'none'");
          return new WebResourceResponse(mime, "UTF-8", 200, "OK", headers, getAssets().open(file));
        } catch (Exception ignored) { return new WebResourceResponse("text/plain", "UTF-8", 404, "Not Found", null, new ByteArrayInputStream(new byte[0])); }
      }
      @Override public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) { return true; }
      @Override public boolean onRenderProcessGone(WebView view, RenderProcessGoneDetail detail) {
        cancelLive();
        view.destroy(); web = null;
        if (++rendererFailures <= 2) createWebView();
        else {
          TextView message = new TextView(MainActivity.this);
          message.setText("Vishal Jarvis's graphics engine stopped. Your settings and history are safe. Tap to retry, or restart the app.");
          message.setPadding(30,60,30,30); message.setOnClickListener(v -> { rendererFailures = 0; createWebView(); }); setContentView(message);
        }
        return true;
      }
    });
    web.setWebChromeClient(new WebChromeClient() {
      @Override public boolean onConsoleMessage(ConsoleMessage message) {
        // Log only errors from bundled scripts, never page data or provider payloads.
        if (message.messageLevel() == ConsoleMessage.MessageLevel.ERROR) android.util.Log.e("MyraaWeb", "Bundled page reported an error.");
        return true;
      }
      @Override public boolean onJsConfirm(WebView view, String url, String message, JsResult result) {
        new AlertDialog.Builder(MainActivity.this).setTitle("Vishal Jarvis").setMessage(message)
          .setPositiveButton("Confirm", (d,w) -> result.confirm()).setNegativeButton("Cancel", (d,w) -> result.cancel())
          .setOnCancelListener(d -> result.cancel()).show(); return true;
      }
      @Override public void onPermissionRequest(PermissionRequest request) {
        runOnUiThread(() -> {
          if (foreground && ORIGIN.equals(request.getOrigin().toString().replaceAll("/$", "")) &&
              web != null && (ORIGIN + "/assets/mobile.html").equals(web.getUrl()) &&
              checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED &&
              request.getResources().length == 1 && PermissionRequest.RESOURCE_AUDIO_CAPTURE.equals(request.getResources()[0]))
            request.grant(new String[]{PermissionRequest.RESOURCE_AUDIO_CAPTURE});
          else {
            request.deny();
            // A silent denial looks like a broken voice button; tell the user what to allow.
            if (foreground) emit(json("type", "error", "service", "live", "message",
              "Microphone access was blocked. Allow Microphone in Settings → Permissions, then tap Talk again."));
          }
        });
      }
    });
    setContentView(web);
    web.loadUrl(ORIGIN + "/assets/mobile.html");
  }

  private void showWebViewError(String text) {
    TextView message = new TextView(this); message.setText(text); message.setTextColor(Color.WHITE);
    message.setBackgroundColor(Color.rgb(16,19,23)); message.setPadding(30,70,30,30);
    message.setOnClickListener(v -> createWebView()); setContentView(message);
  }

  private WebResourceResponse blocked() { return new WebResourceResponse("text/plain", "UTF-8", 403, "Blocked", null, new ByteArrayInputStream(new byte[0])); }
  private JSONObject state() {
    JSONObject permissions = json(
      "microphone", granted(Manifest.permission.RECORD_AUDIO),
      "contacts", granted(Manifest.permission.READ_CONTACTS),
      "camera", granted(Manifest.permission.CAMERA),
      "notifications", Build.VERSION.SDK_INT < 33 || granted(Manifest.permission.POST_NOTIFICATIONS),
      "overlay", Settings.canDrawOverlays(this),
      "phone", granted(Manifest.permission.CALL_PHONE),
      "sms", granted(Manifest.permission.SEND_SMS),
      "accessibility", UiAutomationService.available());
    JSONObject value = json("model", prefs.getString("model", "gemini-3.5-flash"), "quality", prefs.getString("quality", "balanced"),
      "language", prefs.getString("language", "bn-IN"), "spokenReplies", spokenRepliesOn(),
      "keyConfigured", prefs.contains("keyCipher"), "keyVerified", prefs.getLong("keyVerifiedAt", 0) > 0 && prefs.getString("verifiedModel", "").equals(prefs.getString("model", "gemini-3.5-flash")),
      "providerErrorCode", prefs.getString("providerErrorCode", ""), "ttsAvailable", ttsReady, "version", BuildInfo.VERSION,
      "textScale", prefs.getInt("textScale", 100), "pendingTasks", AiTaskStore.get(this).pendingCount(),
      "floatingControlsAllowed", Settings.canDrawOverlays(this), "liveModel", prefs.getString("liveModel", "gemini-3.1-flash-live-preview"),
      "permissions", permissions, "memory", memoryArray());
    try {
      value.put("pro", json("wakeWord", JarvisPrefs.wakeWord(this), "wakeStatus", WakeWordService.lastStatus,
        "secureActions", JarvisPrefs.secureActions(this), "notificationAccess", JarvisNotificationListener.enabled(this),
        "calendar", granted(Manifest.permission.READ_CALENDAR), "homeCity", JarvisPrefs.homeCity(this),
        "fallbackModel", JarvisPrefs.fallbackModel(this), "reminders", ReminderStore.all(this).length(), "notes", NoteStore.all(this).length(),
        "imageAttached", ImageAttachment.hasStaged()));
    } catch (JSONException ignored) { /* tolerated: the window may already be gone */ }

    try {
      JSONArray pending = new JSONArray();
      for (AiTaskStore.Task task : AiTaskStore.get(this).pending()) pending.put(json("id", task.id, "text", task.prompt, "status", task.status));
      value.put("pending", pending);
      AiTaskStore.Task failed = AiTaskStore.get(this).latestFailure();
      if (failed != null) value.put("lastFailedTask", json("id", failed.id, "text", failed.prompt, "message", failed.error));
    } catch (Exception ignored) { /* tolerated: the window may already be gone */ }
    try { value.put("history", new JSONArray(prefs.getString("history", "[]"))); } catch (Exception ignored) { try { value.put("history", new JSONArray()); } catch (Exception unused) {} }
    try { value.put("audio", audioCapture == null ? new JSONObject() : audioCapture.diagnostics()); } catch (Exception ignored) { /* tolerated: the window may already be gone */ }
    return value;
  }

  private boolean granted(String permission) { return checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED; }

  private JSONArray memoryArray() {
    try { return new JSONArray(prefs.getString("memory", "[]")); } catch (Exception ignored) { return new JSONArray(); }
  }

  /** Long-term memory: short, deduplicated, user-approved facts; secrets are refused. */
  private boolean addFact(String raw) { return MemoryStore.add(this, raw); }
  private boolean removeFact(String id) { return MemoryStore.remove(this, id); }

  private void cancelActionWaiters() {
    for (String id : new ArrayList<>(actionWaiters.keySet())) {
      ActionWaiter waiter = actionWaiters.remove(id);
      if (waiter != null) waiter.complete(json("status", "cancelled", "message", "Action cancelled."));
      final String pendingId = id;
      runOnUiThread(() -> mobileActions.cancel(pendingId));
    }
  }

  /**
   * Every call the page makes passes through here. A blocked runtime permission (contacts, call log,
   * notifications on Android 13+, exact alarms) throws SecurityException from deep inside a helper,
   * and an exception crossing the JS bridge kills the whole process - the phone shows "keeps
   * stopping" and the window closes before it ever draws. Guarded entry points turn that into a
   * normal, readable refusal instead.
   */
  private void guard(Runnable body) {
    try { body.run(); }
    catch (Throwable failure) {
      try { CrashLog.record(MainActivity.this, Thread.currentThread(), failure); } catch (Throwable ignored) { /* already failing */ }
      String why = failure instanceof SecurityException
        ? "Android blocked that because a permission is missing. Open Settings - Permissions, allow it, then try again."
        : "That did not work on this phone: " + failure.getClass().getSimpleName();
      try { emit(json("type", "error", "service", "bridge", "message", why)); } catch (Throwable ignored) { /* the page may be gone */ }
    }
  }

  public final class NativeBridge {
    /** The bundle calls this once it has drawn the shell; a silent script failure stays visible. */
    @JavascriptInterface public void pageReady() { scriptsLoaded = true; pageLoaded = true; android.util.Log.i("MyraaWeb", "Interface drawn"); }
    @JavascriptInterface public String getState() {
      // The page reads this before it draws anything, so it must always answer.
      try { return state().toString(); } catch (Throwable failure) {
        try { CrashLog.record(MainActivity.this, Thread.currentThread(), failure); } catch (Throwable ignored) { /* already failing */ }
        return json("history", new JSONArray(), "keyConfigured", false, "model", "gemini-3.5-flash",
          "quality", "balanced", "language", "bn-IN", "version", BuildInfo.VERSION).toString();
      }
    }
    @JavascriptInterface public void configure(String raw) { guard(() -> configureWork(raw)); }
    private void configureWork(String raw) {
      configureSettings(raw, false);
    }
    @JavascriptInterface public void checkConnection(String raw) { guard(() -> checkConnectionWork(raw)); }
    private void checkConnectionWork(String raw) {
      configureSettings(raw, true);
    }
    private void configureSettings(String raw, boolean checkOnly) {
      try {
        JSONObject input = new JSONObject(raw);
        String model = input.optString("model", "gemini-3.5-flash");
        if (!model.matches("[A-Za-z0-9._-]{1,100}")) throw new Exception("Enter a valid Gemini model identifier.");
        String liveModel = input.optString("liveModel", prefs.getString("liveModel", "gemini-3.1-flash-live-preview"));
        if (!liveModel.matches("[A-Za-z0-9._-]{1,100}")) throw new Exception("Enter a valid Live voice model identifier.");
        String quality = input.optString("quality", "balanced");
        if (!Arrays.asList("low", "balanced", "high").contains(quality)) throw new Exception("Invalid graphics preset.");
        String language = input.optString("language", "bn-IN");
        if (!Arrays.asList("bn-IN", "hi-IN", "en-IN", "en-US").contains(language)) throw new Exception("Invalid language.");
        String key = input.optString("key", "").trim();
        final int token = settingsGeneration.incrementAndGet();
        emit(json("type", "settings", "status", "checking", "message", checkOnly ? "Checking Google key and model access…" : key.isEmpty() ? "Saving settings…" : "Checking the new key before saving…"));
        settingsNetwork.execute(() -> {
          try {
            if (settingsGeneration.get() != token) return;
            String candidate = key.isEmpty() && checkOnly ? readKey() : key;
            String networkStatus = NetworkDiagnostics.status(MainActivity.this);
            boolean verified = !candidate.isEmpty();
            if (checkOnly && !verified) throw new GeminiAccess.Failure("KEY_MISSING", 0, "Enter a new key in this field, or save one before checking the connection.");
            if (verified && "offline".equals(networkStatus)) throw new GeminiAccess.Failure("NO_INTERNET", 0, "No internet connection is available. Connect to Wi-Fi or mobile data, then retry.");
            if (verified && "captive_portal".equals(networkStatus)) throw new GeminiAccess.Failure("CAPTIVE_PORTAL", 0, "The network requires a sign-in before internet access. Sign in, then retry.");
            if (verified) provider.verify(candidate, model, connection -> {
              settingsConnection = connection;
              if (connection != null && settingsGeneration.get() != token) connection.disconnect();
            });
            synchronized (settingsLock) {
              if (settingsGeneration.get() != token) return;
              SharedPreferences.Editor edit = prefs.edit();
              if (!checkOnly) {
                if (!key.isEmpty()) writeKey(edit, key);
                edit.putString("model", model).putString("quality", quality).putString("language", language)
                  .putBoolean("spokenReplies", input.optBoolean("spokenReplies", true))
                  .putString("liveModel", liveModel);
              }
              if (verified && (!checkOnly || key.isEmpty())) edit.putLong("keyVerifiedAt", System.currentTimeMillis()).putString("verifiedModel", model).remove("providerErrorCode");
              if (!edit.commit()) throw new Exception("The settings could not be saved on this device.");
            }
            if (settingsGeneration.get() == token) emit(json("type", "settings", "status", checkOnly ? "checked" : "saved", "state", state(),
              "network", networkStatus, "message", checkOnly ? "Internet and Gemini reachable. Google accepted the key and this model supports text chat." : "Settings saved."));
          } catch (Exception failure) {
            if (settingsGeneration.get() == token) {
              GeminiAccess.Failure issue = failure instanceof GeminiAccess.Failure ? (GeminiAccess.Failure) failure : null;
              emit(json("type", "settings", "status", "error", "code", issue == null ? "CONNECTION_FAILED" : issue.code,
                "network", NetworkDiagnostics.status(MainActivity.this),
                "message", issue == null ? "Could not reach Gemini. Check internet, DNS and the selected model, then retry. The previous key and settings were kept." : issue.getMessage() + " The previous key and settings were kept."));
            }
          }
        });
      } catch (Exception e) { emit(json("type", "settings", "status", "error", "code", "SETTINGS_INVALID", "message", "Check the model identifier, graphics, language and key fields. Previous settings were kept.")); }
    }
    @JavascriptInterface public void chat(String text, String id) { guard(() -> startChat(text, id)); }
    @JavascriptInterface public void cancel() { guard(() -> cancelWork()); }
    private void cancelWork() {
      generation.incrementAndGet();
      HttpsURLConnection connection = activeConnection; if (connection != null) connection.disconnect();
      cancelActionWaiters();
      String current;
      synchronized (chatLock) { current = activeTaskId; activeTaskId = null; busy = false; }
      AiTaskStore.Task task = current == null ? AiTaskStore.get(MainActivity.this).latestPending() : AiTaskStore.get(MainActivity.this).find(current);
      AiTaskCoordinator.cancel(MainActivity.this, task);
      AiTaskCoordinator.endForeground(current);
      stopService(new Intent(MainActivity.this, AiTaskForegroundService.class));
    }
    @JavascriptInterface public void startLive(String id) { guard(() -> startLiveWork(id)); }
    private void startLiveWork(String id) {
      if (id == null || !id.matches("[A-Za-z0-9-]{1,80}")) return;
      runOnUiThread(() -> {
        cancelLive();
        if (!foreground) { emit(json("type", "live-error", "id", id, "message", "Open Vishal Jarvis to start live voice.")); return; }
        currentLiveId=id;
        ArrayList<String> needed=new ArrayList<>();
        if(checkSelfPermission(Manifest.permission.RECORD_AUDIO)!=PackageManager.PERMISSION_GRANTED)needed.add(Manifest.permission.RECORD_AUDIO);
        if(Build.VERSION.SDK_INT>=33&&checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED)needed.add(Manifest.permission.POST_NOTIFICATIONS);
        if(!needed.isEmpty()){pendingLiveId=id;requestPermissions(needed.toArray(new String[0]),MICROPHONE_REQUEST);}else provisionLive(id, false);
      });
    }
    @JavascriptInterface public void mobileAction(String id,String name,String args) { guard(() -> mobileActionWork(id, name, args)); }
    private void mobileActionWork(String id,String name,String args) {
      if(args==null||args.length()>10000)return;
      runOnUiThread(()->{
        if(!foreground&&!LiveVoiceService.overlayVisible){emit(json("type","mobile-action-result","id",id,"result",json("status","permission_required","message","Bring Vishal Jarvis back to the front, or allow Floating controls in Settings → Permissions, so it can switch apps for you.")));return;}
        if(isFinishing()){emit(json("type","mobile-action-result","id",id,"result",json("status","unavailable","message","Vishal Jarvis is closing. Open it again and repeat the request.")));return;}
        try {
          if(!foreground&&"send_message".equals(new JSONObject(args).optString("action"))){
            if(pendingUiAction!=null){emit(json("type","mobile-action-result","id",id,"result",json("status","busy","message","Finish the current recipient selection first.")));return;}
            pendingUiAction=()->mobileActions.execute(id,name,args);startActivity(new Intent(MainActivity.this,MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_REORDER_TO_FRONT));return;
          }
        }catch(JSONException ignored){}
        mobileActions.execute(id,name,args);
      });
    }
    @JavascriptInterface public void cancelMobileAction(String id) { guard(() -> { if (id != null) runOnUiThread(() -> mobileActions.cancel(id)); }); }
    @JavascriptInterface public void openFloatingSettings() { guard(() -> runOnUiThread(() -> startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + getPackageName()))))); }
    @JavascriptInterface public void endLive() { guard(() -> runOnUiThread(() -> cancelLive())); }
    @JavascriptInterface public void renewLive(String id) { guard(() -> renewLiveWork(id)); }
    private void renewLiveWork(String id) {
      if (id == null || !id.matches("[A-Za-z0-9-]{1,80}")) return;
      runOnUiThread(() -> {
        if (currentLiveId == null) return;
        currentLiveId = id;
        provisionLive(id, true);
      });
    }
    @JavascriptInterface public String startNativeCapture() {
      try {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
          return json("ok", false, "message", "Microphone permission was denied.").toString();
        }
        boolean ok = audioCapture != null && audioCapture.start();
        JSONObject diagnostics = audioCapture == null ? json("ok", false) : audioCapture.diagnostics();
        diagnostics.put("ok", ok);
        return diagnostics.toString();
      } catch (Exception error) {
        return json("ok", false, "message", "Native microphone capture could not start.").toString();
      }
    }
    @JavascriptInterface public void stopNativeCapture() { guard(() -> { if (audioCapture != null) audioCapture.stop(); }); }
    @JavascriptInterface public String audioDiagnostics() {
      try { return audioCapture == null ? "{}" : audioCapture.diagnostics().toString(); }
      catch (Throwable failure) { return json("ok", false, "message", "The microphone reports no diagnostics right now.").toString(); }
    }
    @JavascriptInterface public void speak(String text) { guard(() -> speakText(text)); }
    @JavascriptInterface public void stopSpeaking() { guard(() -> runOnUiThread(MainActivity.this::stopSpeaking)); }
    @JavascriptInterface public void stopSpeech() { guard(() -> { if (tts != null) tts.stop(); AiTaskEvents.speaking = false; emit(json("type", "speech", "active", false)); }); }
    @JavascriptInterface public void clearKey() { guard(() -> clearKeyWork()); }
    private void clearKeyWork() {
      cancel(); AiTaskCoordinator.cancelAll(MainActivity.this);
      synchronized (settingsLock) {
        settingsGeneration.incrementAndGet();
        HttpsURLConnection connection = settingsConnection; if (connection != null) connection.disconnect();
        prefs.edit().remove("keyCipher").remove("keyIv").remove("keyVerifiedAt").remove("verifiedModel").remove("providerErrorCode").commit();
      }
      emit(json("type", "settings", "status", "cleared", "state", state(), "message", "Saved API key removed."));
    }
    @JavascriptInterface public void clearHistory() { guard(() -> { cancelWork(); AiTaskCoordinator.cancelAll(MainActivity.this); AiTaskStore.get(MainActivity.this).clearHistory(); }); }
    @JavascriptInterface public void openSettings() { guard(() -> runOnUiThread(() -> startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + getPackageName()))))); }
    @JavascriptInterface public String rememberFact(String text) {
      try {
        boolean saved = addFact(text);
        String message = saved ? "Saved to memory." : "That fact was not saved. Keep it under 240 characters and never store passwords, OTPs or keys.";
        emit(json("type", "memory", "status", saved ? "saved" : "rejected", "state", state(), "message", message));
        return json("status", saved ? "saved" : "rejected", "message", message).toString();
      } catch (Throwable failure) {
        return json("status", "rejected", "message", "That fact could not be stored on this phone.").toString();
      }
    }
    @JavascriptInterface public void deleteFact(String id) { guard(() -> deleteFactWork(id)); }
    private void deleteFactWork(String id) {
      boolean removed = removeFact(id);
      emit(json("type", "memory", "status", removed ? "deleted" : "unchanged", "state", state(), "message", removed ? "Memory updated." : "That memory entry was already gone."));
    }
    @JavascriptInterface public void clearMemory() { guard(() -> clearMemoryWork()); }
    private void clearMemoryWork() {
      MemoryStore.clear(MainActivity.this);
      emit(json("type", "memory", "status", "cleared", "state", state(), "message", "All remembered facts were deleted."));
    }
    @JavascriptInterface public void copyText(String text) { guard(() -> copyTextWork(text)); }
    private void copyTextWork(String text) {
      String body = text == null ? "" : text;
      if (body.isEmpty() || body.length() > 20000) return;
      runOnUiThread(() -> {
        try {
          android.content.ClipboardManager clipboard = (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
          clipboard.setPrimaryClip(android.content.ClipData.newPlainText("Vishal Jarvis", body));
          emit(json("type", "clipboard", "status", "copied", "message", "Copied to the clipboard."));
        } catch (RuntimeException failure) {
          emit(json("type", "clipboard", "status", "failed", "message", "Android blocked clipboard access."));
        }
      });
    }
    @JavascriptInterface public void shareText(String text) { guard(() -> shareTextWork(text)); }
    private void shareTextWork(String text) {
      String body = text == null ? "" : text;
      if (body.isEmpty() || body.length() > 4000) return;
      final String id = "share-" + UUID.randomUUID();
      runOnUiThread(() -> mobileActions.execute(id, "mobile_action", json("action", "share_text", "text", body).toString()));
    }
    @JavascriptInterface public void setTextScale(int percent) { guard(() -> setTextScaleWork(percent)); }
    private void setTextScaleWork(int percent) {
      int size = Math.max(85, Math.min(140, percent));
      prefs.edit().putInt("textScale", size).apply();
      runOnUiThread(() -> { if (web != null) web.getSettings().setTextZoom(size); });
    }
    @JavascriptInterface public void exportTranscript() { guard(() -> exportTranscriptWork()); }
    private void exportTranscriptWork() {
      runOnUiThread(() -> {
        try {
          TranscriptExport.create(MainActivity.this);
          Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
            .setType("text/plain").putExtra(Intent.EXTRA_TITLE, "Vishal-Jarvis-conversation.txt");
          startActivityForResult(intent, EXPORT_TRANSCRIPT_REQUEST);
        } catch (Exception e) { error("export", e instanceof IllegalStateException ? e.getMessage() : "Could not create the transcript.", null); }
      });
    }
    @JavascriptInterface public void shareTranscript() { guard(() -> shareTranscriptWork()); }
    private void shareTranscriptWork() {
      runOnUiThread(() -> {
        try {
          File shared = TranscriptExport.createShared(MainActivity.this);
          Uri uri = TranscriptProvider.uriFor(shared);
          Intent intent = new Intent(Intent.ACTION_SEND).setType("text/plain")
            .putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
          intent.setClipData(android.content.ClipData.newRawUri("Vishal Jarvis transcript", uri));
          startActivity(Intent.createChooser(intent, "Share conversation text file"));
        } catch (Exception e) { error("export", e instanceof IllegalStateException ? e.getMessage() : "Could not share the transcript.", null); }
      });
    }
    @JavascriptInterface public void pickImage() { guard(() -> pickImageWork()); }
    private void pickImageWork() {
      runOnUiThread(() -> new AlertDialog.Builder(MainActivity.this).setTitle("Show Jarvis a photo")
        .setItems(new CharSequence[]{"Take a photo", "Choose from gallery"}, (d, which) -> {
          if (which == 0) {
            if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) launchCamera();
            else requestPermissions(new String[]{Manifest.permission.CAMERA}, CAMERA_FOR_PHOTO_REQUEST);
          } else launchGallery();
        }).setNegativeButton("Cancel", null).show());
    }
    @JavascriptInterface public void clearImage() { guard(ImageAttachment::clear); }
    /** Reads a PDF or text document into the next request's context. */
    @JavascriptInterface public void pickDocument() { guard(() -> pickDocumentWork()); }
    private void pickDocumentWork() {
      runOnUiThread(() -> {
        try {
          Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
            .setType("*/*").putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"application/pdf", "text/*"});
          startActivityForResult(intent, PICK_DOCUMENT_REQUEST);
        } catch (RuntimeException missing) { emit(json("type", "document-attached", "ok", false, "message", "No file app is available on this phone.")); }
      });
    }
    @JavascriptInterface public void openProSettings() { guard(() -> runOnUiThread(() -> startActivity(new Intent(MainActivity.this, ProSettingsActivity.class)))); }
    @JavascriptInterface public String takeLaunchAction() {
      try { if (!pendingAutoVoice) return ""; pendingAutoVoice = false; return "voice"; }
      catch (Throwable failure) { return ""; }
    }
    /**
     * "It closes when I open it" is impossible to diagnose from chat, and the phone cannot be
     * plugged into a computer. This hands the local crash log to any share target instead.
     */
    @JavascriptInterface public void shareCrashLog() {
      guard(() -> {
        String crash = CrashLog.read(MainActivity.this);
        if (crash.isEmpty()) crash = "No crash has been recorded on this phone. Version " + BuildInfo.VERSION
          + " on Android " + Build.VERSION.RELEASE + " (" + Build.MANUFACTURER + " " + Build.MODEL + ").";
        String text = "Vishal Jarvis " + BuildInfo.VERSION + " · Android " + Build.VERSION.RELEASE
          + " · " + Build.MANUFACTURER + " " + Build.MODEL + "\n\n" + crash;
        runOnUiThread(() -> {
          try {
            startActivity(Intent.createChooser(new Intent(Intent.ACTION_SEND).setType("text/plain")
              .putExtra(Intent.EXTRA_SUBJECT, "Vishal Jarvis " + BuildInfo.VERSION + " report")
              .putExtra(Intent.EXTRA_TEXT, text), "Share crash report"));
          } catch (RuntimeException noShareApp) {
            new AlertDialog.Builder(MainActivity.this).setTitle("Crash report").setMessage(text).setPositiveButton("Close", null).show();
          }
        });
      });
    }
    @JavascriptInterface public void requestPermission(String name) { guard(() -> requestPermissionWork(name)); }
    private void requestPermissionWork(String name) {
      runOnUiThread(() -> {
        if (name == null) return;
        ArrayList<String> needed = new ArrayList<>();
        if ("microphone".equals(name)) { if (!granted(Manifest.permission.RECORD_AUDIO)) needed.add(Manifest.permission.RECORD_AUDIO); }
        else if ("contacts".equals(name)) { if (!granted(Manifest.permission.READ_CONTACTS)) needed.add(Manifest.permission.READ_CONTACTS); }
        else if ("camera".equals(name)) { if (!granted(Manifest.permission.CAMERA)) needed.add(Manifest.permission.CAMERA); }
        else if ("notifications".equals(name)) { if (Build.VERSION.SDK_INT >= 33 && !granted(Manifest.permission.POST_NOTIFICATIONS)) needed.add(Manifest.permission.POST_NOTIFICATIONS); }
        else if ("call".equals(name) || "phone".equals(name)) {
          if (!granted(Manifest.permission.CALL_PHONE)) needed.add(Manifest.permission.CALL_PHONE);
        }
        else if ("sms".equals(name)) {
          if (!granted(Manifest.permission.SEND_SMS)) needed.add(Manifest.permission.SEND_SMS);
        }
        else if ("accessibility".equals(name)) {
          ComponentName sender = new ComponentName(MainActivity.this, UiAutomationService.class);
          Intent direct = new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS);
          try { direct.putExtra(":settings:fragment_args_key", sender.flattenToString());
            direct.putExtra(":settings:show_fragment_args", android.os.Bundle.EMPTY); } catch (RuntimeException ignored) { /* tolerated: the window may already be gone */ }
          try { startActivity(direct); }
          catch (RuntimeException e) { startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)); }
          emit(json("type", "permissions", "state", state(), "message",
            "Enable “Vishal Jarvis message sender” in Accessibility so the agent can press Send itself."));
          return;
        }
        else if ("overlay".equals(name)) {
          if (!Settings.canDrawOverlays(MainActivity.this)) startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + getPackageName())));
          return;
        } else return;
        if (needed.isEmpty()) { emit(json("type", "permissions", "state", state(), "message", "That permission is already granted.")); return; }
        requestPermissions(needed.toArray(new String[0]), PERMISSION_REQUEST);
      });
    }
  }

  /** Result of one streamed model round: visible text, raw parts for echo, and tool calls. */
  private static final class Round {
    final StringBuilder text = new StringBuilder();
    final JSONArray parts = new JSONArray();
    final JSONArray calls = new JSONArray();
  }

  /** Bridges a fire-and-forget Android action into the agent loop. */
  private static final class ActionWaiter {
    private final CountDownLatch latch = new CountDownLatch(1);
    private volatile JSONObject value;
    void complete(JSONObject result) { value = result; latch.countDown(); }
  }

  private void askForTaskNotifications() {
    runOnUiThread(() -> {
      if (foreground && Build.VERSION.SDK_INT >= 33 && !granted(Manifest.permission.POST_NOTIFICATIONS)
          && !prefs.getBoolean("askedTaskNotifications", false)) {
        prefs.edit().putBoolean("askedTaskNotifications", true).apply();
        requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, TASK_NOTIFICATION_REQUEST);
      }
    });
  }

  private void startChat(String input, String id) {
    final String text = input == null ? "" : input.trim();
    if (text.isEmpty() || text.length() > 8000 || id == null || !id.matches("[A-Za-z0-9-]{1,80}")) {
      error("text", "Enter a message between 1 and 8000 characters.", id); return;
    }
    // The user speaking or typing while Jarvis is still talking wins: stop the voice first, so the
    // new question is answered immediately instead of waiting behind the old answer. The answer
    // that was interrupted is dropped from the transcript so it cannot be mistaken for an offer
    // still in progress.
    if (AiTaskEvents.speaking) {
      runOnUiThread(this::stopSpeaking);
      emit(json("type", "interrupted", "reason", "user"));
    }
    if (!prefs.contains("keyCipher")) { error("text", "Add your Gemini API key in Settings.", id); return; }
    synchronized (chatLock) { if (busy) { error("text", "A reply is already running. Stop it or wait.", id); return; } busy = true; }
    ImageAttachment.claim(id);
    DocumentAttachment.claim(id);
    AiTaskStore store = AiTaskStore.get(this);
    AiTaskStore.Task task;
    try {
      if (store.pendingCount() >= 50) throw new IllegalStateException("Too many pending tasks");
      task = store.enqueue(id, text);
      AiTaskCoordinator.schedule(this, task);
    } catch (Exception failure) {
      AiTaskStore.Task saved = store.find(id); if (saved != null) store.fail(id, "Android could not schedule this task.");
      synchronized (chatLock) { busy = false; }
      error("text", "Android could not save or schedule this task. Please try again.", id); return;
    }
    String networkStatus = NetworkDiagnostics.status(this);
    boolean immediate = foreground && !"offline".equals(networkStatus) && !"captive_portal".equals(networkStatus)
      && AiTaskCoordinator.beginForeground(store, id);
    if (!immediate) {
      askForTaskNotifications();
      synchronized (chatLock) { busy = false; }
      emit(json("type", "queued", "id", id, "message", "Saved. This task will run in the background when the network and Android allow it."));
      return;
    }
    synchronized (chatLock) { activeTaskId = id; }
    final int token = generation.incrementAndGet();
    runOnUiThread(() -> {
      if (id.equals(activeTaskId) && foreground) {
        try { startForegroundService(new Intent(MainActivity.this, AiTaskForegroundService.class)); }
        catch (RuntimeException ignored) { /* The durable job remains scheduled if Android rejects foreground start. */ }
      }
      askForTaskNotifications();
    });
    network.execute(() -> {
      try {
        AiTaskStore.Task current = store.find(id);
        if (current == null) return;
        String answer = new AiAgent(MainActivity.this, id, new AiAgent.Listener() {
          public boolean cancelled() { return generation.get() != token; }
          public void connection(HttpsURLConnection connection) {
            activeConnection = connection;
            if (connection != null && generation.get() != token) connection.disconnect();
          }
          public void event(JSONObject event) { if (generation.get() == token) emit(event); }
          public JSONObject tool(String name, JSONObject args) {
            // runTool already answers headless actions directly and sends everything else through
            // the window handlers, which are the ones that can ask for a permission or open a
            // chooser. Nothing is refused here any more: refusing here is what made device actions
            // silently stop working whenever the app briefly reported itself as not foreground.
            return runTool(name, args, id);
          }
        }).run(current);
        if (generation.get() != token) return;
        if (store.complete(id, answer)) {
          AiTaskCoordinator.cancelJobOnly(MainActivity.this, current);
          if (!foreground) AiTaskNotifications.finished(MainActivity.this, current, true);
          emit(json("type", "reply", "id", id, "text", answer));
          if (spokenRepliesOn()) speakText(answer);
        }
      } catch (Exception failure) {
        if (generation.get() == token && !store.isCancelled(id)) {
          if (AiAgent.retryable(failure)) {
            store.requeue(id, AiAgent.safeMessage(failure));
            emit(json("type", "queued", "id", id, "message", "Connection interrupted. Your saved task will retry automatically."));
          } else {
            String message = AiAgent.safeMessage(failure);
            store.fail(id, message); AiTaskCoordinator.cancelJobOnly(MainActivity.this, store.find(id));
            if (failure instanceof GeminiAccess.Failure) {
              GeminiAccess.Failure issue = (GeminiAccess.Failure)failure;
              SharedPreferences.Editor edit = prefs.edit().putString("providerErrorCode", issue.code);
              boolean transientStatus = issue.status == 408 || issue.status == 409 || issue.status == 429 || issue.status >= 500;
              if (!transientStatus) edit.putLong("keyVerifiedAt", 0);
              edit.commit();
            }
            AiTaskStore.Task failed = store.find(id);
            if (!foreground && failed != null) AiTaskNotifications.finished(MainActivity.this, failed, false);
            error("text", message, id);
          }
        }
      } finally {
        boolean stop = false;
        synchronized (chatLock) { if (id.equals(activeTaskId)) { activeTaskId = null; busy = false; stop = true; } }
        AiTaskCoordinator.endForeground(id);
        if (stop) stopService(new Intent(MainActivity.this, AiTaskForegroundService.class));
      }
    });
  }

  /** Executes one tool call for the model and always returns a truthful status object. */
  private JSONObject runTool(String name, JSONObject args, String taskId) {
    if (MobileTools.REMEMBER.equals(name)) {
      String kind = args.optString("kind", "fact");
      String label = args.optString("name", "");
      String value = args.optString("value", "");
      JSONObject saved = (!label.isEmpty() && !value.isEmpty())
        ? MemoryDb.rememberEntity(this, kind.isEmpty() ? "fact" : kind, label, value)
        : MemoryStore.save(this, args.optString("fact", ""), kind.isEmpty() ? "fact" : kind);
      if ("saved".equals(saved.optString("status"))) emit(json("type", "memory", "status", "saved", "state", state()));
      return saved;
    }
    if (!MobileTools.ACTION.equals(name)) return json("status", "unsupported", "message", "That tool is not available in the Vishal Jarvis Android app.");
    // Device actions that need no Activity run straight away, in the foreground or not. Only the
    // ones that open a chooser, a permission prompt or a confirmation use the window path.
    // In the visible app every action that has its own window handler keeps using it (permission
    // prompts, choosers, confirmations). The registry is only the answer for headless tools and
    // for the background case, where nothing can be shown.
    String directAction = args.optString("action", "");
    boolean onlyHeadless = ActionRegistry.newOnly(directAction);
    if (onlyHeadless || (!foreground && ActionRegistry.backgroundSafe(directAction))) {
      JSONObject direct = ActionRegistry.run(this, directAction, args, taskId);
      emit(json("type", "tool-result", "name", name, "result", direct));
      return direct;
    }
    final String actionId = "tool-" + UUID.randomUUID();
    ActionWaiter waiter = new ActionWaiter();
    actionWaiters.put(actionId, waiter);
    try {
      final String payload = args.toString();
      runOnUiThread(() -> {
        if (!foreground) waiter.complete(json("status", "permission_required", "message", "Open Vishal Jarvis to perform this phone action. Nothing was done in the background."));
        else mobileActions.execute(actionId, MobileTools.ACTION, payload);
      });
      if (!waiter.latch.await(45, TimeUnit.SECONDS)) {
        // Never leave the action dangling: cancel it so a stale chooser cannot block the next one.
        runOnUiThread(() -> { mobileActions.cancel(actionId); });
        return json("status", "timeout", "message", "That action did not finish in time. If a chooser or permission prompt is open, answer it and ask again.");
      }
      JSONObject result = waiter.value;
      return result == null ? json("status", "failed", "message", "Android returned no result for that action.") : result;
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      return json("status", "cancelled", "message", "The action was cancelled.");
    } finally {
      actionWaiters.remove(actionId);
    }
  }

  /**
   * Speaks a reply, out loud, with a chain of fallbacks.
   *
   * A missing Bengali voice used to make the app completely silent: the language was set, it
   * failed, and the method returned. Now every usable voice is tried in order (the chosen
   * language, a Bengali voice the engine reports, Indian English, plain English), and if none of
   * them can be set the reply is spoken with the engine's own default voice — reading Bengali
   * text in an English voice is imperfect, but silence is worse. The screen always shows the text
   * as well, and the UI is told which voice was used so the user can see why it sounds different.
   */
  /**
   * Speaks one short line on behalf of the call assistant, with no window involved.
   *
   * Android's text-to-speech needs a live engine object, and the call has no Activity, so the
   * engine is created here for the single sentence and shut down afterwards. Failures are
   * swallowed on purpose: losing a sentence must never break a phone call.
   */
  static void speakForCall(Context context, String text) {
    final TextToSpeech[] holder = new TextToSpeech[1];
    holder[0] = new TextToSpeech(context.getApplicationContext(), status -> {
      TextToSpeech engine = holder[0];
      if (engine == null) return;
      if (status != TextToSpeech.SUCCESS) { try { engine.shutdown(); } catch (RuntimeException ignored) { /* never started */ } return; }
      String tag = JarvisPrefs.get(context).getString("language", "bn-IN");
      boolean ready = false;
      for (Locale candidate : new Locale[]{ Locale.forLanguageTag(tag), Locale.forLanguageTag("bn-BD"),
          Locale.forLanguageTag("bn-IN"), Locale.forLanguageTag("en-IN"), Locale.US }) {
        if (candidate == null) continue;
        int result = engine.setLanguage(candidate);
        if (result != TextToSpeech.LANG_MISSING_DATA && result != TextToSpeech.LANG_NOT_SUPPORTED) { ready = true; break; }
      }
      if (!ready) { try { engine.shutdown(); } catch (RuntimeException ignored) { /* never started */ } return; }
      Bundle params = new Bundle();
      int spoken = engine.speak(SpeechEngine.speakable(text), TextToSpeech.QUEUE_FLUSH, params, "call-assist");
      if (spoken == TextToSpeech.ERROR) { try { engine.shutdown(); } catch (RuntimeException ignored) { /* never started */ } }
    });
  }

  private void speakText(String text) {
    runOnUiThread(() -> {
      if (!foreground) return;
      if (text == null || text.trim().isEmpty()) return;
      if (!ttsReady || tts == null || speech == null) {
        // Startup race: remember the sentence and speak it as soon as the engine is ready.
        pendingSpeech = text;
        if (tts == null) {
          tts = new TextToSpeech(this, status -> {
            ttsReady = status == TextToSpeech.SUCCESS;
            if (!ttsReady) { error("tts", "No ready Android text-to-speech engine is available. The reply is on screen. Install or update \"Speech Recognition & Synthesis\" from the Play Store.", null); return; }
            attachSpeechEngine();
            String queued = pendingSpeech; pendingSpeech = null;
            if (queued != null) speakText(queued);
          });
        }
        return;
      }
      pendingSpeech = null;
      String tag = prefs.getString("language", "bn-IN");
      String reply = SpeechEngine.speakable(text);
      Locale wanted = Locale.forLanguageTag(tag);
      Locale[] candidates = new Locale[]{ wanted, bengaliVoice, Locale.forLanguageTag("bn-BD"), Locale.forLanguageTag("bn-IN"),
        Locale.forLanguageTag("en-IN"), Locale.US };
      java.util.List<String> tried = new java.util.ArrayList<>();
      boolean ready = false;
      for (Locale candidate : candidates) {
        if (candidate == null) continue;
        if (tried.contains(candidate.toLanguageTag())) continue;
        tried.add(candidate.toLanguageTag());
        int result = tts.setLanguage(candidate);
        if (result != TextToSpeech.LANG_MISSING_DATA && result != TextToSpeech.LANG_NOT_SUPPORTED) { ready = true; break; }
      }
      if (!ready) {
        // Last resort: keep whatever voice the engine has, instead of saying nothing.
        emit(json("type", "capability", "tts", false,
          "message", "No installed voice matches this language, so Jarvis is speaking with the phone's default voice. For natural Bengali, install a Bengali voice in Android Settings → System → Languages → Text-to-speech output."));
      }
      // Whole reply, any length: SpeechEngine queues it in sentence-sized chunks.
      if (!speech.speak(reply)) error("tts", "The installed speech engine could not start. The reply is on screen.", null);
    });
  }

  /** Replies are spoken unless the user turned it off; a reply nobody hears looks like a bug. */
  private boolean spokenRepliesOn() { return prefs == null || prefs.getBoolean("spokenReplies", true); }

  /** Builds the SpeechEngine once the TTS object is ready (shared by onCreate and the retry path). */
  private void attachSpeechEngine() {
    if (tts == null || speech != null) return;
    speech = new SpeechEngine(tts);
    speech.setListener(new SpeechEngine.Listener() {
      @Override public void onStart() { AiTaskEvents.speaking = true; emit(json("type", "speech", "active", true)); }
      @Override public void onDone() { AiTaskEvents.speaking = false; emit(json("type", "speech", "active", false)); }
      @Override public void onError(String message) { AiTaskEvents.speaking = false; emit(json("type", "speech", "active", false)); error("tts", message, null); }
    });
    try {
      java.util.Set<java.util.Locale> locales = tts.getAvailableLanguages();
      if (locales != null) {
        for (java.util.Locale locale : locales) {
          if (locale != null && "bn".equals(locale.getLanguage())) { bengaliVoice = locale; break; }
        }
      }
    } catch (RuntimeException ignored) { /* tolerated: the window may already be gone */ }
  }

  private void stopSpeaking() {
    if (speech != null) speech.stop();
    AiTaskEvents.speaking = false;
    emit(json("type", "speech", "active", false));
  }

  private void writeKey(SharedPreferences.Editor edit, String key) throws Exception { CredentialVault.write(edit, key); }
  private String readKey() throws Exception { return CredentialVault.read(prefs); }
  private static byte[] readLimited(InputStream input, int limit) throws IOException {
    try (InputStream source = input; ByteArrayOutputStream out = new ByteArrayOutputStream()) {
      byte[] bytes = new byte[8192]; int count;
      while ((count = source.read(bytes)) != -1) { if (out.size() + count > limit) throw new IOException("Response exceeds the size limit."); out.write(bytes, 0, count); }
      return out.toByteArray();
    }
  }
  private static JSONObject json(Object... values) {
    JSONObject result = new JSONObject();
    try { for (int i = 0; i < values.length; i += 2) result.put(String.valueOf(values[i]), values[i+1]); } catch (JSONException ignored) { /* tolerated: the window may already be gone */ }
    return result;
  }
  private void emit(JSONObject event) { runOnUiThread(() -> { if (web != null && !isFinishing()) web.evaluateJavascript("window.MyraaEvents&&window.MyraaEvents(" + event.toString() + ")", null); }); }
  private void error(String service, String message, String id) { JSONObject value = json("type", "error", "service", service, "message", message); if (id != null) try { value.put("id", id); } catch (JSONException ignored) { /* tolerated: the window may already be gone */ } emit(value); }

  private void emitNativeAudio(byte[] pcm, boolean speech) {
    if (web == null || isFinishing() || audioEmitBusy) return;
    audioEmitBusy = true;
    final String payload = Base64.encodeToString(pcm, Base64.NO_WRAP);
    runOnUiThread(() -> {
      try {
        if (web != null && !isFinishing()) {
          web.evaluateJavascript("window.MyraaNativeAudio&&window.MyraaNativeAudio('" + payload + "'," + (speech ? "true" : "false") + ")", unused -> audioEmitBusy = false);
        } else audioEmitBusy = false;
      } catch (RuntimeException ignored) { audioEmitBusy = false; }
    });
  }
  private void cancelLive() {
    pendingLiveId = null; currentLiveId = null; liveGeneration.incrementAndGet(); allowBackgroundLive=false; stopService(new Intent(this,LiveVoiceService.class));
    if (audioCapture != null) audioCapture.stop();
    HttpsURLConnection connection = liveConnection; liveConnection = null; if (connection != null) connection.disconnect();
  }
  private void provisionLive(String id) { provisionLive(id, false); }
  private void provisionLive(String id, boolean renew) {
    pendingLiveId = null; currentLiveId = id; final int mine = liveGeneration.incrementAndGet();
    if((foreground || renew)&&(Build.VERSION.SDK_INT<33||checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)==PackageManager.PERMISSION_GRANTED)){
      try{startForegroundService(new Intent(this,LiveVoiceService.class));allowBackgroundLive=true;}catch(RuntimeException ignored){if(!renew)allowBackgroundLive=false;}
    }
    liveNetwork.execute(() -> {
      try {
        String key = readKey(); if (key.isEmpty()) throw new IOException("Missing key");
        String token = provider.createLiveToken(key, connection -> {
          if (liveGeneration.get() != mine) { if (connection != null) connection.disconnect(); }
          else liveConnection = connection;
        });
        runOnUiThread(() -> {
          if (liveGeneration.get() != mine || (!foreground && !renew && !allowBackgroundLive)) return;
          String model = prefs.getString("liveModel", "gemini-3.1-flash-live-preview");
          if (!model.matches("[A-Za-z0-9._-]{1,100}")) model = "gemini-3.1-flash-live-preview";
          emit(json("type", "live-token", "id", id, "token", token, "model", model, "voice", "Aoede",
            "backgroundLive", allowBackgroundLive, "renew", renew,
            "instruction", MobileTools.systemPrompt(personality, memoryArray()) + "\nYou are on a live voice call: reply in one short spoken sentence (about 15 words) unless the user asks for detail, never chat or add filler, use the mobile_action tool for device tasks, and never invent a result. Messages stay drafts until the user confirms send. For call, set recipient to the name exactly as spoken in Bengali script or Latin letters, including nicknames; do not translate, respell or invent a number — the phone matches contacts phonetically. If several people could match, wait for the phone confirmation instead of guessing. To open an app, call mobile_action with action open_app and the short name the user said (ইউটিউব, হোয়াটসঅ্যাপ, youtube kholo are all resolved on the phone); never say you cannot open apps. If the result says not_installed, read its suggestions back honestly."));
        });
      } catch (Exception error) {
        if (liveGeneration.get() == mine) emit(json("type", "live-error", "id", id, "message",
          error instanceof GeminiAccess.Failure ? error.getMessage() : "Could not start Gemini Live. Check the saved key, network and Live model access in Settings."));
      }
    });
  }
  @Override protected void onActivityResult(int request, int result, Intent data) {
    super.onActivityResult(request, result, data);
    if (request == PICK_IMAGE_REQUEST) { if (result == RESULT_OK && data != null && data.getData() != null) stageImage(data.getData()); return; }
    if (request == CAPTURE_IMAGE_REQUEST) {
      if (result == RESULT_OK && CaptureProvider.file(this).length() > 0) stageImage(Uri.fromFile(CaptureProvider.file(this)));
      else if (result == RESULT_OK && data != null && data.getData() != null) stageImage(data.getData());
      return;
    }
    if (request == PICK_DOCUMENT_REQUEST) {
      if (result == RESULT_OK && data != null && data.getData() != null) stageDocument(data.getData());
      return;
    }
    if (request != EXPORT_TRANSCRIPT_REQUEST || result != RESULT_OK || data == null || data.getData() == null) return;
    Uri destination = data.getData();
    settingsNetwork.execute(() -> {
      try (InputStream in = new FileInputStream(TranscriptProvider.file(this));
           OutputStream out = getContentResolver().openOutputStream(destination, "wt")) {
        if (out == null) throw new IOException("No document stream");
        byte[] buffer = new byte[8192]; int n; while ((n = in.read(buffer)) != -1) out.write(buffer, 0, n);
        emit(json("type", "clipboard", "message", "Conversation text file saved."));
      } catch (Exception e) { error("export", "Could not save the conversation file.", null); }
    });
  }
  @Override public void onRequestPermissionsResult(int request, String[] permissions, int[] results) {
    super.onRequestPermissionsResult(request, permissions, results);
    if (request == TASK_NOTIFICATION_REQUEST) { emit(json("type", "permissions", "state", state())); return; }
    if (request == ProActions.CALENDAR_REQUEST || request == ProActions.CALENDAR_WRITE_REQUEST) {
      emit(json("type", "permissions", "state", state()));
      // Without this the delete request waits forever after the user allows write access.
      if (request == ProActions.CALENDAR_WRITE_REQUEST) getWindow().getDecorView().post(() -> mobileActions.calendarPermissionResult());
      return;
    }
    if (request == PERMISSION_REQUEST) { emit(json("type", "permissions", "state", state())); return; }
    if (request == CAMERA_FOR_PHOTO_REQUEST) {
      if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) launchCamera();
      else emit(json("type", "image-attached", "ok", false, "message", "Camera permission was denied. You can still choose a photo from the gallery."));
      return;
    }
    if(request==MobileActions.CONTACTS_REQUEST||request==MobileActions.CALL_LOG_REQUEST){mobileActions.permissionResult();return;}
    if(request==MobileActions.PHONE_REQUEST){mobileActions.phonePermissionResult();emit(json("type","permissions","state",state()));return;}
    if(request==MobileActions.TORCH_REQUEST){mobileActions.torchPermissionResult();return;}
    if (request != MICROPHONE_REQUEST || pendingLiveId == null) return;
    if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
      if (foreground) provisionLive(pendingLiveId);
    } else {
      String id = pendingLiveId; pendingLiveId = null;
      emit(json("type", "live-error", "id", id, "message", "Microphone permission was denied. You can enable it in Android app settings."));
    }
  }
  @Override protected void onPause() {
    foreground=false;AiTaskEvents.visible=false;super.onPause();boolean keep=allowBackgroundLive;
    if(web!=null){web.evaluateJavascript("window.dispatchEvent(new CustomEvent('myraa:lifecycle',{detail:{active:false,backgroundLive:"+keep+",permissionPrompt:"+(pendingLiveId!=null)+"}}))",null);if(!keep)web.onPause();}
    if(pendingLiveId==null&&!keep)cancelLive();/* TTS intentionally not stopped on pause: read-aloud must finish in background; stopSpeech()/onDestroy still stop it. */
  }
  @Override protected void onResume() {
    foreground=true;AiTaskEvents.visible=true;super.onResume();AiTaskCoordinator.recover(this);if(pendingUiAction!=null){Runnable action=pendingUiAction;pendingUiAction=null;action.run();}if(pendingLiveId!=null&&checkSelfPermission(Manifest.permission.RECORD_AUDIO)==PackageManager.PERMISSION_GRANTED)provisionLive(pendingLiveId);
    if(web!=null){web.onResume();emit(json("type", "state", "state", state()));web.evaluateJavascript("window.dispatchEvent(new CustomEvent('myraa:lifecycle',{detail:{active:true,backgroundLive:"+allowBackgroundLive+"}}))",null);}
    if(pendingAutoVoice&&web!=null)emit(json("type","auto-voice"));
    if(JarvisPrefs.wakeWord(this)&&!WakeWordService.running)WakeWordService.start(this);
    if(web!=null&&UiAutomationService.available()!=lastAccessibility){lastAccessibility=UiAutomationService.available();emit(json("type","permissions","state",state()));}
  }
  @Override protected void onStop(){super.onStop();if(shownOverLock){shownOverLock=false;setShowWhenLocked(false);setTurnScreenOn(false);getWindow().clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);}if(!allowBackgroundLive&&pendingLiveId==null){cancelLive();if(web!=null)web.evaluateJavascript("window.dispatchEvent(new CustomEvent('myraa:lifecycle',{detail:{active:false}}))",null);}}
  @Override protected void onDestroy() {
    pendingUiAction=null;if(mobileActions!=null)mobileActions.close();
    if(stopLiveRegistered){try{unregisterReceiver(stopLiveReceiver);}catch(IllegalArgumentException ignored){}stopLiveRegistered=false;}
    cancelLive(); liveNetwork.shutdownNow();
    synchronized (settingsLock) { settingsGeneration.incrementAndGet(); if (settingsConnection != null) settingsConnection.disconnect(); }
    settingsNetwork.shutdownNow();
    generation.incrementAndGet();
    if (activeConnection != null) activeConnection.disconnect();
    cancelActionWaiters();
    String interrupted;
    synchronized (chatLock) { interrupted = activeTaskId; activeTaskId = null; busy = false; }
    if (interrupted != null) {
      AiTaskStore.get(this).requeue(interrupted, "The app closed; waiting for the background job.");
      AiTaskCoordinator.endForeground(interrupted);
      AiTaskCoordinator.recover(this);
      stopService(new Intent(this, AiTaskForegroundService.class));
    }
    AiTaskEvents.visible = false; AiTaskEvents.detach(taskListener);
    network.shutdownNow();
    if (isFinishing()) { if (speech != null) { speech.stop(); speech.shutdown(); speech = null; } if (tts != null) { tts.stop(); tts.shutdown(); tts = null; } ttsReady = false; }
    if (isFinishing() && web != null) { web.removeJavascriptInterface("MyraaNative"); web.destroy(); web = null; }
    super.onDestroy();
  }
}

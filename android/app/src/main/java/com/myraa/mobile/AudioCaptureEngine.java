package com.myraa.mobile;

import android.content.Context;
import android.media.*;
import android.media.audiofx.AcousticEchoCanceler;
import android.media.audiofx.AutomaticGainControl;
import android.media.audiofx.NoiseSuppressor;
import android.os.Build;
import org.json.JSONObject;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Native 16 kHz capture with device effects (when present) plus software
 * speech enhancement. Designed so hardware and software never both apply
 * aggressive noise suppression on the same path.
 */
public final class AudioCaptureEngine {
  public interface Listener {
    void onAudio(byte[] pcm16le, boolean speech);
    void onDiagnostics(JSONObject diagnostics);
    void onError(String message);
  }

  private final Context context;
  private final Listener listener;
  private final SpeechEnhancer enhancer = new SpeechEnhancer();
  private final AtomicBoolean running = new AtomicBoolean();
  private Thread thread;
  private AudioRecord recorder;
  private NoiseSuppressor suppressor;
  private AcousticEchoCanceler echoCanceler;
  private AutomaticGainControl gainControl;
  private AudioManager audioManager;
  private AudioManager.OnAudioFocusChangeListener focusListener;
  private boolean hardwareNs;
  private boolean hardwareAec;
  private boolean hardwareAgc;
  private int sessionId = AudioManager.AUDIO_SESSION_ID_GENERATE;
  private int previousMode = AudioManager.MODE_NORMAL;
  private boolean modeChanged;
  private int sourceUsed = MediaRecorder.AudioSource.VOICE_COMMUNICATION;
  private int actualRate = SpeechEnhancer.SAMPLE_RATE;
  private volatile JSONObject lastDiagnostics = new JSONObject();

  public AudioCaptureEngine(Context context, Listener listener) {
    this.context = context.getApplicationContext();
    this.listener = listener;
  }

  public boolean isRunning() { return running.get(); }
  public JSONObject diagnostics() { return lastDiagnostics; }
  public SpeechEnhancer enhancer() { return enhancer; }

  public synchronized boolean start() {
    if (running.get()) return true;
    audioManager = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
    if (audioManager != null) {
      // Without communication mode many devices never engage the hardware
      // echo canceller, so the model's own loudspeaker output re-enters the
      // microphone and aborts its response. Engage it for the call and
      // restore the previous mode afterwards.
      previousMode = audioManager.getMode();
      try { audioManager.setMode(AudioManager.MODE_IN_COMMUNICATION); modeChanged = true; }
      catch (RuntimeException ignored) { modeChanged = false; }
    }
    try {
      if (audioManager != null) {
        focusListener = new AudioManager.OnAudioFocusChangeListener() {
          public void onAudioFocusChange(int change) { /* ducking only; capture stays up */ }
        };
        audioManager.requestAudioFocus(focusListener, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN_TRANSIENT);
      }
    } catch (RuntimeException ignored) { /* nothing to do on release of a temporary object */ }
    int[] sources = new int[]{
      MediaRecorder.AudioSource.VOICE_COMMUNICATION,
      MediaRecorder.AudioSource.VOICE_RECOGNITION,
      MediaRecorder.AudioSource.MIC
    };
    int[] rates = new int[]{16000, 48000, 44100, 8000};
    AudioRecord created = null;
    int rate = 16000;
    int source = sources[0];
    outer:
    for (int candidateSource : sources) {
      for (int candidateRate : rates) {
        int min = AudioRecord.getMinBufferSize(candidateRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
        if (min <= 0) continue;
        int buffer = Math.max(min, candidateRate / 5);
        try {
          AudioRecord trial = new AudioRecord(candidateSource, candidateRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, buffer);
          if (trial.getState() == AudioRecord.STATE_INITIALIZED) {
            created = trial;
            rate = candidateRate;
            source = candidateSource;
            break outer;
          }
          trial.release();
        } catch (RuntimeException ignored) { /* nothing to do on release of a temporary object */ }
      }
    }
    if (created == null) {
      publishError("Microphone capture is unavailable on this device.");
      return false;
    }
    recorder = created;
    actualRate = rate;
    sourceUsed = source;
    sessionId = recorder.getAudioSessionId();
    hardwareNs = enableNs(sessionId);
    hardwareAec = enableAec(sessionId);
    hardwareAgc = enableAgc(sessionId);
    enhancer.setLightMode(hardwareNs);
    running.set(true);
    publishDiagnostics(true);
    thread = new Thread(this::loop, "vishal-jarvis-audio");
    thread.setPriority(Thread.MAX_PRIORITY);
    thread.start();
    return true;
  }

  public synchronized void stop() {
    running.set(false);
    Thread worker = thread;
    thread = null;
    if (worker != null) {
      try { worker.interrupt(); worker.join(500); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
    }
    releaseEffects();
    if (recorder != null) {
      try { recorder.stop(); } catch (RuntimeException ignored) { /* a recorder that never started */ }
      try { recorder.release(); } catch (RuntimeException ignored) { /* a recorder that is already released */ }
      recorder = null;
    }
    if (audioManager != null && focusListener != null) {
      try { audioManager.abandonAudioFocus(focusListener); } catch (RuntimeException ignored) { /* audio focus that was never taken */ }
    }
    if (audioManager != null && modeChanged) {
      try { audioManager.setMode(previousMode); } catch (RuntimeException ignored) { /* an audio mode the device refuses */ }
      modeChanged = false;
    }
    focusListener = null;
  }

  private void loop() {
    AudioRecord current = recorder;
    if (current == null) return;
    try { current.startRecording(); }
    catch (RuntimeException error) {
      running.set(false);
      publishError("Microphone recording could not start on this device.");
      return;
    }
    int hopBytes = SpeechEnhancer.FRAME * 2;
    byte[] deviceFrame = new byte[Math.max(hopBytes, actualRate / 50 * 2)];
    byte[] work16k = new byte[hopBytes];
    int workFill = 0;
    Resampler resampler = actualRate == 16000 ? null : new Resampler(actualRate, 16000);
    long start = System.currentTimeMillis();
    enhancer.resetDiagnosticsWindow();
    try {
      while (running.get()) {
        int read = current.read(deviceFrame, 0, deviceFrame.length);
        if (read == AudioRecord.ERROR_DEAD_OBJECT || read == AudioRecord.ERROR_INVALID_OPERATION) {
          if (!recover()) break;
          current = recorder;
          if (current == null) break;
          continue;
        }
        if (read <= 0) continue;
        byte[] sixteen = resampler == null ? slice(deviceFrame, read) : resampler.push(deviceFrame, read);
        if (sixteen.length == 0) continue;
        int offset = 0;
        while (offset < sixteen.length) {
          int take = Math.min(hopBytes - workFill, sixteen.length - offset);
          System.arraycopy(sixteen, offset, work16k, workFill, take);
          workFill += take;
          offset += take;
          if (workFill < hopBytes) break;
          boolean speech;
          if (!enhancer.isCalibrated() && System.currentTimeMillis() - start < 450) {
            enhancer.calibrateNoise(work16k, 0, hopBytes);
            speech = false;
          } else {
            speech = enhancer.process(work16k, 0, hopBytes);
          }
          byte[] copy = new byte[hopBytes];
          System.arraycopy(work16k, 0, copy, 0, hopBytes);
          listener.onAudio(copy, speech);
          workFill = 0;
        }
        publishDiagnostics(false);
      }
    } catch (RuntimeException error) {
      publishError("Microphone capture stopped unexpectedly and will try to recover on reconnect.");
    } finally {
      publishDiagnostics(true);
    }
  }

  private boolean recover() {
    try {
      releaseEffects();
      if (recorder != null) {
        try { recorder.release(); } catch (RuntimeException ignored) { /* a recorder that is already released */ }
      }
      Thread.sleep(80);
      if (!running.get()) return false;
      int min = AudioRecord.getMinBufferSize(actualRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
      recorder = new AudioRecord(sourceUsed, actualRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, Math.max(min, actualRate / 5));
      if (recorder.getState() != AudioRecord.STATE_INITIALIZED) return false;
      sessionId = recorder.getAudioSessionId();
      hardwareNs = enableNs(sessionId);
      hardwareAec = enableAec(sessionId);
      hardwareAgc = enableAgc(sessionId);
      enhancer.setLightMode(hardwareNs);
      recorder.startRecording();
      return true;
    } catch (Exception ignored) {
      return false;
    }
  }

  private boolean enableNs(int session) {
    try {
      if (!NoiseSuppressor.isAvailable()) return false;
      suppressor = NoiseSuppressor.create(session);
      if (suppressor == null) return false;
      suppressor.setEnabled(true);
      return suppressor.getEnabled();
    } catch (Throwable ignored) { return false; }
  }

  private boolean enableAec(int session) {
    try {
      if (!AcousticEchoCanceler.isAvailable()) return false;
      echoCanceler = AcousticEchoCanceler.create(session);
      if (echoCanceler == null) return false;
      echoCanceler.setEnabled(true);
      return echoCanceler.getEnabled();
    } catch (Throwable ignored) { return false; }
  }

  private boolean enableAgc(int session) {
    try {
      if (!AutomaticGainControl.isAvailable()) return false;
      gainControl = AutomaticGainControl.create(session);
      if (gainControl == null) return false;
      gainControl.setEnabled(true);
      return gainControl.getEnabled();
    } catch (Throwable ignored) { return false; }
  }

  private void releaseEffects() {
    try { if (suppressor != null) suppressor.release(); } catch (Throwable ignored) { /* nothing to do on release of a temporary object */ }
    try { if (echoCanceler != null) echoCanceler.release(); } catch (Throwable ignored) { /* nothing to do on release of a temporary object */ }
    try { if (gainControl != null) gainControl.release(); } catch (Throwable ignored) { /* nothing to do on release of a temporary object */ }
    suppressor = null; echoCanceler = null; gainControl = null;
  }

  private void publishDiagnostics(boolean force) {
    JSONObject value = new JSONObject();
    try {
      value.put("nativeCapture", true)
        .put("sampleRateRequested", 16000)
        .put("sampleRateActual", actualRate)
        .put("source", sourceName(sourceUsed))
        .put("hardwareNoiseSuppressor", hardwareNs)
        .put("hardwareAec", hardwareAec)
        .put("hardwareAgc", hardwareAgc)
        .put("softwareNs", hardwareNs ? "light" : "adaptive")
        .put("softwareAgc", true)
        .put("calibrated", enhancer.isCalibrated())
        .put("speaking", enhancer.isSpeaking())
        .put("noiseFloorDb", Math.round(enhancer.noiseFloorDb() * 10) / 10.0)
        .put("inputRmsDb", Math.round(enhancer.inputRmsDb() * 10) / 10.0)
        .put("clipping", enhancer.clipping())
        .put("nsStrength", Math.round(enhancer.nsStrength() * 100) / 100.0)
        .put("latencyMs", enhancer.latencyMs() + (actualRate == 16000 ? 20 : 35))
        .put("audioSessionId", sessionId);
      if (audioManager != null) {
        value.put("microphoneMute", audioManager.isMicrophoneMute());
        value.put("mode", audioManager.getMode());
        if (Build.VERSION.SDK_INT >= 28) {
          try { value.put("microphones", audioManager.getMicrophones().size()); } catch (Exception ignored) { /* nothing to do on release of a temporary object */ }
        }
      }
    } catch (Exception ignored) { /* nothing to do on release of a temporary object */ }
    lastDiagnostics = value;
    if (force) listener.onDiagnostics(value);
  }

  private void publishError(String message) {
    JSONObject value = new JSONObject();
    try { value.put("nativeCapture", false).put("error", message); } catch (Exception ignored) { /* nothing to do on release of a temporary object */ }
    lastDiagnostics = value;
    listener.onError(message);
  }

  private static String sourceName(int source) {
    if (source == MediaRecorder.AudioSource.VOICE_COMMUNICATION) return "VOICE_COMMUNICATION";
    if (source == MediaRecorder.AudioSource.VOICE_RECOGNITION) return "VOICE_RECOGNITION";
    if (source == MediaRecorder.AudioSource.MIC) return "MIC";
    return String.valueOf(source);
  }

  private static byte[] slice(byte[] data, int length) {
    if (length == data.length) return data;
    byte[] out = new byte[length];
    System.arraycopy(data, 0, out, 0, length);
    return out;
  }

  /** Linear resampler used only when the device cannot open 16 kHz. */
  static final class Resampler {
    private final double step;
    private double position;
    private short previous;
    Resampler(int from, int to) { this.step = from / (double) to; }
    byte[] push(byte[] in, int length) {
      int samples = length / 2;
      int outCount = Math.max(0, (int) ((samples - 1) / step));
      byte[] out = new byte[outCount * 2];
      int written = 0;
      int index = 0;
      while (position < samples - 1 && written < outCount) {
        int i = (int) position;
        double frac = position - i;
        short a = i == 0 ? previous : sample(in, i - 1);
        short b = sample(in, i);
        int mixed = (int) (a + (b - a) * frac);
        out[written * 2] = (byte) (mixed & 0xff);
        out[written * 2 + 1] = (byte) ((mixed >> 8) & 0xff);
        written++;
        position += step;
        index = i;
      }
      previous = sample(in, Math.max(0, samples - 1));
      position -= samples;
      if (position < 0) position = 0;
      if (written == outCount) return out;
      byte[] slim = new byte[written * 2];
      System.arraycopy(out, 0, slim, 0, slim.length);
      return slim;
    }
    private static short sample(byte[] in, int i) {
      int lo = in[i * 2] & 0xff;
      int hi = in[i * 2 + 1];
      return (short) ((hi << 8) | lo);
    }
  }
}

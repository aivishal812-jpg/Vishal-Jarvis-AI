package com.myraa.mobile;

/**
 * Real-time speech enhancement for 16 kHz mono PCM.
 * Adaptive Wiener spectral subtraction, high-pass, AGC, and VAD.
 * Strength follows measured noise so quiet rooms stay natural and
 * traffic / wind / crowd noise is suppressed without a second pass
 * that would hollow out the voice.
 */
public final class SpeechEnhancer {
  public static final int SAMPLE_RATE = 16000;
  public static final int HOP = 160;          // 10 ms
  public static final int FFT = 512;          // 32 ms analysis window
  public static final int FRAME = HOP * 2;    // 20 ms public I/O

  private final float[] hann = new float[FFT];
  private final float[] inFifo = new float[FFT];
  private final float[] outFifo = new float[FFT];
  private final float[] re = new float[FFT];
  private final float[] im = new float[FFT];
  private final float[] windowed = new float[FFT];
  private final float[] noisePsd = new float[FFT / 2 + 1];
  private final float[] smoothPsd = new float[FFT / 2 + 1];
  private final float[] lastGain = new float[FFT / 2 + 1];
  private final float[] prevHigh = new float[2];
  private int fifoFill;
  private int outRead;
  private int processedHops;
  private boolean calibrated;
  private float noiseRms = 0.02f;
  private float speechRms = 0.08f;
  private float agcGain = 1f;
  private float envelope;
  private int speechHold;
  private int noiseHold;
  private boolean speaking;
  private float nsStrength = 1f;
  private boolean lightMode;
  private int clipCount;
  private int sampleCount;
  private float peak;
  private float lastRms;
  private float dc;
  private static final float TARGET_RMS = 0.12f;
  private static final float GMIN_STRONG = 0.06f;
  private static final float GMIN_LIGHT = 0.18f;

  public SpeechEnhancer() {
    for (int i = 0; i < FFT; i++) {
      hann[i] = 0.5f * (1f - (float) Math.cos(2.0 * Math.PI * i / (FFT - 1)));
    }
    for (int i = 0; i < lastGain.length; i++) lastGain[i] = 1f;
  }

  /** Light software NS when the device already runs a hardware suppressor. */
  public void setLightMode(boolean light) { this.lightMode = light; }

  public boolean isCalibrated() { return calibrated; }
  public boolean isSpeaking() { return speaking; }
  public float noiseFloorDb() { return db(noiseRms); }
  public float inputRmsDb() { return db(lastRms); }
  public float peak() { return peak; }
  public boolean clipping() { return sampleCount > 0 && clipCount * 100 > sampleCount; }
  public float nsStrength() { return nsStrength; }
  public int latencyMs() { return 1000 * HOP / SAMPLE_RATE + 20; }

  public void resetDiagnosticsWindow() { clipCount = 0; sampleCount = 0; peak = 0; }

  /**
   * Process one 20 ms (320 sample) little-endian PCM16 frame in place.
   * Returns true when the frame is classified as speech or hangover.
   */
  public boolean process(byte[] pcm, int offset, int length) {
    int samples = length / 2;
    float[] input = new float[samples];
    float energy = 0;
    for (int i = 0; i < samples; i++) {
      int lo = pcm[offset + i * 2] & 0xff;
      int hi = pcm[offset + i * 2 + 1];
      float x = (short) ((hi << 8) | lo) / 32768f;
      dc = dc * 0.995f + x * 0.005f;
      x -= dc;
      x = highPass(x);
      input[i] = x;
      energy += x * x;
      float ax = Math.abs(x);
      if (ax > peak) peak = ax;
      if (ax > 0.98f) clipCount++;
      sampleCount++;
    }
    lastRms = (float) Math.sqrt(energy / Math.max(1, samples));
    boolean vad = observeVad(input, lastRms);

    int produced = 0;
    float[] output = new float[samples];
    int cursor = 0;
    while (cursor < samples) {
      int take = Math.min(HOP - fifoFill, samples - cursor);
      System.arraycopy(input, cursor, inFifo, FFT - HOP + fifoFill, take);
      fifoFill += take;
      cursor += take;
      if (fifoFill < HOP) break;
      float[] hop = enhanceHop(vad);
      fifoFill = 0;
      System.arraycopy(inFifo, HOP, inFifo, 0, FFT - HOP);
      int copy = Math.min(HOP, samples - produced);
      System.arraycopy(hop, 0, output, produced, copy);
      produced += copy;
    }
    if (produced < samples) {
      for (int i = produced; i < samples; i++) output[i] = input[i] * 0.2f;
    }
    applyAgc(output, vad);
    for (int i = 0; i < samples; i++) {
      float y = output[i];
      if (y > 0.97f) y = 0.97f;
      if (y < -0.97f) y = -0.97f;
      int v = Math.round(y * 32767f);
      pcm[offset + i * 2] = (byte) (v & 0xff);
      pcm[offset + i * 2 + 1] = (byte) ((v >> 8) & 0xff);
    }
    return speaking;
  }

  public void calibrateNoise(byte[] pcm, int offset, int length) {
    boolean previous = lightMode;
    lightMode = false;
    process(pcm, offset, length);
    lightMode = previous;
    if (lastRms > 1e-5f) noiseRms = 0.8f * noiseRms + 0.2f * lastRms;
    adaptStrength();
    processedHops++;
    if (processedHops >= 20) calibrated = true; // ~400 ms
  }

  private float highPass(float x) {
    // Biquad-ish 1-pole at ~90 Hz to drop wind rumble without thinning vowels.
    float cutoff = 0.035f;
    prevHigh[0] += cutoff * (x - prevHigh[0]);
    float y = x - prevHigh[0];
    return y;
  }

  private boolean observeVad(float[] input, float rms) {
    int zc = 0;
    for (int i = 1; i < input.length; i++) {
      if ((input[i] >= 0) != (input[i - 1] >= 0)) zc++;
    }
    float zcr = zc / (float) input.length;
    float adaptive = Math.max(0.008f, noiseRms * (3.2f + 1.4f * nsStrength));
    boolean band = rms >= adaptive && zcr > 0.01f && zcr < 0.35f;
    if (!calibrated) {
      noiseHold++;
      speaking = false;
      return false;
    }
    if (band) {
      speechHold = 18; // 360 ms hangover so mid-sentence pauses are not cut
      speechRms = 0.9f * speechRms + 0.1f * rms;
      speaking = true;
      return true;
    }
    if (speechHold > 0) {
      speechHold--;
      speaking = true;
      return true;
    }
    speaking = false;
    noiseRms = 0.95f * noiseRms + 0.05f * rms;
    adaptStrength();
    return false;
  }

  private void adaptStrength() {
    float snr = speechRms / Math.max(1e-5f, noiseRms);
    if (snr < 2.5f) nsStrength = 1.35f;
    else if (snr < 6f) nsStrength = 1.05f;
    else nsStrength = 0.7f;
    if (lightMode) nsStrength *= 0.45f;
  }

  private float[] enhanceHop(boolean vad) {
    for (int i = 0; i < FFT; i++) {
      windowed[i] = inFifo[i] * hann[i];
      re[i] = windowed[i];
      im[i] = 0;
    }
    fft(re, im, false);
    int bins = FFT / 2 + 1;
    float gmin = lightMode ? GMIN_LIGHT : GMIN_STRONG;
    float oversub = lightMode ? 0.7f * nsStrength : 1.15f * nsStrength;
    for (int k = 0; k < bins; k++) {
      float psd = re[k] * re[k] + im[k] * im[k];
      smoothPsd[k] = 0.7f * smoothPsd[k] + 0.3f * psd;
      if (!vad || !calibrated) {
        noisePsd[k] = Math.max(1e-12f, 0.92f * noisePsd[k] + 0.08f * smoothPsd[k]);
      }
      float hz = k * (SAMPLE_RATE / (float) FFT);
      float extra = 1f;
      if (hz < 90) extra = 3.2f;            // wind / rumble
      else if (hz < 250) extra = 1.4f;      // traffic body
      else if (hz > 7000) extra = 1.6f;
      float n = noisePsd[k] * oversub * extra;
      float postSnr = Math.max(0f, smoothPsd[k] / Math.max(1e-12f, n) - 1f);
      float wiener = postSnr / (postSnr + 1f);
      if (hz >= 300 && hz <= 3400) {
        wiener = Math.max(wiener, 0.35f);   // preserve vowel formants
      }
      float gain = Math.max(gmin, wiener);
      gain = 0.55f * lastGain[k] + 0.45f * gain;
      lastGain[k] = gain;
      re[k] *= gain;
      im[k] *= gain;
      if (k > 0 && k < FFT - k) {
        re[FFT - k] *= gain;
        im[FFT - k] *= gain;
      }
    }
    fft(re, im, true);
    float[] hop = new float[HOP];
    for (int i = 0; i < FFT; i++) {
      outFifo[i] += re[i] * hann[i];
    }
    System.arraycopy(outFifo, 0, hop, 0, HOP);
    System.arraycopy(outFifo, HOP, outFifo, 0, FFT - HOP);
    for (int i = FFT - HOP; i < FFT; i++) outFifo[i] = 0;
    return hop;
  }

  private void applyAgc(float[] output, boolean vad) {
    float energy = 0;
    for (int i = 0; i < output.length; i++) energy += output[i] * output[i];
    float rms = (float) Math.sqrt(energy / Math.max(1, output.length));
    envelope = 0.86f * envelope + 0.14f * rms;
    float target = lightMode ? TARGET_RMS * 0.85f : TARGET_RMS;
    float desired = envelope > 1e-4f ? target / envelope : 1f;
    if (desired > 8f) desired = 8f;
    if (desired < 0.35f) desired = 0.35f;
    float speed = vad ? 0.08f : 0.03f;
    agcGain += speed * (desired - agcGain);
    for (int i = 0; i < output.length; i++) output[i] *= agcGain;
  }

  static void fft(float[] re, float[] im, boolean inverse) {
    int n = re.length;
    if (inverse) {
      for (int i = 0; i < n; i++) im[i] = -im[i];
    }
    int j = 0;
    for (int i = 1; i < n; i++) {
      int bit = n >> 1;
      for (; (j & bit) != 0; bit >>= 1) j &= ~bit;
      j |= bit;
      if (i < j) {
        float tr = re[i]; re[i] = re[j]; re[j] = tr;
        float ti = im[i]; im[i] = im[j]; im[j] = ti;
      }
    }
    for (int len = 2; len <= n; len <<= 1) {
      float ang = (float) (-2 * Math.PI / len);
      float wlenRe = (float) Math.cos(ang);
      float wlenIm = (float) Math.sin(ang);
      for (int i = 0; i < n; i += len) {
        float wRe = 1f, wIm = 0f;
        int half = len >> 1;
        for (int k = 0; k < half; k++) {
          int u = i + k, v = u + half;
          float tRe = wRe * re[v] - wIm * im[v];
          float tIm = wRe * im[v] + wIm * re[v];
          re[v] = re[u] - tRe;
          im[v] = im[u] - tIm;
          re[u] += tRe;
          im[u] += tIm;
          float nRe = wRe * wlenRe - wIm * wlenIm;
          wIm = wRe * wlenIm + wIm * wlenRe;
          wRe = nRe;
        }
      }
    }
    if (inverse) {
      float s = 1f / n;
      for (int i = 0; i < n; i++) {
        re[i] *= s;
        im[i] = -im[i] * s;
      }
    }
  }

  private static float db(float rms) {
    if (rms < 1e-8f) return -100f;
    return (float) (20.0 * Math.log10(rms));
  }
}

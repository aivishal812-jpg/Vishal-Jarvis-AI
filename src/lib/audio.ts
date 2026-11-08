import { BrowserSpeechEnhancer } from '../mobile/voiceEnhance';

/**
 * Audio handling utility for Myraa Live API Voice stream.
 * Handles:
 * - 16kHz layout sampling for microphone stream.
 * - Raw Little Endian Int16 PCM translation.
 * - 24kHz layout output sampling for model voice playback.
 * - Gapless double-buffer queue scheduler.
 * - Interrupt signal immediate stop.
 * - Input & Output AnalyserNodes for real-time waveform visuals.
 */

export type LiveState = "disconnected" | "connecting" | "listening" | "speaking";

// PCM Conversion Helper: converts Float32Array [-1.0, 1.0] to signed Int16 Raw PCM Little Endian
function floatTo16BitPCM(input: Float32Array): ArrayBuffer {
  const buffer = new ArrayBuffer(input.length * 2);
  const view = new DataView(buffer);
  let offset = 0;
  for (let i = 0; i < input.length; i++, offset += 2) {
    let s = Math.max(-1, Math.min(1, input[i]));
    view.setInt16(offset, s < 0 ? s * 0x8000 : s * 0x7FFF, true);
  }
  return buffer;
}

/**
 * Streaming pitch shifter that raises pitch by `ratio` without changing speed
 * (lip-sync and timing are kept). A single read head plays the delay line a
 * little faster than real time; when it catches up with the newest audio it
 * jumps back by the lag whose waveform matches best (pitch-period aligned)
 * and crossfades for 5 ms. Only one copy of the voice is audible at a time,
 * so there is no doubling or echo (the comb effect of two free-running heads).
 * State carries across chunks, so chunk boundaries stay seamless.
 */
export class PitchShifter {
  private readonly N = 8192;
  private readonly buf = new Float32Array(8192);
  private written = 0;
  private rp: number;
  private fadeFrom = 0;
  private fadeLeft = 0;
  private readonly W: number; // match window
  private readonly X: number; // crossfade length
  private readonly minLag: number;
  private readonly maxLag: number;
  private readonly minDelay: number;

  constructor(private readonly ratio: number, sampleRate = 24000) {
    const s = sampleRate / 24000;
    this.W = Math.round(240 * s); // 10 ms
    this.X = Math.round(120 * s); // 5 ms
    this.minLag = Math.round(240 * s);
    this.maxLag = Math.round(720 * s); // 30 ms
    this.minDelay = Math.round(400 * s);
    this.rp = -(this.minDelay + this.maxLag);
  }

  private at(pos: number): number {
    const i = Math.floor(pos);
    const f = pos - i;
    const a = i >= 0 && i < this.written ? this.buf[i % this.N] : 0;
    const b = i + 1 >= 0 && i + 1 < this.written ? this.buf[(i + 1) % this.N] : 0;
    return a + (b - a) * f;
  }

  /** Jump the head back by the best-matching lag and start a crossfade. */
  private splice(): void {
    const base = Math.floor(this.rp);
    let best = this.minLag;
    let bestScore = -Infinity;
    for (let lag = this.minLag; lag <= this.maxLag; lag += 2) {
      let dot = 0;
      let energy = 1e-9;
      for (let k = 0; k < this.W; k += 2) {
        const a = this.at(base + k);
        const b = this.at(base - lag + k);
        dot += a * b;
        energy += b * b;
      }
      const score = dot / Math.sqrt(energy);
      if (score > bestScore) {
        bestScore = score;
        best = lag;
      }
    }
    this.fadeFrom = this.rp;
    this.rp -= best;
    this.fadeLeft = this.X;
  }

  process(input: Float32Array): Float32Array {
    const out = new Float32Array(input.length);
    for (let n = 0; n < input.length; n++) {
      this.buf[this.written % this.N] = input[n];
      this.written++;
      let y: number;
      if (this.fadeLeft > 0) {
        const t = 1 - this.fadeLeft / this.X;
        y = this.at(this.fadeFrom) * (1 - t) + this.at(this.rp) * t;
        this.fadeFrom += this.ratio;
        this.fadeLeft--;
      } else {
        y = this.at(this.rp);
      }
      out[n] = y;
      this.rp += this.ratio;
      if (this.fadeLeft === 0 && this.written - this.rp < this.minDelay) this.splice();
    }
    return out;
  }
}

// Float conversion helper: converts signed Int16 array buffer to Float32Array [-1.0, 1.0]
function pcm16ToFloats(uint8Array: Uint8Array): Float32Array {
  const int16 = new Int16Array(
    uint8Array.buffer,
    uint8Array.byteOffset,
    Math.floor(uint8Array.byteLength / 2)
  );
  const floats = new Float32Array(int16.length);
  for (let i = 0; i < int16.length; i++) {
    floats[i] = int16[i] / 32768.0;
  }
  return floats;
}

// Convert ArrayBuffer to Base64 String
function base64ArrayBuffer(arrayBuffer: ArrayBuffer): string {
  let binary = '';
  const bytes = new Uint8Array(arrayBuffer);
  const len = bytes.byteLength;
  for (let i = 0; i < len; i++) {
    binary += String.fromCharCode(bytes[i]);
  }
  return window.btoa(binary);
}

// Convert Base64 string to Uint8Array
function base64ToUint8Array(base64: string): Uint8Array {
  const binaryString = window.atob(base64);
  const len = binaryString.length;
  const bytes = new Uint8Array(len);
  for (let i = 0; i < len; i++) {
    bytes[i] = binaryString.charCodeAt(i);
  }
  return bytes;
}

export interface AudioSocket {
  readyState: number;
  binaryType: string;
  onopen: ((event: any) => void) | null;
  onmessage: ((event: any) => void) | null;
  onerror: ((event: any) => void) | null;
  onclose: ((event: any) => void) | null;
  send(data: string): void;
  close(): void;
  recover?(): void;
}
export interface AudioConnection {
  createSocket(): AudioSocket;
  pitch?: number;
  startNativeCapture?(): Promise<Record<string, unknown> | null>;
  stopNativeCapture?(): void;
  renewLive?(id: string): void;
}


/**
 * Gapless, turn-aware PCM playback engine (24 kHz mono Int16 -> Float32).
 *
 * Root-cause fix for premature voice cut-off: an individual chunk ending is
 * NOT the end of the AI response. Playback is only "finished" when BOTH
 * (a) the server sent turnComplete for the current turn, AND
 * (b) every scheduled source of that turn has actually ended.
 * Every chunk is scheduled in arrival order on one monotonic timeline;
 * chunks are never overwritten, and stale sources from an interrupted turn
 * (older generation) can never mutate the current turn's state.
 */
export interface GaplessPcmPlayerHooks {
  onTurnStarted?: () => void;
  onTurnFinished?: () => void;
}

interface ScheduledSource {
  stop: () => void;
  disconnect?: () => void;
  onended: (() => void) | null;
}

export class GaplessPcmPlayer {
  /** Safety bound: 3 minutes of queued-but-unscheduled audio. */
  static readonly MAX_QUEUED_SAMPLES = 24000 * 180;
  private sources = new Set<ScheduledSource>();
  private queue: Float32Array[] = [];
  private queuedSamples = 0;
  private nextStartTime = 0;
  private generation = 0;
  private turnComplete = false;
  private turnActive = false;
  private disposed = false;

  constructor(
    private readonly ctx: {
      currentTime: number;
      state: string;
      createBuffer: (channels: number, length: number, sampleRate: number) => { duration: number; getChannelData: (channel: number) => Float32Array };
      createBufferSource: () => any;
      resume?: () => Promise<void>;
    },
    private readonly destination: unknown,
    private readonly hooks: GaplessPcmPlayerHooks = {},
  ) {}

  get scheduledCount(): number { return this.sources.size; }
  get queuedCount(): number { return this.queue.length; }
  get hasPendingAudio(): boolean { return this.turnActive || this.sources.size > 0 || this.queue.length > 0; }
  get isTurnComplete(): boolean { return this.turnComplete; }

  enqueue(samples: Float32Array): void {
    if (this.disposed || !samples.length) return;
    if (!this.turnActive) {
      // First chunk of a new turn (also the path after a finished turn).
      this.turnActive = true;
      this.turnComplete = false;
      this.hooks.onTurnStarted?.();
    }
    this.queue.push(samples);
    this.queuedSamples += samples.length;
    // Never silently discard: if a pathological peer floods faster than the
    // bound, drop only the oldest *unscheduled* audio and keep playing.
    while (this.queuedSamples > GaplessPcmPlayer.MAX_QUEUED_SAMPLES && this.queue.length > 1) {
      this.queuedSamples -= this.queue.shift()!.length;
      console.warn('[Audio] Playback queue bound reached; dropping oldest unscheduled chunk.');
    }
    this.pump();
  }

  markTurnComplete(): void {
    this.turnComplete = true;
    this.maybeFinishTurn();
  }

  /** Genuine barge-in / user stop only. Never called for a chunk ending. */
  interrupt(): void {
    this.generation += 1;
    for (const source of this.sources) {
      try { source.onended = null; source.stop(); } catch { /* already ended */ }
      try { source.disconnect?.(); } catch { /* noop */ }
    }
    this.sources.clear();
    this.queue = [];
    this.queuedSamples = 0;
    this.nextStartTime = 0;
    this.turnComplete = false;
    this.turnActive = false;
  }

  dispose(): void {
    this.disposed = true;
    this.interrupt();
  }

  private pump(): void {
    if (this.ctx.state !== 'running') {
      // A suspended/interrupted context (background, audio-focus loss) keeps
      // its timeline frozen, so scheduling stays sequential; also ask it to
      // resume so queued speech continues instead of silently stalling.
      try { void this.ctx.resume?.()?.catch(() => undefined); } catch { /* noop */ }
    }
    while (this.queue.length) {
      const floats = this.queue.shift()!;
      this.queuedSamples -= floats.length;
      const generation = this.generation;
      try {
        const buffer = this.ctx.createBuffer(1, floats.length, 24000);
        buffer.getChannelData(0).set(floats);
        const source = this.ctx.createBufferSource() as ScheduledSource & { buffer: unknown; connect: (node: unknown) => void; start: (when: number) => void };
        source.buffer = buffer;
        source.connect(this.destination);
        const currentTime = this.ctx.currentTime;
        if (this.nextStartTime <= currentTime) this.nextStartTime = currentTime + 0.03;
        source.onended = () => {
          if (generation !== this.generation) return; // stale, interrupted turn
          this.sources.delete(source);
          this.maybeFinishTurn();
        };
        source.start(this.nextStartTime);
        this.nextStartTime += buffer.duration;
        this.sources.add(source);
      } catch (error) {
        // One bad chunk must not discard the rest of the response.
        console.error('PCM chunk scheduling failed; continuing with next chunk:', error);
      }
    }
  }

  private maybeFinishTurn(): void {
    if (!this.turnActive || !this.turnComplete) return;
    if (this.sources.size > 0 || this.queue.length > 0) return;
    this.turnActive = false;
    this.turnComplete = false;
    this.nextStartTime = 0;
    this.hooks.onTurnFinished?.();
  }
}

export class MyraaAudioSession {
  private ws: AudioSocket | null = null;
  
  // Audios contexts (separate to match exact required sample rates)
  private inputAudioCtx: AudioContext | null = null;
  private outputAudioCtx: AudioContext | null = null;
  
  // Audio sources & processors
  private micStream: MediaStream | null = null;
  private micSourceNode: MediaStreamAudioSourceNode | null = null;
  private micProcessorNode: ScriptProcessorNode | null = null;
  
  // Visualisers
  public inputAnalyser: AnalyserNode | null = null;
  public outputAnalyser: AnalyserNode | null = null;
  private outputGainNode: GainNode | null = null;
  private pitchShifter: PitchShifter | null = null;
  
  // Buffering / Playback details (turn-aware gapless engine; see above)
  private player: GaplessPcmPlayer | null = null;
  private preContextChunks: Float32Array[] = [];
  private deferredDisconnect = false;
  private pitchSemitones = 0;
  private userSpeaking = false;
  private voiceFrames = 0;
  private lastVoiceAt = 0;
  private outputSpeechStartedAt = 0;
  private readonly voiceThreshold = 0.02;
  private readonly voiceStartFrames = 2;
  private readonly voiceStopDelayMs = 500;
  private readonly bargeInGuardMs = 700;
  private readonly bargeInStartFrames = 4;
  private readonly bargeInVoiceThreshold = 0.045;
  private readonly bargeInSustainMs = 350;
  private bargeInSince = 0;
  private nativeSpeechHint = false;
  
  // State Callbacks
  private onStateChange: (state: LiveState) => void;
  private onTranscription: (role: "user" | "model", text: string) => void;
  private onToolCall: (name: string, args: any, callback: (result: any) => void) => void;
  private onError: (error: string) => void;
  private onMemorySync?: (memories: any[]) => void;
  private onScreenVisionState?: (state: "idle" | "capturing" | "ready" | "error", info: { activeWindow?: string | null; error?: string | null }) => void;
  
  private connection?: AudioConnection;
  private currentState: LiveState = "disconnected";
  private isActivated = false;
  private useMicrophone = true;
  private liveReady = false;
  private outputReady = false;
  private pendingTextMessages: string[] = [];
  private usingNativeCapture = false;
  private lastNativeAt = 0;
  private nativeWatchdog: ReturnType<typeof setInterval> | null = null;
  private browserEnhancer = new BrowserSpeechEnhancer();

  constructor(handlers: {
    onStateChange: (state: LiveState) => void;
    onTranscription: (role: "user" | "model", text: string) => void;
    onToolCall: (name: string, args: any, callback: (result: any) => void) => void;
    onError: (error: string) => void;
    onMemorySync?: (memories: any[]) => void;
    onScreenVisionState?: (state: "idle" | "capturing" | "ready" | "error", info: { activeWindow?: string | null; error?: string | null }) => void;
    connection?: AudioConnection;
  }) {
    this.connection = handlers.connection;
    this.onStateChange = handlers.onStateChange;
    this.onTranscription = handlers.onTranscription;
    this.onToolCall = handlers.onToolCall;
    this.onError = handlers.onError;
    this.onMemorySync = handlers.onMemorySync;
    this.onScreenVisionState = handlers.onScreenVisionState;
  }

  private setState(state: LiveState) {
    this.currentState = state;
    this.onStateChange(state);
  }

  public getState(): LiveState {
    return this.currentState;
  }

  private outputActive(): boolean {
    return !!this.player?.hasPendingAudio;
  }

  private newPitchShifter(): PitchShifter | null {
    return this.pitchSemitones > 0
      ? new PitchShifter(Math.pow(2, Math.min(5, this.pitchSemitones) / 12))
      : null;
  }

  public nativeCaptureActive(): boolean {
    return this.usingNativeCapture;
  }

  public ingestNativePcm(base64: string, speechHint: boolean): void {
    const bytes = base64ToUint8Array(base64);
    if (!bytes.length) return;
    this.lastNativeAt = Date.now();
    const floats = pcm16ToFloats(bytes);
    this.nativeSpeechHint = speechHint;
    // Respect the VAD decision. Previously every native frame was forwarded
    // unconditionally, so the model's own loudspeaker output reached Gemini,
    // whose server VAD (START_OF_ACTIVITY_INTERRUPTS) aborted generation
    // mid-response -- the confirmed "voice cuts off after the first
    // sentence" bug. Only genuine user speech (barge-in) is forwarded while
    // the model is speaking. The native speech hint alone never forwards.
    const shouldForward = this.updateVoiceActivity(floats);
    void speechHint;
    if (shouldForward && this.ws && this.ws.readyState === WebSocket.OPEN && this.currentState !== 'disconnected' && this.currentState !== 'connecting') {
      this.ws.send(JSON.stringify({ audio: base64 }));
    }
  }

  public async startNativeCapture(): Promise<boolean> {
    if (!this.connection?.startNativeCapture) return false;
    const started = await this.connection.startNativeCapture();
    this.usingNativeCapture = !!(started && started.ok !== false);
    if (this.usingNativeCapture) this.startNativeWatchdog();
    return this.usingNativeCapture;
  }

  public stopNativeCapture(): void {
    this.usingNativeCapture = false;
    if (this.nativeWatchdog) { clearInterval(this.nativeWatchdog); this.nativeWatchdog = null; }
    this.connection?.stopNativeCapture?.();
  }

  private startNativeWatchdog(): void {
    if (this.nativeWatchdog) clearInterval(this.nativeWatchdog);
    this.lastNativeAt = Date.now();
    this.nativeWatchdog = setInterval(() => {
      if (!this.usingNativeCapture || this.currentState === 'disconnected') return;
      if (Date.now() - this.lastNativeAt > 2500) {
        this.ws?.recover?.();
        this.lastNativeAt = Date.now();
      }
    }, 800);
  }

  /**
   * Pushes a compressed JPEG base64 screenshot frame directly to the live WebSocket server.
   */
  public sendVideoFrame(
    base64Data: string,
    metadata: { changeScore?: number; heartbeat?: boolean } = {},
  ) {
    if (this.ws && this.ws.readyState === WebSocket.OPEN && this.currentState !== "disconnected") {
      this.ws.send(JSON.stringify({ type: "video", video: base64Data, ...metadata }));
    }
  }

  /** Bundled Android WebView has no HTTP server behind it. */
  private isAndroidApp(): boolean {
    return !!(window as any).MyraaNative || location.hostname === 'appassets.androidplatform.net';
  }

  /** Queue typed input; it is flushed once Gemini Live is ready. */
  public sendText(text: string) {
    const trimmed = text.trim();
    if (!trimmed) return;

    if (this.currentState === "speaking") {
      this.emitConversationEvent("user_interrupted_myraa");
      this.handleInterruption();
    }

    if (
      this.ws &&
      this.ws.readyState === WebSocket.OPEN &&
      this.liveReady &&
      this.outputReady &&
      this.currentState !== "disconnected"
    ) {
      this.ws.send(JSON.stringify({ type: "text", text: trimmed }));
      return;
    }
    this.pendingTextMessages.push(trimmed);
  }

  private flushPendingTextMessages() {
    if (!this.ws || this.ws.readyState !== WebSocket.OPEN || !this.liveReady || !this.outputReady) return;
    for (const text of this.pendingTextMessages.splice(0)) {
      this.ws.send(JSON.stringify({ type: "text", text }));
    }
  }

  // Voice mode requests a microphone; typed mode keeps audio responses without one.
  public async connect(useMicrophone = true) {
    if (this.isActivated) return;
    this.isActivated = true;
    this.useMicrophone = useMicrophone;
    this.setState("connecting");

    try {
      // The desktop build checks a server permission policy. The Android app has no
      // server at all, so asking /api/permissions here made every live call fail.
      // Only ask when a custom socket is absent AND the origin really serves it.
      if (useMicrophone && !this.connection && !this.isAndroidApp()) {
        try {
          const response = await fetch('/api/permissions');
          if (response.ok) {
            const permissions = await response.json();
            if (permissions?.policy?.capabilities?.ACCESS_MICROPHONE?.decision === 'deny') throw new Error('Microphone access is denied in the permission policy. You can keep using text.');
          }
        } catch (policyError) {
          // A missing policy endpoint is not a microphone failure; continue with audio.
          if (policyError instanceof Error && policyError.message.includes('denied')) throw policyError;
        }
        if (!this.isActivated) return;
      }
      // 1. Establish custom WebSocket server bridge
      const protocol = window.location.protocol === "https:" ? "wss:" : "ws:";
      this.ws = this.connection?.createSocket() ?? new WebSocket(`${protocol}//${window.location.host}/live`);
      this.ws.binaryType = "blob";

      this.ws.onopen = async () => {
        console.log("[Myraa] Connected to server side WS bridge");
        try {
          // Guard against early user disconnect during connection setup
          if (!this.isActivated) return;

          // Transport recovery re-fires onopen for the replacement socket.
          // The existing graph (and any in-flight playback on it) must be
          // reused: rebuilding it created a second playback engine and
          // orphaned scheduled audio on the abandoned context.
          if (this.outputAudioCtx && this.player) {
            if (this.outputAudioCtx.state !== "running") await this.outputAudioCtx.resume().catch(() => {});
            const inputCtx = this.inputAudioCtx; if (inputCtx && inputCtx.state !== "running") await inputCtx.resume().catch(() => {});
            return;
          }

          // Safe, cross-browser AudioContext initialization
          const AudioContextClass = window.AudioContext || (window as any).webkitAudioContext;
          if (!AudioContextClass) {
            throw new Error("Holographic audio link unsupported: Web Audio API missing in browser.");
          }

          this.inputAudioCtx = new AudioContextClass({ sampleRate: 16000 });
          this.outputAudioCtx = new AudioContextClass({ sampleRate: 24000 });

          // Ensure Audio Contexts are active and resumed to bypass browser security blocks
          if (this.inputAudioCtx.state === "suspended") {
            await this.inputAudioCtx.resume().catch(() => {});
          }
          if (this.outputAudioCtx.state === "suspended") {
            await this.outputAudioCtx.resume().catch(() => {});
          }
          
          // Setup custom output Analyser & Volume Gains
          this.outputGainNode = this.outputAudioCtx.createGain();
          this.outputAnalyser = this.outputAudioCtx.createAnalyser();
          this.outputAnalyser.fftSize = 256;
          this.outputAnalyser.smoothingTimeConstant = 0.8;
          
          this.outputGainNode.connect(this.outputAnalyser);
          this.outputAnalyser.connect(this.outputAudioCtx.destination);
          // If Android suspends/interrupts the context (background, focus
          // loss), recover it while a session is live instead of letting
          // scheduled speech stall forever.
          this.outputAudioCtx.onstatechange = () => {
            if (this.isActivated && this.outputAudioCtx && this.outputAudioCtx.state !== "running") {
              void this.outputAudioCtx.resume().catch(() => {});
            }
          };
          this.player = new GaplessPcmPlayer(this.outputAudioCtx, this.outputGainNode, {
            onTurnStarted: () => {
              this.outputSpeechStartedAt = performance.now();
              if (this.currentState !== "speaking") this.setState("speaking");
            },
            onTurnFinished: () => {
              this.outputSpeechStartedAt = 0;
              if (this.currentState === "speaking") this.setState("listening");
              if (this.deferredDisconnect) { this.deferredDisconnect = false; this.disconnect(); }
            },
          });
          this.outputReady = true;
          // Flush any chunks that arrived before the graph existed.
          for (const chunk of this.preContextChunks.splice(0)) this.player.enqueue(chunk);
          this.flushPendingTextMessages();
          // Optional pitch lift (Settings → Voice → Voice pitch).
          this.pitchShifter = null;
          if (this.connection) {
            this.pitchSemitones = this.connection.pitch || 0;
            this.pitchShifter = this.newPitchShifter();
          } else void fetch("/api/app-settings")
            .then((r) => (r.ok ? r.json() : null))
            .then((s) => {
              this.pitchSemitones = Number(s?.voice?.pitch) || 0;
              this.pitchShifter = this.newPitchShifter();
            })
            .catch(() => undefined);

          if (!this.useMicrophone) {
            this.setState("listening");
            this.flushPendingTextMessages();
            return;
          }

          if (await this.startNativeCapture()) {
            this.setState("listening");
            return;
          }

          // Obtain the user microphone. Some embedded preview browsers do not
          // expose getUserMedia even though the main UI itself renders normally.
          if (!navigator.mediaDevices?.getUserMedia) {
            throw new Error(
              "Microphone access is unavailable in this browser. Open MYRAA in Chrome at http://localhost:3000 and allow microphone access.",
            );
          }
          const stream = await navigator.mediaDevices.getUserMedia({
            audio: {
              echoCancellation: true,
              noiseSuppression: true,
              autoGainControl: true,
              channelCount: 1,
              sampleRate: 16000,
            }
          });

          // Safeguard: Check if we disconnected while waiting for user to grant mic permissions
          if (!this.isActivated || !this.inputAudioCtx || !this.outputAudioCtx) {
            stream.getTracks().forEach((track) => {
              try {
                track.stop();
              } catch (e) {}
            });
            return;
          }

          this.micStream = stream;

          // Setup custom input Analyser
          this.inputAnalyser = this.inputAudioCtx.createAnalyser();
          this.inputAnalyser.fftSize = 256;
          
          this.micSourceNode = this.inputAudioCtx.createMediaStreamSource(this.micStream);
          this.micSourceNode.connect(this.inputAnalyser);

          // Stream input PCM 16-bit to WS
          // A smaller buffer cuts microphone-to-live-stream latency while
          // remaining stable on ordinary Windows audio devices.
          this.micProcessorNode = this.inputAudioCtx.createScriptProcessor(1024, 1, 1);
          this.micSourceNode.connect(this.micProcessorNode);
          this.micProcessorNode.connect(this.inputAudioCtx.destination);

          this.micProcessorNode.onaudioprocess = (e) => {
            if (this.currentState === "disconnected" || this.currentState === "connecting" || this.usingNativeCapture) return;
            
            const channelData = e.inputBuffer.getChannelData(0);
            const enhanced = this.browserEnhancer.process(channelData);
            const shouldForwardMicrophone = this.updateVoiceActivity(enhanced.output) || enhanced.speech;
            if (!shouldForwardMicrophone) return;
            
            // Convert to base64 Int16 Little Endian PCM
            const pcmBuffer = floatTo16BitPCM(enhanced.output);
            const base64 = base64ArrayBuffer(pcmBuffer);
            
            if (this.ws && this.ws.readyState === WebSocket.OPEN) {
              this.ws.send(JSON.stringify({ audio: base64 }));
            }
          };

          // Sound setups are fully functional
          this.setState("listening");

        } catch (audioError: any) {
          console.error("Audio Context or Microphone Initialization Failed:", audioError);
          this.onError(`Permission error: ${audioError.message || "Microphone required for holographic Live link."}`);
          this.disconnect();
        }
      };

      this.ws.onmessage = async (event) => {
        try {
          const data = JSON.parse(event.data);
          if (data.type === 'voice_diagnostic') { if (data.message) this.onError(data.message); return; }
          
          // Root Error Handler message
          if (data.type === "error") {
            this.onError(data.error);
            this.disconnect();
            if (data.code === "INVALID_API_KEY") {
              // Keep the current screen stable so the user can read the error.
              // The key gate will be shown naturally on the next app launch.
              console.warn("[Myraa] Gemini rejected the configured API key.");
            }
            return;
          }

          // Handle server-side states
          if (data.type === "status") {
            console.log("[Myraa WS Status]:", data.status);
            if (data.status === "connecting_gemini") {
              // Wait for Gemini Live connection
            } else if (data.status === "connected") {
              this.liveReady = true;
              this.setState("listening");
              this.flushPendingTextMessages();
            } else if (data.status === "session_closed") {
              // Never close the output context over speech that is still
              // queued: finish the current turn first, then disconnect.
              if (this.outputActive()) this.deferredDisconnect = true;
              else this.disconnect();
            }
            return;
          }

          // Handle audio payload (24kHzPCM model response)
          if (data.type === "audio" && data.audio) {
            this.playAudioPCMChunk(data.audio);
          }

          // Handle interruption signal (e.g. user talked over Myraa)
          if (data.type === "interrupted") {
            this.handleInterruption();
          }

          // Turn complete: the server will send no more audio for this
          // turn, but already-scheduled audio may still be playing. The
          // playback engine flips back to listening only after it drains.
          if (data.type === "turnComplete") {
            if (this.player) this.player.markTurnComplete();
            else if (this.currentState === "speaking") this.setState("listening");
          }

          // Handle live captions transcription
          if (data.type === "transcription") {
            this.onTranscription(data.role, data.text);
          }

          // Handle memory synchronization
          if (data.type === "memory_sync" && data.memories) {
            if (this.onMemorySync) {
              this.onMemorySync(data.memories);
            }
          }

          // Handle Tool Calling
          if (data.type === "toolCall") {
            const { callId, name, args } = data;
            this.onToolCall(name, args, (result) => {
              // Send back execution result to server bridge
              if (this.ws && this.ws.readyState === WebSocket.OPEN) {
                this.ws.send(JSON.stringify({
                  type: "toolResponse",
                  id: callId,
                  name: name,
                  output: result
                }));
              }
            });
          }

          // Screen-vision lifecycle events from the server.
          if (data.type === "screenVisionState") {
            if (this.onScreenVisionState) {
              this.onScreenVisionState(data.state, {
                activeWindow: data.activeWindow ?? null,
                error: data.error ?? null,
              });
            }
          }

        } catch (parseError) {
          console.error("Error reading server packet:", parseError);
        }
      };

      this.ws.onerror = (wsError) => {
        console.error("WebSocket transport error:", wsError);
        this.onError("Holographic network link lost. Please check connection.");
        this.disconnect();
      };

      this.ws.onclose = () => {
        console.log("WebSocket connection closed");
        this.disconnect();
      };

    } catch (e: any) {
      console.error("Connection establish sequence failed:", e);
      this.onError(e.message || "Failed to initialize active channel.");
      this.disconnect();
    }
  }

  // Interruption triggers: stops all active audio players immediately.
  // Only a genuine barge-in, an explicit user stop or disconnect may call
  // this -- never a single chunk finishing.
  private handleInterruption() {
    console.log("[Audio] Interruption signal received; flushing playback queue.");
    this.player?.interrupt();
    this.bargeInSince = 0;
    this.preContextChunks = [];
    this.deferredDisconnect = false;
    // Pitch-shifter state belongs to the interrupted turn; rebuild it so the
    // next turn does not start from stale delay-line audio.
    this.pitchShifter = this.newPitchShifter();
    this.outputSpeechStartedAt = 0;
    this.setState("listening");
  }

  /**
   * Lightweight local VAD for turn-taking. It never calls a model: it only
   * emits start/stop events and flushes buffered TTS immediately on barge-in.
   */
  private updateVoiceActivity(samples: Float32Array): boolean {
    let energy = 0;
    for (let i = 0; i < samples.length; i++) energy += samples[i] * samples[i];
    const rms = Math.sqrt(energy / Math.max(1, samples.length));
    const timestamp = performance.now();
    const myraaOutputActive = this.currentState === "speaking" || this.outputActive();

    // Full-duplex speaker playback can leak back into the microphone even with
    // browser echo cancellation enabled. Do not feed that echo to Gemini. A
    // short guard protects the opening words; after it, a louder sustained
    // signal is still accepted as a genuine human barge-in.
    if (
      myraaOutputActive
      && this.outputSpeechStartedAt > 0
      && timestamp - this.outputSpeechStartedAt < this.bargeInGuardMs
    ) {
      this.voiceFrames = 0;
      return false;
    }

    const outputRms = myraaOutputActive ? this.readOutputRms() : 0;
    const threshold = myraaOutputActive
      ? Math.max(
          this.bargeInVoiceThreshold,
          Math.min(0.1, outputRms * 0.32 + 0.012),
        )
      : this.voiceThreshold;
    const requiredFrames = myraaOutputActive ? this.bargeInStartFrames : this.voiceStartFrames;

    if (rms >= threshold) {
      this.lastVoiceAt = timestamp;
      this.voiceFrames += 1;
      if (myraaOutputActive && !this.userSpeaking) {
        // Playback is active: a few loud frames are almost certainly speaker
        // echo, not a human. Native frames arrive every 20 ms, so the old
        // 4-frame rule fired after ~80 ms of echo and killed the response
        // locally. Require the native enhancer's own speech verdict AND a
        // sustained 350 ms of loud, speech-like input before interrupting,
        // and forward nothing to Gemini while the candidate is unproven.
        if (this.usingNativeCapture && !this.nativeSpeechHint) {
          this.bargeInSince = 0;
          return false;
        }
        if (this.bargeInSince === 0) this.bargeInSince = timestamp;
        if (timestamp - this.bargeInSince < this.bargeInSustainMs) return false;
        this.userSpeaking = true;
        this.emitConversationEvent("user_interrupted_myraa", rms);
        this.handleInterruption();
        this.emitConversationEvent("user_started_speaking", rms);
        return true;
      }
      if (!this.userSpeaking && this.voiceFrames >= requiredFrames) {
        this.userSpeaking = true;
        this.emitConversationEvent("user_started_speaking", rms);
      }
      return !myraaOutputActive || this.userSpeaking;
    }

    this.voiceFrames = 0;
    this.bargeInSince = 0;
    if (this.userSpeaking && timestamp - this.lastVoiceAt >= this.voiceStopDelayMs) {
      this.userSpeaking = false;
      this.emitConversationEvent("user_stopped_speaking", rms);
    }
    return !myraaOutputActive || this.userSpeaking;
  }

  private readOutputRms(): number {
    if (!this.outputAnalyser) return 0;
    const samples = new Uint8Array(this.outputAnalyser.fftSize);
    this.outputAnalyser.getByteTimeDomainData(samples);
    let energy = 0;
    for (let i = 0; i < samples.length; i++) {
      const normalized = (samples[i] - 128) / 128;
      energy += normalized * normalized;
    }
    return Math.sqrt(energy / Math.max(1, samples.length));
  }

  private emitConversationEvent(event: string, rms?: number) {
    if (!this.ws || this.ws.readyState !== WebSocket.OPEN) return;
    this.ws.send(JSON.stringify({ type: "conversationEvent", event, rms }));
  }

  // Direct raw PCM chunk playback at 24kHz via the turn-aware engine.
  private playAudioPCMChunk(base64Audio: string) {
    try {
      const uint8Array = base64ToUint8Array(base64Audio);
      if (!uint8Array.length) return;
      const raw = pcm16ToFloats(uint8Array);
      const floats = this.pitchShifter ? this.pitchShifter.process(raw) : raw;
      if (!this.player) {
        // Graph not ready yet: preserve the chunk (in order) instead of
        // silently discarding the start of the response.
        this.preContextChunks.push(floats);
        let queued = this.preContextChunks.reduce((sum, chunk) => sum + chunk.length, 0);
        while (queued > GaplessPcmPlayer.MAX_QUEUED_SAMPLES && this.preContextChunks.length > 1) {
          queued -= this.preContextChunks.shift()!.length;
        }
        return;
      }
      if (this.outputAudioCtx && this.outputAudioCtx.state !== "running") {
        void this.outputAudioCtx.resume().catch(() => {});
      }
      this.player.enqueue(floats);
    } catch (playbackError) {
      console.error("PCM Chunk buffering/playback failed:", playbackError);
    }
  }

  // Fully cleanup and release microphones & connection sockets
  public disconnect() {
    this.isActivated = false;
    this.liveReady = false;
    this.outputReady = false;
    this.stopNativeCapture();
    this.setState("disconnected");

    // Close WS socket
    if (this.ws) {
      try {
        this.ws.close();
      } catch (e) {}
      this.ws = null;
    }

    // Stop and release user microphone streams
    if (this.micStream) {
      this.micStream.getTracks().forEach((track) => {
        try {
          track.stop();
        } catch (e) {}
      });
      this.micStream = null;
    }

    // Disconnect routing nodes
    if (this.micProcessorNode) {
      try {
        this.micProcessorNode.disconnect();
      } catch (e) {}
      this.micProcessorNode = null;
    }

    if (this.micSourceNode) {
      try {
        this.micSourceNode.disconnect();
      } catch (e) {}
      this.micSourceNode = null;
    }

    // Close Audio contexts
    if (this.inputAudioCtx) {
      try {
        this.inputAudioCtx.close();
      } catch (e) {}
      this.inputAudioCtx = null;
    }

    if (this.outputAudioCtx) {
      try {
        this.outputAudioCtx.close();
      } catch (e) {}
      this.outputAudioCtx = null;
    }

    this.player?.dispose();
    this.player = null;
    this.preContextChunks = [];
    this.deferredDisconnect = false;
    this.pitchShifter = null;
    this.userSpeaking = false;
    this.voiceFrames = 0;
    this.lastVoiceAt = 0;
    this.outputSpeechStartedAt = 0;
    this.pendingTextMessages = [];
    this.inputAnalyser = null;
    this.outputAnalyser = null;
    this.outputGainNode = null;
  }
}

import type { AudioSocket } from '../lib/audio';
import { LIVE_TOOLS } from './actions';

export interface MobileLiveBridge { startLive(id: string): void; endLive(): void; renewLive?(id: string): void; }
/** Adapt the native short-lived token and Gemini Live protocol to the PC audio engine. */
export class MobileLiveTransport implements AudioSocket {
  readyState = 0;
  binaryType = 'blob';
  onopen: AudioSocket['onopen'] = null;
  onmessage: AudioSocket['onmessage'] = null;
  onerror: AudioSocket['onerror'] = null;
  onclose: AudioSocket['onclose'] = null;
  private socket: WebSocket | null = null;
  private ready = false;
  private closed = false;
  private recovering = false;
  private recoveries = 0;
  private toolCalls = new Map<string, { id?: string; name: string }>();
  private id = crypto.randomUUID();
  private timeout: ReturnType<typeof setTimeout>;
  private receiveChain = Promise.resolve();
  constructor(private bridge: MobileLiveBridge) {
    window.addEventListener('myraa:mobile-live', this.nativeEvent);
    this.timeout = setTimeout(() => this.fail('Live voice timed out while connecting. Check your connection and Live model access.'), 35000);
    bridge.startLive(this.id);
  }
  recover() {
    if (this.closed) return;
    if (this.recovering && !this.socket) return;
    if (this.recoveries >= 3) {
      this.fail('Live audio stalled after several reconnects. Tap Talk to start a fresh call.');
      return;
    }
    this.recoveries += 1;
    this.recovering = true;
    this.ready = false;
    this.dropSocket();
    this.id = crypto.randomUUID();
    clearTimeout(this.timeout);
    this.timeout = setTimeout(() => this.fail('Live voice timed out while reconnecting. Check your connection and Live model access.'), 35000);
    if (this.bridge.renewLive) this.bridge.renewLive(this.id);
    else this.fail('Live audio recovery is unavailable on this build.');
  }
  private dropSocket() {
    if (!this.socket) return;
    this.socket.onopen = null; this.socket.onmessage = null; this.socket.onclose = null; this.socket.onerror = null;
    try { this.socket.close(); } catch { /* already closed */ }
    this.socket = null;
  }
  private packet(value: unknown) { if (this.readyState !== 3) this.onmessage?.({ data: JSON.stringify(value) }); }
  private fail(message: string) { this.packet({ type: 'error', error: message }); this.close(); }
  private nativeEvent = (event: Event) => {
    const value = (event as CustomEvent).detail;
    if (!value || value.id !== this.id || this.readyState === 3) return;
    if (value.type === 'live-error') {
      if (this.closed) return;
      this.recovering = false;
      if (this.recoveries > 0 || this.ready) { this.recover(); return; }
      this.fail(value.message || 'Gemini Live could not start.'); return;
    }
    if (value.type !== 'live-token' || this.socket) return;
    if (typeof value.token !== 'string' || !value.token.startsWith('auth_tokens/') || !/^[A-Za-z0-9._-]{1,100}$/.test(value.model)) {
      this.fail('The Live session credential was invalid. Try connecting again.'); return;
    }
    const socket = new WebSocket('wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContentConstrained?access_token=' + encodeURIComponent(value.token));
    this.socket = socket;
    socket.onopen = () => {
      if (this.readyState === 3) { socket.close(); return; }
      this.readyState = 1;
      socket.send(JSON.stringify({ setup: {
        model: 'models/' + value.model,
        generationConfig: { responseModalities: ['AUDIO'], speechConfig: { voiceConfig: { prebuiltVoiceConfig: { voiceName: value.voice || 'Aoede' } } } },
        systemInstruction: { parts: [{ text: value.instruction || 'You are Vishal Jarvis, created by Vishal. Default to natural West Bengal Indian Bengali unless the user speaks another language. Use mobile_action for calls to saved contacts by name, flashlight on/off, and Wi-Fi settings. For saved-contact calls, pass the spoken contact name as recipient; do not ask for a number unless no contact is found. For Wi-Fi, explain that Android opens the Internet panel and the user must switch Wi-Fi on there.' }] },
        inputAudioTranscription: {}, outputAudioTranscription: {}, tools: [{ functionDeclarations: LIVE_TOOLS }],
        realtimeInputConfig: {
          automaticActivityDetection: {
            disabled: false,
            startOfSpeechSensitivity: 'START_SENSITIVITY_HIGH',
            endOfSpeechSensitivity: 'END_SENSITIVITY_LOW',
            prefixPaddingMs: 40,
            silenceDurationMs: 700,
          },
          activityHandling: 'START_OF_ACTIVITY_INTERRUPTS',
          turnCoverage: 'TURN_INCLUDES_ONLY_ACTIVITY',
        },
      } }));
      this.onopen?.({});
    };
    socket.onmessage = event => {
      // Blob decoding must preserve arrival order, especially interruption vs audio.
      this.receiveChain = this.receiveChain.then(async () => {
        if (this.readyState === 3) return;
        const raw = typeof event.data === 'string' ? event.data : await (event.data as Blob).text();
        const data = JSON.parse(raw);
        if (data.error) { this.fail('Gemini Live rejected the session. Check the key, quota and Live model access in Settings.'); return; }
        if (data.setupComplete) {
          this.ready = true; this.recovering = false; this.recoveries = 0; clearTimeout(this.timeout);
          this.packet({ type: 'status', status: 'connected' });
        }
        for (const call of data.toolCall?.functionCalls || []) {
          const callId = call.id || crypto.randomUUID();
          if (this.toolCalls.has(callId)) continue;
          if (this.toolCalls.size >= 128) { this.fail('Too many actions in this call. Reconnect to continue.'); return; }
          this.toolCalls.set(callId, { id: call.id, name: call.name });
          try {
            this.packet({ type: 'toolCall', callId, name: call.name, args: call.args || {} });
          } catch {
            // A broken handler must not cut the live call; answer the model honestly instead.
            this.send(JSON.stringify({ type: 'toolResponse', id: callId, name: call.name,
              output: { status: 'failed', message: 'That action could not run. Nothing was changed.' } }));
          }
        }
        const content = data.serverContent;
        if (content?.interrupted) this.packet({ type: 'interrupted' });
        if (content?.inputTranscription?.text) this.packet({ type: 'transcription', role: 'user', text: content.inputTranscription.text });
        if (content?.outputTranscription?.text) this.packet({ type: 'transcription', role: 'model', text: content.outputTranscription.text });
        for (const part of content?.modelTurn?.parts || []) {
          if (part.inlineData?.mimeType?.startsWith('audio/pcm') && part.inlineData.data) this.packet({ type: 'audio', audio: part.inlineData.data });
        }
        if (content?.turnComplete) this.packet({ type: 'turnComplete' });
        if (data.goAway) this.recover();
      }).catch(() => this.fail('The Live audio connection returned an unreadable message. Reconnect to continue.'));
    };
    socket.onerror = () => {
      if (this.closed) return;
      if (this.ready || this.recovering) this.recover();
      else this.fail('The Live audio connection failed. Check your network and Gemini Live access.');
    };
    socket.onclose = event => {
      if (this.closed || this.readyState === 3) return;
      if (event.code === 1000 && !this.recovering) { this.packet({ type: 'status', status: 'session_closed' }); this.close(); return; }
      this.recover();
    };
  };
  send(raw: string) {
    if (!this.ready || this.readyState !== 1 || this.socket?.readyState !== WebSocket.OPEN) {
      const data = (() => { try { return JSON.parse(raw); } catch { return null; } })();
      return;
    }
    const data = JSON.parse(raw);
    if (data.audio) this.socket.send(JSON.stringify({ realtimeInput: { audio: { data: data.audio, mimeType: 'audio/pcm;rate=16000' } } }));
    // Live expects typed user turns as clientContent, not as realtimeInput.text.
    else if (data.type === 'text' && data.text) this.socket.send(JSON.stringify({
      clientContent: { turns: [{ role: 'user', parts: [{ text: String(data.text) }] }], turnComplete: true },
    }));
    else if (data.type === 'toolResponse') {
      const call = this.toolCalls.get(data.id);
      if (call && call.name === data.name) this.socket.send(JSON.stringify({ toolResponse: { functionResponses: [{ ...(call.id ? { id: call.id } : {}), name: call.name, response: data.output }] } }));
    }
    // No desktop tools or unrestricted phone UI authority.
  }
  close() {
    if (this.readyState === 3) return;
    this.closed = true; this.readyState = 3; this.ready = false; this.recovering = false; clearTimeout(this.timeout);
    window.removeEventListener('myraa:mobile-live', this.nativeEvent);
    this.dropSocket();
    this.bridge.endLive();
  }
}

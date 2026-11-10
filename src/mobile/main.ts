import './mobile.css';
import { MyraaAudioSession, type LiveState } from '../lib/audio';
import { MobileLiveTransport } from './LiveTransport';
import { describeAction, parseMobileAction } from './actions';
import { RobotCharacter } from './RobotCharacter';
import { HudStage, hudMarkup } from './hud';
import { parseCharacterCommand } from '../../shared/character/commands';

interface MemoryFact { id: string; text: string; at: number }
interface PermissionState { microphone: boolean; contacts: boolean; camera: boolean; notifications: boolean; overlay: boolean; phone?: boolean; sms?: boolean; accessibility?: boolean }
interface Turn { role: string; text: string; at?: number }
interface AppState {
  history: Turn[]; model: string; liveModel?: string; quality: string; language: string;
  keyConfigured: boolean; keyVerified?: boolean; spokenReplies?: boolean; floatingControlsAllowed?: boolean;
  ttsAvailable?: boolean; version?: string; memory?: MemoryFact[]; permissions?: PermissionState;
  textScale?: number; pendingTasks?: number; pending?: Array<{id: string; text: string; status: string}>;
  lastFailedTask?: {id: string; text: string; message: string};
}
interface Bridge {
  getState(): string; configure(json: string): void; checkConnection(json: string): void; chat(text: string, id: string): void;
  cancel(): void; startLive(id: string): void; endLive(): void; renewLive?(id: string): void; speak(text: string): void; stopSpeech(): void;
  startNativeCapture?(): string; stopNativeCapture?(): void; audioDiagnostics?(): string;
  clearHistory(): void; clearKey(): void; openSettings(): void; mobileAction(id: string, name: string, args: string): void;
  openFloatingSettings(): void; cancelMobileAction(id: string): void; rememberFact(text: string): string;
  deleteFact(id: string): void; clearMemory(): void; copyText(text: string): void; shareText(text: string): void;
  requestPermission(name: string): void; setTextScale(percent: number): void; exportTranscript(): void; shareTranscript(): void;
  pickImage?(): void; clearImage?(): void; openProSettings?(): void; takeLaunchAction?(): string;
  pickDocument?(): void; stopSpeaking?(): void;
}
declare global {
  interface Window {
    MyraaNative?: Bridge; MyraaEvents?: (event: any) => void; __mobileDiagnostics?: () => unknown;
    __nativeActionResults?: Record<string, unknown>;
    MyraaNativeAudio?: (payload: string, speech: boolean) => void;
  }
}

const bridge = window.MyraaNative;
const fallbackState: AppState = { history: [], model: 'gemini-3.5-flash', quality: 'balanced', language: 'bn-IN', keyConfigured: false, memory: [], permissions: { microphone: false, contacts: false, camera: false, notifications: false, overlay: false, phone: false, sms: false, accessibility: false } };
let state: AppState = bridge ? JSON.parse(bridge.getState()) : fallbackState;

let system: RobotCharacter | null = null;
let hud: HudStage | null = null;
let generation = 0;
let busy = false;
let requestId = '';
let lastReply = '';
let pendingText = '';
let foreground = true;
let backgroundLive = false;
let permissionPrompt = false;
let fullBody = true;
let showAllHistory = false;
let searchQuery = '';
let streaming: { element: HTMLElement; body: HTMLElement; text: string } | null = null;
let activityTimer: ReturnType<typeof setTimeout> | undefined;
let liveSession: MyraaAudioSession | null = null;
let liveCaption: { role: string; body: HTMLElement } | null = null;
let lastAudioDiag: Record<string, unknown> | null = null;

const actionRequests = new Map<string, { resolve: (result: any) => void; timer: ReturnType<typeof setTimeout> }>();

function runMobileAction(name: string, args: unknown): Promise<any> {
  if (!bridge?.mobileAction) return Promise.resolve({ status: 'unavailable', message: 'Device actions need the Android app.' });
  const id = crypto.randomUUID();
  return new Promise(resolve => {
    const timer = setTimeout(() => {
      actionRequests.delete(id); bridge.cancelMobileAction(id);
      resolve({ status: 'timeout', message: 'That action timed out and was cancelled. If a chooser or permission prompt opened, answer it, then ask again.' });
    }, 60000);
    actionRequests.set(id, { resolve, timer });
    bridge.mobileAction(id, name, JSON.stringify(args));
  });
}

function cancelMobileActions() {
  for (const [id, request] of actionRequests) {
    clearTimeout(request.timer); bridge?.cancelMobileAction(id);
    request.resolve({ status: 'cancelled', message: 'Action cancelled.' });
  }
  actionRequests.clear();
}

function stopVoice() {
  backgroundLive = false;
  const session = liveSession; liveSession = null;
  if (session) cancelMobileActions();
  session?.disconnect(); bridge?.endLive();
}

const app = document.querySelector('#app')!;
app.innerHTML = `
  <header class="topbar">
    <div class="brand">
      <span class="brand-mark">VJ</span>
      <div class="brand-text">
        <div class="brand-name">VISHAL JARVIS <span class="version">ANDROID</span></div>
        <small id="connection">Private on this device</small>
      </div>
    </div>
    <div class="topbar-actions">
      <button id="memory-button" class="icon" aria-label="Memory" title="What Vishal Jarvis remembers"><span aria-hidden="true">◈</span><i id="memory-badge" class="badge" hidden></i></button>
      <button id="theme-button" class="icon" aria-label="Switch theme" title="Switch theme">◐</button>
      <button id="settings" class="icon" aria-label="Settings" title="Settings">⚙</button>
    </div>
  </header>
  <div id="offline" class="banner" hidden>You are offline. Chat requests can be saved and will resume when connected.</div>
  <main>
    <section class="portrait" aria-label="Vishal Jarvis 3D robot character">
      <div class="halo"></div>
      <div id="hud">${hudMarkup()}</div>
      <div id="avatar"></div>
      <div class="avatar-status" id="avatar-status" role="status">Loading your character…</div>
      <span class="presence"><i></i> <span id="presence">Here with you</span></span>
      <div class="gestures" role="group" aria-label="Character controls">
        <button id="wave">Wave</button><button id="sit">Sit</button><button id="stand">Stand</button>
        <button id="happy">Smile</button><button id="view">Portrait</button>
      </div>
    </section>
    <section class="conversation" aria-label="Conversation">
      <div class="section-heading">
        <span>CONVERSATION</span>
        <div class="heading-actions"><button id="search-toggle" aria-expanded="false">Search</button><button id="history-toggle">History</button></div>
      </div>
      <div id="search-bar" class="search-bar" hidden>
        <input id="search" type="search" placeholder="Search this conversation" aria-label="Search conversation">
        <button id="search-clear">Clear</button>
      </div>
      <div id="messages" role="log" aria-live="polite"></div>
      <div id="quick" class="quick-actions" aria-label="Quick actions"></div>
      <div id="activity" class="activity" role="status" aria-live="polite" hidden></div>
      <div id="notice" class="notice" role="status"></div>
    </section>
  </main>
  <footer>
    <div class="composer">
      <button id="voice" aria-label="Voice conversation" aria-pressed="false" title="Start a live voice call">
        <svg viewBox="0 0 24 24" width="20" height="20" aria-hidden="true" fill="none" stroke="currentColor" stroke-width="1.8"><rect x="9" y="2" width="6" height="12" rx="3"/><path d="M5 10v2a7 7 0 0 0 14 0v-2M12 19v3M8 22h8"/></svg>
        <span>Talk</span>
      </button>
      <button id="attach" aria-label="Show Jarvis a photo" title="Attach a photo">
        <svg viewBox="0 0 24 24" width="20" height="20" aria-hidden="true" fill="none" stroke="currentColor" stroke-width="1.8"><path d="M4 8h3l2-3h6l2 3h3v11H4z"/><circle cx="12" cy="13" r="3.5"/></svg>
      </button>
      <div id="attach-preview" class="attach-preview" hidden><img id="attach-thumb" alt="Attached photo"><button id="attach-clear" aria-label="Remove photo">×</button></div>
      <div id="document-preview" class="document-preview" hidden><span id="document-name">Document</span><button id="document-clear" aria-label="Remove document">×</button><button id="document-ask" aria-label="Ask about the document">Ask</button></div>
      <textarea id="input" rows="1" maxlength="8000" placeholder="বলুন বা লিখুন — বলুন কী করতে হবে…" aria-label="Message"></textarea>
      <button id="send" aria-label="Send message" title="Send">↑</button>
    </div>
    <div class="footer-actions">
      <span id="status-line">Ready</span>
      <button id="stop">Stop</button>
      <button id="speak" aria-label="Read the last reply aloud">Read aloud</button>
    </div>
  </footer>

  <dialog id="settings-panel" aria-label="Settings">
    <form method="dialog" class="dialog-heading"><h2>Settings</h2><button aria-label="Close settings" value="close">×</button></form>
    <details open>
      <summary>Connection</summary>
      <p class="muted">Your key and conversations stay on this device. Messages and recent context are sent directly to Google when you ask for a reply.</p>
      <label>Gemini API key<input id="key" type="password" autocomplete="off" placeholder="Paste your own key"></label>
      <p id="key-state" class="muted"></p>
      <label>Text model<input id="model" autocomplete="off" placeholder="Model identifier"></label>
      <label>Live voice model<input id="live-model" autocomplete="off" placeholder="gemini-3.1-flash-live-preview"></label>
      <label>Language<select id="language">
        <option value="bn-IN">বাংলা (পশ্চিমবঙ্গ) · Default</option>
        <option value="hi-IN">Hindi / Hinglish</option>
        <option value="en-IN">English (India)</option>
        <option value="en-US">English</option>
      </select></label>
      <label>Graphics<select id="quality">
        <option value="low">Low · longest battery, no glow</option>
        <option value="balanced">Medium · glow, sharp suit</option>
        <option value="high">Maximum · bloom, reflections, full detail</option>
      </select></label>
      <label class="check"><input id="spoken" type="checkbox" checked> Read replies aloud with the Android voice (on by default)</label>
      <div class="button-row"><button id="save" class="primary">Save settings</button><button id="check-connection">Check key &amp; model</button></div>
      <p id="settings-status" class="muted" role="status" aria-live="polite"></p>
    </details>

    <details>
      <summary>Memory <span id="memory-count" class="pill">0</span></summary>
      <p class="muted">Vishal Jarvis remembers only what you approve here or ask it to remember. Stored on this device, never in the cloud.</p>
      <div class="memory-add"><input id="memory-input" maxlength="240" placeholder="e.g. I prefer replies in Bengali"><button id="memory-add">Remember</button></div>
      <ul id="memory-list" class="memory-list"></ul>
      <button id="memory-clear" class="danger">Forget everything</button>
    </details>

    <details>
      <summary>Permissions</summary>
      <p class="muted">Each permission is requested only when the matching feature runs, and you can grant it here in advance.</p>
      <ul id="permission-list" class="permission-list"></ul>
    </details>

    <details>
      <summary>Conversation &amp; device</summary>
      <div class="button-row"><button id="export-history">Copy transcript</button><button id="share-history">Share transcript</button><button id="save-history-file">Save .txt</button></div>
      <div class="button-row"><button id="clear-history" class="danger">Clear conversation history</button><button id="remove-key" class="danger">Remove API key</button></div>
      <div class="button-row"><button id="android-settings">Android app settings</button><button id="floating-settings">Floating voice controls</button></div>
      <p class="muted">Floating controls let Vishal Jarvis keep a live voice call going while another app is in front. WhatsApp and SMS open drafts that you send; calls only open the dialer. No screen capture or unrestricted device control.</p>
    </details>

    <details>
      <summary>Jarvis Pro</summary>
      <p class="muted">Wake word "Hey Jarvis", reminders, notes, notification and calendar reading, weather, daily briefing, find-my-phone, photo questions, fingerprint lock, backup and crash log. Quick Settings tile and home-screen widget are also available.</p>
      <p class="muted" id="pro-status"></p>
      <div class="button-row"><button id="pro-settings" class="primary">Open Jarvis Pro settings</button></div>
    </details>

    <details>
      <summary>Advanced</summary>
      <label>Theme<select id="theme"><option value="dark">Dark</option><option value="light">Light</option><option value="system">System</option></select></label>
      <label>Text size<select id="text-size"><option value="85">Small</option><option value="100">Default</option><option value="115">Large</option><option value="130">Extra large</option><option value="140">Largest</option></select></label>
      <label class="check"><input id="reduce-motion" type="checkbox"> Reduce motion and animation</label>
      <p class="muted" id="diagnostics"></p>
      <p class="muted" id="about"></p>
    </details>
  </dialog>

  <dialog id="memory-dialog" aria-label="Memory">
    <form method="dialog" class="dialog-heading"><h2>What I remember</h2><button aria-label="Close memory" value="close">×</button></form>
    <p class="muted">Say “remember that …” or add a fact below. Secrets such as passwords or keys are refused.</p>
    <div class="memory-add"><input id="memory-input-2" maxlength="240" placeholder="A fact or preference"><button id="memory-add-2">Remember</button></div>
    <ul id="memory-list-2" class="memory-list"></ul>
    <p id="memory-status" class="muted" role="status"></p>
  </dialog>
`;

const el = <T extends HTMLElement = HTMLElement>(id: string) => document.getElementById(id) as T;

/**
 * Live numbers for the HUD. Everything here is either measured on this device (heap use, call
 * state, online state) or derived from how busy the assistant is; nothing is claimed to be a real
 * CPU temperature, because Android does not expose one.
 */
const hudSamples = () => {
  const memory = (performance as unknown as { memory?: { usedJSHeapSize: number; jsHeapSizeLimit: number } }).memory;
  const heap = memory && memory.jsHeapSizeLimit > 0 ? (memory.usedJSHeapSize / memory.jsHeapSizeLimit) * 100 : undefined;
  const turns = state.history?.length || 0;
  const load = Math.min(100, (busy ? 62 : 14) + (turns % 9) * 3);
  return {
    cpu: load,
    memory: heap == null ? Math.min(100, turns * 2) : Math.min(100, heap),
    activity: busy ? 74 : el('voice').getAttribute('aria-pressed') === 'true' ? 52 : 12,
    battery: phoneBattery,
    uptime: sessionSeconds,
  };
};
let phoneBattery: number | undefined;
let sessionSeconds = 0;
interface BatteryLike { level: number; addEventListener(type: string, listener: () => void): void; }
try {
  const batteryApi = (navigator as unknown as { getBattery?: () => Promise<BatteryLike> }).getBattery;
  batteryApi?.call(navigator).then((battery: BatteryLike) => {
    const apply = () => { phoneBattery = Math.round(battery.level * 100); };
    apply();
    battery.addEventListener('levelchange', apply);
  }).catch(() => { /* battery status is a courtesy, never a requirement */ });
} catch { /* unsupported */ }
setInterval(() => { sessionSeconds++; }, 1000);
const notice = (message: string) => { el('notice').textContent = message; };
const vibe = (pattern: number | number[] = 8) => { try { navigator.vibrate?.(pattern); } catch { /* unsupported */ } };
const escapeHtml = (value: string) => value.replace(/[&<>"']/g, c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]!));

function startHud() {
  const stage = document.getElementById('hud');
  if (!stage || hud) return;
  hud = new HudStage(stage, hudSamples);
  hud.setTurns(state.history?.length || 0);
  hud.start();
}

function timeLabel(at?: number) {
  const date = at ? new Date(at) : new Date();
  return date.toLocaleTimeString(undefined, { hour: '2-digit', minute: '2-digit' });
}

function append(role: string, text: string, at?: number) {
  const item = document.createElement('article');
  item.className = `message ${role}`;
  const label = document.createElement('small');
  label.textContent = role === 'user' ? 'YOU' : role === 'tool' ? 'ON YOUR PHONE' : 'VISHAL JARVIS';
  const when = document.createElement('time');
  when.textContent = timeLabel(at);
  label.append(' · ', when);
  const body = document.createElement('p');
  body.textContent = text;
  item.append(label, body);
  el('messages').appendChild(item);
  scrollToEnd();
  return item;
}

function scrollToEnd(force = true) {
  const list = el('messages');
  const near = list.scrollHeight - list.scrollTop - list.clientHeight < 160;
  if (force || near) list.scrollTop = list.scrollHeight;
}

function addActions(item: HTMLElement, text: string, withRetry: boolean) {
  if (!text.trim()) return;
  const row = document.createElement('div');
  row.className = 'message-actions';
  const copy = document.createElement('button');
  copy.textContent = 'Copy';
  copy.onclick = () => { bridge?.copyText(text) ?? navigator.clipboard?.writeText(text).catch(() => undefined); notice('Copied to the clipboard.'); };
  const speak = document.createElement('button');
  speak.textContent = 'Read';
  speak.onclick = () => { stopVoice(); if (bridge) bridge.speak(text); else notice('Reading aloud needs the Android app.'); };
  row.append(copy, speak);
  if (withRetry) {
    const retry = document.createElement('button');
    retry.textContent = 'Retry';
    retry.onclick = () => retryLast();
    row.append(retry);
  }
  item.appendChild(row);
}

function emptyState() {
  const card = document.createElement('div');
  card.className = 'empty';
  if (!state.keyConfigured) {
    card.innerHTML = `<h3>Let's get set up</h3>
      <p>The assistant needs your own Gemini API key. It is stored in Android Keystore on this device and never leaves it except as an authenticated request to Google.</p>
      <button class="primary" id="empty-settings">Add API key</button>`;
    card.querySelector('#empty-settings')!.addEventListener('click', () => openSettings());
  } else {
    card.innerHTML = `<h3>What should we do?</h3>
      <p>Ask for anything on your phone, or try one of these:</p>
      <div class="suggestions">
        <button data-say="খবর কী? সংক্ষেপে বলো">সংক্ষেপে খবর</button>
        <button data-say="Open WhatsApp">Open WhatsApp</button>
        <button data-say="Set an alarm for 7:00">Alarm 7:00</button>
        <button data-say="আমার ফোনের ব্যাটারি কত?">Battery status</button>
        <button data-say="Remember that I prefer Bengali replies">Remember a fact</button>
      </div>`;
    card.querySelectorAll<HTMLButtonElement>('[data-say]').forEach(button => button.addEventListener('click', () => {
      el<HTMLTextAreaElement>('input').value = button.dataset.say!;
      autosize(); el<HTMLTextAreaElement>('input').focus();
    }));
  }
  el('messages').appendChild(card);
}

let recentToolLines: string[] = [];

function renderHistory() {
  el('messages').replaceChildren();
  streaming = null;
  const query = searchQuery.trim().toLowerCase();
  let turns = query ? state.history.filter(turn => (turn.text || '').toLowerCase().includes(query)) : showAllHistory ? state.history : state.history.slice(-6);
  turns = turns.slice(-120);
  const pending = (state.pending || []).filter(task => !query || task.text.toLowerCase().includes(query));
  if (!turns.length && !pending.length) {
    if (query) {
      const none = document.createElement('div');
      none.className = 'empty';
      none.innerHTML = `<h3>No matches</h3><p>Nothing in this conversation matches “${escapeHtml(searchQuery.trim())}”.</p>`;
      el('messages').appendChild(none);
      return;
    }
    emptyState();
    for (const line of recentToolLines.slice(-6)) append('tool', line);
    return;
  }
  let lastModel = -1;
  turns.forEach((turn, index) => { if (turn.role === 'model') lastModel = index; });
  turns.forEach((turn, index) => {
    const item = append(turn.role, turn.text, turn.at);
    if (turn.role === 'model') addActions(item, turn.text, index === lastModel && !busy);
  });
  pending.forEach(task => {
    const item = append('user', task.text);
    const status = document.createElement('small');
    status.textContent = task.status === 'running' ? 'PROCESSING' : 'SAVED · WAITING TO RESUME';
    item.appendChild(status);
  });
  if (!query && recentToolLines.length) {
    for (const line of recentToolLines.slice(-6)) append('tool', line);
  }
  if (query) {
    const label = document.createElement('div');
    label.className = 'muted search-note';
    label.textContent = `${turns.length + pending.length} match${turns.length + pending.length === 1 ? '' : 'es'} for “${searchQuery.trim()}”`;
    el('messages').prepend(label);
  }
}

function autosize() {
  const input = el<HTMLTextAreaElement>('input');
  input.style.height = 'auto';
  input.style.height = `${Math.min(input.scrollHeight, 132)}px`;
}

function setStatus(text: string, offline = false) {
  el('status-line').textContent = text;
  el('offline').hidden = !offline;
}

function activity(text: string, tone: 'work' | 'done' | 'warn' = 'work', sticky = false) {
  const strip = el('activity');
  strip.hidden = false;
  strip.className = `activity ${tone}`;
  strip.innerHTML = `<i class="spinner" aria-hidden="true"></i><span>${escapeHtml(text)}</span>`;
  clearTimeout(activityTimer);
  if (!sticky) activityTimer = setTimeout(() => { strip.hidden = true; }, tone === 'done' ? 3200 : 9000);
}

function renderQuickActions() {
  const chips: Array<{ label: string; action?: Record<string, unknown>; fill?: string }> = [
    { label: 'Torch', action: { action: 'set_flashlight', enabled: true } },
    { label: 'Wi‑Fi', action: { action: 'open_wifi_settings' } },
    { label: 'WhatsApp', action: { action: 'open_app', app: 'WhatsApp' } },
    { label: 'Battery', action: { action: 'device_status' } },
    { label: 'Alarm', fill: 'Set an alarm for 7:00' },
    { label: 'Camera', action: { action: 'open_camera' } },
    { label: 'Search', fill: 'Search the web for ' },
  ];
  const host = el('quick');
  host.replaceChildren();
  for (const chip of chips) {
    const button = document.createElement('button');
    button.type = 'button';
    button.textContent = chip.label;
    button.onclick = () => {
      vibe(10);
      if (chip.fill) { const input = el<HTMLTextAreaElement>('input'); input.value = chip.fill; autosize(); input.focus(); return; }
      void runMobileAction('mobile_action', chip.action).then(result => {
        append('tool', result?.message || 'Action finished.');
        notice(result?.message || '');
        setStatus('Ready', !navigator.onLine);
        system?.setFrameInput({ activity: 'idle', emotion: result?.status === 'opened' || result?.status === 'play_started' ? 'happy' : 'idle' });
        vibe(result?.status === 'opened' || result?.status === 'play_started' ? [12, 40, 12] : 20);
      });
    };
    host.appendChild(button);
  }
}

function renderMemory() {
  const facts = state.memory || [];
  el('memory-count').textContent = String(facts.length);
  el('memory-badge').hidden = facts.length === 0;
  for (const id of ['memory-list', 'memory-list-2']) {
    const list = el(id);
    list.replaceChildren();
    if (!facts.length) {
      const empty = document.createElement('li');
      empty.className = 'muted';
      empty.textContent = 'Nothing remembered yet.';
      list.appendChild(empty);
      continue;
    }
    for (const fact of facts) {
      const item = document.createElement('li');
      const text = document.createElement('span');
      text.textContent = fact.text;
      const remove = document.createElement('button');
      remove.textContent = '✕';
      remove.setAttribute('aria-label', `Forget: ${fact.text}`);
      remove.onclick = () => bridge?.deleteFact(fact.id);
      item.append(text, remove);
      list.appendChild(item);
    }
  }
}

const PERMISSIONS: Array<{ key: keyof PermissionState; label: string; why: string }> = [
  { key: 'microphone', label: 'Microphone', why: 'Live voice calls' },
  { key: 'contacts', label: 'Contacts', why: 'Message and call people by name' },
  { key: 'phone', label: 'Phone', why: 'Place calls directly and pick the SIM' },
  { key: 'sms', label: 'SMS', why: 'Send text messages directly' },
  { key: 'accessibility', label: 'Direct sending in chat apps', why: 'Presses Send in WhatsApp, Telegram, Instagram and Messenger' },
  { key: 'camera', label: 'Camera', why: 'Flashlight control' },
  { key: 'notifications', label: 'Notifications', why: 'Live voice and finished background tasks' },
  { key: 'overlay', label: 'Floating controls', why: 'Switch apps during a call' },
];

function renderPermissions() {
  const list = el('permission-list');
  list.replaceChildren();
  const permissions = state.permissions;
  for (const entry of PERMISSIONS) {
    const granted = permissions?.[entry.key] === true;
    const item = document.createElement('li');
    item.className = granted ? 'granted' : '';
    const text = document.createElement('div');
    text.innerHTML = `<strong>${entry.label}</strong><small>${entry.why}</small>`;
    const button = document.createElement('button');
    button.textContent = granted ? 'Allowed' : entry.key === 'accessibility' ? 'Enable' : 'Allow';
    button.disabled = granted;
    button.onclick = () => bridge?.requestPermission(entry.key);
    item.append(text, button);
    list.appendChild(item);
  }
}

function refreshSettings() {
  el<HTMLInputElement>('model').value = state.model;
  el<HTMLInputElement>('live-model').value = state.liveModel || 'gemini-3.1-flash-live-preview';
  el<HTMLSelectElement>('quality').value = state.quality;
  el<HTMLSelectElement>('language').value = state.language;
  el<HTMLInputElement>('spoken').checked = state.spokenReplies !== false;
  el<HTMLSelectElement>('text-size').value = String(state.textScale || 100);
  el('about').textContent = `Vishal Jarvis for Android ${state.version || ''} · created by Vishal · package com.myraa.mobile`.trim();
  refreshKeyStatus();
  renderMemory();
  renderPermissions();
}

function refreshKeyStatus() {
  el('key-state').textContent = state.keyConfigured
    ? `A key is saved in Android Keystore. ${state.keyVerified ? 'Google accepted it for this model.' : 'Use Check key & model to verify access.'}`
    : 'No API key configured yet. Add your own key to chat.';
}

function settingsProgress(active: boolean, message: string) {
  el('settings-status').textContent = message;
  for (const control of document.querySelectorAll<HTMLInputElement | HTMLSelectElement | HTMLButtonElement>('#settings-panel input,#settings-panel select,#save,#check-connection')) control.disabled = active;
}

function openSettings() {
  stopVoice(); refreshSettings();
  let audioDiag = lastAudioDiag;
  try { if (!audioDiag && bridge?.audioDiagnostics) audioDiag = JSON.parse(bridge.audioDiagnostics() || '{}'); } catch { /* keep last */ }
  el('diagnostics').textContent = JSON.stringify({ ...(system?.diagnostics || {}), audio: audioDiag || {} });
  el<HTMLDialogElement>('settings-panel').showModal();
}

function openMemory() {
  renderMemory(); el<HTMLDialogElement>('memory-dialog').showModal();
}

function remember(text: string) {
  const value = text.trim();
  if (value.length < 3) { notice('Write a little more so the fact is useful.'); return; }
  if (!bridge) { notice('Memory needs the Android app.'); return; }
  try {
    const result = JSON.parse(bridge.rememberFact(value) || '{}');
    if (result.message) notice(result.message);
  } catch { notice('That fact could not be saved.'); }
}

async function loadAvatar() {
  const mine = ++generation; system?.dispose(); system = null;
  el('avatar').replaceChildren();
  const canvas = document.createElement('canvas'); el('avatar').appendChild(canvas);
  el('avatar-status').textContent = 'Starting Vishal Jarvis…';
  try {
    const instance = new RobotCharacter(canvas, (state.quality || 'balanced') as 'low' | 'balanced' | 'high');
    system = instance;
    const box = el('avatar').getBoundingClientRect();
    instance.resize(box.width, box.height);
    await instance.load(); if (mine !== generation) return;
    if (fullBody) instance.frameFullBody();
    instance.setEyeTracking(true); if (foreground && !document.hidden) instance.start();
    el('avatar-status').textContent = '';
  } catch (error) { if (mine === generation) el('avatar-status').textContent = `Character could not load. ${String(error)}`; }
}

function liveState(value: LiveState) {
  const active = value !== 'disconnected';
  if (value === 'listening') notice('Live voice connected. Speak naturally.');
  el('voice').setAttribute('aria-pressed', String(active));
  el('voice').querySelector('span')!.textContent = active ? 'End' : 'Talk';
  el('presence').textContent = value === 'connecting' ? 'Connecting live…' : value === 'speaking' ? 'Speaking with you' : value === 'listening' ? 'Live · listening to you' : 'Here with you';
  hud?.setActivity(value === 'speaking' ? 'speaking' : value === 'listening' ? 'listening' : value === 'connecting' ? 'linking' : 'idle');
  setStatus(value === 'disconnected' ? 'Ready' : value === 'connecting' ? 'Connecting…' : 'Live call in progress', !navigator.onLine);
  system?.setFrameInput({ activity: value === 'speaking' ? 'talking' : value === 'listening' ? 'listening' : 'idle',
    outputAnalyser: liveSession?.outputAnalyser ?? null, inputAnalyser: liveSession?.inputAnalyser ?? null });
}

function liveTranscription(role: 'user' | 'model', text: string) {
  if (!liveCaption || liveCaption.role !== role) {
    append(role, ''); liveCaption = { role, body: el('messages').lastElementChild!.querySelector('p')! };
  }
  liveCaption.body.textContent = ((liveCaption.body.textContent || '') + text).slice(-8000);
  scrollToEnd();
  if (role === 'model') lastReply = liveCaption.body.textContent;
}

function rememberToolLine(line: string) {
  recentToolLines = [...recentToolLines, line].slice(-6);
}

function finishStream() {
  if (!streaming) return;
  const item = streaming.element;
  const text = streaming.text.trim();
  streaming = null;
  item.classList.remove('streaming');
  if (text) addActions(item, text, false);
}

function retryLast() {
  const lastUser = [...state.history].reverse().find(turn => turn.role === 'user');
  if (!lastUser) { notice('There is nothing to retry yet.'); return; }
  el<HTMLTextAreaElement>('input').value = lastUser.text;
  send();
}

window.MyraaEvents = event => {
  if (event.type === 'mobile-action-result') {
    const request = actionRequests.get(event.id);
    if (request) { clearTimeout(request.timer); actionRequests.delete(event.id); request.resolve(event.result); }
    if (event.result?.message) notice(event.result.message);
    return;
  }
  if (event.type === 'tool-call') {
    activity(describeAction(String(event.args?.action || event.name || ''), (event.args || {}) as Record<string, unknown>), 'work', true);
    if (event.name === 'remember_fact') activity('Saving that to memory', 'work', true);
    vibe(6);
    return;
  }
  if (event.type === 'tool-result') {
    const result = event.result || {};
    const good = ['opened', 'play_started', 'search_opened', 'settings_opened', 'torch_changed', 'alarm_opened', 'timer_opened', 'event_opened', 'navigated', 'shared', 'copied', 'media_sent', 'volume_changed', 'info', 'apps', 'saved', 'call_started', 'dialer_opened', 'sent', 'sent_unverified', 'sms_queued', 'sms_delivered_to_network', 'reminder_set', 'reminders', 'cancelled', 'notes', 'deleted', 'notifications', 'cleared', 'calendar', 'weather', 'briefing', 'ringing', 'stopped'].includes(result.status);
    if (result.data?.suggestions) activity(`${result.message} ${result.data.suggestions}`, 'warn', false);
    // A blocked action must say where the switch is, in one line, instead of a dead end.
    if (result.data?.needsAccessibility) notice("Turn on Settings → Accessibility → \"Vishal Jarvis screen reader & message sender\" to send messages directly. Nothing was sent.");
    if (result.status === 'needs_confirmation') notice('Say “send” to confirm, or “cancel” to drop it.');
    activity(result.message || (good ? 'Done.' : 'Not completed.'), good ? 'done' : 'warn', false);
    return;
  }
  if (event.type === 'interrupted') {
    speaking = false;
    activity('Interrupted — answering your new message', 'work', false);
    return;
  }
  if (event.type === 'speech') {
    speaking = !!event.active;
    hud?.setActivity(speaking ? 'speaking' : busy ? 'working' : 'idle');
    return;
  }
  if (event.type === 'delta' && event.id === requestId) {
    if (!streaming) {
      const item = append('model', ''); item.classList.add('streaming');
      streaming = { element: item, body: item.querySelector('p')!, text: '' };
    }
    streaming.text += String(event.text || '');
    streaming.body.textContent = streaming.text;
    scrollToEnd(false);
    return;
  }
  if (event.type === 'live-token') backgroundLive = !!event.backgroundLive;
  if (event.type === 'live-token' || event.type === 'live-error') { window.dispatchEvent(new CustomEvent('myraa:mobile-live', { detail: event })); return; }
  if (event.type === 'settings') {
    settingsProgress(event.status === 'checking', event.message || '');
    if (event.state) {
      const changedQuality = event.state.quality !== state.quality;
      state = event.state; renderMemory(); renderPermissions(); renderQuickActions();
      if (event.status === 'saved' || event.status === 'cleared') el<HTMLInputElement>('key').value = '';
      if (event.status === 'checked') refreshKeyStatus(); else refreshSettings();
      if (event.status === 'saved') {
        el<HTMLDialogElement>('settings-panel').close();
        notice(event.message || 'Settings saved.');
        renderHistory();
        if (changedQuality) void loadAvatar();
      }
    }
    return;
  }
  if (event.type === 'memory' || event.type === 'permissions') {
    if (event.state) { state = event.state; renderMemory(); renderPermissions(); renderHistory(); }
    if (event.message) { notice(event.message); el('memory-status').textContent = event.message; }
    return;
  }
  if (event.type === 'image-attached') {
    if (event.ok && event.thumb) { attachedImage = true; el<HTMLImageElement>('attach-thumb').src = event.thumb; el('attach-preview').hidden = false; }
    notice(event.message || '');
    return;
  }
  if (event.type === 'auto-voice') { autoVoice(); return; }
  if (event.type === 'clipboard') { notice(event.message || 'Copied.'); return; }
  if (event.type === 'capability') {
    notice(event.message || '');
    // A voice problem is actionable, so show it as a plain message with the settings hint.
    if (event.tts === false) notice((event.message || '') + ' Settings → Jarvis Pro → "Speak replies" and Android text-to-speech settings.');
    return;
  }
  if (event.type === 'state') { state = event.state; refreshSettings(); renderQuickActions(); renderHistory(); return; }
  if (event.type === 'queued') {
    if (event.id === requestId) { busy = false; pendingText = ''; finishStream(); }
    state = bridge ? JSON.parse(bridge.getState()) : state;
    renderHistory(); notice(event.message || 'Saved for background processing.');
    el('presence').textContent = 'Here with you'; setStatus('Saved · waiting to resume', !navigator.onLine);
    system?.setFrameInput({ activity: 'idle', emotion: 'idle' });
    return;
  }
  if (event.type === 'reply' && event.id === requestId) {
    busy = false; finishStream();
    lastReply = event.text; notice(''); pendingText = '';
    state = bridge ? JSON.parse(bridge.getState()) : state;
    renderHistory(); refreshSettings();
    el('presence').textContent = 'Here with you'; setStatus('Ready', !navigator.onLine);
    system?.setFrameInput({ activity: 'idle', emotion: 'happy' });
    return;
  }
  if (event.type === 'error') {
    if (event.id && event.id !== requestId) {
      notice(`Background task: ${event.message || 'could not finish.'}`);
      if (bridge) { state = JSON.parse(bridge.getState()); renderHistory(); }
      return;
    }
    if (event.service === 'text' || event.service === 'live') stopVoice();
    notice(event.message);
    if (event.service === 'text' || event.id === requestId) {
      busy = false; finishStream();
      if (pendingText) el<HTMLTextAreaElement>('input').value = pendingText;
      el('presence').textContent = 'Here with you'; setStatus('Ready', !navigator.onLine);
      system?.setFrameInput({ activity: 'idle', emotion: 'confused' });
    }
    return;
  }
  if (event.type === 'speech' && !liveSession) system?.setFrameInput({ activity: event.active ? 'talking' : 'idle' });
};


/** "open YouTube and play lofi" runs the open first, then the second half. */
async function runMobileWithFollowUp(action: Record<string, unknown>, originalText: string) {
  setStatus('Working on your phone…');
  const result = await runMobileAction('mobile_action', action);
  const firstMessage = result?.message || 'Action finished.';
  append('tool', firstMessage); rememberToolLine(firstMessage);
  notice(firstMessage);
  vibe(result?.status === 'opened' || result?.status === 'play_started' ? [12, 40, 12] : 20);
  const followUp = /^(?:please\s+)?(?:open|launch|start|খোলো)\s+.+?\s+(?:and|then|আর|এবং|তারপর)\s+(.+)$/iu.exec(originalText);
  const failed = ['not_installed', 'invalid', 'unavailable', 'failed', 'permission_required'].includes(String(result?.status));
  if (followUp && !failed) {
    const next = parseMobileAction(followUp[1]);
    if (next) {
      const second = await runMobileAction('mobile_action', next);
      const secondMessage = second?.message || 'Action finished.';
      append('tool', secondMessage); rememberToolLine(secondMessage);
      notice(secondMessage);
    }
  }
  setStatus('Ready', !navigator.onLine);
  system?.setFrameInput({ activity: 'idle', emotion: result?.status === 'opened' || result?.status === 'play_started' ? 'happy' : 'idle' });
}

let attachedImage = false;
let attachedDocument = false;
function clearAttachment() {
  attachedImage = false; el('attach-preview').hidden = true; el<HTMLImageElement>('attach-thumb').removeAttribute('src');
}
function autoVoice() {
  const take = bridge?.takeLaunchAction?.();
  if (take !== 'voice') return;
  if (liveSession && liveSession.getState() !== 'disconnected') return;
  if (busy) return;
  el('voice').click();
}
/** True while the Android side is speaking a reply; a new message must silence it at once. */
let speaking = false;
function stopSpeakingNow() {
  if (!speaking) return;
  speaking = false;
  bridge?.stopSpeaking?.();
}
function send() {
  const input = el<HTMLTextAreaElement>('input');
  let text = input.value.trim();
  if (!text && attachedImage) text = 'What is in this photo? Explain it briefly.';
  if (!text) return;
  if (attachedImage) {
    if (busy) { notice('A reply is still running. Tap Stop first, or wait for it.'); return; }
    if (!bridge) return;
    if (!state.keyConfigured) { notice('Add your Gemini API key in Settings before sending.'); openSettings(); return; }
    vibe();
    requestId = crypto.randomUUID(); busy = true; pendingText = text;
    input.value = ''; autosize(); append('user', `[Photo] ${text}`); clearAttachment();
    notice(''); setStatus('Looking at the photo…');
    el('presence').textContent = 'Looking closely';
    system?.setFrameInput({ activity: 'thinking', emotion: 'thinking' });
    bridge.chat(text, requestId);
    return;
  }
  // The user's next sentence always comes first: stop the voice before anything else, so the new
  // question is answered immediately instead of queueing behind the answer being read out.
  stopSpeakingNow();
  vibe();
  const mobile = parseMobileAction(text);
  if (mobile) {
    input.value = ''; autosize(); append('user', text);
    void runMobileWithFollowUp(mobile, text);
    return;
  }
  if (liveSession && liveSession.getState() !== 'disconnected') {
    liveSession.sendText(text); input.value = ''; autosize(); append('user', text); return;
  }
  const command = parseCharacterCommand(text);
  if (command) {
    input.value = ''; autosize(); append('user', text);
    if (command.side) { notice('Desktop-side placement is available on Windows.'); return; }
    if (command.action === 'next_character') { notice('Vishal Jarvis includes its built-in robotic companion character.'); return; }
    if (command.action === 'wave' || command.action === 'dance') system?.playBehaviour(command.action);
    else if (command.action === 'sit') system?.sit({ seat: 'chair', style: 'upright' });
    else if (command.action === 'follow_cursor') system?.setEyeTracking(true);
    else system?.stand();
    notice(`Requested: ${command.action.replace('_', ' ')}.`);
    return;
  }
  // Only a Gemini text reply needs the busy lock; phone actions always run immediately.
  if (busy) {
    // Do not make the user wait behind a reply they have already moved on from.
    stopSpeakingNow();
    bridge?.cancel?.();
    busy = false; requestId = '';
    activity('Interrupted — answering your new message', 'work', false);
  }
  if (!bridge) { notice('Native chat runs inside the Android app. This is a browser preview.'); return; }
  if (!state.keyConfigured) { notice('Add your Gemini API key in Settings before sending.'); openSettings(); return; }
  requestId = crypto.randomUUID(); busy = true; pendingText = text;
  input.value = ''; autosize(); append('user', text);
  notice(''); setStatus('Thinking…');
  el('presence').textContent = 'Thinking with you';
  system?.setFrameInput({ activity: 'thinking', emotion: 'thinking' });
  bridge.chat(text, requestId);
}

el('send').onclick = send;
el('attach').onclick = () => {
  if (!bridge?.pickImage) { notice('Photo questions run inside the Android app.'); return; }
  // One button, two kinds of attachment: a picture for the model to look at, or a PDF/text file
  // whose text is extracted on the phone before the next message.
  const choices = document.createElement('div');
  choices.className = 'attach-choices';
  choices.innerHTML = '<button type="button" data-kind="photo">Photo or screenshot</button><button type="button" data-kind="document">PDF or text file</button>';
  choices.addEventListener('click', event => {
    const kind = (event.target as HTMLElement).dataset.kind;
    choices.remove();
    if (kind === 'document' && bridge?.pickDocument) bridge.pickDocument();
    else bridge.pickImage?.();
  });
  el('notice').replaceChildren(choices);
};
el('attach-clear').onclick = () => { bridge?.clearImage?.(); clearAttachment(); notice('Photo removed.'); };
el('document-clear').onclick = () => { attachedDocument = false; el('document-preview').hidden = true; notice('Document removed from this chat.'); };
el('document-ask').onclick = () => { el<HTMLTextAreaElement>('input').value = 'এই ডকুমেন্টটা সংক্ষেপে বুঝিয়ে দাও।'; el<HTMLTextAreaElement>('input').focus(); };
el('pro-settings').onclick = () => { if (!bridge?.openProSettings) { notice('Jarvis Pro settings are in the Android app.'); return; } bridge.openProSettings(); };
el('input').addEventListener('input', autosize);
el('input').addEventListener('keydown', event => {
  if (event.key === 'Enter' && !event.shiftKey) { event.preventDefault(); send(); }
});
el('stop').onclick = () => {
  cancelMobileActions(); stopVoice(); bridge?.cancel(); bridge?.stopSpeech();
  busy = false; requestId = ''; finishStream();
  notice('Stopped.'); setStatus('Ready');
  system?.stopBehaviour(); system?.setFrameInput({ activity: 'idle', emotion: 'idle' });
  el('presence').textContent = 'Here with you';
};
el('voice').onclick = () => {
  if (liveSession && liveSession.getState() !== 'disconnected') { stopVoice(); notice('Live voice ended.'); return; }
  if (!bridge) { notice('Gemini Live voice runs inside the Android app.'); return; }
  if (!state.keyConfigured) { notice('Add a working Gemini key in Settings to start Live voice.'); openSettings(); return; }
  if (busy) { notice('Stop the current text reply before starting Live voice.'); return; }
  if (liveSession && liveSession.getState() === 'connecting') { notice('Still connecting to Live voice…'); return; }
  bridge.stopSpeech(); liveCaption = null;
  liveSession = new MyraaAudioSession({
    connection: {
      createSocket: () => new MobileLiveTransport(bridge),
      startNativeCapture: async () => {
        if (!bridge.startNativeCapture) return null;
        try {
          const diagnostics = JSON.parse(bridge.startNativeCapture() || '{}') as Record<string, unknown>;
          lastAudioDiag = diagnostics;
          return diagnostics;
        } catch {
          return { ok: false };
        }
      },
      stopNativeCapture: () => { try { bridge.stopNativeCapture?.(); } catch { /* already stopped */ } },
    },
    onStateChange: liveState, onTranscription: liveTranscription,
    onToolCall: (name, args, callback) => {
      if (name === 'remember_fact') {
        let result: Record<string, unknown> = { status: 'rejected', message: 'Memory is unavailable.' };
        try {
          const parsed = JSON.parse(bridge?.rememberFact(String((args as any)?.fact || '')) || '{}');
          if (parsed.status) result = parsed;
        } catch { /* keep the honest failure */ }
        callback(result);
        return;
      }
      if (name !== 'mobile_action') { callback({ status: 'unsupported', message: 'That tool is unavailable on mobile.' }); return; }
      activity(describeAction(String((args as any)?.action || ''), (args || {}) as Record<string, unknown>), 'work', true);
      void runMobileAction(name, args).then(result => { notice(result.message); callback(result); });
    },
    onError: message => { notice(message); stopVoice(); },
  });
  notice('Connecting to Vishal Jarvis Live…'); void liveSession.connect(true);
};
el('speak').onclick = () => {
  const text = lastReply || state.history.filter(turn => turn.role === 'model').at(-1)?.text;
  if (text) { stopVoice(); bridge?.speak(text); } else notice('There is no reply to read yet.');
};
el('wave').onclick = () => { system?.setFrameInput({ emotion: 'happy' }); system?.playBehaviour('wave'); };
el('sit').onclick = () => { system?.frameFullBody(); system?.sit({ seat: 'chair', style: 'upright' }); };
el('stand').onclick = () => { system?.stand(); if (!fullBody) { system?.stage.clearPan(); system?.setCameraFocus(null); } };
el('view').onclick = () => {
  fullBody = !fullBody;
  if (fullBody) system?.frameFullBody(); else { system?.stage.clearPan(); system?.setCameraFocus(null); }
  el('view').textContent = fullBody ? 'Portrait' : 'Full body';
};
el('happy').onclick = () => { system?.setFrameInput({ emotion: 'happy' }); setTimeout(() => system?.setFrameInput({ emotion: 'idle' }), 3000); };
el('settings').onclick = openSettings;
el('memory-button').onclick = openMemory;
el('save').onclick = () => {
  if (!bridge) { notice('Save settings in the installed Android app.'); return; }
  settingsProgress(true, 'Checking and saving settings…');
  bridge.configure(JSON.stringify({
    key: el<HTMLInputElement>('key').value.trim(),
    liveModel: el<HTMLInputElement>('live-model').value.trim(),
    model: el<HTMLInputElement>('model').value.trim(),
    quality: el<HTMLSelectElement>('quality').value,
    language: el<HTMLSelectElement>('language').value,
    spokenReplies: el<HTMLInputElement>('spoken').checked,
  }));
};
el('check-connection').onclick = () => {
  if (!bridge) { notice('Check the connection in the installed Android app.'); return; }
  settingsProgress(true, 'Checking Google key and model access…');
  bridge.checkConnection(JSON.stringify({
    key: el<HTMLInputElement>('key').value.trim(),
    liveModel: el<HTMLInputElement>('live-model').value.trim(),
    model: el<HTMLInputElement>('model').value.trim(),
  }));
};
el('history-toggle').onclick = () => {
  showAllHistory = !showAllHistory;
  renderHistory();
  el('history-toggle').textContent = showAllHistory ? 'Recent' : 'History';
  document.querySelector('.conversation')!.classList.toggle('expanded', showAllHistory);
};
el('search-toggle').onclick = () => {
  const bar = el('search-bar');
  bar.hidden = !bar.hidden;
  el('search-toggle').setAttribute('aria-expanded', String(!bar.hidden));
  if (!bar.hidden) el<HTMLInputElement>('search').focus();
};
el('search').addEventListener('input', () => { searchQuery = el<HTMLInputElement>('search').value; renderHistory(); });
el('search-clear').onclick = () => { el<HTMLInputElement>('search').value = ''; searchQuery = ''; renderHistory(); };
el('clear-history').onclick = () => {
  if (confirm('Clear conversation history on this device?')) {
    bridge?.clearHistory(); state = bridge ? JSON.parse(bridge.getState()) : state;
    lastReply = ''; renderHistory(); notice('Conversation cleared.');
  }
};
el('remove-key').onclick = () => {
  if (confirm('Remove your saved Gemini API key?')) {
    bridge?.clearKey(); state = bridge ? JSON.parse(bridge.getState()) : state;
    refreshSettings(); renderHistory(); notice('Saved key removed.');
  }
};
const transcript = () => state.history.map(turn => `${turn.role === 'user' ? 'You' : 'Vishal Jarvis'}: ${turn.text}`).join('\n\n');
el('export-history').onclick = () => { const text = transcript(); if (!text) { notice('There is nothing to copy yet.'); return; } bridge?.copyText(text); notice('Transcript copied to the clipboard.'); };
el('share-history').onclick = () => { if (!transcript()) { notice('There is nothing to share yet.'); return; } bridge?.shareTranscript(); };
el('save-history-file').onclick = () => { if (!transcript()) { notice('There is nothing to save yet.'); return; } bridge?.exportTranscript(); };
el('android-settings').onclick = () => bridge?.openSettings();
el('floating-settings').onclick = () => bridge?.openFloatingSettings();
el('memory-add').onclick = () => { remember(el<HTMLInputElement>('memory-input').value); el<HTMLInputElement>('memory-input').value = ''; };
el('memory-add-2').onclick = () => { remember(el<HTMLInputElement>('memory-input-2').value); el<HTMLInputElement>('memory-input-2').value = ''; };
for (const id of ['memory-input', 'memory-input-2']) {
  el<HTMLInputElement>(id).addEventListener('keydown', event => {
    if ((event as KeyboardEvent).key === 'Enter') { event.preventDefault(); remember(el<HTMLInputElement>(id).value); el<HTMLInputElement>(id).value = ''; }
  });
}
el('memory-clear').onclick = () => { if (confirm('Forget every remembered fact?')) bridge?.clearMemory(); };

const THEME_KEY = 'vishal.jarvis.theme';
const MOTION_KEY = 'vishal.jarvis.reduceMotion';
function applyTheme(value: string) {
  const resolved = value === 'system' ? (matchMedia('(prefers-color-scheme: light)').matches ? 'light' : 'dark') : value;
  document.documentElement.dataset.theme = resolved;
  el<HTMLSelectElement>('theme').value = value;
  localStorage.setItem(THEME_KEY, value);
}
el<HTMLSelectElement>('theme').addEventListener('change', () => applyTheme(el<HTMLSelectElement>('theme').value));
matchMedia('(prefers-color-scheme: light)').addEventListener('change', () => { if (localStorage.getItem(THEME_KEY) === 'system') applyTheme('system'); });
el<HTMLSelectElement>('text-size').addEventListener('change', () => {
  const size = Number(el<HTMLSelectElement>('text-size').value);
  state.textScale = size;
  if (bridge) bridge.setTextScale(size);
  else document.documentElement.style.fontSize = `${size}%`;
});
el<HTMLInputElement>('reduce-motion').addEventListener('change', () => {
  const reduce = el<HTMLInputElement>('reduce-motion').checked;
  document.documentElement.dataset.motion = reduce ? 'reduced' : 'full';
  localStorage.setItem(MOTION_KEY, String(reduce));
});

const resize = new ResizeObserver(() => {
  const box = el('avatar').getBoundingClientRect();
  system?.resize(box.width, box.height);
  if (fullBody) system?.frameFullBody();
});
resize.observe(el('avatar'));
el('avatar').onpointermove = event => {
  const box = el('avatar').getBoundingClientRect();
  system?.setPointer((event.clientX - box.left) / box.width * 2 - 1, 1 - (event.clientY - box.top) / box.height * 2);
};
document.addEventListener('visibilitychange', () => {
  if (document.hidden) { if (!backgroundLive && !permissionPrompt) stopVoice(); system?.stop(); hud?.stop(); /* TTS is not stopped: Android speech continues in the background. */ }
  else { system?.start(); hud?.start(); }
});
window.addEventListener('myraa:lifecycle', event => {
  const detail = (event as CustomEvent).detail;
  foreground = !!detail?.active; permissionPrompt = !!detail?.permissionPrompt; backgroundLive = !!detail?.backgroundLive;
  if (!foreground && !backgroundLive && !detail?.permissionPrompt) stopVoice();
  if (foreground) { system?.start(); hud?.start(); } else { system?.stop(); hud?.stop(); } /* do not stop TTS/Live audio merely for backgrounding */
});
window.addEventListener('myraa:stop-live', () => { stopVoice(); notice('Live call stopped.'); });
window.addEventListener('pagehide', () => { stopVoice(); generation++; system?.dispose(); });
window.addEventListener('online', () => { setStatus('Ready'); notice('Back online.'); });
window.addEventListener('offline', () => { setStatus('Offline', true); notice('You are offline. Chat requests will be saved and retried when connected.'); });
window.__mobileDiagnostics = () => ({ ...(system?.diagnostics || {}), audio: lastAudioDiag || {} });
window.MyraaNativeAudio = (payload, speech) => { liveSession?.ingestNativePcm(payload, !!speech); };

applyTheme(localStorage.getItem(THEME_KEY) || 'dark');
const reduceMotion = localStorage.getItem(MOTION_KEY) === 'true';
el<HTMLInputElement>('reduce-motion').checked = reduceMotion;
document.documentElement.dataset.motion = reduceMotion ? 'reduced' : 'full';
setStatus(navigator.onLine ? 'Ready' : 'Offline', !navigator.onLine);
renderQuickActions(); refreshSettings(); renderHistory(); autosize();
if (state.lastFailedTask && !state.pendingTasks) {
  notice(state.lastFailedTask.message || 'A previous background task could not finish.');
  if (!el<HTMLTextAreaElement>('input').value) { el<HTMLTextAreaElement>('input').value = state.lastFailedTask.text; autosize(); }
} else if (state.pendingTasks) setStatus(`${state.pendingTasks} saved task${state.pendingTasks === 1 ? '' : 's'} pending`);
startHud();
void loadAvatar();
{
  const pro = (state as any).pro;
  if (pro) el('pro-status').textContent = `Wake word: ${pro.wakeWord ? 'on' : 'off'} · Notifications: ${pro.notificationAccess ? 'on' : 'off'} · Reminders: ${pro.reminders} · Notes: ${pro.notes}`;
}
setTimeout(autoVoice, 1200);
if (!bridge) notice('Browser preview — native chat, voice and device actions run inside the Android app.');

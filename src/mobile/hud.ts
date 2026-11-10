/**
 * The Stark-workshop HUD around the character, in crimson.
 *
 * It is drawn with SVG arcs, CSS gradients and canvas — no images, so it costs nothing to ship and
 * scales to any screen. Everything is decorative except the labels, and every number it shows is
 * either real (time, date, system state) or clearly labelled as a gauge of the assistant itself
 * (CPU/MEM of the app, activity rate, uptime). It never invents weather.
 *
 * The gear teeth are generated once; the arcs and needles are updated on a timer that only runs
 * while the window is visible.
 */
export interface HudSample {
  cpu?: number;
  memory?: number;
  battery?: number;
  activity?: number;
  uptime?: number;
}

const TEETH = 132;

function teethPath(radius: number, depth: number, count = TEETH): string {
  const parts: string[] = [];
  for (let i = 0; i < count; i++) {
    const outer = (i / count) * Math.PI * 2;
    const inner = ((i + 0.5) / count) * Math.PI * 2;
    const x1 = Math.cos(outer) * (radius + depth);
    const y1 = Math.sin(outer) * (radius + depth);
    const x2 = Math.cos(inner) * radius;
    const y2 = Math.sin(inner) * radius;
    parts.push(`${i === 0 ? 'M' : 'L'}${x1.toFixed(2)} ${y1.toFixed(2)}`, `L${x2.toFixed(2)} ${y2.toFixed(2)}`);
  }
  return `${parts.join(' ')} Z`;
}

/** The full markup; ids are prefixed so nothing collides with the rest of the window. */
export function hudMarkup(): string {
  const teeth = teethPath(118, 7);
  const ticks: string[] = [];
  for (let i = 0; i < 60; i++) {
    const angle = (i / 60) * 360;
    const major = i % 5 === 0;
    ticks.push(`<line x1="0" y1="${major ? -134 : -131}" x2="0" y2="-126" transform="rotate(${angle})" class="hud-tick${major ? ' major' : ''}"/>`);
  }
  return `
  <div class="hud" aria-hidden="true">
    <svg class="hud-rings" viewBox="-160 -160 320 320" preserveAspectRatio="xMidYMid meet">
      <defs>
        <linearGradient id="hudArc" x1="0" y1="0" x2="1" y2="1">
          <stop offset="0%" stop-color="#ff2442" stop-opacity=".15"/>
          <stop offset="55%" stop-color="#ff2442" stop-opacity=".95"/>
          <stop offset="100%" stop-color="#ff8a4a" stop-opacity=".9"/>
        </linearGradient>
        <radialGradient id="hudCore" cx="50%" cy="50%" r="50%">
          <stop offset="0%" stop-color="#ffd9c2" stop-opacity=".95"/>
          <stop offset="38%" stop-color="#ff2442" stop-opacity=".55"/>
          <stop offset="100%" stop-color="#ff2442" stop-opacity="0"/>
        </radialGradient>
        <filter id="hudBlur"><feGaussianBlur stdDeviation="1.6"/></filter>
      </defs>
      <circle r="148" class="hud-ring faint"/>
      <path d="${teeth}" class="hud-teeth"/>
      <g class="hud-ticks">${ticks.join('')}</g>
      <circle r="112" class="hud-ring"/>
      <circle r="92" class="hud-ring faint"/>
      <circle r="70" class="hud-ring dashed"/>
      <circle r="150" class="hud-arc" stroke="url(#hudArc)" stroke-dasharray="150 790" stroke-linecap="round">
        <animateTransform attributeName="transform" type="rotate" from="0" to="360" dur="26s" repeatCount="indefinite"/>
      </circle>
      <circle r="150" class="hud-arc thin" stroke="url(#hudArc)" stroke-dasharray="40 900">
        <animateTransform attributeName="transform" type="rotate" from="360" to="0" dur="17s" repeatCount="indefinite"/>
      </circle>
      <circle r="122" class="hud-arc thin" stroke="url(#hudArc)" stroke-dasharray="6 14">
        <animateTransform attributeName="transform" type="rotate" from="0" to="360" dur="60s" repeatCount="indefinite"/>
      </circle>
      <g class="hud-sweep">
        <path d="M0 0 L0 -150 A150 150 0 0 1 60 -137 Z" fill="url(#hudArc)" opacity=".12"/>
        <animateTransform attributeName="transform" type="rotate" from="0" to="360" dur="9s" repeatCount="indefinite"/>
      </g>
      <circle r="58" fill="url(#hudCore)" class="hud-core"/>
      <!-- the triangle badge from the Stark HUD, drawn in crimson -->
      <g class="hud-badge">
        <circle r="44" class="hud-badge-ring"/>
        <path d="M0 -30 L26 16 L-26 16 Z" class="hud-tri"/>
        <circle r="9" class="hud-tri-core"/>
        <circle r="16" class="hud-ring faint"/>
      </g>
      <!-- corner readouts around the ring, as in the reference interface -->
      <g class="hud-labels">
        <text x="0" y="-156" text-anchor="middle">JARVIS · CORE</text>
        <text x="-152" y="4" text-anchor="end">PWR</text>
        <text x="152" y="4" text-anchor="start">SYS</text>
        <text x="0" y="162" text-anchor="middle" id="hud-ring-time">--:--</text>
      </g>
    </svg>

    <div class="hud-panel hud-left-top">
      <div class="hud-head">SIGNAL ANALYSER <span id="hud-signal-state">STANDBY</span></div>
      <canvas id="hud-wave" width="240" height="56"></canvas>
      <div class="hud-metric-row"><span>TOTAL</span><b id="hud-signal-total">0.0</b><span>mB</span></div>
    </div>

    <div class="hud-panel hud-right-top hud-tall">
      <div class="hud-user">
        <div class="hud-avatar" id="hud-avatar">VJ</div>
        <div><div class="hud-name">VISHAL JARVIS <span>OS</span></div><div class="hud-user-sub">User: Vishal</div></div>
      </div>
      <div class="hud-rule"></div>
      <div class="hud-gauge"><span>CPU</span><div class="hud-bar"><i id="hud-cpu"></i></div><b id="hud-cpu-val">0%</b></div>
      <div class="hud-gauge"><span>MEM</span><div class="hud-bar"><i id="hud-mem"></i></div><b id="hud-mem-val">0%</b></div>
      <div class="hud-gauge"><span>BATT</span><div class="hud-bar"><i id="hud-batt"></i></div><b id="hud-batt-val">--</b></div>
      <div class="hud-gauge"><span>ACT</span><div class="hud-bar"><i id="hud-act"></i></div><b id="hud-act-val">0%</b></div>
      <div class="hud-rule"></div>
      <div class="hud-kv"><span>SESSION</span><b id="hud-uptime">00:00</b></div>
      <div class="hud-kv"><span>STATE</span><b id="hud-state">IDLE</b></div>
    </div>

    <div class="hud-panel hud-left-bottom">
      <div class="hud-head">NETWORK TRAFFIC</div>
      <div class="hud-spark"><span id="hud-up">▲ 0.0 mB</span><span id="hud-down">▼ 0.0 mB</span></div>
      <div class="hud-kv"><span>LINK</span><b id="hud-link">ONLINE</b></div>
    </div>

    <div class="hud-panel hud-right-bottom">
      <div class="hud-head">REQUEST FLOW</div>
      <div class="hud-bars" id="hud-bars"></div>
      <div class="hud-kv"><span>TURNS</span><b id="hud-turns">0</b></div>
    </div>

    <div class="hud-clock"><b id="hud-clock">--:--</b><span id="hud-date">--</span></div>
  </div>`;
}

/** How often the HUD redraws. 4 fps is enough for a gauge wall and cheap on battery. */
const FRAME_MS = 250;

export class HudStage {
  private timer: ReturnType<typeof setInterval> | undefined;
  private readonly started = Date.now();
  private wave: number[] = new Array(48).fill(0);
  private bars: number[] = new Array(20).fill(6);
  private lastSample: HudSample = {};
  private readonly canvas: HTMLCanvasElement | null;
  private readonly context: CanvasRenderingContext2D | null;

  constructor(private readonly root: HTMLElement, private readonly sample: () => HudSample) {
    this.canvas = root.querySelector<HTMLCanvasElement>('#hud-wave');
    this.context = this.canvas ? this.canvas.getContext('2d') : null;
    const bars = root.querySelector('#hud-bars');
    if (bars) for (let i = 0; i < this.bars.length; i++) bars.insertAdjacentHTML('beforeend', '<i></i>');
  }

  start(): void {
    if (this.timer) return;
    this.tick();
    this.timer = setInterval(() => this.tick(), FRAME_MS);
  }

  stop(): void {
    if (this.timer) clearInterval(this.timer);
    this.timer = undefined;
  }

  setActivity(label: string): void {
    const state = this.root.querySelector('#hud-state');
    if (state) state.textContent = label.toUpperCase();
    const wave = this.root.querySelector('#hud-signal-state');
    if (wave) wave.textContent = label.toUpperCase();
  }

  /** Speech level from the analyser, so the analyser strip reacts to the real voice. */
  setLevel(level: number): void {
    const value = Math.max(0, Math.min(1, level));
    this.wave.push(value);
    if (this.wave.length > 48) this.wave.shift();
    const total = this.root.querySelector('#hud-signal-total');
    if (total) total.textContent = (value * 42 + 3).toFixed(1);
  }

  private tick(): void {
    const now = new Date();
    const clock = this.root.querySelector('#hud-clock');
    if (clock) clock.textContent = now.toLocaleTimeString(undefined, { hour12: false });
    const ringTime = this.root.querySelector('#hud-ring-time');
    if (ringTime) ringTime.textContent = now.toLocaleTimeString(undefined, { hour: '2-digit', minute: '2-digit' });
    const date = this.root.querySelector('#hud-date');
    if (date) date.textContent = now.toLocaleDateString(undefined, { weekday: 'short', day: '2-digit', month: 'short' }).toUpperCase();

    const sample = this.sample();
    this.lastSample = sample;
    this.gauge('cpu', sample.cpu);
    this.gauge('mem', sample.memory);
    this.gauge('batt', sample.battery);
    this.gauge('act', sample.activity);
    const uptime = this.root.querySelector('#hud-uptime');
    if (uptime) {
      const seconds = Math.floor((Date.now() - this.started) / 1000) + (sample.uptime || 0);
      uptime.textContent = `${String(Math.floor(seconds / 60)).padStart(2, '0')}:${String(seconds % 60).padStart(2, '0')}`;
    }
    const link = this.root.querySelector('#hud-link');
    if (link) link.textContent = navigator.onLine ? 'ONLINE' : 'OFFLINE';
    const up = this.root.querySelector('#hud-up');
    const down = this.root.querySelector('#hud-down');
    if (up) up.textContent = `▲ ${((sample.activity || 0) * 2.4 + 0.4).toFixed(1)} mB`;
    if (down) down.textContent = `▼ ${((sample.cpu || 0) * 1.7 + 0.6).toFixed(1)} mB`;
    this.drawWave();
    this.drawBars(sample);
  }

  private gauge(id: string, value?: number): void {
    const bar = this.root.querySelector<HTMLElement>(`#hud-${id}`);
    const label = this.root.querySelector(`#hud-${id}-val`);
    if (value == null) {
      if (bar) bar.style.width = '0%';
      if (label) label.textContent = '--';
      return;
    }
    const percent = Math.max(0, Math.min(100, value));
    if (bar) bar.style.width = `${percent.toFixed(0)}%`;
    if (label) label.textContent = `${percent.toFixed(0)}%`;
  }

  private drawWave(): void {
    const context = this.context;
    const canvas = this.canvas;
    if (!context || !canvas) return;
    const { width, height } = canvas;
    context.clearRect(0, 0, width, height);
    context.strokeStyle = 'rgba(255,36,66,.22)';
    context.lineWidth = 1;
    for (let y = 8; y < height; y += 12) {
      context.beginPath(); context.moveTo(0, y); context.lineTo(width, y); context.stroke();
    }
    context.beginPath();
    this.wave.forEach((value, index) => {
      const x = (index / (this.wave.length - 1)) * width;
      const y = height - 6 - value * (height - 12) * (0.4 + Math.sin(index * 0.6) * 0.35);
      if (index === 0) context.moveTo(x, y);
      else context.lineTo(x, y);
    });
    context.strokeStyle = '#ff2442';
    context.shadowColor = '#ff2442';
    context.shadowBlur = 8;
    context.lineWidth = 1.6;
    context.stroke();
    context.shadowBlur = 0;
  }

  private drawBars(sample: HudSample): void {
    const value = Math.max(0.05, Math.min(1, (sample.activity || 0) / 100));
    this.bars.push(value);
    if (this.bars.length > 20) this.bars.shift();
    const nodes = this.root.querySelectorAll<HTMLElement>('#hud-bars i');
    nodes.forEach((node, index) => {
      const height = 4 + this.bars[index] * 26;
      node.style.height = `${height.toFixed(0)}px`;
      node.style.opacity = String(0.35 + this.bars[index] * 0.65);
    });
  }

  /** Turns the conversation size into the TURNS readout. */
  setTurns(count: number): void {
    const node = this.root.querySelector('#hud-turns');
    if (node) node.textContent = String(count);
  }

  sampleNow(): HudSample { return this.lastSample; }
}

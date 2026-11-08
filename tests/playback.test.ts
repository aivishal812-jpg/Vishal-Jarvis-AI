/**
 * Regression tests for the P0 premature voice cut-off.
 * A fake Web Audio graph drives the real GaplessPcmPlayer: no arbitrary
 * delays, no device, deterministic chunk/chunk-end/turnComplete ordering.
 */
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { GaplessPcmPlayer } from '../src/lib/audio';

class FakeBuffer { constructor(public duration: number, private data: Float32Array) {} getChannelData() { return this.data; } }
class FakeSource {
  buffer: FakeBuffer | null = null; onended: (() => void) | null = null; startedAt = -1; stopped = false;
  connect() {}
  start(when: number) { this.startedAt = when; }
  stop() { this.stopped = true; }
  finish() { this.onended?.(); }
}
class FakeContext {
  currentTime = 0; state = 'running'; sources: FakeSource[] = []; resumeCalls = 0;
  createBuffer(_c: number, length: number, rate: number) { return new FakeBuffer(length / rate, new Float32Array(length)); }
  createBufferSource() { const s = new FakeSource(); this.sources.push(s); return s; }
  async resume() { this.resumeCalls += 1; this.state = 'running'; }
}
const chunk = (seconds: number) => new Float32Array(Math.round(seconds * 24000)).fill(0.25);
function rig() {
  const ctx = new FakeContext(); const events: string[] = [];
  const player = new GaplessPcmPlayer(ctx as never, {}, {
    onTurnStarted: () => events.push('started'),
    onTurnFinished: () => events.push('finished'),
  });
  return { ctx, events, player };
}

// TEST 2/5: long multi-chunk, multi-sentence response is never cut at chunk 1.
{
  const { ctx, events, player } = rig();
  for (let i = 0; i < 10; i++) player.enqueue(chunk(0.5));
  assert.equal(ctx.sources.length, 10, 'every chunk must be scheduled');
  for (let i = 1; i < 10; i++) assert.ok(Math.abs(ctx.sources[i].startedAt - (0.03 + i * 0.5)) < 1e-6, 'gapless in-order schedule');
  ctx.sources.forEach(s => s.finish());
  assert.deepEqual(events, ['started'], 'chunk endings alone must not finish the turn');
  player.markTurnComplete();
  assert.deepEqual(events, ['started', 'finished'], 'turn finishes only after turnComplete AND drain');
}
// TEST: turnComplete arriving while audio still scheduled does not cut it.
{
  const { ctx, events, player } = rig();
  player.enqueue(chunk(1)); player.enqueue(chunk(1)); player.markTurnComplete();
  ctx.sources[0].finish();
  assert.deepEqual(events, ['started'], 'remaining scheduled audio must keep playing');
  ctx.sources[1].finish();
  assert.deepEqual(events, ['started', 'finished']);
}
// TEST 4/11: consecutive turns do not interrupt each other; turn 2 starts clean.
{
  const { ctx, events, player } = rig();
  player.enqueue(chunk(0.2)); player.markTurnComplete(); ctx.sources[0].finish();
  player.enqueue(chunk(0.2)); player.markTurnComplete(); ctx.sources[1].finish();
  assert.deepEqual(events, ['started', 'finished', 'started', 'finished']);
}
// Interruption: only explicit interrupt stops playback; stale ends are ignored.
{
  const { ctx, events, player } = rig();
  player.enqueue(chunk(1)); player.enqueue(chunk(1));
  player.interrupt();
  assert.ok(ctx.sources.every(s => s.stopped), 'interrupt stops scheduled sources');
  ctx.sources.forEach(s => s.finish()); // stale onended from the dead turn
  player.enqueue(chunk(0.2)); player.markTurnComplete(); ctx.sources[2].finish();
  assert.deepEqual(events, ['started', 'started', 'finished'], 'stale sources must not finish the new turn');
}
// Suspended context (background / focus loss): resume is requested, audio is kept.
{
  const { ctx, player } = rig();
  ctx.state = 'suspended';
  player.enqueue(chunk(0.5));
  assert.ok(ctx.resumeCalls >= 1, 'suspended context must be resumed');
  assert.equal(ctx.sources.length, 1, 'chunk must not be discarded while suspended');
  player.markTurnComplete(); ctx.sources[0].finish();
  assert.equal(player.hasPendingAudio, false);
}
// Source-level guards wired into the session / transport / native paths.
const audio = readFileSync('src/lib/audio.ts', 'utf8');
const transport = readFileSync('src/mobile/LiveTransport.ts', 'utf8');
const ui = readFileSync('src/mobile/main.ts', 'utf8');
const activity = readFileSync('android/app/src/main/java/com/myraa/mobile/MainActivity.java', 'utf8');
assert.match(audio, /const shouldForward = this\.updateVoiceActivity\(floats\);[\s\S]*?if \(shouldForward/, 'native frames must respect VAD (speaker echo caused server interruption)');
assert.doesNotMatch(audio, /activeSources/, 'legacy per-chunk source list must stay removed');
assert.match(audio, /onstatechange/, 'output context suspension must self-recover');
assert.match(audio, /deferredDisconnect/, 'session close must drain queued speech first');
assert.match(audio, /preContextChunks/, 'chunks arriving before the graph exists must be preserved');
assert.match(audio, /bargeInSustainMs = 350/, 'echo bursts must not interrupt playback; barge-in must be sustained');
assert.match(audio, /usingNativeCapture && !this\.nativeSpeechHint/, 'native echo without a speech verdict must never interrupt or be forwarded');
assert.match(transport, /receiveChain/, 'Live messages must stay in arrival order');
assert.doesNotMatch(ui, /document\.hidden\) \{[^}]*stopSpeech/, 'minimising must not stop TTS');
assert.doesNotMatch(activity, /if\(pendingLiveId==null&&!keep\)cancelLive\(\);if\(tts!=null\)tts\.stop\(\);/, 'onPause must not stop TTS');
const capture = readFileSync('android/app/src/main/java/com/myraa/mobile/AudioCaptureEngine.java', 'utf8');
assert.match(capture, /MODE_IN_COMMUNICATION/, 'hardware echo cancellation requires communication mode during Live capture');
assert.match(capture, /setMode\(previousMode\)/, 'previous audio mode must be restored after capture');
console.log('Playback regression tests passed: no premature termination, ordered gapless chunks, turn-aware completion.');

/** Lightweight software speech enhancement used only when native AudioRecord is unavailable. */
export class BrowserSpeechEnhancer {
  private noise = 0.02;
  private envelope = 0.08;
  private hp = 0;
  private speaking = false;
  private hold = 0;
  private calibrated = 0;
  process(input: Float32Array): { output: Float32Array; speech: boolean } {
    const out = new Float32Array(input.length);
    let energy = 0;
    let zc = 0;
    for (let i = 0; i < input.length; i++) {
      const x = input[i];
      this.hp += 0.035 * (x - this.hp);
      const y = x - this.hp;
      out[i] = y;
      energy += y * y;
      if (i > 0 && (y >= 0) !== (out[i - 1] >= 0)) zc++;
    }
    const rms = Math.sqrt(energy / Math.max(1, input.length));
    const zcr = zc / Math.max(1, input.length);
    if (this.calibrated < 8) {
      this.noise = this.noise * 0.7 + rms * 0.3;
      this.calibrated++;
    }
    const threshold = Math.max(0.01, this.noise * 3.4);
    const voiced = rms >= threshold && zcr > 0.01 && zcr < 0.35;
    if (voiced) {
      this.hold = 14;
      this.speaking = true;
      this.envelope = this.envelope * 0.85 + rms * 0.15;
    } else if (this.hold > 0) {
      this.hold--;
      this.speaking = true;
    } else {
      this.speaking = false;
      this.noise = this.noise * 0.95 + rms * 0.05;
    }
    const gate = this.speaking ? 1 : Math.max(0.08, Math.min(1, rms / Math.max(1e-4, threshold)));
    const target = 0.12;
    const agc = Math.max(0.4, Math.min(6, target / Math.max(0.02, this.envelope)));
    for (let i = 0; i < out.length; i++) {
      let y = out[i] * gate * agc;
      if (y > 0.97) y = 0.97;
      if (y < -0.97) y = -0.97;
      out[i] = y;
    }
    return { output: out, speech: this.speaking };
  }
}

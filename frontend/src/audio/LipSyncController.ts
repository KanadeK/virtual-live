import type { CharacterController } from "../character/CharacterController";
export interface LipSyncController {
  start(): void;
  update(amplitude: number): void;
  stop(): void;
}
/** Browser speech has no PCM access. Estimate syllable envelopes, anchored to boundary callbacks. */
export class EstimatedLipSync implements LipSyncController {
  private active = false;
  private startAt = 0;
  private offset = 0;
  private raf = 0;
  constructor(
    private character: CharacterController,
    private text: string,
    private rate: number,
  ) {}
  start() {
    this.active = true;
    this.startAt = performance.now();
    this.offset = 0;
    this.raf = requestAnimationFrame(this.frame);
  }
  boundary(charIndex: number) {
    this.offset = charIndex;
    this.startAt = performance.now();
  }
  update(amplitude: number) {
    this.character.setMouth(
      amplitude < 0.13
        ? 0
        : amplitude < 0.36
          ? 1
          : amplitude < 0.67
            ? 2
            : amplitude < 0.87
              ? 3
              : 4,
    );
  }
  stop() {
    this.active = false;
    cancelAnimationFrame(this.raf);
    this.character.setMouth(0);
  }
  private frame = () => {
    if (!this.active) return;
    const elapsed = performance.now() - this.startAt;
    const period = 190 / this.rate;
    const index = Math.min(
      this.text.length - 1,
      this.offset + Math.floor(elapsed / (210 / this.rate)),
    );
    const punctuation = /[，。！？、,.!?\s]/.test(this.text[index] || "");
    const envelope = punctuation
      ? 0
      : Math.pow(Math.max(0, Math.sin((elapsed / period) * Math.PI)), 0.8);
    this.update(envelope);
    this.raf = requestAnimationFrame(this.frame);
  };
}
/** Future decoded audio path: connect a MediaElementAudioSourceNode to analyser + destination. */
export class AudioAnalyserLipSync implements LipSyncController {
  private raf = 0;
  private active = false;
  private samples: Uint8Array<ArrayBuffer>;
  private smoothed=0;
  constructor(
    private analyser: AnalyserNode,
    private character: CharacterController,
  ) {
    this.samples = new Uint8Array(analyser.fftSize);
  }
  start() {
    this.active = true;
    this.frame();
  }
  update(amplitude: number) {
    this.character.setMouth(
      amplitude < 0.014
        ? 0
        : amplitude < 0.035
          ? 1
          : amplitude < 0.07
            ? 2
            : amplitude < 0.13
              ? 3
              : 4,
    );
  }
  stop() {
    this.active = false;
    cancelAnimationFrame(this.raf);
    this.character.setMouth(0);
  }
  private frame = () => {
    if (!this.active) return;
    this.analyser.getByteTimeDomainData(this.samples);
    let energy = 0;
    for (const sample of this.samples) energy += ((sample - 128) / 128) ** 2;
    this.smoothed=this.smoothed*.45+Math.sqrt(energy / this.samples.length)*.55;
    this.update(this.smoothed);
    this.raf = requestAnimationFrame(this.frame);
  };
}

import type { Action, Emotion } from "../events/protocol";
const STAGE_CENTER = 540;
const isWalk = (action: string) => action === 'walk' || action === 'walk_left' || action === 'walk_right';
export type CharacterState =
  | "IDLE"
  | "WALKING"
  | "TALKING"
  | "WAVING"
  | "CELEBRATING"
  | "HAPPY"
  | "SURPRISED"
  | "THINKING"
  | "SAD";
export interface CharacterPose {
  x: number;
  state: CharacterState;
  action: string;
  emotion: string;
  mouth: string;
  speaking: string;
  blinking: boolean;
  direction: number;
  spriteFrame?: string;
}
export interface CharacterRenderer {
  readonly frameId: string;
  render(pose: CharacterPose, now: number, following?: boolean): void;
}
const STATES: Partial<Record<Action, CharacterState>> = {
  idle: "IDLE",
  walk: "IDLE",
  walk_left: "IDLE",
  walk_right: "IDLE",
  talk: "TALKING",
  wave: "WAVING",
  celebrate: "CELEBRATING",
  happy: "HAPPY",
  surprised: "SURPRISED",
  think: "THINKING",
  sad: "SAD",
};
/** Explicit priority/expiry state machine. Blink and mouth are orthogonal face layers. */
export class CharacterController {
  x = 540;
  state: CharacterState = "IDLE";
  fps = 60;
  private last = performance.now();
  private until = 0;
  private priority = 0;
  private nextChoice = performance.now() + 2400;
  private nextBlink = performance.now() + 1700;
  private blinkUntil = 0;
  private speaking = false;
  private emotionUntil = 0;
  private direction = 1;
  private remote?: CharacterPose;
  private remoteUntil = 0;
  constructor(
    private actor: HTMLElement,
    private bubble: HTMLElement,
    private shadow: HTMLElement,
    private renderer?: CharacterRenderer,
  ) {
    requestAnimationFrame(this.frame);
  }
  request(action: Action, priority = 20, duration = 2300): boolean {
    // Historical walking commands remain protocol-compatible; the desk actor stays seated in place.
    if (isWalk(action)) action = 'idle';
    if (action === "blink") {
      this.blinkUntil = performance.now() + 160;
      return true;
    }
    if (this.speaking && priority < 40) return false;
    if (priority < this.priority && performance.now() < this.until)
      return false;
    this.priority = priority;
    this.until = performance.now() + duration;
    this.state = STATES[action] || "IDLE";
    if (action !== "talk" && !this.speaking) this.setMouth(0);
    this.actor.dataset.action = action;
    return true;
  }
  setEmotion(emotion: Emotion, duration = 4000) {
    this.actor.dataset.emotion = emotion;
    this.emotionUntil = performance.now() + duration;
  }
  beginSpeech(action: Action, emotion: Emotion) {
    this.speaking = true;
    this.actor.dataset.speaking = "true";
    this.setEmotion(emotion, Infinity);
    this.request(
      action === "celebrate"
        ? "celebrate"
        : action === "wave"
          ? "wave"
          : action === "happy"
            ? "happy"
            : "talk",
      action === "celebrate" ? 40 : 30,
      action === "celebrate"
        ? 2400
        : action === "wave"
          ? 2200
          : action === "happy"
            ? 1700
            : Infinity,
    );
  }
  endSpeech() {
    this.speaking = false;
    this.priority = 0;
    this.until = 0;
    this.actor.dataset.speaking = "false";
    this.setMouth(0);
    this.setEmotion("neutral", 0);
    this.request("idle", 0, 1600);
    this.nextChoice = performance.now() + 1800;
  }
  pose(): CharacterPose {
    return {
      x: this.x,
      state: this.state,
      action: this.actor.dataset.action || "idle",
      emotion: this.actor.dataset.emotion || "neutral",
      mouth: this.actor.dataset.mouth || "0",
      speaking: this.actor.dataset.speaking || "false",
      blinking: this.actor.classList.contains("blinking"),
      direction: this.direction,
      spriteFrame: this.renderer?.frameId,
    };
  }
  follow(pose: CharacterPose) {
    // Old tabs may still broadcast walking poses. Keep face and speech in sync without moving the actor.
    this.remote = { ...pose, x: STAGE_CENTER, direction: 1,
      state: pose.state === 'WALKING' ? 'IDLE' : pose.state,
      action: isWalk(pose.action) ? 'idle' : pose.action,
      spriteFrame: pose.spriteFrame?.includes('walk') ? undefined : pose.spriteFrame };
    this.remoteUntil = performance.now() + 1200;
  }
  becomeOwner() {
    this.remoteUntil = 0;
  }
  setMouth(index: number) {
    if (performance.now() < this.remoteUntil) return;
    this.actor.dataset.mouth = String(Math.max(0, Math.min(4, index)));
  }
  private frame = (now: number) => {
    const dt = Math.min((now - this.last) / 1000, 0.06);
    this.last = now;
    if (dt > 0) this.fps = this.fps * 0.95 + (1 / dt) * 0.05;
    if (this.remote && now < this.remoteUntil) {
      const p = this.remote;
      this.x = STAGE_CENTER;
      this.state = p.state;
      this.direction = p.direction;
      this.actor.dataset.action = p.action;
      this.actor.dataset.emotion = p.emotion;
      this.actor.dataset.mouth = p.mouth;
      this.actor.dataset.speaking = p.speaking;
      this.actor.classList.toggle("blinking", p.blinking);
      this.position(this.direction);
      this.renderer?.render(p, now, true);
      requestAnimationFrame(this.frame);
      return;
    }
    if (now >= this.until && this.priority > 0) {
      this.priority = 0;
      this.request(
        this.speaking ? "talk" : "idle",
        this.speaking ? 30 : 0,
        this.speaking ? Infinity : 1200,
      );
      this.nextChoice = now + 2000;
    }
    if (!this.speaking && this.priority === 0 && now > this.nextChoice) {
      const roll = Math.random();
      if (roll < 0.72) {
        this.request("idle", 0, 4000);
      } else
        this.request(
          roll < 0.84 ? "think" : roll < 0.96 ? "wave" : "happy",
          10,
          2300,
        );
      this.nextChoice = now + 4000 + Math.random() * 3500;
    }
    this.x = STAGE_CENTER;
    this.direction = 1;
    this.position(1);
    if (now > this.nextBlink) {
      this.blinkUntil = now + 145;
      this.nextBlink = now + 2400 + Math.random() * 3800;
    }
    this.actor.classList.toggle("blinking", now < this.blinkUntil);
    if (now > this.emotionUntil && !this.speaking)
      this.actor.dataset.emotion = "neutral";
    if (this.state === "TALKING" && !this.speaking)
      this.setMouth(Math.sin(now / 120) > 0.2 ? 2 : 0);
    this.renderer?.render(this.pose(), now);
    requestAnimationFrame(this.frame);
  };
  private position(dir: number) {
    this.actor.dataset.facing=this.state==='WALKING'?(dir<0?'left':'right'):'front';
    this.actor.style.left = `${this.x}px`;
    this.actor.style.transform = `translateX(-50%) translateX(var(--body-offset, 0px)) scaleX(${dir})`;
    this.shadow.style.left = `${this.x}px`;
    // Keep the entire balloon inside the 1080px canvas. Tail follows actor independently.
    const bx = Math.max(310, Math.min(770, this.x));
    this.bubble.style.left = `${bx}px`;
    this.bubble.style.setProperty("--tail", `${this.x - bx}px`);
  }
}

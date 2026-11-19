import type { CharacterPose, CharacterRenderer } from "./CharacterController";
import { CHARACTER_PROFILE } from "./profile";
interface Frame {
  atlas?: string;
  bodyAnchorX?: number;
  index: number;
  x: number;
  y: number;
  mouth: { x: number; y: number; width: number; height: number; angle: number };
}
interface Clip {
  frames: string[];
  frameMs: number;
  loop: boolean;
}
interface Manifest {
  frameWidth: number;
  frameHeight: number;
  baseline: number;
  bodyAnchorX: number;
  atlas: string;
  additionalAtlases?: Record<string,string>;
  mouths: { atlas: string; width: number; height: number; count: number };
  frames: Record<string, Frame>;
  clips: Record<string, Clip>;
}
/** Draws real generated PNG poses; the mouth is a separate generated RGBA patch. */
export class PngCharacterRenderer implements CharacterRenderer {
  frameId = "idle-0";
  private canvas: HTMLCanvasElement;
  private ctx: CanvasRenderingContext2D;
  private clip = "idle";
  private clipStart = 0;
  private reduced = matchMedia('(prefers-reduced-motion: reduce)');
  private constructor(
    private actor: HTMLElement,
    private manifest: Manifest,
    private atlas: HTMLImageElement,
    private mouths: HTMLImageElement,
    private extraAtlases: Record<string,HTMLImageElement>,
  ) {
    this.canvas = document.createElement("canvas");
    this.canvas.className = "character-canvas";
    this.canvas.width = manifest.frameWidth;
    this.canvas.height = manifest.frameHeight;
    this.canvas.setAttribute("role", "img");
    this.canvas.setAttribute("aria-label", CHARACTER_PROFILE.name);
    this.ctx = this.canvas.getContext("2d", { alpha: true })!;
    this.ctx.imageSmoothingEnabled = true;
    this.ctx.imageSmoothingQuality = "high";
    actor.replaceChildren(this.canvas);
    actor.style.transformOrigin=`${manifest.bodyAnchorX/manifest.frameWidth*100}% 50%`;
    actor.dataset.renderer = "png-sequence";
    this.drawFrame("idle-0", 0);
  }
  static async create(actor: HTMLElement, manifestUrl: string) {
    const response = await fetch(manifestUrl);
    if (!response.ok) throw new Error("PNG 动作清单加载失败");
    const manifest = (await response.json()) as Manifest;
    const base = new URL(".", new URL(manifestUrl, location.href));
    const image = (file: string) =>
      new Promise<HTMLImageElement>((resolve, reject) => {
        const img = new Image();
        img.onload = () => resolve(img);
        img.onerror = () => reject(new Error(`PNG 素材加载失败：${file}`));
        img.src = new URL(file, base).href;
      });
    const [atlas, mouths] = await Promise.all([
      image(manifest.atlas),
      image(manifest.mouths.atlas),
    ]);
    const extras=Object.fromEntries(await Promise.all(Object.entries(manifest.additionalAtlases||{}).map(async([id,file])=>[id,await image(file)])));
    return new PngCharacterRenderer(actor, manifest, atlas, mouths, extras);
  }
  render(pose: CharacterPose, now: number, following = false) {
    let key = pose.action;
    if(key==='walk_left'||key==='walk_right')key='walk';
    if ((key === "idle" || key === "talk") && pose.emotion !== "neutral")
      key = pose.emotion === "happy" ? "happy" : pose.emotion;
    if (!this.manifest.clips[key]) key = "idle";
    // Blinks use a generated closed-eye pose; don't interrupt waving or a walking stride.
    const blinking = pose.blinking && (key === "idle" || key === "talk");
    const nextClip = blinking ? "blink" : key;
    if (nextClip !== this.clip) {
      this.clip = nextClip;
      this.clipStart = now;
    }
    const clip = this.manifest.clips[this.clip];
    const elapsed = Math.max(0, now - this.clipStart);
    const reduced = this.reduced.matches;
    let frame = clip.frames[reduced ? 0 : Math.floor(elapsed / clip.frameMs) % clip.frames.length];
    if (following && !reduced && pose.spriteFrame && this.manifest.frames[pose.spriteFrame])
      frame = pose.spriteFrame;
    this.frameId = frame;
    const anchor=this.manifest.frames[frame].bodyAnchorX??this.manifest.bodyAnchorX;
    this.actor.style.transformOrigin=`${anchor/this.manifest.frameWidth*100}% 50%`;
    this.actor.style.setProperty('--body-offset',`${(this.manifest.bodyAnchorX-anchor)*this.actor.clientWidth/this.manifest.frameWidth}px`);
    this.actor.dataset.frame = frame;
    this.actor.dataset.clip = this.clip;
    this.canvas.dataset.frame = frame;
    const ctx = this.ctx,
      m = this.manifest;
    ctx.clearRect(0, 0, m.frameWidth, m.frameHeight);
    const cycle = elapsed / (clip.frameMs * clip.frames.length);
    const breath = reduced ? 0 : Math.sin(now / 1400) * 0.005;
    const walking = pose.action === "walk" || pose.action === "walk_left" || pose.action === "walk_right",
      jumping = pose.action === "happy" || pose.action === "celebrate";
    const dy = reduced ? 0 : jumping
      ? -58 * Math.pow(Math.max(0, Math.sin(cycle * Math.PI * 2)), 2)
      : walking
        ? -5 * Math.abs(Math.sin(cycle * Math.PI * 4))
        : -2 * Math.sin(now / 1400);
    const sway =
      reduced ? 0 : pose.speaking === "true"
        ? Math.sin(now / 750) * 0.008
        : walking
          ? Math.sin(cycle * Math.PI * 2) * 0.006
          : Math.sin(now / 2200) * 0.003;
    ctx.save();
    ctx.translate(anchor, m.baseline + dy);
    ctx.rotate(sway);
    ctx.scale(1, 1 + breath);
    ctx.translate(-anchor, -m.baseline);
    const mouth = Number(pose.mouth) || 0;
    // Registered poses are drawn once. Whole-body dissolves create two visible silhouettes at this scale.
    this.drawFrame(frame, 0);
    this.drawMouth(frame, mouth);
    ctx.restore();
  }
  private drawFrame(id: string, mouth: number) {
    const m = this.manifest,
      f = m.frames[id],
      ctx = this.ctx;
    if (!f) return;
    ctx.drawImage(
      f.atlas ? this.extraAtlases[f.atlas] : this.atlas,
      f.x,
      f.y,
      m.frameWidth,
      m.frameHeight,
      0,
      0,
      m.frameWidth,
      m.frameHeight,
    );
    if (mouth > 0) this.drawMouth(id, mouth);
  }
  private drawMouth(id: string, mouth: number) {
    const m = this.manifest,
      f = m.frames[id],
      ctx = this.ctx;
    if (!f || mouth < 1) return;
    const index = Math.min(m.mouths.count, Math.max(1, mouth)) - 1,
      patch = f.mouth;
    ctx.save();
    ctx.translate(patch.x, patch.y);
    ctx.rotate((patch.angle * Math.PI) / 180);
    ctx.drawImage(
      this.mouths,
      index * m.mouths.width,
      0,
      m.mouths.width,
      m.mouths.height,
      -patch.width / 2,
      -patch.height / 2,
      patch.width,
      patch.height,
    );
    ctx.restore();
  }
}

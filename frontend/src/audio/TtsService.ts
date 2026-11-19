import type { AudioSettings, SpeechTask } from "../events/protocol";
export interface TtsHooks {
  onStart(): void;
  /** Only an actual engine start, never the text/autoplay fallback timer. */
  onAudibleStart?(kind: "buffer" | "browser"): void;
  onAudibleEnd?(): void;
  onBoundary(index: number): void;
  onAnalyser?(analyser: AnalyserNode): void;
  onProgress?(seconds: number, duration: number): void;
}
export interface TtsService {
  speak(text: string, settings: AudioSettings, hooks: TtsHooks): Promise<void>;
  cancel(): void;
}
export class BrowserSpeechTts implements TtsService {
  private voice?: SpeechSynthesisVoice;
  private finish?: () => void;
  private timer = 0;
  private utterance?: SpeechSynthesisUtterance;
  constructor(private notice: (text: string) => void) {
    if (!("speechSynthesis" in window)) {
      queueMicrotask(() => notice("浏览器不支持语音，已使用文字和动作模式"));
      return;
    }
    queueMicrotask(this.loadVoices);
    speechSynthesis.addEventListener("voiceschanged", this.loadVoices);
  }
  private loadVoices = () => {
    const voices = speechSynthesis
      .getVoices()
      .filter((v) => /^zh([_-]|$)/i.test(v.lang));
    this.voice =
      voices.find((v) =>
        /ting|tingting|xiaoxiao|晓晓|siri|mei|lili|female/i.test(v.name),
      ) ||
      voices.find((v) => /zh[-_]CN/i.test(v.lang)) ||
      voices[0];
    this.notice(
      this.voice
        ? `中文音色：${this.voice.name} · 估算嘴型`
        : "没有可用中文声音，请在系统安装普通话音色；当前使用文字和动作",
    );
  };
  available() {
    return !!this.voice;
  }
  speak(text: string, settings: AudioSettings, hooks: TtsHooks): Promise<void> {
    this.cancel();
    if (!settings.enabled || !this.voice || !("speechSynthesis" in window)) {
      hooks.onStart();
      return new Promise((resolve) => {
        this.finish = resolve;
        this.timer = window.setTimeout(
          () => {
            this.finish = undefined;
            resolve();
          },
          Math.max(2000, (text.length * 210) / settings.rate),
        );
      });
    }
    return new Promise((resolve) => {
      let textStarted = false, engineStarted = false, engineStopped = false,
        finished = false;
      const finish = () => {
        if (finished) return;
        finished = true;
        if(engineStarted)hooks.onAudibleEnd?.();
        clearTimeout(this.timer);
        if (this.utterance === u) {
          this.finish = undefined;
          this.utterance = undefined;
        }
        resolve();
      };
      this.finish = finish;
      const u = new SpeechSynthesisUtterance(text);
      this.utterance = u;
      const current = () => !finished && this.utterance === u;
      const startText = () => {
        if (!current() || textStarted) return;
        textStarted = true;
        hooks.onStart();
      };
      u.lang = this.voice!.lang;
      u.voice = this.voice!;
      u.rate = settings.rate;
      u.pitch = settings.pitch;
      u.onstart = () => {
        // Text fallback can precede the actual engine. It must not consume this start.
        if (!current() || engineStarted || engineStopped) return;
        engineStarted = true;
        startText();
        hooks.onAudibleStart?.("browser");
      };
      u.onboundary = (e) => { if (current() && engineStarted && !engineStopped) hooks.onBoundary(e.charIndex); };
      u.onend = () => { if (current()) finish(); };
      u.onerror = (e) => {
        if (!current()) return;
        engineStopped = true;
        if (e.error === "canceled" || e.error === "interrupted") {
          finish();
          return;
        }
        this.notice("语音播放失败，已继续文字和动作模式");
        if (engineStarted) { finish(); return; }
        startText();
        clearTimeout(this.timer);
        this.timer = window.setTimeout(
          finish,
          Math.max(1500, (text.length * 150) / settings.rate),
        );
      };
      this.timer = window.setTimeout(
        () => {
          if (!current()) return;
          speechSynthesis.cancel();
          this.notice("语音超时，已恢复播报队列");
          finish();
        },
        Math.max(15000, (text.length * 600) / settings.rate + 3000),
      );
      speechSynthesis.resume();
      speechSynthesis.speak(u);
      // Some engines never emit start/error under autoplay restrictions.
      window.setTimeout(() => {
        startText();
      }, 900);
    });
  }
  cancel() {
    clearTimeout(this.timer);
    const finish = this.finish;
    this.finish = undefined;
    this.utterance = undefined;
    finish?.();
    if ("speechSynthesis" in window) speechSynthesis.cancel();
  }
}

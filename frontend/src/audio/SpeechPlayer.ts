import { BrowserSpeechTts } from "./TtsService";
import { DoubaoTts } from "./DoubaoTts";
import { EstimatedLipSync, AudioAnalyserLipSync, type LipSyncController } from "./LipSyncController";
import type { CharacterController } from "../character/CharacterController";
import type { AudioSettings, SpeechTask } from "../events/protocol";
import { api } from "../events/api";
import { onAudioOwnership, ownsAudio } from './ownership';
export interface SpeechPlaybackObservation { taskId: string; state: "started" | "stopped" | "finished" | "text" | "cancelled"; kind?: "buffer" | "browser"; }
export class SpeechPlayer {
  private observedTask = "";
  settings: AudioSettings = { enabled: true, rate: 1.05, pitch: 1.25, voice: "" };
  unlocked = false;
  private tts: BrowserSpeechTts;
  private doubao: DoubaoTts;
  private notice: (text:string)=>void;
  private token = 0;
  private active = "";
  private lip?: LipSyncController;
  private typing = 0;
  private hide = 0;
  private seen = new Set<string>();
  private audioUsed = false;
  constructor(
    private character: CharacterController,
    private bubble: HTMLElement,
    private text: HTMLElement,
    private isLive: boolean,
    private clientId: () => string,
    notice: (text: string) => void,
    private observe?: (observation: SpeechPlaybackObservation) => void,
  ) {
    this.notice=notice;
    this.tts = new BrowserSpeechTts(notice);
    this.doubao = new DoubaoTts(notice);
    const unlisten = onAudioOwnership(owns => {
      if (!owns && this.isLive && this.active) this.cancel();
    });
    window.addEventListener('pagehide', () => { unlisten(); this.cancel(); }, { once: true });
  }
  unlock() {
    this.unlocked = true;
    this.doubao.unlock();
    if ("speechSynthesis" in window) {
      speechSynthesis.resume();
      const u = new SpeechSynthesisUtterance("");
      speechSynthesis.speak(u);
    }
  }
  applySettings(settings: AudioSettings) {
    this.settings = settings;
    if (!settings.enabled && this.audioUsed) {this.tts.cancel();this.doubao.cancel();}
  }
  async play(task: SpeechTask) {
    const owns = this.isLive && ownsAudio() !== false && task.ownerId === this.clientId();
    // Non-owner /live tabs behave exactly like a silent preview.
    const key = `${task.taskId}:${owns}`;
    if (this.active === key) return;
    if (this.seen.has(key)) {
      if (owns)
        void api("/speech/completed", {
          taskId: task.taskId,
          clientId: this.clientId(),
        }).catch(() => {});
      return;
    }
    this.cancel();
    this.active = key;
    const token = ++this.token;
    this.observedTask = task.taskId;
    this.character.setEmotion(task.emotion);
    clearTimeout(this.hide);
    this.bubble.hidden = false;
    this.bubble.setAttribute('aria-hidden', 'false');
    this.bubble.classList.add("visible");
    this.text.textContent = "…";
    const { rate } = this.settings;
    let started = false, audible = false;
    const onStart = () => {
      if (started || token !== this.token) return;
      started = true;
      const start = performance.now();
      this.typing = window.setInterval(() => {
        const end = Math.min(
          task.text.length,
          Math.max(1, Math.floor((performance.now() - start) / (95 / rate))),
        );
        this.text.textContent = task.text.slice(Math.max(0, end - 56), end);
      }, 45);
    };
    this.audioUsed = owns && this.unlocked && this.settings.enabled;
    const hooks={
      onStart,
      onAudibleStart:(kind:"buffer"|"browser")=>{
        if(token!==this.token||!this.audioUsed)return;
        audible=true;this.character.beginSpeech(task.action,task.emotion);
        if(kind==='browser'){this.lip=new EstimatedLipSync(this.character,task.text,rate);this.lip.start();}
        this.observe?.({taskId:task.taskId,state:'started',kind});
      },
      onAudibleEnd:()=>{if(token!==this.token)return;this.lip?.stop();this.character.endSpeech();this.observe?.({taskId:task.taskId,state:'stopped'});},
      onBoundary:(index:number)=>{if(this.lip instanceof EstimatedLipSync)this.lip.boundary(index);},
      onAnalyser:(analyser:AnalyserNode)=>{if(token!==this.token)return;this.lip?.stop();this.lip=new AudioAnalyserLipSync(analyser,this.character);this.lip.start();clearInterval(this.typing);},
      onProgress:(seconds:number,duration:number)=>{if(token!==this.token)return;const end=Math.min(task.text.length,Math.max(1,Math.ceil(task.text.length*seconds/Math.max(.1,duration))));this.text.textContent=task.text.slice(Math.max(0,end-56),end);}
    };
    if(this.audioUsed&&task.ttsProvider==='doubao'){
      try{await this.doubao.speak(task.text,this.settings,hooks,task,this.clientId());}
      catch(error){
        if(token!==this.token)return;
        const code=error instanceof Error?error.message:'TTS_FAILED';
        this.notice(`豆包语音暂不可用（${code}），已回退浏览器语音 · 估算嘴型`);
        await this.tts.speak(task.text,{...this.settings,enabled:this.audioUsed},hooks);
      }
    }else await this.tts.speak(task.text,{...this.settings,enabled:this.audioUsed},hooks);
    if (token !== this.token) return;
    this.observe?.({taskId:task.taskId,state:audible?"finished":"text"});
    this.observedTask="";
    this.seen.add(key);
    if (this.seen.size > 300)
      this.seen.delete(this.seen.values().next().value!);
    this.lip?.stop();
    clearInterval(this.typing);
    this.text.textContent = task.text.slice(-56);
    this.character.endSpeech();
    this.active = "";
    this.audioUsed = false;
    this.hide = window.setTimeout(
      () => { this.bubble.classList.remove("visible"); this.bubble.setAttribute('aria-hidden', 'true'); },
      1800,
    );
    if (owns) {
      // Retry a lost completion without replaying the utterance; backend task-id check is idempotent.
      for (let i = 0; i < 3; i++) {
        if (ownsAudio() === false) break;
        try {
          await api("/speech/completed", {
            taskId: task.taskId,
            clientId: this.clientId(),
          });
          break;
        } catch {
          await new Promise((r) => setTimeout(r, 700));
        }
      }
    }
  }
  cancel(hideBubble = true) {
    if(this.observedTask)this.observe?.({taskId:this.observedTask,state:"cancelled"});
    this.observedTask="";
    this.token++;
    this.tts.cancel();
    this.doubao.cancel();
    this.lip?.stop();
    clearInterval(this.typing);
    clearTimeout(this.hide);
    this.active = "";
    this.audioUsed = false;
    this.character.endSpeech();
    if (hideBubble) { this.bubble.classList.remove("visible"); this.bubble.setAttribute('aria-hidden', 'true'); }
  }
  ended(taskId: string) {
    if (this.active.startsWith(taskId + ":")) {
      this.cancel(false);
      this.hide = window.setTimeout(
        () => { this.bubble.classList.remove("visible"); this.bubble.setAttribute('aria-hidden', 'true'); },
        1600,
      );
    }
  }
}

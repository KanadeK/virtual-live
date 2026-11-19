import type {AudioSettings,SpeechTask} from '../events/protocol';
import type {TtsHooks,TtsService} from './TtsService';

interface AudioReply {provider:'doubao'|'browser'|'silent';audioUrl?:string;durationSeconds?:number;cacheHit?:boolean;reason?:string;voice?:string}
/** Credentials never enter this module. It only requests an owned task's same-origin audio. */
export class DoubaoTts implements TtsService {
  private context?:AudioContext;private source?:AudioBufferSourceNode;private analyser?:AnalyserNode;
  private abort?:AbortController;private finish?:()=>void;private generation=0;private progress=0;
  constructor(private notice:(text:string)=>void){}
  unlock(){
    if(!('AudioContext'in window))return;
    this.context??=new AudioContext();void this.context.resume();
    const silent=this.context.createBufferSource();silent.buffer=this.context.createBuffer(1,1,this.context.sampleRate);silent.connect(this.context.destination);silent.start();
  }
  async speak(_text:string,settings:AudioSettings,hooks:TtsHooks,task?:SpeechTask,clientId?:string):Promise<void>{
    this.cancel();const generation=++this.generation;
    if(!task||!clientId)throw new Error('TTS_TASK_REQUIRED');
    if(!this.context)throw new Error('AUDIO_NOT_UNLOCKED');
    const abort=new AbortController();this.abort=abort;
    const timeout=window.setTimeout(()=>abort.abort(),30000);
    try{
      const response=await fetch('/api/tts/synthesize',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({taskId:task.taskId,clientId,rate:settings.rate,pitch:settings.pitch}),signal:abort.signal});
      if(!response.ok)throw new Error(`TTS_HTTP_${response.status}`);
      const audio=await response.json() as AudioReply;
      if(generation!==this.generation)return;
      if(audio.provider!=='doubao'||!audio.audioUrl)throw new Error(audio.reason||'TTS_FALLBACK');
      // Only local audio routes are accepted, even if a malformed response provides a URL.
      if(!/^\/api\/tts\/audio\/[a-f0-9]{64}$/.test(audio.audioUrl))throw new Error('INVALID_AUDIO_URL');
      const bytes=await fetch(audio.audioUrl,{signal:abort.signal});if(!bytes.ok)throw new Error('AUDIO_FETCH_FAILED');
      const buffer=await this.context.decodeAudioData(await bytes.arrayBuffer());
      if(generation!==this.generation)return;
      clearTimeout(timeout);await this.context.resume();
      if(this.context.state!=='running')throw new Error('AUDIO_NOT_UNLOCKED');
      const source=this.context.createBufferSource(),analyser=this.context.createAnalyser();
      source.buffer=buffer;analyser.fftSize=1024;analyser.smoothingTimeConstant=.65;source.connect(analyser);analyser.connect(this.context.destination);
      this.source=source;this.analyser=analyser;
      this.notice(`豆包语音 2.0${audio.cacheHit?' · 缓存':''} · 音量驱动嘴型`);
      await new Promise<void>(resolve=>{
        let done=false,started=false;const complete=()=>{if(done)return;done=true;if(started)hooks.onAudibleEnd?.();clearInterval(this.progress);source.disconnect();analyser.disconnect();this.source=undefined;this.analyser=undefined;this.finish=undefined;resolve();};
        this.finish=complete;source.onended=complete;
        const start=this.context!.currentTime;source.start();
        started=true;hooks.onStart();hooks.onAudibleStart?.("buffer");hooks.onAnalyser?.(analyser);
        this.progress=window.setInterval(()=>hooks.onProgress?.(Math.max(0,this.context!.currentTime-start),buffer.duration),80);
        window.setTimeout(()=>{if(!done&&generation===this.generation){try{source.stop();}catch{}complete();}},buffer.duration*1000+5000);
      });
    }catch(error){if(generation!==this.generation)return;throw error;}
    finally{clearTimeout(timeout);if(this.abort===abort)this.abort=undefined;}
  }
  cancel(){this.generation++;this.abort?.abort();this.abort=undefined;clearInterval(this.progress);try{this.source?.stop();}catch{}const finish=this.finish;this.finish=undefined;finish?.();this.source=undefined;this.analyser=undefined;}
}

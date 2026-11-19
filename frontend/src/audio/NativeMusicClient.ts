import {api} from '../events/api';

export interface NativeMusicState {
  enabled:boolean;mode:'HTML'|'STOPPING_HTML'|'ACTIVATING_NATIVE'|'NATIVE'|'NATIVE_PAUSED'|'RELEASING'|'HOLDING_SILENCE';
  revision:number;instanceId?:string;ownerEpoch:string;stopNonce:string;notice:string;
  playbackConfirmed?:boolean;completeSongVerified:false;previewStatus:'unknown';
  position?:number;duration?:number;current?:{id:string;title:string;artist:string};queue?:{requestId:string;id:string;title:string;artist:string}[];
  reportedAt?:number;
}
interface HtmlExecutor {suspendForNative:()=>Promise<boolean>;resumeFromNative:()=>void;duck:(active:boolean)=>void;nativeState?:(state:NativeMusicState)=>boolean}
type Post=(path:string,body?:unknown)=>Promise<unknown>;
interface ActualSpeech {taskId:string;ownerEpoch:string;actual:boolean;native?:{executionId:string;playbackId:string;sequence:number;startConfirmed:boolean;responseSequence:number}}

/** Only the product backend is contacted. No Awoo address/token is present in browser code. */
export class NativeMusicClient {
  private state?:NativeMusicState;private owns=false;private epoch='';private speech?:ActualSpeech;private pulseTimer=0;
  private acknowledged='';private stopSerial=0;
  constructor(private music:HtmlExecutor,private clientId:()=>string,private post:Post=api){}
  async refresh(){try{this.receive(await api<NativeMusicState>('/music/native/status'));}catch{await this.music.suspendForNative();}}
  ownership(owns:boolean,epoch:string){
    if(owns===this.owns&&epoch===this.epoch)return;
    this.actualAudioEnded();this.owns=owns;this.epoch=epoch;this.acknowledged='';this.stopSerial++;
    if(!owns)void this.music.suspendForNative();else if(this.state)this.apply(this.state);
  }
  receive(state:NativeMusicState){
    if(this.state&&this.state.instanceId===state.instanceId&&this.state.ownerEpoch===state.ownerEpoch&&state.revision<this.state.revision)return;
    this.state=state;this.apply(state);
  }
  private apply(state:NativeMusicState){
    if(!this.owns||!this.epoch||state.ownerEpoch!==this.epoch){void this.music.suspendForNative();return;}
    if(this.music.nativeState?.(state)===false)return;
    if(state.mode==='HTML'){this.stopSerial++;this.music.resumeFromNative();this.attachActualSpeech();return;}
    const serial=++this.stopSerial;
    void this.music.suspendForNative().then(stopped=>{
      if(serial!==this.stopSerial||this.state!==state||!this.owns||this.epoch!==state.ownerEpoch)return;
      if(state.mode==='STOPPING_HTML'&&state.stopNonce&&this.acknowledged!==state.stopNonce){
        this.acknowledged=state.stopNonce;
        void this.post('/music/native/html-stopped',{clientId:this.clientId(),ownerEpoch:this.epoch,stopNonce:state.stopNonce,stopped}).catch(()=>{if(this.acknowledged===state.stopNonce)this.acknowledged='';});
      }
    });
    this.attachActualSpeech();
  }
  /** Called only by a real media `playing`/utterance `onstart` hook, never text/estimated start. */
  actualAudioStarted(taskId:string,ownerEpoch:string){
    if(!this.owns||ownerEpoch!==this.epoch)return;
    if(this.speech?.actual&&this.speech.taskId===taskId&&this.speech.ownerEpoch===ownerEpoch)return;
    this.actualAudioEnded();this.speech={taskId,ownerEpoch,actual:true};this.music.duck(true);this.attachActualSpeech();
    this.pulseTimer=window.setInterval(()=>{
      if(!this.speech?.actual)return;
      const previous=this.speech.native;this.attachActualSpeech();
      const native=this.speech?.native;
      if(native&&native===previous)this.sendSpeech(native.startConfirmed?'PULSE':'START');
    },2000);
  }
  actualAudioEnded(taskId?:string){
    if(taskId&&this.speech?.taskId!==taskId)return;
    clearInterval(this.pulseTimer);this.pulseTimer=0;
    if(this.speech){this.sendSpeech('END');this.speech.actual=false;this.speech=undefined;}
    this.music.duck(false);
  }
  private attachActualSpeech(){
    const speech=this.speech,state=this.state;
    if(!speech?.actual||!this.owns||speech.ownerEpoch!==this.epoch)return;
    const executionId=state&&state.enabled&&['NATIVE','NATIVE_PAUSED'].includes(state.mode)&&state.ownerEpoch===this.epoch?state.stopNonce:'';
    if(speech.native?.executionId===executionId)return;
    if(speech.native){this.sendSpeech('END');speech.native=undefined;}
    if(executionId){speech.native={executionId,playbackId:crypto.randomUUID(),sequence:0,startConfirmed:false,responseSequence:0};this.sendSpeech('START');}
  }
  private sendSpeech(phase:'START'|'PULSE'|'END'){
    const speech=this.speech,native=speech?.native;if(!speech||!native)return;
    const body={clientId:this.clientId(),ownerEpoch:speech.ownerEpoch,executionId:native.executionId,taskId:speech.taskId,playbackId:native.playbackId,sequence:++native.sequence,phase};
    void (async()=>{
      let accepted=false;
      try{const response=await this.post('/music/native/speech',body);accepted=typeof response==='object'&&response!==null&&'accepted' in response&&response.accepted===true;}catch{}
      // Late replies cannot revive an ended/replaced playback or undo a newer confirmation.
      if(phase==='END'||this.speech!==speech||speech.native!==native||!speech.actual||!this.owns||speech.ownerEpoch!==this.epoch||body.sequence<native.responseSequence)return;
      native.responseSequence=body.sequence;native.startConfirmed=accepted;
    })();
  }
  disconnected(){this.actualAudioEnded();this.owns=false;this.stopSerial++;void this.music.suspendForNative();}
  destroy(){this.disconnected();}
}

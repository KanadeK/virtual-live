import WebSocket from 'ws';
import { BilibiliApi, type ConnectionInfo } from './api.js';
import { BiliError, packet, PacketDecoder, parseJson, MAX_PACKET_BYTES } from './protocol.js';
import { normalize, type BilibiliEvent } from './normalize.js';

export interface SessionStatus { state:string; message:string; errorCode:string; requiresLogin:boolean; attempts:number; frameCount:number; hasLogin:boolean }
export interface Api { resolve(input:string, signal:AbortSignal):Promise<{roomId:string;liveStatus:number}>; connectInfo(roomId:string, liveStatus:number, signal:AbortSignal):Promise<ConnectionInfo>; dispose():void }
export interface SessionOptions {
  api?: Api; socket?: (url:string) => WebSocket; heartbeatMs?:number; authTimeoutMs?:number; staleMs?:number; backoffMs?:number;
  onStatus:(status:SessionStatus)=>void; onEvent:(event:BilibiliEvent)=>void;
  onResolved:(room:{roomId:string; liveStatus:number})=>void;
}

export class BilibiliSession {
  private readonly abort = new AbortController();
  private readonly api:Api;
  private ws?:WebSocket;
  private running?:Promise<void>;
  private stopped = false;
  private frames = 0;
  private attempts = 0;
  private hasLogin = false;
  constructor(private input:string, cookie:string, private options:SessionOptions) { this.api = options.api ?? new BilibiliApi(cookie); }
  start():Promise<void> { return this.running ??= this.run(); }
  async stop():Promise<void> {
    this.stopped = true; this.abort.abort(); this.ws?.terminate();
    await this.running; this.api.dispose();
  }
  private status(state:string, message:string, errorCode = ''):void {
    if (!this.stopped) this.options.onStatus({state,message,errorCode,requiresLogin:errorCode==='LOGIN_REQUIRED', attempts:this.attempts,frameCount:this.frames,hasLogin:this.hasLogin});
  }
  private async delay(ms:number):Promise<void> {
    const signal = this.abort.signal;
    if (signal.aborted) return;
    await new Promise<void>(resolve => {
      const done = () => {clearTimeout(timer);signal.removeEventListener('abort',done);resolve();};
      const timer = setTimeout(done,ms); signal.addEventListener('abort',done,{once:true});
    });
  }
  private async run():Promise<void> {
    try {
      while (!this.stopped) {
        try {
          this.status('connecting','正在解析B站房间并获取正常弹幕凭证');
          const room = await this.api.resolve(this.input,this.abort.signal);
          if (this.stopped) return;
          this.options.onResolved(room);
          const info = await this.api.connectInfo(room.roomId,room.liveStatus,this.abort.signal);
          if (this.stopped) return;
          this.hasLogin = info.hasLogin;
          await this.connection(info);
        } catch (error) {
          if (this.stopped) return;
          const failure = error instanceof BiliError ? error : new BiliError('NETWORK_ERROR','B站连接中断，请检查网络',true);
          if (failure.code === 'ROOM_OFFLINE') {this.status('closed',failure.message,failure.code);return;}
          if (!failure.retryable || this.attempts >= 5) {this.status('error',failure.message,failure.code);return;}
          const ms = Math.min(30000,(this.options.backoffMs ?? 3000)*2**this.attempts++);
          this.status('reconnecting',`${failure.message}，${Math.max(1,Math.ceil(ms/1000))}秒后重试`,failure.code);
          await this.delay(ms);
        }
      }
    } finally { this.ws?.terminate(); this.ws=undefined; this.api.dispose(); }
  }
  private connection(info:ConnectionInfo):Promise<void> {
    return new Promise((resolve,reject) => {
      const socket = this.options.socket ? this.options.socket(info.urls[this.attempts % info.urls.length])
        : new WebSocket(info.urls[this.attempts % info.urls.length], {origin:'https://live.bilibili.com',maxPayload:MAX_PACKET_BYTES,handshakeTimeout:12000,followRedirects:false});
      this.ws = socket;
      const decoder = new PacketDecoder();
      let authenticated = false, settled = false, lastReply = Date.now();
      let heartbeat:ReturnType<typeof setInterval>|undefined;
      const finish = (error?:BiliError) => {
        if (settled) return; settled = true;
        clearTimeout(authTimer); clearInterval(heartbeat);this.abort.signal.removeEventListener('abort',cancel);
        socket.terminate(); if(this.ws===socket)this.ws=undefined;
        if (this.stopped || !error) resolve(); else reject(error);
      };
      const cancel = () => finish();
      const authTimer = setTimeout(()=>finish(new BiliError('AUTH_TIMEOUT','B站弹幕鉴权超时',true)),this.options.authTimeoutMs ?? 12000);
      this.abort.signal.addEventListener('abort',cancel,{once:true});
      socket.on('open',()=>{ if (this.stopped) {finish();return;} socket.send(packet(7,info.authBody)); });
      socket.on('message',raw=>{
        if (this.stopped || settled) return;
        this.frames++;
        try {
          const bytes = Array.isArray(raw) ? Buffer.concat(raw) : Buffer.isBuffer(raw) ? raw : Buffer.from(raw);
          for (const item of decoder.feed(bytes)) {
            if (settled) return;
            if (item.operation === 8) {
              const auth = parseJson(item.body.toString('utf8'));
              if (auth?.code !== 0) {
                finish(new BiliError(Number(auth.code)===-101?'LOGIN_REQUIRED':'AUTH_FAILED','B站拒绝弹幕鉴权，请正常登录；如仍被拒绝需平台授权'));return;
              }
              if (authenticated) continue;
              authenticated=true;clearTimeout(authTimer);lastReply=Date.now();
              this.status('live','正在采集B站评论、礼物和观众进场');
              socket.send(packet(2,'[object Object]'));
              heartbeat=setInterval(()=>{
                if (Date.now()-lastReply>(this.options.staleMs ?? 75000)) {finish(new BiliError('HEARTBEAT_TIMEOUT','B站弹幕心跳超时',true));return;}
                if(socket.readyState===WebSocket.OPEN)socket.send(packet(2,'[object Object]'));
              },this.options.heartbeatMs ?? 30000);
            } else if(item.operation===3 && authenticated) {
              if (item.body.length < 4) throw new Error('invalid heartbeat');
              lastReply=Date.now();this.status('live','正在采集B站评论、礼物和观众进场');
            }
            else if(item.operation===5 && authenticated) {
              const command = parseJson(item.body.toString('utf8'));
              if (command.cmd==='PREPARING') {finish(new BiliError('ROOM_OFFLINE','B站房间已下播'));return;}
              for(const event of normalize(command,info.roomId))this.options.onEvent(event);
            }
          }
        } catch { finish(new BiliError('PROTOCOL_ERROR','B站弹幕协议异常，已停止采集')); }
      });
      socket.on('error',()=>finish(new BiliError('NETWORK_ERROR','无法连接B站弹幕服务器',true)));
      socket.on('close',()=>{
        if(settled)return;
        try{decoder.finish();}catch{finish(new BiliError('PROTOCOL_ERROR','B站弹幕数据不完整'));return;}
        finish(new BiliError('NETWORK_ERROR','B站弹幕连接已断开',true));
      });
    });
  }
}

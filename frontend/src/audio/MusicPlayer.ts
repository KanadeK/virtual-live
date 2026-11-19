import {api,toast} from '../events/api';
import {icon} from '../pages/icons';
import { StageMusicBanner } from './StageMusicBanner';
import { updateAudioOwnership } from './ownership';
import type {NativeMusicState} from './NativeMusicClient';

export interface Song {songId:string;title:string;artist:string;songUrl:string;url?:string;audioUrl:string;durationMs:number;source?:string}
export interface MusicEntry {entryId:string;song:Song;requestedAt:number;source?:string}
export interface MusicState {playlist:MusicEntry[];current:Partial<MusicEntry>;paused:boolean;repeat:boolean;volume:number;position:number;limit:number;revision:number;notice:string;fallbackActive?:boolean;localPlaylist?:MusicEntry[];source?:string;playback?:{phase:string;reportedAt:number;confirmed:boolean;entryId?:string;ownerEpoch?:string};native?:NativeMusicState}
export function musicMarkup(){return `<section class="panel music-panel" id="music" aria-labelledby="music-title">
  <div class="panel-heading"><h2 id="music-title">${icon('music')} 点歌台</h2><span class="music-source" id="music-source">本地背景音乐</span></div>
  <div class="music-defaults-row"><p class="music-intro">无人点歌时播放本地背景音乐，点歌后优先播放你选的歌曲。</p><button type="button" class="text-button" id="music-defaults">恢复本地背景音乐</button></div>
  <form id="music-form"><label for="music-command">歌曲名称</label><div class="music-input-row"><input id="music-command" maxlength="140" required placeholder="输入歌名，例如：稻香" autocomplete="off"><button class="primary-button" type="submit" id="music-add">${icon('plus')} 加入</button></div><p id="music-feedback" class="music-feedback" role="status">列表最多 20 首，可切换列表循环或顺序播放。</p></form>
  <div class="music-now"><span class="music-disc">${icon('music')}</span><div><small id="music-playback-state">等待音乐</small><b id="music-now-title">本地背景音乐</b><span id="music-now-artist">音乐由独立直播画面播放</span></div></div>
  <div class="music-toolbar"><button type="button" class="secondary-button" id="music-toggle">${icon('pause')} 暂停</button><button type="button" class="secondary-button" id="music-skip">${icon('next')} 下一首</button><button type="button" class="secondary-button" id="music-repeat" aria-pressed="true">${icon('repeat')} 列表循环</button><label for="music-volume">音量<input type="range" id="music-volume" min="0" max="1" step="0.05" value="0.45"><output id="music-volume-value">45%</output></label></div>
  <div class="music-list-heading"><span>点歌列表 <b id="music-count">0 / 20</b></span><button type="button" class="text-button" id="music-clear">清空点歌</button></div><ol id="music-list" class="music-list"><li class="music-empty">暂无点歌，播放本地背景音乐。</li></ol>
  <p class="music-footnote" id="music-local-status">主播从已配置曲库找歌；入队和实际播放分别显示。</p>
  <p class="music-footnote" id="music-provider-status">正在检查音源…</p>
  <button type="button" class="text-button" id="music-awoo-check">检查嗷呜只读状态</button><p id="music-awoo-status" role="status"></p>
</section>`;}

let controlState: MusicState | undefined;
let controlOwnerConnected = false;
let boundForm: HTMLFormElement | null = null;
const text = (id: string, value: string) => { const el = document.getElementById(id); if (el) el.textContent = value; };
const localSong = (state: MusicState) => state.source === 'local' || state.fallbackActive === true || state.current.source === 'local' || state.current.song?.source === 'local';

/** Pure DOM rendering: safe on /live and on a control page without a music panel. */
export function updateMusicControls(state: MusicState, ownerConnected = controlOwnerConnected) {
  controlState = state; controlOwnerConnected = ownerConnected;
  if (!document.getElementById('music-form')) return;
  const song = state.current.song, local = localSong(state);
  const sourceName:Record<string,string>={local:'本地曲库',netease:'网易云',tencent:'QQ音乐',kugou:'酷狗音乐',qishui:'汽水音乐',awoo:'嗷呜点歌机'};
  text('music-source', local ? '本地曲库' : song ? sourceName[song.source||state.source||'']||'已配置音源' : '音乐');
  text('music-count', `${state.playlist.length} / ${state.limit}`);
  text('music-now-title', song?.title || '暂无播放音乐');
  text('music-now-artist', song?.artist || (local ? '本地背景音乐' : '音乐由独立直播画面播放'));
  const confirmed=state.playback?.confirmed===true&&Date.now()-(state.playback?.reportedAt||0)<8000;
  const progress=`${Math.floor(state.position/60)}:${String(Math.floor(state.position%60)).padStart(2,'0')}`;
  text('music-playback-state', !song ? '等待音乐' : state.paused ? '已暂停' : !ownerConnected ? '等待打开直播页并启用音乐' : confirmed ? `正在播放 · ${progress}` : state.playback?.phase==='LOADING' ? '正在加载音频' : '已选曲，等待播放器确认');
  const toggle = document.getElementById('music-toggle');
  if (toggle) toggle.innerHTML = icon(state.paused ? 'play' : 'pause') + (state.paused ? '继续' : '暂停');
  const repeat = document.getElementById('music-repeat');
  if (repeat) { repeat.innerHTML = icon('repeat') + (state.repeat ? '列表循环' : '顺序播放'); repeat.setAttribute('aria-pressed', String(state.repeat)); }
  for (const id of ['music-toggle', 'music-skip']) { const button = document.getElementById(id) as HTMLButtonElement | null; if (button) button.disabled = !song; }
  const clear = document.getElementById('music-clear') as HTMLButtonElement | null;
  if (clear) clear.disabled = state.playlist.length === 0;
  const volume = document.getElementById('music-volume') as HTMLInputElement | null;
  if (volume && document.activeElement !== volume) volume.value = String(state.volume);
  text('music-volume-value', `${Math.round(state.volume * 100)}%`);
  const localCount = state.localPlaylist?.length;
  text('music-local-status', localCount !== undefined ? `本地背景音乐 ${localCount} 首。点歌优先播放，清空点歌后恢复本地音乐。` : '无人点歌时自动播放本地背景音乐。');
  const list = document.getElementById('music-list');
  if (list) {
    list.replaceChildren();
    if (!state.playlist.length) { const empty = document.createElement('li'); empty.className = 'music-empty'; empty.textContent = local && song ? '暂无点歌，正在播放本地背景音乐。' : '暂无点歌。'; list.append(empty); }
    state.playlist.forEach((entry, index) => {
      const row = document.createElement('li'); row.className = entry.entryId === state.current.entryId ? 'current-song' : ''; row.dataset.entryId = entry.entryId;
      const order = document.createElement('span'); order.className = 'music-order'; order.textContent = row.className ? '♫' : String(index + 1).padStart(2, '0');
      const info = document.createElement('div'), artist = document.createElement('small');
      const url = entry.song.songUrl || entry.song.url;
      const title = document.createElement(url && entry.source !== 'local' && entry.song.source !== 'local' ? 'a' : 'span');
      if (title instanceof HTMLAnchorElement && url) { title.href = url; title.target = '_blank'; title.rel = 'noopener noreferrer'; title.title = '在网易云打开'; }
      title.textContent = entry.song.title; artist.textContent = entry.song.artist; info.append(title, artist);
      const remove = document.createElement('button'); remove.type = 'button'; remove.className = 'music-remove'; remove.dataset.removeSong = entry.entryId; remove.setAttribute('aria-label', `移除 ${entry.song.title}`); remove.innerHTML = icon('clear');
      row.append(order, info, remove); list.append(row);
    });
  }
  if (state.notice?.includes('无法播放')) { text('music-feedback', state.notice); document.getElementById('music-feedback')?.classList.add('bad'); }
  const native=state.native;
  if(native?.enabled&&native.mode!=='HTML'){
    const queue=native.queue||[],current=native.current;const active=['NATIVE','NATIVE_PAUSED'].includes(native.mode);
    text('music-source','QQ音乐');text('music-now-title',current?.title||'等待QQ音乐');text('music-now-artist',current?.artist||'');text('music-count',`${queue.length} / ${state.limit}`);
    const seconds=Math.max(0,native.position||0),time=`${Math.floor(seconds/60)}:${String(Math.floor(seconds%60)).padStart(2,'0')}`;
    text('music-playback-state',native.mode==='NATIVE_PAUSED'?'QQ已暂停':native.playbackConfirmed?`已开始播放 · ${time} · 整曲待确认`:native.notice||'等待QQ反馈');
    if(toggle){(toggle as HTMLButtonElement).disabled=!active;toggle.innerHTML=icon(native.mode==='NATIVE_PAUSED'?'play':'pause')+(native.mode==='NATIVE_PAUSED'?'继续':'暂停');}
    if(clear)clear.disabled=false;
    for(const id of ['music-skip','music-repeat','music-volume']){const element=document.getElementById(id) as HTMLButtonElement|HTMLInputElement|null;if(element)element.disabled=true;}
    text('music-volume-value','在QQ内调整');text('music-local-status','清空点歌后，待QQ停止确认再恢复本地音乐。');text('music-provider-status','QQ正常点歌；试听和整曲权限以客户端为准。');
    if(list){list.replaceChildren();if(!queue.length){const empty=document.createElement('li');empty.className='music-empty';empty.textContent=active?'QQ待播队列为空，处理中的请求等待反馈。':native.notice;list.append(empty);}for(const [index,item] of queue.entries()){const row=document.createElement('li'),title=document.createElement('span'),artist=document.createElement('small');title.textContent=`${index+1}. ${item.title}`;artist.textContent=item.artist;row.append(title,artist);list.append(row);}}
  }else{
    for(const id of ['music-repeat','music-volume']){const element=document.getElementById(id) as HTMLButtonElement|HTMLInputElement|null;if(element)element.disabled=false;}
  }
}

/** Bind a silent control panel without ever constructing an Audio or MusicPlayer. */
export function bindMusic() {
  const form = document.querySelector<HTMLFormElement>('#music-form');
  if (!form || form === boundForm) return;
  boundForm = form;
  const feedback = (message: string, bad = false) => { text('music-feedback', message); document.getElementById('music-feedback')?.classList.toggle('bad', bad); };
  const change = async (path: string, body: unknown) => {
    try { const state = await api<MusicState>(path, body); updateMusicControls(state); return state; }
    catch (error) { const message = error instanceof Error ? error.message : '音乐操作失败'; feedback(message, true); toast(message, true); return undefined; }
  };
  form.onsubmit = async event => {
    event.preventDefault();
    const input = document.getElementById('music-command') as HTMLInputElement | null, button = document.getElementById('music-add') as HTMLButtonElement | null;
    const query = input?.value.trim();
    if (!query) { feedback('请输入歌曲名称', true); return; }
    if (button) button.disabled = true;
    feedback('正在搜索并加入点歌列表…');
    const state = await change('/music/request', { command: /^点歌\s/.test(query) ? query : '点歌 ' + query });
    if (state && input) { input.value = ''; feedback(state.notice || '已加入点歌列表'); }
    if (button) button.disabled = false;
  };
  const bind = (id: string, fn: () => void) => { const el = document.getElementById(id); if (el) el.onclick = fn; };
  bind('music-repeat', () => { void change('/music/repeat', { enabled: !controlState?.repeat }); });
  bind('music-toggle', () => { const native=controlState?.native;void change('/music/pause', { paused: native?.enabled&&native.mode!=='HTML'?native.mode!=='NATIVE_PAUSED':!controlState?.paused }); });
  bind('music-skip', () => { void change('/music/skip', {}); });
  bind('music-clear', () => { void change('/music/clear', {}); });
  bind('music-defaults', () => {
    const button = document.getElementById('music-defaults') as HTMLButtonElement | null;
    if (button) button.disabled = true;
    void change('/music/defaults', {}).then(state => { if (state) feedback(state.notice || '本地背景音乐已恢复'); }).finally(() => { if (button) button.disabled = false; });
  });
  const volume = document.getElementById('music-volume');
  if (volume) volume.onchange = event => { void change('/music/volume', { volume: Number((event.target as HTMLInputElement).value) }); };
  const list = document.getElementById('music-list');
  if (list) list.onclick = event => { const button = (event.target as HTMLElement).closest<HTMLButtonElement>('[data-remove-song]'); if (button) void change('/music/remove', { entryId: button.dataset.removeSong }); };
  if (controlState) updateMusicControls(controlState);
  void api<MusicState>('/music/status').then(state => updateMusicControls(state)).catch(() => {});
  void api<{notice:string}>('/music/provider').then(state=>text('music-provider-status',state.notice)).catch(()=>text('music-provider-status','音源状态暂不可用'));
  bind('music-awoo-check',()=>{void api<{notice:string}>('/music/awoo/status').then(state=>text('music-awoo-status',state.notice)).catch(()=>text('music-awoo-status','嗷呜状态无法读取；未执行任何控制'));});
}

/** Only the room's /live owner creates audio. Controls and observers stay silent. */
export class MusicPlayer {
  private banner = new StageMusicBanner();
  private state?:MusicState;private owns=false;private unlocked=false;private audio?:HTMLAudioElement;
  private entryId='';private loadTimer=0;private progressTimer=0;private ducked=false;private finishing=false;private lastNotice='';private blocked=false;private ownerConnected=false;
  private ownerEpoch='';
  private nativeSuspended=false;private nativeSnapshot?:NativeMusicState;
  constructor(private isLive:boolean,private clientId:()=>string){
    if(!isLive)bindMusic();
    window.addEventListener('pagehide',()=>{this.stop();this.banner.destroy();},{once:true});
  }
  async refresh(){try{this.receive(await api<MusicState>('/music/status'),true);}catch{}}
  receive(state:MusicState,force=false){
    if(this.owns&&state.native&&state.native.ownerEpoch!==this.ownerEpoch)return;
    const previousInstance=this.state?.native?.instanceId,incomingInstance=state.native?.instanceId;
    const sameInstance=!previousInstance||!incomingInstance||previousInstance===incomingInstance;
    if(!force&&sameInstance&&this.state&&state.revision<this.state.revision)return;
    this.state=state;if(state.native)this.nativeState(state.native);this.render();this.sync();
    if(this.isLive&&state.notice!==this.lastNotice&&state.notice?.includes('无法播放'))toast(state.notice,true);
    this.lastNotice=state.notice;
  }
  ownership(owns:boolean,ownerConnected=this.ownerConnected,ownerEpoch=this.ownerEpoch){
    if(this.isLive)updateAudioOwnership(owns);
    const changed=owns!==this.owns||ownerEpoch!==this.ownerEpoch,connectionChanged=ownerConnected!==this.ownerConnected;
    this.ownerConnected=ownerConnected;
    if(changed){this.stop();this.owns=owns;this.ownerEpoch=ownerEpoch;if(owns)this.sync();}
    if(changed||connectionChanged)this.render();
  }
  disconnected(){this.ownership(false);}
  unlock(){this.unlocked=true;this.blocked=false;this.sync();}
  duck(value:boolean){this.ducked=value;this.applyVolume();}
  nativeState(state:NativeMusicState){
    if(this.owns&&state.ownerEpoch!==this.ownerEpoch)return false;
    const previous=this.nativeSnapshot;
    if(previous&&previous.instanceId===state.instanceId&&previous.ownerEpoch===state.ownerEpoch&&state.revision<previous.revision)return false;
    this.nativeSnapshot=state;if(state.enabled&&state.mode!=='HTML')this.nativeSuspended=true;this.render();return true;
  }
  async suspendForNative():Promise<boolean>{
    this.nativeSuspended=true;clearTimeout(this.loadTimer);clearInterval(this.progressTimer);
    const audio=this.audio||document.querySelector<HTMLAudioElement>('#music-audio');
    if(!audio)return true;
    try{
      audio.onended=null;audio.onerror=null;audio.onplaying=null;audio.onloadedmetadata=null;audio.onwaiting=null;audio.onpause=null;
      audio.pause();if(!audio.paused)return false;audio.removeAttribute('src');audio.load();
      // Verify the real element after resource-selection/pending play callbacks settle.
      for(let attempt=0;attempt<5&&(!audio.paused||audio.networkState!==0||audio.readyState!==0);attempt++)await new Promise(resolve=>window.setTimeout(resolve,20));
      // Chrome may retain historical currentSrc after load() has fully unloaded
      // the resource. NETWORK_EMPTY/HAVE_NOTHING + paused prove that state.
      if(!audio.paused||audio.getAttribute('src')||audio.networkState!==0||audio.readyState!==0)return false;
      audio.remove();if(this.audio===audio){this.audio=undefined;this.entryId='';this.finishing=false;this.blocked=false;}
      return !document.querySelector('#music-audio');
    }catch{return false;}
  }
  resumeFromNative(){if(this.nativeSnapshot?.enabled&&this.nativeSnapshot.mode!=='HTML')return;this.nativeSuspended=false;this.sync();}
  private applyVolume(){if(this.audio)this.audio.volume=(this.state?.volume??.45)*(this.ducked ? .25 : 1);}
  private stop(){clearTimeout(this.loadTimer);clearInterval(this.progressTimer);const audio=this.audio;this.audio=undefined;this.entryId='';this.finishing=false;this.blocked=false;const notice=document.getElementById('music-live-notice');if(notice)notice.hidden=true;if(audio){audio.onended=null;audio.onerror=null;audio.onplaying=null;audio.onloadedmetadata=null;audio.onwaiting=null;audio.onpause=null;audio.pause();audio.removeAttribute('src');audio.load();audio.remove();}}
  private sync(){
    if(this.nativeSuspended){void this.suspendForNative();return;}
    const state=this.state,entry=state?.current;
    if(!state||!this.isLive||!this.owns||!this.ownerEpoch||!entry?.entryId||!entry.song){this.stop();return;}
    if(state.native&&state.native.ownerEpoch!==this.ownerEpoch){this.stop();return;}
    if(this.entryId!==entry.entryId){
      this.stop();const audio=new Audio();audio.id='music-audio';audio.preload='none';audio.hidden=true;document.body.append(audio);this.audio=audio;this.entryId=entry.entryId;
      const id=entry.entryId,clientId=this.clientId(),ownerEpoch=this.ownerEpoch;
      audio.onended=()=>{if(this.entryId===id)void this.finish(id,false,clientId,ownerEpoch);};
      audio.onerror=()=>{if(this.entryId===id)void this.finish(id,true,clientId,ownerEpoch);};
      const report=(phase:'LOADING'|'PLAYING'|'PAUSED')=>{if(this.audio===audio&&this.owns&&this.entryId===id&&this.ownerEpoch===ownerEpoch)void api('/music/playback',{entryId:id,clientId,ownerEpoch,phase,seconds:Number.isFinite(audio.currentTime)?audio.currentTime:0}).catch(()=>{});};
      audio.onplaying=()=>{clearTimeout(this.loadTimer);this.setLiveNotice('');report('PLAYING');};
      audio.onwaiting=()=>report('LOADING');
      audio.onpause=()=>{if(this.state?.paused)report('PAUSED');};
      audio.onloadedmetadata=()=>{if(this.entryId===id&&state.position>0&&Number.isFinite(audio.duration))audio.currentTime=Math.min(state.position,Math.max(0,audio.duration-.2));};
      void api<{entryId:string;ownerEpoch:string;audioUrl:string}>('/music/prepare',{entryId:id,clientId,ownerEpoch}).then(ready=>{
        if(this.audio!==audio||this.ownerEpoch!==ownerEpoch||ready.entryId!==id||ready.ownerEpoch!==ownerEpoch)return;
        audio.src=ready.audioUrl;this.sync();
      }).catch(()=>{if(this.audio===audio&&this.ownerEpoch===ownerEpoch)void this.finish(id,true,clientId,ownerEpoch);});
      this.progressTimer=window.setInterval(()=>{if(this.audio===audio&&this.owns&&this.ownerEpoch===ownerEpoch&&!audio.paused&&Number.isFinite(audio.currentTime))void api('/music/progress',{entryId:id,clientId,ownerEpoch,seconds:audio.currentTime}).catch(()=>{});},2000);
    }
    this.applyVolume();
    if(!this.audio?.getAttribute('src'))return;
    if(state.paused){this.audio?.pause();clearTimeout(this.loadTimer);return;}
    if(!this.unlocked){this.setLiveNotice('播放音乐前，请点击启用语音与音乐。');return;}
    if(this.blocked)return;
    if(this.audio?.paused&&!this.finishing){const audio=this.audio,id=this.entryId,clientId=this.clientId(),ownerEpoch=this.ownerEpoch;void audio.play().then(()=>{if(this.audio!==audio)return;this.setLiveNotice('');}).catch(error=>{
      if(this.audio!==audio||this.state?.paused)return;
      if(error instanceof DOMException&&error.name==='NotAllowedError'){clearTimeout(this.loadTimer);this.blocked=true;this.setLiveNotice('浏览器阻止了自动播放，请点击“启用音乐”。',true);}
      else if(!(error instanceof DOMException&&error.name==='AbortError'))void this.finish(id,true,clientId,ownerEpoch);
    });clearTimeout(this.loadTimer);this.loadTimer=window.setTimeout(()=>{if(this.audio===audio&&audio.readyState<2&&!this.state?.paused)void this.finish(id,true,clientId,ownerEpoch);},20000);}
  }
  private async finish(id:string,failed:boolean,clientId:string,ownerEpoch:string){
    if(!this.owns||this.entryId!==id||this.ownerEpoch!==ownerEpoch||this.finishing||this.state?.paused)return;this.finishing=true;clearTimeout(this.loadTimer);
    for(let attempt=0;attempt<3;attempt++){
      if(!this.owns||this.entryId!==id||this.ownerEpoch!==ownerEpoch)return;
      try{await api<{accepted:boolean}>('/music/completed',{entryId:id,clientId,ownerEpoch,failed});if(this.ownerEpoch!==ownerEpoch)return;await this.refresh();if(this.entryId===id)this.finishing=false;return;}
      catch{if(attempt<2)await new Promise(resolve=>setTimeout(resolve,700));}
    }
    if(this.entryId===id&&this.ownerEpoch===ownerEpoch){this.setLiveNotice('音乐连接中断，恢复连接后继续播放。');this.finishing=false;window.setTimeout(()=>{if(this.owns&&this.entryId===id&&this.ownerEpoch===ownerEpoch)void this.finish(id,failed,clientId,ownerEpoch);},3000);}
  }
  private setLiveNotice(text:string,button=false){
    if(!this.isLive)return;
    let el=document.getElementById('music-live-notice');if(!el){el=document.createElement('div');el.id='music-live-notice';el.className='music-live-notice';document.body.append(el);}
    el.replaceChildren();el.hidden=!text||!button;
    if(button){const b=document.createElement('button');b.innerHTML=icon('volume');b.setAttribute('aria-label','启用音乐');b.title=text;b.onclick=()=>this.unlock();el.append(b);}
  }
  private render(){
    const native=this.nativeSnapshot||this.state?.native;
    const song=native?.enabled&&native.mode!=='HTML'?(native.current?.title?{songId:native.current.id,title:native.current.title,artist:native.current.artist,songUrl:'',audioUrl:'',durationMs:(native.duration||0)*1000,source:'qqmusic'}:undefined):this.state?.current.song;
    this.banner.render(song,native?.enabled&&native.mode!=='HTML'?native.mode==='NATIVE_PAUSED':this.state?.paused??false);
    if(!this.isLive&&this.state)updateMusicControls({...this.state,native},this.ownerConnected);
  }
}

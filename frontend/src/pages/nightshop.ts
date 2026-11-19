import type { MusicState } from '../audio/MusicPlayer';
import type {NativeMusicState} from '../audio/NativeMusicClient';
import type { CaptureAudience, CaptureEvent, CapturePlatform, CaptureStatus, RoomStatus, SpeechTask, WireMessage } from '../events/protocol';

export type NightLayer = 'all' | 'background' | 'avatar' | 'foreground' | 'ui';
export function nightView() {
  const params = new URLSearchParams(location.search);
  const requested = params.get('layer');
  const layer: NightLayer = requested && ['background', 'avatar', 'foreground', 'ui'].includes(requested) ? requested as NightLayer : 'all';
  return { layer, avatar: layer === 'all' || layer === 'avatar', ui: layer === 'all' || layer === 'ui',
    canOwn: (layer === 'all' || layer === 'avatar') && params.get('preview') !== '1', safe: params.get('safe') === '1' };
}
export type NightView = ReturnType<typeof nightView>;

export interface NightFinanceValues { balanceFen?: number | null; incomeFen?: number | null; costFen?: number | null; costBasis?: string; incomeReason?: string; incomeStartedAt?: number | null; costEstimate?: { startedAt?: number; reason?: string; reported?: number; knownSubtotalFen?: number }; }
/** Display-only mapping of explicit raw fen values; never writes a ledger or estimates a price. */
export function nightFinanceDisplay(finance?: NightFinanceValues) {
  const amount = (value: unknown) => typeof value === 'number' && Number.isFinite(value) && value >= 0 ? value : null;
  const income = amount(finance?.balanceFen), rawCost = amount(finance?.costFen);
  const cost = amount(rawCost === null ? null : rawCost * 3);
  return {
    balance: income === null ? '—' : income > 0 && income < 1 ? '<0.01 条' : `${(income / 100).toFixed(2)} 条`,
    incomeFoot: income === null ? finance?.incomeReason === 'free_currency_only' ? '免费礼物不折现' : finance?.incomeReason === 'gift_amount_missing' ? '部分礼物未核价' : '等待真实采集' : `${metricTime(finance?.incomeStartedAt)}起·非到账`,
    cost: cost === null ? '—' : cost > 0 && cost < 1 ? '< ¥ 0.01' : `¥ ${(cost / 100).toFixed(2)}`,
    costFoot: cost === null ? finance?.costEstimate?.reason === 'usage_not_returned' || finance?.costEstimate?.reason === 'unpriced_usage' ? '部分费用未核算' : '费用未核算' : `${metricTime(finance?.costEstimate?.startedAt)}起·估算×3·优惠未减`,
  };
}
function metricTime(at?: number | null) { return at && Number.isFinite(at) ? new Intl.DateTimeFormat('zh-CN', { timeZone: 'Asia/Shanghai', hour: '2-digit', minute: '2-digit', hourCycle: 'h23' }).format(at) : '接入后'; }

export function nightAudienceDisplay(audience?: CaptureAudience) {
  if (!audience?.available || audience.scope !== 'capture_run' || audience.timeZone !== 'Asia/Shanghai'
      || !Number.isInteger(audience.uniqueChatters) || audience.uniqueChatters! < 0 || !audience.startedAt)
    return { count: '—', basis: audience?.reason === 'capacity_reached' ? '统计范围已满' : '等待真实采集' };
  const since = new Intl.DateTimeFormat('zh-CN', { timeZone: 'Asia/Shanghai', hour: '2-digit', minute: '2-digit', hourCycle: 'h23' }).format(audience.startedAt);
  return { count: `${audience.uniqueChatters} 位`, basis: `${since}起${audience.hadGaps ? ' · 含断线' : ' · 采集去重'}` };
}

/** V3 art/layout only. No package server, demo seed, alternate avatar or business store. */
export function nightshopMarkup(view: NightView) {
  return `<div class="stage-shell nightshop-shell"><section class="stage nightshop" data-layer="${view.layer}" aria-label="虚拟主播工作室">
    ${view.layer === 'all' || view.layer === 'background' ? `<div class="night-background" data-layer-part="background"><img src="/assets/nightshop-v3/studio.svg" alt="" fetchpriority="high"><div class="night-vignette"></div></div>` : ''}
    ${view.avatar ? `<div class="night-avatar" data-layer-part="avatar"><div class="actor-shadow"></div><div class="actor" data-action="idle" data-mouth="0" data-emotion="neutral" data-speaking="false"></div></div>` : ''}
    ${view.layer === 'all' || view.layer === 'foreground' ? `<div class="night-foreground" data-layer-part="foreground"><div class="night-desk-front"></div></div>` : ''}
    ${view.ui ? `<div class="night-ui" data-layer-part="ui">
      <header class="night-brand"><h1>虚拟主播工作室 <i>✧</i></h1></header>
      <section class="night-board" aria-label="小屋营业牌">
        <div class="night-metrics"><div><small>鱼干余额</small><strong id="night-fish-balance">—</strong></div><div><small>运行支出</small><strong id="night-display-cost">—</strong></div><div><small>本次聊过天</small><strong id="night-chatters">—</strong></div></div>
        <div class="night-music-row"><div class="night-record" aria-hidden="true"><span>♫</span></div><div class="night-now"><strong id="night-song">暂无曲目</strong><div id="night-artist">等你留一首喜欢的歌</div><div class="night-timeline"><div><i id="night-progress"></i></div><span id="night-time">— / —</span></div></div><div class="night-queue"><small>点歌队列 <b id="night-queue-count">—</b></small><ol id="night-queue"><li>等待队列状态</li></ol></div></div>
        <div class="night-recent"><span id="night-recent-label">小屋便签</span><p id="night-recent">忙完了就来坐坐，聊两句。</p></div>
      </section>
      <div class="night-caption" hidden aria-live="off"><span id="night-speech-phase">回复已生成 · 语音待确认</span><p id="night-caption-text"></p></div>
    </div>` : ''}
    <div class="speech-bubble night-player-bubble" hidden aria-hidden="true"><div class="bubble-text"></div></div>
    <div class="gift-overlay" hidden></div><div class="particles" hidden></div><pre class="debug-panel" hidden></pre>
    ${view.safe ? `<div class="night-safe" aria-label="估算的平台遮挡，仅供预览"><div class="night-safe-top">平台头像 / 关注区域（估算）</div><div class="night-safe-promo">右上活动入口<br>（估算）</div><div class="night-safe-chat">原生弹幕留空区域<br>源 y1248 起 · 用户截图估算</div><div class="night-safe-input">原生输入 / 礼物区域（估算）</div></div>` : ''}
  </section></div>`;
}

export function fitNightshop() {
  const shell = document.querySelector<HTMLElement>('.nightshop-shell')!;
  const stage = document.querySelector<HTMLElement>('.nightshop')!;
  const resize = () => stage.style.transform = `translate(-50%,-50%) scale(${Math.max(shell.clientWidth / 1080, shell.clientHeight / 1920)})`;
  const observer = new ResizeObserver(resize); observer.observe(shell); resize();
  window.addEventListener('pagehide', () => observer.disconnect(), { once: true });
}

const text = (id: string, value: string) => { const el = document.getElementById(id); if (el) el.textContent = value; };
const clean = (value: unknown, max = 100) => typeof value === 'string' ? value.replace(/[\u0000-\u001f\u007f]/g, '').slice(0, max) : '';
const clock = (seconds: number) => Number.isFinite(seconds) && seconds >= 0 ? `${Math.floor(seconds / 60)}:${String(Math.floor(seconds % 60)).padStart(2, '0')}` : '—';
const platformOf = (value: { platform?: unknown }): CapturePlatform | undefined => value.platform === undefined ? 'douyin' : value.platform === 'douyin' || value.platform === 'bilibili' ? value.platform : undefined;

export interface VoiceObservation { taskId: string; state: 'started' | 'stopped' | 'finished' | 'text' | 'cancelled'; kind?: 'buffer' | 'browser'; }
/** Read-only projection. Missing finance/day/story/daily identity sources stay unknown. */
export class NightshopProjection {
  ownerId = ''; ownerEpoch = ''; ownerConnected = false;
  private connected = false;
  private confirmationFloor = Date.now();
  private music?: MusicState;
  private native?:NativeMusicState;
  private captures = new Map<CapturePlatform, CaptureStatus>();
  private seen = new Set<string>();
  private recentUntil = 0;
  private taskId = ''; private captionUntil = 0; private voiceUntil = 0;
  private voiceClosed = true;
  private preparing = false;
  private timer = window.setInterval(() => this.tick(), 500);
  constructor() { window.addEventListener('pagehide', () => clearInterval(this.timer), { once: true }); }
  connection(connected: boolean) {
    this.connected = connected; this.confirmationFloor = Date.now();
    // A new transport never inherits a previous playback observation or speech start.
    this.music = undefined;this.native=undefined; this.closeCaption(); this.voiceUntil = 0;
    this.captures.clear(); this.clearRecent(); this.renderAudience(); this.renderFinance(); this.renderMusic(); this.renderRoom();
  }
  captureStatus(status: CaptureStatus) {
    const platform = platformOf(status); if (platform) this.captures.set(platform, status);
    this.renderRoom();
  }
  receive({ type, data }: WireMessage) {
    if (type === 'SNAPSHOT' || type === 'QUEUE_UPDATE') {
      const room = data as RoomStatus & { finance?: NightFinanceValues };
      this.renderFinance(room.finance); this.renderAudience(room.audience);
      if (this.ownerId !== room.ownerId || this.ownerEpoch !== (room.ownerEpoch || '')) {
        this.confirmationFloor = Date.now(); this.voiceUntil = 0;
        text('night-speech-phase', '主播 · 语音状态待确认');
      }
      this.ownerId = room.ownerId; this.ownerEpoch = room.ownerEpoch || ''; this.ownerConnected = room.ownerConnected;
      this.preparing = room.phase === 'PREPARING_AI'; this.renderRoom(); this.renderMusic();
    }
    if (type === 'MUSIC_UPDATE') this.musicState(data as MusicState);
    if(type==='NATIVE_MUSIC_STATE')this.nativeState(data as NativeMusicState);
    if (type === 'CAPTURE_STATUS') this.captureStatus(data as CaptureStatus);
    if (type === 'CAPTURE_RESET') { const platform = platformOf(data as CaptureStatus); if (platform) this.captures.delete(platform); this.clearRecent(); this.renderRoom(); }
    if (type === 'CAPTURE_EVENT') this.capture(data as CaptureEvent);
    if (type === 'SPEAK') {
      const task = data as SpeechTask;
      this.taskId = task.taskId; this.captionUntil = Date.now() + 90_000; this.voiceUntil = 0; this.voiceClosed = false;
      text('night-caption-text', clean(task.text, 240));
      text('night-speech-phase', '主播 · 回复已生成，语音待确认');
      const caption = document.querySelector<HTMLElement>('.night-caption'); if (caption) caption.hidden = false;
    }
    if (type === 'SPEECH_END' || type === 'SPEECH_CANCEL') {
      if ((data as { taskId: string }).taskId === this.taskId) { this.voiceClosed = true; this.voiceUntil = 0; this.captionUntil = Date.now() + 1600; text('night-speech-phase', '主播 · 本段回复结束'); this.renderRoom(); }
    }
  }
  musicState(state: MusicState) {
    if (this.music && state.revision < this.music.revision) return;
    this.music = state;if(state.native)this.nativeState(state.native); this.renderMusic();
  }
  nativeState(state:NativeMusicState){
    if(this.native&&this.native.instanceId===state.instanceId&&this.native.ownerEpoch===state.ownerEpoch&&state.revision<this.native.revision)return;
    this.native=state;this.renderMusic();
  }
  voice(observation: VoiceObservation) {
    if (!this.connected || this.voiceClosed || observation.taskId !== this.taskId) return;
    if (observation.state === 'started') {
      this.voiceUntil = Date.now() + 1800;
      text('night-speech-phase', observation.kind === 'browser' ? '主播 · 浏览器语音播放中（估算嘴型）' : '主播 · 语音播放中');
    } else if(observation.state==='stopped'){
      this.voiceUntil=0;text('night-speech-phase','主播 · 等待语音继续');
    } else {
      this.voiceClosed = true;
      this.voiceUntil = 0;
      text('night-speech-phase', observation.state === 'text' ? '主播 · 文字回复（未确认发声）' : observation.state === 'finished' ? '主播 · 本段语音结束' : '主播 · 语音中断');
    }
    this.renderRoom();
  }
  private renderRoom() {
    const capture = [...this.captures.values()].find(s => s.state === 'live');
    text('night-connection', !this.connected ? '小屋连接中断' : capture ? `${capture.platform === 'bilibili' ? 'B站' : '抖音'}互动已连接` : '小屋在线 · 等待互动连接');
    document.querySelector('.night-status')?.classList.toggle('connected', this.connected && !!capture);
    text('night-activity', !this.connected ? '等连接回来' : this.voiceUntil > Date.now() ? '正在回你' : this.preparing ? '正在想一想' : '随便聊聊');
  }
  private renderMusic() {
    if(this.native?.enabled&&this.native.mode!=='HTML'){this.renderNativeMusic(this.native);return;}
    const state = this.music, song = state?.current.song, playback = state?.playback;
    const age = Date.now() - (playback?.reportedAt || 0);
    const confirmed = !!(this.connected && this.ownerConnected && this.ownerEpoch && state?.current.entryId && playback?.entryId === state.current.entryId && playback.ownerEpoch === this.ownerEpoch && playback.confirmed === true && playback.phase === 'PLAYING' && !state.paused && playback.reportedAt >= this.confirmationFloor && age >= 0 && age < 8000);
    text('night-song', clean(song?.title) || '暂无曲目');
    text('night-artist', song ? `${clean(song.artist) || '歌手未知'}${state?.source === 'local' || state?.fallbackActive ? ' · 本地曲库' : ''}` : '等你留一首喜欢的歌');
    document.querySelector('.night-record')?.classList.toggle('playing', confirmed);
    const progress = document.getElementById('night-progress');
    if (progress) progress.style.width = `${confirmed && song?.durationMs && state ? Math.min(100, Math.max(0, state.position * 100_000 / song.durationMs)) : 0}%`;
    text('night-time', `${confirmed && state ? clock(state.position) : '--:--'} / ${song && song.durationMs > 0 ? clock(song.durationMs / 1000) : '--:--'}`);
    const notice = state?.notice === '未找到本地背景音乐' ? '还没有待播曲目，想听什么可以留言。' : clean(state?.notice, 100);
    text('night-request', state ? notice || '等待点歌' : '点歌状态待确认');
    const entries = state?.playlist.filter(e => e.entryId !== state.current.entryId) || [];
    text('night-queue-count', state ? String(entries.length) : '—');
    const list = document.getElementById('night-queue');
    if (list) {
      list.replaceChildren();
      for (const entry of entries.slice(0, 2)) { const li = document.createElement('li'); li.textContent = clean(entry.song.title, 60); list.append(li); }
      if (!entries.length) { const li = document.createElement('li'); li.textContent = state ? '暂无等待曲目' : '等待队列状态'; list.append(li); }
    }
  }
  private renderNativeMusic(state:NativeMusicState){
    const current=state.current,age=Date.now()-(state.reportedAt||0);
    const confirmed=!!(this.connected&&this.ownerConnected&&state.ownerEpoch===this.ownerEpoch&&state.mode==='NATIVE'&&current?.id&&state.playbackConfirmed&&state.reportedAt!>=this.confirmationFloor&&age>=0&&age<5000);
    text('night-song',clean(current?.title)||'等待QQ曲目');text('night-artist',current?.title?`${clean(current.artist)||'歌手未知'} · QQ音乐`:'等正常点歌反馈');
    document.querySelector('.night-record')?.classList.toggle('playing',confirmed);const progress=document.getElementById('night-progress');if(progress)progress.style.width=`${confirmed&&state.duration?Math.min(100,Math.max(0,(state.position||0)*100/state.duration)):0}%`;
    text('night-time',`${confirmed?clock(state.position||0):'--:--'} / ${state.duration&&state.duration>0?clock(state.duration):'--:--'}`);text('night-request',clean(state.notice,100)||'QQ播放状态待确认');
    const queue=state.queue||[];text('night-queue-count',state.reportedAt?String(queue.length):'—');const list=document.getElementById('night-queue');if(list){list.replaceChildren();for(const item of queue.slice(0,2)){const li=document.createElement('li');li.textContent=clean(item.title,60);list.append(li);}if(!queue.length){const li=document.createElement('li');li.textContent=state.reportedAt?'暂无等待曲目':'等待QQ队列状态';list.append(li);}}
  }
  private capture(event: CaptureEvent) {
    const platform = platformOf(event), status = platform && this.captures.get(platform), now = Date.now();
    // Consume only fresh capture pushes; never status.events/logs/SYSTEM/manual simulation.
    if (!this.connected || !status || status.state !== 'live' || status.mode !== (platform === 'bilibili' ? 'bilibili-web' : 'lightweight') || !event.id || !event.userId || !event.roomId || event.roomId !== status.roomId || event.timestamp < now - 45_000 || event.timestamp > now + 10_000 || !['chat', 'gift'].includes(event.type)) return;
    const key = `${platform}:${event.roomId}:${event.id}`;
    if (this.seen.has(key)) return;
    this.seen.add(key); if (this.seen.size > 4096) this.seen.delete(this.seen.values().next().value!);
    const content = clean(event.content, 100), name = clean(event.nickname, 24);
    // Public chat is eligible unless it explicitly asks for privacy or exposes obvious secrets.
    if (/别记|不要记|不[要准]公开|不要显示|忘记|隐私|密码|cookie|token|身份证|住址|电话|1[3-9]\d{9}/i.test(content + name)) return;
    const message = event.type === 'gift' ? `${name || '观众'}送来${clean(event.giftName, 30) || '一份心意'}${event.giftCount > 0 ? ` ×${event.giftCount}` : ''}` : content ? `${name || '观众'}：${content}` : '';
    if (!message) return;
    text('night-recent-label', event.type === 'gift' ? '收到心意' : '柜台留言');
    text('night-recent', message); this.recentUntil = now + 120_000;
  }
  private renderAudience(audience?: CaptureAudience) {
    const display=nightAudienceDisplay(audience);
    text('night-chatters',display.count);
  }
  private renderFinance(finance?: NightFinanceValues) {
    const display = nightFinanceDisplay(finance);
    text('night-fish-balance', display.balance);
    text('night-display-cost', display.cost);
  }
  private clearRecent() { this.recentUntil = 0; text('night-recent-label', '小屋便签'); text('night-recent', '忙完了就来坐坐，聊两句。'); }
  private closeCaption() { this.taskId = ''; this.captionUntil = 0; this.voiceClosed = true; this.voiceUntil = 0; const el = document.querySelector<HTMLElement>('.night-caption'); if (el) el.hidden = true; }
  private tick() {
    if (this.voiceUntil && Date.now() > this.voiceUntil) { this.voiceUntil = 0; text('night-speech-phase', '主播 · 语音状态待确认'); }
    this.renderMusic(); this.renderRoom();
    if (this.recentUntil && Date.now() > this.recentUntil) this.clearRecent();
    if (this.captionUntil && Date.now() > this.captionUntil) this.closeCaption();
  }
}

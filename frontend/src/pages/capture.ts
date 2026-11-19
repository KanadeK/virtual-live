import { api, toast } from '../events/api';
import type { CaptureEvent, CapturePlatform, CaptureStatus } from '../events/protocol';
import { icon } from './icons';

const platforms: CapturePlatform[] = ['douyin', 'bilibili'];
const names: Record<CapturePlatform, string> = { douyin: '抖音', bilibili: 'B站' };
const loginHint = (platform: CapturePlatform) => `点「登录${names[platform]}并采集」，在独立临时 Chrome 窗口的官方页面扫码；登录成功后自动尝试连接，采集中状态以平台鉴权结果为准。`;

export function captureMarkup() {
  return `<section class="panel capture-panel" id="douyin-capture" aria-label="抖音实时采集">
    <div class="panel-heading"><h2>${icon('chat')} 直播实时采集</h2><span id="capture-mode" class="capture-mode">抖音 · Lightweight</span></div>
    <div class="capture-platforms" role="group" aria-label="采集平台"><button type="button" data-capture-platform="douyin" aria-pressed="true">抖音</button><button type="button" data-capture-platform="bilibili" aria-pressed="false">B站</button></div>
    <p id="capture-source" class="capture-source" role="status">正在确认当前采集来源…</p>
    <p id="capture-intro" class="capture-intro">连接抖音直播间，实时接收评论、礼物和观众进场。连接或登录新平台会停止旧来源，已入队的台词继续串行播报。</p>
    <form id="capture-form"><label for="capture-room">直播间链接 / 房间号</label>
      <div class="capture-connect-row"><input id="capture-room" required maxlength="300" placeholder="https://live.douyin.com/房间号" autocomplete="off" aria-describedby="capture-intro"></div>
      <div class="capture-actions"><button id="capture-login-button" class="primary-button" type="button">登录抖音并采集</button><button id="capture-connect" class="secondary-button" type="submit">连接采集</button><button id="capture-stop" class="secondary-button" type="button">停止</button><button id="capture-login-cancel" class="text-button" type="button" hidden>取消登录</button></div>
      <p id="capture-login-message" class="capture-login-message" role="status">${loginHint('douyin')}</p>
      <details class="capture-cookie"><summary>高级连接：使用已有 Cookie</summary><label id="capture-cookie-label" for="capture-cookie">抖音 Cookie 请求头</label><input id="capture-cookie" type="password" autocomplete="off" maxlength="16000" placeholder="仅用于本次连接，不会回显或保存"><p>可使用上方官方扫码登录。Cookie 仅瞬时提交给本次采集，提交后立即清空，断开后释放。</p></details>
    </form>
    <div class="capture-status-row"><span id="capture-state" class="status-chip">读取中</span><span id="capture-login">正在读取抖音状态</span></div>
    <p id="capture-message" class="capture-message" role="status">填写直播间后开始采集。</p>
    <p id="capture-error" class="capture-error" role="status" hidden></p>
    <div class="capture-metrics"><span id="capture-room-status">当前查看：抖音</span><span id="capture-attempts">重试 0 次</span><button id="capture-refresh" type="button" class="text-button">刷新状态</button></div>
    <div class="capture-toolbar"><span id="capture-counts">评论 0 · 礼物 0 · 进场 0</span><select id="capture-filter" aria-label="筛选采集消息"><option value="all">全部消息</option><option value="chat">评论弹幕</option><option value="gift">礼物</option><option value="member">观众进场</option></select><button id="capture-pause" type="button" class="text-button" aria-pressed="false">暂停滚动</button><button id="capture-clear" type="button" class="text-button">清空</button></div>
    <div id="capture-feed" class="capture-feed" role="log" aria-label="评论礼物滚动消息" aria-live="off" tabindex="0"><p class="capture-empty">等待直播间消息…</p></div>
    <p class="capture-footnote">最近 500 条 · 昵称为平台显示昵称 · 新弹幕自动生成回复 · 欢迎与礼物感谢直接使用语料 · 同观众 4 秒限播</p>
  </section>`;
}

interface PlatformView {
  rows: CaptureEvent[];
  status?: CaptureStatus;
  version: number;
  refreshing: boolean;
  refreshAgain: boolean;
  mutating: boolean;
  pendingRoute: string;
  loadError: string;
  operationError: string;
  roomDraft: string;
  retiredRooms: Set<string>;
  sequence: number;
}
const newView = (): PlatformView => ({ rows: [], version: 0, refreshing: false, refreshAgain: false, mutating: false, pendingRoute: '', loadError: '', operationError: '', roomDraft: '', retiredRooms: new Set(), sequence: 0 });
const views: Record<CapturePlatform, PlatformView> = { douyin: newView(), bilibili: newView() };
let selected: CapturePlatform = 'douyin';
let follow = true;
let requesting = false;
let sequence = 0;
const el = <T extends HTMLElement = HTMLElement>(id: string) => document.getElementById(id) as T | null;
const loggingIn = (status?: CaptureStatus) => ['opening', 'waiting'].includes(status?.loginState || '');
const collecting = (status?: CaptureStatus) => ['connecting', 'live', 'reconnecting'].includes(status?.state || '');
const platformOf = (data: { platform?: unknown }): CapturePlatform | undefined => data.platform === undefined ? 'douyin' : platforms.includes(data.platform as CapturePlatform) ? data.platform as CapturePlatform : undefined;

function renderSource() {
  const target = el('capture-source');
  if (!target) return;
  const active = platforms.filter(platform => collecting(views[platform].status) || loggingIn(views[platform].status));
  const platform = active.sort((a, b) => views[b].sequence - views[a].sequence)[0];
  target.dataset.activePlatform = platform || '';
  target.dataset.roomId = platform ? views[platform].status?.roomId || '' : '';
  if (requesting) { target.textContent = `正在处理${names[selected]}采集操作…`; return; }
  if (!platform) {
    target.textContent = platforms.some(key => !views[key].status || views[key].loadError) ? '采集来源状态尚未确认，请刷新状态。' : '当前没有活跃采集来源。连接或登录后开始接收消息。';
    return;
  }
  const view = views[platform];
  const viewing = platform !== selected ? `；下方正在查看${names[selected]}，切换查看不会停止采集。` : '';
  if (loggingIn(view.status) && !collecting(view.status)) {
    target.dataset.roomId = '';
    target.textContent = `正在登录${names[platform]} · 等待官方页面扫码，尚未建立本次采集连接。${viewing}`;
  } else {
    target.textContent = `当前来源：${names[platform]} · 房间 ${view.status!.roomId || '解析中'} · ${view.loadError ? '状态待确认' : view.status!.state === 'live' ? '采集中' : '连接中'}${loggingIn(view.status) ? '；官方登录同时进行中，成功后连接新目标。' : ''}${viewing}`;
  }
}

function renderRows() {
  const feed = el('capture-feed');
  if (!feed) return;
  const oldTop = feed.scrollTop;
  const rows = views[selected].rows;
  const filter = el<HTMLSelectElement>('capture-filter')?.value || 'all';
  const visible = rows.filter(row => filter === 'all' || row.type === filter);
  const fragment = document.createDocumentFragment();
  for (const row of visible) {
    const line = document.createElement('div'); line.className = `capture-line capture-${row.type}`;
    line.dataset.eventId = row.id; line.dataset.platform = selected; line.dataset.roomId = row.roomId;
    const time = document.createElement('time'); time.textContent = new Date(row.timestamp).toLocaleTimeString('zh-CN', { hour12: false }); time.dateTime = new Date(row.timestamp).toISOString();
    const source = document.createElement('span'); source.className = 'capture-origin'; source.textContent = `${names[selected]} · 房间 ${row.roomId}`;
    const kind = document.createElement('span'); kind.className = 'capture-kind'; kind.textContent = { chat: '弹幕', gift: '礼物', member: '进场' }[row.type];
    const name = document.createElement('b'); name.textContent = row.nickname || '未提供昵称'; name.title = `平台昵称 · 观众 ID：${row.userId || '未知'}`;
    const text = document.createElement('span'); text.className = 'capture-content';
    text.textContent = row.type === 'chat' ? row.content : row.type === 'gift' ? `送出 ${row.giftName} ×${row.giftCount}${row.comboTotal > row.giftCount ? `（连击累计 ${row.comboTotal}）` : ''}` : '进入直播间';
    line.append(time, source, kind, name, text); fragment.append(line);
  }
  if (!visible.length) { const empty = document.createElement('p'); empty.className = 'capture-empty'; empty.textContent = rows.length ? '没有符合筛选条件的消息' : `等待${names[selected]}直播间消息…`; fragment.append(empty); }
  feed.replaceChildren(fragment);
  feed.scrollTop = follow ? feed.scrollHeight : oldTop;
}

function renderStatus() {
  const state = el('capture-state');
  if (!state) return;
  const view = views[selected], next = view.status, name = names[selected];
  const waitingLogin = loggingIn(next) && !collecting(next);
  const pendingState = view.mutating ? ({ connect: '连接中', login: '等待登录', disconnect: '断开中' } as Record<string, string>)[view.pendingRoute] : '';
  state.textContent = pendingState || (view.loadError ? '状态待确认' : waitingLogin ? '等待登录' : next ? ({ stopped: '已停止', connecting: '连接中', live: '采集中', reconnecting: '重连中', closed: '未开播', error: '连接失败' })[next.state] || next.state : '读取中');
  state.dataset.state = pendingState || waitingLogin ? 'connecting' : view.loadError ? 'error' : next?.state || 'stopped';
  el('capture-message')!.textContent = view.operationError || view.loadError || next?.message || `填写${name}直播间后开始采集。`;
  el('capture-login')!.textContent = next?.hasLogin ? (selected === 'douyin' ? '已提供登录 Cookie' : '已取得登录态') : selected === 'douyin' ? '游客状态 · 礼物可能不可见' : '未登录 · 部分直播间需正常登录';
  el('capture-counts')!.textContent = `评论 ${next?.comments || 0} · 礼物 ${next?.gifts || 0} · 进场 ${next?.members || 0}`;
  el('capture-room-status')!.textContent = `当前查看：${name}${waitingLogin ? ' · 正在登录，尚未连接' : next?.roomId ? ` · 房间 ${next.roomId}` : ''}`;
  el('capture-attempts')!.textContent = `重试 ${next?.attempts || 0} 次`;
  const input = el<HTMLInputElement>('capture-room');
  if (input && !input.value && !view.roomDraft && next?.roomId) input.value = view.roomDraft = next.inputRoomId || next.roomId;
  const connect = el<HTMLButtonElement>('capture-connect')!;
  connect.disabled = requesting;
  connect.textContent = next?.state === 'error' || next?.state === 'closed' ? '重试连接' : '连接采集';
  el<HTMLButtonElement>('capture-stop')!.disabled = requesting || !next || (next.state === 'stopped' && !loggingIn(next));
  el<HTMLButtonElement>('capture-stop')!.title = `断开${name}采集并取消本平台登录`;
  el<HTMLButtonElement>('capture-login-button')!.disabled = requesting || loggingIn(next);
  el('capture-login-cancel')!.hidden = !loggingIn(next);
  el<HTMLButtonElement>('capture-login-cancel')!.disabled = requesting;
  el('capture-login-message')!.textContent = next?.state === 'live' && !loggingIn(next)
    ? `当前已连接${name}直播间，采集鉴权已成功。${next.hasLogin ? '本次采集已取得登录态。' : '当前为游客连接；平台要求登录时，请使用官方扫码登录。'}`
    : next?.loginMessage || loginHint(selected);
  el<HTMLButtonElement>('capture-clear')!.disabled = requesting;
  el<HTMLButtonElement>('capture-refresh')!.disabled = requesting || view.refreshing;
  document.querySelectorAll<HTMLButtonElement>('[data-capture-platform]').forEach(button => { button.disabled = requesting; button.setAttribute('aria-pressed', String(button.dataset.capturePlatform === selected)); });
  const error = el('capture-error')!;
  const reasons: Record<string, string> = { ROOM_OFFLINE: '直播间尚未开播，开播后可重试。', LOGIN_REQUIRED: '此直播间需要正常登录，请使用官方扫码登录。', ACCESS_DENIED: '平台拒绝访问，请确认账号有权进入此直播间。', RATE_LIMITED: '平台限制了请求频率，请稍后重试。', NETWORK_ERROR: '网络连接失败，请检查网络后重试。', AUTH_FAILED: '采集鉴权失败，请重新登录并确认直播间权限。', PROTOCOL_ERROR: '采集协议错误，请稍后重试。' };
  error.textContent = next?.requiresLogin ? '此直播间需要正常登录，请使用官方扫码登录。' : next?.errorCode ? reasons[next.errorCode] || '' : '';
  error.hidden = !error.textContent;
  renderSource();
}

function renderPlatform() {
  const name = names[selected];
  el('douyin-capture')!.dataset.platform = selected;
  el('capture-mode')!.textContent = `${name} · ${selected === 'douyin' ? 'Lightweight' : 'Web 采集'}`;
  el('capture-intro')!.textContent = selected === 'douyin' ? '连接抖音直播间，实时接收评论、礼物和观众进场。连接或登录新平台会停止旧来源，已入队的台词继续串行播报。' : '支持 B站房间号、短房间号或 live.bilibili.com 直播链接。部分直播间须在官方页面正常登录并具备访问权限。连接或登录会停止旧来源，已入队台词继续播报。';
  el<HTMLInputElement>('capture-room')!.placeholder = selected === 'douyin' ? 'https://live.douyin.com/房间号' : '房间号 / 短号 / https://live.bilibili.com/房间号';
  el<HTMLInputElement>('capture-room')!.value = views[selected].roomDraft;
  el('capture-login-button')!.textContent = `登录${name}并采集`;
  el('capture-cookie-label')!.textContent = `${name} Cookie 请求头`;
  renderStatus(); renderRows();
}

function applyStatus(platform: CapturePlatform, next: CaptureStatus, authoritative = false) {
  const view = views[platform];
  if (!authoritative && next.roomId && view.retiredRooms.has(next.roomId)) return;
  // A legitimate switch stops the previous source before it starts the next one.
  // A late "live" packet from the stopped peer cannot take ownership back.
  if (!authoritative && (collecting(next) || loggingIn(next)) && platforms.some(peer => peer !== platform && (collecting(views[peer].status) || loggingIn(views[peer].status)))) return;
  const oldRoom = view.status?.roomId;
  if (oldRoom && next.roomId && oldRoom !== next.roomId) {
    view.retiredRooms.add(oldRoom); view.retiredRooms.delete(next.roomId);
    view.rows = view.rows.filter(row => row.roomId === next.roomId);
  }
  view.status = next; view.loadError = ''; view.sequence = ++sequence;
  if (platform === selected) { renderStatus(); renderRows(); } else renderSource();
}

async function refreshPlatform(platform: CapturePlatform) {
  const view = views[platform];
  if (view.mutating) return;
  if (view.refreshing) { view.refreshAgain = true; return; }
  view.refreshing = true;
  const version = view.version;
  if (platform === selected) renderStatus();
  try {
    const next = await api<CaptureStatus>(`/${platform}/status`);
    // A platform mutation or SSE packet makes an older HTTP snapshot obsolete.
    if (version !== view.version || view.mutating || platformOf(next) !== platform) return;
    view.rows = (next.events || []).filter(row => platformOf(row) === platform && row.roomId === next.roomId).slice(-500);
    applyStatus(platform, next, true);
  } catch (error) {
    if (version !== view.version || view.mutating) return;
    view.loadError = `无法读取${names[platform]}采集状态：${error instanceof Error ? error.message : '请重试'}`;
  } finally {
    view.refreshing = false;
    if (platform === selected) renderStatus(); else renderSource();
    if (view.refreshAgain) { view.refreshAgain = false; void refreshPlatform(platform); }
  }
}

export async function refreshCapture() {
  if (!el('capture-feed')) return;
  await Promise.all(platforms.map(refreshPlatform));
}

export function receiveCapture(type: string, data: unknown) {
  if (!el('capture-feed') || !['CAPTURE_EVENT', 'CAPTURE_STATUS', 'CAPTURE_RESET'].includes(type) || !data || typeof data !== 'object') return;
  const platform = platformOf(data as { platform?: unknown });
  if (!platform) return;
  const view = views[platform];
  view.version++;
  if (view.mutating) return;
  if (type === 'CAPTURE_EVENT') {
    const row = data as CaptureEvent;
    if (!view.status || !collecting(view.status) || row.roomId !== view.status.roomId || !['chat', 'gift', 'member'].includes(row.type)) return;
    if (!view.rows.some(existing => existing.id === row.id && existing.roomId === row.roomId)) {
      view.rows.push(row); view.rows = view.rows.slice(-500);
      if (platform === selected) renderRows();
    }
  } else if (type === 'CAPTURE_STATUS') applyStatus(platform, data as CaptureStatus);
  else { view.rows = []; if (platform === selected) renderRows(); }
}

export function bindCapture() {
  const form = el<HTMLFormElement>('capture-form');
  if (!form) return;
  const post = async (route: string, body: unknown) => {
    if (requesting) return;
    const platform = selected, view = views[platform];
    requesting = true; view.mutating = true; view.pendingRoute = route; view.version++; view.operationError = '';
    renderStatus();
    try {
      const next = await api<CaptureStatus>(`/${platform}/${route}`, body);
      if (platformOf(next) !== platform) throw new Error('采集响应的平台不匹配，请刷新状态');
      view.mutating = false;
      view.rows = (next.events || []).filter(row => platformOf(row) === platform && row.roomId === next.roomId).slice(-500);
      applyStatus(platform, next, true);
    } catch (error) {
      view.operationError = error instanceof Error ? error.message : '采集操作失败';
      toast(view.operationError, true);
    } finally {
      view.mutating = false; view.pendingRoute = ''; requesting = false;
      renderStatus(); renderRows();
      await refreshCapture();
    }
  };
  form.onsubmit = event => {
    event.preventDefault();
    const cookie = el<HTMLInputElement>('capture-cookie')!;
    const body = { roomId: el<HTMLInputElement>('capture-room')!.value.trim(), cookie: cookie.value.trim() };
    cookie.value = '';
    void post('connect', body);
  };
  el<HTMLInputElement>('capture-room')!.oninput = event => { views[selected].roomDraft = (event.target as HTMLInputElement).value; };
  document.querySelectorAll<HTMLButtonElement>('[data-capture-platform]').forEach(button => {
    button.onclick = () => {
      const platform = button.dataset.capturePlatform as CapturePlatform;
      if (requesting || platform === selected) return;
      views[selected].roomDraft = el<HTMLInputElement>('capture-room')!.value;
      el<HTMLInputElement>('capture-cookie')!.value = '';
      selected = platform; renderPlatform(); void refreshPlatform(platform);
    };
  });
  el('capture-stop')!.onclick = () => void post('disconnect', {});
  el('capture-login-button')!.onclick = () => {
    el<HTMLInputElement>('capture-cookie')!.value = '';
    if (form.reportValidity()) void post('login', { roomId: el<HTMLInputElement>('capture-room')!.value.trim() });
  };
  el('capture-login-cancel')!.onclick = () => void post('login/cancel', {});
  el('capture-clear')!.onclick = () => void post('clear', {});
  el('capture-refresh')!.onclick = () => void refreshCapture();
  el('capture-pause')!.onclick = () => {
    follow = !follow;
    el('capture-pause')!.textContent = follow ? '暂停滚动' : '继续滚动';
    el('capture-pause')!.setAttribute('aria-pressed', String(!follow));
    if (follow) { const feed = el('capture-feed')!; feed.scrollTop = feed.scrollHeight; }
  };
  el('capture-filter')!.onchange = renderRows;
  renderPlatform(); void refreshCapture();
  window.setInterval(() => void refreshCapture(), 5000);
}

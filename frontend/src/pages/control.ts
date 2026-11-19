import { musicMarkup } from "../audio/MusicPlayer";
import { captureMarkup, bindCapture } from "./capture";
import { corpusMarkup, idleMarkup, bindCorpus } from "./workbenchCorpus";
import { cloudMarkup, bindCloud, refreshCloud } from "./workbenchCloud";
import { bindWorkbenchNavigation } from "./workbenchNavigation";
import { icon } from "./icons";
import { command, api, toast } from "../events/api";
import type { Action, AudioSettings, Emotion, EventType, RoomStatus } from "../events/protocol";

const actions: [Action, string, string][] = [
  ["idle", "待机", "☁"], ["wave", "挥手", "✋"], ["happy", "开心", "☺"],
  ["celebrate", "欢呼", "✧"], ["surprised", "惊讶", "!"], ["think", "思考", "?"],
  ["sad", "委屈", "⌁"], ["blink", "眨眼", "◡"], ["talk", "说话", "♬"],
];
const el = <T extends HTMLElement = HTMLElement>(id: string) => document.getElementById(id) as T;

function interactionMarkup() {
  return `<section class="panel" id="interaction"><div class="panel-heading"><h2>${icon("chat")} 互动模拟</h2><span class="mock-pill">模拟观众事件</span></div>
    <div class="event-tabs" role="tablist" aria-label="模拟互动类型">${[["COMMENT", "chat", "发弹幕"], ["USER_ENTER", "user", "观众进场"], ["GIFT", "gift", "送礼物"]].map(([type, glyph, text], i) => `<button type="button" id="event-tab-${type}" class="${i === 0 ? "selected" : ""}" data-event="${type}" role="tab" aria-selected="${i === 0}" aria-controls="event-form" tabindex="${i === 0 ? 0 : -1}">${icon(glyph)} ${text}</button>`).join("")}</div>
    <form id="event-form" role="tabpanel" aria-labelledby="event-tab-COMMENT"><label for="nickname">观众昵称</label><input id="nickname" maxlength="20" value="小明" required placeholder="给这位新朋友起个名字">
      <div id="comment-fields"><label for="comment">弹幕内容</label><textarea id="comment" maxlength="160" rows="2">小助手，你今天吃饭了吗？</textarea><div class="quick-comments"><button type="button" data-comment="小助手好可爱！">夸夸小助手</button><button type="button" data-comment="今天工作有点累">聊聊日常</button><button type="button" data-comment="可以跳个舞吗？">来个表演</button></div></div>
      <div id="gift-fields" hidden><label>选择礼物</label><div class="gift-options">${[["小心心", "heart"], ["小鱼干", "fish"], ["鲜花", "flower"], ["星星", "star"]].map(([name, glyph], index) => `<button type="button" class="gift-choice ${index === 0 ? "selected" : ""}" data-gift="${name}" aria-pressed="${index === 0}">${icon(glyph)}<span>${name}</span></button>`).join("")}</div><label for="gift-count">礼物数量</label><input type="number" id="gift-count" min="1" max="9999" value="10"></div>
      <div id="enter-fields" class="enter-note" hidden>${icon("spark")} 新朋友到来，小助手会挥手欢迎。</div>
      <div class="form-actions"><button type="button" class="secondary-button" id="like-button">${icon("heart")} 点赞 +1</button><button type="submit" class="primary-button" id="send-event">发送弹幕 ${icon("play")}</button></div>
    </form></section>`;
}
function characterMarkup() {
  return `<section class="panel character-panel" id="character"><div class="panel-heading"><h2>${icon("smile")} 角色表演</h2><span class="status-chip" id="character-state">自然待机</span></div><label>动作指令</label><div class="action-grid">${actions.map(([action, name, glyph]) => `<button type="button" data-action="${action}"><span aria-hidden="true">${glyph}</span>${name}</button>`).join("")}</div><label for="emotion">表情</label><select id="emotion"><option value="neutral">自然 · 元气营业中</option><option value="happy">开心 · 快乐冒泡</option><option value="surprised">惊讶 · 哇哦</option><option value="sad">委屈 · 需要抱抱</option><option value="think">思考 · 小脑袋转一转</option></select><p class="field-hint">动作发送到独立直播画面；播报中的低优先级动作受到保护。</p></section>`;
}
function naturalMarkup() {
  return `<section class="panel auto-panel"><div class="panel-heading"><h2>${icon("spark")} 自然互动</h2></div>
    <div class="natural-section"><div class="natural-heading"><h3>观众事件模拟</h3><span class="mock-pill">Mock</span></div><p>自动模拟进场、弹幕和礼物，用于调试直播互动。</p><div class="interval-row"><label for="min-interval">模拟间隔</label><input id="min-interval" type="number" min="5" max="120" value="5" aria-label="最小模拟间隔"><span>至</span><input id="max-interval" type="number" min="5" max="120" value="15" aria-label="最大模拟间隔"><span>秒</span></div><button type="button" class="auto-button" id="auto-toggle">${icon("play")} 开始模拟互动</button></div>
    ${idleMarkup()}</section>`;
}
function audioMarkup() {
  return `<section class="panel audio-panel" id="audio"><div class="panel-heading"><h2>${icon("volume")} 语音设置</h2><div class="audio-enable"><span>启用语音</span><label class="switch" aria-label="开启语音"><input type="checkbox" id="voice-enabled" checked><span></span></label></div></div>
    <div class="audio-settings-grid"><div><div class="range-label"><label for="speech-rate">语速</label><output id="rate-value">1.05×</output></div><input id="speech-rate" type="range" min="0.5" max="1.8" step="0.05" value="1.05"></div><div><div class="range-label"><label for="speech-pitch">音调</label><output id="pitch-value">1.25×</output></div><input id="speech-pitch" type="range" min="0.5" max="2" step="0.05" value="1.25"></div><div class="voice-setting"><div class="range-label"><label for="voice-id">音色 ID</label><output id="voice-default"></output></div><div class="voice-row"><input id="voice-id" maxlength="64" autocomplete="off" spellcheck="false" placeholder="留空使用云端默认音色"><button type="button" class="voice-save" id="voice-save">保存音色</button></div></div></div>
    <div class="audio-note" id="voice-notice">语速和音调调整后即时保存；音色保存后用于下一条播报。</div><p id="audio-feedback" class="inline-feedback" role="status" aria-live="polite"></p></section>`;
}
function queueMarkup() {
  return `<aside class="queue-column"><section class="panel queue-panel"><div class="panel-heading"><h2>${icon("clock")} 播报队列 <b id="queue-count" class="count-badge">0</b></h2><button type="button" class="text-button" id="clear-queue" title="清空等待队列">清空等待</button></div><div id="owner-hint" class="owner-hint">等待独立直播画面连接</div><div class="current-task-label"><span>当前任务</span><span id="speech-phase">等待互动</span></div><div id="current-speech" class="current-speech">小助手正在等待你的互动…</div><ol id="queue-list" class="queue-list"></ol><span class="queue-footnote">礼物优先 · 串行播报 · 最多 20 条等待任务</span></section><div class="queue-help">${icon("volume")}<p>在右上角打开直播，启用语音与音乐后开始播报。工作台只负责控制与查看。</p></div></aside>`;
}
export function controlMarkup() {
  const menu = [["capture", "radio", "直播实时采集"], ["debug", "grid", "调试台"], ["corpus", "book", "语料库配置"], ["cloud", "cloud", "云端链路"], ["history", "history", "互动记录"]];
  return `<div class="console-app"><aside class="rail"><a class="brand" href="/control"><span class="brand-mark">${icon("fish")}</span><span><strong>Virtual Live</strong><small>直播工作台</small></span></a><nav role="tablist" aria-label="工作台菜单" aria-orientation="vertical">${menu.map(([page, glyph, title]) => `<button type="button" class="rail-item" role="tab" id="workspace-tab-${page}" data-workspace="${page}" ${page === "capture" ? 'aria-label="抖音实时采集" title="直播实时采集：抖音 / B站"' : ''} aria-controls="workspace-${page}" aria-selected="false" tabindex="-1">${icon(glyph)}<span>${title}</span></button>`).join("")}</nav><div class="rail-bottom">${icon("fish")}<span>让可爱，持续发生。</span></div></aside>
    <main class="console-main"><header class="console-header"><span class="breadcrumb">直播工作台</span><div class="header-right"><span id="connection" class="connection" role="status"><i></i>连接中</span><a class="primary-button live-shortcut" href="/live" target="_blank" rel="noopener">${icon("link")} 打开直播</a></div></header>
      <div class="workspace-heading"><h1 id="workspace-title">直播实时采集</h1><p id="workspace-description">连接抖音或 B站直播间，查看实时互动与播报进度。</p></div>
      <div class="workbench-content" id="workbench-content" tabindex="0" aria-label="工作区内容">
        <section id="workspace-capture" class="workspace-panel" data-workspace-panel="capture" role="tabpanel" aria-labelledby="workspace-tab-capture" hidden><div class="capture-layout">${captureMarkup()}${queueMarkup()}</div></section>
        <section id="workspace-debug" class="workspace-panel" data-workspace-panel="debug" role="tabpanel" aria-labelledby="workspace-tab-debug" hidden><div class="debug-grid">${interactionMarkup()}${characterMarkup()}<section class="panel manual-panel"><div class="panel-heading"><h2>${icon("volume")} 让小助手说</h2><span class="subtle-label">手动播报</span></div><p class="panel-intro">输入台词，直接转语音并加入播报队列。</p><form id="manual-form"><label class="sr-only" for="manual-text">主播台词</label><textarea id="manual-text" maxlength="160" rows="5" required placeholder="输入一句想让小助手说的话…"></textarea><div class="form-actions"><span class="field-hint">最多 160 字</span><button class="primary-button" type="submit">${icon("play")} 加入播报</button></div></form></section>${naturalMarkup()}</div>${musicMarkup()}</section>
        <section id="workspace-corpus" class="workspace-panel" data-workspace-panel="corpus" role="tabpanel" aria-labelledby="workspace-tab-corpus" hidden>${audioMarkup()}${corpusMarkup()}</section>
        <section id="workspace-cloud" class="workspace-panel" data-workspace-panel="cloud" role="tabpanel" aria-labelledby="workspace-tab-cloud" hidden>${cloudMarkup()}</section>
        <section id="workspace-history" class="workspace-panel" data-workspace-panel="history" role="tabpanel" aria-labelledby="workspace-tab-history" hidden><section class="panel log-panel"><div class="panel-heading"><h2>${icon("history")} 互动记录</h2><span id="log-count" class="live-indicator">实时同步</span></div><div id="event-logs" class="event-logs" role="log" aria-label="互动记录" aria-live="off"><div class="empty-logs">${icon("chat")}<p>还没有互动记录</p><span>连接抖音或 B站采集，或前往调试台发送一次互动。</span></div></div></section></section>
        <footer class="console-footer"><span>${icon("fish")} Virtual Live</span><span><span id="footer-ai-provider">AI · Mock</span> / <span id="footer-tts-provider">TTS · Browser</span></span></footer>
      </div></main></div>`;
}

let autoRunning = false, lastLogSignature = "", savedVoice = "", voiceDirty = false;
let settingsVersion = 0;
let confirmedVoice = "", audioSavesPending = 0;
let audioSaveQueue: Promise<void> = Promise.resolve();
export function bindControl() {
  bindCapture(); bindCorpus(); bindCloud();
  bindWorkbenchNavigation(page => {
    if (page === "cloud") void refreshCloud();
    if (page === "capture") {
      el("workspace-title").textContent = "直播实时采集";
      el("workspace-description").textContent = "连接抖音或 B站直播间，查看实时互动与播报进度。";
      document.title = "直播实时采集 · Virtual Live";
    }
  });
  let eventType: EventType = "COMMENT", gift = "小心心";
  const eventTabs = Array.from(document.querySelectorAll<HTMLButtonElement>("[data-event]"));
  eventTabs.forEach((button, index) => {
    button.onclick = () => {
      eventType = button.dataset.event as EventType;
      eventTabs.forEach(tab => { const active = tab === button; tab.classList.toggle("selected", active); tab.setAttribute("aria-selected", String(active)); tab.tabIndex = active ? 0 : -1; });
      el("comment-fields").hidden = eventType !== "COMMENT"; el("gift-fields").hidden = eventType !== "GIFT"; el("enter-fields").hidden = eventType !== "USER_ENTER";
      el<HTMLTextAreaElement>("comment").required = eventType === "COMMENT";
      el<HTMLInputElement>("gift-count").disabled = eventType !== "GIFT";
      el("event-form").setAttribute("aria-labelledby", button.id);
      el("send-event").innerHTML = (eventType === "COMMENT" ? "发送弹幕" : eventType === "GIFT" ? "送出礼物" : "欢迎进场") + icon("play");
    };
    button.onkeydown = event => {
      const next = event.key === "ArrowRight" ? (index + 1) % eventTabs.length : event.key === "ArrowLeft" ? (index + eventTabs.length - 1) % eventTabs.length : event.key === "Home" ? 0 : event.key === "End" ? eventTabs.length - 1 : -1;
      if (next >= 0) { event.preventDefault(); eventTabs[next].focus(); eventTabs[next].click(); }
    };
  });
  eventTabs[0].click();
  document.querySelectorAll<HTMLButtonElement>("[data-gift]").forEach(button => { button.onclick = () => { gift = button.dataset.gift!; document.querySelectorAll<HTMLButtonElement>("[data-gift]").forEach(choice => { const active = choice === button; choice.classList.toggle("selected", active); choice.setAttribute("aria-pressed", String(active)); }); }; });
  document.querySelectorAll<HTMLButtonElement>("[data-comment]").forEach(button => { button.onclick = () => { el<HTMLTextAreaElement>("comment").value = button.dataset.comment!; }; });
  el<HTMLFormElement>("event-form").onsubmit = async event => {
    event.preventDefault();
    const nickname = el<HTMLInputElement>("nickname").value.trim();
    if (!nickname) { toast("请输入观众昵称", true); return; }
    const content = el<HTMLTextAreaElement>("comment").value.trim();
    if (eventType === "COMMENT" && !content) { toast("请输入弹幕内容", true); return; }
    const button = el<HTMLButtonElement>("send-event"); button.disabled = true;
    try {
      const result = await command("/demo/event", { eventId: crypto.randomUUID(), type: eventType, userId: `demo-${nickname}`, nickname, content, giftName: gift, giftCount: eventType === "GIFT" ? Number(el<HTMLInputElement>("gift-count").value) : 0, timestamp: Date.now() });
      if (result) toast(result.accepted ? "互动已发送" : result.reason === "rate_limited" ? "这位观众发送过快，请等 4 秒" : "事件已处理");
    } finally { button.disabled = false; }
  };
  el("like-button").onclick = () => void command("/demo/event", { eventId: crypto.randomUUID(), type: "LIKE", nickname: el<HTMLInputElement>("nickname").value.trim() || "小明", giftCount: 0, timestamp: Date.now() }, "小爱心 +1");
  document.querySelectorAll<HTMLButtonElement>("button[data-action]").forEach(button => { button.onclick = () => void command("/character/action", { action: button.dataset.action }, "动作指令已发送"); });
  el<HTMLSelectElement>("emotion").onchange = event => void command("/character/action", { emotion: (event.target as HTMLSelectElement).value });
  el<HTMLFormElement>("manual-form").onsubmit = async event => {
    event.preventDefault(); const input = el<HTMLTextAreaElement>("manual-text");
    if (!input.value.trim()) { toast("请输入台词", true); return; }
    const button = el<HTMLFormElement>("manual-form").querySelector<HTMLButtonElement>("button")!; button.disabled = true;
    try { if (await command("/speech/manual", { text: input.value.trim() }, "台词已加入播报队列")) input.value = ""; }
    finally { button.disabled = false; }
  };
  el("clear-queue").onclick = () => void command("/speech/clear", {}, "等待队列已清空，当前台词继续播报");
  el("auto-toggle").onclick = async () => {
    const min = Number(el<HTMLInputElement>("min-interval").value), max = Number(el<HTMLInputElement>("max-interval").value);
    if (!autoRunning && (!Number.isFinite(min) || !Number.isFinite(max) || min < 5 || max < min || max > 120)) { toast("请输入 5～120 秒的有效间隔，最大值不能小于最小值", true); return; }
    const button = el<HTMLButtonElement>("auto-toggle"); button.disabled = true;
    try { const result = await command(`/demo/auto/${autoRunning ? "stop" : "start"}`, autoRunning ? {} : { minSeconds: min, maxSeconds: max }); if (result) setAuto(Boolean(result.running)); }
    finally { button.disabled = false; }
  };
  const saveAudio = async (voice = savedVoice, savingVoice = false) => {
    // Keep an explicitly saved voice when the operator immediately adjusts another setting.
    if (savingVoice) savedVoice = voice;
    const settings: AudioSettings = { enabled: el<HTMLInputElement>("voice-enabled").checked, rate: Number(el<HTMLInputElement>("speech-rate").value), pitch: Number(el<HTMLInputElement>("speech-pitch").value), voice };
    const version = ++settingsVersion;
    audioSavesPending++;
    el("audio-feedback").textContent = "保存中…";
    audioSaveQueue = audioSaveQueue.then(async () => {
      try {
        await api("/audio/settings", settings);
        confirmedVoice = voice;
        if (savingVoice && el<HTMLInputElement>("voice-id").value.trim() === voice) voiceDirty = false;
        if (version !== settingsVersion) return;
        savedVoice = voice;
        el("audio-feedback").textContent = "语音设置已保存，下一条播报生效"; el("audio-feedback").classList.remove("bad");
      } catch (error) {
        if (version !== settingsVersion) return;
        savedVoice = confirmedVoice;
        el("audio-feedback").textContent = error instanceof Error ? error.message : "保存失败，请重试"; el("audio-feedback").classList.add("bad");
      } finally { audioSavesPending--; }
    });
    await audioSaveQueue;
  };
  const saveVoice = () => {
    const voice = el<HTMLInputElement>("voice-id").value.trim();
    if (!/^[a-zA-Z0-9_-]{0,64}$/.test(voice)) { toast("音色 ID 仅支持字母、数字、下划线和中划线，最长 64 位", true); return; }
    void saveAudio(voice, true);
  };
  el("voice-save").onclick = saveVoice;
  el<HTMLInputElement>("voice-id").oninput = () => { voiceDirty = true; el("audio-feedback").textContent = "音色尚未保存"; };
  el<HTMLInputElement>("voice-id").onkeydown = event => { if (event.key === "Enter") { event.preventDefault(); saveVoice(); } };
  for (const [id, output] of [["speech-rate", "rate-value"], ["speech-pitch", "pitch-value"]]) {
    el<HTMLInputElement>(id).oninput = event => { el(output).textContent = Number((event.target as HTMLInputElement).value).toFixed(2) + "×"; };
    el(id).onchange = () => void saveAudio();
  }
  el("voice-enabled").onchange = () => void saveAudio();
  const refresh = () => void api<RoomStatus>("/status").then(updateControl).catch(() => {});
  refresh(); window.setInterval(refresh, 5000);
}
function setAuto(running: boolean) {
  autoRunning = running;
  const button = document.getElementById("auto-toggle");
  if (button) { button.innerHTML = icon(running ? "pause" : "play") + (running ? "暂停模拟互动" : "开始模拟互动"); button.classList.toggle("running", running); button.setAttribute("aria-pressed", String(running)); }
}
export function updateCharacterState(action?: Action, emotion?: Emotion) {
  const target = document.getElementById("character-state"); if (!target) return;
  const names: Partial<Record<Action, string>> = { idle: "自然待机", talk: "正在说话", wave: "挥手欢迎", celebrate: "礼物欢呼", happy: "开心", surprised: "惊讶", think: "思考", sad: "委屈", blink: "眨眼" };
  target.textContent = action ? names[action] || "自然待机" : emotion ? ({ neutral: "自然", happy: "开心", surprised: "惊讶", sad: "委屈", think: "思考" })[emotion] : "自然待机";
}
export function updateControl(status: RoomStatus) {
  updateProviders(status);
  if (!document.getElementById("queue-count")) return;
  el("queue-count").textContent = String(status.queueSize);
  el("owner-hint").textContent = status.ownerConnected ? "主播画面已连接，语音由 /live 播放" : "等待 /live 连接，队列暂存";
  el("owner-hint").classList.toggle("connected", status.ownerConnected);
  const preparing = status.phase === "PREPARING_AI";
  el("speech-phase").textContent = preparing ? "正在生成回复" : status.current.text ? "正在播报" : "等待互动";
  el("current-speech").textContent = preparing ? "DeepSeek 正在生成短回复…" : status.current.text || "小助手正在等待你的互动…";
  el("current-speech").classList.toggle("speaking", !!status.current.text || preparing);
  const queue = el("queue-list"), fragment = document.createDocumentFragment();
  for (const item of status.queue) {
    const row = document.createElement("li"), label = document.createElement("span"), text = document.createElement("p");
    label.textContent = ["礼物", "手动", "弹幕", "欢迎", "空闲"][item.priority] || "台词";
    if (item.priority === 0) label.className = "high";
    text.textContent = item.text; row.append(label, text); fragment.append(row);
  }
  if (!status.queue.length) { const empty = document.createElement("li"); empty.className = "queue-empty"; empty.textContent = "没有等待播报的台词"; fragment.append(empty); }
  queue.replaceChildren(fragment);
  const entries = status.logs.filter(entry => entry.type !== "DONE");
  const signature = entries.map(entry => entry.id).join(",");
  el("log-count").textContent = entries.length ? `最近 ${entries.length} 条` : "实时同步";
  if (signature !== lastLogSignature) {
    lastLogSignature = signature;
    const logs = el("event-logs"); logs.replaceChildren();
    for (const entry of entries) {
      const row = document.createElement("div"); row.className = "log-row";
      const marker = document.createElement("span"); marker.className = "log-marker";
      if (["GIFT", "COMMENT", "USER_ENTER"].includes(entry.type)) marker.classList.add(entry.type.toLowerCase());
      marker.innerHTML = icon(entry.type === "GIFT" ? "gift" : entry.type === "USER_ENTER" ? "user" : entry.type === "COMMENT" ? "chat" : "clock");
      const body = document.createElement("div"), text = document.createElement("p"), time = document.createElement("time");
      text.textContent = entry.text; time.dateTime = new Date(entry.timestamp).toISOString(); time.textContent = new Date(entry.timestamp).toLocaleTimeString("zh-CN", { hour12: false });
      body.append(text, time); row.append(marker, body); logs.append(row);
    }
    if (!entries.length) logs.innerHTML = `<div class="empty-logs">${icon("chat")}<p>还没有互动记录</p><span>连接抖音或 B站采集，或前往调试台发送一次互动。</span></div>`;
  }
  if (status.autoRunning !== undefined) setAuto(status.autoRunning);
  if (status.current.text) updateCharacterState(status.current.action || "talk");
  else if (status.phase === "IDLE") updateCharacterState("idle");
  if (audioSavesPending) return;
  savedVoice = status.audio.voice ?? "";
  confirmedVoice = savedVoice;
  for (const [id, value] of [["voice-enabled", status.audio.enabled], ["speech-rate", status.audio.rate], ["speech-pitch", status.audio.pitch], ["voice-id", savedVoice]] as const) {
    const input = el<HTMLInputElement>(id);
    if (input === document.activeElement || (id === "voice-id" && voiceDirty)) continue;
    if (typeof value === "boolean") input.checked = value; else input.value = String(value);
  }
  if (document.activeElement !== el("speech-rate")) el("rate-value").textContent = status.audio.rate.toFixed(2) + "×";
  if (document.activeElement !== el("speech-pitch")) el("pitch-value").textContent = status.audio.pitch.toFixed(2) + "×";
}
export function updateProviders(status: RoomStatus) {
  const ai = status.aiStatus, tts = status.ttsStatus, ttsFailed = !!tts?.lastError;
  const set = (id: string, text: string) => { const target = document.getElementById(id); if (target) target.textContent = text; };
  set("provider-ai", status.aiProvider === "deepseek" ? `DeepSeek · ${ai?.model || "已连接"}` : "本地 Mock");
  set("provider-tts", ttsFailed ? "豆包受阻 · 浏览器回退" : tts?.provider === "doubao" ? "豆包语音 2.0" : tts?.configurationIssue ? "待补配置 · 浏览器回退" : "浏览器语音");
  set("footer-ai-provider", status.aiProvider === "deepseek" ? "AI · DeepSeek" : "AI · Mock");
  set("footer-tts-provider", tts?.provider === "doubao" && !ttsFailed ? "TTS · 豆包" : "TTS · 浏览器");
  const issues: string[] = [];
  if (tts?.configurationIssue === "DOUBAO_APP_ID_REQUIRED") issues.push("豆包缺少同一应用的 App ID，未发送合成请求。");
  else if (tts?.configurationIssue) issues.push("豆包鉴权配置不完整，当前使用浏览器语音。");
  if (ai?.lastFallback) issues.push(`AI 最近回退：${ai.lastFallback}`);
  if (tts?.lastError === "DOUBAO_HTTP_403") issues.push("豆包返回 403，请确认账户可访问语音合成 2.0 的 seed-tts-2.0 资源。当前回退浏览器语音。");
  else if (tts?.lastError) issues.push(`语音最近回退：${tts.lastError}`);
  const issue = document.getElementById("provider-issue"); if (issue) { issue.textContent = issues.join(" "); issue.hidden = !issues.length; }
  if (status.usage) {
    const usage = status.usage, counters = usage.counters;
    set("usage-date", `${usage.date} · 上海日期`);
    set("ai-usage", `DeepSeek 今日请求：${counters.aiRequests || 0} / ${usage.aiDailyRequestLimit}`);
    set("tts-usage", `TTS 今日请求字符：${counters.ttsRequestedCharacters || 0} / ${usage.ttsDailyCharLimit}`);
    set("cache-usage", `缓存命中：AI ${counters.aiCacheHits || 0} · 语音 ${counters.ttsCacheHits || 0}；实际 Token ${usage.aiTotalTokens ?? (counters.promptTokens || 0) + (counters.completionTokens || 0)}`);
  }
  set("auto-cost-note", status.paidAutoEnabled ? "自动模拟已允许云端调用，受本机每日上限保护。" : "自动模拟使用本地回复与浏览器语音，不消耗云端额度。");
  if (ttsFailed) set("voice-notice", "豆包调用受阻，已回退浏览器语音 · 估算嘴型");
  else if (tts?.provider === "doubao") set("voice-notice", `${tts.lastSuccessAt ? "豆包语音 2.0 · 音量驱动嘴型" : "豆包语音 2.0 已配置，等待首次播报"}；语速与音调即时保存，音色保存后生效。`);
  else set("voice-notice", "当前使用浏览器语音；语速和音调即时保存，音色 ID 用于豆包语音。");
  set("voice-default", tts?.voice ? `默认 ${tts.voice}` : "");
}

import { api } from "../events/api";
import type { CorpusItem, CorpusState, CorpusType, IdleSettings } from "../events/protocol";
import { icon } from "./icons";

const categories: Record<CorpusType, { name: string; placeholders: string[]; hint: string; example: string }> = {
  welcome: { name: "随机欢迎语", placeholders: ["nickname", "nicknames", "count"], hint: "{nickname} 或 {nicknames} 是合并后的昵称，{count} 是进场人数。多条启用时随机使用。", example: "欢迎 {nickname}，进来陪小助手聊聊天呀！" },
  idle: { name: "空闲语料", placeholders: [], hint: "空闲语料不支持占位符。仅在自然互动开启、近期有真人互动且播报空闲时使用。", example: "今天大家过得怎么样？来和小助手聊聊吧。" },
  gift: { name: "礼物感谢语", placeholders: ["nickname", "giftName", "giftCount"], hint: "{nickname} 是送礼观众，{giftName} 是礼物名称，{giftCount} 是合并后的净增数量。", example: "谢谢 {nickname} 送来的 {giftCount} 个 {giftName}，小助手收到啦！" },
};
const el = <T extends HTMLElement = HTMLElement>(id: string) => document.getElementById(id) as T;
let state: CorpusState | undefined;
let selected: CorpusType = "welcome";
let idleDirty = false;
let loading = false;
const drafts = new Map<string, Pick<CorpusItem, "text" | "enabled">>();
const pending = new Set<string>();

export function corpusMarkup() {
  return `<section class="panel corpus-panel" aria-labelledby="corpus-heading">
    <div class="panel-heading"><h2 id="corpus-heading">${icon("book")} 播报语料</h2><span class="status-chip">直接 TTS</span></div>
    <p class="panel-intro">这些台词直接转成语音，不经过 AI。新增、修改和启停保存后用于下一次播报。</p>
    <div class="corpus-tabs" role="tablist" aria-label="语料分类">${(Object.keys(categories) as CorpusType[]).map((type, i) => `<button type="button" role="tab" id="corpus-tab-${type}" data-corpus-type="${type}" aria-controls="corpus-content" aria-selected="${i === 0}" tabindex="${i === 0 ? 0 : -1}" class="${i === 0 ? "selected" : ""}">${categories[type].name}<span data-corpus-count="${type}">0</span></button>`).join("")}</div>
    <div id="corpus-content" role="tabpanel" aria-labelledby="corpus-tab-welcome">
      <p id="corpus-hint" class="placeholder-hint"></p>
      <form id="corpus-add-form" class="corpus-add-form"><label for="corpus-new-text">新增语料</label><textarea id="corpus-new-text" maxlength="160" rows="2" required></textarea><div class="form-actions"><span class="field-hint">1～160 字 · 每类最多 100 条</span><button type="submit" class="primary-button" id="corpus-add">${icon("plus")} 添加语料</button></div></form>
      <div class="list-heading"><span id="corpus-list-summary">正在读取语料…</span><button type="button" class="text-button" id="corpus-refresh">刷新语料</button></div>
      <p id="corpus-feedback" class="inline-feedback" role="status" aria-live="polite"></p>
      <div id="corpus-list" class="corpus-list"></div>
    </div>
  </section>`;
}

export function idleMarkup() {
  return `<div class="natural-section idle-section"><div class="natural-heading"><h3>空闲台词播报</h3><span id="idle-status" class="status-chip">读取中</span></div>
    <p>使用语料库中的空闲台词直接转语音。仅在主播在线、语音开启、队列为空且近期有真人互动时触发。</p>
    <form id="idle-form"><div class="idle-fields"><div><label for="idle-interval">播报间隔（秒）</label><input type="number" id="idle-interval" min="30" max="3600" value="120" required></div><div><label for="idle-activity">真人互动窗口（秒）</label><input type="number" id="idle-activity" min="30" max="3600" value="300" required></div></div>
      <div class="form-actions"><label class="check-label" for="idle-enabled"><input type="checkbox" id="idle-enabled">启用空闲播报</label><button type="submit" id="idle-save" class="secondary-button">保存设置</button></div>
      <p id="idle-feedback" class="inline-feedback" role="status" aria-live="polite">无近期真人互动时自动停止；开启不会制造观众活动。</p>
    </form></div>`;
}

function feedback(id: string, text: string, bad = false) {
  const target = el(id);
  target.textContent = text;
  target.classList.toggle("bad", bad);
}
function validate(text: string, type: CorpusType) {
  if (!text.trim()) return "请输入语料内容";
  if (text.length > 160) return "语料最多 160 字";
  const unknown = [...text.matchAll(/\{([^{}]+)\}/g)].map(match => match[1]).filter(name => !categories[type].placeholders.includes(name));
  return unknown.length ? `不支持占位符 {${unknown[0]}}，请参考上方提示` : "";
}
function applyState(next: CorpusState) {
  if (state && next.revision < state.revision) return;
  state = next;
  (Object.keys(categories) as CorpusType[]).forEach(type => {
    const count = document.querySelector<HTMLElement>(`[data-corpus-count="${type}"]`);
    if (count) count.textContent = String(next.items.filter(item => item.type === type).length);
  });
  if (!idleDirty) {
    el<HTMLInputElement>("idle-enabled").checked = next.idle.enabled;
    el<HTMLInputElement>("idle-interval").value = String(next.idle.intervalSeconds);
    el<HTMLInputElement>("idle-activity").value = String(next.idle.requireRecentActivitySeconds);
  }
  el("idle-status").textContent = next.idle.enabled ? "已启用" : "已关闭";
  el("idle-status").classList.toggle("enabled", next.idle.enabled);
  renderList();
}
function renderList() {
  const focused = document.activeElement as HTMLElement | null;
  const focusRow = focused?.closest<HTMLElement>("[data-corpus-id]")?.dataset.corpusId;
  const focusSelector = focused instanceof HTMLTextAreaElement ? "textarea" : focused instanceof HTMLInputElement ? "input" : focused?.classList.contains("danger-text") ? ".danger-text" : 'button[type="submit"]';
  const selection = focused instanceof HTMLTextAreaElement ? [focused.selectionStart, focused.selectionEnd] : undefined;
  el("corpus-hint").textContent = categories[selected].hint;
  el<HTMLTextAreaElement>("corpus-new-text").placeholder = categories[selected].example;
  el("corpus-content").setAttribute("aria-labelledby", `corpus-tab-${selected}`);
  if (!state) return;
  const items = state.items.filter(item => item.type === selected);
  const active = items.filter(item => item.enabled).length;
  el("corpus-list-summary").textContent = `${categories[selected].name} ${items.length} 条，已启用 ${active} 条`;
  const fragment = document.createDocumentFragment();
  for (const item of items) {
    const draft = drafts.get(item.id) ?? item;
    const form = document.createElement("form");
    form.className = "corpus-row";
    form.dataset.corpusId = item.id;
    const textarea = document.createElement("textarea");
    textarea.rows = 2; textarea.maxLength = 160; textarea.required = true;
    textarea.value = draft.text;
    textarea.setAttribute("aria-label", `${categories[selected].name}内容`);
    const actions = document.createElement("div"); actions.className = "corpus-row-actions";
    const label = document.createElement("label"); label.className = "check-label";
    const checkbox = document.createElement("input"); checkbox.type = "checkbox"; checkbox.checked = draft.enabled;
    label.append(checkbox, document.createTextNode("启用"));
    const message = document.createElement("span"); message.className = "row-feedback"; message.setAttribute("role", "status");
    message.textContent = drafts.has(item.id) ? "有未保存修改" : "已保存";
    const save = document.createElement("button"); save.type = "submit"; save.className = "secondary-button"; save.textContent = "保存修改";
    const remove = document.createElement("button"); remove.type = "button"; remove.className = "text-button danger-text"; remove.textContent = "删除";
    remove.setAttribute("aria-label", `删除语料：${item.text}`);
    const captureDraft = () => {
      drafts.set(item.id, { text: textarea.value, enabled: checkbox.checked });
      message.textContent = "有未保存修改"; message.classList.remove("bad");
    };
    textarea.oninput = captureDraft;
    checkbox.onchange = () => { captureDraft(); void saveItem(); };
    const busy = (value: boolean) => { textarea.disabled = checkbox.disabled = save.disabled = remove.disabled = value; };
    const saveItem = async () => {
      if (pending.has(item.id)) return;
      const restoreFocus = form.contains(document.activeElement);
      const checkboxFocus = document.activeElement === checkbox;
      const body = { text: textarea.value.trim(), enabled: checkbox.checked };
      const problem = validate(body.text, item.type);
      if (problem) { message.textContent = problem; message.classList.add("bad"); return; }
      pending.add(item.id); busy(true); message.textContent = "保存中…";
      try {
        const next = await api<CorpusState>(`/corpus/${encodeURIComponent(item.id)}`, body, "PUT");
        drafts.delete(item.id); pending.delete(item.id); applyState(next);
        feedback("corpus-feedback", "修改已保存，下一次播报立即生效");
      } catch (error) { feedback("corpus-feedback", error instanceof Error ? error.message : "保存失败，请重试", true); }
      finally {
        pending.delete(item.id); busy(false); renderList();
        if (restoreFocus && document.activeElement === document.body && !el("workspace-corpus").hidden) {
          const row = document.querySelector<HTMLElement>(`[data-corpus-id="${CSS.escape(item.id)}"]`);
          row?.querySelector<HTMLElement>(checkboxFocus ? "input" : 'button[type="submit"]')?.focus({ preventScroll: true });
        }
      }
    };
    form.onsubmit = event => { event.preventDefault(); void saveItem(); };
    remove.onclick = async () => {
      if (pending.has(item.id)) return;
      pending.add(item.id); busy(true);
      try {
        const next = await api<CorpusState>(`/corpus/${encodeURIComponent(item.id)}`, undefined, "DELETE");
        drafts.delete(item.id); pending.delete(item.id); applyState(next);
        feedback("corpus-feedback", "语料已删除");
      } catch (error) { feedback("corpus-feedback", error instanceof Error ? error.message : "删除失败，请重试", true); }
      finally { pending.delete(item.id); busy(false); renderList(); }
    };
    if (pending.has(item.id)) busy(true);
    actions.append(label, message, save, remove); form.append(textarea, actions); fragment.append(form);
  }
  if (!items.length) {
    const empty = document.createElement("div"); empty.className = "list-empty";
    empty.textContent = selected === "idle" ? "还没有空闲语料，添加后可在调试台开启空闲播报。" : "还没有自定义语料，当前使用内置保底台词。添加一条开始配置。";
    fragment.append(empty);
  }
  el("corpus-list").replaceChildren(fragment);
  if (focusRow && focused && !focused.isConnected && document.activeElement === document.body) {
    const target = document.querySelector<HTMLElement>(`[data-corpus-id="${CSS.escape(focusRow)}"]`)?.querySelector<HTMLElement>(focusSelector);
    target?.focus({ preventScroll: true });
    if (target instanceof HTMLTextAreaElement && selection) target.setSelectionRange(selection[0], selection[1]);
  }
}
export async function refreshCorpus() {
  if (loading) return;
  loading = true;
  el<HTMLButtonElement>("corpus-refresh").disabled = true;
  try { applyState(await api<CorpusState>("/corpus")); feedback("corpus-feedback", "语料已同步"); }
  catch (error) {
    const message = error instanceof Error ? error.message : "读取语料失败";
    feedback("corpus-feedback", `${message}，可点击刷新重试`, true);
    if (!state) { el("corpus-list-summary").textContent = "语料暂时不可用"; el("idle-status").textContent = "未连接"; }
  } finally { loading = false; el<HTMLButtonElement>("corpus-refresh").disabled = false; }
}
export function bindCorpus() {
  const tabs = Array.from(document.querySelectorAll<HTMLButtonElement>("[data-corpus-type]"));
  tabs.forEach((tab, index) => {
    tab.onclick = () => {
      selected = tab.dataset.corpusType as CorpusType;
      tabs.forEach(button => { const active = button === tab; button.classList.toggle("selected", active); button.setAttribute("aria-selected", String(active)); button.tabIndex = active ? 0 : -1; });
      feedback("corpus-feedback", ""); renderList();
    };
    tab.onkeydown = event => {
      const next = event.key === "ArrowRight" ? (index + 1) % tabs.length : event.key === "ArrowLeft" ? (index + tabs.length - 1) % tabs.length : event.key === "Home" ? 0 : event.key === "End" ? tabs.length - 1 : -1;
      if (next < 0) return;
      event.preventDefault(); tabs[next].focus(); tabs[next].click();
    };
  });
  el<HTMLFormElement>("corpus-add-form").onsubmit = async event => {
    event.preventDefault();
    const input = el<HTMLTextAreaElement>("corpus-new-text"), text = input.value.trim(), type = selected;
    const problem = validate(text, type);
    if (problem) { feedback("corpus-feedback", problem, true); return; }
    el<HTMLButtonElement>("corpus-add").disabled = true;
    feedback("corpus-feedback", "正在添加…");
    try { applyState(await api<CorpusState>("/corpus", { type, text, enabled: true })); input.value = ""; feedback("corpus-feedback", "语料已添加并启用，下一次播报立即生效"); }
    catch (error) { feedback("corpus-feedback", error instanceof Error ? error.message : "添加失败，请重试", true); }
    finally { el<HTMLButtonElement>("corpus-add").disabled = false; }
  };
  el("corpus-refresh").onclick = () => void refreshCorpus();
  el<HTMLFormElement>("idle-form").oninput = () => { idleDirty = true; feedback("idle-feedback", "设置尚未保存"); };
  el<HTMLFormElement>("idle-form").onsubmit = async event => {
    event.preventDefault();
    const idle: IdleSettings = {
      enabled: el<HTMLInputElement>("idle-enabled").checked,
      intervalSeconds: Number(el<HTMLInputElement>("idle-interval").value),
      requireRecentActivitySeconds: Number(el<HTMLInputElement>("idle-activity").value),
    };
    el<HTMLButtonElement>("idle-save").disabled = true;
    feedback("idle-feedback", "保存中…");
    try { const next = await api<CorpusState>("/corpus/idle", idle, "PUT"); idleDirty = false; applyState(next); feedback("idle-feedback", idle.enabled ? "已启用空闲播报，将在满足真人互动与空闲条件时使用语料" : "空闲播报已关闭"); }
    catch (error) { feedback("idle-feedback", error instanceof Error ? error.message : "保存失败，请重试", true); }
    finally { el<HTMLButtonElement>("idle-save").disabled = false; }
  };
  renderList(); void refreshCorpus();
}

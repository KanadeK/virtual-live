import { api } from "../events/api";
import type { IntegrationConfig, IntegrationUpdate, SecretName } from "../events/protocol";
import { icon } from "./icons";

const el = <T extends HTMLElement = HTMLElement>(id: string) => document.getElementById(id) as T;
let loaded = false;
let dirty = false;
let loading = false;
const secrets: [string, SecretName, string][] = [
  ["deepseek-key", "deepseekApiKey", "deepseek-key-state"],
  ["doubao-key", "doubaoApiKey", "doubao-key-state"],
  ["doubao-token", "doubaoAccessToken", "doubao-token-state"],
];

function secretField(id: string, name: string, stateId: string) {
  return `<div class="secret-field"><div class="range-label"><label for="${id}">${name}</label><span id="${stateId}" class="secret-state">读取中</span></div><input type="password" id="${id}" autocomplete="new-password" spellcheck="false" maxlength="${id === "doubao-token" ? 2048 : 1024}" placeholder="留空保留已有密钥"><label class="check-label clear-secret" for="${id}-clear"><input type="checkbox" id="${id}-clear">保存时清除此密钥</label></div>`;
}
export function cloudMarkup() {
  return `<form id="cloud-form" autocomplete="off"><div class="cloud-config-grid">
    <section class="panel" aria-labelledby="deepseek-heading"><div class="panel-heading"><h2 id="deepseek-heading">${icon("chat")} DeepSeek 回复</h2><span id="provider-ai" class="status-chip">本地 Mock</span></div>
      <p class="panel-intro">评论生成短回复。欢迎、空闲和礼物感谢直接使用语料。</p>
      <label for="ai-provider">回复服务</label><select id="ai-provider"><option value="mock">本地 Mock</option><option value="deepseek">DeepSeek</option></select>
      <label for="ai-model">模型名称</label><input id="ai-model" required maxlength="80" value="deepseek-flash" spellcheck="false">
      ${secretField("deepseek-key", "API Key", "deepseek-key-state")}
      <div class="form-grid"><div><label for="ai-max-tokens">最大输出 Token</label><input type="number" id="ai-max-tokens" min="32" max="96" value="96" required></div><div><label for="ai-max-chars">回复字数上限</label><input type="number" id="ai-max-chars" min="15" max="45" value="45" required></div></div>
      <label for="ai-daily-limit">每日请求上限</label><input type="number" id="ai-daily-limit" min="0" max="1000000" step="1" value="50" required><p class="field-hint">设为 0 将停止云端回复调用。</p>
    </section>
    <section class="panel" aria-labelledby="doubao-heading"><div class="panel-heading"><h2 id="doubao-heading">${icon("volume")} 豆包语音</h2><span id="provider-tts" class="status-chip">浏览器语音</span></div>
      <div class="form-grid"><div><label for="tts-provider">语音服务</label><select id="tts-provider"><option value="browser">浏览器语音</option><option value="doubao">豆包语音 2.0</option></select></div><div><label for="tts-auth-mode">鉴权方式</label><select id="tts-auth-mode"><option value="apiKey">API Key</option><option value="appToken">App ID + Access Token</option></select></div></div>
      <p class="field-hint">仅使用当前鉴权方式，切换会保留另一组凭据。</p>
      <div id="tts-api-key-fields">${secretField("doubao-key", "API Key", "doubao-key-state")}</div>
      <div id="tts-app-token-fields" hidden><label for="doubao-app-id">App ID</label><input id="doubao-app-id" maxlength="128" autocomplete="off" spellcheck="false" placeholder="同一应用的 App ID">${secretField("doubao-token", "Access Token", "doubao-token-state")}</div>
      <label for="tts-resource">资源 ID</label><input id="tts-resource" required maxlength="100" value="seed-tts-2.0" spellcheck="false">
      <label for="tts-speaker">默认音色 ID</label><input id="tts-speaker" required maxlength="100" value="zh_female_gujie_uranus_bigtts" spellcheck="false"><p class="field-hint">语料库中的音色 ID 可覆盖此默认值。</p>
      <label for="tts-daily-limit">每日请求字符上限</label><input type="number" id="tts-daily-limit" min="0" max="10000000" step="1" value="1000" required>
    </section>
    <section class="panel cloud-runtime"><div><h3>调用与保存</h3><p>空密钥保留原值，勾选清除后保存才会删除。后续任务使用新配置，当前播报继续。</p></div><div class="runtime-fields"><div><label for="cloud-timeout">请求超时（秒）</label><input type="number" id="cloud-timeout" min="2" max="60" value="20" required></div><label class="check-label" for="paid-auto-enabled"><input type="checkbox" id="paid-auto-enabled">允许自动模拟调用云端</label></div><p id="auto-cost-note" class="auto-cost-note">自动模拟使用本地回复与浏览器语音。</p><div class="cloud-save-row"><span id="cloud-revision" class="field-hint">配置读取中</span><button type="button" id="cloud-refresh" class="secondary-button">重新读取</button><button type="submit" id="cloud-save" class="primary-button">${icon("check")} 保存并生效</button></div><p id="cloud-feedback" class="inline-feedback" role="status" aria-live="polite"></p></section>
  </div></form>
  <section class="panel integration-panel" aria-labelledby="usage-heading"><div class="panel-heading"><h2 id="usage-heading">${icon("cloud")} 今日云端用量</h2><span id="usage-date" class="field-hint">上海日期</span></div><p id="provider-issue" class="provider-issue" hidden></p><div class="usage-box"><span id="ai-usage">DeepSeek 今日请求：0</span><span id="tts-usage">TTS 今日请求字符：0</span><small id="cache-usage">缓存命中：0</small></div><p class="field-hint usage-note">用量来自实际供应商返回；本机限额不代表账户余额。</p></section>`;
}
function feedback(message: string, bad = false) {
  el("cloud-feedback").textContent = message;
  el("cloud-feedback").classList.toggle("bad", bad);
}
function showAuth() {
  const appToken = el<HTMLSelectElement>("tts-auth-mode").value === "appToken";
  el("tts-api-key-fields").hidden = appToken;
  el("tts-app-token-fields").hidden = !appToken;
}
function render(config: IntegrationConfig) {
  const values: Record<string, string | number> = {
    "ai-provider": config.ai.provider, "ai-model": config.ai.model,
    "ai-max-tokens": config.ai.maxOutputTokens, "ai-max-chars": config.ai.maxReplyChars,
    "ai-daily-limit": config.ai.dailyRequestLimit, "tts-provider": config.tts.provider,
    "tts-auth-mode": config.tts.authMode, "doubao-app-id": config.tts.appId,
    "tts-resource": config.tts.resourceId, "tts-speaker": config.tts.speaker,
    "tts-daily-limit": config.tts.dailyCharLimit, "cloud-timeout": config.timeoutSeconds,
  };
  Object.entries(values).forEach(([id, value]) => { el<HTMLInputElement | HTMLSelectElement>(id).value = String(value); });
  el<HTMLInputElement>("paid-auto-enabled").checked = config.paidAutoEnabled;
  const configured = [config.ai.apiKeyConfigured, config.tts.apiKeyConfigured, config.tts.accessTokenConfigured];
  secrets.forEach(([id, , stateId], index) => {
    const input = el<HTMLInputElement>(id); input.value = ""; input.disabled = false;
    input.placeholder = configured[index] ? "已配置 · 留空保留已有密钥" : "未配置 · 填写新密钥";
    el<HTMLInputElement>(`${id}-clear`).checked = false;
    el(stateId).textContent = configured[index] ? "已配置 · 已隐藏" : "未配置";
    el(stateId).classList.toggle("configured", configured[index]);
  });
  el("cloud-revision").textContent = `配置版本 ${config.revision}`;
  el("cloud-refresh").textContent = "重新读取";
  showAuth(); loaded = true; dirty = false;
}
export async function refreshCloud() {
  if (loading || dirty) return;
  loading = true;
  const controls = Array.from(el<HTMLFormElement>("cloud-form").querySelectorAll<HTMLInputElement | HTMLSelectElement | HTMLButtonElement>("input, select, button"));
  const disabled = controls.map(control => control.disabled);
  controls.forEach(control => { control.disabled = true; });
  try { render(await api<IntegrationConfig>("/integrations/config")); feedback("已读取当前配置，密钥仅显示配置状态"); }
  catch (error) { feedback(`${error instanceof Error ? error.message : "读取配置失败"}，请重新读取后再保存`, true); }
  finally {
    loading = false;
    controls.forEach((control, index) => { control.disabled = disabled[index]; });
    secrets.forEach(([id]) => { el<HTMLInputElement>(id).disabled = el<HTMLInputElement>(`${id}-clear`).checked; });
    el<HTMLButtonElement>("cloud-refresh").disabled = false;
    el<HTMLButtonElement>("cloud-save").disabled = !loaded;
  }
}
export function bindCloud() {
  const form = el<HTMLFormElement>("cloud-form");
  form.oninput = () => { dirty = true; feedback("有未保存修改"); el("cloud-refresh").textContent = "放弃修改并重新读取"; };
  el<HTMLSelectElement>("tts-auth-mode").onchange = showAuth;
  secrets.forEach(([id]) => {
    el<HTMLInputElement>(`${id}-clear`).onchange = event => {
      const clear = (event.target as HTMLInputElement).checked;
      el<HTMLInputElement>(id).disabled = clear;
      if (clear) el<HTMLInputElement>(id).value = "";
    };
  });
  el("cloud-refresh").onclick = () => {
    dirty = false;
    void refreshCloud();
  };
  form.onsubmit = async event => {
    event.preventDefault();
    if (!loaded || loading) return;
    const value = (id: string) => el<HTMLInputElement | HTMLSelectElement>(id).value.trim();
    const number = (id: string) => Number(value(id));
    const update: IntegrationUpdate = {
      ai: { provider: value("ai-provider") as "mock" | "deepseek", model: value("ai-model"), maxOutputTokens: number("ai-max-tokens"), maxReplyChars: number("ai-max-chars"), dailyRequestLimit: number("ai-daily-limit") },
      tts: { provider: value("tts-provider") as "browser" | "doubao", authMode: value("tts-auth-mode") as "apiKey" | "appToken", appId: value("doubao-app-id"), resourceId: value("tts-resource"), speaker: value("tts-speaker"), dailyCharLimit: number("tts-daily-limit") },
      paidAutoEnabled: el<HTMLInputElement>("paid-auto-enabled").checked,
      timeoutSeconds: number("cloud-timeout"), clearSecrets: [],
    };
    for (const [id, name] of secrets) if (el<HTMLInputElement>(`${id}-clear`).checked) update.clearSecrets!.push(name);
    // Empty values are omitted so the server preserves existing credentials.
    if (value("deepseek-key")) update.ai!.apiKey = value("deepseek-key");
    if (value("doubao-key")) update.tts!.apiKey = value("doubao-key");
    if (value("doubao-token")) update.tts!.accessToken = value("doubao-token");
    const controls = Array.from(form.querySelectorAll<HTMLInputElement | HTMLSelectElement | HTMLButtonElement>("input, select, button"));
    const disabled = controls.map(control => control.disabled);
    controls.forEach(control => { control.disabled = true; });
    feedback("正在保存…");
    try { render(await api<IntegrationConfig>("/integrations/config", update, "PUT")); feedback("配置已保存并生效，后续任务使用新配置"); }
    catch (error) { feedback(error instanceof Error ? error.message : "保存失败，请重试", true); }
    finally {
      controls.forEach((control, index) => { control.disabled = disabled[index]; });
      secrets.forEach(([id]) => { el<HTMLInputElement>(id).disabled = el<HTMLInputElement>(`${id}-clear`).checked; });
    }
  };
  el<HTMLButtonElement>("cloud-save").disabled = true;
  void refreshCloud();
}

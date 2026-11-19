import { createHash } from 'node:crypto';
import { BiliError, parseJson } from './protocol.js';

const NAV = 'https://api.bilibili.com/x/web-interface/nav';
const ROOM = 'https://api.live.bilibili.com/room/v1/Room/room_init';
const DANMU = 'https://api.live.bilibili.com/xlive/web-room/v1/index/getDanmuInfo';
const USER_AGENT = 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/142.0.0.0 Safari/537.36';
const MIXIN = [46,47,18,2,53,8,23,32,15,50,10,31,58,3,45,35,27,43,5,49,33,9,42,19,29,28,14,39,12,38,41,13];
export const decimalId = (value: unknown): string => /^(?:0|[1-9]\d{0,29})$/.test(String(value)) ? String(value) : '';

export function parseRoom(input: string): string {
  const value = typeof input === 'string' ? input.trim() : '';
  if (/^[1-9]\d{0,29}$/.test(value)) return value;
  try {
    const url = new URL(value);
    if (/^https?:\/\/live\.bilibili\.com\/[1-9]\d{0,29}\/?(?:[?#].*)?$/i.test(value)
        && ['http:', 'https:'].includes(url.protocol) && url.hostname.toLowerCase() === 'live.bilibili.com'
        && !url.username && !url.password && !url.port && /^\/[1-9]\d{0,29}\/?$/.test(url.pathname)
        && !/[%\\]/.test(value.split(/[?#]/, 1)[0])) return url.pathname.replaceAll('/', '');
  } catch { /* Report the same fixed error for every invalid target. */ }
  throw new BiliError('INVALID_ROOM', '请输入B站房间号或 live.bilibili.com 直播链接');
}

export function wbiSign(params: Record<string, string>, img: string, sub: string, seconds = Math.floor(Date.now() / 1000)): string {
  const keyPart = (url: string) => new URL(url).pathname.split('/').pop()!.split('.')[0];
  const joined = keyPart(img) + keyPart(sub);
  if (joined.length < 64) throw new BiliError('ACCESS_DENIED', 'B站未返回正常鉴权参数，请稍后重试');
  const key = MIXIN.map(i => joined[i]).join('');
  const values = { ...params, wts: String(seconds) };
  const query = Object.entries(values).sort(([a], [b]) => a.localeCompare(b))
    .map(([k, v]) => `${encodeURIComponent(k)}=${encodeURIComponent(v.replace(/[!'()*]/g, ''))}`).join('&');
  return `${query}&w_rid=${createHash('md5').update(query + key).digest('hex')}`;
}

export interface ConnectionInfo { roomId: string; uid: string; hasLogin: boolean; liveStatus: number; authBody: string; urls: string[] }
export type Fetcher = (url: string, init: RequestInit) => Promise<Response>;

export class BilibiliApi {
  private cookies = new Map<string, string>();
  constructor(cookie = '', private fetcher: Fetcher = (url, init) => fetch(url, init)) {
    if (cookie.length > 16000 || /[\r\n\x00]/.test(cookie)) throw new BiliError('INVALID_COOKIE', '请填写单行Cookie请求头');
    for (const item of cookie.split(';')) {
      const split = item.indexOf('=');
      if (split > 0) this.cookies.set(item.slice(0, split).trim(), item.slice(split + 1).trim());
    }
  }
  private async request(url: string, signal: AbortSignal): Promise<Response> {
    if (signal.aborted) throw signal.reason;
    try {
      const response = await this.fetcher(url, { redirect: 'error', signal: AbortSignal.any([signal, AbortSignal.timeout(12000)]),
        headers: { 'User-Agent': USER_AGENT, Referer: 'https://live.bilibili.com/', Cookie: [...this.cookies].map(([k,v]) => `${k}=${v}`).join('; ') } });
      if (response.status === 429 || response.status === 412) throw new BiliError('RATE_LIMITED', 'B站限制了请求频率，请稍后再连接');
      if (response.status === 401) throw new BiliError('LOGIN_REQUIRED', 'B站要求有效登录，请点击登录B站后重试');
      if (response.status === 403) throw new BiliError('ACCESS_DENIED', 'B站拒绝了访问，请正常登录或检查房间权限');
      if (!response.ok) throw new BiliError('NETWORK_ERROR', 'B站服务暂时不可用，请稍后重试', true);
      return response;
    } catch (error) {
      if (signal.aborted) throw signal.reason;
      if (error instanceof BiliError) throw error;
      throw new BiliError('NETWORK_ERROR', '无法连接B站，请检查网络', true);
    }
  }
  private async json(url: string, signal: AbortSignal): Promise<any> {
    const response = await this.request(url, signal);
    const reader = response.body?.getReader();
    if (!reader) throw new BiliError('PROTOCOL_ERROR', 'B站返回了空响应');
    const chunks: Uint8Array[] = []; let size = 0;
    try {
      while (true) {
        const next = await reader.read(); if (next.done) break;
        size += next.value.length;
        if (size > 1024 * 1024) throw new BiliError('PROTOCOL_ERROR', 'B站接口响应过大');
        chunks.push(next.value);
      }
      return parseJson(Buffer.concat(chunks).toString('utf8'));
    } catch (e) {
      if (e instanceof BiliError || signal.aborted) throw e;
      throw new BiliError('PROTOCOL_ERROR', '无法解析B站接口响应');
    } finally { await reader.cancel().catch(() => {}); }
  }
  private check(result: any): void {
    if (typeof result?.code !== 'number') throw new BiliError('PROTOCOL_ERROR', 'B站接口响应缺少有效状态码');
    const code = result.code;
    if (code === 0) return;
    if ([-101, -111, 61000].includes(code)) throw new BiliError('LOGIN_REQUIRED', 'B站登录已失效或要求登录，请点击登录B站');
    if ([-412, -509, 429].includes(code)) throw new BiliError('RATE_LIMITED', 'B站限制了请求频率，请稍后再连接');
    if ([-403, -352, 403].includes(code)) throw new BiliError('ACCESS_DENIED', 'B站拒绝鉴权，请正常登录；受限账号或房间可能需要官方开放平台授权');
    if ([60004, 1, 19002003].includes(code)) throw new BiliError('ROOM_NOT_FOUND', 'B站房间不存在或不可访问，请检查房间号');
    throw new BiliError('ACCESS_DENIED', 'B站未允许此次访问，请检查房间权限或稍后重试');
  }
  async resolve(input: string, signal: AbortSignal): Promise<{roomId:string; liveStatus:number}> {
    const result = await this.json(`${ROOM}?id=${parseRoom(input)}`, signal); this.check(result);
    const roomId = decimalId(result.data?.room_id), liveStatus = Number(result.data?.live_status);
    if (!roomId || roomId === '0' || ![0,1,2].includes(liveStatus)) throw new BiliError('PROTOCOL_ERROR', 'B站房间信息不完整');
    return { roomId, liveStatus };
  }
  async connectInfo(roomId: string, liveStatus: number, signal: AbortSignal): Promise<ConnectionInfo> {
    if (liveStatus !== 1) throw new BiliError('ROOM_OFFLINE', 'B站房间未开播或正在轮播，请开播后再连接');
    const nav = await this.json(NAV, signal);
    const hasSession = Boolean(this.cookies.get('SESSDATA'));
    if (Number(nav.code) !== 0 && Number(nav.code) !== -101) this.check(nav);
    const hasLogin = Number(nav.code) === 0 && nav.data?.isLogin === true;
    if (hasSession && !hasLogin) throw new BiliError('LOGIN_REQUIRED', 'B站Cookie已失效，请重新登录');
    const uid = hasLogin ? decimalId(nav.data.mid) : '0';
    if (!uid) throw new BiliError('PROTOCOL_ERROR', 'B站未返回正常用户信息');
    if (!this.cookies.get('buvid3')) {
      const response = await this.request('https://www.bilibili.com/', signal);
      for (const header of response.headers.getSetCookie()) {
        const pair = header.split(';', 1)[0], at = pair.indexOf('=');
        const name = pair.slice(0, at);
        if (at > 0 && ['buvid3', 'buvid4', 'b_nut'].includes(name)) this.cookies.set(name, pair.slice(at + 1));
      }
      await response.body?.cancel();
    }
    const buvid = this.cookies.get('buvid3');
    if (!buvid) throw new BiliError('ACCESS_DENIED', 'B站未下发访客标识，请正常登录后重试');
    let query: string;
    try { query = wbiSign({ id: roomId, type: '0' }, nav.data.wbi_img.img_url, nav.data.wbi_img.sub_url); }
    catch { throw new BiliError('ACCESS_DENIED', 'B站未下发鉴权参数，请正常登录或稍后重试'); }
    const info = await this.json(`${DANMU}?${query}`, signal); this.check(info);
    const token = info.data?.token;
    const hosts: unknown[] = Array.isArray(info.data?.host_list) ? info.data.host_list : [];
    const urls = hosts.flatMap((host: any) => {
      const name = String(host?.host || ''), port = Number(host?.wss_port);
      return /^(?:[a-z0-9-]+\.)+chat\.bilibili\.com$/i.test(name) && Number.isInteger(port) && port > 0 && port <= 65535
        ? [`wss://${name}:${port}/sub`] : [];
    });
    if (typeof token !== 'string' || !token || token.length > 16000 || !urls.length) throw new BiliError('ACCESS_DENIED', 'B站未下发有效弹幕凭证，无法建立连接');
    // The auth schema requires JSON integers. Insert only validated decimals, preserving int64 exactly.
    const authBody = `{"uid":${uid},"roomid":${roomId},"protover":3,"platform":"web","type":2,"buvid":${JSON.stringify(buvid)},"key":${JSON.stringify(token)}}`;
    return { roomId, uid, hasLogin, liveStatus, authBody, urls };
  }
  dispose(): void { this.cookies.clear(); }
}

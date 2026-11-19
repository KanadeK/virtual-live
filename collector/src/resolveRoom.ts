import './network.js';

export function extractWebRoom(html: string): string | undefined {
  const text = html.replace(/\\+/g, '').replace(/&quot;/g, '"');
  return text.match(/"(?:webRid|web_rid)"\s*:\s*"(\d{1,30})"/)?.[1];
}

export async function resolveRoom(input: string): Promise<string> {
  // Long IDs are the internal room_id used by mobile share links.
  if (/^\d{1,15}$/.test(input)) return input;
  let address = /^\d{16,30}$/.test(input) ? `https://webcast.amemv.com/douyin/webcast/reflow/${input}` : input;
  const allowed = new Set(['v.douyin.com', 'live.douyin.com', 'webcast.amemv.com', 'www.douyin.com']);
  for (let hop = 0; hop < 6; hop++) {
    const url = new URL(address);
    if (url.protocol !== 'https:' || !allowed.has(url.hostname) || url.username || url.password || (url.port && url.port !== '443')) throw Error('不支持的分享链接');
    if (url.hostname === 'live.douyin.com' && /^\/\d{1,15}\/?$/.test(url.pathname)) return url.pathname.replaceAll('/', '');
    const response = await fetch(url, { redirect: 'manual', headers: { 'User-Agent': 'Mozilla/5.0' }, signal: AbortSignal.timeout(15000) });
    const redirect = response.headers.get('location');
    if (response.status >= 300 && response.status < 400 && redirect) { address = new URL(redirect, url).href; await response.body?.cancel(); continue; }
    if (!response.ok) throw Error('无法读取分享页面');
    const room = extractWebRoom(await response.text());
    if (room) return room;
    break;
  }
  throw Error('无法解析分享链接，请使用 live.douyin.com 的直播间链接');
}

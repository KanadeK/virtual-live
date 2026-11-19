// JSON-lines subprocess bridge. Cookie enters through stdin, never argv or stdout.
import { createInterface } from 'node:readline';
import { LightweightSession } from './collector/lightweightSession.js';
import { normalize } from './pipeline/normalizer.js';
import { resolveRoom } from './resolveRoom.js';

const send = (value: unknown) => process.stdout.write(JSON.stringify(value) + '\n');
console.log = console.warn = console.error = () => {}; // Upstream diagnostics must not contain credentials.
let session: LightweightSession | undefined;
let stopped = false;
let initialized = false;
let timer: ReturnType<typeof setInterval> | undefined;
let retry: ReturnType<typeof setTimeout> | undefined;
let attempts = 0;

async function stop() {
  stopped = true;
  clearInterval(timer);
  clearTimeout(retry);
  await session?.stop();
  process.exit(0);
}
process.on('SIGTERM', () => void stop());
process.on('SIGINT', () => void stop());
const input = createInterface({ input: process.stdin });
input.on('close', () => void stop());
input.on('line', async line => {
  if (initialized) return;
  initialized = true;
  try {
    const config = JSON.parse(line) as { roomId: string; cookie?: string };
    send({ kind: 'status', state: 'connecting', message: '正在识别直播间地址' });
    config.roomId = await resolveRoom(config.roomId);
    if (stopped) return;
    send({ kind: 'resolved', roomId: config.roomId });
    const connect = () => {
      if (stopped) return;
      let lastFrames = 0, lastFrameAt = Date.now();
      let upstreamError = '';
      session = new LightweightSession(config.roomId, {
        cookie: config.cookie,
        onError: error => {
          const http = error.message.match(/HTTP \d{3}/)?.[0];
          upstreamError = http ? `抖音返回 ${http}` : error.message.includes('cookie') ? '无法获取游客 Cookie' : error.message.includes('room_id') ? '未能解析直播间信息' : '无法建立弹幕连接';
        },
        onMessage: (raw, meta) => {
          if (stopped) return;
          const event = normalize(raw, meta);
          if (event && ['chat', 'gift', 'member'].includes(event.type)) send({ kind: 'event', event });
        },
      });
      const current = session;
      const started = Date.now();
      send({ kind: 'status', state: 'connecting', message: '正在获取房间信息并建立 Lightweight 连接', attempts });
      current.start();
      timer = setInterval(() => {
        if (stopped) return;
        const stats = current.stats();
        if (stats.frameCount !== lastFrames) { lastFrameAt = Date.now(); lastFrames = stats.frameCount; }
        const stale = stats.wsCount > 0 && Date.now() - lastFrameAt > 45000;
        const timedOut = current.status === 'connecting' && Date.now() - started > 90000;
        if (['error', 'closed'].includes(current.status) || stale || timedOut) {
          clearInterval(timer);
          const closed = current.status === 'closed';
          const message = closed ? '直播间已结束或未开播' : timedOut ? '连接超时，请检查网络、房间号或 Cookie' : stale ? '长时间未收到数据，正在恢复连接' : `${upstreamError || '连接失败'}，请检查网络或登录后重试`;
          void current.stop();
          if (!closed && attempts < 5) {
            const seconds = Math.min(30, 3 * 2 ** attempts++);
            send({ kind: 'status', state: 'reconnecting', message: `${message}，${seconds} 秒后重试`, attempts, ...stats });
            retry = setTimeout(connect, seconds * 1000);
          } else send({ kind: 'status', state: closed ? 'closed' : 'error', message, attempts, ...stats });
          return;
        }
        send({ kind: 'status', state: current.status, message: current.status === 'live' ? '正在采集评论、礼物和观众进场' : '正在连接直播间', attempts, ...stats });
      }, 1000);
    };
    connect();
  } catch {
    send({ kind: 'status', state: 'error', message: '无法识别直播间，请检查网络或使用网页直播间链接' });
    process.exitCode = 1;
    input.close();
  }
});

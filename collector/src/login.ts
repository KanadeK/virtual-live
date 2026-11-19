// User-initiated, temporary login window. This is separate from the Lightweight collector.
import { chromium, type Browser } from 'playwright-core';
import { createInterface } from 'node:readline';
import { resolveRoom } from './resolveRoom.js';
import { chromeExecutable } from './chrome.js';

const send = (message: unknown) => process.stdout.write(JSON.stringify(message) + '\n');
let browser: Browser | undefined;
let closing = false;
let timer: ReturnType<typeof setTimeout> | undefined;
async function close() {
  if (closing) return;
  closing = true; clearTimeout(timer);
  await browser?.close().catch(() => {});
  process.exit(0);
}
process.on('SIGTERM', () => void close());
process.on('SIGINT', () => void close());
const input = createInterface({ input: process.stdin });
input.on('close', () => void close());
input.once('line', async line => {
  try {
    const config = JSON.parse(line) as { roomId: string };
    const roomId = await resolveRoom(config.roomId);
    if (closing) return;
    send({ kind: 'status', state: 'opening', message: '正在打开独立抖音登录窗口' });
    browser = await chromium.launch({ channel: 'chrome', executablePath: chromeExecutable(), headless: false });
    if (closing) { await browser.close(); return; }
    browser.on('disconnected', () => {
      if (!closing) { send({ kind: 'status', state: 'cancelled', message: '登录窗口已关闭，可重新打开' }); void close(); }
    });
    const context = await browser.newContext({ viewport: { width: 1100, height: 800 }, locale: 'zh-CN' });
    const page = await context.newPage();
    const address = `https://live.douyin.com/${roomId}`;
    await page.goto(address, { waitUntil: 'domcontentloaded', timeout: 45000 }).catch(() => {});
    if (closing) return;
    send({ kind: 'status', state: 'waiting', message: '请在弹出的抖音窗口点击登录并扫码，完成后自动开始采集' });
    const deadline = Date.now() + 5 * 60 * 1000;
    const check = async () => {
      if (closing) return;
      if (Date.now() > deadline) {
        send({ kind: 'status', state: 'expired', message: '登录等待已超时，请重新打开登录窗口' }); await close(); return;
      }
      try {
        const cookies = await context.cookies(['https://live.douyin.com/', 'https://www.douyin.com/']);
        const values = new Map(cookies.map(cookie => [cookie.name, cookie.value]));
        if ((values.get('sessionid_ss') || values.get('sessionid')) && values.get('ttwid')) {
          const cookie = [...values].map(([name, value]) => `${name}=${value}`).join('; ');
          // Close the temporary profile before handing the secret to the local backend.
          closing = true;
          await browser?.close();
          send({ kind: 'credentials', cookie });
          process.exit(0);
        }
      } catch { /* Closing a page/context during polling is handled by disconnected. */ }
      timer = setTimeout(() => void check(), 1000);
    };
    await check();
  } catch {
    send({ kind: 'status', state: 'error', message: '无法打开抖音登录窗口，请确认已安装 Google Chrome' });
    await close();
  }
});

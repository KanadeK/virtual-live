import { test, expect, Page } from '@playwright/test';
import fs from 'node:fs';
import path from 'node:path';
const systemChrome=path.join(process.env.ProgramFiles||'C:\\Program Files','Google/Chrome/Application/chrome.exe');
test.use({launchOptions:process.platform==='win32'&&fs.existsSync(systemChrome)?{executablePath:systemChrome}:{}});
const screenshots='../docs/screenshots';
async function state(page:Page){return (await page.request.get('/api/status')).json();}
async function debug(page:Page){await page.locator('[data-workspace="debug"]').click();}
async function idle(page:Page){await expect.poll(async()=>{const value=await state(page);return !value.current.taskId&&value.queueSize===0;},{timeout:35000}).toBe(true);}
async function event(page:Page,type:string,extras:Record<string,unknown>={}){return (await page.request.post('/api/demo/event',{data:{eventId:crypto.randomUUID(),type,userId:crypto.randomUUID(),nickname:'小明',content:'小助手今天吃饭了吗？',giftName:'小心心',giftCount:type==='GIFT'?10:0,timestamp:Date.now(),...extras}})).json();}
test.beforeAll(async({request})=>{
 const config=await (await request.get('/api/integrations/config')).json();
 expect(config.ai.provider,'Browser regression requires an isolated Mock backend').toBe('mock');expect(config.tts.provider).toBe('browser');
 fs.mkdirSync(screenshots,{recursive:true});await request.post('/api/demo/auto/stop',{data:{}});await request.post('/api/speech/clear',{data:{}});
});
test.afterEach(async({request})=>{await request.post('/api/demo/auto/stop',{data:{}});await request.post('/api/speech/clear',{data:{}});});
test('五菜单、四宫格、独立日志及桌面/窄屏布局',async({page})=>{
 const audioCalls:string[]=[];page.on('request',request=>{if(/\/api\/(tts\/synthesize|speech\/completed)/.test(request.url()))audioCalls.push(request.url());});
 await page.goto('/control');await expect(page.locator('#connection')).toContainText('服务已连接');expect(await page.locator('.stage,.actor,audio').count()).toBe(0);
 await expect(page.locator('.live-shortcut')).toHaveAttribute('href','/live');await expect(page.locator('.rail [data-workspace]')).toHaveCount(5);
 for(const menu of ['capture','debug','corpus','cloud','history']){await page.locator(`[data-workspace="${menu}"]`).click();await expect(page.locator(`#workspace-${menu}`)).toBeVisible();await expect(page.locator('[data-workspace-panel]:visible')).toHaveCount(1);}
 await debug(page);await expect(page.locator('.debug-grid > .panel')).toHaveCount(4);await expect(page.locator('#music')).toBeVisible();
 const bounds=await page.locator('.debug-grid > .panel').evaluateAll(nodes=>nodes.map(node=>{const box=node.getBoundingClientRect();return {x:box.x,y:box.y};}));expect(bounds[0].y).toBe(bounds[1].y);expect(bounds[2].y).toBe(bounds[3].y);expect(bounds[0].x).toBe(bounds[2].x);
 await page.locator('#nickname').fill('菜单测试');await page.locator('#comment').fill('独立日志测试');await page.locator('#send-event').click();await page.locator('[data-workspace="history"]').click();await expect(page.locator('#event-logs')).toContainText('菜单测试');
 for(const size of [{width:1440,height:1000},{width:1280,height:720},{width:390,height:844}]){await page.setViewportSize(size);for(const menu of ['capture','debug','corpus','cloud','history']){await page.locator(`[data-workspace="${menu}"]`).click();expect(await page.evaluate(()=>document.documentElement.scrollWidth)).toBeLessThanOrEqual(size.width);}}
 await page.screenshot({path:`${screenshots}/control-mobile.png`});await page.setViewportSize({width:1440,height:1000});await page.locator('[data-workspace="capture"]').click();await page.screenshot({path:`${screenshots}/control.png`});expect(audioCalls).toEqual([]);
});
test('实际渲染、欢迎/评论/礼物、串行优先级与单主播播报',async({browser})=>{
 test.setTimeout(150000);const context=await browser.newContext();await context.addInitScript(()=>{speechSynthesis.getVoices=()=>[];});
 const live=await context.newPage(),control=await context.newPage(),errors:string[]=[];for(const page of [live,control])page.on('pageerror',error=>errors.push(error.message));
 await live.goto('/live');await expect(live.locator('.actor canvas')).toBeVisible();await live.locator('#enable-voice').click();await control.goto('/control');await expect(control.locator('#connection')).toContainText('服务已连接');await debug(control);
 await expect(live.locator('.actor')).toHaveAttribute('data-character','demo-assistant');await expect(live.locator('.actor canvas')).toHaveAttribute('width','1024');
 const alpha=await live.locator('canvas.character-canvas').evaluate(node=>{const canvas=node as HTMLCanvasElement,data=canvas.getContext('2d')!.getImageData(0,0,canvas.width,canvas.height).data;let transparent=0,opaque=0;for(let i=3;i<data.length;i+=4){if(data[i]===0)transparent++;if(data[i]>=240)opaque++;}return {transparent,opaque};});expect(alpha.transparent).toBeGreaterThan(10000);expect(alpha.opaque).toBeGreaterThan(10000);
 await control.locator('[data-event="USER_ENTER"]').click();await control.locator('#nickname').fill('小明');await control.locator('#send-event').click();await expect(live.locator('.bubble-text')).toContainText('小明');await expect(live.locator('.actor')).toHaveAttribute('data-speaking','true');await expect.poll(()=>live.locator('.actor').getAttribute('data-mouth'),{intervals:[70]}).not.toBe('0');await idle(live);
 await control.locator('[data-event="COMMENT"]').click();await control.locator('#send-event').click();await expect.poll(async()=>(await state(live)).current.text||'').toMatch(/小鱼干|甜点|肚子/);await idle(live);
 await control.locator('[data-event="GIFT"]').click();await control.locator('#gift-count').fill('10');await control.locator('#send-event').click();await expect(live.locator('.bubble-text')).toContainText('小明');await expect(live.locator('.actor')).toHaveAttribute('data-action','celebrate');await idle(live);
 // Queue while a real current task is still speaking, then clear waiting work only.
 await control.locator('#manual-text').fill('这是一条用于检查串行播报的台词，新的礼物和评论不会打断当前播报。');await control.locator('#manual-form button').click();await expect.poll(async()=>(await state(live)).current.taskId).toBeTruthy();const active=(await state(live)).current.taskId;
 for(let i=0;i<3;i++)await event(live,'COMMENT',{nickname:'观众'+i});for(let i=0;i<2;i++)await event(live,'GIFT',{nickname:'送礼者'+i});const loaded=await state(live);expect(loaded.current.taskId).toBe(active);expect(loaded.queue.slice(0,2).map((task:{priority:number})=>task.priority)).toEqual([0,0]);
 const observer=await context.newPage();await observer.goto('/live');await observer.locator('#enable-voice').click();const rejected=await observer.request.post('/api/speech/completed',{data:{taskId:active,clientId:'preview-forged'}});expect((await rejected.json()).accepted).toBe(false);expect(await observer.locator('#music-audio').count()).toBe(0);
 await control.request.post('/api/speech/clear',{data:{}});await idle(live);const duplicate=await control.request.post('/api/speech/completed',{data:{taskId:active,clientId:loaded.ownerId||'stale'}});expect((await duplicate.json()).accepted).toBe(false);
 await control.locator('[data-workspace="corpus"]').click();await control.locator('#voice-enabled').uncheck();await expect.poll(async()=>(await state(live)).audio.enabled).toBe(false);await debug(control);await control.locator('#manual-text').fill('静音也会完成任务。');await control.locator('#manual-form button').click();await expect(live.locator('.bubble-text')).toContainText('静音');await idle(live);await control.locator('[data-workspace="corpus"]').click();await control.locator('#voice-enabled').check();
 await live.screenshot({path:`${screenshots}/live-idle.png`});expect(errors).toEqual([]);await context.close();
});
test('人物固定居中，旧行走请求降为待机，保留表演动作',async({browser})=>{
 const context=await browser.newContext({viewport:{width:540,height:960}}),live=await context.newPage();await live.goto('/live');await expect(live.locator('.actor canvas')).toBeVisible();await expect.poll(async()=>(await state(live)).ownerConnected).toBe(true);
 const x=()=>live.locator('.actor').evaluate(node=>parseFloat((node as HTMLElement).style.left));await expect.poll(x).toBe(540);
 for(const action of ['walk_left','walk_right']){await live.request.post('/api/character/action',{data:{action}});await expect(live.locator('.actor')).toHaveAttribute('data-action','idle');expect(await x()).toBe(540);}
 for(const action of ['wave','happy','surprised','think','sad','idle']){await live.request.post('/api/character/action',{data:{action}});await expect(live.locator('.actor')).toHaveAttribute('data-action',action);expect(await x()).toBe(540);}
 expect(await live.locator('.night-background img').evaluate(node=>(node as HTMLImageElement).naturalWidth)).toBeGreaterThan(0);await context.close();
});
test('网络断线重连及重新加载恢复工作台',async({page})=>{
 await page.goto('/control');await expect(page.locator('#connection')).toContainText('服务已连接');await debug(page);await page.context().setOffline(true);await expect(page.locator('#connection')).toContainText('正在重新连接');await page.context().setOffline(false);await expect(page.locator('#connection')).toContainText('服务已连接');await page.locator('#like-button').click();await expect(page.locator('#toast')).toContainText('小爱心 +1');await page.reload();await expect(page.locator('#workspace-debug')).toBeVisible();
});
test('没有中文音色时明确提示且继续嘴型与完成回调',async({page})=>{
 await page.addInitScript(()=>{speechSynthesis.getVoices=()=>[];});await page.request.post('/api/audio/settings',{data:{enabled:true,rate:1.05,pitch:1.25}});await page.goto('/live');await expect(page.locator('.actor canvas')).toBeVisible();await page.locator('#enable-voice').click();await expect(page.locator('.live-voice-notice')).toContainText('没有可用中文声音');await page.request.post('/api/speech/manual',{data:{text:'没有声音也能快乐冒泡！'}});await expect(page.locator('.bubble-text')).toContainText('没有声音');await idle(page);await expect(page.locator('.actor')).toHaveAttribute('data-mouth','0');
});

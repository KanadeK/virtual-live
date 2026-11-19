// Offline acceptance of Node subprocess -> Java capture/dedupe -> SSE -> real browser.
import {chromeOptions,start,startVite,stop,waitFor as ready,assertPortsFree} from './verify-support.mjs';
import {createRequire} from 'node:module';
import {fileURLToPath} from 'node:url';
import fs from 'node:fs/promises';
import path from 'node:path';
import os from 'node:os';
import assert from 'node:assert/strict';
const root=path.resolve(path.dirname(fileURLToPath(import.meta.url)),'..');
const {chromium,expect}=createRequire(path.join(root,'frontend/package.json'))('@playwright/test');
const dir=await fs.mkdtemp(path.join(os.tmpdir(),'dafeiyu-capture-'));
const port=18084,frontPort=15177,base=`http://127.0.0.1:${frontPort}`;
let backend,frontend,browser,logs='';
const delay=ms=>new Promise(resolve=>setTimeout(resolve,ms));
async function api(route,body){const response=await fetch(base+'/api'+route,{method:body===undefined?'GET':'POST',headers:{'Content-Type':'application/json'},body:body===undefined?undefined:JSON.stringify(body)});return {http:response.status,...await response.json()};}
await fs.writeFile(path.join(dir,'worker.mjs'),`import {createInterface} from 'node:readline';
const input=createInterface({input:process.stdin});input.once('line',line=>{
const config=JSON.parse(line),send=v=>process.stdout.write(JSON.stringify(v)+'\\n');
send({kind:'status',state:'live',message:'本地协议夹具采集中',frameCount:1});
const event=(id,type,data,nickname='观众小鱼')=>send({kind:'event',event:{id,roomId:config.roomId,type,ts:Date.now(),user:{id:'9007199254740993111',nickname},data}});
setTimeout(()=>{
event('c1','chat',{content:'小助手你好，第一条评论'});event('c1','chat',{content:'重复应忽略'});event('c2','chat',{content:'连续评论也必须显示'});
event('g1','gift',{giftId:'9',giftName:'小心心',repeatCount:1,groupId:'888'});event('g2','gift',{giftId:'9',giftName:'小心心',repeatCount:3,groupId:'888'});event('g3','gift',{giftId:'9',giftName:'小心心',repeatCount:3,groupId:'888',repeatEnd:true});
event('m1','member',{});event('xss','chat',{content:'<script>window.__captureXss=1</script>'},'<img src=x onerror=window.__captureXss=1>');
},200);
let n=0;setInterval(()=>event('continuous-'+(++n),'chat',{content:'滚动消息 '+n}),180);
});input.on('close',()=>process.exit(0));
`);
await fs.writeFile(path.join(dir,'login.js'),`const input=require('node:readline').createInterface({input:process.stdin});input.once('line',()=>{process.stdout.write(JSON.stringify({kind:'status',state:'waiting',message:'本地模拟等待扫码'})+'\\n');setTimeout(()=>{process.stdout.write(JSON.stringify({kind:'credentials',cookie:'ttwid=fixture; sessionid_ss=login-secret-fixture'})+'\\n');process.exit(0);},250);});input.on('close',()=>process.exit(0));`);
try{
 await assertPortsFree(port,frontPort);
 backend=start('java',['-jar','target/douyinVirtualLive-1.0.0.jar'],path.join(root,'backend'),{SERVER_PORT:String(port),AI_PROVIDER:'mock',TTS_PROVIDER:'browser',DEEPSEEK_API_KEY:'',DOUBAO_TTS_API_KEY:'',OUTBOUND_PROXY_URL:'',PROVIDER_DATA_DIR:dir,DOUYIN_WORKER:path.join(dir,'worker.mjs'),DOUYIN_NODE:process.execPath});await ready(`http://127.0.0.1:${port}/api/health`);
 frontend=startVite(frontPort,port);await ready(base+'/api/health',frontend);
 browser=await chromium.launch(chromeOptions);const context=await browser.newContext({viewport:{width:1440,height:1000}}),page=await context.newPage(),errors=[];
 page.on('pageerror',e=>errors.push(e.message));await page.goto(base+'/control');await expect(page.locator('#connection')).toContainText('服务已连接');
 await page.locator('[data-workspace="capture"]').click();await expect(page.locator('#capture-room')).toBeInViewport();
 assert.equal((await api('/douyin/connect',{roomId:'https://evil.example/123'})).http,400);
 await page.locator('#capture-room').fill('https://live.douyin.com/123');await page.locator('.capture-cookie summary').click();await page.locator('#capture-cookie').fill('ttwid=fixture; sessionid_ss=secret-fixture-cookie');await page.locator('#capture-connect').click();
 await expect(page.locator('#capture-state')).toHaveText('采集中');await expect(page.locator('#capture-cookie')).toHaveValue('');await expect(page.locator('#capture-login')).toHaveText('已提供登录 Cookie');
 await expect(page.locator('#capture-feed')).toContainText('连续评论也必须显示');await expect(page.locator('#capture-counts')).toContainText('礼物 3 · 进场 1');
 const captured=await api('/douyin/status');assert.equal(captured.events.filter(e=>e.type==='gift').length,2);assert.equal(captured.gifts,3);assert.equal(captured.events.filter(e=>e.id.endsWith(':c1')).length,1);assert.ok(!JSON.stringify(captured).includes('secret-fixture-cookie'));
 assert.equal(await page.locator('#capture-feed img, #capture-feed script').count(),0);assert.equal(await page.evaluate(()=>window.__captureXss),undefined);
 // Capture stores every rapid comment, while the existing speech queue throttles one user.
 const speech=await api('/status');assert.ok(speech.queueSize>0);assert.equal(speech.queue.filter(task=>task.priority===2).length,1);
 await page.locator('#capture-filter').selectOption('gift');await expect(page.locator('.capture-line')).toHaveCount(2);await expect(page.locator('#capture-feed')).toContainText('连击累计 3');
 await page.locator('#capture-filter').selectOption('all');await expect.poll(()=>page.locator('.capture-line').count()).toBeGreaterThan(15);
 const atBottom=()=>page.locator('#capture-feed').evaluate(el=>Math.abs(el.scrollHeight-el.clientHeight-el.scrollTop)<3);await expect.poll(atBottom).toBe(true);
 await page.locator('#capture-pause').click();await page.locator('#capture-feed').evaluate(el=>el.scrollTop=0);await delay(450);assert.equal(await page.locator('#capture-feed').evaluate(el=>el.scrollTop),0);
 await page.locator('#capture-pause').click();await expect.poll(atBottom).toBe(true);
 await context.setOffline(true);await delay(500);await context.setOffline(false);await expect(page.locator('#connection')).toContainText('服务已连接',{timeout:15000});
 await page.reload();await expect(page.locator('#capture-state')).toHaveText('采集中');await expect(page.locator('#capture-feed')).toContainText('第一条评论');
 for(const size of [{width:1440,height:1000},{width:1280,height:720},{width:390,height:844}]){
  await page.setViewportSize(size);await page.locator('#douyin-capture').scrollIntoViewIfNeeded();
  const layout=await page.evaluate(()=>({h:document.documentElement.scrollHeight,w:document.documentElement.scrollWidth,vh:innerHeight,vw:innerWidth}));assert.equal(layout.h,layout.vh);assert.equal(layout.w,layout.vw);
 }
 await page.setViewportSize({width:1440,height:1000});await page.locator('#workbench-content').evaluate(el=>el.scrollTop=0);await page.locator('#capture-filter').selectOption('gift');
 await page.screenshot({path:path.join(root,'docs/screenshots/control-capture-fixture.png')});
 await page.locator('#capture-stop').click();await expect(page.locator('#capture-state')).toHaveText('已停止');const stopped=await api('/douyin/status');await delay(400);assert.equal((await api('/douyin/status')).comments,stopped.comments);
 await page.locator('#capture-clear').click();await expect(page.locator('.capture-line')).toHaveCount(0);assert.equal((await api('/douyin/status')).gifts,0);
 await page.locator('#capture-room').fill('456');await page.locator('#capture-login-button').click();await expect(page.locator('#capture-login-message')).toContainText('采集鉴权已成功');await expect(page.locator('#capture-state')).toHaveText('采集中');await expect.poll(async()=>(await api('/douyin/status')).events.length).toBeGreaterThan(0);
 const loggedIn=await api('/douyin/status');assert.ok(loggedIn.events.every(e=>e.roomId==='456'));assert.equal(loggedIn.hasLogin,true);assert.ok(!JSON.stringify(loggedIn).includes('login-secret-fixture'));await api('/douyin/disconnect',{});
 assert.deepEqual(errors,[]);
 await fs.writeFile(path.join(root,'docs/capture-check.json'),JSON.stringify({kind:'OFFLINE_CAPTURE_FIXTURE',passed:true,mode:'lightweight',checks:['Node subprocess lifecycle','Java -> SSE -> browser','viewer nickname/comments/entry','gift combo 1 -> 3 -> final 3 counts 3','event dedupe','rapid comments remain visible with speech throttling','fresh comments enter speech queue','login helper automatically reconnects','Cookie stdin only / not in status / input cleared','HTML rendered as text','type filter','pause/resume scrolling','offline/reconnect/reload snapshot recovery','stop/clear/switch room','desktop/mobile no outer overflow'],pageErrors:errors},null,2)+'\n');
 console.log('PASS: offline Lightweight capture/login bridge, gift dedupe, SSE, scrolling, privacy and responsive UI. No paid calls.');
}finally{await browser?.close();await stop(frontend);await stop(backend);await fs.rm(dir,{recursive:true,force:true});}

// Actual Java/SSE/Chrome with local JSONL capture and HTTP provider fixtures.
// --preflight checks fixture contracts without rebuilding or stopping the official service.
import {spawn} from 'node:child_process';
import {createServer} from 'node:http';
import {createRequire} from 'node:module';
import fs from 'node:fs/promises';
import os from 'node:os';
import path from 'node:path';
import assert from 'node:assert/strict';
import {root,chromeOptions,start,startVite,stop,waitFor,assertPortsFree,delay} from './verify-support.mjs';

const evidence=path.resolve(root,'../work/coordination/bilibili');
const fixtureDir=path.join(root,'scripts/fixtures/bilibili');
await fs.mkdir(evidence,{recursive:true});
const preflight=process.argv.includes('--preflight');
const {chromium,expect}=createRequire(path.join(root,'frontend/package.json'))('@playwright/test');
const port=18210,frontPort=15210,providerPort=18211,base=`http://127.0.0.1:${frontPort}`;
const runDir=await fs.mkdtemp(path.join(os.tmpdir(),'ds-bilibili-'));
const report={kind:'OFFLINE_BILIBILI_JSONL_ACTUAL_JAVA_CHROME',checkedAt:new Date().toISOString(),passed:false,realPlatformConnected:false,realProviderRequests:0,checks:[],pageErrors:[],layouts:[]};
let backend,frontend,browser,fixture;
const aiInputs=[],ttsTexts=[],silent={control:{synth:0,completed:0},observer:{synth:0,completed:0}};
const reply='B站夹具问候，今天也要元气满满！';
async function selectPlatform(page,platform){await page.locator(`[data-capture-platform="${platform}"]`).click();await expect(page.locator(`[data-capture-platform="${platform}"]`)).toHaveAttribute('aria-pressed','true');}
async function api(route,body,method=body===undefined?'GET':'POST'){
  const response=await fetch(base+'/api'+route,{method,headers:{'Content-Type':'application/json'},body:body===undefined?undefined:JSON.stringify(body)});
  const result=await response.json();assert.ok(response.ok,`${route} ${response.status}: ${JSON.stringify(result)}`);return result;
}
const alive=pid=>{try{process.kill(pid,0);return true;}catch{return false;}};
async function readPids(kind){const entries=await fs.readdir(runDir);return Promise.all(entries.filter(name=>name.startsWith(kind+'-')&&name.endsWith('.json')).map(async name=>JSON.parse(await fs.readFile(path.join(runDir,name),'utf8'))));}
async function fixtureSmoke(roomId,kind='worker.mjs'){
  const child=spawn(process.execPath,[path.join(fixtureDir,kind)],{cwd:fixtureDir,windowsHide:true,env:{...process.env,BILI_FIXTURE_STATE_DIR:runDir},stdio:['pipe','pipe','pipe']});
  const rows=[];let buffer='',stderr='';child.stderr.on('data',chunk=>stderr+=chunk);child.stdout.on('data',chunk=>{buffer+=chunk;let i;while((i=buffer.indexOf('\n'))>=0){rows.push(JSON.parse(buffer.slice(0,i)));buffer=buffer.slice(i+1);}});
  child.stdin.write(JSON.stringify({roomId,cookie:'SESSDATA=preflight-dummy'})+'\n');
  try{await delay(roomId==='1206'?750:450);assert.equal(stderr,'');assert.ok(rows.length>0);return rows;}
  finally{child.stdin.end();await stop(child);assert.equal(alive(child.pid),false);}
}
try{
  if(preflight){
    await assertPortsFree(port,frontPort,providerPort);
    const chat=await fixtureSmoke('1101');assert.equal(chat.filter(row=>row.kind==='event').length,2);assert.equal(chat.find(row=>row.kind==='event').event.id,'9007199254740993199');
    const gift=await fixtureSmoke('1103');assert.deepEqual(gift.filter(row=>row.kind==='event').map(row=>row.event.data.repeatCount),[1,3,3,3]);
    const old=await fixtureSmoke('1104');assert.ok(old.filter(row=>row.kind==='event').every(row=>Date.now()-row.event.ts>45000));
    for(const [roomId,code] of [['1201','LOGIN_REQUIRED'],['1202','AUTH_FAILED'],['1203','ROOM_OFFLINE'],['1204','ACCESS_DENIED'],['1205','RATE_LIMITED']])assert.equal((await fixtureSmoke(roomId)).find(row=>row.errorCode)?.errorCode,code);
    const retry=await fixtureSmoke('1206');assert.ok(retry.some(row=>row.state==='reconnecting'));assert.equal(retry.at(-1).state,'live');
    const resolved=(await fixtureSmoke('1001')).find(row=>row.kind==='resolved');assert.equal(resolved.roomId,'9007199254740993123');
    const login=await fixtureSmoke('1001','login.js');assert.equal(login.at(-1).kind,'credentials');assert.ok(login[0].state==='waiting');
    const waiting=await fixtureSmoke('1501','login.js');assert.deepEqual(waiting.map(row=>row.state),['waiting']);
    assert.ok((await readPids('worker')).every(row=>!alive(row.pid)));assert.ok((await readPids('login')).every(row=>!alive(row.pid)));
    report.kind='OFFLINE_BILIBILI_FIXTURE_PREFLIGHT';report.checks.push('reserved ports free','fixture JSONL and 64-bit string IDs','duplicate chat and cumulative gift frames','old event timestamps','five error categories','reconnection lifecycle','short-to-long ID fixture','login success and cancellation process cleanup');report.passed=true;
    await fs.writeFile(path.join(evidence,'preflight-check.json'),JSON.stringify(report,null,2)+'\n');
    console.log('PASS: Bilibili fixture preflight. Official service untouched; no external calls.');
  }else{
    await assertPortsFree(port,frontPort,providerPort);
    const jar=path.join(runDir,'app.jar');await fs.copyFile(path.join(root,'backend/target/douyinVirtualLive-1.0.0.jar'),jar);
    fixture=createServer(async(req,res)=>{
      try{
        let raw='';for await(const chunk of req)raw+=chunk;const body=JSON.parse(raw);res.setHeader('Content-Type','application/json');
        if(req.url==='/chat/completions'){
          aiInputs.push(body);assert.equal(body.model,'deepseek-flash');assert.equal(body.thinking.type,'disabled');assert.equal(body.max_tokens,96);
          res.end(JSON.stringify({choices:[{message:{content:reply}}],usage:{prompt_tokens:30,completion_tokens:15}}));
        }else if(req.url==='/tts'){
          ttsTexts.push(body.req_params.text);assert.equal(req.headers['x-api-resource-id'],'seed-tts-2.0');
          const pcm=Buffer.alloc(24000*2*2);for(let i=0;i<pcm.length/2;i++)pcm.writeInt16LE(Math.round(6000*Math.sin(2*Math.PI*220*i/24000)*(i%12000<7000?1:0)),i*2);
          res.end(JSON.stringify({code:0,data:pcm.toString('base64')})+'\n'+JSON.stringify({code:20000000,usage:{text_words:body.req_params.text.length}})+'\n');
        }else{res.writeHead(404);res.end('{}');}
      }catch(error){report.pageErrors.push(String(error));res.writeHead(500);res.end('{}');}
    });await new Promise(resolve=>fixture.listen(providerPort,'127.0.0.1',resolve));
    const endpoint=`http://127.0.0.1:${providerPort}`;
    backend=start('java',['-jar',jar],path.join(root,'backend'),{SERVER_PORT:String(port),PROVIDER_DATA_DIR:path.join(runDir,'providers'),AI_PROVIDER:'deepseek',DEEPSEEK_API_KEY:'fixture-only',DEEPSEEK_BASE_URL:endpoint,TTS_PROVIDER:'doubao',DOUBAO_TTS_API_KEY:'fixture-only',DOUBAO_TTS_AUTH_MODE:'apiKey',DOUBAO_TTS_URL:endpoint+'/tts',DOUYIN_NODE:process.execPath,DOUYIN_WORKER:path.join(fixtureDir,'douyin-worker.mjs'),BILIBILI_NODE:process.execPath,BILIBILI_WORKER:path.join(fixtureDir,'worker.mjs'),BILI_FIXTURE_STATE_DIR:runDir});
    await waitFor(`http://127.0.0.1:${port}/api/health`,backend);frontend=startVite(frontPort,port);await waitFor(base+'/api/health',frontend);
    for(const [type,text] of [['welcome','欢迎{nicknames}，共{count}位。'],['gift','感谢{nickname}的{giftCount}个{giftName}。']]){
      await api('/corpus/'+type+'-default-1',{text,enabled:true},'PUT');const corpus=await api('/corpus');for(const row of corpus.items.filter(item=>item.type===type&&item.id!==type+'-default-1'))await api('/corpus/'+row.id,{text:row.text,enabled:false},'PUT');
    }
    browser=await chromium.launch(chromeOptions);const context=await browser.newContext({viewport:{width:1440,height:1000}});
    await context.addInitScript(()=>{
      speechSynthesis.getVoices=()=>[];window.__biliAudio={frames:0,nonSilentFrames:0,maxRms:0,mouthFrames:0};
      const read=AnalyserNode.prototype.getByteTimeDomainData;AnalyserNode.prototype.getByteTimeDomainData=function(array){read.call(this,array);let sum=0;for(const value of array)sum+=((value-128)/128)**2;const rms=Math.sqrt(sum/array.length),p=window.__biliAudio;p.frames++;p.maxRms=Math.max(p.maxRms,rms);if(rms>.014)p.nonSilentFrames++;};
      setInterval(()=>{const actor=document.querySelector('.actor');if(actor?.getAttribute('data-speaking')==='true'&&actor.getAttribute('data-mouth')!=='0')window.__biliAudio.mouthFrames++;},40);
    });
    const control=await context.newPage(),live=await context.newPage(),observer=await context.newPage();
    for(const page of [control,live,observer])page.on('pageerror',error=>report.pageErrors.push(error.message));
    for(const [role,page] of [['control',control],['observer',observer]])page.on('request',request=>{if(request.url().endsWith('/tts/synthesize'))silent[role].synth++;if(request.url().endsWith('/speech/completed'))silent[role].completed++;});
    await control.goto(base+'/control');await expect(control.locator('#connection')).toContainText('服务已连接');
    await selectPlatform(control,'bilibili');
    await expect(control.locator('#capture-login-button')).toContainText('B站');
    await control.locator('#capture-room').fill('https://live.bilibili.com/1001');await control.locator('#capture-connect').click();await expect(control.locator('#capture-state')).toHaveText('采集中');
    const parsed=await api('/bilibili/status');assert.equal(parsed.roomId,'9007199254740993123');assert.equal(parsed.platform,'bilibili');
    await api('/bilibili/disconnect',{});report.checks.push('Bilibili UI connect, HTTPS URL and resolved string room ID');
    await control.locator('#capture-room').fill('1501');await control.locator('#capture-login-button').click();await expect(control.locator('#capture-login-cancel')).toBeVisible();await expect.poll(async()=>(await api('/bilibili/status')).loginState).toBe('waiting');
    const cancelled=await readPids('login');await control.locator('#capture-login-cancel').click();await expect(control.locator('#capture-login-cancel')).toBeHidden();await expect.poll(()=>cancelled.every(row=>!alive(row.pid))).toBe(true);
    await control.locator('#capture-room').fill('bili-draft');await selectPlatform(control,'douyin');await control.locator('#capture-room').fill('douyin-draft');await selectPlatform(control,'bilibili');await expect(control.locator('#capture-room')).toHaveValue('bili-draft');await selectPlatform(control,'douyin');await expect(control.locator('#capture-room')).toHaveValue('douyin-draft');await selectPlatform(control,'bilibili');
    await control.locator('.capture-cookie summary').click();await control.locator('#capture-cookie').fill('SESSDATA=fixture-cookie-draft');await selectPlatform(control,'douyin');await expect(control.locator('#capture-cookie')).toHaveValue('');await selectPlatform(control,'bilibili');assert.ok(!(await control.evaluate(()=>JSON.stringify(localStorage))).includes('fixture-cookie-draft'));
    report.checks.push('explicit login cancel stops helper','separate in-memory room drafts','platform switch clears Cookie without persisting it');
    // No owner yet: new comment is queued, then actual Java SPEAK ordering must work on /live join.
    await api('/bilibili/connect',{roomId:'1101'});await expect.poll(async()=>(await api('/bilibili/status')).comments).toBe(1);await expect.poll(async()=>(await api('/status')).queueSize).toBe(1);
    await live.goto(base+'/live');await live.locator('#enable-voice').click();await observer.goto(base+'/live');await observer.locator('#enable-voice').click();
    const drain=()=>expect.poll(async()=>{const state=await api('/status');return !state.current.taskId&&state.queueSize===0;},{timeout:15000,intervals:[100]}).toBe(true);
    await expect(live.locator('.bubble-text')).toContainText('B站夹具问候');await expect(live.locator('.actor')).toHaveAttribute('data-speaking','true');await live.screenshot({path:path.join(evidence,'bilibili-live-audio.png')});await drain();assert.equal(aiInputs.length,1);assert.deepEqual(ttsTexts,[reply]);
    report.audio=await live.evaluate(()=>window.__biliAudio);assert.ok(report.audio.nonSilentFrames>0);assert.ok(report.audio.maxRms>.014);assert.ok(report.audio.mouthFrames>0);
    const captured=await api('/bilibili/status');assert.equal(captured.events.length,1);assert.ok(captured.events[0].id.includes('9007199254740993199'));assert.ok(captured.events[0].userId.startsWith('9007199254740993127'));
    report.checks.push('duplicate Bilibili comment → AI fixture once → TTS fixture once → Chrome audio/mouth/completion','initial SPEAK before queue snapshot remains playable','64-bit event/user strings survive Java and SSE');
    for(const [roomId,expected] of [['1102','欢迎甲、乙，共2位。'],['1103','感谢甲的3个小心心。']]){
      await api('/bilibili/connect',{roomId});await expect.poll(async()=>(await api('/status')).current.text||'',{timeout:8000,intervals:[100]}).toBe(expected);await drain();assert.equal(ttsTexts.at(-1),expected);assert.equal(aiInputs.length,1);
    }
    const afterGift=await api('/bilibili/status');assert.equal(afterGift.gifts,3);assert.equal(afterGift.events.filter(row=>row.type==='gift').length,2);
    await api('/bilibili/connect',{roomId:'1104'});await expect.poll(async()=>(await api('/bilibili/status')).events.length).toBe(3);await delay(2200);await drain();assert.equal(aiInputs.length,1);assert.equal(ttsTexts.length,3);report.checks.push('custom merged welcome direct TTS; no additional AI','gift net delta 1→3→final3 thanked once; no additional AI','old comment/member/gift displayed but never spoken');
    for(const [roomId,code,state] of [['1201','LOGIN_REQUIRED','error'],['1202','AUTH_FAILED','error'],['1203','ROOM_OFFLINE','closed'],['1204','ACCESS_DENIED','error'],['1205','RATE_LIMITED','error']]){
      await api('/bilibili/connect',{roomId});await expect.poll(async()=>(await api('/bilibili/status')).errorCode).toBe(code);assert.equal((await api('/bilibili/status')).state,state);
    }
    await api('/bilibili/connect',{roomId:'1206'});await expect.poll(async()=>(await api('/bilibili/status')).state).toBe('live');assert.equal((await api('/bilibili/status')).attempts,1);report.checks.push('login/auth/offline/permission/rate-limit and reconnect states cross worker→Java');
    await api('/bilibili/login',{roomId:'1501'});await expect.poll(async()=>(await api('/bilibili/status')).loginState).toBe('waiting');
    const waitingPids=await readPids('login');assert.ok(waitingPids.some(row=>alive(row.pid)));await api('/douyin/connect',{roomId:'1301'});await expect.poll(async()=>(await api('/douyin/status')).state).toBe('live');await expect.poll(async()=>(await api('/bilibili/status')).state).toBe('stopped');await expect.poll(()=>waitingPids.every(row=>!alive(row.pid))).toBe(true);
    const douyinBefore=(await api('/douyin/status')).comments;
    for(const body of [{roomId:'https://evil.example/123'},{roomId:'1301',cookie:'bad\nheader'}]){
      const invalid=await fetch(base+'/api/bilibili/connect',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify(body)});assert.equal(invalid.status,400);assert.equal((await api('/douyin/status')).state,'live');
    }
    await api('/bilibili/connect',{roomId:'1301'});await expect.poll(async()=>(await api('/bilibili/status')).comments).toBeGreaterThan(3);assert.equal((await api('/douyin/status')).state,'stopped');const stoppedComments=(await api('/douyin/status')).comments;await delay(500);assert.equal((await api('/douyin/status')).comments,stoppedComments);assert.ok(stoppedComments>=douyinBefore);
    await expect(control.locator('#capture-feed')).toContainText('bilibili 历史滚动');assert.equal(await control.locator('#capture-feed img, #capture-feed script').count(),0);assert.equal(await control.evaluate(()=>window.__captureXss),undefined);
    const sameWire=await api('/bilibili/status');assert.ok(sameWire.events.every(row=>row.platform==='bilibili'));await selectPlatform(control,'douyin');await expect(control.locator('#capture-feed')).toContainText('douyin 历史滚动');assert.ok(!(await control.locator('#capture-feed').textContent()).includes('bilibili 历史滚动'));await selectPlatform(control,'bilibili');await expect(control.locator('#capture-feed')).toContainText('bilibili 历史滚动');
    report.checks.push('invalid Bilibili URL/Cookie leaves active Douyin source running','cross-platform connect stops previous worker and pending login','same wire IDs stay separated in status and visible feeds','HTML nicknames rendered as text');
    for(const roomId of ['1401','1402']){
      await api('/bilibili/connect',{roomId});await expect.poll(async()=>(await api('/bilibili/status')).state).toBe('live');
      if(roomId==='1402')await expect.poll(async()=>(await api('/bilibili/status')).gifts).toBe(1);
      await api('/douyin/connect',{roomId:'1002'});await delay(2100);assert.equal(aiInputs.length,1);assert.equal(ttsTexts.length,3);assert.equal((await api('/bilibili/status')).state,'stopped');
    }
    await Promise.all([api('/bilibili/connect',{roomId:'1002'}),api('/douyin/connect',{roomId:'1002'})]);await delay(400);
    const concurrent=await Promise.all([api('/douyin/status'),api('/bilibili/status')]);assert.equal(concurrent.filter(row=>row.state!=='stopped').length,1);assert.equal((await readPids('worker')).filter(row=>alive(row.pid)).length,1);
    report.checks.push('old worker delayed comment and pending gift discarded on switch','concurrent platform connects leave exactly one active worker');
    await api('/bilibili/connect',{roomId:'1301'});await expect.poll(async()=>(await api('/bilibili/status')).comments).toBeGreaterThan(3);
    for(const platform of ['douyin','bilibili']){
      await selectPlatform(control,platform);
      for(const size of [{width:1440,height:1000},{width:1280,height:720},{width:390,height:844},{width:320,height:720}]){
        await control.setViewportSize(size);const layout=await control.evaluate(()=>({w:document.documentElement.scrollWidth,h:document.documentElement.scrollHeight,vw:innerWidth,vh:innerHeight}));assert.equal(layout.w,layout.vw);assert.equal(layout.h,layout.vh);report.layouts.push({platform,...layout});await control.screenshot({path:path.join(evidence,`${platform}-control-${size.width}.png`)});
      }
    }
    await control.setViewportSize({width:1440,height:1000});await control.locator('#capture-stop').click();await expect(control.locator('#capture-state')).toHaveText('已停止');
    await control.locator('#capture-clear').click();await expect(control.locator('.capture-line')).toHaveCount(0);
    await control.locator('#capture-room').fill('1001');await control.locator('#capture-login-button').click();await expect(control.locator('#capture-state')).toHaveText('采集中');assert.equal((await api('/bilibili/status')).hasLogin,true);
    const stateText=JSON.stringify(await api('/bilibili/status'));assert.ok(!stateText.includes('fixture-bili-session'));assert.equal(await control.locator('#capture-cookie').inputValue(),'');
    await api('/bilibili/disconnect',{});await api('/douyin/disconnect',{});await drain();
    await expect.poll(async()=>(await readPids('worker')).every(row=>!alive(row.pid))).toBe(true);await expect.poll(async()=>(await readPids('login')).every(row=>!alive(row.pid))).toBe(true);
    assert.deepEqual(silent,{control:{synth:0,completed:0},observer:{synth:0,completed:0}});assert.equal(aiInputs.length,1);assert.equal(ttsTexts.length,3);assert.deepEqual(report.pageErrors,[]);
    report.checks.push('dual-platform 320/390/720-height layouts','stop/clear and login-helper success','Cookie cleared from input and absent from status','control and observer zero synth/complete','all fixture workers and login processes exit');report.silent=silent;report.providerFixtureCalls={ai:aiInputs.length,tts:ttsTexts.length,texts:ttsTexts};report.usage=(await api('/integrations/status')).usage;report.passed=true;
    await fs.writeFile(path.join(evidence,'bilibili-check.json'),JSON.stringify(report,null,2)+'\n');console.log('PASS: Bilibili JSONL + actual Java + Chrome, source isolation, custom direct TTS, audio and layouts. No paid calls.');
  }
}catch(error){report.failure=String(error);if(backend)report.backendOutput=backend.output;await fs.writeFile(path.join(evidence,preflight?'preflight-failed.json':'bilibili-failed.json'),JSON.stringify(report,null,2)+'\n');throw error;}
finally{
  await browser?.close();await stop(frontend);await stop(backend);if(fixture)await new Promise(resolve=>fixture.close(resolve));
  const target=path.resolve(runDir);assert.ok(target.startsWith(path.resolve(os.tmpdir())+path.sep),'Refuse cleanup outside task temp directory');await fs.rm(target,{recursive:true,force:true});
}

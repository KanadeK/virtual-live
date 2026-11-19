// Offline contract + real browser audio pipeline test. Never reads .env or real credentials.
import {createServer} from 'node:http';
import {chromeOptions,start,startVite,stop,waitFor,assertPortsFree} from './verify-support.mjs';
import {createRequire} from 'node:module';
import {fileURLToPath} from 'node:url';
import path from 'node:path';
import fs from 'node:fs/promises';
import os from 'node:os';
import assert from 'node:assert/strict';
const root=path.resolve(path.dirname(fileURLToPath(import.meta.url)),'..');
const require=createRequire(path.join(root,'frontend/package.json'));
const {chromium,expect}=require('@playwright/test');
const dir=await fs.mkdtemp(path.join(os.tmpdir(),'dafeiyu-provider-'));
const port=18081,frontPort=15174,base=`http://127.0.0.1:${frontPort}`;
const reply='小助手吃饱啦，小鱼干留给你！';
const welcome='欢迎观众小鱼、观众小红来到直播间！小助手给2位朋友比个心！';
let aiCalls=0,ttsCalls=0,failTts=false,backend,frontend,browser,logs='';
await fs.writeFile(path.join(dir,'capture-worker.mjs'),`import {createInterface} from 'node:readline';const input=createInterface({input:process.stdin});input.once('line',line=>{const {roomId}=JSON.parse(line),send=value=>process.stdout.write(JSON.stringify(value)+'\\n');send({kind:'status',state:'live',message:'本地采集夹具'});setTimeout(()=>{
if(roomId==='124'){
 const first={id:'arrival-1',roomId,type:'member',ts:Date.now(),user:{id:'visitor-1',nickname:'观众小鱼'},data:{}};
 const second={...first,id:'arrival-2',user:{id:'visitor-2',nickname:'观众小红'}};
 for(const event of [first,first,second])send({kind:'event',event});return;
}
const event={id:'provider-fixture-real-comment',roomId,type:'chat',ts:Date.now(),user:{id:'fixture-user',nickname:'采集测试观众'},data:{content:'你吃饭了吗？'}};send({kind:'event',event});send({kind:'event',event});},200);});input.on('close',()=>process.exit(0));`);
const fixture=createServer(async(req,res)=>{
 try{
  let raw='';for await(const chunk of req)raw+=chunk;const body=JSON.parse(raw);
  res.setHeader('Content-Type','application/json');
  if(req.url==='/chat/completions'){
   aiCalls++;assert.equal(body.model,'deepseek-flash');assert.equal(body.thinking.type,'disabled');assert.equal(body.max_tokens,96);
   res.end(JSON.stringify({choices:[{message:{content:reply}}],usage:{prompt_tokens:30,completion_tokens:15}}));
  }else if(req.url==='/tts'){
   ttsCalls++;assert.equal(req.headers['x-api-resource-id'],'seed-tts-2.0');assert.equal(req.headers['x-api-app-id'],'fixture-app');
   if(failTts)assert.equal(body.req_params.text,'测试语音回退。');else assert.ok([reply,welcome].includes(body.req_params.text));
   if(failTts){res.writeHead(503);res.end('{}');return;}
   // Three seconds of alternating voiced syllables and silence, signed 16-bit PCM.
   const pcm=Buffer.alloc(24000*3*2);
   for(let i=0;i<pcm.length/2;i++){const envelope=(i%12000)<7000?1:0;pcm.writeInt16LE(Math.round(Math.sin(2*Math.PI*220*i/24000)*7000*envelope),i*2);}
   res.end(JSON.stringify({code:0,data:pcm.toString('base64')})+'\n'+JSON.stringify({code:20000000,usage:{text_words:14}})+'\n');
  }else{res.writeHead(404);res.end('{}');}
 }catch(error){res.writeHead(500);res.end('{}');console.error(error);}
});
await new Promise(resolve=>fixture.listen(0,'127.0.0.1',resolve));
const fixtureUrl=`http://127.0.0.1:${fixture.address().port}`;
const delay=ms=>new Promise(resolve=>setTimeout(resolve,ms));
async function api(route,data){const r=await fetch(base+'/api'+route,{method:data===undefined?'GET':'POST',headers:{'Content-Type':'application/json'},body:data===undefined?undefined:JSON.stringify(data)});assert.ok(r.ok,route+' '+r.status);return r.json();}
async function idle(){await expect.poll(async()=>!(await api('/status')).current.taskId,{timeout:20000}).toBe(true);}
try{
 await assertPortsFree(port,frontPort);
 backend=start('java',['-jar','target/douyinVirtualLive-1.0.0.jar'],path.join(root,'backend'),{SERVER_PORT:String(port),AI_PROVIDER:'deepseek',DEEPSEEK_API_KEY:'fixture-key',DEEPSEEK_BASE_URL:fixtureUrl,TTS_PROVIDER:'doubao',DOUBAO_TTS_API_KEY:'',DOUBAO_TTS_APP_ID:'fixture-app',DOUBAO_TTS_ACCESS_TOKEN:'fixture-token',DOUBAO_TTS_URL:fixtureUrl+'/tts',OUTBOUND_PROXY_URL:'',PROVIDER_DATA_DIR:dir,PAID_AUTO_ENABLED:'false',DOUYIN_WORKER:path.join(dir,'capture-worker.mjs'),DOUYIN_NODE:process.execPath});
 await waitFor(`http://127.0.0.1:${port}/api/health`);
 frontend=startVite(frontPort,port);
 await waitFor(base+'/api/health');
 // Pin this fixture's corpus so randomized built-in wording cannot make the contract flaky.
 for(const [id,text,enabled] of [['welcome-default-1',welcome.replace('观众小鱼、观众小红','{nicknames}'),true],['welcome-default-2','{nickname}来啦！',false]]){
  const response=await fetch(base+'/api/corpus/'+id,{method:'PUT',headers:{'Content-Type':'application/json'},body:JSON.stringify({text,enabled})});assert.ok(response.ok);
 }
 browser=await chromium.launch(chromeOptions);const context=await browser.newContext();
 const control=await context.newPage(),live=await context.newPage(),observer=await context.newPage(),errors=[];
 for(const p of [control,live,observer]){p.on('pageerror',e=>errors.push(e.message));await p.addInitScript(()=>{speechSynthesis.getVoices=()=>[];});}
 await control.goto(base+'/control');await live.goto(base+'/live');await live.locator('#enable-voice').click();
 await observer.goto(base+'/live');await observer.locator('#enable-voice').click();
 await expect.poll(async()=>(await api('/status')).ownerConnected).toBe(true);
 await api('/douyin/connect',{roomId:'123'});
 await expect(live.locator('.live-voice-notice')).toContainText('豆包语音 2.0');
 await expect.poll(()=>live.locator('.actor').getAttribute('data-mouth'),{intervals:[50]}).not.toBe('0');
 await expect(live.locator('.actor')).toHaveAttribute('data-speaking','true');
 const active=(await api('/status')).current;
 const captured=await api('/douyin/status');assert.equal(captured.comments,1);assert.equal(captured.events[0].nickname,'采集测试观众');
 const rejected=await fetch(base+'/api/tts/synthesize',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({taskId:active.taskId,clientId:'preview-forged',rate:1,pitch:1})});assert.equal(rejected.status,409);
 await idle();await expect(live.locator('.actor')).toHaveAttribute('data-mouth','0');assert.equal(aiCalls,1);assert.equal(ttsCalls,1);
 // Identical manual line reuses generated WAV, observer never synthesizes.
 await api('/speech/manual',{text:reply});await expect(live.locator('.live-voice-notice')).toContainText('缓存');await idle();assert.equal(ttsCalls,1);
 // Real captured arrivals merge to one fixed welcome, go straight to TTS and never call AI.
 await api('/douyin/connect',{roomId:'124'});
 await expect.poll(async()=>(await api('/status')).current.text||'',{timeout:7000}).toBe(welcome);
 const welcomeTask=(await api('/status')).current;assert.equal(welcomeTask.priority,3);assert.equal(welcomeTask.ttsProvider,'doubao');
 await expect(live.locator('.bubble-text')).toContainText('欢迎观众小鱼、观众小红');
 await idle();assert.equal(ttsCalls,2,JSON.stringify({status:await api('/integrations/status'),notice:await live.locator('.live-voice-notice').textContent()}));assert.equal(aiCalls,1);assert.equal((await api('/douyin/status')).members,2);
 // Muted speech must complete without a remote TTS request.
 await api('/audio/settings',{enabled:false,rate:1.05,pitch:1.25});await api('/speech/manual',{text:'静音也会继续播报。'});await idle();assert.equal(ttsCalls,2);
 // A V3 transport error explicitly falls back and drains the same task.
 failTts=true;await api('/audio/settings',{enabled:true,rate:1.05,pitch:1.25});await api('/speech/manual',{text:'测试语音回退。'});
 await expect(live.locator('.live-voice-notice')).toContainText('已回退浏览器语音');await idle();assert.equal(ttsCalls,3);
 // Automatically merged welcomes must retain their local-only origin.
 await api('/demo/event',{eventId:'mock-fixture-enter',type:'USER_ENTER',nickname:'自动观众',giftCount:0});
 await expect(live.locator('.bubble-text')).toContainText('欢迎自动观众');await idle();assert.equal(ttsCalls,3);
 const status=await api('/integrations/status');assert.equal(status.usage.counters.aiRequests,1);assert.equal(status.usage.counters.ttsCacheHits,1);assert.equal(status.usage.counters.ttsBilledCharacters,28);assert.deepEqual(errors,[]);
 await fs.mkdir(path.join(root,'docs'),{recursive:true});await fs.writeFile(path.join(root,'docs/provider-fixture-check.json'),JSON.stringify({kind:'OFFLINE_FIXTURE_NOT_REAL_PROVIDER',passed:true,aiCalls,ttsCalls,checks:['captured comment → DeepSeek → SSE → TTS WAV → Web Audio analyser → lips → completion','duplicate capture invokes AI/TTS once','real captured arrivals → merged template welcome → TTS with zero extra AI calls','duplicate arrival ignored','cached replay','silent control and second live observer','non-owner request rejected','mute skips synthesis','HTTP failure fallback drains queue','automatic merged welcome local only'],pageErrors:errors},null,2));
 console.log('PASS: offline provider contract, real Web Audio playback/analyser/lips/completion, cache, mute, ownership, fallback, auto welcome. No paid calls.');
}finally{await browser?.close();await stop(frontend);await stop(backend);await new Promise(resolve=>fixture.close(resolve));await fs.rm(dir,{recursive:true,force:true});}

// One local test comment. --offline proves the exact UI/audio path with local provider fixtures first.
import {createRequire} from 'node:module';
import {createServer} from 'node:http';
import path from 'node:path';
import fs from 'node:fs/promises';
import os from 'node:os';
import assert from 'node:assert/strict';
import {root,chromeOptions,start,startVite,stop,waitFor,assertPortsFree,debugMenu} from './verify-support.mjs';
const offline=process.argv.includes('--offline'),paid=process.argv.includes('--paid-once');
if(offline===paid)throw Error('Choose --offline for local fixtures or --paid-once for the authorized real single-comment check.');
const {chromium,expect}=createRequire(path.join(root,'frontend/package.json'))('@playwright/test');
let base=process.argv.find(arg=>arg.startsWith('--base='))?.slice(7)||'http://127.0.0.1:5173';
if(!/^http:\/\/(127\.0\.0\.1|localhost):\d+$/.test(base))throw Error('A localhost URL with an explicit port is required.');
let fixture,dir,backend,frontend,browser,before,report,firstAfter,task={},failure,fixtureAi=0,fixtureTts=0;
const errors=[],audioReplies=[],requests={control:0,observer:0,live:0},completions={control:0,observer:0,live:0};
async function api(route,data){const response=await fetch(base+'/api'+route,{method:data===undefined?'GET':'POST',headers:{'Content-Type':'application/json'},body:data===undefined?undefined:JSON.stringify(data)});assert.ok(response.ok,route+' '+response.status);return response.json();}
function delta(before,after){const result={};for(const key of new Set([...Object.keys(before||{}),...Object.keys(after||{})]))result[key]=(after?.[key]||0)-(before?.[key]||0);return result;}
try{
 if(offline){
  const port=18085,frontPort=15178;await assertPortsFree(port,frontPort);dir=await fs.mkdtemp(path.join(os.tmpdir(),'dafeiyu-cloud-'));
  fixture=createServer(async(req,res)=>{let raw='';for await(const chunk of req)raw+=chunk;const body=JSON.parse(raw);res.setHeader('Content-Type','application/json');
   if(req.url==='/chat/completions'){fixtureAi++;assert.equal(body.model,'deepseek-flash');assert.equal(body.thinking.type,'disabled');res.end(JSON.stringify({choices:[{message:{content:'小助手给你元气问候，今天也要开心呀！'}}],usage:{prompt_tokens:30,completion_tokens:15}}));}
   else{fixtureTts++;const pcm=Buffer.alloc(24000*3*2);for(let i=0;i<pcm.length/2;i++)pcm.writeInt16LE(Math.round(Math.sin(2*Math.PI*220*i/24000)*7000*((i%12000)<7000?1:0)),i*2);res.end(JSON.stringify({code:0,data:pcm.toString('base64')})+'\n'+JSON.stringify({code:20000000,usage:{text_words:18}})+'\n');}
  });await new Promise(resolve=>fixture.listen(0,'127.0.0.1',resolve));const endpoint=`http://127.0.0.1:${fixture.address().port}`;
  backend=start('java',['-jar','target/douyinVirtualLive-1.0.0.jar'],path.join(root,'backend'),{SERVER_PORT:String(port),PROVIDER_DATA_DIR:dir,AI_PROVIDER:'deepseek',DEEPSEEK_API_KEY:'fixture',DEEPSEEK_BASE_URL:endpoint,TTS_PROVIDER:'doubao',DOUBAO_TTS_API_KEY:'fixture',DOUBAO_TTS_AUTH_MODE:'apiKey',DOUBAO_TTS_URL:endpoint+'/tts'});await waitFor(`http://127.0.0.1:${port}/api/health`,backend);frontend=startVite(frontPort,port);base=`http://127.0.0.1:${frontPort}`;await waitFor(base+'/api/health',frontend);
 }
 before=await api('/integrations/status');assert.equal(before.ai.configured,true,'DeepSeek is not configured');assert.equal((await api('/status')).ownerConnected,false,'Close other /live owners before running the single-comment check');
 assert.equal(before.paidAutoEnabled,false,'Automatic paid simulation must stay disabled');
 await api('/demo/auto/stop',{});await api('/speech/clear',{});await api('/audio/settings',{enabled:true,rate:1.05,pitch:1.25});
 browser=await chromium.launch(chromeOptions);const context=await browser.newContext();const live=await context.newPage(),control=await context.newPage(),observer=await context.newPage();
 await live.addInitScript(()=>{window.__audioProbe={frames:0,nonSilentFrames:0,maxRms:0,mouthFrames:0};const read=AnalyserNode.prototype.getByteTimeDomainData;AnalyserNode.prototype.getByteTimeDomainData=function(array){read.call(this,array);let energy=0;for(const value of array)energy+=((value-128)/128)**2;const rms=Math.sqrt(energy/array.length),probe=window.__audioProbe;probe.frames++;if(rms>.014)probe.nonSilentFrames++;probe.maxRms=Math.max(probe.maxRms,rms);};setInterval(()=>{const actor=document.querySelector('.actor');if(actor?.getAttribute('data-speaking')==='true'&&actor.getAttribute('data-mouth')!=='0')window.__audioProbe.mouthFrames++;},40);});
 for(const [role,page] of [['live',live],['control',control],['observer',observer]]){page.on('pageerror',error=>errors.push(error.message));page.on('request',request=>{if(request.url().endsWith('/api/tts/synthesize'))requests[role]++;if(request.url().endsWith('/api/speech/completed'))completions[role]++;});}
 live.on('response',async response=>{if(response.url().endsWith('/api/tts/synthesize'))try{audioReplies.push(await response.json());}catch{}});
 await live.setViewportSize({width:540,height:960});await live.goto(base+'/live');await live.locator('#enable-voice').click();
 await control.setViewportSize({width:1440,height:1000});await control.goto(base+'/control');await expect(control.locator('#connection')).toContainText('服务已连接');await debugMenu(control);
 await observer.goto(base+'/live');await observer.locator('#enable-voice').click();
 await control.locator('[data-event="COMMENT"]').click();await control.locator('#nickname').fill('本地链路测试');await control.locator('#comment').fill('小助手，给今天的观众一句元气问候。');await control.locator('#send-event').click();
 await expect.poll(async()=>(await api('/status')).current.text||'',{timeout:45000}).not.toBe('');task=(await api('/status')).current;
 await expect(live.locator('.actor')).toHaveAttribute('data-speaking','true');
 await fs.mkdir(path.join(root,'docs/screenshots'),{recursive:true});await live.screenshot({path:path.join(root,`docs/screenshots/live-cloud${offline?'-fixture':''}.png`)});await control.screenshot({path:path.join(root,`docs/screenshots/control-cloud${offline?'-fixture':''}.png`)});
 await expect.poll(async()=>!(await api('/status')).current.taskId,{timeout:60000}).toBe(true);await expect(live.locator('.actor')).toHaveAttribute('data-mouth','0');
 firstAfter=await api('/integrations/status');
 if(process.argv.includes('--require-doubao')){
  assert.ok(audioReplies.some(reply=>reply.provider==='doubao'),'First speech did not return Doubao audio');
  await api('/speech/manual',{text:task.text});await expect.poll(async()=>(await api('/integrations/status')).usage.counters.ttsCacheHits||0,{timeout:15000}).toBeGreaterThan(firstAfter.usage.counters.ttsCacheHits||0);await expect.poll(async()=>!(await api('/status')).current.taskId,{timeout:60000}).toBe(true);
 }
 const after=await api('/integrations/status'),probe=await live.evaluate(()=>window.__audioProbe),usageDelta=delta(before.usage.counters,after.usage.counters),replayDelta=firstAfter?delta(firstAfter.usage.counters,after.usage.counters):{};
 await expect(live.locator('#stage-token-count')).toHaveText((after.usage.aiTotalTokens*10).toLocaleString('en-US'));
 report={checkedAt:new Date().toISOString(),kind:offline?'OFFLINE_SINGLE_COMMENT_FIXTURE':'LOCAL_TEST_COMMENT_REAL_AI_TTS',passed:false,model:after.ai.model,thinking:after.ai.thinking,reply:task.text,aiLatencyMs:after.ai.lastLatencyMs,ttsLatencyMs:after.tts.lastLatencyMs,ttsVoice:after.tts.voice,ttsConfigurationIssue:after.tts.configurationIssue||'',ttsLastError:after.tts.lastError,aiLastFallback:after.ai.lastFallback,audioReplies,audioProbe:probe,usageDelta,rawAiTokensDelta:after.usage.aiTotalTokens-before.usage.aiTotalTokens,rawAiTokensToday:after.usage.aiTotalTokens,displayedTokens:await live.locator('#stage-token-count').textContent(),replayDelta,requests,completions,queueCompleted:true,pageErrors:errors};
 assert.equal(after.ai.lastFallback,'');assert.deepEqual(errors,[]);assert.ok((usageDelta.aiRequests||0)+(usageDelta.aiCacheHits||0)>0);assert.equal(requests.control,0);assert.equal(requests.observer,0);assert.equal(completions.control,0);assert.equal(completions.observer,0);
 if(process.argv.includes('--require-doubao')){assert.ok(probe.nonSilentFrames>0,'Speech audio analyser stayed silent');assert.ok(probe.mouthFrames>0,'Audio did not animate the mouth');assert.ok(audioReplies.some(reply=>reply.cacheHit===true));assert.equal(replayDelta.aiRequests||0,0);assert.equal(replayDelta.ttsRequests||0,0);}
 if(offline){assert.equal(fixtureAi,1);assert.equal(fixtureTts,1);}report.passed=true;
}catch(error){failure=error;if(before){const after=await api('/integrations/status').catch(()=>null);report={...report,checkedAt:new Date().toISOString(),kind:offline?'OFFLINE_SINGLE_COMMENT_FIXTURE':'LOCAL_TEST_COMMENT_REAL_AI_TTS',passed:false,error:error.message,reply:task.text,usageDelta:after?delta(before.usage.counters,after.usage.counters):null,rawAiTokensDelta:after?after.usage.aiTotalTokens-before.usage.aiTotalTokens:null,ttsLastError:after?.tts.lastError,ttsConfigurationIssue:after?.tts.configurationIssue,aiLastFallback:after?.ai.lastFallback,requests,audioReplies,pageErrors:errors};}}
finally{if(report){await fs.writeFile(path.join(root,`docs/cloud${offline?'-fixture':''}-check.json`),JSON.stringify(report,null,2));console.log(JSON.stringify(report,null,2));}await browser?.close();await stop(frontend);await stop(backend);if(fixture)await new Promise(resolve=>fixture.close(resolve));if(dir)await fs.rm(dir,{recursive:true,force:true});}
if(failure)throw failure;

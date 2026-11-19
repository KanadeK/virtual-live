// One continuous raw-WS -> production collector modules -> child JSONL -> Java -> AI/TTS -> Chrome check.
import {createServer} from 'node:http';
import {createRequire} from 'node:module';
import {brotliCompressSync,deflateSync} from 'node:zlib';
import fs from 'node:fs/promises';
import os from 'node:os';
import path from 'node:path';
import assert from 'node:assert/strict';
import {root,start,startVite,waitFor,assertPortsFree,chromeOptions,stop,delay} from './verify-support.mjs';
const {chromium,expect}=createRequire(path.join(root,'frontend/package.json'))('@playwright/test');
const {WebSocketServer}=createRequire(path.join(root,'collector/package.json'))('ws');
const port=18210,frontPort=15210,wirePort=18211,base=`http://127.0.0.1:${frontPort}`,endpoint=`http://127.0.0.1:${wirePort}`;
const dir=await fs.mkdtemp(path.join(os.tmpdir(),'ds-bili-wire-bridge-')),evidence=path.resolve(root,'../work/coordination/bilibili');
let backend,frontend,browser,server,wsServer,aiCalls=0,ttsCalls=0,heartbeats=0;
const upstream=[],states=[],texts=[],errors=[],silent={control:{synth:0,done:0},observer:{synth:0,done:0}},acks=[];
const roomId='9007199254740993123',uid='9007199254740993127',reply='原始B站协议已接入，小助手向你问好！';
const report={kind:'OFFLINE_RAW_WS_PRODUCTION_MODULES_JSONL_JAVA_CHROME',checkedAt:new Date().toISOString(),passed:false,realPlatformConnected:false,realProviderRequests:0};
function wire(op,body='',version=1){const bytes=Buffer.isBuffer(body)?body:Buffer.from(body),header=Buffer.alloc(16);header.writeUInt32BE(16+bytes.length);header.writeUInt16BE(16,4);header.writeUInt16BE(version,6);header.writeUInt32BE(op,8);header.writeUInt32BE(1,12);return Buffer.concat([header,bytes]);}
async function api(route,body){const response=await fetch(base+'/api'+route,{method:body===undefined?'GET':'POST',headers:{'Content-Type':'application/json'},body:body===undefined?undefined:JSON.stringify(body)});assert.ok(response.ok,route+' '+response.status);return response.json();}
try{
  await assertPortsFree(port,frontPort,wirePort);const jar=path.join(dir,'app.jar');await fs.copyFile(path.join(root,'backend/target/douyinVirtualLive-1.0.0.jar'),jar);
  server=createServer(async(req,res)=>{
    try{
      const url=new URL(req.url,endpoint);res.setHeader('Content-Type','application/json');
      if(url.pathname==='/bili'){
        const target=new URL(url.searchParams.get('target'));assert.ok(['api.live.bilibili.com','api.bilibili.com','www.bilibili.com'].includes(target.hostname));upstream.push(target.pathname);
        if(target.pathname.endsWith('/room_init')){assert.equal(target.searchParams.get('id'),'1001');res.end(`{"code":0,"data":{"room_id":${roomId},"live_status":1}}`);}
        else if(target.pathname.endsWith('/nav'))res.end(JSON.stringify({code:0,data:{isLogin:false,wbi_img:{img_url:'https://i0.hdslb.com/bfs/wbi/7cd084941338484aae1ad9425b84077c.png',sub_url:'https://i0.hdslb.com/bfs/wbi/4932caff0ff746eab6f01bf08b70ac45.png'}}}));
        else if(target.hostname==='www.bilibili.com'){res.setHeader('Set-Cookie','buvid3=fixture-guest; Domain=.bilibili.com; Path=/');res.end('{}');}
        else if(target.pathname.endsWith('/getDanmuInfo')){assert.equal(target.searchParams.get('id'),roomId);assert.match(target.searchParams.get('w_rid'),/^[a-f0-9]{32}$/);assert.ok(req.headers.cookie.includes('buvid3=fixture-guest'));res.end(JSON.stringify({code:0,data:{token:'fixture-server-issued',host_list:[{host:'fixture.broadcastlv.chat.bilibili.com',wss_port:443}]}}));}
        else throw Error('Unexpected API endpoint');return;
      }
      let raw='';for await(const chunk of req)raw+=chunk;const body=JSON.parse(raw);
      if(url.pathname==='/chat/completions'){aiCalls++;assert.ok(JSON.stringify(body).includes('连续原始WS弹幕'));res.end(JSON.stringify({choices:[{message:{content:reply}}],usage:{prompt_tokens:21,completion_tokens:12}}));}
      else if(url.pathname==='/tts'){ttsCalls++;texts.push(body.req_params.text);assert.equal(body.req_params.text,reply);const pcm=Buffer.alloc(24000*3*2);for(let i=0;i<pcm.length/2;i++)pcm.writeInt16LE(Math.round(6500*Math.sin(2*Math.PI*220*i/24000)*(i%12000<7000?1:0)),i*2);res.end(JSON.stringify({code:0,data:pcm.toString('base64')})+'\n'+JSON.stringify({code:20000000,usage:{text_words:reply.length}})+'\n');}
      else throw Error('Unexpected fixture path');
    }catch(error){errors.push(String(error));res.writeHead(500);res.end('{}');}
  });wsServer=new WebSocketServer({host:'127.0.0.1',port:0,path:'/sub'});await new Promise(resolve=>wsServer.once('listening',resolve));const socketEndpoint=`ws://127.0.0.1:${wsServer.address().port}/sub`;wsServer.on('connection',socket=>{
    socket.on('error',()=>{});socket.on('message',data=>{
      try{
        const op=data.readUInt32BE(8);
        if(op===7){const auth=data.subarray(16).toString();assert.match(auth,new RegExp('"roomid":'+roomId));assert.ok(auth.includes('"uid":0'));assert.ok(auth.includes('fixture-server-issued'));
          socket.send(wire(8,'{"code":0}'));
        }else if(op===2){heartbeats++;const pong=Buffer.alloc(4);pong.writeUInt32BE(10);socket.send(wire(3,pong));if(heartbeats===1){
          const ts=Math.floor(Date.now()/1000),command=`{"cmd":"DANMU_MSG:4:0:2:2:2:0","info":[[0,1,25,16777215,${ts},1,0,0,0,0,0,0,0,0,0,{"extra":"{\\"id\\":9007199254740993199}"}],"连续原始WS弹幕",[${uid},"原始WS观众"]]}`;
          const frame=wire(5,brotliCompressSync(wire(5,deflateSync(Buffer.concat([wire(5,command,0),wire(5,command,0)])),2)),3);socket.send(frame);
        }}
      }catch(error){errors.push(String(error));socket.close();}
    });
  });await new Promise(resolve=>server.listen(wirePort,'127.0.0.1',resolve));
  backend=start('java',['-jar',jar],path.join(root,'backend'),{SERVER_PORT:String(port),PROVIDER_DATA_DIR:path.join(dir,'providers'),AI_PROVIDER:'deepseek',DEEPSEEK_API_KEY:'fixture',DEEPSEEK_BASE_URL:endpoint,TTS_PROVIDER:'doubao',DOUBAO_TTS_API_KEY:'fixture',DOUBAO_TTS_AUTH_MODE:'apiKey',DOUBAO_TTS_URL:endpoint+'/tts',BILIBILI_NODE:process.execPath,BILIBILI_WORKER:path.join(root,'scripts/fixtures/bilibili/wire-worker.mjs'),BILI_WIRE_FIXTURE:endpoint,BILI_WIRE_SOCKET:socketEndpoint});await waitFor(`http://127.0.0.1:${port}/api/health`,backend);frontend=startVite(frontPort,port);await waitFor(base+'/api/health',frontend);
  browser=await chromium.launch(chromeOptions);const context=await browser.newContext();await context.addInitScript(()=>{
    speechSynthesis.getVoices=()=>[];window.__wireAudio={frames:0,nonSilent:0,maxRms:0,mouth:0};const original=AnalyserNode.prototype.getByteTimeDomainData;AnalyserNode.prototype.getByteTimeDomainData=function(values){original.call(this,values);let sum=0;for(const v of values)sum+=((v-128)/128)**2;const rms=Math.sqrt(sum/values.length),p=window.__wireAudio;p.frames++;p.maxRms=Math.max(p.maxRms,rms);if(rms>.014)p.nonSilent++;};setInterval(()=>{const actor=document.querySelector('.actor');if(actor?.getAttribute('data-speaking')==='true'&&actor.getAttribute('data-mouth')!=='0')window.__wireAudio.mouth++;},40);
  });
  const control=await context.newPage(),live=await context.newPage(),observer=await context.newPage();for(const page of [control,live,observer])page.on('pageerror',e=>errors.push(e.message));for(const [role,page] of [['control',control],['observer',observer]])page.on('request',request=>{if(request.url().endsWith('/tts/synthesize'))silent[role].synth++;if(request.url().endsWith('/speech/completed'))silent[role].done++;});live.on('request',request=>{if(request.url().endsWith('/speech/completed'))acks.push(request.postDataJSON());});
  await control.goto(base+'/control');await control.locator('[data-capture-platform="bilibili"]').click();await live.goto(base+'/live');await live.locator('#enable-voice').click();await observer.goto(base+'/live');await observer.locator('#enable-voice').click();
  await control.locator('#capture-room').fill('https://live.bilibili.com/1001');await control.locator('#capture-connect').click();await expect(control.locator('#capture-state')).toHaveText('采集中');await expect(control.locator('#capture-feed')).toContainText('连续原始WS弹幕');await expect(live.locator('.bubble-text')).toContainText('原始B站协议已接入');await expect(live.locator('.actor')).toHaveAttribute('data-speaking','true');await live.screenshot({path:path.join(evidence,'bilibili-raw-bridge-live.png')});await control.screenshot({path:path.join(evidence,'bilibili-raw-bridge-control.png')});
  await expect.poll(async()=>{const s=await api('/status');return !s.current.taskId&&s.queueSize===0;},{timeout:15000}).toBe(true);
  const captured=await api('/bilibili/status'),audio=await live.evaluate(()=>window.__wireAudio);assert.equal(captured.roomId,roomId);assert.equal(captured.events.length,1);assert.equal(captured.events[0].userId,uid);assert.equal(captured.comments,1);assert.equal(aiCalls,1);assert.equal(ttsCalls,1);assert.ok(audio.nonSilent>0&&audio.maxRms>.014&&audio.mouth>0);assert.equal(acks.length,1);assert.deepEqual(silent,{control:{synth:0,done:0},observer:{synth:0,done:0}});assert.ok(heartbeats>=1);await api('/bilibili/disconnect',{});await delay(150);assert.equal(wsServer.clients.size,0);assert.deepEqual(errors,[]);
  Object.assign(report,{passed:true,checks:['production BilibiliApi uses normal fixed endpoints through explicit local HTTP transport','short ID resolved from raw numeric int64 room JSON','normal guest buvid/WBI/token path and exact numeric auth','server code0 gates production Session','nested Brotli/zlib duplicate raw DANMU_MSG through production decoder/normalizer','child JSONL → actual Java/SSE → UI','one AI fixture → one TTS fixture → Chrome audio/mouth/one completion','control and observer silent; stop closes socket'],upstream,heartbeats,aiCalls,ttsCalls,texts,audio,completed:acks.length,silent,pageErrors:errors});await fs.writeFile(path.join(evidence,'bilibili-bridge-check.json'),JSON.stringify(report,null,2)+'\n');console.log('PASS: continuous raw WS → production collector modules → child JSONL → Java → AI/TTS fixture → real Chrome audio. No external calls.');
}catch(error){report.failure=String(error);report.errors=errors;report.aiCalls=aiCalls;report.ttsCalls=ttsCalls;report.upstream=upstream;try{report.integrations=await api('/integrations/status');}catch{}report.backendOutput=backend?.output;await fs.writeFile(path.join(evidence,'bilibili-bridge-failed.json'),JSON.stringify(report,null,2)+'\n');throw error;}
finally{await browser?.close();await stop(frontend);await stop(backend);if(wsServer){for(const socket of wsServer.clients)socket.terminate();await new Promise(resolve=>wsServer.close(resolve));}if(server)await new Promise(resolve=>server.close(resolve));assert.ok(path.resolve(dir).startsWith(path.resolve(os.tmpdir())+path.sep));await fs.rm(dir,{recursive:true,force:true});}

// Actual backend restart in a private, persistent fixture directory. No .env or paid providers.
import {createRequire} from 'node:module';
import path from 'node:path';
import fs from 'node:fs/promises';
import os from 'node:os';
import assert from 'node:assert/strict';
import {root,chromeOptions,start,startVite,waitFor,stop,assertPortsFree,runNode,delay} from './verify-support.mjs';
const {chromium,expect}=createRequire(path.join(root,'frontend/package.json'))('@playwright/test');
const dir=await fs.mkdtemp(path.join(os.tmpdir(),'dafeiyu-reconnect-'));
const port=18080,frontPort=15173,base=`http://127.0.0.1:${frontPort}`;
let backend,frontend,browser;
function startBackend(){return start('java',['-jar','target/douyinVirtualLive-1.0.0.jar'],path.join(root,'backend'),{SERVER_PORT:String(port),PROVIDER_DATA_DIR:dir});}
try{
 await assertPortsFree(port,frontPort);
 backend=startBackend();await waitFor(`http://127.0.0.1:${port}/api/health`,backend);
 frontend=startVite(frontPort,port);await waitFor(base+'/api/health',frontend);
 if(process.argv.includes('--regression')){
  assert.equal(await runNode(['node_modules/@playwright/test/cli.js','test'],path.join(root,'frontend'),{PLAYWRIGHT_BASE_URL:base}),0,'Offline Playwright regression failed');
  for(let i=0;i<80;i++){const status=await(await fetch(base+'/api/status')).json();if(!status.ownerConnected)break;await delay(250);}
 }
 browser=await chromium.launch(chromeOptions);const context=await browser.newContext();
 const control=await context.newPage(),live=await context.newPage(),errors=[];
 for(const page of [control,live]){page.on('pageerror',error=>errors.push(error.message));await page.addInitScript(()=>{speechSynthesis.getVoices=()=>[];});}
 await control.goto(base+'/control');await live.goto(base+'/live');
 await expect(control.locator('#connection')).toContainText('服务已连接');
 await stop(backend);await expect(control.locator('#connection')).toContainText('重新连接',{timeout:15000});
 backend=startBackend();await waitFor(`http://127.0.0.1:${port}/api/health`,backend);
 await expect(control.locator('#connection')).toContainText('服务已连接');
 await expect.poll(async()=>(await(await fetch(base+'/api/status')).json()).ownerConnected).toBe(true);
 await fetch(base+'/api/demo/event',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({eventId:'after-restart',type:'GIFT',nickname:'重连测试',userId:'reconnect-user',giftName:'小心心',giftCount:10})});
 await expect(live.locator('.bubble-text')).toContainText('重连测试');
 await expect.poll(async()=>!(await(await fetch(base+'/api/status')).json()).current.taskId,{timeout:25000}).toBe(true);
 assert.deepEqual(errors,[]);
 await fs.mkdir(path.join(root,'docs'),{recursive:true});
 await fs.writeFile(path.join(root,'docs/reconnect-check.json'),JSON.stringify({kind:'OFFLINE_ACTUAL_JAVA_RESTART',passed:true,ports:[port,frontPort],checks:['actual backend stop/restart','SSE disconnected/reconnected','single live lease reacquired','gift after restart','completion queue drains'],pageErrors:errors},null,2));
 console.log('PASS: actual Spring Boot stop/restart, SSE reconnect, gift x10 and completion. No paid calls.');
}finally{await browser?.close();await stop(frontend);await stop(backend);await fs.rm(dir,{recursive:true,force:true});}

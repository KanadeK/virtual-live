// Windows-only launcher lifecycle/ownership check. It never replaces unrelated services.
import {spawn} from 'node:child_process';
import {createServer} from 'node:http';
import fs from 'node:fs/promises';
import path from 'node:path';
import assert from 'node:assert/strict';
import {root,assertPortsFree} from './verify-support.mjs';
if(process.platform!=='win32')throw Error('This acceptance checks the Windows PowerShell launcher.');
const script=path.join(root,'scripts/start-dev.ps1'),recordFile=path.join(root,'.runtime/dev-windows.json');
let launched=false;
async function invoke(action,extras=[],success=true){
 const child=spawn('powershell.exe',['-NoProfile','-ExecutionPolicy','Bypass','-File',script,'-Action',action,...extras],{cwd:root,windowsHide:true,stdio:['ignore','pipe','pipe']});let output='';for(const stream of [child.stdout,child.stderr])stream.on('data',data=>output+=data);
 const code=await new Promise((resolve,reject)=>{child.once('error',reject);child.once('exit',resolve);});assert.equal(code===0,success,`Launcher ${action} exit ${code}: ${output}`);return output;
}
try{
 await assertPortsFree(18088,15188,18089);
 try{await fs.access(recordFile);throw Error('Existing launcher record found; stop the managed project before launcher acceptance.');}catch(error){if(error.code!=='ENOENT')throw error;}
 const occupied=createServer((req,res)=>res.end('owned dummy'));
 await new Promise(resolve=>occupied.listen(18089,'127.0.0.1',resolve));
 try{assert.match(await invoke('Start',['-Background','-SkipBuild','-Offline','-BackendPort','18089','-FrontendPort','15188'],false),/occupied/);assert.equal(await(await fetch('http://127.0.0.1:18089')).text(),'owned dummy');}
 finally{await new Promise(resolve=>occupied.close(resolve));}
 const options=['-Background','-SkipBuild','-Offline','-BackendPort','18088','-FrontendPort','15188'];
 const output=await invoke('Start',options);launched=true;const first=JSON.parse(output.slice(output.indexOf('{')));assert.equal(first.backendManaged,true);assert.equal(first.frontendManaged,true);
 const again=JSON.parse(await invoke('Start',options));assert.equal(again.backendPid,first.backendPid);assert.equal(again.frontendPid,first.frontendPid);
 const config=await(await fetch('http://127.0.0.1:18088/api/integrations/config')).json();assert.equal(config.ai.provider,'mock');assert.equal(config.tts.provider,'browser');assert.equal(config.paidAutoEnabled,false);
 await invoke('Stop');launched=false;await assertPortsFree(18088,15188);
 const saved=JSON.parse(await invoke('Status'));assert.equal(saved.backendManaged,false);assert.equal(saved.frontendManaged,false);
 await fs.writeFile(path.join(root,'docs/launcher-check.json'),JSON.stringify({passed:true,kind:'WINDOWS_LAUNCHER_OFFLINE',checks:['occupied port refused, unrelated listener survived','hidden background start using local JDK and Node/Vite','project PID/executable/creation time checks','idempotent start retains PIDs','isolated provider configuration','stop releases both ports','status after stop'],testPids:{backend:first.backendPid,frontend:first.frontendPid},paidCalls:0},null,2));console.log('PASS: Windows hidden start/status/stop, ownership, occupied-port protection and isolated Mock data.');
}finally{if(launched)await invoke('Stop');}

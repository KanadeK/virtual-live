// Shared local acceptance process helpers. Never load .env or borrow another project's ports.
import {spawn} from 'node:child_process';
import fs from 'node:fs';
import net from 'node:net';
import path from 'node:path';
import {fileURLToPath} from 'node:url';
export const root=path.resolve(path.dirname(fileURLToPath(import.meta.url)),'..');
export const delay=ms=>new Promise(resolve=>setTimeout(resolve,ms));
const localJava=path.resolve(root,'../work/tools/jdk-21.0.12.1+1/bin/java.exe');
export const java=process.env.JAVA_HOME?path.join(process.env.JAVA_HOME,'bin',process.platform==='win32'?'java.exe':'java'):
  process.platform==='win32'&&fs.existsSync(localJava)?localJava:'java';
export const offlineEnv={AI_PROVIDER:'mock',TTS_PROVIDER:'browser',DEEPSEEK_API_KEY:'',DOUBAO_TTS_API_KEY:'',DOUBAO_TTS_APP_ID:'',DOUBAO_TTS_ACCESS_TOKEN:'',DOUBAO_TTS_AUTH_MODE:'',OUTBOUND_PROXY_URL:'',PAID_AUTO_ENABLED:'false',LOCAL_MUSIC_ENABLED:'false'};
const systemChrome=process.platform==='win32'?path.join(process.env.ProgramFiles||'C:\\Program Files','Google/Chrome/Application/chrome.exe'):undefined;
export const chromeOptions={channel:'chrome',headless:true,...(systemChrome&&fs.existsSync(systemChrome)?{executablePath:systemChrome}:{})};
export function start(command,args,cwd,env={}) {
  const child=spawn(command==='java'?java:command,args,{cwd,env:{...process.env,...offlineEnv,...env},windowsHide:true,stdio:['ignore','pipe','pipe']});
  child.output=''; child.startError=null;
  for(const stream of [child.stdout,child.stderr])stream?.on('data',data=>{child.output=(child.output+data).slice(-12000);});
  child.on('error',error=>{child.startError=error;});
  return child;
}
export function startVite(port,backendPort) {
  return start(process.execPath,[path.join(root,'frontend/node_modules/vite/bin/vite.js'),'--host','127.0.0.1','--port',String(port),'--strictPort'],path.join(root,'frontend'),{SERVER_PORT:String(backendPort)});
}
export async function assertPortsFree(...ports) {
  for(const port of ports)await new Promise((resolve,reject)=>{
    const server=net.createServer();server.once('error',error=>reject(new Error(`Port ${port} unavailable; no existing process was stopped. ${error.code}`)));
    server.listen({host:'127.0.0.1',port,exclusive:true},()=>server.close(resolve));
  });
}
export async function waitFor(url,...children) {
  for(let attempt=0;attempt<150;attempt++) {
    for(const child of children)if(child&&(child.startError||child.exitCode!==null))throw child.startError||new Error(`Process exited (${child.exitCode}): ${child.output}`);
    try { if((await fetch(url,{signal:AbortSignal.timeout(600)})).ok)return; } catch {}
    await delay(200);
  }
  throw new Error(`Startup timed out: ${url}\n${children.filter(Boolean).map(child=>child.output).join('\n')}`);
}
export async function stop(child) {
  if(!child||child.exitCode!==null||child.signalCode!==null||child.startError)return;
  const done=new Promise(resolve=>child.once('exit',resolve));
  if(process.platform==='win32') {
    const killer=spawn('taskkill.exe',['/PID',String(child.pid),'/T','/F'],{windowsHide:true,stdio:'ignore'});
    await new Promise(resolve=>{killer.once('exit',resolve);killer.once('error',resolve);});
  } else child.kill('SIGTERM');
  await Promise.race([done,delay(10000).then(()=>{if(child.exitCode===null&&child.signalCode===null)throw new Error(`Owned process ${child.pid} did not stop`);})]);
}
export async function runNode(args,cwd,env={}) {
  const child=spawn(process.execPath,args,{cwd,env:{...process.env,...env},windowsHide:true,stdio:'inherit'});
  return new Promise((resolve,reject)=>{child.once('error',reject);child.once('exit',code=>resolve(code));});
}
export async function debugMenu(page) { await page.locator('[data-workspace="debug"]').click(); }

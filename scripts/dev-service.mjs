// Detached local development launcher. No login item, scheduler, or paid API calls.
import {spawn,spawnSync} from 'node:child_process';
import fs from 'node:fs';
import path from 'node:path';
import {fileURLToPath} from 'node:url';

const root=path.resolve(path.dirname(fileURLToPath(import.meta.url)),'..');
const runtime=path.join(root,'.runtime'),recordPath=path.join(runtime,'dev-service.json');
const launcher=path.join(root,'scripts/start-dev.sh');
const delay=ms=>new Promise(resolve=>setTimeout(resolve,ms));
const envFile=fs.existsSync(path.join(root,'.env'))?fs.readFileSync(path.join(root,'.env'),'utf8'):'';
const port=Number(process.env.SERVER_PORT||envFile.match(/^SERVER_PORT\s*=\s*["']?(\d+)/m)?.[1]||8080);
function record(){try{return JSON.parse(fs.readFileSync(recordPath,'utf8'));}catch{return null;}}
function managedProcess(saved){
  if(!Number.isInteger(saved?.pid)||saved.pid<2)return false;
  const command=spawnSync('ps',['-o','command=','-p',String(saved.pid)],{encoding:'utf8'});
  return command.status===0&&command.stdout.trim()===`bash ${launcher}`;
}
async function healthy(url){try{const response=await fetch(url,{signal:AbortSignal.timeout(1000)});return response.ok;}catch{return false;}}
async function status(){const saved=record();const [backend,frontend]=await Promise.all([healthy(`http://127.0.0.1:${port}/api/health`),healthy('http://127.0.0.1:5173/control')]);return {managed:managedProcess(saved),pid:managedProcess(saved)?saved.pid:null,backend,frontend,serverPort:port};}
function show(state){console.log(`后端 ${state.backend?'运行中':'未就绪'}（${port}） / 前端 ${state.frontend?'运行中':'未就绪'}（5173）${state.managed?' / 独立后台进程 '+state.pid:''}`);if(state.backend&&state.frontend)console.log('控制台 http://localhost:5173/control\n直播页 http://localhost:5173/live');}
async function start(){
  const initial=await status();
  if(initial.backend&&initial.frontend){show(initial);return;}
  if((initial.backend||initial.frontend)&&!initial.managed)throw Error('部分端口被已有服务占用；未停止或替换任何现有进程。');
  fs.mkdirSync(runtime,{recursive:true});
  if(!initial.managed){
    const output=fs.openSync(path.join(runtime,'launcher.log'),'a');
    try{
      const child=spawn('bash',[launcher],{cwd:root,detached:true,stdio:['ignore',output,output],env:process.env});
      await new Promise((resolve,reject)=>{child.once('spawn',resolve);child.once('error',reject);});
      fs.writeFileSync(recordPath,JSON.stringify({pid:child.pid,startedAt:new Date().toISOString()},null,2)+'\n',{mode:0o600});child.unref();
    }finally{fs.closeSync(output);}
  }
  for(let attempt=0;attempt<90;attempt++){
    const current=await status();
    if(current.backend&&current.frontend){show(current);return;}
    if(!current.managed)throw Error('后台启动失败，请查看 .runtime/launcher.log、backend.log 和 frontend.log。');
    await delay(500);
  }
  throw Error('后台进程仍在准备中，请稍后用 --status 检查。');
}
async function stop(){
  const saved=record();
  if(!managedProcess(saved)){console.log('没有本脚本管理的后台进程；未停止其他服务。');return;}
  process.kill(saved.pid,'SIGTERM');
  for(let attempt=0;attempt<80;attempt++){
    if(!managedProcess(saved)){fs.rmSync(recordPath,{force:true});console.log('后台服务已停止。');return;}
    await delay(500);
  }
  throw Error('停止信号已发送，仍在等待服务退出；没有强制结束进程。');
}
try{switch(process.argv[2]){case 'start':await start();break;case 'stop':await stop();break;case 'status':show(await status());break;default:throw Error('用法：node scripts/dev-service.mjs start|stop|status');}}
catch(error){console.error(error.message);process.exitCode=1;}

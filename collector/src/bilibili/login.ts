// Called only by the explicit login route. Playwright launches an isolated temporary profile.
import {chromium,type Browser} from 'playwright-core';
import {createInterface} from 'node:readline';
import {chromeExecutable} from '../chrome.js';
import {parseRoom} from './api.js';
const send=(value:unknown)=>process.stdout.write(JSON.stringify({platform:'bilibili',...value as object})+'\n');
let browser:Browser|undefined,closing=false,initialized=false,timer:ReturnType<typeof setTimeout>|undefined;
async function close(){if(closing)return;closing=true;clearTimeout(timer);await browser?.close().catch(()=>{});process.exit(0);}
process.on('SIGTERM',()=>void close());process.on('SIGINT',()=>void close());
const input=createInterface({input:process.stdin});input.on('close',()=>void close());
input.on('line',async line=>{
  if(initialized)return;initialized=true;
  try{
    parseRoom(JSON.parse(line).roomId);
    send({kind:'status',state:'opening',message:'正在打开独立B站登录窗口'});
    browser=await chromium.launch({channel:'chrome',executablePath:chromeExecutable(),headless:false});
    if(closing){await browser.close();return;}
    browser.on('disconnected',()=>{if(!closing){send({kind:'status',state:'cancelled',message:'B站登录窗口已关闭'});void close();}});
    const context=await browser.newContext({viewport:{width:1100,height:800},locale:'zh-CN'});
    const page=await context.newPage();
    // Official page renders its own QR locally; no third-party QR service receives login tokens.
    await page.goto('https://passport.bilibili.com/login',{waitUntil:'domcontentloaded',timeout:45000});
    if(closing)return;
    send({kind:'status',state:'waiting',message:'请在B站官方窗口扫码登录，完成后自动连接采集'});
    const deadline=Date.now()+5*60*1000;
    const check=async()=>{
      if(closing)return;
      if(Date.now()>deadline){send({kind:'status',state:'expired',message:'B站登录等待超时，请重新打开'});await close();return;}
      try{
        const cookies=await context.cookies(['https://live.bilibili.com/','https://api.bilibili.com/']);
        const values=new Map(cookies.filter(c=>c.domain==='bilibili.com'||c.domain==='.bilibili.com'||c.domain.endsWith('.bilibili.com')).map(c=>[c.name,c.value]));
        if(values.get('SESSDATA')&&values.get('DedeUserID')){
          const cookie=[...values].map(([k,v])=>`${k}=${v}`).join('; ');
          closing=true;await browser?.close();send({kind:'credentials',cookie});process.exit(0);
        }
      }catch{/* Closing the window is handled by the disconnected listener. */}
      timer=setTimeout(()=>void check(),1000);
    };
    await check();
  }catch{send({kind:'status',state:'error',message:'无法打开B站登录窗口，请检查Chrome及网络'});await close();}
});

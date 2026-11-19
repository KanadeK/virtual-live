// Test-only transport injection around production API/session/normalizer; never used by default.
import {createInterface} from 'node:readline';
import {createRequire} from 'node:module';
import {BilibiliApi,parseRoom} from '../../../collector/dist/bilibili/api.js';
import {BilibiliSession} from '../../../collector/dist/bilibili/session.js';
const {WebSocket}=createRequire(new URL('../../../collector/package.json',import.meta.url))('ws');
const endpoint=process.env.BILI_WIRE_FIXTURE||'';
const socketEndpoint=process.env.BILI_WIRE_SOCKET||'';
if(!/^http:\/\/127\.0\.0\.1:\d+$/.test(endpoint))throw Error('Wire fixture requires its explicit localhost endpoint.');
if(!/^ws:\/\/127\.0\.0\.1:\d+\/sub$/.test(socketEndpoint))throw Error('Wire fixture requires its explicit localhost socket.');
const send=value=>process.stdout.write(JSON.stringify({platform:'bilibili',...value})+'\n');
let session,stopping=false;
const close=async()=>{if(stopping)return;stopping=true;await session?.stop();process.exit(0);};
const input=createInterface({input:process.stdin});input.on('close',()=>void close());process.on('SIGTERM',()=>void close());process.on('SIGINT',()=>void close());
input.once('line',async line=>{
  try{
    const config=JSON.parse(line),roomId=parseRoom(config.roomId);
    const api=new BilibiliApi(config.cookie||'',(url,init)=>fetch(endpoint+'/bili?target='+encodeURIComponent(url),init));
    session=new BilibiliSession(roomId,config.cookie||'',{api,socket:url=>{
      if(!/^wss:\/\/[a-z0-9.-]+\.chat\.bilibili\.com:443\/sub$/.test(url))throw Error('Unexpected production API host.');
      return new WebSocket(socketEndpoint);
    },heartbeatMs:50,authTimeoutMs:1500,onResolved:room=>send({kind:'resolved',...room}),onStatus:status=>send({kind:'status',...status}),onEvent:event=>send({kind:'event',event})});
    await session.start();if(!stopping)input.close();
  }catch{send({kind:'status',state:'error',errorCode:'FIXTURE_FAILED',message:'本地原始协议夹具失败'});input.close();}
});

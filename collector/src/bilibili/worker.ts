// The only credential input is the initial stdin JSON line. stdout is strictly JSONL.
import {createInterface} from 'node:readline';
import {BilibiliSession} from './session.js';
import {BiliError} from './protocol.js';
import {parseRoom} from './api.js';
const send=(value:unknown)=>process.stdout.write(JSON.stringify({platform:'bilibili',...value as object})+'\n');
let session:BilibiliSession|undefined, initialized=false, stopping=false;
async function stop(){if(stopping)return;stopping=true;await session?.stop();process.exit(0);}
process.on('SIGTERM',()=>void stop());process.on('SIGINT',()=>void stop());
const input=createInterface({input:process.stdin});input.on('close',()=>void stop());
input.on('line',async line=>{
  if(initialized)return;initialized=true;
  try{
    if(line.length>20000)throw new Error();
    const config=JSON.parse(line) as {roomId:string;cookie?:string};
    const roomId=parseRoom(config.roomId);
    session=new BilibiliSession(roomId,config.cookie??'',{
      onResolved:room=>send({kind:'resolved',...room}),
      onStatus:status=>send({kind:'status',...status}),
      onEvent:event=>send({kind:'event',event})
    });
    await session.start();
    if(!stopping){input.close();}
  }catch(error){
    const known=error instanceof BiliError;
    send({kind:'status',state:'error',errorCode:known?error.code:'INVALID_CONFIG',message:known?error.message:'B站采集配置无效'});
    input.close();
  }
});

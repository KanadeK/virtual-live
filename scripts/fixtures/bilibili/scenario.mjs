// JSONL acceptance fixture only. This module never calls an external service.
import {createInterface} from 'node:readline';
import fs from 'node:fs';
import path from 'node:path';

export function runFixture(platform) {
  const input=createInterface({input:process.stdin});
  const timers=new Set();
  let initialized=false;
  const send=value=>process.stdout.write(JSON.stringify({platform,...value})+'\n');
  const later=(fn,ms)=>{const timer=setTimeout(fn,ms);timers.add(timer);return timer;};
  const record=(kind,roomId)=>{
    const dir=process.env.BILI_FIXTURE_STATE_DIR;
    if(dir)fs.writeFileSync(path.join(dir,`${kind}-${process.pid}.json`),JSON.stringify({kind,platform,pid:process.pid,roomId}));
  };
  const stop=()=>{for(const timer of timers)clearTimeout(timer);process.exit(0);};
  input.on('close',stop);process.on('SIGTERM',stop);process.on('SIGINT',stop);
  input.once('line',line=>{
    if(initialized)return;initialized=true;
    const config=JSON.parse(line),source=String(config.roomId),roomId=platform==='bilibili'&&source==='1001'?'9007199254740993123':source;
    record('worker',roomId);
    const status=(state,extra={})=>send({kind:'status',state,message:`${platform} 本地离线夹具：${state}`,frames:1,frameCount:1,hasLogin:/SESSDATA=|sessionid_ss=/.test(config.cookie||''),liveStatus:1,...extra});
    const event=(id,type,data={},name='甲',old=false)=>({platform,id,roomId,type,ts:Date.now()-(old?120000:0),user:{id:({甲:'9007199254740993127',乙:'9007199254740993128',旧:'9007199254740993129'})[name]||'9007199254740993130',nickname:name},data});
    const emit=row=>send({kind:'event',event:row});
    send({kind:'resolved',roomId,inputRoomId:source,liveStatus:source==='1203'?0:1,hasLogin:/SESSDATA=|sessionid_ss=/.test(config.cookie||'')});
    const failures={1201:['error','LOGIN_REQUIRED',true],1202:['error','AUTH_FAILED',false],1203:['closed','ROOM_OFFLINE',false],1204:['error','ACCESS_DENIED',false],1205:['error','RATE_LIMITED',false]};
    if(failures[source]){const [state,errorCode,requiresLogin]=failures[source];status(state,{errorCode,requiresLogin,liveStatus:source==='1203'?0:1});return;}
    status('connecting');
    if(source==='1206')status('reconnecting',{attempts:1,errorCode:'NETWORK_ERROR'});
    later(()=>{
      status('live',{attempts:source==='1206'?1:0});
      if(source==='1101'){
        const chat=event('9007199254740993199','chat',{content:'B站夹具弹幕，给我一句问候。'});
        emit(chat);emit(chat);
      }else if(source==='1102'){
        const first=event('member-1','member');emit(first);emit(first);emit(event('member-2','member',{},'乙'));
      }else if(source==='1103'){
        const data={giftId:'9007199254740993101',giftName:'小心心',groupId:'9007199254740993111'};
        emit(event('gift-1','gift',{...data,repeatCount:1,countMode:'delta',num:1,transactionId:'tx-1'}));
        emit(event('gift-2','gift',{...data,repeatCount:3,countMode:'total'}));
        emit(event('gift-final','gift',{...data,repeatCount:3,countMode:'total',repeatEnd:true}));
        emit(event('gift-final','gift',{...data,repeatCount:3,countMode:'total',repeatEnd:true}));
      }else if(source==='1104'){
        emit(event('history-chat','chat',{content:'历史弹幕不应调用AI'},'旧',true));
        emit(event('history-member','member',{},'旧',true));
        emit(event('history-gift','gift',{giftId:'9',giftName:'小心心',repeatCount:20,num:20,countMode:'delta',transactionId:'old-tx',groupId:'old'},'旧',true));
      }else if(source==='1301'){
        let count=0;const loop=()=>{emit(event('same-wire-id-'+(++count),'chat',{content:`${platform} 历史滚动 ${count}`},'<img src=x onerror=window.__captureXss=1>',true));later(loop,160);};loop();
      }else if(source==='1401'){
        later(()=>emit(event('late-old-room','chat',{content:'旧来源延迟事件不得播报'})),1000);
      }else if(source==='1402'){
        emit(event('pending-old-gift','gift',{giftId:'9',giftName:'小心心',repeatCount:1,num:1,countMode:'delta',transactionId:'abandoned',groupId:'abandoned'}));
      }
    },source==='1206'?500:150);
  });
}

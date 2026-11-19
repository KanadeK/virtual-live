// Offline JSONL fixture used by integration. This file does not contact any external host.
import {createInterface} from 'node:readline';
const input=createInterface({input:process.stdin});let closed=false;
input.once('line',line=>{
  const config=JSON.parse(line),platform=config.platform==='bilibili'?'bilibili':'douyin';
  const send=value=>process.stdout.write(JSON.stringify({platform,...value})+'\n');
  const roomId=config.roomId==='123'?'456':config.roomId;
  const event=(id,type,data={},timestamp=Date.now(),userId='9007199254740993111')=>send({kind:'event',event:{platform,id,roomId,type,ts:timestamp,user:{id:userId,nickname:'夹具观众'},data}});
  send({kind:'resolved',roomId,liveStatus:config.roomId==='911'?0:1});
  if(config.roomId==='910'){send({kind:'status',state:'error',errorCode:'LOGIN_REQUIRED',requiresLogin:true,message:'B站要求有效登录，请点击登录B站'});return;}
  if(config.roomId==='911'){send({kind:'status',state:'closed',errorCode:'ROOM_OFFLINE',message:'B站房间未开播'});return;}
  if(config.roomId==='912'){send({kind:'status',state:'error',errorCode:'RATE_LIMITED',message:'B站请求被限流，请稍后连接'});return;}
  if(config.roomId==='913'){event('unauthenticated','chat',{content:'鉴权前消息必须丢弃'});send({kind:'status',state:'error',errorCode:'AUTH_FAILED',message:'B站鉴权失败'});return;}
  send({kind:'status',state:'live',message:'离线协议夹具正在采集',frameCount:1,attempts:0,hasLogin:Boolean(config.cookie)});
  if(config.roomId==='960'){
    event('song-command','chat',{content:'点歌　：　晴天'});
    event('song-command','chat',{content:'点歌　：　晴天'});
    event('song-history','chat',{content:'点歌 历史歌曲'},Date.now()-60000,'22');
    event('song-question','chat',{content:'可以点歌吗'},Date.now(),'33');
    event('song-empty','chat',{content:'点歌 ：　'},Date.now(),'44');
    event('song-rate-limited','chat',{content:'点歌 限流歌曲'});
    send({kind:'status',state:'live',message:'慢音乐搜索时JSONL仍继续',frameCount:77});
  }else if(config.roomId==='920'){
    event('pending','gift',{giftId:'9',giftName:'小花',groupId:'pending',num:3,repeatCount:3,countMode:'delta'});
  }else if(config.roomId==='930'){
    let n=0;const interval=setInterval(()=>{if(!closed)event(`late-${process.pid}-${n++}`,'chat',{content:'持续夹具事件'});},25);
    // Intentionally keep sending for a moment after stdin closes to exercise stale-worker fences.
    input.on('close',()=>{setTimeout(()=>event(`late-after-close-${process.pid}`,'chat',{content:'停止后的迟到消息'}),30);setTimeout(()=>{closed=true;clearInterval(interval);process.exit(0);},250);});return;
  }else if(config.roomId!=='940'){
    event('comment1','chat',{content:'你好小鱼'});event('comment1','chat',{content:'你好小鱼'});
    event('comment2','chat',{content:'连续评论仍显示'});event('entry1','member');
    const gift=(id,num,total)=>event(id,'gift',{giftId:'9',giftName:'小花',groupId:'group1',num,repeatCount:total,countMode:platform==='bilibili'?(num?'delta':'total'):undefined,repeatEnd:!num});
    gift('gift1',1,1);gift('gift2',2,3);gift('gift-end',0,3);gift('gift-old-order',0,2);
    event('old','chat',{content:'历史消息不播报'},Date.now()-60000);
    event('follow','follow');
  }
});
input.on('close',()=>{closed=true;setTimeout(()=>process.exit(0),400);});

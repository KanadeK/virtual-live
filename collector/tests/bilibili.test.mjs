import test from 'node:test';
import assert from 'node:assert/strict';
import {once} from 'node:events';
import {createServer} from 'node:http';
import {deflateSync,gzipSync,brotliCompressSync} from 'node:zlib';
import protobuf from 'protobufjs';
import WebSocket,{WebSocketServer} from 'ws';
import {BilibiliApi,parseRoom,wbiSign} from '../dist/bilibili/api.js';
import {packet,PacketDecoder,parseJson,MAX_PACKET_BYTES,MAX_DECODED_BYTES} from '../dist/bilibili/protocol.js';
import {normalize} from '../dist/bilibili/normalize.js';
import {BilibiliSession} from '../dist/bilibili/session.js';

const sleep=ms=>new Promise(r=>setTimeout(r,ms));
async function until(fn,timeout=2500){const end=Date.now()+timeout;while(!fn()){if(Date.now()>end)assert.fail('fixture timed out');await sleep(5);}}
const img='https://i0.hdslb.com/bfs/wbi/7cd084941338484aae1ad9425b84077c.png';
const sub='https://i0.hdslb.com/bfs/wbi/4932caff0ff746eab6f01bf08b70ac45.png';
const ok=data=>Response.json({code:0,data});

test('strict room inputs: short IDs and official URLs never become arbitrary fetch targets',()=>{
  assert.equal(parseRoom(' 123 '),'123');assert.equal(parseRoom('https://live.bilibili.com/123/?broadcast_type=0'),'123');
  for(const value of ['0','-1','123abc','../../123','file:///123','https://evil.test/123','https://live.bilibili.com.evil/123',
    'https://user@live.bilibili.com/123','https://live.bilibili.com:8080/123','https://live.bilibili.com/x/../123',
    'https://live.bilibili.com/%31%32%33','https://live.bilibili.com/123/..','https://live.bilibili.com\\@127.0.0.1/123'])assert.throws(()=>parseRoom(value));
});
test('JSON integer literals over 2^53 retain exact UID, room and event IDs, including escaped strings',()=>{
  const value=parseJson('{"uid":9007199254740993111,"id":9223372036854775807,"small":123,"minus":-9007199254740993,"s":"\\\"9007199254740993111\\\"","f":1.25}');
  assert.equal(value.uid,'9007199254740993111');assert.equal(value.id,'9223372036854775807');assert.equal(value.minus,'-9007199254740993');assert.equal(value.small,123);assert.equal(value.s,'"9007199254740993111"');assert.equal(value.f,1.25);
  assert.throws(()=>parseJson('{"uid":0002}'));
});
test('WBI signature matches a fixed independent known vector',()=>{
  assert.equal(wbiSign({foo:'114',bar:'514',zab:'1919810'},img,sub,1702204169),
    'bar=514&foo=114&wts=1702204169&zab=1919810&w_rid=8f6f2b5b3d485fe1886cec6a0be8c5d4');
  assert.match(wbiSign({foo:"one one four!'()*",bar:'五一四'},img,sub,1702204169),/^bar=%E4%BA%94%E4%B8%80%E5%9B%9B&foo=one%20one%20four&/);
});
test('API resolves canonical numeric int64, obtains server-issued token and uses exact integer auth',async()=>{
  const requests=[];
  const api=new BilibiliApi('SESSDATA=fixture-private; buvid3=fixture-buvid',async(url,init)=>{
    requests.push([url,init]);
    if(url.includes('room_init'))return new Response('{"code":0,"data":{"room_id":9007199254740993111,"live_status":1}}');
    if(url.includes('/nav'))return new Response(`{"code":0,"data":{"isLogin":true,"mid":9223372036854775807,"wbi_img":{"img_url":"${img}","sub_url":"${sub}"}}}`);
    return ok({token:'issued-fixture-token',host_list:[{host:'test.chat.bilibili.com',wss_port:443}]});
  });
  try{
    const room=await api.resolve('123',new AbortController().signal);assert.equal(room.roomId,'9007199254740993111');
    const info=await api.connectInfo(room.roomId,room.liveStatus,new AbortController().signal);
    assert.equal(info.uid,'9223372036854775807');assert.equal(info.hasLogin,true);
    assert.ok(info.authBody.includes('"uid":9223372036854775807'));assert.ok(info.authBody.includes('"roomid":9007199254740993111'));
    assert.equal(parseJson(info.authBody).key,'issued-fixture-token');assert.equal(requests.length,3);
    assert.equal(new URL(requests[2][0]).searchParams.get('id'),room.roomId);
    assert.match(requests[2][0],/w_rid=[a-f0-9]{32}/);assert.equal(requests[0][1].redirect,'error');
  }finally{api.dispose();}
});
test('guest API uses real buvid cookie and token; never falls back to missing token or arbitrary WS host',async()=>{
  let token='issued-token',host='test.chat.bilibili.com';const requests=[];
  const api=new BilibiliApi('',async(url,init)=>{
    requests.push([url,init]);
    if(url.includes('/nav'))return Response.json({code:-101,data:{isLogin:false,wbi_img:{img_url:img,sub_url:sub}}});
    if(url==='https://www.bilibili.com/')return new Response('',{headers:{'Set-Cookie':'buvid3=server-issued-buvid; Domain=.bilibili.com; Path=/'}});
    return ok({token,host_list:[{host,wss_port:443}]});
  });
  try{
    const info=await api.connectInfo('123',1,new AbortController().signal);assert.equal(info.hasLogin,false);
    assert.equal(parseJson(info.authBody).uid,0);assert.equal(parseJson(info.authBody).buvid,'server-issued-buvid');
    token='';await assert.rejects(api.connectInfo('123',1,new AbortController().signal),e=>e.code==='ACCESS_DENIED');
    token='issued-token';host='127.0.0.1';await assert.rejects(api.connectInfo('123',1,new AbortController().signal),e=>e.code==='ACCESS_DENIED');
    host='evil.chat.bilibili.com.evil';await assert.rejects(api.connectInfo('123',1,new AbortController().signal));
    assert.equal(requests.filter(([url])=>url==='https://www.bilibili.com/').length,1);
  }finally{api.dispose();}
});
test('platform errors, offline, stale cookie, rate limiting and network errors are explicit and sanitized',async()=>{
  for(const [response,expected] of [[()=>new Response('',{status:429}),'RATE_LIMITED'],[()=>new Response('',{status:403}),'ACCESS_DENIED'],
    [()=>Response.json({code:-101,message:'SESSDATA=secret'}),'LOGIN_REQUIRED'],[()=>Response.json({code:-352}),'ACCESS_DENIED'],
    [()=>Response.json({code:60004}),'ROOM_NOT_FOUND'],[()=>{throw new Error('secret-cookie')},'NETWORK_ERROR']]){
    const api=new BilibiliApi('',async()=>response());
    await assert.rejects(api.resolve('123',new AbortController().signal),e=>e.code===expected&&!e.message.includes('secret'));api.dispose();
  }
  const api=new BilibiliApi('SESSDATA=expired',async()=>Response.json({code:-101,data:{}}));
  await assert.rejects(api.connectInfo('123',2,new AbortController().signal),e=>e.code==='ROOM_OFFLINE');
  await assert.rejects(api.connectInfo('123',1,new AbortController().signal),e=>e.code==='LOGIN_REQUIRED');api.dispose();
  assert.throws(()=>new BilibiliApi('SESSDATA=x\r\nExtra:y'));
});
test('HTTP transport fixture uses a real localhost server while production URLs remain fixed',async()=>{
  const urls=[];const server=createServer((req,res)=>{urls.push(req.url);res.setHeader('Content-Type','application/json');res.end('{"code":0,"data":{"room_id":456,"live_status":1}}');});
  server.listen(0,'127.0.0.1');await once(server,'listening');
  const api=new BilibiliApi('',(url,init)=>fetch(`http://127.0.0.1:${server.address().port}/${new URL(url).search}`,init));
  try{assert.deepEqual(await api.resolve('123',new AbortController().signal),{roomId:'456',liveStatus:1});assert.deepEqual(urls,['/?id=123']);}
  finally{api.dispose();await new Promise(r=>server.close(r));}
});
test('plain multi-packet frames, split chunks, extended header, gzip/zlib and nested Brotli all decode',()=>{
  const first=packet(5,'{"cmd":"DANMU_MSG","x":1}',0),second=packet(8,'{"code":0}');
  const expected=[5,8];
  for(const frame of [Buffer.concat([first,second]),packet(5,deflateSync(Buffer.concat([first,second])),2),
    packet(5,gzipSync(Buffer.concat([first,second])),2),packet(5,brotliCompressSync(packet(5,deflateSync(Buffer.concat([first,second])),2)),3)]){
    const decoder=new PacketDecoder();assert.deepEqual(decoder.feed(frame).map(p=>p.operation),expected);decoder.finish();
    const split=new PacketDecoder();const output=[];for(let i=0;i<frame.length;i+=3)output.push(...split.feed(frame.subarray(i,i+3)));split.finish();assert.deepEqual(output.map(p=>p.operation),expected);
  }
  const extended=Buffer.concat([first.subarray(0,16),Buffer.alloc(4),first.subarray(16)]);extended.writeUInt32BE(extended.length,0);extended.writeUInt16BE(20,4);
  assert.equal(new PacketDecoder().feed(extended)[0].body.toString(),first.subarray(16).toString());
});
test('invalid lengths/versions, incomplete compressed packets, decompression bombs and excessive nesting fail boundedly',()=>{
  for(const [offset,value,wide] of [[0,0,true],[0,15,true],[0,MAX_PACKET_BYTES+1,true],[4,15,false],[4,999,false],[6,4,false]]){
    const bytes=packet(5,'{}');if(wide)bytes.writeUInt32BE(value,offset);else bytes.writeUInt16BE(value,offset);
    assert.throws(()=>new PacketDecoder().feed(bytes),e=>e.code==='PROTOCOL_ERROR');
  }
  assert.throws(()=>new PacketDecoder().feed(packet(5,Buffer.from('bad gzip'),2)));
  assert.throws(()=>new PacketDecoder().feed(packet(5,deflateSync(Buffer.from([1,2])),2)));
  assert.throws(()=>new PacketDecoder().feed(packet(5,deflateSync(Buffer.alloc(MAX_DECODED_BYTES+1)),2)));
  let nested=packet(5,'{}');for(let i=0;i<6;i++)nested=packet(5,deflateSync(nested),2);
  assert.throws(()=>new PacketDecoder().feed(nested));
  const partial=new PacketDecoder();partial.feed(packet(5,'{}').subarray(0,17));assert.throws(()=>partial.finish());
  assert.throws(()=>new PacketDecoder().feed(Buffer.concat(Array.from({length:2049},()=>packet(5,'{}')))));
});

const rawChat=()=>`{"cmd":"DANMU_MSG:4:0:2:2:2:0","info":[[0,1,25,16777215,${Date.now()},9007199254740993222],"你好小鱼",[9007199254740993111,"观众小鱼"]]}`;
test('DANMU_MSG variants preserve numeric UID/rnd and stable IDs; missing time is never made fresh',()=>{
  const raw=parseJson(rawChat()),event=normalize(raw,'9007199254740993333')[0];
  assert.equal(event.platform,'bilibili');assert.equal(event.user.id,'9007199254740993111');assert.equal(event.roomId,'9007199254740993333');
  assert.equal(event.data.content,'你好小鱼');assert.deepEqual(normalize(raw,event.roomId)[0],event);
  raw.info[0][4]=0;assert.equal(normalize(raw,event.roomId)[0].ts,0);
  assert.deepEqual(normalize({cmd:'SUPER_CHAT_MESSAGE',data:{}},'123'),[]);assert.deepEqual(normalize({cmd:'GUARD_BUY',data:{}},'123'),[]);
});
const pbRoot=protobuf.parse('syntax="proto3";message Interaction {uint64 uid=1;string uname=2;uint32 kind=5;int64 timestamp=7;}message Item{uint64 id=1;string name=2;uint32 count=3;string transaction=9;int64 timestamp=10;string rnd=12;}message Gifts{uint64 uid=1;string name=2;repeated Item items=10;}').root;
const encode=(name,data)=>{const t=pbRoot.lookupType(name);return Buffer.from(t.encode(t.fromObject(data)).finish()).toString('base64');};
test('INTERACT_WORD and protobuf V2 welcome only enter-room; follow/share/like do not welcome',()=>{
  for(const kind of [1,2,3,4,5,6]){
    const data={uid:'9007199254740993111',uname:'观众',msg_type:kind,timestamp:Math.floor(Date.now()/1000)};
    assert.equal(normalize({cmd:'INTERACT_WORD',data},'123').length,kind===1?1:0);
    const pb=encode('Interaction',{uid:data.uid,uname:data.uname,kind,timestamp:String(data.timestamp)});
    const events=normalize({cmd:'INTERACT_WORD_V2',data:{pb}},'123');assert.equal(events.length,kind===1?1:0);
    if(events.length)assert.equal(events[0].user.id,data.uid);
  }
  assert.deepEqual(normalize({cmd:'INTERACT_WORD_V2',data:{pb:'!!invalid'}},'123'),[]);
});
test('SEND_GIFT and COMBO_SEND share group identity and cumulative semantics; protobuf V2 batches keep int64 IDs',()=>{
  const gift={uid:'9007199254740993111',uname:'观众',giftId:9,giftName:'小花',num:2,tid:'9007199254740993444',timestamp:Math.floor(Date.now()/1000),
    batch_combo_send:{batch_combo_id:'group1',batch_combo_num:3}};
  const send=normalize({cmd:'SEND_GIFT',data:gift},'123')[0];
  const combo=normalize({cmd:'COMBO_SEND',data:{uid:gift.uid,uname:gift.uname,gift_id:9,gift_name:'小花',batch_combo_id:'group1',batch_combo_num:3}},'123')[0];
  assert.equal(send.data.num,2);assert.equal(send.data.repeatCount,3);assert.equal(send.data.groupId,combo.data.groupId);assert.equal(combo.data.countMode,'total');assert.equal(combo.ts,0);
  const pb=encode('Gifts',{uid:gift.uid,name:gift.uname,items:[{id:'9',name:'小花',count:2,transaction:gift.tid,timestamp:String(gift.timestamp)},{id:'10',name:'小鱼',count:1,transaction:'next',timestamp:String(gift.timestamp)}]});
  const events=normalize({cmd:'SEND_GIFT_V2',data:{pb}},'123');assert.equal(events.length,2);assert.equal(events[0].user.id,gift.uid);assert.equal(events[0].data.transactionId,gift.tid);assert.equal(events[1].data.num,1);
});

async function fixture(onConnection,overrides={}){
  const server=new WebSocketServer({host:'127.0.0.1',port:0});await once(server,'listening');
  const states=[],events=[],resolved=[];let connects=0,disposed=0;
  const api={resolve:async()=>({roomId:'456',liveStatus:1}),connectInfo:async()=>{connects++;return {roomId:'456',uid:'0',hasLogin:false,liveStatus:1,authBody:'{"key":"server-issued-fixture"}',urls:['wss://test.chat.bilibili.com/sub']};},dispose:()=>disposed++};
  server.on('connection',onConnection);
  const options={api,socket:()=>new WebSocket(`ws://127.0.0.1:${server.address().port}`),heartbeatMs:20,authTimeoutMs:300,staleMs:100,backoffMs:10,
    onStatus:s=>states.push(s),onEvent:e=>events.push(e),onResolved:r=>resolved.push(r),...overrides};
  const session=new BilibiliSession('123','',options);const running=session.start();
  return {session,states,events,resolved,connects:()=>connects,disposed:()=>disposed,running,
    close:async()=>{await session.stop();for(const client of server.clients)client.terminate();await new Promise(r=>server.close(r));}};
}
test('real local WS waits for auth code=0, sends heartbeat, handles nested frames and stops without late events',async()=>{
  const sent=[];let ws;
  const f=await fixture(socket=>{ws=socket;socket.on('message',bytes=>{
    for(const item of new PacketDecoder().feed(bytes)){
      sent.push(item.operation);
      if(item.operation===7){assert.equal(parseJson(item.body.toString()).key,'server-issued-fixture');socket.send(packet(5,rawChat()));setTimeout(()=>{socket.send(packet(8,'{"code":0}'));socket.send(packet(5,brotliCompressSync(packet(5,rawChat())),3));},30);}
      if(item.operation===2)socket.send(packet(3,Buffer.from([0,0,0,1])));
    }
  });});
  try{
    await until(()=>f.events.length===1&&sent.filter(op=>op===2).length>=2);
    assert.equal(f.states.filter(s=>s.state==='live').length>=1,true);assert.equal(f.events[0].roomId,'456');assert.equal(f.events[0].user.id,'9007199254740993111');
    await f.session.stop();const count=f.events.length;try{ws.send(packet(5,rawChat()));}catch{}await sleep(30);assert.equal(f.events.length,count);assert.equal(f.disposed()>0,true);
  }finally{await f.close();}
});
test('auth denial never reports live or forwards events and does not retry around permissions',async()=>{
  const f=await fixture(socket=>{socket.send(packet(8,'{"code":-101,"message":"sensitive-token"}'));socket.send(packet(5,rawChat()));});
  try{await f.running;assert.equal(f.states.some(s=>s.state==='live'),false);assert.equal(f.events.length,0);assert.equal(f.states.at(-1).requiresLogin,true);assert.equal(f.connects(),1);assert.equal(JSON.stringify(f.states).includes('sensitive-token'),false);}
  finally{await f.close();}
});
test('disconnect uses exponential backoff, re-resolves and refreshes token; cancellation prevents further retries',async()=>{
  let connections=0;
  const f=await fixture(socket=>{connections++;socket.on('message',bytes=>{for(const item of new PacketDecoder().feed(bytes))if(item.operation===7){socket.send(packet(8,'{"code":0}'));setTimeout(()=>socket.close(),10);}});});
  try{
    await until(()=>f.states.filter(s=>s.state==='reconnecting').length>=3);
    assert.deepEqual(f.states.filter(s=>s.state==='reconnecting').map(s=>s.attempts),[1,2,3]);assert.equal(f.connects(),connections);
    await f.session.stop();const count=connections;await sleep(100);assert.equal(connections,count);
  }finally{await f.close();}
});
test('missing/null auth result is not success and invalid heartbeat bodies cannot renew a lease',async()=>{
  for(const result of ['{}','{"code":null}','{"code":"0"}']){
    const f=await fixture(socket=>socket.send(packet(8,result)));
    try{await f.running;assert.equal(f.states.some(s=>s.state==='live'),false);assert.equal(f.states.at(-1).errorCode,'AUTH_FAILED');}
    finally{await f.close();}
  }
  const f=await fixture(socket=>{socket.send(packet(8,'{"code":0}'));socket.send(packet(3,''));});
  try{await f.running;assert.equal(f.states.at(-1).errorCode,'PROTOCOL_ERROR');}
  finally{await f.close();}
});
test('heartbeat timeout and auth timeout are bounded, malformed frames stop without retry',async()=>{
  const missing=await fixture(socket=>{socket.send(packet(8,'{"code":0}'));});
  try{await until(()=>missing.states.some(s=>s.errorCode==='HEARTBEAT_TIMEOUT'));await missing.session.stop();}
  finally{await missing.close();}
  const noAuth=await fixture(()=>{},{authTimeoutMs:30});
  try{await until(()=>noAuth.states.some(s=>s.errorCode==='AUTH_TIMEOUT'));}
  finally{await noAuth.close();}
  const invalid=await fixture(socket=>{const malformed=packet(5,'{}');malformed.writeUInt32BE(0);socket.send(malformed);});
  try{await invalid.running;assert.equal(invalid.states.at(-1).errorCode,'PROTOCOL_ERROR');assert.equal(invalid.connects(),1);}
  finally{await invalid.close();}
});
test('stop during delayed HTTP resolution prevents opening a WebSocket',async()=>{
  let release,opened=0;const events=[];
  const api={resolve:()=>new Promise(r=>release=r),connectInfo:async()=>{assert.fail('late connectInfo');},dispose:()=>{}};
  const session=new BilibiliSession('123','',{api,onStatus:()=>{},onEvent:e=>events.push(e),onResolved:()=>assert.fail('late resolved'),socket:()=>{opened++;throw new Error();}});
  const running=session.start();const stopping=session.stop();release({roomId:'456',liveStatus:1});await stopping;await running;assert.equal(opened,0);assert.equal(events.length,0);
});

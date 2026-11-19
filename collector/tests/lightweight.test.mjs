import test from 'node:test';
import assert from 'node:assert/strict';
import protobuf from 'protobufjs';
import { gzipSync } from 'node:zlib';
import { WebSocketServer } from 'ws';
import { once } from 'node:events';
import { decodeFrame, decodeAckInfo } from '../dist/proto/douyin.proto.js';
import { normalize } from '../dist/pipeline/normalizer.js';
import { LightweightSession } from '../dist/collector/lightweightSession.js';
import { resolveRoom, extractWebRoom } from '../dist/resolveRoom.js';

// Independent minimal wire schema, with real platform field numbers and 64-bit IDs.
const root = protobuf.parse(`syntax="proto3";
message Header {string key=1;string value=2;}
message Push {uint64 logId=2;repeated Header headersList=5;string payloadEncoding=6;string payloadType=7;bytes payload=8;}
message Response {repeated Message messagesList=1;string internalExt=5;bool needAck=9;}
message Message {string method=1;bytes payload=2;int64 msgId=3;}
message Common {uint64 createTime=4;}
message User {uint64 id=1;string nickName=3;}
message Chat {Common common=1;User user=2;string content=3;}
message GiftInfo {int64 id=5;bool combo=10;string name=16;}
message Gift {int64 giftId=2;int64 repeatCount=5;User user=7;int32 repeatEnd=9;int64 groupId=11;GiftInfo gift=15;}
`).root;
const encode = (name, value) => root.lookupType(name).encode(root.lookupType(name).fromObject(value)).finish();
test('share link redirects resolve internal IDs to the web room ID', async () => {
  const originalFetch=globalThis.fetch;
  const visited=[];
  globalThis.fetch=async url=>{visited.push(String(url));return visited.length===1?new Response(null,{status:302,headers:{location:'https://webcast.amemv.com/douyin/webcast/reflow/7000000000000000001'}}):new Response('{\\"webRid\\":\\"12345678901\\"}');};
  try {
    assert.equal(await resolveRoom('https://v.douyin.com/ExampleRoom/'),'12345678901');assert.equal(visited.length,2);
    assert.equal(extractWebRoom('{"web_rid":"123"}'),'123');
    await assert.rejects(()=>resolveRoom('https://evil.example/test'));
  } finally {globalThis.fetch=originalFetch;}
});
function frame(payloadEncoding = false) {
  const chat = encode('Chat', { common: {createTime:'1793149200'}, user:{id:'9007199254740993111',nickName:'小鱼🐟'}, content:'真实协议测试' });
  const gift = encode('Gift', { giftId:'9', repeatCount:'3', user:{id:'9007199254740993111',nickName:'小鱼🐟'}, repeatEnd:1, groupId:'9007199254740993555',gift:{id:'9',combo:true,name:'小心心'} });
  const response = encode('Response', {messagesList:[{method:'WebcastChatMessage',payload:chat,msgId:'9007199254740993222'},{method:'WebcastGiftMessage',payload:gift,msgId:'9007199254740993223'}],needAck:true,internalExt:'fixture-ack'});
  return encode('Push', {logId:'9007199254740993444',payloadType:'msg',payload:gzipSync(response),...(payloadEncoding?{payloadEncoding:'gzip'}:{headersList:[{key:'compress_type',value:'gzip'}]})});
}

test('gzip frames decode chat/gift/64-bit IDs and ack using either encoding field', () => {
  for (const encoding of [false,true]) {
    const {messages} = decodeFrame(frame(encoding)); assert.equal(messages.length,2);
    const chat=normalize(messages[0],{roomId:'123'}),gift=normalize(messages[1],{roomId:'123'});
    assert.equal(chat.user.id,'9007199254740993111');assert.equal(chat.user.nickname,'小鱼🐟');assert.equal(chat.data.content,'真实协议测试');assert.equal(chat.ts,1793149200000);
    assert.equal(gift.data.giftName,'小心心');assert.equal(gift.data.repeatCount,3);assert.equal(gift.data.repeatEnd,true);assert.equal(gift.data.groupId,'9007199254740993555');
    assert.deepEqual(decodeAckInfo(frame(encoding)),{logId:'9007199254740993444',needAck:true,internalExt:'fixture-ack'});
  }
});

test('Lightweight session receives local WebSocket frames, sends ACK and heartbeat, and stops cleanly', async () => {
  const server=new WebSocketServer({host:'127.0.0.1',port:0}); await once(server,'listening');
  const originalFetch=globalThis.fetch;
  globalThis.fetch=async()=>new Response('"roomId":"456";"room":{"id_str":"456","status":2}');
  const messages=[],sent=[];
  const session=new LightweightSession('123',{cookie:'ttwid=local-fixture',onMessage:(raw,meta)=>messages.push(normalize(raw,meta))});
  session.buildWssUrl=()=>`ws://127.0.0.1:${server.address().port}`;
  server.on('connection',socket=>{socket.on('message',data=>sent.push(root.lookupType('Push').decode(data)));socket.send(frame());});
  try {
    session.start();assert.equal(await session.waitForLive(3000),true);assert.equal(messages.length,2);
    await new Promise(resolve=>setTimeout(resolve,5200));
    assert.ok(sent.some(p=>p.payloadType==='ack'&&Buffer.from(p.payload).toString()==='fixture-ack'));
    assert.ok(sent.some(p=>p.payloadType==='hb'));assert.equal(session.stats().wsCount,1);
    await session.stop();assert.equal(session.stats().wsCount,0);
  } finally {globalThis.fetch=originalFetch;await session.stop();for(const s of server.clients)s.terminate();await new Promise(resolve=>server.close(resolve));}
});

test('stopping during HTTP resolution never opens a late WebSocket', async () => {
  const originalFetch=globalThis.fetch;
  let release;
  globalThis.fetch=()=>new Promise(resolve=>{release=()=>resolve(new Response('"roomId":"456"'));});
  const session=new LightweightSession('123',{cookie:'ttwid=local-fixture',onMessage:()=>assert.fail('late event')});
  let websocketBuilt=false;session.buildWssUrl=()=>{websocketBuilt=true;return 'ws://127.0.0.1:1';};
  try {session.start();await new Promise(resolve=>setImmediate(resolve));await session.stop();release();await new Promise(resolve=>setTimeout(resolve,50));assert.equal(websocketBuilt,false);}
  finally {globalThis.fetch=originalFetch;await session.stop();}
});

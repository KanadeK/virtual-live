// Independent wire acceptance against production dist; no real Bilibili or provider requests.
import {createRequire} from 'node:module';
import {brotliCompressSync,deflateSync,gzipSync} from 'node:zlib';
import fs from 'node:fs/promises';
import path from 'node:path';
import assert from 'node:assert/strict';
import {root,delay} from './verify-support.mjs';
import {PacketDecoder,parseJson} from '../collector/dist/bilibili/protocol.js';
import {normalize} from '../collector/dist/bilibili/normalize.js';
import {BilibiliSession} from '../collector/dist/bilibili/session.js';
const {WebSocket,WebSocketServer}=createRequire(path.join(root,'collector/package.json'))('ws');
const evidence=path.resolve(root,'../work/coordination/bilibili');await fs.mkdir(evidence,{recursive:true});
// Build bytes separately from the production packet() encoder.
function wire(op,body='',version=1,headerSize=16){const bytes=Buffer.isBuffer(body)?body:Buffer.from(body),header=Buffer.alloc(headerSize);header.writeUInt32BE(headerSize+bytes.length,0);header.writeUInt16BE(headerSize,4);header.writeUInt16BE(version,6);header.writeUInt32BE(op,8);header.writeUInt32BE(1,12);return Buffer.concat([header,bytes]);}
const timestamp=Math.floor(Date.now()/1000),roomId='9007199254740993123',uid='9007199254740993127',giftId='9007199254740993101';
const chat=`{"cmd":"DANMU_MSG:4:0:2:2:2:0","info":[[0,1,25,16777215,${timestamp},1,0,0,0,0,0,0,0,0,0,{"extra":"{\\"id\\":9007199254740993199}"}],"独立原始协议弹幕",[${uid},"原始协议观众"]]}`;
const member=`{"cmd":"INTERACT_WORD","data":{"uid":${uid},"uname":"原始协议观众","msg_type":1,"timestamp":${timestamp},"id":9007199254740993198}}`;
const gift=`{"cmd":"SEND_GIFT","data":{"uid":${uid},"uname":"原始协议观众","giftId":${giftId},"giftName":"小心心","num":1,"timestamp":${timestamp},"tid":9007199254740993197,"batch_combo_id":9007199254740993196,"batch_combo_num":1}}`;
const combo=`{"cmd":"COMBO_SEND","data":{"uid":${uid},"uname":"原始协议观众","gift_id":${giftId},"gift_name":"小心心","combo_num":3,"combo_id":9007199254740993196,"timestamp":${timestamp}}}`;
const report={kind:'OFFLINE_REAL_WIRE_PRODUCTION_COLLECTOR',checkedAt:new Date().toISOString(),passed:false,realPlatformConnected:false,realProviderRequests:0,checks:[]};
let server,session;
try{
  const plain=Buffer.concat([wire(5,chat,0,20),wire(5,member,0),wire(5,gift,0),wire(5,combo,0)]);
  const nested=wire(5,brotliCompressSync(wire(5,deflateSync(plain),2)),3);
  const heartbeat=Buffer.alloc(4);heartbeat.writeUInt32BE(4321);
  const combined=Buffer.concat([wire(8,'{"code":0}'),nested,wire(3,heartbeat)]),decoder=new PacketDecoder(),decoded=[];
  for(let offset=0;offset<combined.length;offset+=7)decoded.push(...decoder.feed(combined.subarray(offset,offset+7)));decoder.finish();
  assert.deepEqual(decoded.map(row=>row.operation),[8,5,5,5,5,3]);
  const normalized=decoded.filter(row=>row.operation===5).flatMap(row=>normalize(parseJson(row.body.toString()),roomId));assert.deepEqual(normalized.map(row=>row.type),['chat','member','gift','gift']);assert.ok(normalized.every(row=>row.user.id===uid&&row.roomId===roomId));assert.equal(normalized[2].data.giftId,giftId);assert.equal(normalized[2].data.transactionId,'9007199254740993197');assert.equal(normalized[2].data.groupId,'9007199254740993196');
  assert.equal(new PacketDecoder().feed(wire(5,gzipSync(wire(5,chat,0)),2))[0].body.toString(),chat);
  const json=parseJson(`{"id":${uid},"text":"literal ${uid} and escaped \\\"quotes\\\"","n":42}`);assert.equal(json.id,uid);assert.equal(json.n,42);assert.equal(json.text,`literal ${uid} and escaped "quotes"`);
  assert.deepEqual(normalize(parseJson(member.replace('"msg_type":1','"msg_type":2')),roomId),[]);assert.deepEqual(normalize({cmd:'GUARD_BUY',data:{uid}},roomId),[]);
  for(const invalid of [Buffer.from([0,0,0,1,0,16,0,1,0,0,0,5,0,0,0,1]),wire(5,Buffer.from('not-zlib'),2)])assert.throws(()=>new PacketDecoder().feed(invalid),/数据格式异常/);
  const partial=new PacketDecoder();partial.feed(combined.subarray(0,10));assert.throws(()=>partial.finish());
  report.checks.push('independent big-endian framing and extended header','nested Brotli→zlib and gzip','coalesced packets and seven-byte chunk fragmentation','raw numeric JSON IDs beyond 2^53 preserved in UID/gift/group/transaction','DANMU_MSG command variant, member and gift mapping','follow and unsupported guard ignored','invalid lengths/compression/truncation fail closed');
  server=new WebSocketServer({host:'127.0.0.1',port:0});await new Promise(resolve=>server.once('listening',resolve));
  const url=`ws://127.0.0.1:${server.address().port}`,states=[],events=[];let authReceived=false,heartbeats=0,disposed=0,connections=0;
  server.on('connection',socket=>{
    connections++;const parser=new PacketDecoder();socket.on('error',()=>{});socket.on('message',data=>{
      for(const frame of parser.feed(data)){
        if(frame.operation===7){authReceived=true;const auth=parseJson(frame.body.toString());assert.equal(auth.key,'fixture-server-token');
          // A business message before auth success must not be forwarded.
          socket.send(wire(5,chat,0));setTimeout(()=>{if(socket.readyState===WebSocket.OPEN)socket.send(wire(8,'{"code":0}'));},80);
        }else if(frame.operation===2){heartbeats++;socket.send(wire(3,heartbeat));if(heartbeats===1)socket.send(nested);}
      }
    });
  });
  const fakeApi={resolve:async()=>({roomId,liveStatus:1}),connectInfo:async()=>({roomId,urls:[url],hasLogin:false,authBody:JSON.stringify({uid:0,roomid:123,protover:3,key:'fixture-server-token'})}),dispose(){disposed++;}};
  session=new BilibiliSession('1001','',{api:fakeApi,heartbeatMs:30,authTimeoutMs:500,staleMs:500,backoffMs:20,onStatus:state=>states.push(state),onEvent:event=>events.push(event),onResolved:()=>{}});const running=session.start();
  for(let attempt=0;attempt<100&&!authReceived;attempt++)await delay(10);assert.equal(authReceived,true);assert.ok(!states.some(row=>row.state==='live'));assert.equal(events.length,0);
  for(let attempt=0;attempt<100&&events.length<4;attempt++)await delay(10);assert.ok(states.some(row=>row.state==='live'));assert.equal(events.length,4);assert.ok(heartbeats>=1);assert.ok(events.every(row=>row.user.id===uid));
  await session.stop();await running;const count=events.length;await delay(90);assert.equal(events.length,count);assert.ok(disposed>0);report.checks.push('real local WS handshake sends auth without prematurely becoming live','messages before auth success ignored','server auth code0 gates live and event forwarding','actual socket heartbeats and nested wire events','stop closes socket and prevents late events');report.socket={connections,heartbeats,events:events.length,authReceived};report.passed=true;
  await fs.writeFile(path.join(evidence,'bilibili-wire-check.json'),JSON.stringify(report,null,2)+'\n');console.log('PASS: independent raw Bilibili wire, auth gating, heartbeat, compression, int64 and stop. No external calls.');
}catch(error){report.failure=String(error);await fs.writeFile(path.join(evidence,'bilibili-wire-failed.json'),JSON.stringify(report,null,2)+'\n');throw error;}
finally{await session?.stop();if(server){for(const client of server.clients)client.terminate();await new Promise(resolve=>server.close(resolve));}}

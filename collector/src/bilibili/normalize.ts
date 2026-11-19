import { createHash } from 'node:crypto';
import protobuf from 'protobufjs';
import { decimalId } from './api.js';
import { parseJson } from './protocol.js';

// Minimal field declarations from the current wire schema; unrelated UI/material fields are skipped.
const schemas = protobuf.parse(`syntax="proto3";
message Interact { uint64 uid=1; string uname=2; int32 msgType=5; int64 timestamp=7; }
message GiftItem { uint64 giftId=1; string giftName=2; uint32 num=3; string tid=9; int64 timestamp=10; string rnd=12; }
message Gifts { uint64 uid=1; string uname=2; repeated GiftItem giftList=10; }
`).root;
const string = (value: unknown): string => value == null ? '' : String(value);
const positive = (value: unknown): number => Math.max(0, Math.min(1000000, Math.floor(Number(value) || 0)));
const validId = (value: unknown): string => string(value) && string(value) !== '0' ? string(value).slice(0, 200) : '';
const hash = (value: unknown): string => createHash('sha256').update(JSON.stringify(value)).digest('hex');
function timestamp(value: unknown): number {
  const time = Number(value);
  // Missing timestamps remain historical, never become a fresh event after reconnect.
  if (!Number.isFinite(time) || time <= 0) return 0;
  return Math.floor(time < 100000000000 ? time * 1000 : time);
}
function decodePb(name: string, base64: unknown): any {
  if (typeof base64 !== 'string' || base64.length > 512000 || !/^[A-Za-z0-9+/]*={0,2}$/.test(base64)) return null;
  try { const type = schemas.lookupType(name); return type.toObject(type.decode(Buffer.from(base64, 'base64')), { longs: String, defaults: true }); }
  catch { return null; }
}
export interface BilibiliEvent {
  platform: 'bilibili'; id: string; roomId: string; type: 'chat'|'member'|'gift'; ts: number;
  user: {id:string; nickname:string}; data: Record<string, unknown>;
}

/** No raw command is forwarded: credentials, unrelated PII and unsupported message kinds stay out of JSONL. */
export function normalize(command: any, roomId: string): BilibiliEvent[] {
  const cmd = string(command?.cmd).split(':', 1)[0];
  if (cmd === 'INTERACT_WORD_V2') {
    const value = decodePb('Interact', command.data?.pb);
    return value ? normalize({ cmd:'INTERACT_WORD', data:{uid:value.uid, uname:value.uname, msg_type:value.msgType, timestamp:value.timestamp} }, roomId) : [];
  }
  if (cmd === 'SEND_GIFT_V2') {
    const value = decodePb('Gifts', command.data?.pb);
    return value ? value.giftList.flatMap((gift: any) => normalize({cmd:'SEND_GIFT', data:{...gift, uid:value.uid, uname:value.uname}}, roomId)) : [];
  }
  let userId = '', nickname = '', ts = 0, type: BilibiliEvent['type'], identity: unknown, data: Record<string, unknown>;
  if (cmd === 'DANMU_MSG') {
    const info = command.info;
    if (!Array.isArray(info) || !Array.isArray(info[0]) || !Array.isArray(info[2]) || typeof info[1] !== 'string') return [];
    userId = decimalId(info[2][0]); nickname = string(info[2][1]); ts = timestamp(info[0][4]); type = 'chat';
    let extra: any = info[0][15]?.extra;
    if (typeof extra === 'string') { try { extra = parseJson(extra); } catch { extra = null; } }
    identity = validId(extra?.id_str) || validId(extra?.id) || [userId, ts, info[0][5], info[1]];
    data = { content: info[1].slice(0,2000) };
  } else if (cmd === 'INTERACT_WORD') {
    const value = command.data;
    if (!value || Number(value.msg_type) !== 1) return []; // follow/share/like are not arrivals
    userId = decimalId(value.uid); nickname = string(value.uname); ts = timestamp(value.timestamp); type = 'member';
    identity = validId(value.id_str) || validId(value.id) || [userId, ts, value.trigger_time ?? '']; data = {};
  } else if (cmd === 'SEND_GIFT' || cmd === 'COMBO_SEND') {
    const value = command.data;
    if (!value) return [];
    userId = decimalId(value.uid); nickname = string(value.uname);
    ts = timestamp(value.timestamp ?? value.ts); type = 'gift';
    const giftId = decimalId(value.giftId ?? value.gift_id);
    if (!giftId) return [];
    const batch = value.batch_combo_send, combo = value.combo_send;
    const groupId = validId(value.batch_combo_id) || validId(batch?.batch_combo_id) || validId(value.combo_id) || validId(combo?.combo_id);
    const total = positive(value.batch_combo_num ?? batch?.batch_combo_num ?? value.combo_num ?? combo?.combo_num);
    const transactionId = validId(value.tid) || validId(value.rnd);
    const num = cmd === 'SEND_GIFT' ? positive(value.num) : 0;
    if (cmd === 'COMBO_SEND' && (!groupId || total < 1)) return [];
    if (cmd === 'SEND_GIFT' && num < 1) return [];
    // COMBO_SEND often omits a timestamp. It may only update a known group downstream;
    // its absence must never allow an old, unknown cumulative group to generate thanks.
    data = { giftId, giftName:string(value.giftName ?? value.gift_name).slice(0,100) || '礼物',
      coinType: string(value.coin_type), totalCoin: value.total_coin == null ? null : string(value.total_coin),
      unitPriceCoin: value.price == null ? null : string(value.price),
      groupId, transactionId, num, repeatCount:total, countMode:cmd === 'COMBO_SEND' ? 'total' : 'delta',
      repeatEnd:cmd === 'COMBO_SEND' || value.combo_end === true };
    identity = cmd === 'SEND_GIFT' ? [giftId, transactionId || [ts,num,groupId,total]] : [giftId,groupId,total];
  } else return [];
  if (!userId || userId === '0') return [];
  return [{platform:'bilibili', id:`${type}-${hash([userId,identity])}`, roomId, type, ts,
    user:{id:userId,nickname:nickname.slice(0,80) || '匿名观众'}, data}];
}

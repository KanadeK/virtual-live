import { brotliDecompressSync, unzipSync } from 'node:zlib';

export const MAX_PACKET_BYTES = 4 * 1024 * 1024;
export const MAX_DECODED_BYTES = 8 * 1024 * 1024;
export class BiliError extends Error {
  constructor(public readonly code: string, message: string, public readonly retryable = false) { super(message); }
}

/** Preserve decimal IDs before JSON.parse can round an int64. Strings stay byte-for-byte intact. */
export function parseJson(text: string): any {
  let result = '', index = 0;
  while (index < text.length) {
    const start = index, ch = text[index++];
    if (ch === '"') {
      while (index < text.length) {
        const next = text[index++];
        if (next === '\\') index++;
        else if (next === '"') break;
      }
      result += text.slice(start, index);
    } else if (ch === '-' || /[0-9]/.test(ch)) {
      while (index < text.length && /[0-9eE.+-]/.test(text[index])) index++;
      const token = text.slice(start, index);
      result += /^-?\d+$/.test(token) && !Number.isSafeInteger(Number(token)) ? JSON.stringify(token) : token;
    } else result += ch;
  }
  return JSON.parse(result);
}

export function packet(operation: number, body: string | Buffer = '', version = 1): Buffer {
  const bytes = Buffer.isBuffer(body) ? body : Buffer.from(body);
  const header = Buffer.alloc(16);
  header.writeUInt32BE(16 + bytes.length, 0); header.writeUInt16BE(16, 4);
  header.writeUInt16BE(version, 6); header.writeUInt32BE(operation, 8); header.writeUInt32BE(1, 12);
  return Buffer.concat([header, bytes]);
}
export interface Packet { operation: number; version: number; body: Buffer }

/** Bounded streaming framing, including recursively packed gzip/zlib and Brotli bodies. */
export class PacketDecoder {
  private pending = Buffer.alloc(0);
  feed(chunk: Buffer): Packet[] {
    if (this.pending.length + chunk.length > MAX_DECODED_BYTES) throw this.invalid();
    const input = Buffer.concat([this.pending, chunk]);
    const output: Packet[] = [], budget = { bytes: 0, packets: 0 };
    const consumed = this.decode(input, output, budget, 0, true);
    this.pending = Buffer.from(input.subarray(consumed));
    return output;
  }
  finish(): void { if (this.pending.length) throw this.invalid(); }
  private invalid(): BiliError { return new BiliError('PROTOCOL_ERROR', 'B站弹幕数据格式异常'); }
  private decode(input: Buffer, out: Packet[], budget: {bytes: number; packets: number}, depth: number, partial: boolean): number {
    if (depth > 4) throw this.invalid();
    let offset = 0;
    while (offset < input.length) {
      const remaining = input.length - offset;
      if (remaining < 16) { if (partial) break; throw this.invalid(); }
      const size = input.readUInt32BE(offset), header = input.readUInt16BE(offset + 4);
      const version = input.readUInt16BE(offset + 6), operation = input.readUInt32BE(offset + 8);
      if (size < 16 || size > MAX_PACKET_BYTES || header < 16 || header > 1024 || header > size || version > 3) throw this.invalid();
      if (remaining < size) { if (partial) break; throw this.invalid(); }
      if (++budget.packets > 2048) throw this.invalid();
      const body = input.subarray(offset + header, offset + size);
      if (version === 2 || version === 3) {
        let decoded: Buffer;
        try {
          const options = { maxOutputLength: Math.max(1, MAX_DECODED_BYTES - budget.bytes) };
          decoded = version === 3 ? brotliDecompressSync(body, options) : unzipSync(body, options);
        } catch { throw this.invalid(); }
        budget.bytes += decoded.length;
        if (budget.bytes > MAX_DECODED_BYTES) throw this.invalid();
        this.decode(decoded, out, budget, depth + 1, false);
      } else out.push({ operation, version, body });
      offset += size;
    }
    return offset;
  }
}

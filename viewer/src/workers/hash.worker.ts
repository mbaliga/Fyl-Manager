/// <reference lib="webworker" />
// Streaming SHA-256 (WebCrypto has no incremental API). Messages: {type:"chunk", bytes} … {type:"end"}.
const K = new Uint32Array([
  0x428a2f98,0x71374491,0xb5c0fbcf,0xe9b5dba5,0x3956c25b,0x59f111f1,0x923f82a4,0xab1c5ed5,0xd807aa98,0x12835b01,0x243185be,0x550c7dc3,0x72be5d74,0x80deb1fe,0x9bdc06a7,0xc19bf174,
  0xe49b69c1,0xefbe4786,0x0fc19dc6,0x240ca1cc,0x2de92c6f,0x4a7484aa,0x5cb0a9dc,0x76f988da,0x983e5152,0xa831c66d,0xb00327c8,0xbf597fc7,0xc6e00bf3,0xd5a79147,0x06ca6351,0x14292967,
  0x27b70a85,0x2e1b2138,0x4d2c6dfc,0x53380d13,0x650a7354,0x766a0abb,0x81c2c92e,0x92722c85,0xa2bfe8a1,0xa81a664b,0xc24b8b70,0xc76c51a3,0xd192e819,0xd6990624,0xf40e3585,0x106aa070,
  0x19a4c116,0x1e376c08,0x2748774c,0x34b0bcb5,0x391c0cb3,0x4ed8aa4a,0x5b9cca4f,0x682e6ff3,0x748f82ee,0x78a5636f,0x84c87814,0x8cc70208,0x90befffa,0xa4506ceb,0xbef9a3f7,0xc67178f2,
]);
const H = new Uint32Array([0x6a09e667,0xbb67ae85,0x3c6ef372,0xa54ff53a,0x510e527f,0x9b05688c,0x1f83d9ab,0x5be0cd19]);
const W = new Uint32Array(64);
const buf = new Uint8Array(64);
let bufLen = 0;
let total = 0;

function block(b: Uint8Array, o: number) {
  for (let i = 0; i < 16; i++) W[i] = (b[o + 4 * i]! << 24) | (b[o + 4 * i + 1]! << 16) | (b[o + 4 * i + 2]! << 8) | b[o + 4 * i + 3]!;
  for (let i = 16; i < 64; i++) {
    const a = W[i - 15]!, c = W[i - 2]!;
    const s0 = ((a >>> 7) | (a << 25)) ^ ((a >>> 18) | (a << 14)) ^ (a >>> 3);
    const s1 = ((c >>> 17) | (c << 15)) ^ ((c >>> 19) | (c << 13)) ^ (c >>> 10);
    W[i] = (W[i - 16]! + s0 + W[i - 7]! + s1) | 0;
  }
  let a = H[0]!, b1 = H[1]!, c = H[2]!, d = H[3]!, e = H[4]!, f = H[5]!, g = H[6]!, h = H[7]!;
  for (let i = 0; i < 64; i++) {
    const S1 = ((e >>> 6) | (e << 26)) ^ ((e >>> 11) | (e << 21)) ^ ((e >>> 25) | (e << 7));
    const ch = (e & f) ^ (~e & g);
    const t1 = (h + S1 + ch + K[i]! + W[i]!) | 0;
    const S0 = ((a >>> 2) | (a << 30)) ^ ((a >>> 13) | (a << 19)) ^ ((a >>> 22) | (a << 10));
    const mj = (a & b1) ^ (a & c) ^ (b1 & c);
    const t2 = (S0 + mj) | 0;
    h = g; g = f; f = e; e = (d + t1) | 0; d = c; c = b1; b1 = a; a = (t1 + t2) | 0;
  }
  H[0] = (H[0]! + a) | 0; H[1] = (H[1]! + b1) | 0; H[2] = (H[2]! + c) | 0; H[3] = (H[3]! + d) | 0;
  H[4] = (H[4]! + e) | 0; H[5] = (H[5]! + f) | 0; H[6] = (H[6]! + g) | 0; H[7] = (H[7]! + h) | 0;
}

function update(data: Uint8Array) {
  total += data.length;
  let i = 0;
  if (bufLen) {
    while (i < data.length && bufLen < 64) buf[bufLen++] = data[i++]!;
    if (bufLen === 64) { block(buf, 0); bufLen = 0; }
  }
  for (; i + 64 <= data.length; i += 64) block(data, i);
  while (i < data.length) buf[bufLen++] = data[i++]!;
}

function finish(): string {
  const bits = total * 8;
  buf[bufLen++] = 0x80;
  if (bufLen > 56) { buf.fill(0, bufLen); block(buf, 0); bufLen = 0; }
  buf.fill(0, bufLen, 56);
  const dv = new DataView(buf.buffer);
  dv.setUint32(56, Math.floor(bits / 2 ** 32));
  dv.setUint32(60, bits >>> 0);
  block(buf, 0);
  return [...H].map((x) => (x >>> 0).toString(16).padStart(8, "0")).join("");
}

self.onmessage = (e: MessageEvent) => {
  const m = e.data;
  if (m.type === "chunk") { update(new Uint8Array(m.bytes)); (self as unknown as Worker).postMessage({ type: "ack" }); }
  else if (m.type === "end") (self as unknown as Worker).postMessage({ type: "done", hash: finish() });
};

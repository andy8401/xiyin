// Minimal AES-128-ECB decryptor (pure JS, no dependencies).
// The web player encrypts audio URLs with its public protocol key; this is not
// an account credential. Verified against Node's crypto in tests/aes.test.mjs.

function gfMultiply(a, b) {
  let result = 0;
  for (let bit = 0; bit < 8; bit += 1) {
    if (b & 1) result ^= a;
    const high = a & 0x80;
    a = (a << 1) & 0xff;
    if (high) a ^= 0x1b;
    b >>= 1;
  }
  return result & 0xff;
}

function gfPower(base, exponent) {
  let result = 1;
  let value = base;
  let power = exponent;
  while (power > 0) {
    if (power & 1) result = gfMultiply(result, value);
    value = gfMultiply(value, value);
    power >>= 1;
  }
  return result;
}

function rotateLeft(value, bits) {
  return ((value << bits) | (value >> (8 - bits))) & 0xff;
}

function buildAesTables() {
  const sbox = new Uint8Array(256);
  const inverseSbox = new Uint8Array(256);
  for (let i = 0; i < 256; i += 1) {
    const inverse = i === 0 ? 0 : gfPower(i, 254);
    const substituted = inverse ^ rotateLeft(inverse, 1) ^ rotateLeft(inverse, 2) ^ rotateLeft(inverse, 3) ^ rotateLeft(inverse, 4) ^ 0x63;
    sbox[i] = substituted;
    inverseSbox[substituted] = i;
  }
  return { sbox, inverseSbox };
}

const AES_TABLES = buildAesTables();

function expandKey(key) {
  const words = new Uint8Array(176);
  words.set(key);
  let rcon = 1;
  for (let i = 16; i < 176; i += 4) {
    let a = words[i - 4];
    let b = words[i - 3];
    let c = words[i - 2];
    let d = words[i - 1];
    if (i % 16 === 0) {
      const first = a;
      a = AES_TABLES.sbox[b] ^ rcon;
      b = AES_TABLES.sbox[c];
      c = AES_TABLES.sbox[d];
      d = AES_TABLES.sbox[first];
      rcon = gfMultiply(rcon, 2);
    }
    words[i] = words[i - 16] ^ a;
    words[i + 1] = words[i - 15] ^ b;
    words[i + 2] = words[i - 14] ^ c;
    words[i + 3] = words[i - 13] ^ d;
  }
  return words;
}

function addRoundKey(state, roundKeys, round) {
  for (let column = 0; column < 4; column += 1) {
    for (let row = 0; row < 4; row += 1) {
      state[row + 4 * column] ^= roundKeys[round * 16 + column * 4 + row];
    }
  }
}

function invShiftRows(state) {
  const previous = state.slice();
  for (let row = 1; row < 4; row += 1) {
    for (let column = 0; column < 4; column += 1) {
      state[row + 4 * column] = previous[row + 4 * ((column - row + 4) % 4)];
    }
  }
}

function invSubBytes(state) {
  for (let i = 0; i < 16; i += 1) state[i] = AES_TABLES.inverseSbox[state[i]];
}

function invMixColumns(state) {
  for (let column = 0; column < 4; column += 1) {
    const a = state[4 * column];
    const b = state[4 * column + 1];
    const c = state[4 * column + 2];
    const d = state[4 * column + 3];
    state[4 * column] = gfMultiply(a, 14) ^ gfMultiply(b, 11) ^ gfMultiply(c, 13) ^ gfMultiply(d, 9);
    state[4 * column + 1] = gfMultiply(a, 9) ^ gfMultiply(b, 14) ^ gfMultiply(c, 11) ^ gfMultiply(d, 13);
    state[4 * column + 2] = gfMultiply(a, 13) ^ gfMultiply(b, 9) ^ gfMultiply(c, 14) ^ gfMultiply(d, 11);
    state[4 * column + 3] = gfMultiply(a, 11) ^ gfMultiply(b, 13) ^ gfMultiply(c, 9) ^ gfMultiply(d, 14);
  }
}

function stripPadding(bytes, strict) {
  if (!strict) return bytes;
  const padding = bytes[bytes.length - 1];
  if (padding < 1 || padding > 16 || padding > bytes.length) throw new Error("AES: invalid padding");
  for (let i = bytes.length - padding; i < bytes.length; i += 1) {
    if (bytes[i] !== padding) throw new Error("AES: invalid padding");
  }
  return bytes.subarray(0, bytes.length - padding);
}

export function aes128EcbDecrypt(cipher, key, strictPadding = true) {
  if (!(cipher instanceof Uint8Array) || !cipher.length || cipher.length % 16 !== 0) throw new Error("AES: invalid ciphertext");
  if (!(key instanceof Uint8Array) || key.length !== 16) throw new Error("AES: invalid key");
  const roundKeys = expandKey(key);
  const output = new Uint8Array(cipher.length);
  const state = new Uint8Array(16);
  for (let offset = 0; offset < cipher.length; offset += 16) {
    state.set(cipher.subarray(offset, offset + 16));
    addRoundKey(state, roundKeys, 10);
    for (let round = 9; round >= 1; round -= 1) {
      invShiftRows(state);
      invSubBytes(state);
      addRoundKey(state, roundKeys, round);
      invMixColumns(state);
    }
    invShiftRows(state);
    invSubBytes(state);
    addRoundKey(state, roundKeys, 0);
    output.set(state, offset);
  }
  return stripPadding(output, strictPadding);
}

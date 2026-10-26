#!/usr/bin/env node
/**
 * VeriBorder block/item texture alpha audit + fixer.
 *
 *   node tools/fix_texture_alpha.js          # report only
 *   node tools/fix_texture_alpha.js --fix    # rewrite offending PNGs
 *
 * House rule: block/item textures carry **binary alpha** - every pixel is either
 * fully opaque (255) or fully transparent (0). Soft drop shadows / glows baked
 * into the alpha channel are not allowed: they produce mip/atlas artefacts and
 * force the item-icon pipeline to threshold anyway.
 *
 * Fix policy: threshold at 128. Pixels >= 128 become 255, the rest become 0.
 * The colour channel is untouched, so an alpha-13 shadow layer simply disappears
 * instead of turning into a visible dark rectangle (which is what forcing every
 * pixel to 255 would do). Pass --opaque to force alpha 255 everywhere instead.
 */
'use strict';

const fs = require('fs');
const path = require('path');
const zlib = require('zlib');

const ROOT = path.resolve(__dirname, '..');
const TEX_ROOT = path.join(ROOT, 'src/main/resources/assets/veriborder/textures');
const FIX = process.argv.includes('--fix');
const FORCE_OPAQUE = process.argv.includes('--opaque');
const THRESHOLD = 128;

const CRC_TABLE = (() => {
  const t = new Int32Array(256);
  for (let n = 0; n < 256; n++) {
    let c = n;
    for (let k = 0; k < 8; k++) c = (c & 1) ? (0xedb88320 ^ (c >>> 1)) : (c >>> 1);
    t[n] = c;
  }
  return t;
})();
function crc32(buf) {
  let c = 0xffffffff;
  for (let i = 0; i < buf.length; i++) c = CRC_TABLE[(c ^ buf[i]) & 0xff] ^ (c >>> 8);
  return (c ^ 0xffffffff) >>> 0;
}
function chunk(type, data) {
  const len = Buffer.alloc(4);
  len.writeUInt32BE(data.length, 0);
  const t = Buffer.from(type, 'ascii');
  const crc = Buffer.alloc(4);
  crc.writeUInt32BE(crc32(Buffer.concat([t, data])), 0);
  return Buffer.concat([len, t, data, crc]);
}
function writePng(w, h, rgba) {
  const raw = Buffer.alloc((w * 4 + 1) * h);
  for (let y = 0; y < h; y++) {
    raw[y * (w * 4 + 1)] = 0;
    rgba.copy(raw, y * (w * 4 + 1) + 1, y * w * 4, (y + 1) * w * 4);
  }
  const ihdr = Buffer.alloc(13);
  ihdr.writeUInt32BE(w, 0);
  ihdr.writeUInt32BE(h, 4);
  ihdr[8] = 8; ihdr[9] = 6;
  return Buffer.concat([
    Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]),
    chunk('IHDR', ihdr),
    chunk('IDAT', zlib.deflateSync(raw, { level: 9 })),
    chunk('IEND', Buffer.alloc(0))
  ]);
}

function readPng(file) {
  const buf = fs.readFileSync(file);
  if (buf.readUInt32BE(0) !== 0x89504e47) throw new Error('not a png: ' + file);
  let pos = 8, w = 0, h = 0, depth = 0, color = 0, interlace = 0;
  const idat = [];
  let plte = null, trns = null;
  while (pos < buf.length) {
    const len = buf.readUInt32BE(pos);
    const type = buf.toString('ascii', pos + 4, pos + 8);
    const data = buf.subarray(pos + 8, pos + 8 + len);
    pos += 12 + len;
    if (type === 'IHDR') {
      w = data.readUInt32BE(0); h = data.readUInt32BE(4);
      depth = data[8]; color = data[9]; interlace = data[12];
    } else if (type === 'IDAT') idat.push(data);
    else if (type === 'PLTE') plte = Buffer.from(data);
    else if (type === 'tRNS') trns = Buffer.from(data);
    else if (type === 'IEND') break;
  }
  if (depth !== 8) throw new Error('bit depth ' + depth + ' unsupported: ' + file);
  if (interlace !== 0) throw new Error('interlaced png unsupported: ' + file);
  const channels = { 0: 1, 2: 3, 3: 1, 4: 2, 6: 4 }[color];
  if (!channels) throw new Error('colour type ' + color + ' unsupported: ' + file);
  const raw = zlib.inflateSync(Buffer.concat(idat));
  const stride = w * channels;
  const out = Buffer.alloc(w * h * channels);
  let prev = Buffer.alloc(stride);
  for (let y = 0; y < h; y++) {
    const ft = raw[y * (stride + 1)];
    const line = raw.subarray(y * (stride + 1) + 1, (y + 1) * (stride + 1));
    const cur = Buffer.alloc(stride);
    for (let x = 0; x < stride; x++) {
      const a = x >= channels ? cur[x - channels] : 0;
      const b = prev[x];
      const c = x >= channels ? prev[x - channels] : 0;
      let v = line[x];
      if (ft === 1) v = (v + a) & 255;
      else if (ft === 2) v = (v + b) & 255;
      else if (ft === 3) v = (v + ((a + b) >> 1)) & 255;
      else if (ft === 4) {
        const p = a + b - c, pa = Math.abs(p - a), pb = Math.abs(p - b), pc = Math.abs(p - c);
        v = (v + (pa <= pb && pa <= pc ? a : pb <= pc ? b : c)) & 255;
      }
      cur[x] = v;
    }
    cur.copy(out, y * stride);
    prev = cur;
  }
  const rgba = Buffer.alloc(w * h * 4);
  for (let i = 0; i < w * h; i++) {
    if (color === 6) {
      rgba[i * 4] = out[i * 4]; rgba[i * 4 + 1] = out[i * 4 + 1];
      rgba[i * 4 + 2] = out[i * 4 + 2]; rgba[i * 4 + 3] = out[i * 4 + 3];
    } else if (color === 2) {
      rgba[i * 4] = out[i * 3]; rgba[i * 4 + 1] = out[i * 3 + 1];
      rgba[i * 4 + 2] = out[i * 3 + 2]; rgba[i * 4 + 3] = 255;
    } else if (color === 3) {
      const idx = out[i];
      rgba[i * 4] = plte[idx * 3]; rgba[i * 4 + 1] = plte[idx * 3 + 1]; rgba[i * 4 + 2] = plte[idx * 3 + 2];
      rgba[i * 4 + 3] = trns && idx < trns.length ? trns[idx] : 255;
    } else if (color === 4) {
      const g = out[i * 2];
      rgba[i * 4] = g; rgba[i * 4 + 1] = g; rgba[i * 4 + 2] = g; rgba[i * 4 + 3] = out[i * 2 + 1];
    } else {
      const g = out[i];
      rgba[i * 4] = g; rgba[i * 4 + 1] = g; rgba[i * 4 + 2] = g; rgba[i * 4 + 3] = 255;
    }
  }
  return { w, h, rgba };
}

const files = [];
for (const sub of ['block', 'item']) {
  const dir = path.join(TEX_ROOT, sub);
  if (!fs.existsSync(dir)) continue;
  for (const f of fs.readdirSync(dir).filter(n => n.endsWith('.png')).sort()) {
    files.push({ sub, file: path.join(dir, f), name: `${sub}/${f}` });
  }
}

// Blast radius guard: `_glass` panels of the other devices and the EGateRenderer
// door-leaf textures are translucent BY DESIGN (they are drawn on a translucent
// render layer), so they must never be flattened by a blanket --fix. They are
// only touched when named explicitly on the command line.
const INTENTIONAL = /_(glass|leaf)\.png$/;
const named = process.argv.slice(2).filter(a => !a.startsWith('--'));
const wanted = f => named.length === 0 ? !INTENTIONAL.test(f.name) : named.some(n => f.name.includes(n));

let offenders = 0, pixelsFixed = 0, scanned = 0, skipped = 0;
const rows = [];
for (const f of files) {
  scanned++;
  const { w, h, rgba } = readPng(f.file);
  const hist = new Map();
  let bad = 0;
  for (let i = 0; i < w * h; i++) {
    const a = rgba[i * 4 + 3];
    if (a !== 0 && a !== 255) {
      hist.set(a, (hist.get(a) || 0) + 1);
      bad++;
    }
  }
  if (!bad) continue;
  offenders++;
  const buckets = [...hist.entries()].sort((x, y) => y[1] - x[1])
    .map(([a, n]) => `a${a}x${n}`).join(' ');
  if (!wanted(f)) {
    skipped++;
    rows.push({ name: f.name, w, h, bad, buckets, scope: 'intentional' });
    continue;
  }
  pixelsFixed += bad;
  let toOpaque = 0, toClear = 0;
  for (let i = 0; i < w * h; i++) {
    const a = rgba[i * 4 + 3];
    if (a === 0 || a === 255) continue;
    if (FORCE_OPAQUE || a >= THRESHOLD) { rgba[i * 4 + 3] = 255; toOpaque++; }
    else { rgba[i * 4 + 3] = 0; toClear++; }
  }
  rows.push({ name: f.name, w, h, bad, buckets, toOpaque, toClear, file: f.file, rgba, scope: 'fixable' });
  if (FIX) fs.writeFileSync(f.file, writePng(w, h, rgba));
}

console.log(`scanned ${scanned} textures under textures/{block,item}`);
if (!offenders) {
  console.log('alpha audit: CLEAN - every pixel is 0 or 255');
  process.exit(0);
}
console.log(`non-binary alpha: ${offenders} file(s)`);
for (const r of rows) {
  if (r.scope === 'intentional') {
    console.log(`  ${r.name.padEnd(30)} ${r.w}x${r.h}  bad=${String(r.bad).padStart(4)}  ` +
      `-> LEFT AS IS (translucent by design, not in scope)`);
  } else {
    console.log(`  ${r.name.padEnd(30)} ${r.w}x${r.h}  bad=${String(r.bad).padStart(4)}  ` +
      `-> opaque ${r.toOpaque} / transparent ${r.toClear}   [${r.buckets}]`);
  }
}
if (FIX) console.log(`FIXED ${rows.filter(r => r.scope === 'fixable').length} file(s), ${pixelsFixed} pixel(s)` +
  ` (threshold ${THRESHOLD}${FORCE_OPAQUE ? ', forced opaque' : ''}, colour untouched)`);
else console.log(`fixable now: ${rows.filter(r => r.scope === 'fixable').length} file(s), ${pixelsFixed} pixel(s) - run with --fix`);
process.exit(rows.some(r => r.scope === 'fixable') ? 1 : 0);

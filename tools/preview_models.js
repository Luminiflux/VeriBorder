#!/usr/bin/env node
/*
 * VeriBorder model previewer + validator (no vision model required).
 *
 *   node tools/preview_models.js
 *
 * Renders an isometric PNG of every block model into tools/preview/<id>.png
 * with a small software rasterizer (textured quads, painter's algorithm) and
 * writes tools/preview/metrics.txt plus a machine-checkable validation report.
 * Exit code 1 when any validation rule fails.
 *
 * Optional: tools/preview_models.ps1 wraps this and also composes
 * tools/preview/contact-sheet.png with labels.
 */
'use strict';

const fs = require('fs');
const path = require('path');
const zlib = require('zlib');
const crypto = require('crypto');

const ROOT = path.resolve(__dirname, '..');
const ASSETS = path.join(ROOT, 'src', 'main', 'resources', 'assets', 'veriborder');
const MODEL_DIR = path.join(ASSETS, 'models', 'block');
const TEX_DIR = path.join(ASSETS, 'textures', 'block');
const BS_DIR = path.join(ASSETS, 'blockstates');
const ITEM_DIR = path.join(ASSETS, 'models', 'item');
const OUT = path.join(ROOT, 'tools', 'preview');
const SIZE = 512;
const SS = 2;

fs.mkdirSync(OUT, { recursive: true });

// ---------------------------------------------------------------- PNG codec
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
function writePng(file, w, h, rgba) {
  const raw = Buffer.alloc((w * 4 + 1) * h);
  for (let y = 0; y < h; y++) {
    raw[y * (w * 4 + 1)] = 0;
    rgba.copy(raw, y * (w * 4 + 1) + 1, y * w * 4, (y + 1) * w * 4);
  }
  const ihdr = Buffer.alloc(13);
  ihdr.writeUInt32BE(w, 0);
  ihdr.writeUInt32BE(h, 4);
  ihdr[8] = 8; ihdr[9] = 6; ihdr[10] = 0; ihdr[11] = 0; ihdr[12] = 0;
  const png = Buffer.concat([
    Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]),
    chunk('IHDR', ihdr),
    chunk('IDAT', zlib.deflateSync(raw, { level: 9 })),
    chunk('IEND', Buffer.alloc(0))
  ]);
  fs.writeFileSync(file, png);
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
  if (depth !== 8) throw new Error('unsupported bit depth ' + depth + ' in ' + file);
  if (interlace !== 0) throw new Error('interlaced png unsupported: ' + file);
  const channels = { 0: 1, 2: 3, 3: 1, 4: 2, 6: 4 }[color];
  if (!channels) throw new Error('unsupported color type ' + color + ' in ' + file);
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
    } else if (color === 0) {
      const g = out[i]; rgba[i * 4] = g; rgba[i * 4 + 1] = g; rgba[i * 4 + 2] = g; rgba[i * 4 + 3] = 255;
    } else if (color === 4) {
      const g = out[i * 2]; rgba[i * 4] = g; rgba[i * 4 + 1] = g; rgba[i * 4 + 2] = g; rgba[i * 4 + 3] = out[i * 2 + 1];
    } else if (color === 3) {
      const idx = out[i];
      rgba[i * 4] = plte[idx * 3]; rgba[i * 4 + 1] = plte[idx * 3 + 1]; rgba[i * 4 + 2] = plte[idx * 3 + 2];
      rgba[i * 4 + 3] = trns && idx < trns.length ? trns[idx] : 255;
    }
  }
  return { w, h, data: rgba };
}

// ---------------------------------------------------------------- math
const vsub = (a, b) => [a[0] - b[0], a[1] - b[1], a[2] - b[2]];
const vcross = (a, b) => [a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0]];
const vdot = (a, b) => a[0] * b[0] + a[1] * b[1] + a[2] * b[2];
function vnorm(a) {
  const l = Math.hypot(a[0], a[1], a[2]) || 1;
  return [a[0] / l, a[1] / l, a[2] / l];
}
function rotatePoint(p, rot) {
  if (!rot) return p;
  const a = (rot.angle * Math.PI) / 180;
  const c = Math.cos(a), s = Math.sin(a);
  const o = rot.origin;
  const x = p[0] - o[0], y = p[1] - o[1], z = p[2] - o[2];
  let rx = x, ry = y, rz = z;
  if (rot.axis === 'x') { ry = y * c - z * s; rz = y * s + z * c; }
  else if (rot.axis === 'y') { rx = x * c + z * s; rz = -x * s + z * c; }
  else { rx = x * c - y * s; ry = x * s + y * c; }
  return [rx + o[0], ry + o[1], rz + o[2]];
}

// ---------------------------------------------------------------- models
const textureCache = new Map();
function loadTexture(ref) {
  if (textureCache.has(ref)) return textureCache.get(ref);
  const rel = ref.includes(':') ? ref.split(':')[1] : ref;
  const file = path.join(ASSETS, 'textures', rel + '.png');
  let tex = null;
  if (fs.existsSync(file)) tex = Object.assign({ file }, readPng(file));
  textureCache.set(ref, tex);
  return tex;
}

const FACE_DEF = {
  north: {
    verts: b => [[b.x2, b.y2, b.z1], [b.x1, b.y2, b.z1], [b.x1, b.y1, b.z1], [b.x2, b.y1, b.z1]],
    uv: b => [b.x1, b.x2, 16 - b.y2, 16 - b.y1]
  },
  south: {
    verts: b => [[b.x1, b.y2, b.z2], [b.x2, b.y2, b.z2], [b.x2, b.y1, b.z2], [b.x1, b.y1, b.z2]],
    uv: b => [16 - b.x2, 16 - b.x1, 16 - b.y2, 16 - b.y1]
  },
  east: {
    verts: b => [[b.x2, b.y2, b.z2], [b.x2, b.y2, b.z1], [b.x2, b.y1, b.z1], [b.x2, b.y1, b.z2]],
    uv: b => [16 - b.z2, 16 - b.z1, 16 - b.y2, 16 - b.y1]
  },
  west: {
    verts: b => [[b.x1, b.y2, b.z1], [b.x1, b.y2, b.z2], [b.x1, b.y1, b.z2], [b.x1, b.y1, b.z1]],
    uv: b => [b.z1, b.z2, 16 - b.y2, 16 - b.y1]
  },
  up: {
    verts: b => [[b.x1, b.y2, b.z1], [b.x2, b.y2, b.z1], [b.x2, b.y2, b.z2], [b.x1, b.y2, b.z2]],
    uv: b => [b.x1, b.x2, b.z1, b.z2]
  },
  down: {
    verts: b => [[b.x1, b.y1, b.z2], [b.x2, b.y1, b.z2], [b.x2, b.y1, b.z1], [b.x1, b.y1, b.z1]],
    uv: b => [b.x1, b.x2, 16 - b.z2, 16 - b.z1]
  }
};

function buildQuads(model) {
  const quads = [];
  const missing = [];
  const used = new Set();
  const rotOf = el => el.rotation ? {
    origin: el.rotation.origin || [8, 8, 8],
    axis: el.rotation.axis,
    angle: el.rotation.angle
  } : null;
  for (const el of model.elements || []) {
    const b = {
      x1: el.from[0], y1: el.from[1], z1: el.from[2],
      x2: el.to[0], y2: el.to[1], z2: el.to[2]
    };
    const rot = rotOf(el);
    for (const fname of Object.keys(FACE_DEF)) {
      const face = el.faces && el.faces[fname];
      if (!face || face.texture === undefined) continue;
      let tref = face.texture;
      if (tref.startsWith('#')) tref = model.textures[tref.slice(1)];
      if (!tref) { missing.push(fname + ' face of element'); continue; }
      const tex = loadTexture(tref);
      if (!tex) { missing.push(tref); continue; }
      used.add(tref);
      const def = FACE_DEF[fname];
      const uvs = face.uv || def.uv(b);
      const verts = def.verts(b).map(v => rotatePoint(v, rot));
      quads.push({
        verts,
        uv: [
          [uvs[0] / 16 * tex.w, uvs[2] / 16 * tex.h],
          [uvs[1] / 16 * tex.w, uvs[2] / 16 * tex.h],
          [uvs[1] / 16 * tex.w, uvs[3] / 16 * tex.h],
          [uvs[0] / 16 * tex.w, uvs[3] / 16 * tex.h]
        ],
        tex,
        rot
      });
    }
  }
  return { quads, missing, used };
}

// ---------------------------------------------------------------- render
function renderModel(model, file) {
  const { quads, missing, used } = buildQuads(model);
  const S = SIZE * SS;
  const rgba = Buffer.alloc(S * S * 4);
  // background gradient
  for (let y = 0; y < S; y++) {
    const t = y / S;
    const r = Math.round(30 - 12 * t), g = Math.round(38 - 16 * t), b = Math.round(46 - 18 * t);
    for (let x = 0; x < S; x++) {
      const i = (y * S + x) * 4;
      rgba[i] = r; rgba[i + 1] = g; rgba[i + 2] = b; rgba[i + 3] = 255;
    }
  }
  const allVerts = [];
  for (const q of quads) allVerts.push(...q.verts);
  if (!allVerts.length) return { rgba, S, missing, used, quads: [] };

  const bb = { min: [Infinity, Infinity, Infinity], max: [-Infinity, -Infinity, -Infinity] };
  for (const v of allVerts) {
    for (let i = 0; i < 3; i++) {
      bb.min[i] = Math.min(bb.min[i], v[i]);
      bb.max[i] = Math.max(bb.max[i], v[i]);
    }
  }
  const center = [(bb.min[0] + bb.max[0]) / 2, (bb.min[1] + bb.max[1]) / 2, (bb.min[2] + bb.max[2]) / 2];

  const az = 135 * Math.PI / 180, el = 28 * Math.PI / 180;
  const camDir = [Math.cos(el) * Math.sin(az), Math.sin(el), Math.cos(el) * Math.cos(az)];
  const forward = vnorm([-camDir[0], -camDir[1], -camDir[2]]);
  const right = vnorm(vcross([0, 1, 0], forward));
  const up = vcross(right, forward);

  const proj = p => {
    const d = vsub(p, center);
    return [vdot(d, right), -vdot(d, up), vdot(d, forward)];
  };
  const pv = allVerts.map(proj);
  let minX = Infinity, maxX = -Infinity, minY = Infinity, maxY = -Infinity;
  for (const p of pv) {
    minX = Math.min(minX, p[0]); maxX = Math.max(maxX, p[0]);
    minY = Math.min(minY, p[1]); maxY = Math.max(maxY, p[1]);
  }
  const span = Math.max(maxX - minX, maxY - minY) || 1;
  const scale = (S * 0.84) / span;
  const cx = (minX + maxX) / 2, cy = (minY + maxY) / 2;
  const toScreen = (p, s) => [S / 2 + (p[0] - cx) * s, S / 2 + (p[1] - cy) * s];

  // painter's algorithm: far to near
  const items = quads.map(q => {
    const ps = q.verts.map(proj);
    const depth = ps.reduce((a, p) => a + p[2], 0) / ps.length;
    return { q, ps, depth };
  }).sort((a, b) => b.depth - a.depth);

  const light = vnorm([-0.42, 0.86, 0.28]);
  let covered = 0;
  const mask = new Uint8Array(S * S);

  for (const it of items) {
    const { q, ps } = it;
    const scr = ps.map(p => toScreen(p, scale));
    const n = vnorm(vcross(vsub(q.verts[1], q.verts[0]), vsub(q.verts[3], q.verts[0])));
    let shade = 0.66 + 0.34 * Math.max(0, vdot(n, light));
    if (n[1] < -0.2) shade *= 0.72;
    shade = Math.max(0.42, Math.min(1.08, shade));
    for (const tri of [[0, 1, 2], [0, 2, 3]]) {
      const a = scr[tri[0]], b2 = scr[tri[1]], c = scr[tri[2]];
      const ua = q.uv[tri[0]], ub = q.uv[tri[1]], uc = q.uv[tri[2]];
      rasterTri(rgba, mask, S, a, b2, c, ua, ub, uc, q.tex, shade);
    }
  }
  for (let i = 0; i < S * S; i++) if (mask[i]) covered++;

  // downsample SS x SS
  const out = Buffer.alloc(SIZE * SIZE * 4);
  for (let y = 0; y < SIZE; y++) {
    for (let x = 0; x < SIZE; x++) {
      let r = 0, g = 0, b = 0, a = 0;
      for (let sy = 0; sy < SS; sy++) {
        for (let sx = 0; sx < SS; sx++) {
          const i = ((y * SS + sy) * S + (x * SS + sx)) * 4;
          r += rgba[i]; g += rgba[i + 1]; b += rgba[i + 2]; a += rgba[i + 3];
        }
      }
      const o = (y * SIZE + x) * 4;
      const k = SS * SS;
      out[o] = r / k; out[o + 1] = g / k; out[o + 2] = b / k; out[o + 3] = a / k;
    }
  }
  writePng(file, SIZE, SIZE, out);

  // metrics
  const sscale = scale / SS;
  const bboxArea = Math.max(1, ((maxX - minX) * sscale) * ((maxY - minY) * sscale));
  const silhouette = covered / (SS * SS) / bboxArea;
  const dims = [bb.max[0] - bb.min[0], bb.max[1] - bb.min[1], bb.max[2] - bb.min[2]];
  return { missing, used, bb, dims, silhouette, quads };
}

function rasterTri(buf, mask, W, p0, p1, p2, uv0, uv1, uv2, tex, shade) {
  const minX = Math.max(0, Math.floor(Math.min(p0[0], p1[0], p2[0])));
  const maxX = Math.min(W - 1, Math.ceil(Math.max(p0[0], p1[0], p2[0])));
  const minY = Math.max(0, Math.floor(Math.min(p0[1], p1[1], p2[1])));
  const maxY = Math.min(W - 1, Math.ceil(Math.max(p0[1], p1[1], p2[1])));
  if (minX > maxX || minY > maxY) return;
  const area = (p1[0] - p0[0]) * (p2[1] - p0[1]) - (p1[1] - p0[1]) * (p2[0] - p0[0]);
  if (Math.abs(area) < 1e-9) return;
  const inv = 1 / area;
  const tw = tex.w, th = tex.h, td = tex.data;
  for (let y = minY; y <= maxY; y++) {
    const py = y + 0.5;
    for (let x = minX; x <= maxX; x++) {
      const px = x + 0.5;
      let w0 = ((p1[0] - px) * (p2[1] - py) - (p1[1] - py) * (p2[0] - px)) * inv;
      let w1 = ((p2[0] - px) * (p0[1] - py) - (p2[1] - py) * (p0[0] - px)) * inv;
      let w2 = 1 - w0 - w1;
      if (w0 < -1e-5 || w1 < -1e-5 || w2 < -1e-5) continue;
      const u = w0 * uv0[0] + w1 * uv1[0] + w2 * uv2[0];
      const v = w0 * uv0[1] + w1 * uv1[1] + w2 * uv2[1];
      let tx = Math.floor(u), ty = Math.floor(v);
      if (tx < 0) tx = 0; if (tx >= tw) tx = tw - 1;
      if (ty < 0) ty = 0; if (ty >= th) ty = th - 1;
      const si = (ty * tw + tx) * 4;
      const sa = td[si + 3];
      if (sa < 6) continue;
      const a = sa / 255;
      const di = (y * W + x) * 4;
      const dr = td[si] * shade, dg = td[si + 1] * shade, db = td[si + 2] * shade;
      buf[di] = buf[di] * (1 - a) + dr * a;
      buf[di + 1] = buf[di + 1] * (1 - a) + dg * a;
      buf[di + 2] = buf[di + 2] * (1 - a) + db * a;
      buf[di + 3] = 255;
      mask[y * W + x] = 1;
    }
  }
}

// ---------------------------------------------------------------- metrics
function geometrySignature(model) {
  const parts = (model.elements || []).map(el => {
    const r = el.rotation ? `${el.rotation.axis}${el.rotation.angle}@${el.rotation.origin.join(',')}` : '-';
    return `${el.from.map(n => n.toFixed(2)).join(',')}>${el.to.map(n => n.toFixed(2)).join(',')}|${r}`;
  });
  return crypto.createHash('sha1').update(parts.join(';')).digest('hex').slice(0, 12);
}

function paletteOf(used) {
  const colors = new Set();
  const light = new Set();
  let total = 0;
  for (const ref of used) {
    const tex = textureCache.get(ref);
    if (!tex) continue;
    const d = tex.data;
    for (let i = 0; i < d.length; i += 4) {
      if (d[i + 3] < 32) continue;
      const key = (d[i] << 16) | (d[i + 1] << 8) | d[i + 2];
      if (!colors.has(key)) colors.add(key);
      const luma = (0.2126 * d[i] + 0.7152 * d[i + 1] + 0.0722 * d[i + 2]) / 255;
      if (luma >= 0.62 && !light.has(key)) light.add(key);
      total++;
    }
  }
  return { colors: colors.size, light: light.size, pixels: total };
}

// ---------------------------------------------------------------- main
const files = fs.readdirSync(MODEL_DIR).filter(f => f.endsWith('.json')).sort();
const rows = [];
const failures = [];
const shapeHashes = new Map();

for (const file of files) {
  const id = path.basename(file, '.json');
  const full = path.join(MODEL_DIR, file);
  let model;
  try {
    model = JSON.parse(fs.readFileSync(full, 'utf8'));
  } catch (e) {
    failures.push(`${id}: invalid JSON (${e.message})`);
    continue;
  }
  const size = fs.statSync(full).size;
  const els = model.elements || [];
  const sig = geometrySignature(model);
  if (shapeHashes.has(sig)) failures.push(`${id}: identical geometry to ${shapeHashes.get(sig)}`);
  else shapeHashes.set(sig, id);

  const isGate = id === 'egate' || id === 'exit_gate';
  const isFullCube = els.length === 1 &&
    els[0].from.join(',') === '0,0,0' && els[0].to.join(',') === '16,16,16';
  if (isFullCube) failures.push(`${id}: model is a single full 16^3 cube`);
  const minEls = isGate ? 20 : 10;
  if (els.length < minEls) failures.push(`${id}: only ${els.length} elements (need >= ${minEls})`);
  if (size > 120 * 1024) failures.push(`${id}: model is ${(size / 1024).toFixed(1)} KB (limit 120 KB)`);
  if (!els.some(el => el.rotation)) failures.push(`${id}: no rotated element`);

  // The E-Gates are single-lane barriers: 20 px wide (two 4 px
  // cabinets straddling the controller edges), ~16 px tall, 12 px passage.
  const span = isGate
    ? { x: [-2.8, 18.8], y: [-0.5, 18.5], z: [-0.5, 16.5] }
    : { x: [-0.5, 16.5], y: [-0.5, 24.5], z: [-0.5, 16.5] };

  for (const [i, el] of els.entries()) {
    const axes = ['x', 'y', 'z'];
    for (let k = 0; k < 3; k++) {
      const limit = span[axes[k]];
      if (el.from[k] < limit[0] || el.to[k] > limit[1]) {
        failures.push(`${id}: element ${i} out of [${limit[0]},${limit[1]}] ${axes[k]} bounds`);
      }
      if (el.from[k] >= el.to[k]) failures.push(`${id}: element ${i} has from >= to on axis ${k}`);
    }
    if (el.rotation) {
      const a = el.rotation.angle;
      if (!['x', 'y', 'z'].includes(el.rotation.axis)) failures.push(`${id}: element ${i} bad rotation axis`);
      if (Math.abs(a) > 45.0001) failures.push(`${id}: element ${i} rotation angle ${a} > 45`);
      if (Math.abs((a / 22.5) - Math.round(a / 22.5)) > 1e-6) failures.push(`${id}: element ${i} rotation ${a} not a multiple of 22.5`);
    }
  }

  if (isGate) {
    // 20 px wide, ~16 px tall envelope and a genuinely empty 12 px central
    // passage (x 2.2..13.8) for the renderer's animated wings.
    const gx1 = Math.min(...els.map(e => e.from[0]));
    const gx2 = Math.max(...els.map(e => e.to[0]));
    const gy1 = Math.min(...els.map(e => e.from[1]));
    const gy2 = Math.max(...els.map(e => e.to[1]));
    if (gx2 - gx1 < 18) failures.push(`${id}: gate is ${(gx2 - gx1).toFixed(1)} px wide (need >= 18)`);
    if (gy2 - gy1 < 15) failures.push(`${id}: gate is ${(gy2 - gy1).toFixed(1)} px tall (need >= 15)`);
    const clear = { x1: 2.3, y1: 0.4, z1: 0.4, x2: 13.7, y2: 15.0, z2: 15.0 };
    for (const [i, el] of els.entries()) {
      if (el.from[0] < clear.x2 && el.to[0] > clear.x1 &&
          el.from[1] < clear.y2 && el.to[1] > clear.y1 &&
          el.from[2] < clear.z2 && el.to[2] > clear.z1) {
        failures.push(`${id}: element ${i} intrudes into the 12 px door passage`);
      }
    }
  }

  const rendered = renderModel(model, path.join(OUT, id + '.png'));
  for (const m of rendered.missing) failures.push(`${id}: missing texture ${m}`);

  // declared textures must resolve and be 32 or 64 px
  const declared = Object.entries(model.textures || {}).filter(([k]) => k !== 'particle');
  for (const [key, ref] of declared) {
    const tex = loadTexture(ref);
    if (!tex) { failures.push(`${id}: declared texture #${key} -> ${ref} missing`); continue; }
    if (!(tex.w === 32 || tex.w === 64) || !(tex.h === 32 || tex.h === 64)) {
      failures.push(`${id}: texture ${ref} is ${tex.w}x${tex.h} (need 32 or 64)`);
    }
  }

  const pal = paletteOf(rendered.used);
  if (pal.light < 4) failures.push(`${id}: only ${pal.light} light palette colors (need >= 4)`);
  const bbox = rendered.bb;
  rows.push({
    id,
    elements: els.length,
    rotated: els.filter(e => e.rotation).length,
    colors: pal.colors,
    light: pal.light,
    textures: rendered.used.size,
    bbox: `${(bbox.max[0] - bbox.min[0]).toFixed(1)}x${(bbox.max[1] - bbox.min[1]).toFixed(1)}x${(bbox.max[2] - bbox.min[2]).toFixed(1)}`,
    cube: isFullCube ? 'YES' : 'no',
    silhouette: rendered.silhouette,
    hash: sig,
    kb: size / 1024
  });
}

// ---- blockstate / item model cross-checks
let bsCount = 0, variantCount = 0;
for (const file of fs.readdirSync(BS_DIR).filter(f => f.endsWith('.json'))) {
  const id = path.basename(file, '.json');
  bsCount++;
  let bs;
  try { bs = JSON.parse(fs.readFileSync(path.join(BS_DIR, file), 'utf8')); }
  catch (e) { failures.push(`blockstate ${id}: invalid JSON`); continue; }
  const variants = bs.variants || {};
  for (const [key, v] of Object.entries(variants)) {
    variantCount++;
    const list = Array.isArray(v) ? v : [v];
    for (const entry of list) {
      const ref = entry.model || '';
      const rel = ref.replace('veriborder:block/', '');
      if (!fs.existsSync(path.join(MODEL_DIR, rel + '.json'))) {
        failures.push(`blockstate ${id}: variant ${key} points to missing model ${ref}`);
      }
    }
  }
  if (id === 'egate' || id === 'exit_gate') {
    for (const f of ['north', 'east', 'south', 'west']) {
      for (const o of ['false', 'true']) {
        if (!variants[`facing=${f},open=${o}`]) failures.push(`blockstate ${id}: missing variant facing=${f},open=${o}`);
      }
    }
  }
}

for (const file of fs.readdirSync(ITEM_DIR).filter(f => f.endsWith('.json'))) {
  const id = path.basename(file, '.json');
  let im;
  try { im = JSON.parse(fs.readFileSync(path.join(ITEM_DIR, file), 'utf8')); }
  catch (e) { failures.push(`item model ${id}: invalid JSON`); continue; }
  if (im.parent) {
    const ref = im.parent;
    if (ref.startsWith('veriborder:')) {
      const rel = ref.split(':')[1];
      if (!fs.existsSync(path.join(ASSETS, 'models', ref.split(':')[1] + '.json'))) {
        failures.push(`item model ${id}: missing parent ${ref}`);
      }
    }
  }
}

// ---- round-2 required ids and files
const LOOT_DIR = path.join(ROOT, 'src', 'main', 'resources', 'data', 'veriborder', 'loot_tables', 'blocks');
for (const id of ['exit_gate', 'self_endorsement']) {
  if (!rows.some(r => r.id === id)) failures.push(`missing required model ${id}`);
  if (!fs.existsSync(path.join(BS_DIR, id + '.json'))) failures.push(`missing required blockstate ${id}`);
  if (!fs.existsSync(path.join(ITEM_DIR, id + '.json'))) failures.push(`missing required item model ${id}`);
  if (!fs.existsSync(path.join(LOOT_DIR, id + '.json'))) failures.push(`missing required loot table ${id}`);
}
if (!fs.existsSync(path.join(ITEM_DIR, 'device_brush.json'))) failures.push('missing required item model device_brush');
if (!fs.existsSync(path.join(ASSETS, 'textures', 'item', 'device_brush.png'))) failures.push('missing required texture device_brush');
if (rows.length !== 21) failures.push(`expected 21 block models (18 + exit_gate + self_endorsement + self_visa), found ${rows.length}`);

// ---- report
const lines = [];
lines.push('VeriBorder model metrics (generated by tools/preview_models.js)');
lines.push('Rendered: isometric preview, orthographic, 512x512, textured quads.');
lines.push('');
lines.push('id                     els rot  colors  light  tex  bbox          cube  silhouette  shape-hash   KB');
lines.push('---------------------------------------------------------------------------------------------------');
for (const r of rows) {
  lines.push(
    r.id.padEnd(22) +
    String(r.elements).padStart(3) +
    String(r.rotated).padStart(4) +
    String(r.colors).padStart(7) +
    String(r.light).padStart(7) +
    String(r.textures).padStart(5) +
    '  ' + r.bbox.padEnd(14) +
    ' ' + r.cube.padEnd(5) +
    ' ' + r.silhouette.toFixed(3).padStart(10) +
    '  ' + r.hash.padEnd(12) +
    ' ' + r.kb.toFixed(1)
  );
}
lines.push('');
lines.push(`models: ${rows.length}   blockstates: ${bsCount}   variants: ${variantCount}   textures on disk: ${fs.readdirSync(TEX_DIR).filter(f => f.endsWith('.png')).length}`);
lines.push(`min elements: ${Math.min(...rows.map(r => r.elements))}   unique geometry hashes: ${new Set(rows.map(r => r.hash)).size}`);
lines.push(`distinct light colors: min ${Math.min(...rows.map(r => r.light))}   max ${Math.max(...rows.map(r => r.light))}`);
const gateRows = rows.filter(r => r.id === 'egate' || r.id === 'exit_gate');
lines.push(`gates: ${gateRows.map(g => `${g.id} ${g.bbox} (${g.elements} els, ${g.rotated} rotated)`).join('   ')}   passage: 12 px clear`);
lines.push('');
lines.push('CHECKLIST');
const checks = [
  ['every model >= 10 elements (gates >= 20)', !failures.some(f => f.includes('elements (need'))],
  ['every model has a rotated element', !failures.some(f => f.includes('no rotated element'))],
  ['no single full cube', !failures.some(f => f.includes('single full 16^3 cube'))],
  ['unique geometry per device', !failures.some(f => f.includes('identical geometry'))],
  ['>= 4 light palette colors', !failures.some(f => f.includes('light palette colors'))],
  ['all texture refs resolve', !failures.some(f => f.includes('missing texture') || f.includes('declared texture'))],
  ['textures are 32 or 64 px', !failures.some(f => f.includes('need 32 or 64'))],
  ['model files < 120 KB', !failures.some(f => f.includes('limit 120'))],
  ['blockstates reference existing models', !failures.some(f => f.includes('variant') && f.includes('missing model'))],
  ['item model parents resolve', !failures.some(f => f.includes('missing parent'))],
  ['both gates are >= 18 x 15 px', !failures.some(f => f.includes('px wide (need') || f.includes('px tall (need'))],
  ['12 px gate passage is clear', !failures.some(f => f.includes('door passage'))],
  ['21 models + blockstates + item models + loot', !failures.some(f => f.includes('expected 21') || f.includes('missing required'))],
  ['egate + exit_gate have 8 facing/open variants', !failures.some(f => f.includes('blockstate egate') || f.includes('blockstate exit_gate'))]
];
for (const [name, ok] of checks) lines.push(`  [${ok ? 'PASS' : 'FAIL'}] ${name}`);
lines.push('');
if (failures.length === 0) {
  lines.push('FAILURES: none');
} else {
  lines.push(`FAILURES: ${failures.length}`);
  for (const f of failures) lines.push('  - ' + f);
}
fs.writeFileSync(path.join(OUT, 'metrics.txt'), lines.join('\n') + '\n', 'utf8');
console.log(lines.join('\n'));

process.exit(failures.length ? 1 : 0);

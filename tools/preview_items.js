#!/usr/bin/env node
/*
 * VeriBorder item-icon / item-model previewer.
 *
 *   node tools/preview_items.js --out tools/preview/item_after
 *   node tools/preview_items.js --models tools/preview/item_before/models \
 *        --tex-item tools/preview/item_before/textures \
 *        --out tools/preview/item_before/render
 *
 * Renders every models/item/*.json into <out>/<id>.png:
 *   * flat models (parent minecraft:item/generated | handheld) are blitted from
 *     their layer0 PNG with nearest-neighbour pixels + a checkerboard backing so
 *     the silhouette and the 1 px outline are visible,
 *   * element models are rendered as textured quads with the same software
 *     rasterizer settings tools/preview_models.js uses for the block devices, so
 *     an item model and its block counterpart are directly comparable.
 * Parent chains are resolved (veriborder:block/<id> inherits elements/textures),
 * which is what lets the "before" snapshot render the old block-parented item
 * models.
 *
 * Exit code 1 when a model cannot be parsed / has no geometry and no layer0.
 */
'use strict';

const fs = require('fs');
const path = require('path');
const zlib = require('zlib');

const ROOT = path.resolve(__dirname, '..');
const ASSETS = path.join(ROOT, 'src', 'main', 'resources', 'assets', 'veriborder');

// ---------------------------------------------------------------- args
function argOf(name, fallback) {
  const i = process.argv.indexOf('--' + name);
  return i >= 0 && process.argv[i + 1] ? process.argv[i + 1] : fallback;
}
const MODEL_DIR = path.resolve(ROOT, argOf('models', path.join(ASSETS, 'models', 'item')));
const TEX_ITEM = path.resolve(ROOT, argOf('tex-item', path.join(ASSETS, 'textures', 'item')));
const TEX_BLOCK = path.resolve(ROOT, argOf('tex-block', path.join(ASSETS, 'textures', 'block')));
const BLOCK_MODELS = path.resolve(ROOT, argOf('block-models', path.join(ASSETS, 'models', 'block')));
const OUT = path.resolve(ROOT, argOf('out', path.join(ROOT, 'tools', 'preview', 'item_after')));
// Optional id whitelist (comma separated) so a comparison render can ignore
// models another work stream is still adding.
const ONLY = (argOf('ids', '') || '').split(',').map(s => s.trim()).filter(Boolean);
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
  fs.writeFileSync(file, Buffer.concat([
    Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]),
    chunk('IHDR', ihdr),
    chunk('IDAT', zlib.deflateSync(raw, { level: 9 })),
    chunk('IEND', Buffer.alloc(0))
  ]));
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

// ---------------------------------------------------------------- model loading
function loadModelJson(id) {
  // id is a resource path below the namespace: "item/x" or "block/x". The prefix
  // decides the directory - searching both would resolve "block/x" to the item
  // model x.json and stop following the parent chain.
  let file;
  if (id.startsWith('block/')) file = path.join(BLOCK_MODELS, id.slice(6) + '.json');
  else if (id.startsWith('item/')) file = path.join(MODEL_DIR, id.slice(5) + '.json');
  else file = path.join(MODEL_DIR, id + '.json');
  if (!fs.existsSync(file)) return null;
  return JSON.parse(fs.readFileSync(file, 'utf8'));
}
function resolveModel(ref) {
  // ref: "veriborder:block/x" | "veriborder:item/x" | "minecraft:item/generated"
  const chain = [];
  let cur = ref;
  let textures = {};
  let elements = null;
  let guiLight = null;
  for (let depth = 0; depth < 12 && cur; depth++) {
    chain.push(cur);
    const ns = cur.includes(':') ? cur.split(':')[0] : 'veriborder';
    const rel = cur.includes(':') ? cur.split(':')[1] : cur;
    if (rel.startsWith('builtin/')) return { chain, textures, elements, guiLight, flat: true, builtin: true };
    if (ns === 'minecraft') {
      if (rel === 'item/generated' || rel === 'item/handheld') return { chain, textures, elements, guiLight, flat: true };
      return { chain, textures, elements, guiLight, flat: false };
    }
    const json = loadModelJson(rel);
    if (!json) return { chain, textures, elements, guiLight, flat: false, missing: cur };
    if (json.textures) textures = Object.assign({}, json.textures, textures);
    if (!elements && json.elements) elements = json.elements;
    if (json.gui_light) guiLight = json.gui_light;
    cur = json.parent || null;
  }
  return { chain, textures, elements, guiLight, flat: !elements };
}

const textureCache = new Map();
function loadTexture(ref) {
  if (textureCache.has(ref)) return textureCache.get(ref);
  let rel = ref.includes(':') ? ref.split(':')[1] : ref;
  let file = null;
  if (rel.startsWith('block/')) file = path.join(TEX_BLOCK, rel.slice(6) + '.png');
  else if (rel.startsWith('item/')) file = path.join(TEX_ITEM, rel.slice(5) + '.png');
  else file = path.join(TEX_ITEM, rel + '.png');
  let tex = null;
  if (fs.existsSync(file)) tex = Object.assign({ file }, readPng(file));
  textureCache.set(ref, tex);
  return tex;
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

const FACE_DEF = {
  north: { verts: b => [[b.x2, b.y2, b.z1], [b.x1, b.y2, b.z1], [b.x1, b.y1, b.z1], [b.x2, b.y1, b.z1]], uv: b => [b.x1, 16 - b.y2, b.x2, 16 - b.y1] },
  south: { verts: b => [[b.x1, b.y2, b.z2], [b.x2, b.y2, b.z2], [b.x2, b.y1, b.z2], [b.x1, b.y1, b.z2]], uv: b => [16 - b.x2, 16 - b.y2, 16 - b.x1, 16 - b.y1] },
  east: { verts: b => [[b.x2, b.y2, b.z2], [b.x2, b.y2, b.z1], [b.x2, b.y1, b.z1], [b.x2, b.y1, b.z2]], uv: b => [16 - b.z2, 16 - b.y2, 16 - b.z1, 16 - b.y1] },
  west: { verts: b => [[b.x1, b.y2, b.z1], [b.x1, b.y2, b.z2], [b.x1, b.y1, b.z2], [b.x1, b.y1, b.z1]], uv: b => [b.z1, 16 - b.y2, b.z2, 16 - b.y1] },
  up: { verts: b => [[b.x1, b.y2, b.z1], [b.x2, b.y2, b.z1], [b.x2, b.y2, b.z2], [b.x1, b.y2, b.z2]], uv: b => [b.x1, b.z1, b.x2, b.z2] },
  down: { verts: b => [[b.x1, b.y1, b.z2], [b.x2, b.y1, b.z2], [b.x2, b.y1, b.z1], [b.x1, b.y1, b.z1]], uv: b => [b.x1, 16 - b.z2, b.x2, 16 - b.z1] }
};

function buildQuads(model, textures) {
  const quads = [];
  const missing = [];
  const used = new Set();
  for (const el of model || []) {
    const b = { x1: el.from[0], y1: el.from[1], z1: el.from[2], x2: el.to[0], y2: el.to[1], z2: el.to[2] };
    const rot = el.rotation ? { origin: el.rotation.origin || [8, 8, 8], axis: el.rotation.axis, angle: el.rotation.angle } : null;
    for (const fname of Object.keys(FACE_DEF)) {
      const face = el.faces && el.faces[fname];
      if (!face || face.texture === undefined) continue;
      let tref = face.texture;
      if (tref.startsWith('#')) tref = textures[tref.slice(1)];
      if (!tref) { missing.push(fname); continue; }
      const tex = loadTexture(tref);
      if (!tex) { missing.push(tref); continue; }
      used.add(tref);
      const def = FACE_DEF[fname];
      let uvs = face.uv || def.uv(b);
      // legacy flat-shorthand UVs [u,v,w,h] that some hand-written item models
      // use: detect the 4-value form whose 3rd/4th values look like a size.
      if (uvs.length === 4 && face.uv && (uvs[2] <= uvs[0] || uvs[3] <= uvs[1])) {
        uvs = [uvs[0], uvs[1], uvs[0] + uvs[2], uvs[1] + uvs[3]];
      }
      const u1 = uvs[0] / 16 * tex.w, v1 = uvs[1] / 16 * tex.h;
      const u2 = uvs[2] / 16 * tex.w, v2 = uvs[3] / 16 * tex.h;
      const verts = def.verts(b).map(v => rotatePoint(v, rot));
      quads.push({ verts, uv: [[u1, v1], [u2, v1], [u2, v2], [u1, v2]], tex, rot });
    }
  }
  return { quads, missing, used };
}

// ---------------------------------------------------------------- raster
function rasterTri(buf, mask, W, p0, p1, p2, uv0, uv1, uv2, tex, shade, depthBuf, d0, d1, d2) {
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
      const w0 = ((p1[0] - px) * (p2[1] - py) - (p1[1] - py) * (p2[0] - px)) * inv;
      const w1 = ((p2[0] - px) * (p0[1] - py) - (p2[1] - py) * (p0[0] - px)) * inv;
      const w2 = 1 - w0 - w1;
      if (w0 < -1e-5 || w1 < -1e-5 || w2 < -1e-5) continue;
      const z = w0 * d0 + w1 * d1 + w2 * d2;
      const zi = y * W + x;
      if (depthBuf && z > depthBuf[zi] + 1e-4) continue;
      if (depthBuf) depthBuf[zi] = z;
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
      buf[di] = buf[di] * (1 - a) + td[si] * shade * a;
      buf[di + 1] = buf[di + 1] * (1 - a) + td[si + 1] * shade * a;
      buf[di + 2] = buf[di + 2] * (1 - a) + td[si + 2] * shade * a;
      buf[di + 3] = 255;
      mask[y * W + x] = 1;
    }
  }
}

function background(rgba, S) {
  for (let y = 0; y < S; y++) {
    const t = y / S;
    const r = Math.round(30 - 12 * t), g = Math.round(38 - 16 * t), b = Math.round(46 - 18 * t);
    for (let x = 0; x < S; x++) {
      const i = (y * S + x) * 4;
      rgba[i] = r; rgba[i + 1] = g; rgba[i + 2] = b; rgba[i + 3] = 255;
    }
  }
}

function renderElements(quads, file) {
  const S = SIZE * SS;
  const rgba = Buffer.alloc(S * S * 4);
  background(rgba, S);
  const allVerts = [];
  for (const q of quads) allVerts.push(...q.verts);
  if (!allVerts.length) return false;
  const bb = { min: [Infinity, Infinity, Infinity], max: [-Infinity, -Infinity, -Infinity] };
  for (const v of allVerts) for (let i = 0; i < 3; i++) {
    bb.min[i] = Math.min(bb.min[i], v[i]);
    bb.max[i] = Math.max(bb.max[i], v[i]);
  }
  const center = [(bb.min[0] + bb.max[0]) / 2, (bb.min[1] + bb.max[1]) / 2, (bb.min[2] + bb.max[2]) / 2];
  // vanilla block-item gui transform is (30, 225) -> view from the -x/-z/top
  // quadrant; the same azimuth keeps item previews comparable with the block
  // device previews (tools/preview_models.js uses 135 for the same 3/4 read).
  const az = 225 * Math.PI / 180;
  const el = 26 * Math.PI / 180;
  const camDir = [Math.cos(el) * Math.sin(az), Math.sin(el), Math.cos(el) * Math.cos(az)];
  const forward = vnorm([-camDir[0], -camDir[1], -camDir[2]]);
  const right = vnorm(vcross(forward, [0, 1, 0]));
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
  const scale = (S * 0.80) / span;
  const cx = (minX + maxX) / 2, cy = (minY + maxY) / 2;
  const toScreen = p => [S / 2 + (p[0] - cx) * scale, S / 2 + (p[1] - cy) * scale];
  const items = quads.map(q => {
    const ps = q.verts.map(proj);
    return { q, ps, depth: ps.reduce((a, p) => a + p[2], 0) / ps.length };
  }).sort((a, b) => b.depth - a.depth);
  // Item icons are lit from the top-left so the icon palette and the 3D models
  // share one light direction (the brief's "unified top-left key light").
  const light = vnorm([-0.52, 0.80, -0.42]);
  const mask = new Uint8Array(S * S);
  const depthBuf = new Float32Array(S * S).fill(Infinity);
  for (const it of items) {
    const { q, ps } = it;
    const scr = ps.map(p => toScreen(p));
    const n = vnorm(vcross(vsub(q.verts[1], q.verts[0]), vsub(q.verts[3], q.verts[0])));
    const dot = Math.max(0, vdot(n, light));
    let shade = 0.70 + 0.34 * dot;
    if (n[1] < -0.2) shade *= 0.74;
    shade = Math.max(0.46, Math.min(1.06, shade));
    for (const tri of [[0, 1, 2], [0, 2, 3]]) {
      rasterTri(rgba, mask, S, scr[tri[0]], scr[tri[1]], scr[tri[2]],
        q.uv[tri[0]], q.uv[tri[1]], q.uv[tri[2]], q.tex, shade,
        depthBuf, ps[tri[0]][2], ps[tri[1]][2], ps[tri[2]][2]);
    }
  }
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
      const o = (y * SIZE + x) * 4, k = SS * SS;
      out[o] = r / k; out[o + 1] = g / k; out[o + 2] = b / k; out[o + 3] = a / k;
    }
  }
  writePng(file, SIZE, SIZE, out);
  return true;
}

function renderFlat(tex, file) {
  const S = SIZE;
  const rgba = Buffer.alloc(S * S * 4);
  // checkerboard so binary-alpha silhouettes and 1 px outlines are legible
  for (let y = 0; y < S; y++) {
    for (let x = 0; x < S; x++) {
      const i = (y * S + x) * 4;
      const c = ((x >> 4) + (y >> 4)) & 1;
      const base = c ? 44 : 37;
      rgba[i] = base; rgba[i + 1] = base + 5; rgba[i + 2] = base + 9; rgba[i + 3] = 255;
    }
  }
  const zoom = Math.max(1, Math.floor(S * 0.78 / Math.max(tex.w, tex.h)));
  const w = tex.w * zoom, h = tex.h * zoom;
  const ox = Math.floor((S - w) / 2), oy = Math.floor((S - h) / 2);
  for (let y = 0; y < h; y++) {
    for (let x = 0; x < w; x++) {
      const sx = Math.floor(x / zoom), sy = Math.floor(y / zoom);
      const si = (sy * tex.w + sx) * 4;
      if (tex.data[si + 3] === 0) continue;
      const di = ((oy + y) * S + (ox + x)) * 4;
      rgba[di] = tex.data[si]; rgba[di + 1] = tex.data[si + 1];
      rgba[di + 2] = tex.data[si + 2]; rgba[di + 3] = 255;
    }
  }
  writePng(file, S, S, rgba);
  return true;
}

// ---------------------------------------------------------------- main
const files = fs.readdirSync(MODEL_DIR).filter(f => f.endsWith('.json')).sort()
  .filter(f => !ONLY.length || ONLY.includes(path.basename(f, '.json')));
const report = [];
let failed = 0;
for (const file of files) {
  const id = path.basename(file, '.json');
  let json;
  try {
    json = JSON.parse(fs.readFileSync(path.join(MODEL_DIR, file), 'utf8'));
  } catch (e) {
    console.error(`${id}: invalid JSON (${e.message})`);
    failed++;
    continue;
  }
  const resolved = resolveModel(`veriborder:item/${id}`);
  if (resolved.builtin) {
    report.push({ id, kind: 'bewlr', elements: 0, textures: 0, parent: json.parent, ok: true });
    continue;
  }
  let textures = Object.assign({}, resolved.textures);
  let elements = resolved.elements || null;
  if (!resolved.elements && json.elements) elements = json.elements;
  const outFile = path.join(OUT, id + '.png');

  if (elements && elements.length) {
    const { quads, missing, used } = buildQuads(elements, textures);
    if (missing.length) console.error(`${id}: missing texture ${[...new Set(missing)].join(', ')}`);
    const ok = renderElements(quads, outFile);
    report.push({ id, kind: '3d', elements: elements.length, textures: used.size, parent: json.parent || '-', ok });
    if (!ok) { failed++; }
  } else {
    const layer0 = textures.layer0 || (textures['0']) || null;
    const tex = layer0 ? loadTexture(layer0) : null;
    if (!tex) {
      console.error(`${id}: no elements and no resolvable layer0 (${json.parent || 'no parent'})`);
      failed++;
      continue;
    }
    renderFlat(tex, outFile);
    report.push({ id, kind: '2d', elements: 0, textures: 1, parent: json.parent || '-', ok: true, icon: `${tex.w}x${tex.h}` });
  }
}

const lines = [];
lines.push(`item previews -> ${OUT}`);
lines.push('id                       kind  els  tex  icon     parent');
lines.push('---------------------------------------------------------------');
for (const r of report.sort((a, b) => a.id.localeCompare(b.id))) {
  lines.push(
    r.id.padEnd(24) + r.kind.padEnd(6) + String(r.elements).padStart(3) +
    String(r.textures).padStart(5) + '  ' + (r.icon || '-').padEnd(8) + ' ' + r.parent
  );
}
lines.push('');
lines.push(`models: ${report.length}   2d: ${report.filter(r => r.kind === '2d').length}   3d: ${report.filter(r => r.kind === '3d').length}`);
console.log(lines.join('\n'));
fs.writeFileSync(path.join(OUT, 'index.txt'), lines.join('\n') + '\n', 'utf8');
process.exit(failed ? 1 : 0);

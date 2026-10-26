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
    uv: b => [b.x1, 16 - b.y2, b.x2, 16 - b.y1]
  },
  south: {
    verts: b => [[b.x1, b.y2, b.z2], [b.x2, b.y2, b.z2], [b.x2, b.y1, b.z2], [b.x1, b.y1, b.z2]],
    uv: b => [16 - b.x2, 16 - b.y2, 16 - b.x1, 16 - b.y1]
  },
  east: {
    verts: b => [[b.x2, b.y2, b.z2], [b.x2, b.y2, b.z1], [b.x2, b.y1, b.z1], [b.x2, b.y1, b.z2]],
    uv: b => [16 - b.z2, 16 - b.y2, 16 - b.z1, 16 - b.y1]
  },
  west: {
    verts: b => [[b.x1, b.y2, b.z1], [b.x1, b.y2, b.z2], [b.x1, b.y1, b.z2], [b.x1, b.y1, b.z1]],
    uv: b => [b.z1, 16 - b.y2, b.z2, 16 - b.y1]
  },
  up: {
    verts: b => [[b.x1, b.y2, b.z1], [b.x2, b.y2, b.z1], [b.x2, b.y2, b.z2], [b.x1, b.y2, b.z2]],
    uv: b => [b.x1, b.z1, b.x2, b.z2]
  },
  down: {
    verts: b => [[b.x1, b.y1, b.z2], [b.x2, b.y1, b.z2], [b.x2, b.y1, b.z1], [b.x1, b.y1, b.z1]],
    uv: b => [b.x1, 16 - b.z2, b.x2, 16 - b.z1]
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
      // Minecraft model UVs are [u1, v1, u2, v2] with v1 at the top edge.
      // An earlier revision read them as [u1, u2, v1, v2], which collapsed
      // every square UV to a single texel; normalise once here instead.
      const uvs = face.uv || def.uv(b);
      const u1 = uvs[0] / 16 * tex.w, v1 = uvs[1] / 16 * tex.h;
      const u2 = uvs[2] / 16 * tex.w, v2 = uvs[3] / 16 * tex.h;
      const verts = def.verts(b).map(v => rotatePoint(v, rot));
      // Every face's vertex 0 is its top-left corner as seen from outside, so
      // u1 belongs there. north/south used to swap u1/u2 to compensate for the
      // old mirrored camera basis; with that fixed the swap only re-mirrors the
      // face (a real-client test shows unmirrored labels read correctly).
      quads.push({
        verts,
        uv: [[u1, v1], [u2, v1], [u2, v2], [u1, v2]],
        tex,
        rot
      });
    }
  }
  return { quads, missing, used };
}

// ---------------------------------------------------------------- render
function renderModel(model, file, view) {
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

  // Gates are read almost head-on: a near-front angle keeps both pillars the
  // same apparent size (an isometric 135 view makes the near pillar look like a
  // separate cabinet) while still showing the cap depth and the side faces.
  // Explicit {az, el} lets the front/leaf QA renders use their own camera.
  const gateView = !!view || /(^|[\\/])(egate|exit_gate|dual_gate)\.png$/.test(file);
  const az = ((view && view.az !== undefined) ? view.az : (gateView ? 188 : 135)) * Math.PI / 180;
  const el = ((view && view.el !== undefined) ? view.el : (gateView ? 21 : 28)) * Math.PI / 180;
  const camDir = [Math.cos(el) * Math.sin(az), Math.sin(el), Math.cos(el) * Math.cos(az)];
  const forward = vnorm([-camDir[0], -camDir[1], -camDir[2]]);
  // Screen basis: right = forward x worldUp (NOT worldUp x forward), up = right x forward.
  // The previous order flipped both axes, which rendered every preview rotated 180°
  // (models appeared upside down and any north-face lettering read inverted).
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
  const scale = (S * 0.84) / span;
  const cx = (minX + maxX) / 2, cy = (minY + maxY) / 2;
  const toScreen = (p, s) => [S / 2 + (p[0] - cx) * s, S / 2 + (p[1] - cy) * s];

  // painter's algorithm: far to near (centroid depth)
  const items = quads.map(q => {
    const ps = q.verts.map(proj);
    const depth = ps.reduce((a, p) => a + p[2], 0) / ps.length;
    return { q, ps, depth };
  }).sort((a, b) => b.depth - a.depth);

  // Gate previews use a front-biased light so the matte white shell reads as
  // white instead of mid-grey; all other devices keep the original studio light.
  const light = gateView ? vnorm([-0.22, 0.72, -0.66]) : vnorm([-0.42, 0.86, 0.28]);
  let covered = 0;
  const mask = new Uint8Array(S * S);
  // Per-pixel depth buffer. Painter's-algorithm sorting by centroid alone let a
  // tall body's front quad paint over a small element that protrudes in front of
  // it (labels/low slots vanished from previews while being correct in game).
  const depthBuf = new Float32Array(S * S).fill(Infinity);

  for (const it of items) {
    const { q, ps } = it;
    const scr = ps.map(p => toScreen(p, scale));
    const n = vnorm(vcross(vsub(q.verts[1], q.verts[0]), vsub(q.verts[3], q.verts[0])));
    const dot = Math.max(0, vdot(n, light));
    let shade = gateView ? (0.96 + 0.08 * dot) : (0.66 + 0.34 * dot);
    if (n[1] < -0.2) shade *= gateView ? 0.82 : 0.72;
    shade = gateView ? Math.max(0.7, Math.min(1.05, shade)) : Math.max(0.42, Math.min(1.08, shade));
    for (const tri of [[0, 1, 2], [0, 2, 3]]) {
      const a = scr[tri[0]], b2 = scr[tri[1]], c = scr[tri[2]];
      const ua = q.uv[tri[0]], ub = q.uv[tri[1]], uc = q.uv[tri[2]];
      rasterTri(rgba, mask, S, a, b2, c, ua, ub, uc, q.tex, shade,
        depthBuf, ps[tri[0]][2], ps[tri[1]][2], ps[tri[2]][2]);
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
      let w0 = ((p1[0] - px) * (p2[1] - py) - (p1[1] - py) * (p2[0] - px)) * inv;
      let w1 = ((p2[0] - px) * (p0[1] - py) - (p2[1] - py) * (p0[0] - px)) * inv;
      let w2 = 1 - w0 - w1;
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
const files = fs.readdirSync(MODEL_DIR).filter(f => f.endsWith('.json') && f !== 'device_extension.json').sort();
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
  // The dual-clearance gate is contractually the egate geometry with a dark
  // green accent, so that one identical pair is allowed; any other duplicate is
  // still a failure.
  const sharedWithEgate = (id === 'dual_gate' && shapeHashes.get(sig) === 'egate')
    || (id === 'egate' && shapeHashes.get(sig) === 'dual_gate');
  if (shapeHashes.has(sig) && !sharedWithEgate) failures.push(`${id}: identical geometry to ${shapeHashes.get(sig)}`);
  else if (!shapeHashes.has(sig)) shapeHashes.set(sig, id);

  // Gates now ship four cap variants (<id>, <id>_left, <id>_right, <id>_both),
  // so the id test accepts the suffix. Every variant is validated on its own.
  const isGate = /^(egate|exit_gate|dual_gate)(_[a-z]+)?$/.test(id);
  const isFullCube = els.length === 1 &&
    els[0].from.join(',') === '0,0,0' && els[0].to.join(',') === '16,16,16';
  if (isFullCube) failures.push(`${id}: model is a single full 16^3 cube`);
  const minEls = isGate ? 20 : 10;
  if (els.length < minEls) failures.push(`${id}: only ${els.length} elements (need >= ${minEls})`);
  if (size > 120 * 1024) failures.push(`${id}: model is ${(size / 1024).toFixed(1)} KB (limit 120 KB)`);
  // Rotated elements are optional in 26.2: Blender-authored cuboid models may be
  // axis-aligned as long as they are detailed and recognisable (>=10 elements,
  // >=4 light colours).

  // 26.2 gates are a single-block footprint (16 px) so adjacent gates tile
  // seamlessly in any direction, and 24 px tall (1.5 blocks) so the player
  // cannot jump over. The door is animated in EGateRenderer, so the static
  // model must keep the 10 px passage (x 3..13) clear.
  // Round 9 allows standing consoles / full kiosks to be two blocks tall
  // (up to 32 px, see ROUND9_SPEC section 2), so the non-gate vertical bound is
  // 32.5 rather than the old 24.5. Gates keep their own 24 px envelope below.
  // 26.4: the round-3 gates carry a cantilevered face-recognition screen on a
  // 4.2 px arm, so the gate envelope is 22.5 px tall instead of 20. It is still
  // a single-block footprint and the extra height sits well above the door.
  const span = isGate
    ? { x: [-3.2, 19.2], y: [-0.5, 22.5], z: [-0.5, 17.0] }
    : { x: [-0.5, 16.5], y: [-0.5, 32.5], z: [-0.5, 16.5] };

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
      // 26.4 gates use a real 15 deg slanted reader deck and camera screen, while
      // station_verifier keeps its legacy 22.5 deg steps, so the accepted grid is
      // 2.5 deg (a whole number, <= 45) rather than 22.5 only.
      if (Math.abs((a / 2.5) - Math.round(a / 2.5)) > 1e-6) failures.push(`${id}: element ${i} rotation ${a} not a multiple of 2.5`);
    }
  }

  if (isGate) {
    // Single-block footprint, 1.4-block tall, with a genuinely empty central
    // passage (x 2.6..13.4, lane 10.8) for the renderer's animated door leaves.
    // 26.4: cabinets narrowed to 2.6 px per side, and the passage test only
    // guards the walkable/leaf height - the camera head's cantilever lives above
    // y = 17.5 and must not trip this.
    const gx1 = Math.min(...els.map(e => e.from[0]));
    const gx2 = Math.max(...els.map(e => e.to[0]));
    const gy1 = Math.min(...els.map(e => e.from[1]));
    const gy2 = Math.max(...els.map(e => e.to[1]));
    if (gx2 - gx1 < 15) failures.push(`${id}: gate is ${(gx2 - gx1).toFixed(1)} px wide (need >= 15, single block)`);
    if (gy2 - gy1 < 16) failures.push(`${id}: gate is ${(gy2 - gy1).toFixed(1)} px tall (need >= 16)`);
    if (gx2 - gx1 > 22) failures.push(`${id}: gate is ${(gx2 - gx1).toFixed(1)} px wide (shell too wide)`);
    const clear = { x1: 2.6, y1: 0.9, z1: 0.3, x2: 13.4, y2: 17.5, z2: 15.7 };
    for (const [i, el] of els.entries()) {
      if (el.from[0] < clear.x2 && el.to[0] > clear.x1 &&
          el.from[1] < clear.y2 && el.to[1] > clear.y1 &&
          el.from[2] < clear.z2 && el.to[2] > clear.z1) {
        failures.push(`${id}: element ${i} intrudes into the 10 px door passage`);
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

// ---- extra assembly renders: gates with their swing wings closed -----------
// The wings are NOT part of the shipped block model (EGateRenderer still
// animates its own leaves, and the passage rule above must stay strict), so
// they travel in the .bbmodel with export:false. Rendering the combined model
// here keeps the static wing placement reviewable.
const ASM_DIR = path.join(ROOT, 'assets', 'models', '_preview');
const assemblyRenders = [];
const EXTRA_VIEWS = [
  { suffix: '_assembly.json', view: { az: 208, el: 26 } },   // gate + closed swing wings
  { suffix: '_pair.json', view: { az: 200, el: 22 } },       // two gates butted together
  { suffix: '_seam.json', view: { az: 205, el: 24 } }        // close-up of the joint
];
if (fs.existsSync(ASM_DIR)) {
  const all = fs.readdirSync(ASM_DIR).sort();
  for (const { suffix, view } of EXTRA_VIEWS) {
    for (const f of all.filter(n => n.endsWith(suffix))) {
      try {
        const m = JSON.parse(fs.readFileSync(path.join(ASM_DIR, f), 'utf8'));
        const outName = path.basename(f, '.json') + '.png';
        const r = renderModel(m, path.join(OUT, outName), view);
        for (const miss of r.missing) failures.push(`render ${f}: missing texture ${miss}`);
        assemblyRenders.push({ file: outName, elements: (m.elements || []).length, dims: r.dims });
      } catch (e) {
        failures.push(`render ${f}: ${e.message}`);
      }
    }
  }
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
  if (id === 'egate' || id === 'exit_gate' || id === 'dual_gate') {
    // The gates expose facing/left/right/open; open never changes the model (the
    // leaves are renderer geometry), left/right pick the cap variant.
    let missing = 0;
    for (const f of ['north', 'east', 'south', 'west']) {
      for (const l of ['false', 'true']) {
        for (const r of ['false', 'true']) {
          for (const o of ['false', 'true']) {
            const key = `facing=${f},left=${l},right=${r},open=${o}`;
            if (!variants[key]) missing++;
          }
        }
      }
    }
    if (missing) failures.push(`blockstate ${id}: ${missing}/32 facing,left,right,open variants missing`);
    // both open values must resolve to the same model
    for (const f of ['north', 'east', 'south', 'west']) {
      for (const l of ['false', 'true']) {
        for (const r of ['false', 'true']) {
          const a = variants[`facing=${f},left=${l},right=${r},open=false`];
          const b = variants[`facing=${f},left=${l},right=${r},open=true`];
          if (a && b && a.model !== b.model) {
            failures.push(`blockstate ${id}: open=true changes the model (facing=${f},left=${l},right=${r})`);
          }
        }
      }
    }
    for (const suffix of ['', '_left', '_right', '_both']) {
      const ref = `veriborder:block/${id}${suffix}`;
      if (!Object.values(variants).some(v => (Array.isArray(v) ? v[0] : v).model === ref)) {
        failures.push(`blockstate ${id}: no variant uses ${ref}`);
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
for (const id of ['exit_gate', 'self_endorsement', 'dual_gate']) {
  if (!rows.some(r => r.id === id)) failures.push(`missing required model ${id}`);
  if (!fs.existsSync(path.join(BS_DIR, id + '.json'))) failures.push(`missing required blockstate ${id}`);
  if (!fs.existsSync(path.join(ITEM_DIR, id + '.json'))) failures.push(`missing required item model ${id}`);
  if (!fs.existsSync(path.join(LOOT_DIR, id + '.json'))) failures.push(`missing required loot table ${id}`);
}
if (!fs.existsSync(path.join(ITEM_DIR, 'device_brush.json'))) failures.push('missing required item model device_brush');
if (!fs.existsSync(path.join(ASSETS, 'textures', 'item', 'device_brush.png'))) failures.push('missing required texture device_brush');

// No fixed model count any more: gates ship four cap variants, so the population
// grows by design. Instead every json present is validated on its own merits
// above, and this list pins the models the game actually references.
const REQUIRED_MODELS = [
  'egate', 'egate_left', 'egate_right', 'egate_both',
  'exit_gate', 'exit_gate_left', 'exit_gate_right', 'exit_gate_both',
  'dual_gate', 'dual_gate_left', 'dual_gate_right', 'dual_gate_both',
  'self_endorsement'
];
for (const id of REQUIRED_MODELS) {
  if (!rows.some(r => r.id === id)) failures.push(`missing required model ${id}`);
}
const MIN_MODELS = 22;   // 19 single-model devices + the 3 gate shells
if (rows.length < MIN_MODELS) failures.push(`only ${rows.length} block models (expected at least ${MIN_MODELS})`);

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
const gateRows = rows.filter(r => r.id.startsWith('egate') || r.id.startsWith('exit_gate')
  || r.id.startsWith('dual_gate'));
lines.push(`gates: ${gateRows.map(g => `${g.id} ${g.bbox} (${g.elements} els, ${g.rotated} rotated)`).join('   ')}   passage: 10.8 px clear (x 2.6..13.4), single-block footprint`);
lines.push('gate doors: animated in EGateRenderer (two 5 x 12 x 2 px framed glass leaves slide into the pillars on open)');
if (assemblyRenders.length) {
  lines.push('assembly renders (model + static swing wings, review only):');
  for (const a of assemblyRenders) {
    lines.push(`  tools/preview/${a.file}  ${a.elements} els  ${a.dims.map(n => n.toFixed(1)).join(' x ')}`);
  }
}

lines.push('');
lines.push('CHECKLIST');
const checks = [
  ['every model >= 10 elements (gates >= 20)', !failures.some(f => f.includes('elements (need'))],
  ['rotated elements optional (Blender cuboids may be axis-aligned)', !failures.some(f => f.includes('no rotated element'))],
  ['no single full cube', !failures.some(f => f.includes('single full 16^3 cube'))],
  ['unique geometry per device (dual_gate may mirror egate)', !failures.some(f => f.includes('identical geometry'))],
  ['>= 4 light palette colors', !failures.some(f => f.includes('light palette colors'))],
  ['all texture refs resolve', !failures.some(f => f.includes('missing texture') || f.includes('declared texture'))],
  ['textures are 32 or 64 px', !failures.some(f => f.includes('need 32 or 64'))],
  ['model files < 120 KB', !failures.some(f => f.includes('limit 120'))],
  ['blockstates reference existing models', !failures.some(f => f.includes('variant') && f.includes('missing model'))],
  ['item model parents resolve', !failures.some(f => f.includes('missing parent'))],
  ['all gates keep the lane and are >= 16 px tall', !failures.some(f => f.includes('px wide (need') || f.includes('px tall (need') || f.includes('shell too wide'))],
  ['10 px gate passage is clear', !failures.some(f => f.includes('door passage'))],
  ['every model + blockstate + item model + loot present', !failures.some(f => f.includes('expected 22') || f.includes('missing required') || f.includes('only '))],
  ['gates have 32 facing,left,right,open variants (open picks no model)', !failures.some(f => f.includes('blockstate egate') || f.includes('blockstate exit_gate') || f.includes('blockstate dual_gate'))]
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

#!/usr/bin/env node
/*
 * VeriBorder GUI-slot item renderer  (26.2.1 two-block item fix)
 *
 *   node tools/preview_item_gui.js
 *   node tools/preview_item_gui.js --out tools/preview/twoblock \
 *        --before tools/preview/twoblock_before/models --zoom 8
 *
 * Renders every TWO-BLOCK device item (the DeviceBlock.TALL set, see
 * tools/lib/device_tall.js) exactly as the inventory draws it, twice:
 *
 *   <out>/<id>_before.png   the model snapshot given by --before (the shipped
 *                           one-block icon, i.e. the reported bug),
 *   <out>/<id>_after.png    the live models/item/<id>.json (both halves merged).
 *
 * Unlike tools/preview_items.js this renderer does NOT auto-fit the model to the
 * frame: it applies the model's own "display": { "gui": ... } transform
 * (translation -> rotation Y 225 -> rotation X 30 -> scale) and projects
 * orthographically at a fixed zoom, so the pixels are the pixels the game shows.
 * One inventory slot is 16 model units; the viewport is therefore a 16x16 slot
 * box centred on the item-space origin, rendered at ZOOM pixels per unit.
 *
 * <out>/manifest.json carries the numbers the sheet composer prints: bbox height,
 * gui scale, and the projected on-screen height in slot px and in blocks. The
 * "block" reference is measured, not assumed: a plain 16x16x16 cube is pushed
 * through the same vanilla gui transform (0.625, rotation 30/225) and its
 * projected silhouette height is the yardstick ("1.0 block of visual height").
 */
'use strict';

const fs = require('fs');
const path = require('path');
const zlib = require('zlib');
const { TALL_IDS, SOURCE } = require('./lib/device_tall');

const ROOT = path.resolve(__dirname, '..');
const ASSETS = path.join(ROOT, 'src', 'main', 'resources', 'assets', 'veriborder');

function argOf(name, fallback) {
  const i = process.argv.indexOf('--' + name);
  return i >= 0 && process.argv[i + 1] ? process.argv[i + 1] : fallback;
}
const MODEL_DIR = path.resolve(ROOT, argOf('models', path.join(ASSETS, 'models', 'item')));
const BEFORE_DIR = path.resolve(ROOT, argOf('before', path.join(ROOT, 'tools', 'preview', 'twoblock_before', 'models')));
const TEX_ITEM = path.resolve(ROOT, argOf('tex-item', path.join(ASSETS, 'textures', 'item')));
const OUT = path.resolve(ROOT, argOf('out', path.join(ROOT, 'tools', 'preview', 'twoblock')));
const ZOOM = Number(argOf('zoom', '8'));           // image px per model unit
const SLOT = 16;                                   // one inventory slot, in model units
const SIZE = Math.round(SLOT * ZOOM);              // image px per slot
const GUI_SCALE_BLOCK = 0.625;
const GUI_UNIT_PX = GUI_SCALE_BLOCK * 16;          // 10 slot px == one block on screen

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

// ---------------------------------------------------------------- textures
const textureCache = new Map();
function loadTexture(ref) {
  if (textureCache.has(ref)) return textureCache.get(ref);
  const rel = ref.includes(':') ? ref.split(':')[1] : ref;
  const file = path.join(TEX_ITEM, rel.replace(/^item\//, '') + '.png');
  const tex = fs.existsSync(file) ? Object.assign({ file }, readPng(file)) : null;
  textureCache.set(ref, tex);
  return tex;
}

// ---------------------------------------------------------------- 3D maths
const vsub = (a, b) => [a[0] - b[0], a[1] - b[1], a[2] - b[2]];
const vcross = (a, b) => [a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0]];
const vdot = (a, b) => a[0] * b[0] + a[1] * b[1] + a[2] * b[2];
function vnorm(a) {
  const l = Math.hypot(a[0], a[1], a[2]) || 1;
  return [a[0] / l, a[1] / l, a[2] / l];
}
const rad = deg => (deg * Math.PI) / 180;

// MC ItemTransform order: translate, rotate Y, rotate X, rotate Z, scale.
function displayTransform(p, disp) {
  const t = (disp.translation || [0, 0, 0]);
  const r = (disp.rotation || [0, 0, 0]);
  const s = (disp.scale || [1, 1, 1]);
  let v = [p[0] + t[0], p[1] + t[1], p[2] + t[2]];
  // rotation Y
  let a = rad(r[1]), c = Math.cos(a), sn = Math.sin(a);
  v = [v[0] * c + v[2] * sn, v[1], -v[0] * sn + v[2] * c];
  // rotation X
  a = rad(r[0]); c = Math.cos(a); sn = Math.sin(a);
  v = [v[0], v[1] * c - v[2] * sn, v[1] * sn + v[2] * c];
  // rotation Z
  a = rad(r[2]); c = Math.cos(a); sn = Math.sin(a);
  v = [v[0] * c - v[1] * sn, v[0] * sn + v[1] * c, v[2]];
  return [v[0] * s[0], v[1] * s[1], v[2] * s[2]];
}

const FACE_DEF = {
  north: { verts: b => [[b.x2, b.y2, b.z1], [b.x1, b.y2, b.z1], [b.x1, b.y1, b.z1], [b.x2, b.y1, b.z1]], uv: b => [b.x1, 16 - b.y2, b.x2, 16 - b.y1] },
  south: { verts: b => [[b.x1, b.y2, b.z2], [b.x2, b.y2, b.z2], [b.x2, b.y1, b.z2], [b.x1, b.y1, b.z2]], uv: b => [16 - b.x2, 16 - b.y2, 16 - b.x1, 16 - b.y1] },
  east: { verts: b => [[b.x2, b.y2, b.z2], [b.x2, b.y2, b.z1], [b.x2, b.y1, b.z1], [b.x2, b.y1, b.z2]], uv: b => [16 - b.z2, 16 - b.y2, 16 - b.z1, 16 - b.y1] },
  west: { verts: b => [[b.x1, b.y2, b.z1], [b.x1, b.y2, b.z2], [b.x1, b.y1, b.z2], [b.x1, b.y1, b.z1]], uv: b => [b.z1, 16 - b.y2, b.z2, 16 - b.y1] },
  up: { verts: b => [[b.x1, b.y2, b.z1], [b.x2, b.y2, b.z1], [b.x2, b.y2, b.z2], [b.x1, b.y2, b.z2]], uv: b => [b.x1, b.z1, b.x2, b.z2] },
  down: { verts: b => [[b.x1, b.y1, b.z2], [b.x2, b.y1, b.z2], [b.x2, b.y1, b.z1], [b.x1, b.y1, b.z1]], uv: b => [b.x1, 16 - b.z2, b.x2, 16 - b.z1] }
};

function buildQuads(model) {
  const quads = [];
  const missing = [];
  for (const el of model.elements || []) {
    const b = { x1: el.from[0], y1: el.from[1], z1: el.from[2], x2: el.to[0], y2: el.to[1], z2: el.to[2] };
    for (const fname of Object.keys(FACE_DEF)) {
      const face = el.faces && el.faces[fname];
      if (!face || face.texture === undefined) continue;
      let tref = face.texture;
      if (tref.startsWith('#')) tref = model.textures[tref.slice(1)];
      const tex = tref ? loadTexture(tref) : null;
      if (!tex) { missing.push(tref || fname); continue; }
      const uvs = face.uv || FACE_DEF[fname].uv(b);
      const u1 = uvs[0] / 16 * tex.w, v1 = uvs[1] / 16 * tex.h;
      const u2 = uvs[2] / 16 * tex.w, v2 = uvs[3] / 16 * tex.h;
      quads.push({
        verts: FACE_DEF[fname].verts(b),
        uv: [[u1, v1], [u2, v1], [u2, v2], [u1, v2]],
        tex
      });
    }
  }
  return { quads, missing };
}

// ---------------------------------------------------------------- raster
function rasterTri(buf, W, p0, p1, p2, uv0, uv1, uv2, tex, shade, depthBuf, d0, d1, d2) {
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
      if (z > depthBuf[zi] + 1e-6) continue;
      depthBuf[zi] = z;
      const u = w0 * uv0[0] + w1 * uv1[0] + w2 * uv2[0];
      const v = w0 * uv0[1] + w1 * uv1[1] + w2 * uv2[1];
      let tx = Math.floor(u), ty = Math.floor(v);
      if (tx < 0) tx = 0; if (tx >= tw) tx = tw - 1;
      if (ty < 0) ty = 0; if (ty >= th) ty = th - 1;
      const si = (ty * tw + tx) * 4;
      const sa = td[si + 3];
      if (sa < 6) continue;
      const a = sa / 255;
      const di = zi * 4;
      buf[di] = buf[di] * (1 - a) + td[si] * shade * a;
      buf[di + 1] = buf[di + 1] * (1 - a) + td[si + 1] * shade * a;
      buf[di + 2] = buf[di + 2] * (1 - a) + td[si + 2] * shade * a;
      buf[di + 3] = Math.max(buf[di + 3], Math.round(255 * a));
    }
  }
}

function bboxHeight(elements) {
  const ys = [];
  for (const e of elements || []) ys.push(e.from[1], e.to[1]);
  return Math.max(...ys) - Math.min(...ys);
}

/*
 * Render one item model into a SIZE x SIZE RGBA buffer, transparent outside the
 * silhouette, using the model's own gui display transform and a fixed zoom. The
 * viewport is the 16x16 slot box centred on item-space origin (item models are
 * corner-based, so model (8,8,8) is the centre of a one-block item).
 */
function renderSlot(model, outFile) {
  const { quads, missing } = buildQuads(model);
  const buf = Buffer.alloc(SIZE * SIZE * 4);         // transparent
  const depthBuf = new Float32Array(SIZE * SIZE).fill(Infinity);
  const disp = (model.display && model.display.gui) || { rotation: [0, 0, 0], translation: [0, 0, 0], scale: [1, 1, 1] };
  const light = vnorm([-0.52, 0.80, -0.42]);
  let minX = Infinity, maxX = -Infinity, minY = Infinity, maxY = -Infinity;
  const projected = [];

  // corner-based model space -> centred on the block centre (8,8,8).
  // The stored depth is -z (distance from the viewer, who sits at +z), matching
  // rasterTri's "keep the smallest depth" test and the far-to-near sort below.
  const toSlot = p => {
    const v = displayTransform([p[0] - 8, p[1] - 8, p[2] - 8], disp);
    return [v[0] * ZOOM + SIZE / 2, -v[1] * ZOOM + SIZE / 2, -v[2]];
  };
  for (const q of quads) {
    const ps = q.verts.map(toSlot);
    for (const p of ps) {
      minX = Math.min(minX, p[0]); maxX = Math.max(maxX, p[0]);
      minY = Math.min(minY, p[1]); maxY = Math.max(maxY, p[1]);
    }
    const n = vnorm(vcross(vsub(q.verts[1], q.verts[0]), vsub(q.verts[3], q.verts[0])));
    const dot = Math.max(0, vdot(n, light));
    let shade = 0.70 + 0.34 * dot;
    if (n[1] < -0.2) shade *= 0.74;
    shade = Math.max(0.46, Math.min(1.06, shade));
    projected.push({ q, ps, shade, depth: ps.reduce((a, p) => a + p[2], 0) / ps.length });
  }
  projected.sort((a, b) => b.depth - a.depth);      // far to near, z-buffer arbitrates
  for (const it of projected) {
    for (const tri of [[0, 1, 2], [0, 2, 3]]) {
      rasterTri(buf, SIZE, it.ps[tri[0]], it.ps[tri[1]], it.ps[tri[2]],
        it.q.uv[tri[0]], it.q.uv[tri[1]], it.q.uv[tri[2]], it.q.tex, it.shade,
        depthBuf, it.ps[tri[0]][2], it.ps[tri[1]][2], it.ps[tri[2]][2]);
    }
  }
  writePng(outFile, SIZE, SIZE, buf);

  const height = bboxHeight(model.elements);
  const guiScale = disp.scale[1];
  const visualSlotPx = quads.length ? (maxY - minY) / ZOOM : 0;
  return {
    elements: (model.elements || []).length,
    height: +height.toFixed(2),
    guiScale,
    guiShift: (disp.translation || [0, 0, 0])[1],
    visualSlotPx: +visualSlotPx.toFixed(2),
    visualBlocks: +(visualSlotPx / GUI_UNIT_PX).toFixed(2),
    clipped: quads.length ? (minX < 0 || minY < 0 || maxX > SIZE || maxY > SIZE) : false,
    missing
  };
}

// ---------------------------------------------------------------- reference
// Projected silhouette height of a plain 16^3 block item through the same vanilla
// gui transform. That is what "one block of visual height" looks like in a slot,
// so every two-block icon can be compared against it instead of against a guess.
function blockReferencePx() {
  const disp = { rotation: [30, 225, 0], translation: [0, 0, 0], scale: [GUI_SCALE_BLOCK, GUI_SCALE_BLOCK, GUI_SCALE_BLOCK] };
  let minY = Infinity, maxY = -Infinity;
  for (const x of [0, 16]) {
    for (const y of [0, 16]) {
      for (const z of [0, 16]) {
        const v = displayTransform([x - 8, y - 8, z - 8], disp);
        minY = Math.min(minY, v[1]);
        maxY = Math.max(maxY, v[1]);
      }
    }
  }
  return +(maxY - minY).toFixed(2);
}
const BLOCK_REF_PX = blockReferencePx();

// ---------------------------------------------------------------- main
const manifest = [];
let failed = 0;
for (const id of TALL_IDS) {
  const liveFile = path.join(MODEL_DIR, id + '.json');
  const beforeFile = path.join(BEFORE_DIR, id + '.json');
  if (!fs.existsSync(liveFile)) { console.error(`${id}: missing live item model`); failed++; continue; }
  const after = JSON.parse(fs.readFileSync(liveFile, 'utf8'));
  const row = { id, before: null, after: null };
  if (fs.existsSync(beforeFile)) {
    const before = JSON.parse(fs.readFileSync(beforeFile, 'utf8'));
    row.before = renderSlot(before, path.join(OUT, id + '_before.png'));
  }
  row.after = renderSlot(after, path.join(OUT, id + '_after.png'));
  for (const m of (row.after.missing || []).concat(row.before && row.before.missing || [])) {
    console.error(`${id}: missing texture ${m}`);
    failed++;
  }
  manifest.push(row);
}

// Scale reference for the sheet: a plain full-block item through the vanilla gui
// transform, so "one block of visual height" is visible next to the tall icons.
const referenceModel = {
  gui_light: 'front',
  textures: { all: 'veriborder:item/item_shell' },
  elements: [{
    from: [0, 0, 0], to: [16, 16, 16],
    faces: Object.fromEntries(['north', 'south', 'east', 'west', 'up', 'down']
      .map(f => [f, { uv: [0, 0, 16, 16], texture: '#all' }])),
  }],
  display: { gui: { rotation: [30, 225, 0], translation: [0, 0, 0], scale: [GUI_SCALE_BLOCK, GUI_SCALE_BLOCK, GUI_SCALE_BLOCK] } },
};
const referenceMetrics = renderSlot(referenceModel, path.join(OUT, '_reference_block.png'));

fs.writeFileSync(path.join(OUT, 'manifest.json'), JSON.stringify({
  generated: 'tools/preview_item_gui.js',
  registry: SOURCE.set,
  devices: TALL_IDS,
  zoom: ZOOM,
  slotPx: SIZE,
  blockRefPx: BLOCK_REF_PX,
  reference: referenceMetrics,
  guiUnitPx: GUI_UNIT_PX,
  rows: manifest
}, null, 2) + '\n', 'utf8');

const lines = [];
lines.push(`two-block GUI slot renders -> ${path.relative(ROOT, OUT)}`);
lines.push(`registry: ${SOURCE.set} (${TALL_IDS.length} devices)   slot: ${SLOT}x${SLOT} units = ${SIZE}x${SIZE} px   zoom ${ZOOM}`);
lines.push(`measured reference: a plain 16x16x16 block item projects to ${BLOCK_REF_PX} px at gui scale ${GUI_SCALE_BLOCK}`);
lines.push('');
lines.push('id                    before (1-block icon)          after (both halves)');
lines.push('                      height  scale   on-screen      height  scale   on-screen');
lines.push('---------------------------------------------------------------------------------------');
for (const r of manifest) {
  const b = r.before || {};
  const fmt = (o) => `${String(o.height).padStart(7)} ${String(o.guiScale).padStart(7)}  ${String(o.visualSlotPx).padStart(6)} px`;
  lines.push(r.id.padEnd(22) + fmt(b).padEnd(32) + fmt(r.after));
}
lines.push('');
lines.push(`before: ${manifest.filter(r => r.before && r.before.height <= 16.5).length}/${manifest.length} one-block icons (<= 16.5 px, the reported bug)`);
lines.push(`after:  ${manifest.filter(r => r.after.height > 20).length}/${manifest.length} icons > 20 px (both halves present)`);
lines.push(`on-screen: max ${Math.max(...manifest.map(r => r.after.visualSlotPx)).toFixed(2)} px vs the ${BLOCK_REF_PX} px one-block reference = ` +
  `${(Math.max(...manifest.map(r => r.after.visualSlotPx)) / BLOCK_REF_PX).toFixed(2)} blocks (target <= 1.00)`);
lines.push(`reference render: _reference_block.png (16x16x16 block item, ${referenceMetrics.visualSlotPx} px on screen)`);
console.log(lines.join('\n'));
process.exit(failed ? 1 : 0);

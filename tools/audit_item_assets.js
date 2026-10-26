#!/usr/bin/env node
/*
 * VeriBorder item-asset auditor  (26.4.1 item asset upgrade)
 *
 *   node tools/audit_item_assets.js
 *
 * Checks every src/main/resources/assets/veriborder/models/item/*.json and every
 * textures/item/*.png. Exit 0 only when everything passes.
 *
 * ITEM MODELS
 *   * valid JSON,
 *   * a model that declares "elements" is a DEDICATED item model and must:
 *       - carry "gui_light": "front" (block models default to "side", which
 *         makes an inventory icon's front faces go dark),
 *       - declare 20..40 elements (below that the silhouette is a slab, above
 *         that the element count costs render time for no visual gain),
 *       - declare a "display" block with gui + thirdperson_righthand +
 *         firstperson_righthand so the icon sits in the slot and does not clip
 *         through the hand,
 *       - be axis-aligned: no element "rotation" in an item model,
 *       - have no two elements with same-facing coincident faces that overlap
 *         (the z-fighting / light-leak case; touching back-to-back faces are
 *         fine), and no element fully enclosed by another (invisible geometry),
 *       - resolve every face texture: "#key" must exist in the model's own
 *         "textures", and every referenced PNG must exist on disk,
 *       - stay inside the item envelope: x/z within 0..16 px, y within
 *         0..32.5 px (two world blocks - see below).
 *
 *   TWO-BLOCK DEVICES (26.2.1)
 *   Eleven devices are two blocks tall in the world (DeviceBlock.TALL spawns a
 *   DeviceExtensionBlock above them, see tools/lib/device_tall.js). Their item
 *   model must contain BOTH halves, so it is checked against a taller envelope
 *   and against two hard rules:
 *       - bbox height > 20 px (a one-block icon means the upper half is missing,
 *         which is exactly the "two-block machine shows up as one block" bug),
 *       - geometry on both sides of the y = 16 seam (the merged extension tier).
 *   Single-block devices must stay <= 16.5 px tall. Both classes must keep
 *   height * display.gui.scale.y <= 10 px, i.e. one block of visual height at
 *   the vanilla 0.625 gui scale - a 25 px icon at 0.625 would be 1.5 blocks tall
 *   and overflow the inventory slot.
 *   * a model that declares a "parent" must have that parent resolve
 *     (veriborder:item/x, veriborder:block/x, minecraft:item/generated,
 *     minecraft:item/handheld).
 *
 * TEXTURES
 *   * decode as PNG, contain at least one opaque pixel, both dimensions 32 or
 *     64, and BINARY alpha (no pixel with 0 < alpha < 255) - a feathered edge
 *     drops the whole icon into Iris/OptiFine's sorted translucent pass, which
 *     is what makes items look see-through under shaders.
 *
 * The gate item models (egate / exit_gate / dual_gate) belong to the gate line
 * and are exempt from the structural checks (element count, rotation, display);
 * they are still checked for valid JSON, parent resolution and textures.
 */
'use strict';

const fs = require('fs');
const path = require('path');
const zlib = require('zlib');
const { TALL_SET, SOURCE } = require('./lib/device_tall');

const ROOT = path.resolve(__dirname, '..');
const ASSETS = path.join(ROOT, 'src', 'main', 'resources', 'assets', 'veriborder');
const ITEM_DIR = path.join(ASSETS, 'models', 'item');
const BLOCK_DIR = path.join(ASSETS, 'models', 'block');
const TEX_ITEM = path.join(ASSETS, 'textures', 'item');
const TEX_BLOCK = path.join(ASSETS, 'textures', 'block');

const GATE_IDS = new Set(['egate', 'exit_gate', 'dual_gate', 'egate_open', 'exit_gate_open']);
const MIN_ELS = 20;
const MAX_ELS = 40;
const EPS = 0.02;
// Item envelope. x/z never leave the block; y may reach two world blocks because
// the eleven DeviceBlock.TALL machines ship both halves inside one item model.
const MAX_XZ = 16;
const MAX_Y = 32.5;
const SEAM_Y = 16;
// Two-block / single-block height contract.
const TALL_MIN_HEIGHT = 20;      // two-block icons are strictly taller than this
const SINGLE_MAX_HEIGHT = 16.5;  // one-block icons stay at or below this
// gui fit: 16 px block * 0.625 = 10 px on screen = "one block" of inventory height.
const GUI_SCALE_BLOCK = 0.625;
const GUI_UNIT_PX = GUI_SCALE_BLOCK * 16;

// ------------------------------------------------------------------ PNG
function readPng(file) {
  const buf = fs.readFileSync(file);
  if (buf.readUInt32BE(0) !== 0x89504e47) throw new Error('not a PNG');
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
  if (depth !== 8) throw new Error('unsupported bit depth ' + depth);
  if (interlace !== 0) throw new Error('interlaced PNG unsupported');
  const channels = { 0: 1, 2: 3, 3: 1, 4: 2, 6: 4 }[color];
  if (!channels) throw new Error('unsupported colour type ' + color);
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
  const alpha = Buffer.alloc(w * h, 255);
  for (let i = 0; i < w * h; i++) {
    if (color === 6) alpha[i] = out[i * 4 + 3];
    else if (color === 4) alpha[i] = out[i * 2 + 1];
    else if (color === 3) alpha[i] = trns && out[i] < trns.length ? trns[out[i]] : 255;
  }
  return { w, h, alpha };
}

// ------------------------------------------------------------------ helpers
function resolveModel(ref) {
  const ns = ref.includes(':') ? ref.split(':')[0] : 'veriborder';
  const rel = ref.includes(':') ? ref.split(':')[1] : ref;
  // builtin/* is a vanilla parent too: builtin/entity is the BlockEntityWithoutLevelRenderer
  // parent the document line uses for its double-layer tintable documents.
  if (rel.startsWith('builtin/')) return 'vanilla';
  if (ns === 'minecraft') {
    return rel === 'item/generated' || rel === 'item/handheld' ? 'vanilla' : null;
  }
  const dir = rel.startsWith('block/') ? BLOCK_DIR : ITEM_DIR;
  const name = rel.replace(/^(block|item)\//, '');
  const file = path.join(dir, name + '.json');
  return fs.existsSync(file) ? file : null;
}
function textureExists(ref) {
  const rel = ref.includes(':') ? ref.split(':')[1] : ref;
  const dir = rel.startsWith('block/') ? TEX_BLOCK : TEX_ITEM;
  const name = rel.replace(/^(block|item)\//, '');
  return fs.existsSync(path.join(dir, name + '.png'));
}

// ------------------------------------------------------------------ model checks
function auditModel(id, model) {
  const problems = [];
  const els = Array.isArray(model.elements) ? model.elements : [];
  const gate = GATE_IDS.has(id);

  if (model.parent && !resolveModel(model.parent)) {
    problems.push(`parent ${model.parent} does not resolve`);
  }
  if (els.length === 0) {
    if (!model.parent) problems.push('has neither elements nor a parent');
    return problems;
  }
  if (gate) return problems;   // gate line owns its item models

  if (model.gui_light !== 'front') problems.push('missing "gui_light": "front"');
  if (els.length < MIN_ELS || els.length > MAX_ELS) {
    problems.push(`${els.length} elements (need ${MIN_ELS}..${MAX_ELS})`);
  }
  const disp = model.display || {};
  for (const key of ['gui', 'thirdperson_righthand', 'firstperson_righthand']) {
    if (!disp[key]) problems.push(`display.${key} missing`);
  }
  const textures = model.textures || {};
  const box = els.map(e => ({
    min: [0, 1, 2].map(i => Math.min(e.from[i], e.to[i])),
    max: [0, 1, 2].map(i => Math.max(e.from[i], e.to[i])),
  }));
  for (const [i, e] of els.entries()) {
    if (e.rotation) problems.push(`element ${i} is rotated (item models stay axis-aligned)`);
    for (let k = 0; k < 3; k++) {
      const hi = k === 1 ? MAX_Y : MAX_XZ;
      if (e.from[k] >= e.to[k]) problems.push(`element ${i} from >= to on ${'xyz'[k]}`);
      if (e.from[k] < 0 || e.to[k] > hi) problems.push(`element ${i} outside 0..${hi} on ${'xyz'[k]}`);
    }
    for (const [fname, face] of Object.entries(e.faces || {})) {
      const ref = face.texture || '';
      if (ref.startsWith('#')) {
        const key = ref.slice(1);
        if (!textures[key]) problems.push(`element ${i}.${fname} -> unknown #${key}`);
        else if (!textureExists(textures[key])) problems.push(`element ${i}.${fname} -> ${textures[key]} missing on disk`);
      } else if (!textureExists(ref)) {
        problems.push(`element ${i}.${fname} -> ${ref} missing on disk`);
      }
    }
  }
  for (let i = 0; i < box.length; i++) {
    for (let j = i + 1; j < box.length; j++) {
      const a = box[i], b = box[j];
      for (let ax = 0; ax < 3; ax++) {
        const o1 = (ax + 1) % 3, o2 = (ax + 2) % 3;
        const ov1 = Math.min(a.max[o1], b.max[o1]) - Math.max(a.min[o1], b.min[o1]);
        const ov2 = Math.min(a.max[o2], b.max[o2]) - Math.max(a.min[o2], b.min[o2]);
        if (ov1 <= EPS || ov2 <= EPS) continue;
        if (Math.abs(a.max[ax] - b.max[ax]) < 0.005 || Math.abs(a.min[ax] - b.min[ax]) < 0.005) {
          problems.push(`coincident face ${i}/${j} on ${'xyz'[ax]}`);
        }
      }
      let inA = true, inB = true;
      for (let k = 0; k < 3; k++) {
        if (!(b.min[k] >= a.min[k] + 0.01 && b.max[k] <= a.max[k] - 0.01)) inA = false;
        if (!(a.min[k] >= b.min[k] + 0.01 && a.max[k] <= b.max[k] - 0.01)) inB = false;
      }
      if (inA) problems.push(`element ${j} fully enclosed by ${i}`);
      if (inB) problems.push(`element ${i} fully enclosed by ${j}`);
    }
  }

  // ---- two-block contract (26.2.1) -----------------------------------------
  // DeviceBlock.TALL devices must ship BOTH halves of the machine in the icon.
  const height = Math.max(...box.map(b => b.max[1])) - Math.min(...box.map(b => b.min[1]));
  const gui = (disp.gui && disp.gui.scale) || [1, 1, 1];
  const visual = height * gui[1];
  if (TALL_SET.has(id)) {
    if (!(height > TALL_MIN_HEIGHT)) {
      problems.push(`two-block device icon is ${height.toFixed(2)} px tall (need > ${TALL_MIN_HEIGHT}; upper half missing)`);
    }
    const belowSeam = box.some(b => b.min[1] < SEAM_Y - 0.01);
    const aboveSeam = box.some(b => b.max[1] > SEAM_Y + 0.01);
    if (!belowSeam || !aboveSeam) {
      problems.push(`two-block device icon has geometry on only one side of the y=${SEAM_Y} seam`);
    }
  } else if (height > SINGLE_MAX_HEIGHT) {
    problems.push(`single-block device icon is ${height.toFixed(2)} px tall (need <= ${SINGLE_MAX_HEIGHT})`);
  }
  if (visual > GUI_UNIT_PX * 1.001) {
    problems.push(`gui icon is ${visual.toFixed(2)} px tall on screen (limit ${GUI_UNIT_PX} px = one block); gui scale ${gui[1]} does not fit a ${height.toFixed(2)} px model`);
  }
  return problems;
}

// ------------------------------------------------------------------ main
const rows = [];
const failures = [];

const modelFiles = fs.existsSync(ITEM_DIR)
  ? fs.readdirSync(ITEM_DIR).filter(f => f.endsWith('.json')).sort()
  : [];
for (const file of modelFiles) {
  const id = path.basename(file, '.json');
  let model;
  try {
    model = JSON.parse(fs.readFileSync(path.join(ITEM_DIR, file), 'utf8'));
  } catch (e) {
    rows.push({ kind: 'model', name: id, detail: 'invalid JSON', verdict: 'FAIL' });
    failures.push(`${id}: invalid JSON (${e.message})`);
    continue;
  }
  const problems = auditModel(id, model);
  const els = Array.isArray(model.elements) ? model.elements : [];
  let kind = els.length ? `3d/${els.length}el` : 'flat';
  if (els.length) {
    const h = Math.max(...els.map(e => Math.max(e.from[1], e.to[1])))
      - Math.min(...els.map(e => Math.min(e.from[1], e.to[1])));
    kind += `/${h.toFixed(1)}px`;
  }
  rows.push({
    kind: 'model',
    name: id,
    detail: `${kind}  ${model.gui_light || '-'}  ${model.parent || 'standalone'}`,
    verdict: problems.length ? 'FAIL' : 'PASS',
  });
  for (const p of problems) failures.push(`${id}: ${p}`);
}

const texFiles = fs.existsSync(TEX_ITEM)
  ? fs.readdirSync(TEX_ITEM).filter(f => f.endsWith('.png')).sort()
  : [];
for (const file of texFiles) {
  const name = path.basename(file, '.png');
  let png;
  try {
    png = readPng(path.join(TEX_ITEM, file));
  } catch (e) {
    rows.push({ kind: 'texture', name, detail: e.message, verdict: 'FAIL' });
    failures.push(`${name}.png: ${e.message}`);
    continue;
  }
  const problems = [];
  let semi = 0, opaque = 0;
  for (const a of png.alpha) {
    if (a === 255) opaque++;
    else if (a !== 0) semi++;
  }
  if (opaque === 0) problems.push('empty (no opaque pixel)');
  if (semi > 0) problems.push(`${semi} pixel(s) with 0<alpha<255`);
  const okSize = (png.w === 32 || png.w === 64) && (png.h === 32 || png.h === 64);
  if (!okSize) problems.push(`${png.w}x${png.h} (need 32 or 64)`);
  rows.push({
    kind: 'texture',
    name,
    detail: `${png.w}x${png.h}  alpha:${semi === 0 ? 'binary' : 'feathered'}`,
    verdict: problems.length ? 'FAIL' : 'PASS',
  });
  for (const p of problems) failures.push(`${name}.png: ${p}`);
}

const lines = [];
lines.push('VeriBorder item asset audit (tools/audit_item_assets.js)');
lines.push(`models/item: ${modelFiles.length} file(s)   textures/item: ${texFiles.length} file(s)`);
lines.push(`two-block contract: ${TALL_SET.size} device(s) from ${SOURCE.set} must ship both halves (> ${TALL_MIN_HEIGHT} px, geometry either side of y=${SEAM_Y})`);
lines.push(`single-block devices: <= ${SINGLE_MAX_HEIGHT} px   gui: height * gui scale <= ${GUI_UNIT_PX} px (one block)`);
lines.push('');
lines.push('kind      name                          detail                                     verdict');
lines.push('---------------------------------------------------------------------------------------------');
for (const r of rows) {
  lines.push(
    r.kind.padEnd(10) + r.name.padEnd(30) + r.detail.padEnd(43) + r.verdict
  );
}
lines.push('');
const models3d = rows.filter(r => r.kind === 'model' && r.detail.startsWith('3d/')).length;
lines.push(`dedicated 3D item models: ${models3d}   flat item models: ${rows.filter(r => r.kind === 'model' && r.detail.startsWith('flat')).length}`);
lines.push(`of the 3D models: ${TALL_SET.size} two-block (both halves), ${models3d - TALL_SET.size} single-block`);
lines.push('');
if (failures.length === 0) {
  lines.push(`PASS: ${rows.length} asset(s) - valid JSON, gui_light front, element counts in range,`);
  lines.push('      no coincident/enclosed geometry, all texture refs resolve, binary alpha, 32/64 px,');
  lines.push('      two-block icons carry both halves, gui height fits one block');
  console.log(lines.join('\n'));
  process.exit(0);
}
lines.push(`FAIL: ${failures.length} problem(s)`);
for (const f of failures) lines.push('  - ' + f);
console.log(lines.join('\n'));
process.exit(1);

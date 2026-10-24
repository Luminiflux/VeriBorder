#!/usr/bin/env node
/**
 * R9 stamp/print devices — geometry source for the Blockbench .bbmodel files.
 *
 * Authoring contract (ROUND9_SPEC.md):
 *   - java_block, MC coords 0..16, Y up, north = front face
 *   - matte off-white shell + brushed silver + clear acrylic; dark only in
 *     recesses / press head / screen
 *   - every screen is 3 layers: silver bezel in front, dark shadow ring,
 *     screen face set back 0.10..0.12 px
 *   - shared material kit referenced by resource location
 *     (veriborder:block/r9_shell ...); per-device label/accent/paper keep the
 *     <id>_<slot> convention so tools/bbmodel_to_mc.js resolves them verbatim
 *
 * Usage:
 *   node tools/r9_build_stamp_print.js            # write the 4 .bbmodel files
 *   node tools/r9_build_stamp_print.js --spec entry_stamp
 *                                                # print the blockbench_create spec
 */
'use strict';

const fs = require('fs');
const path = require('path');
const { pathToFileURL } = require('url');

const ROOT = path.resolve(__dirname, '..');
const MODEL_DIR = path.join(ROOT, 'assets', 'models');
const PLUGIN_LIB = path.join(
  process.env.USERPROFILE || process.env.HOME || '',
  '.dsh', 'profiles', 'web', 'node_modules', 'dsh-blockbench', 'lib', 'bbmodel.js'
);

// ------------------------------------------------------------------ material kit
// [resource location, width, height]; uv stays 0..16 (MC normalised space).
const KIT = {
  shell:       ['veriborder:block/r9_shell', 32, 32],
  shell2:      ['veriborder:block/r9_shell2', 32, 32],
  silver:      ['veriborder:block/r9_silver_v2', 32, 32],
  silver_flat: ['veriborder:block/r9_silver_flat', 32, 32],
  silver_dark: ['veriborder:block/r9_silver_dark_v2', 32, 32],
  glass:       ['veriborder:block/r9_glass', 32, 32],
  dark:        ['veriborder:block/r9_dark', 32, 32],
  screen:      ['veriborder:block/r9_screen', 32, 32],
  led:         ['veriborder:block/r9_led_green', 32, 32],
  accent:      [null, 32, 32], // per device: veriborder:block/<id>_accent
  label:       [null, 64, 32], // per device: veriborder:block/<id>_label
  paper:       [null, 32, 32]  // per device: veriborder:block/<id>_paper
};

// face -> texture-name map. `shell` keeps shell2 on the sides so a flat white
// box never reads as one colour (spec 4: edge shading).
const MATS = {
  shell:       { north: 'shell', south: 'shell', east: 'shell2', west: 'shell2', up: 'shell', down: 'shell2' },
  shell_flat:  { north: 'shell', south: 'shell', east: 'shell', west: 'shell', up: 'shell', down: 'shell' },
  shell2:      { north: 'shell2', south: 'shell2', east: 'shell2', west: 'shell2', up: 'shell2', down: 'shell2' },
  silver:      { north: 'silver', south: 'silver', east: 'silver', west: 'silver', up: 'silver', down: 'silver' },
  silver_flat: { north: 'silver_flat', south: 'silver_flat', east: 'silver_flat', west: 'silver_flat', up: 'silver_flat', down: 'silver_flat' },
  silver_dark: { north: 'silver_dark', south: 'silver_dark', east: 'silver_dark', west: 'silver_dark', up: 'silver_dark', down: 'silver_dark' },
  dark:        { north: 'dark', south: 'dark', east: 'dark', west: 'dark', up: 'dark', down: 'dark' },
  glass:       { north: 'glass', south: 'glass', east: 'glass', west: 'glass', up: 'glass', down: 'glass' },
  screen:      { north: 'screen', south: 'dark', east: 'dark', west: 'dark', up: 'dark', down: 'dark' },
  led:         { north: 'led', south: 'led', east: 'led', west: 'led', up: 'led', down: 'led' },
  accent:      { north: 'accent', south: 'accent', east: 'accent', west: 'accent', up: 'accent', down: 'accent' },
  paper:       { north: 'paper', south: 'paper', east: 'paper', west: 'paper', up: 'paper', down: 'paper' },
  label:       { north: 'label', south: 'silver_flat', east: 'silver_flat', west: 'silver_flat', up: 'silver_flat', down: 'silver_flat' }
};

const FACES = ['north', 'south', 'east', 'west', 'up', 'down'];
const UV = [0, 0, 16, 16];

const r4 = (v) => Math.round(v * 10000) / 10000;

function box(name, from, to, mat, group) {
  const m = MATS[mat];
  if (!m) throw new Error('unknown material ' + mat);
  const faces = {};
  for (const f of FACES) faces[f] = { texture: m[f] };
  return { name, from: from.map(r4), to: to.map(r4), faces, group };
}

// Three-layer recessed screen: silver bezel frame (proud), dark shadow plate,
// screen face set back 0.10..0.12 px behind the bezel front.
function screen3(prefix, x1, x2, y1, y2, zf, group, opts) {
  const o = opts || {};
  const bw = o.bw !== undefined ? o.bw : 0.34;       // frame width
  const depth = o.depth !== undefined ? o.depth : 0.26; // bezel protrusion
  const inset = o.inset !== undefined ? o.inset : 0.30; // shadow ring width
  const els = [
    box(prefix + '_bezel_l', [x1 - bw, y1 - bw, zf - depth], [x1, y2 + bw, zf], 'silver', group),
    box(prefix + '_bezel_r', [x2, y1 - bw, zf - depth], [x2 + bw, y2 + bw, zf], 'silver', group),
    box(prefix + '_bezel_t', [x1, y2, zf - depth], [x2, y2 + bw, zf], 'silver', group),
    box(prefix + '_bezel_b', [x1, y1 - bw, zf - depth], [x2, y1, zf], 'silver', group),
    box(prefix + '_shadow', [x1, y1, zf - 0.05], [x2, y2, zf], 'dark', group),
    // The screen face sits 0.10 BEHIND the bezel front (larger z = deeper) and
    // 0.05 in front of the shadow plate, so the recess reads from every angle.
    box(prefix + '_face', [x1 + inset, y1 + inset, zf - depth + 0.10], [x2 - inset, y2 - inset, zf - 0.10], 'screen', group)
  ];
  return els;
}

function texturesFor(id) {
  const list = [];
  for (const [name, spec] of Object.entries(KIT)) {
    const loc = spec[0] ? spec[0] : 'veriborder:block/' + id + '_' + name;
    list.push({ name, path: loc, width: spec[1], height: spec[2] });
  }
  return list;
}

// ------------------------------------------------------------------ entry_stamp
// Standing stamp press, 26 px (2 blocks). Single rear column + cantilever arm.
function entryStamp() {
  const E = [];
  const g = (els) => E.push(...els);
  g([
    box('plinth', [1.6, 0, 1.6], [14.4, 1.6, 14.4], 'silver_dark', 'base'),
    box('plinth_trim', [1.75, 1.6, 1.75], [14.25, 1.78, 14.25], 'silver_flat', 'base'),
    box('cabinet', [2.0, 1.78, 2.0], [14.0, 12.2, 14.0], 'shell', 'base'),
    box('kick_plate', [1.93, 1.78, 1.93], [14.07, 2.62, 14.07], 'silver_flat', 'base'),
    box('seam_mid', [1.93, 6.42, 1.93], [14.07, 6.72, 14.07], 'shell2', 'base'),
    box('label', [3.2, 2.95, 1.86], [11.2, 6.15, 1.98], 'label', 'label')
  ]);
  g(screen3('screen', 3.4, 8.8, 7.2, 10.2, 2.0, 'screen'));
  g([
    box('accent', [9.9, 7.6, 1.9], [12.5, 9.4, 1.98], 'accent', 'controls'),
    box('led', [10.0, 10.0, 1.9], [10.5, 10.5, 1.98], 'led', 'controls'),
    box('deck', [1.8, 12.2, 1.8], [14.2, 13.0, 14.2], 'silver_flat', 'deck'),
    box('deck_lip', [1.8, 12.32, 1.72], [14.2, 12.9, 1.8], 'silver_dark', 'deck'),
    box('platen_glass', [4.6, 13.0, 4.2], [9.8, 13.06, 10.0], 'glass', 'deck'),
    box('document', [5.0, 13.06, 4.6], [9.4, 13.13, 9.6], 'paper', 'deck'),
    box('ink_pad', [10.4, 13.0, 4.4], [12.8, 13.16, 7.0], 'dark', 'deck'),
    box('rail_l', [2.9, 13.0, 3.4], [3.1, 13.5, 11.2], 'silver', 'deck'),
    box('rail_r', [12.9, 13.0, 3.4], [13.1, 13.5, 11.2], 'silver', 'deck'),
    box('column', [6.6, 13.0, 10.8], [9.4, 24.6, 13.6], 'shell', 'press'),
    box('column_foot', [6.3, 13.0, 10.5], [9.7, 14.2, 13.9], 'silver_flat', 'press'),
    box('column_cap', [6.3, 24.6, 10.5], [9.7, 25.5, 13.9], 'silver', 'press'),
    box('column_trim', [7.6, 14.2, 10.74], [8.4, 24.6, 10.8], 'silver_flat', 'press'),
    box('top_plate', [6.6, 25.5, 10.8], [9.4, 26.0, 13.6], 'shell2', 'press'),
    box('arm', [5.9, 20.8, 5.6], [10.1, 22.2, 10.85], 'silver', 'press'),
    box('arm_rib', [7.2, 19.8, 9.6], [8.8, 20.8, 10.8], 'silver_dark', 'press'),
    box('ram', [6.9, 18.4, 7.6], [9.1, 20.8, 9.2], 'silver', 'press'),
    box('head_body', [5.6, 15.0, 6.2], [10.4, 18.4, 9.8], 'dark', 'press'),
    box('head_band', [5.5, 17.0, 6.1], [10.5, 17.6, 9.9], 'silver_flat', 'press'),
    box('die', [6.2, 14.4, 6.8], [9.8, 15.0, 9.4], 'dark', 'press')
  ]);
  return E;
}

// ------------------------------------------------------------------- exit_stamp
// Same family, mirrored: column at the rear-left, arm sweeps right, 27 px.
function exitStamp() {
  const E = [];
  const g = (els) => E.push(...els);
  g([
    box('plinth', [1.7, 0, 1.7], [14.3, 1.4, 14.3], 'silver_dark', 'base'),
    box('plinth_trim', [1.85, 1.4, 1.85], [14.15, 1.55, 14.15], 'silver_flat', 'base'),
    box('cabinet', [2.2, 1.55, 2.2], [13.8, 11.8, 13.8], 'shell', 'base'),
    box('drawer_front', [2.6, 2.0, 2.02], [13.4, 4.2, 2.2], 'silver_flat', 'base'),
    box('drawer_handle', [6.4, 2.9, 1.94], [9.6, 3.2, 2.02], 'silver_dark', 'base'),
    box('label', [3.4, 4.7, 2.06], [11.4, 7.7, 2.18], 'label', 'label'),
    box('seam_mid', [2.15, 8.0, 2.15], [13.85, 8.3, 13.85], 'shell2', 'base')
  ]);
  g(screen3('screen', 3.2, 8.2, 8.9, 11.5, 2.2, 'screen', { bw: 0.3, inset: 0.28 }));
  g([
    box('accent', [9.4, 8.9, 2.1], [10.2, 11.5, 2.18], 'accent', 'controls'),
    box('led', [11.4, 8.95, 2.1], [11.9, 9.45, 2.18], 'led', 'controls'),
    box('deck', [2.0, 11.8, 2.0], [14.0, 12.6, 14.0], 'silver_flat', 'deck'),
    box('deck_lip', [2.0, 11.92, 1.92], [14.0, 12.5, 2.0], 'silver_dark', 'deck'),
    box('platen_glass', [5.6, 12.6, 4.2], [10.6, 12.66, 10.0], 'glass', 'deck'),
    box('document', [6.0, 12.66, 4.6], [10.2, 12.73, 9.6], 'paper', 'deck'),
    box('ink_pad', [3.4, 12.6, 4.4], [5.0, 12.76, 6.6], 'dark', 'deck'),
    box('rail_l', [5.2, 12.6, 4.0], [5.4, 13.1, 10.4], 'silver', 'deck'),
    box('rail_r', [10.8, 12.6, 4.0], [11.0, 13.1, 10.4], 'silver', 'deck'),
    box('column', [2.8, 12.6, 10.4], [5.6, 25.4, 13.2], 'shell', 'press'),
    box('column_foot', [2.5, 12.6, 10.1], [5.9, 13.8, 13.5], 'silver_flat', 'press'),
    box('column_cap', [2.5, 25.4, 10.1], [5.9, 26.3, 13.5], 'silver', 'press'),
    box('top_plate', [2.8, 26.3, 10.4], [5.6, 27.0, 13.2], 'shell2', 'press'),
    box('arm', [5.6, 21.4, 6.0], [8.6, 22.8, 13.2], 'silver', 'press'),
    box('arm_rib', [5.6, 19.6, 9.6], [7.6, 21.4, 10.4], 'silver_flat', 'press'),
    box('ram', [5.2, 19.0, 7.2], [8.4, 21.4, 9.4], 'silver', 'press'),
    box('head_body', [5.0, 14.8, 6.2], [9.4, 19.0, 9.6], 'dark', 'press'),
    box('head_band', [4.9, 17.2, 6.1], [9.5, 17.8, 9.7], 'silver_flat', 'press'),
    box('die', [5.6, 14.2, 6.8], [8.8, 14.8, 9.0], 'dark', 'press')
  ]);
  return E;
}

// ----------------------------------------------------------------- stamp_history
// 2-block query kiosk, 28 px: big recessed screen, document table, ticket slot.
function stampHistory() {
  const E = [];
  const g = (els) => E.push(...els);
  g([
    box('plinth', [1.7, 0, 1.7], [14.3, 1.4, 14.3], 'silver_dark', 'base'),
    box('plinth_trim', [1.85, 1.4, 1.85], [14.15, 1.55, 14.15], 'silver_flat', 'base'),
    box('body', [2.2, 1.55, 2.2], [13.8, 26.0, 13.8], 'shell', 'base'),
    box('kick_plate', [2.15, 1.55, 2.15], [13.85, 2.6, 13.85], 'silver_flat', 'base'),
    box('label', [3.8, 3.2, 2.06], [11.8, 6.2, 2.18], 'label', 'label'),
    box('seam_low', [2.15, 6.55, 2.15], [13.85, 6.8, 13.85], 'shell2', 'base'),
    box('doc_table', [2.6, 7.15, 1.0], [13.4, 7.85, 2.2], 'silver_flat', 'tray'),
    box('doc_slot', [4.6, 7.85, 1.3], [9.4, 7.93, 2.05], 'dark', 'tray'),
    box('ticket_slot', [8.6, 8.55, 1.98], [12.8, 9.45, 2.2], 'dark', 'tray'),
    box('ticket', [9.0, 8.7, 1.25], [12.4, 9.3, 2.0], 'paper', 'tray'),
    box('seam_hi', [2.15, 10.2, 2.15], [13.85, 10.45, 13.85], 'shell2', 'base')
  ]);
  g(screen3('screen', 3.8, 11.8, 12.2, 19.0, 2.2, 'screen', { bw: 0.4, depth: 0.28, inset: 0.4 }));
  g([
    box('led', [3.4, 20.2, 2.1], [3.9, 20.7, 2.18], 'led', 'controls'),
    box('card_lip', [11.5, 20.25, 1.94], [13.5, 20.45, 2.16], 'silver_flat', 'controls'),
    box('card_slot', [11.7, 20.45, 2.0], [13.3, 20.95, 2.16], 'dark', 'controls'),
    box('win_frame', [13.8, 10.4, 4.2], [13.86, 17.6, 10.8], 'silver_flat', 'window'),
    box('win_glass', [13.86, 10.9, 4.7], [13.92, 17.1, 10.3], 'glass', 'window'),
    box('vent_l', [2.14, 3.2, 4.4], [2.22, 6.2, 9.6], 'dark', 'base'),
    box('top_cap', [1.9, 26.0, 1.9], [14.1, 27.4, 14.1], 'silver_flat', 'top'),
    box('top_hood', [4.4, 27.4, 4.4], [11.6, 28.0, 11.6], 'shell2', 'top')
  ]);
  return E;
}

// ------------------------------------------------------------------ print_preview
// 20 px printer: recessed screen, paper output slot with a sheet out, flatbed
// scan glass on the lid, rear ADF tower.
function printPreview() {
  const E = [];
  const g = (els) => E.push(...els);
  g([
    box('plinth', [2.2, 0, 2.4], [13.8, 1.0, 13.8], 'silver_dark', 'base'),
    box('body', [2.4, 1.0, 2.6], [13.6, 12.6, 13.6], 'shell', 'base'),
    box('kick_plate', [2.35, 1.0, 2.55], [13.65, 1.8, 13.65], 'silver_flat', 'base'),
    box('label', [2.9, 2.2, 2.44], [11.3, 5.2, 2.56], 'label', 'label'),
    box('out_slot', [3.8, 6.0, 2.5], [10.2, 7.0, 2.62], 'dark', 'paper'),
    box('out_lip', [3.7, 5.78, 2.46], [10.3, 6.0, 2.62], 'silver_flat', 'paper'),
    box('sheet', [4.2, 6.3, 1.0], [9.8, 6.52, 2.52], 'paper', 'paper'),
    box('vent_l', [2.34, 3.0, 3.2], [2.42, 5.4, 9.8], 'dark', 'base')
  ]);
  g(screen3('screen', 3.2, 8.0, 8.2, 11.2, 2.6, 'screen', { bw: 0.28, depth: 0.24, inset: 0.26 }));
  g([
    box('led', [8.6, 10.4, 2.52], [9.1, 10.9, 2.6], 'led', 'controls'),
    box('lid', [2.2, 12.6, 2.4], [13.8, 14.0, 13.8], 'shell2', 'lid'),
    box('lid_plate', [2.35, 14.0, 2.55], [13.65, 14.2, 13.65], 'silver_flat', 'lid'),
    box('scan_glass', [3.4, 14.2, 4.4], [9.0, 14.26, 9.6], 'glass', 'lid'),
    box('scan_sheet', [3.9, 14.26, 4.9], [8.5, 14.32, 9.1], 'paper', 'lid'),
    box('adf', [5.6, 14.2, 10.4], [13.2, 18.2, 13.4], 'shell', 'adf'),
    box('adf_cap', [5.4, 18.2, 10.2], [13.4, 19.0, 13.6], 'silver', 'adf'),
    box('adf_slot', [6.6, 15.05, 10.28], [12.2, 15.75, 10.4], 'dark', 'adf'),
    box('adf_lip', [6.4, 14.75, 10.26], [12.4, 15.0, 10.4], 'silver_flat', 'adf'),
    box('adf_stack', [6.2, 19.0, 10.8], [12.6, 19.5, 13.0], 'paper', 'adf')
  ]);
  return E;
}

const MODELS = {
  entry_stamp: { build: entryStamp, groups: ['base', 'label', 'screen', 'controls', 'deck', 'press'] },
  exit_stamp: { build: exitStamp, groups: ['base', 'label', 'screen', 'controls', 'deck', 'press'] },
  stamp_history: { build: stampHistory, groups: ['base', 'label', 'tray', 'screen', 'controls', 'window', 'top'] },
  print_preview: { build: printPreview, groups: ['base', 'label', 'paper', 'screen', 'controls', 'lid', 'adf'] }
};

function specFor(id) {
  const def = MODELS[id];
  const elements = def.build();
  return {
    name: id,
    model_format: 'java_block',
    format_version: '4.10',
    resolution: { width: 64, height: 64 },
    textures: texturesFor(id),
    elements,
    groups: def.groups.map((name) => ({ name }))
  };
}

// ------------------------------------------------------- front-view dark ratio
// Rasterises the north-facing faces (painter order by depth) so the "black is
// limited to recesses / head / screen, < 25 % of the front" rule is measurable.
function frontDarkRatio(id, elements) {
  const W = 320, H = 600; // 20 px per model unit
  const grid = new Array(W * H).fill(null);
  const sorted = elements.slice().sort((a, b) => b.from[2] - a.from[2]);
  for (const el of sorted) {
    const mat = MATS[el._mat || ''];
    const tex = el.faces.north.texture;
    const kind = tex === 'dark' ? 'dark' : tex === 'screen' ? 'screen' : tex === 'label' ? 'label' : 'light';
    const x1 = Math.round(el.from[0] * 20), x2 = Math.round(el.to[0] * 20);
    const y1 = Math.round((32 - el.to[1]) * 20), y2 = Math.round((32 - el.from[1]) * 20);
    for (let y = Math.max(0, y1); y < Math.min(H, y2); y++) {
      for (let x = Math.max(0, x1); x < Math.min(W, x2); x++) grid[y * W + x] = kind;
    }
  }
  let dark = 0, covered = 0;
  for (const k of grid) {
    if (!k) continue;
    covered++;
    if (k === 'dark' || k === 'screen') dark++;
  }
  return { dark, covered, ratio: covered ? dark / covered : 0 };
}

function faceAreas(elements) {
  return elements
    .filter((e) => e.name === 'label')
    .map((e) => ({ name: e.name, w: +(e.to[0] - e.from[0]).toFixed(2), h: +(e.to[1] - e.from[1]).toFixed(2) }));
}

function heightOf(elements) {
  return Math.max(...elements.map((e) => e.to[1]));
}

async function main() {
  const specArg = process.argv.indexOf('--spec');
  if (specArg !== -1) {
    const id = process.argv[specArg + 1];
    const spec = specFor(id);
    // blockbench_create takes exactly this object (minus the local bookkeeping).
    process.stdout.write(JSON.stringify(spec));
    return;
  }

  const { createModel } = await import(pathToFileURL(PLUGIN_LIB).href);
  fs.mkdirSync(MODEL_DIR, { recursive: true });
  const report = [];
  for (const id of Object.keys(MODELS)) {
    const spec = specFor(id);
    const model = createModel(spec);
    fs.writeFileSync(path.join(MODEL_DIR, id + '.bbmodel'), JSON.stringify(model, null, 2) + '\n', 'utf8');
    const elements = spec.elements;
    const ratio = frontDarkRatio(id, elements);
    const labels = faceAreas(elements);
    report.push({
      id,
      height: heightOf(elements),
      elements: elements.length,
      groups: spec.groups.length,
      label: labels[0] ? `${labels[0].w}x${labels[0].h}` : 'none',
      frontDarkPct: +(ratio.ratio * 100).toFixed(1),
      textures: spec.textures.length
    });
  }
  console.log('id              height  els  groups  label      frontDark%  textures');
  for (const r of report) {
    console.log(
      r.id.padEnd(15),
      String(r.height).padStart(6),
      String(r.elements).padStart(4),
      String(r.groups).padStart(7),
      String(r.label).padStart(9),
      String(r.frontDarkPct).padStart(11),
      String(r.textures).padStart(9)
    );
  }
}

main().catch((e) => { console.error(e); process.exit(1); });

#!/usr/bin/env node
/*
 * Round 9 device set: builds the remaining .bbmodel files through the exact
 * same builder the dsh-blockbench `blockbench_create` tool uses
 * (node_modules/dsh-blockbench/lib/bbmodel.js -> createModel), then writes
 * JSON.stringify(model, null, 2) like the tool does. Output is byte-identical
 * to a blockbench_create call with the same spec; the DSL just keeps the specs
 * reviewable in source form.
 *
 *   node tools/build_r9_issuers.js [id ...]
 */
'use strict';

const fs = require('fs');
const path = require('path');
const { pathToFileURL } = require('url');

const ROOT = path.resolve(__dirname, '..');
const OUT = path.join(ROOT, 'assets', 'models');
const PLUGIN = path.join(
  process.env.USERPROFILE || process.env.HOME || '',
  '.dsh', 'profiles', 'web', 'node_modules', 'dsh-blockbench', 'lib', 'bbmodel.js'
);

// ---- material kit ---------------------------------------------------------
const KIT = [
  ['shell', 'veriborder:block/r9_shell', 32, 32],
  ['shell2', 'veriborder:block/r9_shell2', 32, 32],
  ['silver_flat', 'veriborder:block/r9_silver_flat', 32, 32],
  ['silver_v2', 'veriborder:block/r9_silver_v2', 32, 32],
  ['silver_dark', 'veriborder:block/r9_silver_dark_v2', 32, 32],
  ['dark', 'veriborder:block/r9_dark', 32, 32],
  ['screen', 'veriborder:block/r9_screen', 32, 32],
  ['led', 'veriborder:block/r9_led_green', 32, 32],
  ['glass', 'veriborder:block/r9_glass', 32, 32],
];

function textures(extra = []) {
  const t = KIT.map(([name, p, w, h]) => ({ name, path: p, width: w, height: h }));
  for (const e of extra) t.push(e);
  t.push({ name: 'label', path: '', width: 64, height: 32 });
  return t;
}

const FACES = ['north', 'east', 'south', 'west', 'up', 'down'];

/** every face one material */
function all(matName) {
  const f = {};
  for (const f2 of FACES) f[f2] = { texture: matName };
  return f;
}
/** only the listed faces, rest stay on the default (shell) texture */
function some(map) {
  const f = {};
  for (const k of Object.keys(map)) f[k] = { texture: map[k] };
  return f;
}
/** north + the four visible sides, no down face */
function shellBody(northMat = 'shell', sideMat = 'shell2') {
  return some({ north: northMat, east: sideMat, south: sideMat, west: sideMat, up: sideMat });
}

function el(name, group, from, to, faces) {
  return { name, group, from, to, faces: typeof faces === 'string' ? all(faces) : faces };
}

/*
 * Recessed screen kit: four silver bezel bars in front, a dark shadow ring set
 * back inside the frame, then the screen face a further step back.
 *   zf      = housing front plane
 *   [x1,x2] / [y1,y2] = outer bezel rectangle
 *   bar     = bezel bar width
 */
function screenKit(prefix, zf, x1, y1, x2, y2, bar = 0.26) {
  const zB = zf - 0.40;   // bezel front (most forward)
  const zBack = zf + 0.08;
  const zR = zf - 0.22;   // shadow ring front
  const zS = zf - 0.28;   // screen front (0.12 behind the bezel front)
  const inset = 0.20;
  return [
    el(prefix + '_bezel_top', 'screen', [x1, y2 - bar, zB], [x2, y2, zBack],
      some({ north: 'silver_v2', east: 'silver_v2', west: 'silver_v2', up: 'silver_v2' })),
    el(prefix + '_bezel_bottom', 'screen', [x1, y1, zB], [x2, y1 + bar, zBack],
      some({ north: 'silver_v2', east: 'silver_v2', west: 'silver_v2', down: 'silver_v2' })),
    el(prefix + '_bezel_left', 'screen', [x1, y1 + bar, zB], [x1 + bar, y2 - bar, zBack],
      some({ north: 'silver_v2', west: 'silver_v2', south: 'silver_v2' })),
    el(prefix + '_bezel_right', 'screen', [x2 - bar, y1 + bar, zB], [x2, y2 - bar, zBack],
      some({ north: 'silver_v2', east: 'silver_v2', south: 'silver_v2' })),
    el(prefix + '_shadow_ring', 'screen', [x1 + bar, y1 + bar, zR], [x2 - bar, y2 - bar, zf + 0.04], all('dark')),
    el(prefix + '_panel', 'screen', [x1 + bar + inset, y1 + bar + inset, zS],
      [x2 - bar - inset, y2 - bar - inset, zf + 0.02], all('screen')),
  ];
}

// ---- visa issuer ----------------------------------------------------------
// Low wide counter: sticker/vignette exit on the left, embedded screen sitting
// right of centre, a small brass-painted plate on the right edge, document
// output tray at the bottom.
const visaIssuer = {
  name: 'visa_issuer',
  model_format: 'java_block',
  format_version: '4.10',
  textures: textures([{ name: 'accent', path: '', width: 32, height: 32 }]),
  elements: [
    el('plinth', 'body', [0.6, 0, 0.6], [15.4, 1.0, 15.4], 'silver_dark'),
    el('cabinet', 'body', [0.9, 1.0, 0.9], [15.1, 6.6, 15.1], shellBody()),
    el('paint_panel', 'body', [13.6, 2.2, 0.82], [14.9, 6.2, 0.94], 'accent'),
    el('label_plate', 'body', [2.4, 1.95, 0.8], [10.4, 5.15, 0.94],
      some({ north: 'label', east: 'silver_flat', south: 'silver_flat', west: 'silver_flat', up: 'silver_flat' })),
    el('sticker_frame', 'slots', [2.45, 5.25, 0.84], [7.55, 6.0, 0.96], 'silver_v2'),
    el('sticker_slot', 'slots', [2.6, 5.4, 0.9], [7.4, 5.85, 1.02], 'dark'),
    el('sticker', 'slots', [2.8, 5.5, 0.68], [7.2, 5.78, 0.94],
      some({ north: 'shell', east: 'shell2', south: 'shell2', west: 'shell2', up: 'shell' })),
    el('counter_lip', 'counter', [0.75, 6.6, 0.75], [15.25, 7.1, 15.25], 'silver_flat'),
    el('counter_top', 'counter', [0.8, 7.1, 0.8], [15.2, 7.7, 15.2],
      some({ north: 'shell', east: 'shell2', south: 'shell2', west: 'shell2', up: 'silver_v2' })),
    el('screen_housing', 'screen', [3.2, 7.7, 3.0], [13.0, 12.6, 10.4], shellBody()),
    el('reader_pod', 'slots', [1.2, 7.7, 1.5], [3.0, 8.5, 5.2],
      some({ north: 'silver_flat', east: 'silver_flat', south: 'silver_flat', west: 'silver_flat', up: 'dark' })),
    el('led_status', 'screen', [12.6, 8.6, 2.96], [13.0, 9.0, 3.04],
      some({ north: 'led', east: 'led', up: 'led' })),
    ...screenKit('screen', 3.0, 3.7, 8.15, 12.5, 12.4),
    el('top_cap', 'body', [3.0, 12.6, 2.8], [13.2, 13.3, 10.6], 'shell2'),
    el('top_trim', 'trim', [3.15, 13.3, 2.95], [13.05, 13.55, 10.45], 'silver_flat'),
    el('doc_tray', 'counter', [3.0, 1.2, 0.5], [12.0, 1.75, 0.9], 'silver_flat'),
    el('doc', 'counter', [3.3, 1.75, 0.6], [11.7, 1.9, 0.85], 'shell'),
  ],
};

// ---- endorsement machine --------------------------------------------------
// Press table: work platform with the document clamped on it, cantilever arm
// and ram above, two guide rods, small control display on the post.
const endorsement = {
  name: 'endorsement_machine',
  model_format: 'java_block',
  format_version: '4.10',
  textures: textures(),
  elements: [
    el('plinth', 'body', [2.0, 0, 2.0], [14.0, 1.1, 14.0], 'silver_dark'),
    el('table', 'body', [1.7, 1.1, 1.7], [14.3, 5.4, 14.3],
      some({ north: 'shell', east: 'shell2', south: 'shell2', west: 'shell2', up: 'silver_flat' })),
    el('label_plate', 'body', [2.6, 1.95, 1.62], [10.6, 5.15, 1.74],
      some({ north: 'label', east: 'silver_flat', south: 'silver_flat', west: 'silver_flat', up: 'silver_flat' })),
    el('table_top', 'counter', [1.5, 5.4, 1.5], [14.5, 5.7, 14.5], 'silver_flat'),
    el('platform', 'counter', [3.2, 5.7, 3.2], [12.8, 6.05, 10.8], 'dark'),
    el('document', 'counter', [5.0, 6.05, 3.9], [11.0, 6.27, 9.9],
      some({ north: 'shell', east: 'shell2', south: 'shell2', west: 'shell2', up: 'shell' })),
    el('doc_rule', 'counter', [5.6, 6.27, 5.0], [10.4, 6.32, 5.5], 'dark'),
    // gantry: two guide rods rise behind the platen into the overhead arm
    el('rod_l', 'press', [6.8, 5.7, 10.4], [7.2, 10.4, 11.0], 'silver_flat'),
    el('rod_r', 'press', [10.8, 5.7, 10.4], [11.2, 10.4, 11.0], 'silver_flat'),
    el('head_block', 'press', [6.6, 7.6, 6.8], [11.4, 10.4, 10.4], 'shell2'),
    el('head_ram', 'press', [7.0, 6.9, 7.2], [11.0, 7.6, 10.0], 'silver_v2'),
    el('head_plate', 'press', [7.2, 6.55, 7.4], [10.8, 6.9, 9.8], 'silver_flat'),
    el('arm', 'press', [6.6, 10.4, 10.2], [14.2, 12.4, 14.2], 'silver_flat'),
    el('post', 'press', [11.6, 5.7, 11.6], [14.2, 12.6, 14.2], 'silver_v2'),
    // control display on the post front, below the arm
    ...screenKit('ctrl', 11.6, 12.0, 6.6, 14.0, 9.2, 0.22),
    el('led_status', 'press', [13.9, 9.4, 11.5], [14.1, 9.7, 11.62],
      some({ north: 'led', east: 'led', up: 'led' })),
    el('out_tray', 'counter', [3.2, 0.9, 0.7], [11.0, 1.7, 1.8], 'silver_flat'),
    el('out_doc', 'counter', [3.5, 1.7, 0.9], [10.7, 1.82, 1.6], 'shell'),
    el('top_cap', 'press', [11.4, 12.6, 11.4], [14.4, 13.2, 14.4], 'shell2'),
  ],
};

// ---- self-service kiosks (two blocks tall) --------------------------------
const selfVisa = {
  name: 'self_visa',
  model_format: 'java_block',
  format_version: '4.10',
  textures: textures(),
  elements: [
    el('plinth', 'body', [2.6, 0, 2.6], [13.4, 1.2, 13.4], 'silver_dark'),
    el('column', 'body', [3, 1.2, 3], [13, 27.0, 13], shellBody()),
    el('seam_a', 'trim', [3.05, 7.6, 2.94], [12.95, 7.78, 3.02], 'dark'),
    el('seam_b', 'trim', [3.05, 17.6, 2.94], [12.95, 17.78, 3.02], 'dark'),
    el('base_grill', 'trim', [3.6, 1.5, 2.94], [12.4, 2.1, 3.02], 'dark'),
    el('label_plate', 'body', [4.0, 3.0, 2.92], [12.0, 6.2, 3.0],
      some({ north: 'label', east: 'silver_flat', south: 'silver_flat', west: 'silver_flat', up: 'silver_flat' })),
    el('slot_surround', 'slots', [4.5, 8.4, 2.90], [6.3, 12.2, 3.02], 'silver_v2'),
    el('slot_mouth', 'slots', [4.75, 8.65, 2.97], [6.05, 11.95, 3.09], 'dark'),
    el('scan_frame', 'slots', [8.2, 8.4, 2.86], [11.8, 11.8, 2.98], 'silver_v2'),
    el('scan_glass', 'slots', [8.45, 8.65, 2.78], [11.55, 11.55, 2.92], 'glass'),
    el('led_status', 'screen', [4.4, 12.6, 2.92], [4.8, 13.0, 3.0],
      some({ north: 'led', east: 'led', up: 'led' })),
    ...screenKit('screen', 3.0, 3.9, 13.8, 12.1, 23.6),
    el('trim_l', 'trim', [2.92, 1.2, 2.86], [3.34, 27.0, 3.0], 'silver_flat'),
    el('trim_r', 'trim', [12.66, 1.2, 2.86], [13.08, 27.0, 3.0], 'silver_flat'),
    el('top_cap', 'body', [2.8, 27.0, 2.8], [13.2, 28.9, 13.2], 'shell2'),
    el('top_trim', 'trim', [2.95, 28.9, 2.95], [13.05, 29.4, 13.05], 'silver_flat'),
  ],
};

const selfEndorsement = {
  name: 'self_endorsement',
  model_format: 'java_block',
  format_version: '4.10',
  textures: textures(),
  elements: [
    el('plinth', 'body', [2.6, 0, 2.6], [13.4, 1.2, 13.4], 'silver_dark'),
    el('column', 'body', [3, 1.2, 3], [13, 24.0, 13], shellBody()),
    el('seam_a', 'trim', [3.05, 7.0, 2.94], [12.95, 7.18, 3.02], 'dark'),
    el('base_grill', 'trim', [3.6, 1.5, 2.94], [12.4, 2.1, 3.02], 'dark'),
    el('label_plate', 'body', [4.0, 2.8, 2.92], [12.0, 6.0, 3.0],
      some({ north: 'label', east: 'silver_flat', south: 'silver_flat', west: 'silver_flat', up: 'silver_flat' })),
    el('slot_surround', 'slots', [4.4, 7.9, 2.90], [6.2, 11.4, 3.02], 'silver_v2'),
    el('slot_mouth', 'slots', [4.65, 8.15, 2.97], [5.95, 11.15, 3.09], 'dark'),
    el('led_status', 'screen', [4.3, 11.7, 2.92], [4.7, 12.1, 3.0],
      some({ north: 'led', east: 'led', up: 'led' })),
    ...screenKit('screen', 3.0, 3.8, 12.6, 12.2, 21.6),
    el('trim_l', 'trim', [2.92, 1.2, 2.86], [3.34, 24.0, 3.0], 'silver_flat'),
    el('trim_r', 'trim', [12.66, 1.2, 2.86], [13.08, 24.0, 3.0], 'silver_flat'),
    el('top_deck', 'press', [3.2, 24.0, 3.2], [12.8, 24.35, 12.8], 'silver_flat'),
    el('deck_recess', 'press', [3.8, 24.35, 3.8], [12.2, 24.45, 12.2], 'dark'),
    el('stamp_doc', 'press', [4.6, 24.45, 5.2], [11.4, 24.55, 10.8], 'shell'),
    el('post_l', 'press', [3.0, 24.45, 3.4], [3.6, 26.4, 4.0], 'silver_v2'),
    el('post_r', 'press', [12.4, 24.45, 12.0], [13.0, 26.4, 12.6], 'silver_v2'),
    el('stamp_head', 'press', [3.4, 26.4, 3.4], [12.6, 27.7, 12.6], 'shell2'),
    el('stamp_ram', 'press', [5.6, 25.15, 5.8], [10.4, 26.4, 10.2], 'silver_v2'),
    el('stamp_plate', 'press', [5.8, 24.85, 6.0], [10.2, 25.15, 10.0], 'silver_flat'),
    el('out_tray', 'counter', [4.0, 1.2, 2.2], [12.0, 1.7, 3.0], 'silver_flat'),
    el('out_doc', 'counter', [4.3, 1.7, 2.35], [11.7, 1.8, 2.9], 'shell'),
  ],
};

const MODELS = [visaIssuer, endorsement, selfVisa, selfEndorsement];

(async () => {
  const { createModel } = await import(pathToFileURL(PLUGIN).href);
  const want = process.argv.slice(2);
  for (const spec of MODELS) {
    if (want.length && !want.includes(spec.name)) continue;
    const model = createModel(spec);
    const file = path.join(OUT, spec.name + '.bbmodel');
    fs.writeFileSync(file, JSON.stringify(model, null, 2), 'utf8');
    console.log(`${spec.name}: ${model.elements.length} elements -> ${path.relative(ROOT, file)}`);
  }
})();

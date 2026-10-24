#!/usr/bin/env node
// R9 v3 gate: realistic immigration e-gate. Matte off-white shell, brushed
// silver trim, recessed embedded screens, flush top (no lid/overhang), no
// header beam, no neon. Two cabinets + animated acrylic door (BER).
const fs = require('fs');
const path = require('path');

const TEX = [
  ['shell', 'veriborder:block/r9_shell'],
  ['shell2', 'veriborder:block/r9_shell2'],
  ['silver', 'veriborder:block/r9_silver_v2'],
  ['silver_flat', 'veriborder:block/r9_silver_flat'],
  ['silver_dark', 'veriborder:block/r9_silver_dark_v2'],
  ['glass', 'veriborder:block/r9_glass'],
  ['dark', 'veriborder:block/r9_dark'],
  ['screen', 'veriborder:block/r9_screen_v3'],
  ['led_green', 'veriborder:block/r9_led_green'],
  ['led_red', 'veriborder:block/r9_led_red'],
  ['label_passport', 'veriborder:block/gate_passport_label'],
  ['label_face', 'veriborder:block/gate_face_label'],
  ['label_exit', 'veriborder:block/gate_exit_label']
];
const IDX = {};
TEX.forEach(([n], i) => { IDX[n] = i; });

function faces(slot, overrides) {
  const f = {};
  for (const face of ['north', 'south', 'east', 'west', 'up', 'down']) {
    const s = (overrides && overrides[face]) || slot;
    f[face] = { uv: [0, 0, 16, 16], texture: String(IDX[s]) };
  }
  return f;
}

let uid = 0;
function el(name, from, to, slot, overrides) {
  uid += 1;
  return {
    name, box_uv: false, rescale: false, locked: false, render_order: 'default',
    allow_mirror_modeling: true, from, to, autouv: 0, color: 0,
    origin: [8, 0, 8], faces: faces(slot, overrides),
    type: 'cube', uuid: `00000000-0000-4000-8000-${String(uid).padStart(12, '0')}`
  };
}

function build(exit) {
  const label = exit ? 'label_exit' : 'label_passport';
  const els = [];
  for (const [x0, x1] of [[0, 2.9], [13.1, 16]]) {
    els.push(el('shell', [x0, 1, 1], [x1, 24, 15], 'shell'));
    els.push(el('front_panel', [x0, 1.2, 0.55], [x1, 23.8, 1.05], 'shell2'));
    els.push(el('base_plinth', [x0, 0, 0.5], [x1, 1, 15.5], 'silver'));
    els.push(el('top_step1', [x0, 23.4, 0.5], [x1, 23.75, 15.5], 'silver_flat'));
    els.push(el('top_step2', [x0 + 0.3, 23.75, 0.8], [x1 - 0.3, 23.95, 15.2], 'shell'));
    els.push(el('top_step3', [x0 + 0.55, 23.95, 1.05], [x1 - 0.55, 24.0, 14.95], 'silver_flat'));
    if (x0 > 1) els.push(el('top_pod', [x0 + 0.5, 24.0, 6.0], [x1 - 0.5, 24.4, 10.0], 'shell2'));
    els.push(el('front_bevel', [x0, 1, 0.45], [x1, 1.4, 1.0], 'silver_flat'));
    els.push(el('seam_mid', [x0, 11.95, 0.5], [x1, 12.08, 1.05], 'dark'));
    els.push(el('seam_low', [x0, 17.95, 0.5], [x1, 18.08, 1.05], 'dark'));
    els.push(el('back_bevel', [x0, 1, 15.0], [x1, 1.4, 15.55], 'silver_flat'));
    // front corner trims: thin brushed strips that read as rolled edges
    els.push(el('corner_l', [x0, 1.4, 0.95], [x0 + 0.22, 23.86, 1.12], 'silver_flat'));
    els.push(el('corner_r', [x1 - 0.22, 1.4, 0.95], [x1, 23.86, 1.12], 'silver_flat'));
  }
  els.push(el('edge_w', [-0.03, 1.4, 1.0], [0.12, 23.86, 15.0], 'silver_dark'));
  els.push(el('edge_e', [15.88, 1.4, 1.0], [16.03, 23.86, 15.0], 'silver_dark'));

  // right cabinet: recessed passport reader + screen
  els.push(el('reader_bezel', [13.25, 9.2, 0.15], [15.75, 12.2, 0.55], 'silver'));
  els.push(el('reader_shadow', [13.45, 9.45, 0.28], [15.55, 10.25, 0.36], 'dark'));
  els.push(el('reader_slot', [13.55, 10.4, 0.32], [15.45, 10.95, 0.42], 'dark'));
  els.push(el('reader_screen_bezel', [13.35, 13.0, 0.15], [15.65, 17.2, 0.55], 'silver_dark'));
  els.push(el('reader_screen_shadow', [13.45, 13.1, 0.28], [15.55, 17.1, 0.36], 'dark'));
  els.push(el('reader_screen', [13.5, 13.15, 0.34], [15.5, 17.05, 0.42], 'screen'));
  els.push(el('reader_led_bezel', [13.15, 17.85, 0.18], [13.95, 18.75, 0.3], 'silver_flat'));
  els.push(el('reader_led', [13.25, 17.95, 0.28], [13.85, 18.65, 0.4], exit ? 'led_red' : 'led_green'));

  // left cabinet: recessed face camera + screen
  els.push(el('camera_bezel', [0.5, 16.4, 0.15], [2.5, 19.2, 0.55], 'silver'));
  els.push(el('camera_lens', [0.95, 17.0, 0.3], [2.05, 18.6, 0.42], 'dark'));
  els.push(el('camera_glass', [1.15, 17.2, 0.34], [1.85, 18.4, 0.38], 'glass'));
  els.push(el('face_screen_bezel', [0.35, 12.4, 0.15], [2.65, 16.0, 0.55], 'silver_dark'));
  els.push(el('face_screen_shadow', [0.45, 12.5, 0.28], [2.55, 15.9, 0.36], 'dark'));
  els.push(el('face_screen', [0.5, 12.55, 0.34], [2.5, 15.85, 0.42], 'screen'));
  els.push(el('face_led_bezel', [2.05, 17.85, 0.18], [2.85, 18.75, 0.3], 'silver_flat'));
  els.push(el('face_led', [2.15, 17.95, 0.28], [2.75, 18.65, 0.4], exit ? 'led_green' : 'led_red'));

  // readable English on the outer side faces (12 px long x 4 px tall)
  els.push(el('label_face_plate', [-0.06, 7.5, 3.0], [0.04, 11.5, 13.0], 'silver_dark',
    { west: 'label_face' }));
  els.push(el('label_passport_plate', [15.96, 7.5, 3.0], [16.06, 11.5, 13.0], 'silver_dark',
    { east: label }));

  if (exit) {
    els.push(el('exit_badge', [13.2, 2.2, 0.9], [15.8, 2.8, 1.1], 'silver_dark'));
  }
  return els;
}

function emit(id, exit) {
  const model = {
    meta: { format_version: '4.10', model_format: 'java_block', box_uv: false },
    name: id,
    model_identifier: '',
    visible_box: [1, 1, 0],
    variable_placeholders: '',
    variable_placeholder_buttons: [],
    timeline_setups: [],
    unhandled_root_fields: {},
    resolution: { width: 64, height: 64 },
    elements: build(exit),
    groups: [],
    outliner: [],
    textures: TEX.map(([name, p], i) => ({
      path: p, name, folder: '', namespace: '', id: String(i), group: '',
      width: 64, height: 32, uv_width: 64, uv_height: 32, particle: false,
      layers_enabled: false, render_mode: 'default', visible: true, internal: false,
      saved: true, uuid: `00000000-0000-4000-8000-${String(900 + i).padStart(12, '0')}`
    }))
  };
  model.outliner = model.elements.map(e => e.uuid);
  const p = path.join('assets', 'models', id + '.bbmodel');
  fs.mkdirSync(path.dirname(p), { recursive: true });
  fs.writeFileSync(p, JSON.stringify(model, null, 2) + '\n', 'utf8');
  console.log(id, model.elements.length, 'elements ->', p);
}

emit('egate', false);
emit('exit_gate', true);

#!/usr/bin/env node
// R9 v7 gate: shorter realistic immigration e-gate (21 px = 1.31 blocks, still
// above the 1.25-block jump height), flat top with NO cover/beam, matte white +
// brushed silver + clear acrylic, embedded reader/camera/screens.
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
  ['screen', 'veriborder:block/r9_screen_ui'],
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
    els.push(el('shell', [x0, 0.9, 1], [x1, 20.85, 15], 'shell'));
    els.push(el('front_panel', [x0, 1.1, 0.55], [x1, 20.7, 1.05], 'shell2'));
    els.push(el('base_plinth', [x0, 0, 0.5], [x1, 0.9, 15.5], 'silver'));
    els.push(el('top_edge', [x0, 20.85, 0.55], [x1, 21.0, 15.45], 'silver_flat'));
    els.push(el('front_bevel', [x0, 0.9, 0.45], [x1, 1.25, 1.0], 'silver_flat'));
    els.push(el('back_bevel', [x0, 0.9, 15.0], [x1, 1.25, 15.55], 'silver_flat'));
    els.push(el('seam_mid', [x0, 10.45, 0.5], [x1, 10.58, 1.05], 'dark'));
    els.push(el('seam_low', [x0, 15.45, 0.5], [x1, 15.58, 1.05], 'dark'));
  }
  els.push(el('edge_w', [-0.03, 1.3, 1.0], [0.12, 20.7, 15.0], 'silver_dark'));
  els.push(el('edge_e', [15.88, 1.3, 1.0], [16.03, 20.7, 15.0], 'silver_dark'));

  // right cabinet: passport reader frame + slot, small screen, LED
  els.push(el('reader_frame_t', [13.2, 10.3, 0.10], [15.8, 10.65, 0.55], 'silver'));
  els.push(el('reader_frame_b', [13.2, 7.35, 0.10], [15.8, 7.7, 0.55], 'silver'));
  els.push(el('reader_frame_l', [13.2, 7.35, 0.10], [13.55, 10.65, 0.55], 'silver'));
  els.push(el('reader_frame_r', [15.45, 7.35, 0.10], [15.8, 10.65, 0.55], 'silver'));
  els.push(el('reader_slot', [13.7, 8.75, 0.06], [15.3, 9.3, 0.16], 'dark'));
  els.push(el('reader_slot_lip', [13.6, 8.6, 0.04], [15.4, 9.45, 0.08], 'silver_flat'));

  els.push(el('reader_scr_frame_t', [13.25, 14.85, 0.10], [15.75, 15.2, 0.55], 'silver_dark'));
  els.push(el('reader_scr_frame_b', [13.25, 11.25, 0.10], [15.75, 11.6, 0.55], 'silver_dark'));
  els.push(el('reader_scr_frame_l', [13.25, 11.25, 0.10], [13.6, 15.2, 0.55], 'silver_dark'));
  els.push(el('reader_scr_frame_r', [15.4, 11.25, 0.10], [15.75, 15.2, 0.55], 'silver_dark'));
  els.push(el('reader_screen', [13.6, 11.6, 0.26], [15.4, 14.85, 0.36], 'screen'));

  els.push(el('reader_led_ring', [13.15, 15.7, 0.06], [13.95, 16.6, 0.14], 'silver_flat'));
  els.push(el('reader_led', [13.25, 15.8, 0.04], [13.85, 16.5, 0.1], exit ? 'led_red' : 'led_green'));

  // left cabinet: face camera frame + lens, small screen, LED
  els.push(el('camera_frame_t', [0.45, 15.3, 0.10], [2.55, 15.65, 0.55], 'silver'));
  els.push(el('camera_frame_b', [0.45, 12.85, 0.10], [2.55, 13.2, 0.55], 'silver'));
  els.push(el('camera_frame_l', [0.45, 12.85, 0.10], [0.8, 15.65, 0.55], 'silver'));
  els.push(el('camera_frame_r', [2.2, 12.85, 0.10], [2.55, 15.65, 0.55], 'silver'));
  els.push(el('camera_lens', [1.0, 13.4, 0.16], [2.0, 15.0, 0.3], 'dark'));
  els.push(el('camera_glass', [1.2, 13.6, 0.12], [1.8, 14.8, 0.17], 'glass'));

  els.push(el('face_scr_frame_t', [0.3, 11.55, 0.10], [2.7, 11.9, 0.55], 'silver_dark'));
  els.push(el('face_scr_frame_b', [0.3, 8.25, 0.10], [2.7, 8.6, 0.55], 'silver_dark'));
  els.push(el('face_scr_frame_l', [0.3, 8.25, 0.10], [0.65, 11.9, 0.55], 'silver_dark'));
  els.push(el('face_scr_frame_r', [2.35, 8.25, 0.10], [2.7, 11.9, 0.55], 'silver_dark'));
  els.push(el('face_screen', [0.65, 8.6, 0.26], [2.35, 11.55, 0.36], 'screen'));

  els.push(el('face_led_ring', [2.05, 15.7, 0.06], [2.85, 16.6, 0.14], 'silver_flat'));
  els.push(el('face_led', [2.15, 15.8, 0.04], [2.75, 16.5, 0.1], exit ? 'led_green' : 'led_red'));

  // readable English on the outer side faces
  els.push(el('label_face_plate', [-0.06, 5.5, 3.0], [0.04, 9.3, 13.0], 'silver_dark',
    { west: 'label_face' }));
  els.push(el('label_passport_plate', [15.96, 5.5, 3.0], [16.06, 9.3, 13.0], 'silver_dark',
    { east: label }));

  if (exit) {
    els.push(el('exit_badge', [13.2, 1.8, 0.9], [15.8, 2.4, 1.1], 'silver_dark'));
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
      path: '', resource: p, name, folder: '', namespace: '', id: String(i), group: '',
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

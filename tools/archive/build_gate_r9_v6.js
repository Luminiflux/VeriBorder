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

  // right cabinet: passport reader (frame around an open slot) + screen (frame + recessed glass)
  els.push(el('reader_frame_t', [13.2, 12.0, 0.10], [15.8, 12.35, 0.55], 'silver'));
  els.push(el('reader_frame_b', [13.2, 9.05, 0.10], [15.8, 9.4, 0.55], 'silver'));
  els.push(el('reader_frame_l', [13.2, 9.05, 0.10], [13.55, 12.35, 0.55], 'silver'));
  els.push(el('reader_frame_r', [15.45, 9.05, 0.10], [15.8, 12.35, 0.55], 'silver'));
  els.push(el('reader_slot', [13.7, 10.35, 0.06], [15.3, 10.95, 0.16], 'dark'));
  els.push(el('reader_slot_lip', [13.6, 10.2, 0.04], [15.4, 11.1, 0.08], 'silver_flat'));

  els.push(el('reader_scr_frame_t', [13.25, 16.95, 0.10], [15.75, 17.3, 0.55], 'silver_dark'));
  els.push(el('reader_scr_frame_b', [13.25, 12.95, 0.10], [15.75, 13.3, 0.55], 'silver_dark'));
  els.push(el('reader_scr_frame_l', [13.25, 12.95, 0.10], [13.6, 17.3, 0.55], 'silver_dark'));
  els.push(el('reader_scr_frame_r', [15.4, 12.95, 0.10], [15.75, 17.3, 0.55], 'silver_dark'));
  els.push(el('reader_screen', [13.6, 13.3, 0.26], [15.4, 16.95, 0.36], 'screen'));

  els.push(el('reader_led_ring', [13.15, 17.75, 0.06], [13.95, 18.75, 0.14], 'silver_flat'));
  els.push(el('reader_led', [13.25, 17.85, 0.04], [13.85, 18.65, 0.1], exit ? 'led_red' : 'led_green'));

  // left cabinet: face camera (frame + lens) + screen
  els.push(el('camera_frame_t', [0.45, 19.1, 0.10], [2.55, 19.45, 0.55], 'silver'));
  els.push(el('camera_frame_b', [0.45, 16.35, 0.10], [2.55, 16.7, 0.55], 'silver'));
  els.push(el('camera_frame_l', [0.45, 16.35, 0.10], [0.8, 19.45, 0.55], 'silver'));
  els.push(el('camera_frame_r', [2.2, 16.35, 0.10], [2.55, 19.45, 0.55], 'silver'));
  els.push(el('camera_lens', [1.0, 17.1, 0.16], [2.0, 18.7, 0.3], 'dark'));
  els.push(el('camera_glass', [1.2, 17.3, 0.12], [1.8, 18.5, 0.17], 'glass'));

  els.push(el('face_scr_frame_t', [0.3, 15.75, 0.10], [2.7, 16.1, 0.55], 'silver_dark'));
  els.push(el('face_scr_frame_b', [0.3, 12.35, 0.10], [2.7, 12.7, 0.55], 'silver_dark'));
  els.push(el('face_scr_frame_l', [0.3, 12.35, 0.10], [0.65, 16.1, 0.55], 'silver_dark'));
  els.push(el('face_scr_frame_r', [2.35, 12.35, 0.10], [2.7, 16.1, 0.55], 'silver_dark'));
  els.push(el('face_screen', [0.65, 12.7, 0.26], [2.35, 15.75, 0.36], 'screen'));

  els.push(el('face_led_ring', [2.05, 17.75, 0.06], [2.85, 18.75, 0.14], 'silver_flat'));
  els.push(el('face_led', [2.15, 17.85, 0.04], [2.75, 18.65, 0.1], exit ? 'led_green' : 'led_red'));

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

#!/usr/bin/env node
// R9 v8: gate rebuilt from the original Blender scene (assets/blender/gate/gate.blend):
// white pillars, dark base trim, cyan seam/inner LED, front screen, lane floor strip.
// No top cover (user request). 18 px tall, 16 px wide, 10 px lane. Door is skipped
// in this pass.
const fs = require('fs');
const path = require('path');

const TEX = [
  ['shell', 'veriborder:block/r9_shell'],
  ['shell2', 'veriborder:block/r9_shell2'],
  ['silver', 'veriborder:block/r9_silver_v2'],
  ['silver_flat', 'veriborder:block/r9_silver_flat'],
  ['silver_dark', 'veriborder:block/r9_silver_dark_v2'],
  ['dark', 'veriborder:block/r9_dark'],
  ['cyan', 'veriborder:block/r9_led_cyan'],
  ['screen', 'veriborder:block/r9_screen_ui'],
  ['gate_screen', 'veriborder:block/gate_screen_arrow'],
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
  // pillars: 3 px wide, 14 px deep, 17 px tall on a 1 px dark base
  for (const [x0, x1] of [[0, 2.9], [13.1, 16]]) {
    els.push(el('pillar', [x0, 0, 1], [x1, 17, 15], 'shell'));
    els.push(el('front_panel', [x0, 1, 0.55], [x1, 16.8, 1.05], 'shell2'));
    // cyan inner LED strip on the lane-facing side
    els.push(el('cyan_inner', [x0 === 0 ? 2.84 : 13.1, 1.2, 1.2],
      [x0 === 0 ? 2.9 : 13.16, 16.8, 14.8], 'cyan'));
    // cyan seam across the top of the pillar
    els.push(el('cyan_seam', [x0, 16.8, 1], [x1, 17.0, 15], 'cyan'));
    // silver cap edge (thin, flush - no cover)
    els.push(el('cap_edge', [x0, 17.0, 1], [x1, 17.15, 15], 'silver_flat'));
  }
  // lane floor strip (accent rail)
  // front screens (bezel + recessed UI)
  for (const [x0, x1] of [[0.35, 2.55], [13.45, 15.65]]) {
    els.push(el('scr_frame_t', [x0, 12.3, 0.15], [x1, 12.6, 0.55], 'dark'));
    els.push(el('scr_frame_b', [x0, 6.4, 0.15], [x1, 6.7, 0.55], 'dark'));
    els.push(el('scr_frame_l', [x0, 6.4, 0.15], [x0 + 0.3, 12.6, 0.55], 'dark'));
    els.push(el('scr_frame_r', [x1 - 0.3, 6.4, 0.15], [x1, 12.6, 0.55], 'dark'));
    els.push(el('screen', [x0 + 0.3, 6.7, 0.3], [x1 - 0.3, 12.3, 0.4], 'gate_screen'));
  }
  // passport reader slot on the right pillar
  els.push(el('reader_slot', [13.6, 3.2, 0.1], [15.4, 3.8, 0.3], 'dark'));
  els.push(el('reader_lip', [13.5, 3.05, 0.06], [15.5, 3.95, 0.12], 'silver_flat'));
  // status LEDs
  els.push(el('led_left', [0.6, 13.2, 0.1], [1.2, 13.8, 0.3], exit ? 'led_green' : 'led_red'));
  els.push(el('led_right', [14.8, 13.2, 0.1], [15.4, 13.8, 0.3], exit ? 'led_red' : 'led_green'));
  // readable English on the outer side faces
  if (exit) {
    els.push(el('exit_badge', [13.2, 1.3, 1.0], [15.8, 1.9, 2.0], 'silver_dark'));
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

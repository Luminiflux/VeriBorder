// VeriBorder 26.2.1 — e-gate block model, MTR-style "one block = one cabinet".
//
// Geometry contract (this file is the single source of truth, the Java side reads
// the same numbers through GateCycle):
//
//   local x 0..8    the cabinet (solid, collision)
//   local x 8..16   this gate's half of the lane (wings are drawn by the BER)
//   local z 0..16   travel direction, 0 = approach side
//
// Two gates placed next to each other, one rotated 180 degrees, put their cabinets
// on the outside (x 0..8 and 24..32) and leave a full 16 px = 1.0 block lane
// between them, which is what makes the pair walkable. Cabinets are flush with the
// block boundary on x = 0, so a row of gates reads as one continuous pillar.
//
// The wings are NOT part of the block model: they animate, so EGateRenderer draws
// them. Everything here is static.

const fs = require('fs');
const path = require('path');

const ROOT = path.resolve(__dirname, '..');
const OUT = path.join(ROOT, 'src/main/resources/assets/veriborder/models/block');

const CABINET_W = 8.0;   // x 0..8
const HEIGHT = 21.4;
const COUNTER_Y = 12.8;

const PALETTES = {
  egate: {
    body: 'egate_body', body2: 'egate_body2', trim: 'egate_trim', top: 'egate_top',
    screen: 'egate_screen', led: 'egate_light_green', accent: 'egate_accent',
    arrow: 'egate_arrow', light: 'egate_light_green', detail: 'egate_detail'
  },
  exit_gate: {
    body: 'exit_gate_body', body2: 'exit_gate_body2', trim: 'exit_gate_trim', top: 'exit_gate_top',
    screen: 'exit_gate_screen', led: 'exit_gate_light_red', accent: 'exit_gate_accent',
    arrow: 'exit_gate_arrow', light: 'exit_gate_light_red', detail: 'exit_gate_detail'
  },
  dual_gate: {
    body: 'dual_gate_body', body2: 'dual_gate_body2', trim: 'dual_gate_trim', top: 'dual_gate_top',
    screen: 'dual_gate_screen', led: 'dual_gate_light_green', accent: 'dual_gate_accent',
    arrow: 'dual_gate_arrow', light: 'dual_gate_light_green', detail: 'dual_gate_detail'
  }
};

function box(from, to, faces) {
  const out = { from, to };
  if (faces) out.faces = faces;
  return out;
}

function allFaces(key) {
  const face = { texture: '#' + key };
  return { north: face, east: { ...face }, south: { ...face }, west: { ...face }, up: { ...face }, down: { ...face } };
}

function build(palette) {
  const elements = [];
  const add = (from, to, key, faces) => elements.push(box(from, to, faces || allFaces(key)));

  // Plinth: recessed, reads as the mounting foot of the cabinet.
  add([0.2, 0, 0.2], [7.6, 1.2, 15.8], 'trim');
  add([0.0, 1.2, 0.0], [CABINET_W, 2.0, 16.0], 'trim');

  // Main body, flush to x = 0 so neighbouring cabinets merge without a seam.
  add([0.0, 2.0, 0.6], [7.4, COUNTER_Y, 15.4], 'body');
  // Rear service panel (same x=0 plane, keeps the merged face flat).
  add([0.0, 3.2, 1.4], [0.35, COUNTER_Y - 1.6, 14.6], 'body2');
  // Front lower kick panel with the credential slot.
  add([0.4, 3.0, 14.2], [7.2, COUNTER_Y - 3.2, 15.9], 'body2');
  add([1.0, 4.2, 15.2], [6.6, 5.4, 16.0], 'accent');
  add([1.2, 6.0, 15.2], [6.4, 6.8, 15.95], 'detail');

  // Counter: the surface the officer looks down on, overhanging the lane side.
  add([0.0, COUNTER_Y, 0.0], [7.9, COUNTER_Y + 0.8, 16.0], 'trim');
  add([0.2, COUNTER_Y + 0.8, 0.2], [7.6, COUNTER_Y + 1.0, 15.8], 'top');

  // Screen above the counter, tilted 22.5 degrees towards the lane so the traveller
  // can read it while standing in the lane and the officer can reach the counter.
  const screenBase = 14.1;
  const screenTop = 18.6;
  const tilt = 22.5;
  elements.push({
    from: [6.5, screenBase, 4.2],
    to: [6.9, screenTop, 11.8],
    rotation: { origin: [6.7, screenBase, 8.0], axis: 'z', angle: -tilt, rescale: false },
    faces: allFaces('screen')
  });
  // Screen bezel behind the panel.
  elements.push({
    from: [6.2, screenBase - 0.25, 3.9],
    to: [6.55, screenTop + 0.25, 12.1],
    rotation: { origin: [6.4, screenBase, 8.0], axis: 'z', angle: -tilt, rescale: false },
    faces: allFaces('trim')
  });
  // Screen mast: two thin posts from the counter into the lid.
  add([6.3, COUNTER_Y + 1.0, 4.0], [6.7, screenBase, 4.4], 'trim');
  add([6.3, COUNTER_Y + 1.0, 11.6], [6.7, screenBase, 12.0], 'trim');

  // Document reader on the counter, facing the lane.
  add([5.2, COUNTER_Y + 1.0, 6.6], [7.8, COUNTER_Y + 1.7, 9.4], 'trim');
  add([5.4, COUNTER_Y + 1.7, 6.9], [7.7, COUNTER_Y + 1.85, 9.1], 'detail');

  // Lane-side LED strip: the pass/deny indicator the traveller sees.
  add([7.95, 9.0, 3.0], [8.0, 10.2, 13.0], 'led');
  // Arrow marker on the top cover pointing along the travel direction.
  add([2.4, 20.0, 6.4], [6.0, 20.4, 9.6], 'arrow');

  // Top lid, flush to x 0..8, with a dark core seam.
  add([0.0, 19.0, 0.0], [CABINET_W, 20.0, 16.0], 'trim');
  add([0.0, 20.0, 0.0], [CABINET_W, HEIGHT, 16.0], 'top');
  add([0.0, HEIGHT, 0.0], [CABINET_W, HEIGHT + 0.2, 16.0], 'top');

  // Status light above the lid, on the lane side.
  add([6.2, HEIGHT + 0.2, 6.4], [7.8, HEIGHT + 0.55, 9.6], 'light');

  // Vent slots on the lane face.
  for (let i = 0; i < 3; i++) {
    const y = 15.2 + i * 0.9;
    add([7.4, y, 1.6], [7.55, y + 0.45, 3.2], 'detail');
  }

  return elements;
}

function modelFor(id) {
  const palette = PALETTES[id];
  const textures = {};
  for (const [key, value] of Object.entries(palette)) {
    textures[key] = 'veriborder:block/' + value;
  }
  return {
    textures,
    elements: build(palette)
  };
}

for (const id of ['egate', 'exit_gate', 'dual_gate']) {
  const model = modelFor(id);
  const file = path.join(OUT, id + '.json');
  fs.writeFileSync(file, JSON.stringify(model, null, 2) + '\n');
  const missing = [];
  for (const value of Object.values(model.textures)) {
    const name = value.replace('veriborder:block/', '');
    const png = path.join(ROOT, 'src/main/resources/assets/veriborder/textures/block/' + name + '.png');
    if (!fs.existsSync(png)) missing.push(name);
  }
  console.log(id, 'elements=' + model.elements.length, 'missingTextures=' + (missing.join(',') || 'none'));
}

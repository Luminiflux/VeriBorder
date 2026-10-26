#!/usr/bin/env node
/*
 * VeriBorder dedicated 3D item models  (26.2.1 two-block item fix)
 *
 *   node tools/generate_item_models.js            # write models/item/<id>.json
 *   node tools/generate_item_models.js --dry      # validate + report only
 *
 * Before this generator every device item model was `{ "parent": "veriborder:block/<id>",
 * "gui_light": "front" }` - i.e. the inventory icon was the full-size BLOCK model,
 * a chunky two-tier cabinet that reads badly in a 16 px slot. Since 26.4.1 each device
 * gets its own dedicated ITEM model: a small isometric machine with a front screen,
 * side detail and a stepped (chamfered) top, 20-40 elements, no parent.
 *
 * TWO-BLOCK DEVICES (26.2.1 fix)
 * ------------------------------
 * Eleven devices are TWO blocks tall in the world: DeviceBlock.TALL lists them and
 * DeviceBlock.onPlace() spawns the invisible DeviceExtensionBlock companion in the
 * block above (DeviceExtensionBlock.java). The 26.4.1 generator only ever built the
 * LOWER storey, so those eleven machines showed up in the inventory as one-block
 * desktop units - "the top half is missing". tools/lib/device_tall.js reads that
 * registry out of the Java sources; this generator then builds those devices as two
 * tiers and stacks them exactly one block apart:
 *
 *     elements = lowerTier(p)  ++  upperTier(p).map(el => shift(el, +16))
 *
 * The seam is exact: the lower tier tops out at y = 16 and the extension tier starts
 * at y = 16 after the shift, so the icon is one continuous two-storey machine instead
 * of two floating halves. Single-block devices keep the shipped archetype path
 * (byte-identical output) - only the eleven two-block devices change.
 *
 * GUI DISPLAY
 * -----------
 * A 23-27 px tall model would blow past the 16 px inventory slot at the vanilla
 * gui scale of 0.625 (that number is tuned for a 16 px block). Two-block items
 * therefore get a height-adaptive gui scale (0.625 * 16 / height) plus a vertical
 * translation that re-centres the taller box, so their visual height stays at
 * 1.0 block - the same footprint as a normal block item - while "gui_light": "front"
 * still lights the icon from the front as a device should be.
 *
 * Hard assertions (any failure exits 1, nothing is silently written):
 *   * two-block device item models: bbox height > 20 px   (upper half present),
 *   * single-block device item models: bbox height <= 16.5 px,
 *   * two-block items: the two tiers meet exactly at y = 16 (no floating gap),
 *   * gui height * gui scale <= 1.0 block (10 px at 0.625),
 *   * element bounds: x/z inside 0..16, y inside 0..32.5 (two world blocks),
 *   * 20..40 elements, axis-aligned only, unique geometry per device,
 *   * no same-facing coincident faces, no fully enclosed element,
 *   * every face texture resolves to a `#key` in the model's own texture map.
 *
 * Chassis surfaces reuse the item material set painted by
 * tools/generate_item_textures.ps1 (this generator never writes textures). The
 * screen face uses the per-device veriborder:item/<id>_screen panel.
 */
'use strict';

const fs = require('fs');
const path = require('path');
const { TALL_IDS, TALL_SET, isTall, SOURCE } = require('./lib/device_tall');

const ROOT = path.resolve(__dirname, '..');
const ASSETS = path.join(ROOT, 'src', 'main', 'resources', 'assets', 'veriborder');
const ITEM_DIR = path.join(ASSETS, 'models', 'item');
const TEX_ITEM = path.join(ASSETS, 'textures', 'item');
const OUT_MD = path.join(ROOT, 'tools', 'preview', 'item_model_heights.md');
const DRY = process.argv.includes('--dry');

// One Minecraft block, in model units. The second storey of a two-block machine
// starts exactly here.
const BLOCK_PX = 16;
const EXTENSION_Y = BLOCK_PX;
// Item-model envelope: x/z stay inside one block, y may reach two world blocks
// (+0.5 px slack for proud trim that a world model is allowed to carry too).
const MAX_ITEM_Y = 32.5;
// Vanilla gui scale for a 16 px block item, and the visual size that implies:
// 16 px * 0.625 = 10 px on screen = "one block" in the inventory.
const GUI_SCALE_BLOCK = 0.625;
const GUI_UNIT_PX = GUI_SCALE_BLOCK * BLOCK_PX;   // 10
const MIN_ELS = 20;
const MAX_ELS = 40;

// Two-block item assertions.
const TALL_MIN_HEIGHT = 20;      // strictly greater than this
const SINGLE_MAX_HEIGHT = 16.5;  // at most this

// ------------------------------------------------------------------ helpers
const FACES = ['north', 'south', 'east', 'west', 'up', 'down'];

function has(file) { return fs.existsSync(file); }

// texture key -> resource path. Every surface comes from the ITEM material set
// painted by tools/generate_item_textures.ps1 (see New-IaMaterialTile). `family`
// picks the accent plate colour used for badges, handles and stamp plates.
const FAMILY_ACCENT = {
  issuer: 'item_accent_blue',
  issuerViolet: 'item_accent_violet',
  reader: 'item_accent_teal',
  stamp: 'item_accent_amber',
  service: 'item_accent_red',
};
function textureMap(id, family) {
  const map = {
    particle: 'veriborder:item/item_shell',
    body: 'veriborder:item/item_shell',
    body2: 'veriborder:item/item_shell',
    trim: 'veriborder:item/item_trim',
    top: 'veriborder:item/item_shell',
    glass: 'veriborder:item/item_glass',
    led: 'veriborder:item/item_led',
    accent: `veriborder:item/${FAMILY_ACCENT[family] || 'item_accent_blue'}`,
    detail: 'veriborder:item/item_panel',
    paper: 'veriborder:item/item_paper',
    screen: `veriborder:item/${id}_screen`,
  };
  for (const [key, ref] of Object.entries(map)) {
    const rel = ref.split(':')[1];
    const file = path.join(TEX_ITEM, rel.slice(5) + '.png');
    if (!has(file)) throw new Error(`${id}: missing item texture ${ref} (run tools/generate_item_textures.ps1 first)`);
  }
  return map;
}
// one axis-aligned cuboid; `tex` is either a key used on all six faces or an
// object {all, north, up, ...} for per-face overrides.
function box(from, to, tex) {
  const spec = typeof tex === 'string' ? { all: tex } : tex;
  const faces = {};
  for (const f of FACES) {
    const key = spec[f] || spec.all;
    if (!key) continue;
    faces[f] = { uv: spec.uv || [0, 0, 16, 16], texture: `#${key}` };
  }
  return { from, to, faces };
}

// ------------------------------------------------------------------ archetypes
// ISSUER TOWER: tall cabinet, big front screen, card slot under it, badge,
// stepped top with vent bars. `p` lets each issuer differ in footprint/height.
function issuerTower(id, p) {
  const x0 = p.x0, x1 = p.x1;              // footprint on x
  const z0 = 2, z1 = 14;                   // depth
  const front = z0 - 0.5;                  // bezel plate plane
  const els = [];
  // feet
  els.push(box([x0 + 0.5, 0, z0 + 0.5], [x0 + 2, 0.75, z0 + 2], 'trim'));
  els.push(box([x1 - 2, 0, z0 + 0.5], [x1 - 0.5, 0.75, z0 + 2], 'trim'));
  els.push(box([x0 + 0.5, 0, z1 - 2], [x0 + 2, 0.75, z1 - 0.5], 'trim'));
  els.push(box([x1 - 2, 0, z1 - 2], [x1 - 0.5, 0.75, z1 - 0.5], 'trim'));
  // plinth + main body
  els.push(box([x0 - 0.5, 0.75, z0 - 0.5], [x1 + 0.5, 1.5, z1 + 0.5], 'trim'));
  els.push(box([x0, 1.5, z0], [x1, p.bodyTop, z1], { all: 'body', up: 'top' }));
  // upper stage (stepped back) + chamfer + cap
  els.push(box([x0 + 0.5, p.bodyTop, z0 + 0.5], [x1 - 0.5, p.upTop, z1 - 0.5], { all: 'body2', up: 'top' }));
  els.push(box([x0 + 0.75, p.upTop, z0 + 0.75], [x1 - 0.75, p.upTop + 0.25, z1 - 0.75], 'body2'));
  els.push(box([x0 + 1, p.upTop + 0.25, z0 + 1], [x1 - 1, p.upTop + 0.5, z1 - 1], 'trim'));
  els.push(box([x0 + 1.25, p.upTop + 0.5, z0 + 1.25], [x1 - 1.25, p.upTop + 1, z1 - 1.25], { all: 'top' }));
  // top vent bars
  for (let i = 0; i < 3; i++) {
    const zz = z0 + 2.5 + i * 2.5;
    els.push(box([x0 + 2, p.upTop + 1, zz], [x1 - 2, p.upTop + 1.25, zz + 1], 'detail'));
  }
  // screen bezel plate + screen panel (protrudes through the bezel)
  const sx0 = x0 + 1.5, sx1 = x1 - 1.5;
  els.push(box([sx0 - 0.5, 3.5, front + 0.1], [sx1 + 0.5, 8.2, z0], 'trim'));
  els.push(box([sx0, 4, front - 0.2], [sx1, 7.7, front + 0.1], { all: 'screen', up: 'screen', down: 'screen' }));
  // LED + nameplate + card slot + peeking card
  els.push(box([x1 - 1.2, 8.4, front + 0.2], [x1 - 0.4, 9.0, z0], 'led'));
  // nameplate on the left of the lower front, card slot to its right (they must
  // not share a face plane, so they are side by side rather than stacked)
  els.push(box([x0 + 1.5, 1.8, front + 0.2], [x0 + 4.5, 3.0, z0], 'accent'));
  els.push(box([x0 + 5.2, 2.0, front + 0.35], [sx1, 2.9, z0], 'detail'));
  els.push(box([x0 + 5.7, 2.15, front + 0.15], [x0 + 8.7, 2.75, front + 0.35], 'paper'));
  // side vents (west / east), three thin fins each
  for (let i = 0; i < 3; i++) {
    const yy = 3.0 + i * 1.6;
    els.push(box([x0 - 0.3, yy, z0 + 2.5], [x0, yy + 0.5, z1 - 2.5], 'detail'));
    els.push(box([x1, yy, z0 + 2.5], [x1 + 0.3, yy + 0.5, z1 - 2.5], 'detail'));
  }
  // paper tray on the top stage
  els.push(box([x0 + 2, p.upTop - 0.1, z1 - 3], [x1 - 2, p.upTop + 0.4, z1 - 1.5], 'paper'));
  return els;
}

// READER WEDGE: low desk scanner, glass scan bed on top, raised back spine with
// the screen, vents on the flanks.
function readerWedge(id, p) {
  const x0 = p.x0, x1 = p.x1;
  const z0 = 2, z1 = 14;
  const front = z0 - 0.5;
  const els = [];
  els.push(box([x0 + 0.5, 0, z0 + 0.5], [x0 + 2, 0.6, z0 + 2], 'trim'));
  els.push(box([x1 - 2, 0, z0 + 0.5], [x1 - 0.5, 0.6, z0 + 2], 'trim'));
  els.push(box([x0 + 0.5, 0, z1 - 2], [x0 + 2, 0.6, z1 - 0.5], 'trim'));
  els.push(box([x1 - 2, 0, z1 - 2], [x1 - 0.5, 0.6, z1 - 0.5], 'trim'));
  // base + deck
  els.push(box([x0 - 0.5, 0.6, z0 - 0.5], [x1 + 0.5, 1.2, z1 + 0.5], 'trim'));
  els.push(box([x0, 1.2, z0], [x1, 3.4, z1], { all: 'body', up: 'top' }));
  // glass scan bed: trim frame + inset glass
  els.push(box([x0 + 0.8, 3.4, z0 + 0.8], [x1 - 0.8, 3.6, z1 - 3.2], 'trim'));
  els.push(box([x0 + 1.3, 3.6, z0 + 1.3], [x1 - 1.3, 3.75, z1 - 3.7], { all: 'glass', up: 'glass' }));
  // back spine + chamfer + cap
  els.push(box([x0 + 0.5, 3.4, z1 - 3], [x1 - 0.5, p.spineTop, z1], { all: 'body2', up: 'top' }));
  els.push(box([x0 + 0.75, p.spineTop, z1 - 2.75], [x1 - 0.75, p.spineTop + 0.35, z1 - 0.25], 'trim'));
  els.push(box([x0 + 1, p.spineTop + 0.35, z1 - 2.5], [x1 - 1, p.spineTop + 0.6, z1 - 0.5], { all: 'top' }));
  // screen on the spine front face + bezel
  const sx0 = x0 + 1.5, sx1 = x1 - 1.5;
  els.push(box([sx0 - 0.4, 4.2, z1 - 3.4], [sx1 + 0.4, 7.6, z1 - 3.0], 'trim'));
  els.push(box([sx0, 4.5, z1 - 3.75], [sx1, 7.3, z1 - 3.4], { all: 'screen', up: 'screen', down: 'screen' }));
  // LED bar across the spine cap + accent strip on the deck front
  els.push(box([x0 + 1.5, p.spineTop + 0.6, z1 - 2], [x1 - 1.5, p.spineTop + 0.85, z1 - 1], 'led'));
  els.push(box([x0 + 0.4, 3.15, front + 0.15], [x1 - 0.4, 3.4, z0], 'accent'));
  // card slot on the deck front + rubber pad + flank vents
  els.push(box([x0 + 1.5, 1.6, front + 0.2], [x0 + 6, 2.4, z0], 'detail'));
  for (let i = 0; i < 3; i++) {
    const yy = 1.8 + i * 0.6;
    els.push(box([x0 - 0.3, yy, z0 + 3], [x0, yy + 0.35, z1 - 4], 'detail'));
    els.push(box([x1, yy, z0 + 3], [x1 + 0.3, yy + 0.35, z1 - 4], 'detail'));
  }
  return els;
}

// STAMP PRESS: heavy base, paper pad, back column, press head with a ram, a
// handle bar above and a date window on the head front.
function stampPress(id, p) {
  const x0 = 1.5, x1 = 14.5;
  const z0 = 2.5, z1 = 13.5;
  const front = z0 - 0.5;
  const els = [];
  // four feet + base slab
  els.push(box([x0 + 0.5, 0, z0 + 0.5], [x0 + 2.5, 0.8, z0 + 2.5], 'trim'));
  els.push(box([x1 - 2.5, 0, z0 + 0.5], [x1 - 0.5, 0.8, z0 + 2.5], 'trim'));
  els.push(box([x0 + 0.5, 0, z1 - 2.5], [x0 + 2.5, 0.8, z1 - 0.5], 'trim'));
  els.push(box([x1 - 2.5, 0, z1 - 2.5], [x1 - 0.5, 0.8, z1 - 0.5], 'trim'));
  els.push(box([x0, 0.8, z0], [x1, 2.2, z1], { all: 'body', up: 'top' }));
  // anvil + the paper being stamped (front half of the anvil)
  els.push(box([x0 + 1, 2.2, z0 + 1], [x1 - 1, 3.0, z1 - 1], 'body2'));
  els.push(box([x0 + 2, 3.0, z0 + 0.5], [x1 - 2, 3.25, z0 + 5.5], 'paper'));
  // back column (clear of the paper) + its chamfer
  els.push(box([5, 3.0, z1 - 4.5], [11, p.columnTop, z1], { all: 'body2', up: 'top' }));
  els.push(box([5.25, p.columnTop, z1 - 4.25], [10.75, p.columnTop + 0.3, z1 - 0.25], 'trim'));
  // press head spanning over the paper, with chamfer + cap
  els.push(box([3, p.columnTop + 0.3, z0], [13, p.headTop, z1 - 1.5], { all: 'body', up: 'top' }));
  els.push(box([3.25, p.headTop, z0 + 0.25], [12.75, p.headTop + 0.35, z1 - 1.75], 'trim'));
  els.push(box([3.5, p.headTop + 0.35, z0 + 0.5], [12.5, p.headTop + 0.7, z1 - 2], { all: 'top' }));
  // handle bar + knobs on the cap
  els.push(box([2.2, p.headTop + 0.7, 7.2], [13.8, p.headTop + 1.5, 8.8], 'accent'));
  els.push(box([2.2, p.headTop + 1.5, 7.4], [2.9, p.headTop + 2.0, 8.6], 'trim'));
  els.push(box([13.1, p.headTop + 1.5, 7.4], [13.8, p.headTop + 2.0, 8.6], 'trim'));
  // ram hanging from the head + the stamp plate at its foot
  els.push(box([6, 3.5, z0 + 2], [10, p.columnTop + 0.3, z0 + 4.5], 'body2'));
  els.push(box([6, 3.25, z0 + 2], [10, 3.5, z0 + 4.5], 'accent'));
  // date window on the head front face + bezel + status LED
  els.push(box([4.2, p.columnTop + 0.7, front + 0.35], [9.8, p.headTop - 0.4, z0], 'trim'));
  els.push(box([4.5, p.columnTop + 0.95, front + 0.05], [9.5, p.headTop - 0.65, front + 0.35],
    { all: 'screen', up: 'screen', down: 'screen' }));
  els.push(box([10.2, p.columnTop + 0.9, front + 0.2], [11, p.columnTop + 1.5, z0], 'led'));
  // cooling fins bolted to the base slab's flanks (the anvil above is narrower,
  // so the fins must sit low or they read as floating bars)
  for (let i = 0; i < 2; i++) {
    const yy = 1.0 + i * 0.6;
    els.push(box([x0 - 0.3, yy, z0 + 2], [x0, yy + 0.58, z1 - 2], 'detail'));
    els.push(box([x1, yy, z0 + 2], [x1 + 0.3, yy + 0.58, z1 - 2], 'detail'));
  }
  return els;
}

// ------------------------------------------------------------------ devices
// SERVICE KIOSK: a wide service desk with a low control deck in front and a tall
// back panel carrying the screen. Used by the service terminals and by airport
// check-in, so the family does not turn into 19 variations of one tower.
function serviceKiosk(id, p) {
  const x0 = p.x0, x1 = p.x1;
  const z0 = 2, z1 = 14;
  const front = z0 - 0.5;
  const els = [];
  // feet + plinth
  els.push(box([x0 + 0.5, 0, z0 + 0.5], [x0 + 2, 0.8, z0 + 2], 'trim'));
  els.push(box([x1 - 2, 0, z0 + 0.5], [x1 - 0.5, 0.8, z0 + 2], 'trim'));
  els.push(box([x0 + 0.5, 0, z1 - 2], [x0 + 2, 0.8, z1 - 0.5], 'trim'));
  els.push(box([x1 - 2, 0, z1 - 2], [x1 - 0.5, 0.8, z1 - 0.5], 'trim'));
  els.push(box([x0 - 0.5, 0.8, z0 - 0.5], [x1 + 0.5, 1.5, z1 + 0.5], 'trim'));
  // low service desk + its top
  els.push(box([x0, 1.5, z0], [x1, 4.2, z1], { all: 'body', up: 'top' }));
  els.push(box([x0 + 0.25, 4.2, z0 + 0.25], [x1 - 0.25, 4.6, z1 - 0.25], 'trim'));
  // keypad on the desk + receipt slot in the desk front + paper peeking out
  els.push(box([x0 + 1.5, 4.6, z0 + 1.5], [x0 + 5.5, 4.9, z0 + 4.5], 'detail'));
  els.push(box([x0 + 6.5, 2.0, front + 0.2], [x1 - 1.5, 2.8, z0], 'detail'));
  els.push(box([x0 + 7, 2.6, front], [x1 - 2, 3.2, front + 0.2], 'paper'));
  // tall back panel with chamfer + cap
  els.push(box([x0 + 0.5, 4.6, z1 - 2.5], [x1 - 0.5, p.panelTop, z1], { all: 'body2', up: 'top' }));
  els.push(box([x0 + 0.75, p.panelTop, z1 - 2.25], [x1 - 0.75, p.panelTop + 0.3, z1 - 0.25], 'trim'));
  els.push(box([x0 + 1, p.panelTop + 0.3, z1 - 2], [x1 - 1, p.panelTop + 0.7, z1 - 0.5], { all: 'top' }));
  // screen bezel + screen on the panel's front face
  els.push(box([x0 + 3.0, 5.0, z1 - 2.8], [x1 - 3.0, 9.4, z1 - 2.5], 'trim'));
  els.push(box([x0 + 3.5, 5.4, z1 - 3.1], [x1 - 3.5, 9.0, z1 - 2.8], { all: 'screen', up: 'screen', down: 'screen' }));
  // LED bar on the cap
  els.push(box([x0 + 2, p.panelTop + 0.7, z1 - 2.2], [x1 - 2, p.panelTop + 0.9, z1 - 1.2], 'led'));
  // side fins on the desk flanks
  for (let i = 0; i < 2; i++) {
    const yy = 2.0 + i * 0.9;
    els.push(box([x0 - 0.3, yy, z0 + 2], [x0, yy + 0.6, z1 - 4], 'detail'));
    els.push(box([x1, yy, z0 + 2], [x1 + 0.3, yy + 0.6, z1 - 4], 'detail'));
  }
  return els;
}

// ================================================================== two-block tiers
// The four families above are the LOWER block of a machine when the device is two
// blocks tall: same footprint, same part vocabulary, but the body reaches the
// y = 16 floor line and the chamfer/cap stack moves to the top of the extension
// tier. The `*Upper` builders are authored in LOCAL coordinates starting at y = 0
// and are shifted by exactly one block (+16) when merged, so the two tiers meet
// flush at 16 and the icon reads as one continuous two-storey machine.

/** shift a tier by +dy model units (the two-block merge step) */
function shiftTier(elements, dy) {
  return elements.map(el => ({
    from: [el.from[0], el.from[1] + dy, el.from[2]],
    to: [el.to[0], el.to[1] + dy, el.to[2]],
    faces: el.faces,
  }));
}

/** main model + extension model stacked one block apart */
function stackTiers(lower, upper) {
  return lower.concat(shiftTier(upper, EXTENSION_Y));
}

// ISSUER TOWER, two-block: lower block = service cabinet (screen, nameplate, card
// slot, side vents) closed by a proud belt and a recessed floor cap at y = 16.
function issuerTowerLower(id, p) {
  const x0 = p.x0, x1 = p.x1;
  const z0 = 2, z1 = 14;
  const front = z0 - 0.5;
  const sx0 = x0 + 1.5, sx1 = x1 - 1.5;
  const els = [];
  // feet + plinth (identical language to the one-block archetype)
  els.push(box([x0 + 0.5, 0, z0 + 0.5], [x0 + 2, 0.75, z0 + 2], 'trim'));
  els.push(box([x1 - 2, 0, z0 + 0.5], [x1 - 0.5, 0.75, z0 + 2], 'trim'));
  els.push(box([x0 + 0.5, 0, z1 - 2], [x0 + 2, 0.75, z1 - 0.5], 'trim'));
  els.push(box([x1 - 2, 0, z1 - 2], [x1 - 0.5, 0.75, z1 - 0.5], 'trim'));
  els.push(box([x0 - 0.5, 0.75, z0 - 0.5], [x1 + 0.5, 1.5, z1 + 0.5], 'trim'));
  // body up to the floor line, then belt + recessed cap
  els.push(box([x0, 1.5, z0], [x1, 14.4, z1], { all: 'body', up: 'top' }));
  els.push(box([x0 - 0.2, 14.4, z0 - 0.2], [x1 + 0.2, 15.3, z1 + 0.2], 'trim'));
  els.push(box([x0 + 0.35, 15.3, z0 + 0.35], [x1 - 0.35, 16, z1 - 0.35], { all: 'body2', up: 'top' }));
  // front: screen bezel + screen, service LED, vent band
  els.push(box([sx0 - 0.5, 3.6, front + 0.1], [sx1 + 0.5, 8.3, z0], 'trim'));
  els.push(box([sx0, 4.1, front - 0.2], [sx1, 7.8, front + 0.1], { all: 'screen', up: 'screen', down: 'screen' }));
  els.push(box([x1 - 1.2, 8.5, front + 0.2], [x1 - 0.4, 9.1, z0], 'led'));
  els.push(box([sx0, 11.2, front + 0.2], [sx1, 12.2, z0], 'detail'));
  // nameplate + card slot + peeking card (side by side, different planes)
  els.push(box([x0 + 1.5, 1.8, front + 0.2], [x0 + 4.5, 3.0, z0], 'accent'));
  els.push(box([x0 + 5.2, 2.0, front + 0.35], [sx1, 2.9, z0], 'detail'));
  els.push(box([x0 + 5.7, 2.15, front + 0.15], [x0 + 8.7, 2.75, front + 0.35], 'paper'));
  // flank vents, three fins per side
  for (let i = 0; i < 3; i++) {
    const yy = 3.0 + i * 1.6;
    els.push(box([x0 - 0.3, yy, z0 + 2.5], [x0, yy + 0.5, z1 - 2.5], 'detail'));
    els.push(box([x1, yy, z0 + 2.5], [x1 + 0.3, yy + 0.5, z1 - 2.5], 'detail'));
  }
  return els;
}

// ISSUER TOWER, extension (local y 0..upperTop+1.25): second storey with its own
// status screen, LED bar, badge and the stepped cap + vent bars of the machine.
function issuerTowerUpper(id, p) {
  const x0 = p.x0, x1 = p.x1;
  const z0 = 2, z1 = 14;
  const top = p.upperTop;
  const els = [];
  // skirt sitting on the floor cap, then the upper body
  els.push(box([x0 + 0.6, 0, z0 + 0.6], [x1 - 0.6, 0.6, z1 - 0.6], 'trim'));
  els.push(box([x0 + 0.95, 0.6, z0 + 0.95], [x1 - 0.95, top, z1 - 0.95], { all: 'body2', up: 'top' }));
  // upper front screen + bezel + LED bar + badge
  els.push(box([x0 + 2.2, 1.4, z0 + 0.5], [x1 - 2.2, 5.9, z0 + 0.95], 'trim'));
  els.push(box([x0 + 2.7, 1.8, z0 + 0.2], [x1 - 2.7, 5.5, z0 + 0.5],
    { all: 'screen', up: 'screen', down: 'screen' }));
  els.push(box([x0 + 2.7, 6.1, z0 + 0.55], [x1 - 2.7, 6.5, z0 + 0.95], 'led'));
  els.push(box([x0 + 1.3, 6.1, z0 + 0.55], [x0 + 2.3, 7.1, z0 + 0.95], 'accent'));
  // stepped cap + vent bars
  els.push(box([x0 + 1.2, top, z0 + 1.2], [x1 - 1.2, top + 0.5, z1 - 1.2], 'trim'));
  els.push(box([x0 + 1.5, top + 0.5, z0 + 1.5], [x1 - 1.5, top + 1.0, z1 - 1.5], { all: 'top' }));
  for (let i = 0; i < 2; i++) {
    const zz = z0 + 2.4 + i * 2.3;
    els.push(box([x0 + 2.4, top + 1.0, zz], [x1 - 2.4, top + 1.25, zz + 1], 'detail'));
  }
  return els;
}

// READER WEDGE, two-block: the desk stays low, the back spine becomes a full
// two-block column with the floor-line belt at y = 16.
function readerWedgeLower(id, p) {
  const x0 = p.x0, x1 = p.x1;
  const z0 = 2, z1 = 14;
  const front = z0 - 0.5;
  const els = [];
  els.push(box([x0 + 0.5, 0, z0 + 0.5], [x0 + 2, 0.6, z0 + 2], 'trim'));
  els.push(box([x1 - 2, 0, z0 + 0.5], [x1 - 0.5, 0.6, z0 + 2], 'trim'));
  els.push(box([x0 + 0.5, 0, z1 - 2], [x0 + 2, 0.6, z1 - 0.5], 'trim'));
  els.push(box([x1 - 2, 0, z1 - 2], [x1 - 0.5, 0.6, z1 - 0.5], 'trim'));
  els.push(box([x0 - 0.5, 0.6, z0 - 0.5], [x1 + 0.5, 1.2, z1 + 0.5], 'trim'));
  // desk + glass scan bed (the frame stops short of the column belt so the two
  // 5.4 px floor planes cannot line up face to face)
  els.push(box([x0, 1.2, z0], [x1, 5.4, z1], { all: 'body', up: 'top' }));
  els.push(box([x0 + 0.8, 5.4, z0 + 0.8], [x1 - 0.8, 5.6, z1 - 3.75], 'trim'));
  els.push(box([x0 + 1.3, 5.6, z0 + 1.3], [x1 - 1.3, 5.75, z1 - 3.85], { all: 'glass', up: 'glass' }));
  // full-height back column, closed by a proud belt at the floor line
  els.push(box([x0 + 0.5, 5.4, z1 - 3.4], [x1 - 0.5, 15.2, z1], { all: 'body2', up: 'top' }));
  els.push(box([x0 + 0.15, 15.2, z1 - 3.7], [x1 - 0.15, 16, z1 + 0.2], 'trim'));
  // deck front detail: accent strip + label band + card slot + badge
  els.push(box([x0 + 0.4, 5.15, front + 0.15], [x1 - 0.4, 5.4, z0], 'accent'));
  els.push(box([x0 + 1.2, 4.4, front + 0.2], [x1 - 1.2, 5.0, z0], 'detail'));
  els.push(box([x0 + 1.5, 1.6, front + 0.2], [x0 + 6, 2.4, z0], 'detail'));
  els.push(box([x0 + 1.5, 3.0, front + 0.2], [x0 + 3.2, 3.6, z0], 'accent'));
  // flank vents on the desk + two handles on the column
  for (let i = 0; i < 3; i++) {
    const yy = 1.8 + i * 0.6;
    els.push(box([x0 - 0.3, yy, z0 + 3], [x0, yy + 0.35, z1 - 4], 'detail'));
    els.push(box([x1, yy, z0 + 3], [x1 + 0.3, yy + 0.35, z1 - 4], 'detail'));
  }
  els.push(box([x0 + 0.2, 7.2, z1 - 3.3], [x0 + 0.5, 13.6, z1 - 0.9], 'detail'));
  els.push(box([x1 - 0.5, 7.2, z1 - 3.3], [x1 - 0.2, 13.6, z1 - 0.9], 'detail'));
  return els;
}

// READER WEDGE, extension: the reader head cantilevered over the desk, screen
// facing the customer, status LED bar and camera dot on the cap.
function readerWedgeUpper(id, p) {
  const x0 = p.x0, x1 = p.x1;
  const z0 = 2, z1 = 14;
  const top = p.headTop;
  const els = [];
  els.push(box([x0 + 1.0, 0, z1 - 3.2], [x1 - 1.0, 0.6, z1 - 0.3], 'trim'));
  els.push(box([x0 + 0.4, 0.6, z0 + 2.6], [x1 - 0.4, top, z1 - 0.7], { all: 'body', up: 'top' }));
  // head screen + bezel, LED bar under it, camera dot top-right
  els.push(box([x0 + 1.6, 1.4, z0 + 2.1], [x1 - 1.6, 6.6, z0 + 2.6], 'trim'));
  els.push(box([x0 + 2.1, 1.8, z0 + 1.8], [x1 - 2.1, 6.2, z0 + 2.1],
    { all: 'screen', up: 'screen', down: 'screen' }));
  els.push(box([x0 + 2.1, 0.9, z0 + 2.2], [x1 - 2.1, 1.3, z0 + 2.6], 'led'));
  els.push(box([x1 - 2.3, 7.0, z0 + 2.15], [x1 - 1.5, 7.6, z0 + 2.6], 'led'));
  // cap + head side fins
  els.push(box([x0 + 0.7, top, z0 + 2.9], [x1 - 0.7, top + 0.5, z1 - 1.0], 'trim'));
  els.push(box([x0 + 1.0, top + 0.5, z0 + 3.2], [x1 - 1.0, top + 1.0, z1 - 1.3], { all: 'top' }));
  els.push(box([x0 - 0.1, 2.0, z0 + 3.2], [x0 + 0.4, 6.4, z0 + 4.0], 'detail'));
  els.push(box([x1 - 0.4, 2.0, z0 + 3.2], [x1 + 0.1, 6.4, z0 + 4.0], 'detail'));
  return els;
}

// SERVICE KIOSK, two-block: desk in the lower block, the back panel runs the whole
// two blocks and is closed by a belt at the floor line.
function serviceKioskLower(id, p) {
  const x0 = p.x0, x1 = p.x1;
  const z0 = 2, z1 = 14;
  const front = z0 - 0.5;
  const els = [];
  els.push(box([x0 + 0.5, 0, z0 + 0.5], [x0 + 2, 0.8, z0 + 2], 'trim'));
  els.push(box([x1 - 2, 0, z0 + 0.5], [x1 - 0.5, 0.8, z0 + 2], 'trim'));
  els.push(box([x0 + 0.5, 0, z1 - 2], [x0 + 2, 0.8, z1 - 0.5], 'trim'));
  els.push(box([x1 - 2, 0, z1 - 2], [x1 - 0.5, 0.8, z1 - 0.5], 'trim'));
  els.push(box([x0 - 0.5, 0.8, z0 - 0.5], [x1 + 0.5, 1.5, z1 + 0.5], 'trim'));
  // desk + top + keypad + printed ticket
  els.push(box([x0, 1.5, z0], [x1, 4.2, z1], { all: 'body', up: 'top' }));
  els.push(box([x0 + 0.25, 4.2, z0 + 0.25], [x1 - 0.25, 4.6, z1 - 0.25], 'trim'));
  els.push(box([x0 + 1.5, 4.6, z0 + 1.5], [x0 + 5.5, 4.9, z0 + 4.5], 'detail'));
  els.push(box([x0 + 1.9, 4.9, z0 + 1.9], [x0 + 4.2, 4.98, z0 + 3.9], 'paper'));
  // receipt slot + paper out of the desk front
  els.push(box([x0 + 6.5, 2.0, front + 0.2], [x1 - 1.5, 2.8, z0], 'detail'));
  els.push(box([x0 + 7, 2.6, front], [x1 - 2, 3.2, front + 0.2], 'paper'));
  // back panel to the floor line + belt + nameplate
  els.push(box([x0 + 0.5, 4.6, z1 - 2.5], [x1 - 0.5, 15.2, z1], { all: 'body2', up: 'top' }));
  els.push(box([x0 + 0.2, 15.2, z1 - 2.8], [x1 - 0.2, 16, z1 + 0.2], 'trim'));
  els.push(box([x0 + 1.5, 5.0, z1 - 2.85], [x1 - 1.5, 5.8, z1 - 2.5], 'accent'));
  // desk flank fins
  for (let i = 0; i < 2; i++) {
    const yy = 2.0 + i * 0.9;
    els.push(box([x0 - 0.3, yy, z0 + 2], [x0, yy + 0.6, z1 - 4], 'detail'));
    els.push(box([x1, yy, z0 + 2], [x1 + 0.3, yy + 0.6, z1 - 4], 'detail'));
  }
  return els;
}

// SERVICE KIOSK, extension: the passenger-facing screen head on top of the panel.
function serviceKioskUpper(id, p) {
  const x0 = p.x0, x1 = p.x1;
  const z1 = 14;
  const top = p.headTop;
  const els = [];
  els.push(box([x0 + 1.2, 0, z1 - 2.2], [x1 - 1.2, 0.6, z1 - 0.4], 'trim'));
  els.push(box([x0 + 0.9, 0.6, z1 - 2.6], [x1 - 0.9, top, z1 - 0.6], { all: 'body', up: 'top' }));
  // screen + bezel, LED bar, badge
  els.push(box([x0 + 2.2, 1.4, z1 - 3.0], [x1 - 2.2, 6.8, z1 - 2.6], 'trim'));
  els.push(box([x0 + 2.7, 1.8, z1 - 3.35], [x1 - 2.7, 6.4, z1 - 3.0],
    { all: 'screen', up: 'screen', down: 'screen' }));
  els.push(box([x0 + 2.7, 7.2, z1 - 3.2], [x1 - 2.7, 7.7, z1 - 2.6], 'led'));
  els.push(box([x0 + 1.4, 7.2, z1 - 3.2], [x0 + 2.3, 8.2, z1 - 2.6], 'accent'));
  // cap + head flank fins
  els.push(box([x0 + 1.2, top, z1 - 2.3], [x1 - 1.2, top + 0.5, z1 - 0.2], 'trim'));
  els.push(box([x0 + 1.5, top + 0.5, z1 - 2.0], [x1 - 1.5, top + 1.0, z1 - 0.5], { all: 'top' }));
  els.push(box([x0 - 0.1, 1.6, z1 - 2.3], [x0 + 0.9, 5.4, z1 - 1.3], 'detail'));
  els.push(box([x1 - 0.9, 1.6, z1 - 2.3], [x1 + 0.1, 5.4, z1 - 1.3], 'detail'));
  return els;
}

// STAMP PRESS, two-block: base + anvil + paper, back column to the floor line, and
// the ram hanging from the head (the head itself is the extension tier).
function stampPressLower(id, p) {
  const x0 = p.x0, x1 = p.x1;
  const z0 = 2.5, z1 = 13.5;
  const els = [];
  els.push(box([x0 + 0.5, 0, z0 + 0.5], [x0 + 2.5, 0.8, z0 + 2.5], 'trim'));
  els.push(box([x1 - 2.5, 0, z0 + 0.5], [x1 - 0.5, 0.8, z0 + 2.5], 'trim'));
  els.push(box([x0 + 0.5, 0, z1 - 2.5], [x0 + 2.5, 0.8, z1 - 0.5], 'trim'));
  els.push(box([x1 - 2.5, 0, z1 - 2.5], [x1 - 0.5, 0.8, z1 - 0.5], 'trim'));
  els.push(box([x0, 0.8, z0], [x1, 2.2, z1], { all: 'body', up: 'top' }));
  // anvil + paper pad + a second sheet waiting on the anvil
  els.push(box([x0 + 1, 2.2, z0 + 1], [x1 - 1, 3.0, z1 - 1], 'body2'));
  els.push(box([x0 + 2, 3.0, z0 + 0.5], [x1 - 2, 3.25, z0 + 5.5], 'paper'));
  els.push(box([x0 + 2, 3.25, z0 + 0.5], [x0 + 5.5, 3.45, z0 + 3.5], 'paper'));
  // column to the floor line + belt + front nameplate
  els.push(box([p.colX0, 3.0, z1 - 4.5], [p.colX1, 15.2, z1], { all: 'body2', up: 'top' }));
  els.push(box([p.colX0 - 0.3, 15.2, z1 - 4.8], [p.colX1 + 0.3, 16, z1 + 0.2], 'trim'));
  els.push(box([p.colX0 + 0.6, 4.6, z1 - 4.75], [p.colX1 - 0.6, 6.2, z1 - 4.5], 'accent'));
  // ram (hangs from the head down into the lower block) + stamp plate
  els.push(box([6.4, 6.0, z0 + 1.2], [10.0, 16, z0 + 3.7], 'body2'));
  els.push(box([6.4, 5.7, z0 + 1.2], [10.0, 6.0, z0 + 3.7], 'accent'));
  // cooling fins on the base slab flanks
  for (let i = 0; i < 2; i++) {
    const yy = 1.0 + i * 0.6;
    els.push(box([x0 - 0.3, yy, z0 + 2], [x0, yy + 0.58, z1 - 2], 'detail'));
    els.push(box([x1, yy, z0 + 2], [x1 + 0.3, yy + 0.58, z1 - 2], 'detail'));
  }
  return els;
}

// STAMP PRESS, extension: press head with chamfer, cap, handle bar + knobs, and the
// date window on the head front.
function stampPressUpper(id, p) {
  const x0 = p.x0, x1 = p.x1;
  const z0 = 2.5, z1 = 13.5;
  const top = p.headTop;
  const els = [];
  // collar on the column belt, then the head
  els.push(box([p.colX0 + 0.2, 0, z1 - 4.2], [p.colX1 - 0.2, 0.8, z1 - 0.2], { all: 'body2' }));
  els.push(box([p.headX0, 0.8, z0 + 0.2], [p.headX1, top, z1 - 1.0], { all: 'body', up: 'top' }));
  els.push(box([p.headX0 + 0.3, top, z0 + 0.5], [p.headX1 - 0.3, top + 0.5, z1 - 1.3], 'trim'));
  els.push(box([p.headX0 + 0.6, top + 0.5, z0 + 0.8], [p.headX1 - 0.6, top + 1.0, z1 - 1.6], { all: 'top' }));
  // handle bar + knobs
  els.push(box([x0 + 1.1, top + 1.0, 7.6], [x1 - 1.1, top + 1.8, 9.0], 'accent'));
  els.push(box([x0 + 1.1, top + 1.8, 7.8], [x0 + 1.8, top + 2.3, 8.8], 'trim'));
  els.push(box([x1 - 1.8, top + 1.8, 7.8], [x1 - 1.1, top + 2.3, 8.8], 'trim'));
  // date window: bezel + screen + status LED on the head front
  els.push(box([4.6, 1.6, z0 - 0.25], [9.6, 4.6, z0 + 0.2], 'trim'));
  els.push(box([5.0, 2.0, z0 - 0.55], [9.2, 4.2, z0 - 0.25],
    { all: 'screen', up: 'screen', down: 'screen' }));
  els.push(box([10.0, 1.8, z0 - 0.25], [10.8, 2.4, z0 + 0.2], 'led'));
  // head side fins
  els.push(box([p.headX0 - 0.2, 1.4, z1 - 3.4], [p.headX0, 4.6, z1 - 1.4], 'detail'));
  els.push(box([p.headX1, 1.4, z1 - 3.4], [p.headX1 + 0.2, 4.6, z1 - 1.4], 'detail'));
  return els;
}

// Standard vanilla item display transforms: a 16-unit model in a 16-unit item
// box, so the vanilla numbers keep it in the slot and out of the hand.
const DISPLAY = {
  thirdperson_righthand: { rotation: [75, 45, 0], translation: [0, 2.5, 0], scale: [0.375, 0.375, 0.375] },
  thirdperson_lefthand: { rotation: [75, 45, 0], translation: [0, 2.5, 0], scale: [0.375, 0.375, 0.375] },
  firstperson_righthand: { rotation: [0, 45, 0], translation: [0, 0, 0], scale: [0.4, 0.4, 0.4] },
  firstperson_lefthand: { rotation: [0, 45, 0], translation: [0, 0, 0], scale: [0.4, 0.4, 0.4] },
  gui: { rotation: [30, 225, 0], translation: [0, 0, 0], scale: [0.625, 0.625, 0.625] },
  ground: { rotation: [0, 0, 0], translation: [0, 3, 0], scale: [0.25, 0.25, 0.25] },
  fixed: { rotation: [0, 180, 0], translation: [0, 0, 0], scale: [0.5, 0.5, 0.5] },
};

// Height-adaptive GUI display for a two-block item.
//
// A 24 px tall icon at the vanilla 0.625 would be 1.5 blocks of screen height, so
// the gui scale is normalised by the model's bbox height: height * scale is pinned
// to one block (10 px, the size a 16 px block model occupies at 0.625). The
// translation lifts/lowers the box so the taller model stays centred in the slot
// (model space is corner-based, so the vertical centre moves by (16 - height) / 2).
// Hand transforms keep the vanilla numbers: a two-block machine legitimately reads
// bigger in the hand, only the 16 px inventory slot needs the fit.
function displayFor(height, tall) {
  if (!tall) return DISPLAY;
  const guiScale = Math.min(GUI_SCALE_BLOCK, +(GUI_UNIT_PX / height).toFixed(5));
  const guiShift = +(((BLOCK_PX - height) / 2).toFixed(2));
  return {
    thirdperson_righthand: DISPLAY.thirdperson_righthand,
    thirdperson_lefthand: DISPLAY.thirdperson_lefthand,
    firstperson_righthand: DISPLAY.firstperson_righthand,
    firstperson_lefthand: DISPLAY.firstperson_lefthand,
    gui: { rotation: [30, 225, 0], translation: [0, guiShift, 0], scale: [guiScale, guiScale, guiScale] },
    ground: DISPLAY.ground,
    fixed: DISPLAY.fixed,
  };
}

// ------------------------------------------------------------------ devices
const DEVICES = [
  // id / family / archetype. `tiers` -> two-block machine (lower + extension).
  // ISSUER TOWER (7, 3 of them two blocks tall) ----------------------------
  { id: 'id_issuer', family: 'issuer', build: issuerTower, p: { x0: 2, x1: 14, bodyTop: 8.5, upTop: 10.5 } },
  {
    id: 'passport_issuer', family: 'issuer',
    tiers: { lower: issuerTowerLower, upper: issuerTowerUpper, label: 'issuerTower' },
    p: { x0: 2.5, x1: 13.5, upperTop: 8.8 },
  },
  { id: 'permit_issuer', family: 'issuer', build: issuerTower, p: { x0: 1.5, x1: 14.5, bodyTop: 8, upTop: 9.75 } },
  { id: 'visa_issuer', family: 'issuerViolet', build: issuerTower, p: { x0: 2.25, x1: 13.75, bodyTop: 8.75, upTop: 10.75 } },
  {
    id: 'self_visa', family: 'issuer',
    tiers: { lower: issuerTowerLower, upper: issuerTowerUpper, label: 'issuerTower' },
    p: { x0: 2, x1: 14, upperTop: 7.8 },
  },
  { id: 'endorsement_machine', family: 'issuerViolet', build: issuerTower, p: { x0: 2, x1: 14, bodyTop: 9.25, upTop: 11.25 } },
  {
    id: 'self_endorsement', family: 'issuerViolet',
    tiers: { lower: issuerTowerLower, upper: issuerTowerUpper, label: 'issuerTower' },
    p: { x0: 2.25, x1: 13.75, upperTop: 9.6 },
  },
  // READER WEDGE (4, 2 of them two blocks tall) ----------------------------
  { id: 'desk_scanner', family: 'reader', build: readerWedge, p: { x0: 2, x1: 14, spineTop: 7 } },
  {
    id: 'station_verifier', family: 'reader',
    tiers: { lower: readerWedgeLower, upper: readerWedgeUpper, label: 'readerWedge' },
    p: { x0: 1.5, x1: 14.5, headTop: 8.4 },
  },
  { id: 'hotel_reader', family: 'reader', build: readerWedge, p: { x0: 2.5, x1: 13.5, spineTop: 6.25 } },
  {
    id: 'manual_terminal', family: 'reader',
    tiers: { lower: readerWedgeLower, upper: readerWedgeUpper, label: 'readerWedge' },
    p: { x0: 1.75, x1: 14.25, headTop: 7.6 },
  },
  // SERVICE KIOSK (5, 3 of them two blocks tall) ---------------------------
  {
    id: 'airport_checkin', family: 'reader',
    tiers: { lower: serviceKioskLower, upper: serviceKioskUpper, label: 'serviceKiosk' },
    p: { x0: 1.5, x1: 14.5, headTop: 9.4 },
  },
  { id: 'loss_terminal', family: 'service', build: serviceKiosk, p: { x0: 2, x1: 14, panelTop: 8.5 } },
  {
    id: 'renewal_machine', family: 'service',
    tiers: { lower: serviceKioskLower, upper: serviceKioskUpper, label: 'serviceKiosk' },
    p: { x0: 1.5, x1: 14.5, headTop: 8.8 },
  },
  {
    id: 'blacklist_terminal', family: 'service',
    tiers: { lower: serviceKioskLower, upper: serviceKioskUpper, label: 'serviceKiosk' },
    p: { x0: 1.75, x1: 14.25, headTop: 8.2 },
  },
  { id: 'print_preview', family: 'service', build: serviceKiosk, p: { x0: 2, x1: 14, panelTop: 8.75 } },
  // STAMP PRESS (3, all three two blocks tall) -----------------------------
  {
    id: 'entry_stamp', family: 'stamp',
    tiers: { lower: stampPressLower, upper: stampPressUpper, label: 'stampPress' },
    p: { x0: 1.5, x1: 14.5, colX0: 5, colX1: 11, headX0: 3.2, headX1: 12.8, headTop: 5.4 },
  },
  {
    id: 'exit_stamp', family: 'stamp',
    tiers: { lower: stampPressLower, upper: stampPressUpper, label: 'stampPress' },
    p: { x0: 1.75, x1: 14.25, colX0: 5.25, colX1: 10.75, headX0: 3.4, headX1: 12.6, headTop: 5.9 },
  },
  {
    id: 'stamp_history', family: 'stamp',
    tiers: { lower: stampPressLower, upper: stampPressUpper, label: 'stampPress' },
    p: { x0: 2, x1: 14, colX0: 5.5, colX1: 10.5, headX0: 3.6, headX1: 12.4, headTop: 5.0 },
  },
];

// ------------------------------------------------------------------ validate
function bounds(elements) {
  const min = [Infinity, Infinity, Infinity];
  const max = [-Infinity, -Infinity, -Infinity];
  for (const e of elements) {
    for (let k = 0; k < 3; k++) {
      min[k] = Math.min(min[k], e.from[k], e.to[k]);
      max[k] = Math.max(max[k], e.from[k], e.to[k]);
    }
  }
  return { min, max, size: [max[0] - min[0], max[1] - min[1], max[2] - min[2]] };
}

function validate(id, model, opts) {
  const problems = [];
  const els = model.elements;
  if (!Array.isArray(els) || els.length === 0) return [`${id}: no elements`];
  if (els.length < MIN_ELS || els.length > MAX_ELS) {
    problems.push(`${id}: ${els.length} elements (need ${MIN_ELS}..${MAX_ELS})`);
  }
  if (model.gui_light !== 'front') problems.push(`${id}: gui_light must be "front"`);
  if (!model.display || !model.display.gui) problems.push(`${id}: missing display`);

  const norm = els.map(e => {
    const min = [0, 1, 2].map(i => Math.min(e.from[i], e.to[i]));
    const max = [0, 1, 2].map(i => Math.max(e.from[i], e.to[i]));
    return { min, max, el: e };
  });
  for (const [i, e] of els.entries()) {
    if (e.rotation) problems.push(`${id}: element ${i} has a rotation (item models stay axis-aligned)`);
    for (let k = 0; k < 3; k++) {
      if (e.from[k] >= e.to[k]) problems.push(`${id}: element ${i} from >= to on axis ${k}`);
      const hi = k === 1 ? MAX_ITEM_Y : 16;   // y may reach two world blocks
      if (e.from[k] < 0 || e.to[k] > hi) problems.push(`${id}: element ${i} outside 0..${hi} on axis ${k}`);
    }
    if (!e.faces || Object.keys(e.faces).length === 0) problems.push(`${id}: element ${i} has no faces`);
    for (const [fname, face] of Object.entries(e.faces || {})) {
      const key = (face.texture || '').replace('#', '');
      if (!model.textures[key]) problems.push(`${id}: element ${i} face ${fname} -> unknown texture #${key}`);
    }
  }
  // same-facing coincident faces, and full containment
  for (let i = 0; i < norm.length; i++) {
    for (let j = i + 1; j < norm.length; j++) {
      const a = norm[i], b = norm[j];
      for (let ax = 0; ax < 3; ax++) {
        const o1 = (ax + 1) % 3, o2 = (ax + 2) % 3;
        const ov1 = Math.min(a.max[o1], b.max[o1]) - Math.max(a.min[o1], b.min[o1]);
        const ov2 = Math.min(a.max[o2], b.max[o2]) - Math.max(a.min[o2], b.min[o2]);
        if (ov1 <= 0.02 || ov2 <= 0.02) continue;
        const sameMax = Math.abs(a.max[ax] - b.max[ax]) < 0.005;
        const sameMin = Math.abs(a.min[ax] - b.min[ax]) < 0.005;
        if (sameMax || sameMin) {
          problems.push(`${id}: coincident face ${i}/${j} on ${'xyz'[ax]} (${sameMax ? 'max' : 'min'})`);
        }
      }
      let insideA = true, insideB = true;
      for (let k = 0; k < 3; k++) {
        if (!(b.min[k] >= a.min[k] + 0.01 && b.max[k] <= a.max[k] - 0.01)) insideA = false;
        if (!(a.min[k] >= b.min[k] + 0.01 && a.max[k] <= b.max[k] - 0.01)) insideB = false;
      }
      if (insideA) problems.push(`${id}: element ${j} fully enclosed by ${i}`);
      if (insideB) problems.push(`${id}: element ${i} fully enclosed by ${j}`);
    }
  }

  // ---- two-block contract ---------------------------------------------------
  const bb = bounds(els);
  const height = bb.size[1];
  if (opts.tall) {
    if (!(height > TALL_MIN_HEIGHT)) {
      problems.push(`${id}: TWO-BLOCK item model is ${height.toFixed(2)} px tall (must be > ${TALL_MIN_HEIGHT}) - the extension half is missing`);
    }
    // the two tiers must meet exactly at the block boundary: no floating gap and
    // no overlap (an overlap would z-fight where the halves cross)
    const lowerTop = bounds(opts.lower).max[1];
    const upperBottom = bounds(opts.upper).min[1] + EXTENSION_Y;
    if (Math.abs(lowerTop - EXTENSION_Y) > 1e-6) {
      problems.push(`${id}: lower tier tops out at ${lowerTop} px (must be exactly ${EXTENSION_Y})`);
    }
    if (Math.abs(upperBottom - EXTENSION_Y) > 1e-6) {
      problems.push(`${id}: extension tier starts at ${upperBottom} px after the +${EXTENSION_Y} shift (must be ${EXTENSION_Y})`);
    }
    if (bb.max[1] > MAX_ITEM_Y) {
      problems.push(`${id}: item model reaches y=${bb.max[1]} (limit ${MAX_ITEM_Y})`);
    }
  } else if (height > SINGLE_MAX_HEIGHT) {
    problems.push(`${id}: SINGLE-BLOCK item model is ${height.toFixed(2)} px tall (must be <= ${SINGLE_MAX_HEIGHT})`);
  }
  // gui fit: height * gui scale must stay within one block (10 px at 0.625)
  const guiScale = model.display.gui.scale[1];
  const visual = height * guiScale;
  if (visual > GUI_UNIT_PX * 1.001) {
    problems.push(`${id}: gui visual height ${visual.toFixed(2)} px > one block (${GUI_UNIT_PX} px) - gui scale ${guiScale} not normalised for a ${height.toFixed(2)} px model`);
  }
  return problems;
}

// ------------------------------------------------------------------ main
// Registry cross-check: the device table and DeviceBlock.TALL must agree exactly,
// otherwise a two-block machine silently ships a one-block icon again.
const tableTall = DEVICES.filter(d => d.tiers).map(d => d.id);
const tableIds = DEVICES.map(d => d.id);
const registryProblems = [];
for (const id of TALL_IDS) {
  if (!tableIds.includes(id)) registryProblems.push(`two-block device ${id} has no entry in DEVICES`);
  else if (!tableTall.includes(id)) registryProblems.push(`two-block device ${id} has no lower/upper tiers`);
}
for (const id of tableTall) {
  if (!isTall(id)) registryProblems.push(`${id} is built as two tiers but is not in ${SOURCE.set}`);
}
for (const id of tableIds) {
  if (!isTall(id) && tableTall.includes(id)) registryProblems.push(`${id}: tier table out of sync`);
}

fs.mkdirSync(ITEM_DIR, { recursive: true });
const rows = [];
const allProblems = [...registryProblems];
const seenGeom = new Map();

for (const dev of DEVICES) {
  const tall = isTall(dev.id);
  const textures = textureMap(dev.id, dev.family);
  let elements;
  let tiers = null;
  let archetype;
  if (tall) {
    if (!dev.tiers) throw new Error(`${dev.id}: listed in ${SOURCE.set} but DEVICES has no tiers`);
    const lower = dev.tiers.lower(dev.id, dev.p);
    const upper = dev.tiers.upper(dev.id, dev.p);
    tiers = { lower, upper };
    elements = stackTiers(lower, upper);
    archetype = dev.tiers.label + '+ext';
  } else {
    if (dev.tiers) throw new Error(`${dev.id}: has tiers but is not listed in ${SOURCE.set}`);
    elements = dev.build(dev.id, dev.p);
    archetype = dev.build.name;
  }

  const bb = bounds(elements);
  const height = bb.size[1];
  const model = {
    // dedicated model: no "parent" - that is the point of the upgrade
    gui_light: 'front',
    textures,
    elements,
    display: displayFor(height, tall),
  };
  const problems = validate(dev.id, model, { tall, lower: tiers && tiers.lower, upper: tiers && tiers.upper });
  allProblems.push(...problems);

  // unique geometry per device (the item set is one visual family, not a copy)
  const sig = JSON.stringify(elements.map(e => [e.from, e.to]));
  if (seenGeom.has(sig)) allProblems.push(`${dev.id}: identical geometry to ${seenGeom.get(sig)}`);
  else seenGeom.set(sig, dev.id);

  const gui = model.display.gui;
  rows.push({
    id: dev.id,
    kind: tall ? '2-block' : '1-block',
    archetype,
    elements: elements.length,
    height,
    bbox: `${bb.size[0].toFixed(1)}x${bb.size[1].toFixed(1)}x${bb.size[2].toFixed(1)}`,
    seam: tall ? `${bounds(tiers.lower).max[1].toFixed(2)}|+${EXTENSION_Y}` : '-',
    guiScale: gui.scale[1],
    guiShift: gui.translation[1],
    visual: height * gui.scale[1],
    screen: model.textures.screen ? 'item/' + dev.id + '_screen' : '-',
    verdict: problems.length ? 'FAIL' : 'PASS',
  });
  if (!DRY) {
    fs.writeFileSync(
      path.join(ITEM_DIR, dev.id + '.json'),
      JSON.stringify(model, null, 2) + '\n',
      'utf8'
    );
  }
}

// ------------------------------------------------------------------ report
const tallRows = rows.filter(r => r.kind === '2-block');
const lines = [];
lines.push('VeriBorder dedicated 3D item models (tools/generate_item_models.js)');
lines.push(DRY ? 'mode: --dry (nothing written)' : `written: ${DEVICES.length} file(s) under ${path.relative(ROOT, ITEM_DIR)}`);
lines.push(`two-block registry: ${TALL_IDS.length} device(s) from ${SOURCE.blockFile} (${SOURCE.set}) - ${TALL_IDS.join(', ')}`);
lines.push('');
lines.push('id                    kind      archetype           els   bbox (px)       seam      gui   shift  visual  verdict');
lines.push('--------------------------------------------------------------------------------------------------------------');
for (const r of rows) {
  lines.push(
    r.id.padEnd(22) + r.kind.padEnd(10) + r.archetype.padEnd(20) +
    String(r.elements).padStart(3) + '  ' + r.bbox.padEnd(16) +
    String(r.seam).padStart(8) + '  ' + String(r.guiScale).padStart(6) +
    String(r.guiShift).padStart(7) + String(r.visual.toFixed(2)).padStart(8) + '  ' + r.verdict
  );
}
lines.push('');
lines.push('bbox height of every TWO-BLOCK device item model (assertion: > ' + TALL_MIN_HEIGHT + ' px):');
lines.push('');
lines.push('id                    tier els  lower+ext  bbox height  gui scale  visual (blocks)');
lines.push('-------------------------------------------------------------------------------');
for (const r of tallRows) {
  lines.push(
    r.id.padEnd(22) + String(r.elements).padStart(4) + '       ' + r.seam.padEnd(10) +
    (r.height.toFixed(2) + ' px').padStart(10) + String(r.guiScale).padStart(11) +
    (r.visual / (GUI_SCALE_BLOCK * BLOCK_PX)).toFixed(2).padStart(16)
  );
}
lines.push('');
lines.push(`min elements: ${Math.min(...rows.map(r => r.elements))}   max elements: ${Math.max(...rows.map(r => r.elements))}`);
lines.push('');
lines.push('ASSERTIONS');
const tallFail = allProblems.some(p => p.includes('TWO-BLOCK item model'));
const singleFail = allProblems.some(p => p.includes('SINGLE-BLOCK item model'));
const seamFail = allProblems.some(p => p.includes('tier tops out') || p.includes('tier starts at'));
const guiFail = allProblems.some(p => p.includes('gui visual height'));
const asserts = [
  [`two-block item models > ${TALL_MIN_HEIGHT} px tall (${tallRows.length}/${TALL_IDS.length} checked)`, !tallFail],
  [`single-block item models <= ${SINGLE_MAX_HEIGHT} px tall`, !singleFail],
  ['lower tier ends and extension tier starts at exactly y=16', !seamFail],
  [`gui visual height <= 1.0 block (${GUI_UNIT_PX} px)`, !guiFail],
  [`registry: DEVICES matches ${SOURCE.set}`, registryProblems.length === 0],
  ['geometry: axis-aligned, unique per device, 20..40 elements, no coincident/enclosed faces', !allProblems.some(p => /rotation|coincident|enclosed|elements \(|identical geometry|outside 0\.\./.test(p))],
];
for (const [name, ok] of asserts) lines.push(`  [${ok ? 'PASS' : 'FAIL'}] ${name}`);
lines.push('');
if (allProblems.length) {
  lines.push(`FAIL: ${allProblems.length} problem(s)`);
  for (const p of allProblems) lines.push('  - ' + p);
} else {
  lines.push('PASS: two-block items carry both halves, single-block items unchanged, no geometry problems');
}
console.log(lines.join('\n'));

if (!DRY) {
  fs.mkdirSync(path.dirname(OUT_MD), { recursive: true });
  fs.writeFileSync(OUT_MD, lines.join('\n') + '\n', 'utf8');
}
process.exit(allProblems.length ? 1 : 0);

'use strict';
/*
 * Generates the dual-clearance gate resources from the egate set:
 *   models/block/dual_gate.json      (identical geometry, dual_gate_* textures)
 *   blockstates/dual_gate.json       (8 facing/open variants)
 *   models/item/dual_gate.json       (block parent)
 *   models/item/dual_gate_brush.json (handheld brush item)
 *   loot_tables/blocks/dual_gate.json
 * Texture PNGs are produced by tools/gen_dualgate_textures.ps1.
 *
 *   node tools/gen_dualgate_assets.js
 */
const fs = require('fs');
const path = require('path');

const ROOT = path.resolve(__dirname, '..');
const ASSETS = path.join(ROOT, 'src', 'main', 'resources', 'assets', 'veriborder');
const DATA = path.join(ROOT, 'src', 'main', 'resources', 'data', 'veriborder');

function readJson(file) {
  return JSON.parse(fs.readFileSync(file, 'utf8'));
}
function writeJson(file, value) {
  fs.mkdirSync(path.dirname(file), { recursive: true });
  fs.writeFileSync(file, JSON.stringify(value, null, 2) + '\n', 'utf8');
  console.log('wrote ' + path.relative(ROOT, file));
}

// --- block model: byte-identical geometry, every texture slot dual_gate_* ---
const blockModel = readJson(path.join(ASSETS, 'models', 'block', 'egate.json'));
for (const key of Object.keys(blockModel.textures)) {
  blockModel.textures[key] = blockModel.textures[key].replace('block/egate_', 'block/dual_gate_');
}
writeJson(path.join(ASSETS, 'models', 'block', 'dual_gate.json'), blockModel);

// --- blockstate: same 8 facing/open variants pointing at the new model ------
const blockstate = readJson(path.join(ASSETS, 'blockstates', 'egate.json'));
for (const variant of Object.values(blockstate.variants)) {
  variant.model = variant.model.replace('block/egate', 'block/dual_gate');
}
writeJson(path.join(ASSETS, 'blockstates', 'dual_gate.json'), blockstate);

// --- item model: reuse the gate display transforms --------------------------
const itemModel = readJson(path.join(ASSETS, 'models', 'item', 'egate.json'));
itemModel.parent = 'veriborder:block/dual_gate';
writeJson(path.join(ASSETS, 'models', 'item', 'dual_gate.json'), itemModel);

// --- brush item model -------------------------------------------------------
writeJson(path.join(ASSETS, 'models', 'item', 'dual_gate_brush.json'), {
  parent: 'minecraft:item/handheld',
  textures: { layer0: 'veriborder:item/dual_gate_brush' }
});

// --- loot table -------------------------------------------------------------
const loot = readJson(path.join(DATA, 'loot_tables', 'blocks', 'egate.json'));
for (const pool of loot.pools) {
  for (const entry of pool.entries || []) {
    if (entry.name === 'veriborder:egate') {
      entry.name = 'veriborder:dual_gate';
    }
  }
}
writeJson(path.join(DATA, 'loot_tables', 'blocks', 'dual_gate.json'), loot);

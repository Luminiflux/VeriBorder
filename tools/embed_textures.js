#!/usr/bin/env node
/*
 * Embed texture pixels into .bbmodel files as data URLs.
 *
 * Round 9 references the shared material kit by resource location
 * ("veriborder:block/r9_shell"). That is exactly what the Minecraft converter
 * needs, but Blockbench resolves a texture `path` against the filesystem, so a
 * resource location makes it fail with "Cannot read properties of undefined
 * (reading 'decode')" and the model opens without cubes.
 *
 * Adding a `source` data URL fixes the editor view without touching the
 * conversion: bbmodel_to_mc.js only reads `name` and `path`.
 *
 *   node tools/embed_textures.js [id ...]
 */
'use strict';

const fs = require('fs');
const path = require('path');

const ROOT = path.resolve(__dirname, '..');
const MODELS = path.join(ROOT, 'assets', 'models');
const TEX = path.join(ROOT, 'src', 'main', 'resources', 'assets', 'veriborder', 'textures', 'block');

function resolvePng(id, tex) {
  const declared = String(tex.path || '');
  if (/^[a-z0-9_.-]+:/.test(declared)) {
    const rel = declared.split(':')[1].replace(/^block\//, '');
    return path.join(TEX, rel + '.png');
  }
  return path.join(TEX, `${id}_${tex.name}.png`);
}

let ids = process.argv.slice(2);
if (ids.length === 0) ids = fs.readdirSync(MODELS).filter((f) => f.endsWith('.bbmodel')).map((f) => f.replace(/\.bbmodel$/, ''));

let bad = 0;
for (const id of ids) {
  const file = path.join(MODELS, id + '.bbmodel');
  if (!fs.existsSync(file)) continue;
  const model = JSON.parse(fs.readFileSync(file, 'utf8'));
  let embedded = 0;
  const missing = [];
  for (const tex of model.textures || []) {
    const png = resolvePng(id, tex);
    if (!fs.existsSync(png)) { missing.push(path.relative(ROOT, png)); continue; }
    const b64 = fs.readFileSync(png).toString('base64');
    tex.source = 'data:image/png;base64,' + b64;
    embedded++;
  }
  fs.writeFileSync(file, JSON.stringify(model, null, 2), 'utf8');
  const kb = (fs.statSync(file).size / 1024).toFixed(1);
  console.log(`${id.padEnd(21)} embedded ${embedded}/${(model.textures || []).length} textures, ${kb} KB`);
  for (const m of missing) { console.log('   MISSING ' + m); bad++; }
}
process.exit(bad ? 1 : 0);

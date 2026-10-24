#!/usr/bin/env node
/*
 * Add "gui_light": "front" to the 3D device item models.
 *
 * A model that parents a block model inherits the block's default gui_light
 * ("side"), so the GUI lights a 3D item from the side: one face blows out, the
 * faces facing the camera go dark, and under shaders the hard shading steps read
 * as extra "internal" faces. Flat icons (item/generated, item/handheld) already
 * inherit "front" from the vanilla parent, so only models whose parent is one of
 * our block models are touched.
 *
 *   node tools/fix_item_gui_light.js            # report only
 *   node tools/fix_item_gui_light.js --write    # rewrite models/item/*.json
 *
 * Each file keeps its own formatting (textual insertion, not a re-serialise), so
 * the diff is exactly one added line per model.
 */
'use strict';

const fs = require('fs');
const path = require('path');

const ROOT = path.resolve(__dirname, '..');
const DIR = path.join(ROOT, 'src', 'main', 'resources', 'assets', 'veriborder', 'models', 'item');
const WRITE = process.argv.includes('--write');

let touched = 0;
let already = 0;
let flat = 0;
for (const file of fs.readdirSync(DIR).filter(f => f.endsWith('.json')).sort()) {
  const p = path.join(DIR, file);
  const raw = fs.readFileSync(p, 'utf8');
  let model;
  try {
    model = JSON.parse(raw);
  } catch (e) {
    console.error(`SKIP ${file}: invalid JSON (${e.message})`);
    continue;
  }
  if (model.gui_light) { already++; continue; }
  const parent = model.parent || '';
  if (!/^veriborder:block\//.test(parent)) { flat++; continue; } // inherits front from item/generated

  // "parent" entry: capture indentation, the ': ' / ':  ' gap and the comma
  const re = /^([ \t]*)"parent"(:[ \t]*)("[^"]*")(,?)[ \t]*$/m;
  const m = raw.match(re);
  if (!m) {
    console.error(`SKIP ${file}: could not locate a single-line parent entry`);
    continue;
  }
  const [, indent, gap, literal, comma] = m;
  if (JSON.parse(literal) !== parent) {
    console.error(`SKIP ${file}: parent mismatch`);
    continue;
  }
  const eol = raw.includes('\r\n') ? '\r\n' : '\n'; // keep the file's line endings
  const line = `${indent}"parent"${gap}${literal},`;
  const added = `${indent}"gui_light"${gap}"front"${comma ? ',' : ''}`;
  const next = raw.replace(re, `${line}${eol}${added}`);
  JSON.parse(next); // stays valid
  touched++;
  console.log(`${file.padEnd(30)} parent ${parent}`);
  if (WRITE) fs.writeFileSync(p, next);
}

console.log(`\n3D item models: ${touched} ${WRITE ? 'updated' : 'to update'}, ` +
  `${already} already carried gui_light, ${flat} flat icons (inherit front from vanilla)`);
if (!WRITE && touched) console.log('dry run - pass --write to rewrite the models');

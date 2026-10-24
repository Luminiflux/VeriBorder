#!/usr/bin/env node
/*
 * Front-face audit for the R9 devices (objective, no vision model needed).
 *
 * Rasterises every element's north face onto the X/Y plane at 0.1 px and keeps
 * the front-most face per cell, which approximates what a player sees looking at
 * the machine from the front. Then reports:
 *   - visible dark (r9_dark) share of the visible front area
 *   - visible screen share (reported separately: screens are functional, not casing)
 *   - the largest label face (width x height in px) and its element name
 *   - element count and total height
 *
 *   node tools/check_front_ratio.js id_issuer passport_issuer ...
 */
'use strict';

const fs = require('fs');
const path = require('path');

const ROOT = path.resolve(__dirname, '..');
const MODEL_DIR = path.join(ROOT, 'src', 'main', 'resources', 'assets', 'veriborder', 'models', 'block');
const STEP = 0.1;

function audit(id) {
  const model = JSON.parse(fs.readFileSync(path.join(MODEL_DIR, id + '.json'), 'utf8'));
  const els = model.elements || [];
  let minX = Infinity, maxX = -Infinity, minY = Infinity, maxY = -Infinity;
  for (const e of els) {
    minX = Math.min(minX, e.from[0]); maxX = Math.max(maxX, e.to[0]);
    minY = Math.min(minY, e.from[1]); maxY = Math.max(maxY, e.to[1]);
  }
  const W = Math.ceil((maxX - minX) / STEP);
  const H = Math.ceil((maxY - minY) / STEP);
  const depth = new Float32Array(W * H).fill(Infinity);
  const tex = new Array(W * H).fill(null);

  for (const e of els) {
    const f = e.faces && e.faces.north;
    if (!f) continue;
    const z = e.from[2];
    const x1 = Math.max(0, Math.round((e.from[0] - minX) / STEP));
    const x2 = Math.min(W, Math.round((e.to[0] - minX) / STEP));
    const y1 = Math.max(0, Math.round((e.from[1] - minY) / STEP));
    const y2 = Math.min(H, Math.round((e.to[1] - minY) / STEP));
    for (let y = y1; y < y2; y++) {
      for (let x = x1; x < x2; x++) {
        const i = y * W + x;
        if (z < depth[i] - 1e-6) { depth[i] = z; tex[i] = f.texture; }
      }
    }
  }

  const counts = new Map();
  let covered = 0;
  for (let i = 0; i < tex.length; i++) {
    if (tex[i] === null) continue;
    covered++;
    counts.set(tex[i], (counts.get(tex[i]) || 0) + 1);
  }
  const pct = (t) => ((counts.get(t) || 0) / Math.max(1, covered)) * 100;

  // biggest label face
  let label = { w: 0, h: 0, name: '' };
  for (const e of els) {
    const f = e.faces && e.faces.north;
    if (!f || f.texture !== '#label') continue;
    const w = e.to[0] - e.from[0];
    const h = e.to[1] - e.from[1];
    if (w * h > label.w * label.h) label = { w, h, name: e.name };
  }

  const height = maxY - minY;
  console.log(
    `${id.padEnd(21)} h=${height.toFixed(1).padStart(5)}  els=${String(els.length).padStart(2)}  ` +
    `dark=${pct('#dark').toFixed(1).padStart(5)}%  screen=${pct('#screen').toFixed(1).padStart(5)}%  ` +
    `shell+silver=${(100 - pct('#dark') - pct('#screen')).toFixed(1).padStart(5)}%  ` +
    `label=${label.w.toFixed(1)}x${label.h.toFixed(1)}px (${label.name})`
  );
  return { id, height, els: els.length, dark: pct('#dark'), screen: pct('#screen'), label };
}

const ids = process.argv.slice(2);
const list = ids.length ? ids : ['id_issuer', 'passport_issuer', 'permit_issuer', 'visa_issuer',
  'endorsement_machine', 'self_visa', 'self_endorsement'];
let bad = 0;
for (const id of list) {
  const r = audit(id);
  if (r.els < 14) { console.log('   FAIL <14 elements'); bad++; }
  if (r.dark > 25) { console.log('   FAIL dark front share > 25%'); bad++; }
  if (r.label.w < 8 || r.label.h < 3) { console.log('   FAIL label face < 8x3 px'); bad++; }
}
console.log(bad ? `${bad} check(s) failed` : 'front audit: all checks pass');
process.exit(bad ? 1 : 0);

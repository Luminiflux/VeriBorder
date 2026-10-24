#!/usr/bin/env node
/**
 * R9 terminal QA: front-elevation composition check.
 *
 *   node tools/qa_r9_front.js [id ...]
 *
 * Reads the Blockbench sources (assets/models/<id>.bbmodel) and rasterises the
 * north (front) elevation on a fine grid, painter-sorted by depth. Reports:
 *   - element count, height, footprint
 *   - share of the visible front area that is dark (r9_dark / r9_screen):
 *     ROUND9 acceptance wants < 25 % so the casing reads matte off-white
 *   - label panel size (>= 8 x 3 px)
 *   - three-layer screen (bezel / shadow ring / recessed face) and its recess
 *   - material mix of the front elevation
 *
 * The grid covers y 0..32 px because ROUND9 devices may be two blocks tall.
 * Read-only: it never writes a model.
 */
'use strict';
const fs = require('fs');
const path = require('path');

const ROOT = path.resolve(__dirname, '..');
const MODEL_DIR = path.join(ROOT, 'assets', 'models');
const N = 256;            // samples across 16 px of width
const HMAX = 32;          // px of height covered (2 blocks)
const H = N * HMAX / 16;  // 512 rows

const ids = process.argv.slice(2).length
  ? process.argv.slice(2)
  : fs.readdirSync(MODEL_DIR).filter(f => f.endsWith('.bbmodel')).map(f => f.slice(0, -8)).sort();

function matOf(ref) {
  const s = String(ref || '');
  const m = s.match(/r9_([a-z0-9_]+)$/);
  if (m) return m[1];
  if (/_label$/.test(s)) return 'label';
  return s.replace(/^.*:/, '').replace(/^block\//, '');
}

function analyze(id) {
  const model = JSON.parse(fs.readFileSync(path.join(MODEL_DIR, id + '.bbmodel'), 'utf8'));
  const tex = {};
  for (const t of (model.textures || [])) tex[String(t.id)] = t.path || t.name;
  const els = model.elements || [];
  const elIndex = new Map(els.map((e, i) => [e, i]));

  const matOfEl = el => {
    const f = el.faces && el.faces.north;
    if (!f || f.texture === undefined) return null;
    return matOf(tex[String(f.texture).replace(/^#/, '')]);
  };

  const grid = new Int16Array(N * H).fill(-1);
  const matArea = {};
  let total = 0;

  // far (large z) first, near last, so the nearest surface owns each cell
  const order = els.map((el, i) => ({ el, i, z: el.from[2] })).sort((a, b) => b.z - a.z);
  for (const { el } of order) {
    const mat = matOfEl(el);
    if (!mat) continue;
    const x1 = Math.max(0, Math.round(el.from[0] / 16 * N));
    const x2 = Math.min(N, Math.round(el.to[0] / 16 * N));
    const y1 = Math.max(0, Math.round((HMAX - el.to[1]) / 16 * N));
    const y2 = Math.min(H, Math.round((HMAX - el.from[1]) / 16 * N));
    const idx = elIndex.get(el);
    for (let y = y1; y < y2; y++) {
      for (let x = x1; x < x2; x++) {
        const k = y * N + x;
        if (grid[k] !== -1) {
          const prev = matOfEl(els[grid[k]]);
          if (prev) matArea[prev] -= 1;
        } else total++;
        grid[k] = idx;
        matArea[mat] = (matArea[mat] || 0) + 1;
      }
    }
  }

  // the shared screen texture is near-black, so every screen_* slot counts as dark
  const dark = Object.entries(matArea).filter(([k]) => k === 'dark' || k.startsWith('screen')).reduce((a, [, v]) => a + v, 0);
  const labelEls = els.filter(el => matOfEl(el) === 'label');
  const label = labelEls[0];
  const labelSize = label ? [label.to[0] - label.from[0], label.to[1] - label.from[1]] : [0, 0];

  const byName = n => els.filter(e => e.name === n);
  const bezel = els.filter(e => /^bezel/.test(e.name)).length;
  const shadow = byName('screen_shadow').length > 0;
  const face = els.find(e => /screen_face|screen_plate|platen_face/.test(e.name));
  const bezTop = byName('bezel_top')[0];
  const recess = (face && bezTop) ? +(face.from[2] - bezTop.from[2]).toFixed(2) : null;

  const minY = Math.min(...els.map(e => e.from[1]));
  const maxY = Math.max(...els.map(e => e.to[1]));
  const xs = [Math.min(...els.map(e => e.from[0])), Math.max(...els.map(e => e.to[0]))];
  const zs = [Math.min(...els.map(e => e.from[2])), Math.max(...els.map(e => e.to[2]))];

  return {
    id,
    elements: els.length,
    height: +(maxY - minY).toFixed(2),
    footprint: `${(xs[1] - xs[0]).toFixed(1)} x ${(zs[1] - zs[0]).toFixed(1)}`,
    darkPct: total ? +(100 * dark / total).toFixed(1) : 0,
    label: `${labelSize[0].toFixed(1)} x ${labelSize[1].toFixed(1)}`,
    labelOk: labelSize[0] >= 8 - 1e-6 && labelSize[1] >= 3 - 1e-6,
    screenLayers: { bezel, shadow, face: !!face, recess },
    mats: Object.fromEntries(Object.entries(matArea).filter(([, v]) => v > 0)
      .sort((a, b) => b[1] - a[1]).map(([k, v]) => [k, +(100 * v / total).toFixed(1)]))
  };
}

const out = ids.map(id => {
  try { return analyze(id); } catch (e) { return { id, error: e.message }; }
});

const fails = [];
for (const r of out) {
  if (r.error) { fails.push(`${r.id}: ${r.error}`); continue; }
  if (r.darkPct >= 25) fails.push(`${r.id}: front dark area ${r.darkPct}% (limit 25%)`);
  if (!r.labelOk) fails.push(`${r.id}: label ${r.label} (need >= 8 x 3)`);
  if (!r.screenLayers.shadow || !r.screenLayers.face || r.screenLayers.bezel < 2) {
    fails.push(`${r.id}: screen is not three-layer (bezel/shadow/face)`);
  }
  if (r.elements < 14) fails.push(`${r.id}: only ${r.elements} elements (need >= 14)`);
}

for (const r of out) {
  if (r.error) { console.log(`${r.id}: ERROR ${r.error}`); continue; }
  console.log(
    `${r.id.padEnd(20)} els ${String(r.elements).padStart(2)}  h ${String(r.height).padStart(5)}  ` +
    `foot ${r.footprint.padEnd(11)} front-dark ${String(r.darkPct).padStart(5)}%  label ${r.label.padEnd(9)} ` +
    `bezel ${r.screenLayers.bezel} recess ${r.screenLayers.recess}  mats ${JSON.stringify(r.mats)}`
  );
}
console.log('');
console.log(fails.length ? `QA FAILURES: ${fails.length}\n  - ` + fails.join('\n  - ') : 'QA FAILURES: none');
process.exit(fails.length ? 1 : 0);

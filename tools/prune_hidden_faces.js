#!/usr/bin/env node
/*
 * VeriBorder hidden-face / hidden-element pruner (shader see-through cleanup).
 *
 *   node tools/prune_hidden_faces.js            # report only (dry run)
 *   node tools/prune_hidden_faces.js --write    # rewrite models/block/*.json
 *
 * A Minecraft element always exposes six faces; the generators emit all six even
 * where a face sits *inside* a neighbouring element or coincides with another
 * element's face. Those faces can never be seen in vanilla -- they are pure
 * overdraw, and they are what shader packs surface as "internal" geometry when
 * the depth pre-pass / translucent pass ordering slips. The 22 device block
 * models are also the parents of the 22 3D device item models, so the same
 * overdraw shows up in the GUI/hand item render.
 *
 * A face is dropped only when it is provably invisible:
 *   (a) BURIED   - another opaque element's volume strictly contains the face
 *                  plane and covers the whole face rectangle (0.01 px tolerance),
 *   (b) COPLANAR - another element owns the exact same plane with the same
 *                  facing and covers the whole rectangle (a z-fighting pair).
 * An element whose six faces are all BURIED is fully enclosed: the element is
 * removed instead of being left face-less.
 *
 * Safety rules:
 *   * rotated elements are never used as occluders and never pruned (their AABB
 *     is not their volume),
 *   * an element covered by a translucent texture (#glass/#leaf/#tablet) is not
 *     an occluder and does not hide anything -- you can see through it,
 *   * the element count never drops below what tools/preview_models.js requires
 *     (20 for the *_gate models, 10 otherwise).
 */
'use strict';

const fs = require('fs');
const path = require('path');

const ROOT = path.resolve(__dirname, '..');
const DIR = process.argv.find(a => a.startsWith('--root='))
  ? path.resolve(process.argv.find(a => a.startsWith('--root=')).slice('--root='.length),
    'src', 'main', 'resources', 'assets', 'veriborder', 'models', 'block')
  : path.join(ROOT, 'src', 'main', 'resources', 'assets', 'veriborder', 'models', 'block');
const WRITE = process.argv.includes('--write');
const EPS = 0.01;
const TRANSLUCENT = new Set(['#glass', '#leaf', '#tablet']);

const FACES = {
  north: { axis: 2, sign: -1 },
  south: { axis: 2, sign: 1 },
  west: { axis: 0, sign: -1 },
  east: { axis: 0, sign: 1 },
  down: { axis: 1, sign: -1 },
  up: { axis: 1, sign: 1 }
};

function box(el) {
  const b = { min: [], max: [] };
  for (let k = 0; k < 3; k++) {
    b.min[k] = Math.min(el.from[k], el.to[k]);
    b.max[k] = Math.max(el.from[k], el.to[k]);
  }
  return b;
}
function planeOf(b, face) {
  const { axis, sign } = FACES[face];
  return sign > 0 ? b.max[axis] : b.min[axis];
}
function planeAxes(axis) {
  return [(axis + 1) % 3, (axis + 2) % 3];
}
function covers(a, b, axis) {
  const [p, q] = planeAxes(axis);
  return a.min[p] <= b.min[p] + EPS && a.max[p] >= b.max[p] - EPS &&
         a.min[q] <= b.min[q] + EPS && a.max[q] >= b.max[q] - EPS;
}
function isTranslucent(el) {
  return Object.values(el.faces || {}).some(f => TRANSLUCENT.has(f.texture));
}

function analyze(model, minElements) {
  const els = (model.elements || []).map((el, i) => ({ el, i, box: box(el), rotated: !!el.rotation }));
  const report = [];
  for (const cur of els) {
    if (cur.rotated) continue;
    const b = cur.box;
    const faces = cur.el.faces || {};
    const names = Object.keys(faces);
    const hidden = [];
    for (const face of names) {
      const meta = FACES[face];
      if (!meta) continue; // unknown face name -> leave it alone
      const plane = planeOf(b, face);
      for (const other of els) {
        if (other.i === cur.i || other.rotated || isTranslucent(other.el)) continue;
        const o = other.box;
        // (a) BURIED: the face plane lies strictly inside the other element
        if (o.min[meta.axis] + EPS < plane && plane < o.max[meta.axis] - EPS) {
          if (covers(o, b, meta.axis)) { hidden.push({ face, reason: 'BURIED', by: other.i }); break; }
        }
        // (b) COPLANAR: identical plane, same facing, full coverage
        if (Math.abs(planeOf(o, face) - plane) <= EPS && covers(o, b, meta.axis)) {
          hidden.push({ face, reason: 'COPLANAR', by: other.i });
          break;
        }
      }
    }
    if (!hidden.length) continue;
    if (hidden.length === names.length) {
      report.push({ element: cur.i, drop: true, faces: hidden });
    } else {
      report.push({ element: cur.i, drop: false, faces: hidden });
    }
  }
  const drops = report.filter(r => r.drop).map(r => r.element);
  if (model.elements.length - drops.length < minElements) {
    // cannot drop whole elements without breaking the model-shape gate; keep
    // their faces (they are invisible either way, this is only about overdraw)
    for (const r of report) if (r.drop) { r.drop = false; r.kept = true; }
  }
  return report;
}

let facesBefore = 0;
let facesAfter = 0;
let dropped = 0;
const touched = [];
const files = fs.readdirSync(DIR).filter(f => f.endsWith('.json')).sort();
for (const f of files) {
  const p = path.join(DIR, f);
  const model = JSON.parse(fs.readFileSync(p, 'utf8'));
  if (!Array.isArray(model.elements) || !model.elements.length) continue;
  const minElements = /gate/i.test(f) ? 20 : 10;
  const report = analyze(model, minElements);
  if (!report.length) continue;

  const faces = model.elements.reduce((n, e) => n + Object.keys(e.faces || {}).length, 0);
  const pruned = report.filter(r => !r.drop).reduce((s, r) => s + r.faces.length, 0);
  const removedEls = report.filter(r => r.drop).length;
  const hiddenEls = report.filter(r => r.kept).length;
  facesBefore += faces;
  facesAfter += faces - pruned - report.filter(r => r.drop).reduce((s, r) => s + r.faces.length, 0);
  touched.push({ f, faces, pruned, removedEls, hiddenEls });
  console.log(`${f.padEnd(26)} faces ${String(faces).padStart(3)}  prune ${String(pruned).padStart(2)} face(s)` +
    (removedEls ? `  drop ${removedEls} fully enclosed element(s)` : '') +
    (hiddenEls ? `  keep ${hiddenEls} enclosed element(s) (min-element gate)` : ''));
  for (const r of report.slice(0, 8)) {
    const what = r.drop ? 'drop-element' : (r.kept ? 'keep-element' : 'prune-faces');
    console.log(`    el ${String(r.element).padStart(2)}  ${what.padEnd(15)} ` +
      r.faces.map(h => h.face).join(','));
  }
  if (report.length > 8) console.log(`    ... ${report.length - 8} more`);
  if (!WRITE) continue;

  // prune faces first (indices are still the original ones), then drop elements
  for (const r of report) {
    if (r.drop) continue;
    for (const h of r.faces) delete model.elements[r.element].faces[h.face];
  }
  const drops = report.filter(r => r.drop).map(r => r.element).sort((a, b) => b - a);
  for (const i of drops) { model.elements.splice(i, 1); dropped++; }
  fs.writeFileSync(p, JSON.stringify(model, null, 2) + '\n');
}

if (!touched.length) {
  console.log('clean: every model already carries only visible faces');
} else if (WRITE) {
  console.log(`\n${touched.length} model(s) rewritten: ${facesBefore} -> ${facesAfter} faces` +
    (dropped ? `, ${dropped} fully enclosed element(s) removed` : ''));
} else {
  console.log('\ndry run - pass --write to rewrite the models');
}

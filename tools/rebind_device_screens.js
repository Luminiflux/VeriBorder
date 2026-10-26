#!/usr/bin/env node
'use strict';
/*
 * VeriBorder 26.4.1 — rebind each non-gate device's `screen` texture.
 *
 *   node tools/rebind_device_screens.js          # apply
 *   node tools/rebind_device_screens.js --dry    # report only
 *
 * WHY
 * ---
 * Every one of the 19 non-gate block models declared
 *     "screen": "veriborder:block/r9_screen_ui"
 * so all 19 terminals rendered the exact same panel. tools/gen_device_screens.js
 * draws 19 distinct screens; this script points each model at its own one.
 *
 * SCOPE (deliberate, and verified by tools/verify_screen_changes.js)
 *   - only the VALUE of a texture entry named `screen` / `screen2` / `tablet`
 *   - only when it currently points at a shared r9_screen* texture
 *   - `elements`, UVs, blockstates and Java are never touched
 *   - r9_screen_ui.png is left on disk (other assets may still reference it)
 *
 * The rewrite is a single-line string replacement so the git diff stays one
 * line per file instead of a whole-file JSON re-serialisation.
 */

const fs = require('fs');
const path = require('path');

const ROOT = path.resolve(__dirname, '..');
const MODEL_DIR = path.join(ROOT, 'src', 'main', 'resources', 'assets', 'veriborder', 'models', 'block');
const TEX_DIR = path.join(ROOT, 'src', 'main', 'resources', 'assets', 'veriborder', 'textures', 'block');
const DRY = process.argv.includes('--dry');

// Non-gate devices only. Gates (egate / exit_gate / dual_gate) own their screens.
const DEVICES = [
  'airport_checkin', 'blacklist_terminal', 'desk_scanner', 'endorsement_machine',
  'entry_stamp', 'exit_stamp', 'hotel_reader', 'id_issuer', 'loss_terminal',
  'manual_terminal', 'passport_issuer', 'permit_issuer', 'print_preview',
  'renewal_machine', 'self_endorsement', 'self_visa', 'stamp_history',
  'station_verifier', 'visa_issuer'
];

// Texture keys that drive a screen (and are safe to repoint per device).
const SCREEN_KEYS = ['screen', 'screen2', 'tablet'];
// Shared textures a device must not keep pointing at.
const SHARED = /veriborder:block\/r9_screen[a-z0-9_]*/;

const plan = [];
const problems = [];

for (const id of DEVICES) {
  const file = path.join(MODEL_DIR, id + '.json');
  if (!fs.existsSync(file)) { problems.push(`${id}: model missing`); continue; }
  let src = fs.readFileSync(file, 'utf8');
  const before = src;
  const changes = [];

  for (const key of SCREEN_KEYS) {
    // "screen": "veriborder:block/r9_screen_ui"
    const re = new RegExp(`("${key}"\\s*:\\s*")(veriborder:block/r9_screen[a-z0-9_]*)(")`);
    const m = src.match(re);
    if (!m) continue;

    // screen2 / tablet keep their suffix so the pairing survives
    const suffix = key === 'screen' ? '' : key.replace(/^screen/, '');
    const targetId = key === 'tablet' ? `${id}_tablet` : `${id}_screen${suffix}`;
    const targetRef = `veriborder:block/${targetId}`;
    const texFile = path.join(TEX_DIR, targetId + '.png');
    if (!fs.existsSync(texFile)) {
      problems.push(`${id}: ${key} -> ${targetId}.png does not exist (texture not generated)`);
      continue;
    }
    src = src.replace(re, `$1${targetRef}$3`);
    changes.push({ key, from: m[2], to: targetRef });
  }

  if (!changes.length) { problems.push(`${id}: no shared screen texture found to rebind`); continue; }
  if (src === before) continue;
  plan.push({ id, file, src, changes });
}

if (problems.length) {
  console.error('PROBLEMS:');
  for (const p of problems) console.error('  - ' + p);
  process.exitCode = 1;
}

for (const p of plan) {
  for (const c of p.changes) {
    console.log(`${p.id.padEnd(22)} ${c.key.padEnd(8)} ${c.from}  ->  ${c.to}`);
  }
  if (!DRY) fs.writeFileSync(p.file, p.src);
}

// --------------------------------------------------------------- post-check
// Confirm nothing in the resource tree still routes a device screen through the
// shared panel (item models / other blocks are reported, not silently changed).
const leftovers = [];
const planned = new Map(plan.map((p) => [p.file, p.src])); // dry-run sees the new content too
function walk(dir) {
  for (const e of fs.readdirSync(dir, { withFileTypes: true })) {
    const full = path.join(dir, e.name);
    if (e.isDirectory()) walk(full);
    else if (e.name.endsWith('.json')) {
      const t = planned.has(full) ? planned.get(full) : fs.readFileSync(full, 'utf8');
      if (SHARED.test(t)) {
        const hits = t.split('\n').filter((l) => SHARED.test(l));
        leftovers.push({ file: path.relative(ROOT, full), hits });
      }
    }
  }
}
walk(path.join(ROOT, 'src', 'main', 'resources'));

console.log(`\n${DRY ? '[dry] would rebind' : 'rebound'} ${plan.length} models, ${plan.reduce((n, p) => n + p.changes.length, 0)} texture keys.`);
if (leftovers.length) {
  console.log('\nStill referencing a shared r9_screen* texture:');
  for (const l of leftovers) console.log(`  ${l.file}  (${l.hits.length} line(s))`);
} else {
  console.log('No remaining r9_screen* references under src/main/resources.');
}

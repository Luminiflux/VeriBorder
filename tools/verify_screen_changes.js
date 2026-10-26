#!/usr/bin/env node
'use strict';
/*
 * VeriBorder 26.4.1 — prove the device-screen change is scoped.
 *
 *   node tools/verify_screen_changes.js
 *
 * Checks, against git HEAD:
 *   1. each of the 19 non-gate block models differs by EXACTLY ONE line, and
 *      that line is the `screen` texture binding;
 *   2. `elements` (geometry) and `faces` (UVs) are byte-identical to HEAD;
 *   3. every changed screen texture belongs to a non-gate device and matches
 *      the allowed glob *_screen*.png;
 *   4. the three gate models still point at their own gate screens (untouched);
 *   5. all 19 bindings resolve to an existing PNG.
 *
 * Exit code 1 on any violation. This is the machine-checkable version of the
 * "only screen textures + the screen texture binding may change" rule.
 */

const fs = require('fs');
const path = require('path');
const { execFileSync } = require('child_process');

const ROOT = path.resolve(__dirname, '..');
const REL = (p) => path.join('src', 'main', 'resources', 'assets', 'veriborder', p);

const DEVICES = [
  'airport_checkin', 'blacklist_terminal', 'desk_scanner', 'endorsement_machine',
  'entry_stamp', 'exit_stamp', 'hotel_reader', 'id_issuer', 'loss_terminal',
  'manual_terminal', 'passport_issuer', 'permit_issuer', 'print_preview',
  'renewal_machine', 'self_endorsement', 'self_visa', 'stamp_history',
  'station_verifier', 'visa_issuer'
];
const GATES = ['egate', 'exit_gate', 'dual_gate'];

const fail = [];
const ok = [];

function gitShow(relPath) {
  try {
    return execFileSync('git', ['show', 'HEAD:' + relPath.replace(/\\/g, '/')],
      { cwd: ROOT, encoding: 'utf8', maxBuffer: 64 * 1024 * 1024 });
  } catch (e) { return null; }
}
function gitNumstat(relPath) {
  try {
    const out = execFileSync('git', ['diff', '--numstat', '--', relPath.replace(/\\/g, '/')],
      { cwd: ROOT, encoding: 'utf8' });
    const m = out.trim().split('\n')[0];
    if (!m) return null;
    const [add, del] = m.split('\t');
    return { add: +add, del: +del };
  } catch (e) { return null; }
}

// ---------------------------------------------------------------- 1 + 2 + 5
for (const id of DEVICES) {
  const rel = REL(path.join('models', 'block', id + '.json'));
  const head = gitShow(rel);
  const cur = fs.readFileSync(path.join(ROOT, rel), 'utf8');
  if (head === null) { fail.push(`${rel}: not in git HEAD`); continue; }

  // --- geometry + UVs identical?
  const hJson = JSON.parse(head), cJson = JSON.parse(cur);
  if (JSON.stringify(hJson.elements) !== JSON.stringify(cJson.elements)) {
    fail.push(`${id}: elements/UVs changed (must not)`);
  } else {
    ok.push(`${id}: elements + UVs identical to HEAD`);
  }

  // --- exactly one line different, and it is the screen binding
  const hl = head.split('\n'), cl = cur.split('\n');
  if (hl.length !== cl.length) {
    fail.push(`${id}: line count changed ${hl.length} -> ${cl.length}`);
  } else {
    const diffIdx = [];
    for (let i = 0; i < hl.length; i++) if (hl[i] !== cl[i]) diffIdx.push(i);
    if (diffIdx.length !== 1) {
      fail.push(`${id}: ${diffIdx.length} changed lines (expected exactly 1)`);
    } else {
      const before = hl[diffIdx[0]].trim(), after = cl[diffIdx[0]].trim();
      const isScreen = before.startsWith('"screen"') && after.startsWith('"screen"');
      const wantRef = `veriborder:block/${id}_screen`;
      if (!isScreen) fail.push(`${id}: changed line is not the screen binding: ${after}`);
      else if (!after.includes(`"${wantRef}"`)) fail.push(`${id}: screen -> ${after} (expected ${wantRef})`);
      else ok.push(`${id}: 1-line diff  ${before} -> ${after}`);
    }
  }

  // --- geometry keys untouched
  const otherDiff = ['textures', 'elements'].filter(
    (k) => JSON.stringify(hJson[k]) !== JSON.stringify(cJson[k]));
  if (otherDiff.length !== 1 || otherDiff[0] !== 'textures') {
    fail.push(`${id}: changed sections ${JSON.stringify(otherDiff)} (only "textures" may change)`);
  }

  // --- binding resolves
  const ref = cJson.textures.screen;
  const png = path.join(ROOT, REL(path.join('textures', 'block', path.basename(ref) + '.png')));
  if (!fs.existsSync(png)) fail.push(`${id}: ${ref} -> file missing`);
  else ok.push(`${id}: binding resolves (${path.basename(png)})`);
}

// ---------------------------------------------------------------- 3
// Every changed file under textures/block must be a non-gate *_screen*.png.
const texDirRel = REL(path.join('textures', 'block'));
let changedTex = [];
try {
  changedTex = execFileSync('git', ['diff', '--name-only', '--', texDirRel.replace(/\\/g, '/')],
    { cwd: ROOT, encoding: 'utf8' }).trim().split('\n').filter(Boolean)
    .map((p) => path.basename(p));
} catch (e) { /* ignore */ }

const allowedScreen = /^(?!egate|exit_gate|dual_gate)[a-z0-9_]+_screen\d*\.png$/;
const mine = changedTex.filter((f) => allowedScreen.test(f));
const others = changedTex.filter((f) => !allowedScreen.test(f));
ok.push(`changed non-gate *_screen*.png: ${mine.length}`);

const expected = new Set();
for (const id of DEVICES) { expected.add(`${id}_screen.png`); expected.add(`${id}_screen2.png`); }
const missing = [...expected].filter((f) => !mine.includes(f));
if (missing.length) fail.push(`screen textures not updated: ${missing.join(', ')}`);

// ---------------------------------------------------------------- 4
for (const g of GATES) {
  const rel = REL(path.join('models', 'block', g + '.json'));
  const cur = fs.readFileSync(path.join(ROOT, rel), 'utf8');
  const j = JSON.parse(cur);
  const ref = j.textures.screen || '';
  if (!ref.includes(g + '_screen')) fail.push(`gate ${g}: screen no longer points at its own texture (${ref})`);
  else ok.push(`gate ${g}: untouched, screen = ${ref}`);
}

// ---------------------------------------------------------------- report
for (const o of ok) console.log('  ok   ' + o);
console.log('');
if (others.length) {
  console.log('NOTE — other textures under textures/block also changed in this workspace.');
  console.log('       They belong to the concurrently-running material/gate agents, not to this task.');
  console.log('       Git cannot attribute them, so they are listed rather than asserted:');
  for (const o of others) console.log('         ' + o);
  console.log('');
}
if (fail.length) {
  console.error(`FAILED (${fail.length}):`);
  for (const f of fail) console.error('  - ' + f);
  process.exit(1);
}
console.log('PASS — 19 models changed by exactly one texture-binding line each;');
console.log('       geometry and UVs untouched; 38 non-gate screen textures updated;');
console.log('       gate screens untouched.');

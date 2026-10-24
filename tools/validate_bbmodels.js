#!/usr/bin/env node
/*
 * Compact batch validation for .bbmodel files.
 *
 * Uses the exact same validator implementation as the dsh-blockbench
 * `blockbench_validate` tool (node_modules/dsh-blockbench/lib/bbmodel.js), but
 * prints only errors/warnings so a whole device set can be checked in one go
 * without dumping every element of every model.
 *
 *   node tools/validate_bbmodels.js id_issuer passport_issuer ...
 *   node tools/validate_bbmodels.js --all
 */
'use strict';

const fs = require('fs');
const path = require('path');
const { pathToFileURL } = require('url');

const ROOT = path.resolve(__dirname, '..');
const MODEL_DIR = path.join(ROOT, 'assets', 'models');
const PLUGIN = path.join(
  process.env.USERPROFILE || process.env.HOME || '',
  '.dsh', 'profiles', 'web', 'node_modules', 'dsh-blockbench', 'lib', 'bbmodel.js'
);

(async () => {
  if (!fs.existsSync(PLUGIN)) {
    console.error('validator module not found: ' + PLUGIN);
    process.exit(2);
  }
  const { validateModel } = await import(pathToFileURL(PLUGIN).href);

  let ids = process.argv.slice(2);
  if (ids.length === 0 || ids[0] === '--all') {
    ids = fs.readdirSync(MODEL_DIR)
      .filter((f) => f.endsWith('.bbmodel'))
      .map((f) => f.replace(/\.bbmodel$/i, ''));
  }

  let bad = 0;
  for (const id of ids) {
    const file = path.join(MODEL_DIR, id + '.bbmodel');
    if (!fs.existsSync(file)) { console.log(`${id.padEnd(22)} MISSING .bbmodel`); bad++; continue; }
    const model = JSON.parse(fs.readFileSync(file, 'utf8'));
    const r = validateModel(model, MODEL_DIR);
    const els = (model.elements || []).length;
    const ok = r.passed && r.warnings.length === 0;
    if (!ok) bad++;
    console.log(
      `${id.padEnd(22)} elements=${String(els).padStart(2)}  errors=${r.errors.length}  warnings=${r.warnings.length}  ${ok ? 'CLEAN' : 'PROBLEM'}`
    );
    for (const e of r.errors) console.log('   ERROR   ' + e);
    for (const w of r.warnings) console.log('   WARN    ' + w);
  }
  console.log(bad === 0 ? 'ALL CLEAN' : `${bad} model(s) with problems`);
  process.exit(bad === 0 ? 0 : 1);
})();

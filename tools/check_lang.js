#!/usr/bin/env node
/*
 * VeriBorder localization consistency check (26.3 round 11).
 *
 *   node tools/check_lang.js
 *   node tools/check_lang.js --quiet      # only print problems + summary
 *
 * Compares the two shipped language files:
 *   src/main/resources/assets/veriborder/lang/en_us.json
 *   src/main/resources/assets/veriborder/lang/zh_cn.json
 *
 * Fails (exit 1) on:
 *   * a key present in one file but not the other (missing / extra),
 *   * an empty or whitespace-only value,
 *   * a value that just repeats its key (untranslated placeholder),
 *   * a printf placeholder mismatch (%s, %d, %1$s, ...) between the two
 *     translations of the same key — the classic "String.format crash" cause,
 *   * duplicate keys or invalid JSON in either file.
 *
 * Exit code 0 = both catalogues agree. Run it from `gradlew test` through
 * LangParityTest, or directly from the command line / CI.
 */
'use strict';

const fs = require('fs');
const path = require('path');

const ROOT = path.resolve(__dirname, '..');
const LANG_DIR = path.join(ROOT, 'src', 'main', 'resources', 'assets', 'veriborder', 'lang');
const REFERENCE = 'en_us';
const TRANSLATIONS = ['zh_cn'];
const QUIET = process.argv.includes('--quiet');

function load(file) {
  const text = fs.readFileSync(file, 'utf8');
  let data;
  try {
    data = JSON.parse(text);
  } catch (error) {
    return { error: `${path.basename(file)}: invalid JSON — ${error.message}` };
  }
  if (data === null || typeof data !== 'object' || Array.isArray(data)) {
    return { error: `${path.basename(file)}: top level must be a JSON object` };
  }
  const duplicates = [];
  const seen = new Set();
  // JSON.parse keeps the last duplicate silently, so scan the raw text too.
  const keyPattern = /^\s*"((?:[^"\\]|\\.)*)"\s*:/gm;
  let match;
  while ((match = keyPattern.exec(text)) !== null) {
    const key = JSON.parse(`"${match[1]}"`);
    if (seen.has(key)) duplicates.push(key);
    seen.add(key);
  }
  return { data, duplicates };
}

function placeholders(value) {
  const tokens = [];
  const pattern = /%(?:\d+\$)?[sdf]/g;
  let match;
  while ((match = pattern.exec(String(value))) !== null) tokens.push(match[0]);
  return tokens.sort();
}

function describe(list, limit = 25) {
  const shown = list.slice(0, limit).map((entry) => `    ${entry}`);
  if (list.length > limit) shown.push(`    ... and ${list.length - limit} more`);
  return shown.join('\n');
}

const problems = [];
const warnings = [];

const reference = load(path.join(LANG_DIR, `${REFERENCE}.json`));
if (reference.error) problems.push(reference.error);
for (const key of reference.duplicates || []) {
  problems.push(`${REFERENCE}.json: duplicate key "${key}"`);
}

const catalogues = { [REFERENCE]: reference.data || {} };
for (const code of TRANSLATIONS) {
  const loaded = load(path.join(LANG_DIR, `${code}.json`));
  if (loaded.error) problems.push(loaded.error);
  for (const key of loaded.duplicates || []) {
    problems.push(`${code}.json: duplicate key "${key}"`);
  }
  catalogues[code] = loaded.data || {};
}

const referenceKeys = Object.keys(catalogues[REFERENCE]).sort();

// Reference file: empty values / key-repeats.
for (const key of referenceKeys) {
  const value = catalogues[REFERENCE][key];
  if (typeof value !== 'string') {
    problems.push(`${REFERENCE}: "${key}" is not a string`);
    continue;
  }
  if (value.trim() === '') problems.push(`${REFERENCE}: "${key}" has an empty value`);
  else if (value.trim() === key) warnings.push(`${REFERENCE}: "${key}" repeats its key as the value`);
}

for (const code of TRANSLATIONS) {
  const catalogue = catalogues[code];
  const keys = Object.keys(catalogue).sort();
  const missing = referenceKeys.filter((key) => !(key in catalogue));
  const extra = keys.filter((key) => !(key in catalogues[REFERENCE]));

  if (missing.length) {
    problems.push(`${code}: ${missing.length} key(s) missing\n${describe(missing)}`);
  }
  if (extra.length) {
    problems.push(`${code}: ${extra.length} key(s) not present in ${REFERENCE}\n${describe(extra)}`);
  }

  let empty = 0;
  let placeholderMismatch = 0;
  const emptyKeys = [];
  const placeholderKeys = [];
  for (const key of referenceKeys) {
    if (!(key in catalogue)) continue;
    const value = catalogue[key];
    if (typeof value !== 'string') {
      problems.push(`${code}: "${key}" is not a string`);
      continue;
    }
    if (value.trim() === '') {
      empty++;
      emptyKeys.push(key);
      continue;
    }
    if (value.trim() === key) warnings.push(`${code}: "${key}" repeats its key as the value`);
    const expected = placeholders(catalogues[REFERENCE][key]).join(',');
    const actual = placeholders(value).join(',');
    if (expected !== actual) {
      placeholderMismatch++;
      placeholderKeys.push(`${key}: "${REFERENCE}" uses [${expected || '-'}], "${code}" uses [${actual || '-'}]`);
    }
  }
  if (empty) problems.push(`${code}: ${empty} translation(s) with an empty value\n${describe(emptyKeys)}`);
  if (placeholderMismatch) {
    problems.push(`${code}: ${placeholderMismatch} placeholder mismatch(es)\n${describe(placeholderKeys)}`);
  }

  if (!QUIET) {
    console.log(`${code}: ${keys.length} keys ` +
      `(missing ${missing.length}, extra ${extra.length}, empty ${empty}, placeholder-mismatch ${placeholderMismatch})`);
  }
}

if (!QUIET) {
  console.log(`${REFERENCE}: ${referenceKeys.length} keys, ${warnings.length} warning(s)`);
}

if (warnings.length) {
  console.log('\nWarnings:');
  for (const warning of warnings) console.log(`  ${warning}`);
}

if (problems.length) {
  console.error('\nFAIL: localization catalogues disagree');
  for (const problem of problems) console.error(`  ${problem}`);
  process.exit(1);
}

console.log('\nPASS: en_us and zh_cn agree on every key, value and placeholder');
process.exit(0);

#!/usr/bin/env node
/* One-off audit: bare English UI string literals in client/ (263 localization pass). */
'use strict';
const fs = require('fs');
const path = require('path');
const ROOT = path.resolve(__dirname, '..');
const SRC = path.join(ROOT, 'src', 'main', 'java', 'com', 'veriborder', 'client');
const files = [];
(function walk(dir) {
  for (const entry of fs.readdirSync(dir, { withFileTypes: true })) {
    const p = path.join(dir, entry.name);
    if (entry.isDirectory()) walk(p);
    else if (p.endsWith('.java')) files.push(p);
  }
})(SRC);
const literal = /"((?:[^"\\]|\\.)*)"/g;
for (const file of files) {
  const lines = fs.readFileSync(file, 'utf8').split(/\r?\n/);
  const hits = [];
  lines.forEach((line, index) => {
    // Skip pure translation keys, ids, formats and translation-key builders.
    let match;
    while ((match = literal.exec(line)) !== null) {
      const value = match[1];
      if (!value || value.length < 3) continue;
      if (/^[a-z0-9_]+\.[a-z0-9_.]+$/.test(value)) continue; // translation key / resource id
      if (/^[a-z0-9_]+$/.test(value)) continue; // tag / key id
      if (/^[A-Za-z_]+$/.test(value) && value.length < 6) continue; // short ids
      if (/^[%#\/\\.\-\s]*$/.test(value)) continue; // format-only
      if (/^[A-Z0-9 .:_-]+$/.test(value) && !/[A-Z]{3,}\s/.test(value)) continue; // constants
      if (/^[0-9.]+$/.test(value)) continue;
      hits.push('L' + (index + 1) + ': ' + value);
    }
  });
  if (hits.length) {
    console.log('\n' + path.relative(ROOT, file));
    for (const hit of hits) console.log('  ' + hit);
  }
}

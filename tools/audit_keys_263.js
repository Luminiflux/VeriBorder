#!/usr/bin/env node
/* One-off audit: translation keys referenced in Java but missing from en_us. */
'use strict';
const fs = require('fs');
const path = require('path');
const ROOT = path.resolve(__dirname, '..');
const SRC = path.join(ROOT, 'src', 'main', 'java');
const files = [];
(function walk(dir) {
  for (const entry of fs.readdirSync(dir, { withFileTypes: true })) {
    const p = path.join(dir, entry.name);
    if (entry.isDirectory()) walk(p);
    else if (p.endsWith('.java')) files.push(p);
  }
})(SRC);
const lang = JSON.parse(fs.readFileSync(path.join(ROOT,
  'src/main/resources/assets/veriborder/lang/en_us.json'), 'utf8'));
const pattern = /"([a-z][a-z0-9_]*(?:\.[a-z0-9_]+){2,})"/g;
const missing = new Map();
for (const file of files) {
  const text = fs.readFileSync(file, 'utf8');
  let match;
  while ((match = pattern.exec(text)) !== null) {
    const key = match[1];
    if (!(key in lang)) {
      if (!missing.has(key)) missing.set(key, new Set());
      missing.get(key).add(file.replace(SRC + path.sep, ''));
    }
  }
}
for (const [key, where] of [...missing].sort((a, b) => a[0].localeCompare(b[0]))) {
  console.log(key + '  <-  ' + [...where].join(', '));
}
console.log('TOTAL ' + missing.size);

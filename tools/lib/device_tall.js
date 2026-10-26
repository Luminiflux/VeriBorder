#!/usr/bin/env node
/*
 * VeriBorder two-block device registry - single source of truth for the
 * "which devices are TWO blocks tall?" question.
 *
 *   const { TALL_IDS, TALL_SET, isTall } = require('./lib/device_tall');
 *
 * There is no DeviceType.hasExtension(): a device is two blocks tall because
 * DeviceBlock.TALL (src/main/java/com/veriborder/block/DeviceBlock.java) lists
 * it, and that set is what makes DeviceBlock.onPlace() spawn the invisible
 * DeviceExtensionBlock companion in the block above:
 *
 *   private static final java.util.Set<DeviceType> TALL = java.util.EnumSet.of(
 *           DeviceType.PASSPORT_ISSUER, DeviceType.SELF_VISA, ...);
 *   ...
 *   if (!level.isClientSide && TALL.contains(deviceType) && ...) {
 *       level.setBlockAndUpdate(above, ModBlocks.DEVICE_EXTENSION...);
 *
 * So this module reads both Java files and derives the id list mechanically
 * instead of keeping a hand-maintained copy that can silently rot:
 *
 *   1. DeviceType.java  -> enum constant name => registry id ("SELF_VISA" ->
 *      "self_visa") plus its category (GATE devices never get an extension),
 *   2. DeviceBlock.java -> the EnumSet.of(...) members of TALL.
 *
 * If either parse yields nothing the module throws: a tool that silently
 * assumes "no tall devices" would quietly regenerate one-block icons for
 * two-block machines, which is exactly the bug this registry exists to prevent.
 */
'use strict';

const fs = require('fs');
const path = require('path');

const ROOT = path.resolve(__dirname, '..', '..');
const JAVA = path.join(ROOT, 'src', 'main', 'java', 'com', 'veriborder', 'block');
const DEVICE_TYPE_JAVA = path.join(JAVA, 'DeviceType.java');
const DEVICE_BLOCK_JAVA = path.join(JAVA, 'DeviceBlock.java');

function read(file) {
  if (!fs.existsSync(file)) throw new Error(`device_tall: missing ${path.relative(ROOT, file)}`);
  return fs.readFileSync(file, 'utf8');
}

// Enum constant name -> { id, category } from DeviceType.java.
function parseDeviceTypes() {
  const src = read(DEVICE_TYPE_JAVA);
  const out = new Map();
  const re = /^\s{4}([A-Z][A-Z0-9_]*)\("([a-z0-9_]+)",\s*Category\.([A-Z]+)/gm;
  let m;
  while ((m = re.exec(src))) out.set(m[1], { id: m[2], category: m[3] });
  if (out.size === 0) {
    throw new Error('device_tall: could not parse any DeviceType constant from DeviceType.java');
  }
  return out;
}

// The EnumSet.of(...) members of DeviceBlock.TALL.
function parseTallMembers() {
  const src = read(DEVICE_BLOCK_JAVA);
  const decl = /TALL\s*=\s*(?:java\.util\.)?EnumSet\.of\(([\s\S]*?)\)\s*;/.exec(src);
  if (!decl) throw new Error('device_tall: DeviceBlock.TALL EnumSet.of(...) not found');
  const names = [...decl[1].matchAll(/DeviceType\.([A-Z][A-Z0-9_]*)/g)].map(m => m[1]);
  if (names.length === 0) throw new Error('device_tall: DeviceBlock.TALL has no DeviceType members');
  return names;
}

const TYPES = parseDeviceTypes();
const TALL_ENUM_NAMES = parseTallMembers();

const TALL_IDS = [];
for (const name of TALL_ENUM_NAMES) {
  const type = TYPES.get(name);
  if (!type) throw new Error(`device_tall: DeviceBlock.TALL references unknown DeviceType.${name}`);
  if (type.category === 'GATE') {
    throw new Error(`device_tall: gate DeviceType.${name} must not be two blocks tall`);
  }
  if (TALL_IDS.includes(type.id)) throw new Error(`device_tall: duplicate tall id ${type.id}`);
  TALL_IDS.push(type.id);
}

const TALL_SET = new Set(TALL_IDS);

module.exports = {
  TALL_IDS,
  TALL_SET,
  TALL_ENUM_NAMES,
  isTall: id => TALL_SET.has(id),
  /** every non-gate device id from the enum, in declaration order */
  DEVICE_IDS: [...TYPES.values()].filter(t => t.category !== 'GATE').map(t => t.id),
  categoryOf: id => {
    for (const t of TYPES.values()) if (t.id === id) return t.category;
    return null;
  },
  SOURCE: {
    typeFile: path.relative(ROOT, DEVICE_TYPE_JAVA).replace(/\\/g, '/'),
    blockFile: path.relative(ROOT, DEVICE_BLOCK_JAVA).replace(/\\/g, '/'),
    set: 'DeviceBlock.TALL',
  },
};

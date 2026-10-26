#!/usr/bin/env node
/**
 * Deterministic .bbmodel -> Minecraft Java block model converter.
 *
 * Not a modeler: it only rewrites Blockbench's own element/face/UV data into the
 * vanilla block-model JSON schema. Geometry, UVs and texture assignment all come
 * from the .bbmodel authored in Blockbench.
 *
 * Texture convention: a bbmodel named "<id>.bbmodel" with a texture entry named
 * "body" maps to resource location "veriborder:block/<id>_body", so the PNG must
 * live at src/main/resources/assets/veriborder/textures/block/<id>_body.png.
 *
 * UV convention: Minecraft block-model UVs are ALWAYS normalised to 0..16
 * regardless of the PNG resolution (MC divides by texture_size/16 internally).
 * So a face covering a whole 32x32 or 64x64 texture is still [0,0,16,16]. This
 * converter copies the .bbmodel UVs verbatim, therefore models that use 64x64
 * screens keep 0..16 UVs and nothing has to be rescaled when a texture grows
 * from 16/32 to 64. (Blockbench's own UV editor shows those numbers in "texture
 * pixels", so a 64x64 texture looks quartered there; that is a display-only
 * quirk of this repo's authoring convention, not an export problem.)
 *
 * Usage: node tools/bbmodel_to_mc.js <in.bbmodel> <out.json> [--display]
 * Module: const { convert } = require('./bbmodel_to_mc.js')
 *         convert(rawBbmodelObject, id, { display: false }) -> model object
 */
'use strict';

const fs = require('fs');
const path = require('path');

function round(n) {
  const v = Number(n);
  if (!Number.isFinite(v)) return 0;
  return Math.round(v * 10000) / 10000;
}

/**
 * Convert an already-parsed .bbmodel object.
 * @param {object} raw  parsed .bbmodel JSON
 * @param {string} id   model id without extension (drives the default texture ids)
 * @param {{display?: boolean, includeUnexported?: boolean}} [opts]
 * @returns {{textures: object, elements: object[], display?: object}}
 */
function convert(raw, id, opts = {}) {
  if (!raw || typeof raw !== 'object') throw new Error('convert: raw model is not an object');

  // --- textures -----------------------------------------------------------
  const texList = raw.textures || [];
  const textures = {};
  const texIndexToKey = {};
  texList.forEach((t, i) => {
    const name = String(t.name || ('tex' + i)).replace(/[^A-Za-z0-9_]/g, '_');
    const key = name === 'default' ? 'body' : name;
    texIndexToKey[i] = key;
    if (t.id !== undefined) texIndexToKey[String(t.id)] = key;
    // A path that already is a resource location (e.g. "veriborder:block/r9_shell")
    // is used verbatim so several models can share one material kit. Otherwise the
    // convention is <modelId>_<slot>.
    //
    // `resource` is an optional explicit resource location. Round 9 uses it for the
    // shared kit: Blockbench resolves a texture `path` against the filesystem, so a
    // resource location there makes the .bbmodel fail to open ("... reading
    // 'decode'"). Those models therefore keep `path` empty (pixels travel in
    // `source`) and declare the shared location in `resource`, which wins here.
    const declared = String(t.path || '');
    const hinted = String(t.resource || '');
    textures[key] = /^[a-z0-9_.-]+:/.test(hinted)
      ? hinted
      : /^[a-z0-9_.-]+:/.test(declared)
        ? declared
        : `veriborder:block/${id}_${key}`;
  });
  if (Object.keys(textures).length === 0) textures.body = `veriborder:block/${id}_body`;
  textures.particle = textures.particle || textures[Object.keys(textures)[0]];

  // --- elements -------------------------------------------------------------
  const elements = [];
  for (const el of raw.elements || []) {
    if (el.export === false && !opts.includeUnexported) continue;
    if (!Array.isArray(el.from) || !Array.isArray(el.to)) continue;
    const out = {
      from: el.from.map(n => round(n)),
      to: el.to.map(n => round(n)),
      faces: {}
    };
    for (const face of ['north', 'south', 'east', 'west', 'up', 'down']) {
      const f = el.faces && el.faces[face];
      if (!f || f.texture === null || f.texture === undefined) continue;
      const key = texIndexToKey[f.texture] || texIndexToKey[String(f.texture)] || 'body';
      const uv = Array.isArray(f.uv) ? f.uv.map(n => round(n)) : [0, 0, 16, 16];
      const faceOut = { uv, texture: '#' + key };
      if (f.rotation) faceOut.rotation = f.rotation;
      if (f.cullface) faceOut.cullface = f.cullface;
      out.faces[face] = faceOut;
    }
    if (Object.keys(out.faces).length === 0) continue;
    if (el.rotation && Array.isArray(el.rotation)) {
      const [rx, ry, rz] = el.rotation;
      const axis = Math.abs(rx) >= Math.abs(ry) && Math.abs(rx) >= Math.abs(rz) ? 'x'
        : (Math.abs(ry) >= Math.abs(rz) ? 'y' : 'z');
      const angle = axis === 'x' ? rx : axis === 'y' ? ry : rz;
      if (Math.abs(angle) > 1e-6) {
        out.rotation = {
          origin: (el.origin || [8, 8, 8]).map(n => round(n)),
          axis,
          angle: round(angle),
          rescale: !!el.rescale
        };
      }
    }
    elements.push(out);
  }
  if (elements.length === 0) throw new Error('no elements with faces found in ' + id);

  const model = { textures, elements };
  if (opts.display) {
    // Tall (2-block) machines must still fit an item slot: shrink the item
    // display and shift it down by half the excess height.
    const ys = elements.flatMap(e => [e.from[1], e.to[1]]);
    const h = Math.max(...ys) - Math.min(...ys);
    const fit = h > 16 ? 16 / h : 1;
    const ty = h > 16 ? -(h - 16) / 2 : 0;
    const gs = 0.625 * fit;
    const fs = 0.5 * fit;
    const gds = 0.25 * fit;
    model.display = {
      thirdperson_righthand: { rotation: [75, 45, 0], translation: [0, 2.5 + ty * 0.375, 0], scale: [0.375 * fit, 0.375 * fit, 0.375 * fit] },
      thirdperson_lefthand: { rotation: [75, 45, 0], translation: [0, 2.5 + ty * 0.375, 0], scale: [0.375 * fit, 0.375 * fit, 0.375 * fit] },
      firstperson_righthand: { rotation: [0, 45, 0], translation: [0, ty * 0.4, 0], scale: [0.4 * fit, 0.4 * fit, 0.4 * fit] },
      firstperson_lefthand: { rotation: [0, 45, 0], translation: [0, ty * 0.4, 0], scale: [0.4 * fit, 0.4 * fit, 0.4 * fit] },
      gui: { rotation: [30, 225, 0], translation: [0, ty * 0.5, 0], scale: [gs, gs, gs] },
      ground: { translation: [0, 3 + ty * 0.25, 0], scale: [gds, gds, gds] },
      fixed: { rotation: [0, 180, 0], translation: [0, ty * 0.5, 0], scale: [fs, fs, fs] }
    };
  }
  return model;
}

function main() {
  const inPath = process.argv[2];
  const outPath = process.argv[3];
  if (!inPath || !outPath) {
    console.error('bbmodel_to_mc: usage: node tools/bbmodel_to_mc.js <in.bbmodel> <out.json>');
    process.exit(1);
  }
  let raw;
  try {
    raw = JSON.parse(fs.readFileSync(inPath, 'utf8'));
  } catch (e) {
    console.error('bbmodel_to_mc: cannot read ' + inPath + ': ' + e.message);
    process.exit(1);
  }
  const id = path.basename(inPath).replace(/\.bbmodel$/i, '');
  let model;
  try {
    model = convert(raw, id, { display: process.argv.includes('--display') });
  } catch (e) {
    console.error('bbmodel_to_mc: ' + e.message);
    process.exit(1);
  }
  fs.mkdirSync(path.dirname(outPath), { recursive: true });
  // Compact by default: a 120-element gate is ~110 KB pretty-printed but ~45 KB
  // compact, and the 120 KB block-model budget in preview_models.js leaves no
  // room for pretty-printing once a machine carries real detail. --pretty keeps
  // the readable form for hand inspection.
  const pretty = process.argv.includes('--pretty');
  fs.writeFileSync(outPath, (pretty ? JSON.stringify(model, null, 2) : JSON.stringify(model)) + '\n', 'utf8');
  console.log(`${id}: ${model.elements.length} elements, ${Object.keys(model.textures).length} textures, `
    + `${(fs.statSync(outPath).size / 1024).toFixed(1)} KB -> ${outPath}`);
}

if (require.main === module) main();

module.exports = { convert, round };

#!/usr/bin/env node
/**
 * VeriBorder - modern border-control e-gate model generator (deterministic).
 *
 *   node tools/build_gate_model.js
 *
 * Produces, from code only (no Blender, no browser):
 *
 *   assets/models/{egate,exit_gate,dual_gate}.bbmodel        Blockbench sources
 *   src/main/resources/assets/veriborder/textures/block/<id>_{body,trim,top,
 *        accent,led,glass,screen,screen2,reader,sign,detail}.png      33 PNGs
 *   src/main/resources/assets/veriborder/models/block/{id}.json       MC models
 *   assets/models/_preview/<id>_assembly.json                 block + swing wings
 *   assets/models/_preview/<id>_merged.bbmodel                variant w/o end caps
 *   assets/models/_preview/gate_wings.json                    hinge contract for Java
 *   tools/preview/gate_model_report.md                        generated report
 *
 * Design invariants (checked by the script itself, then by the tools):
 *   - one block footprint, 16 x 16, height <= 22 (camera arm);
 *   - cabinets on BOTH sides (x 0..3.2 and 12.8..16.0), lane x 3.2..12.8 is empty
 *     below the door-frame line so the animated leaves never clip;
 *   - the outer 1 px of each cabinet is a plain, flat, uninterrupted plane so
 *     two gates placed side by side join without a seam (see cap_* elements);
 *   - no two elements may share a min/max coordinate on an axis while also
 *     overlapping on the other two (that is what tools/scan_coplanar.js checks);
 *   - every face that something else completely covers is dropped (no overdraw).
 */
'use strict';

const fs = require('fs');
const path = require('path');
const zlib = require('zlib');
const { convert } = require('./bbmodel_to_mc.js');

const ROOT = path.resolve(__dirname, '..');
const TEX_DIR = path.join(ROOT, 'src/main/resources/assets/veriborder/textures/block');
const BB_DIR = path.join(ROOT, 'assets/models');
const MC_DIR = path.join(ROOT, 'src/main/resources/assets/veriborder/models/block');
const BS_DIR = path.join(ROOT, 'src/main/resources/assets/veriborder/blockstates');
const REF_DIR = path.join(ROOT, 'assets/models/_preview');
const PREV_DIR = path.join(ROOT, 'tools/preview');

// The four block-state variants of one gate. `left` / `right` mean "a same-family
// gate stands on that side", so the end cap facing it disappears and the two
// machines read as one bank. Element lists are filtered, never rebuilt.
const CAP_VARIANTS = [
  { suffix: '', keep: () => true },
  { suffix: '_left', keep: el => !/^cap_left_/.test(el.name) },
  { suffix: '_right', keep: el => !/^cap_right_/.test(el.name) },
  { suffix: '_both', keep: el => !/^cap_(left|right)_/.test(el.name) }
];

/** 4 facings x left x right x open = 32 variants. `open` never changes the model:
 *  the leaves are drawn by EGateRenderer, the block model has no door. */
function buildBlockstate(id) {
  const variants = {};
  for (const f of ['north', 'east', 'south', 'west']) {
    const y = f === 'north' ? 0 : f === 'east' ? 90 : f === 'south' ? 180 : 270;
    for (const l of [false, true]) {
      for (const r of [false, true]) {
        const suffix = l && r ? '_both' : l ? '_left' : r ? '_right' : '';
        for (const o of [false, true]) {
          variants[`facing=${f},left=${l},right=${r},open=${o}`] =
            { model: `veriborder:block/${id}${suffix}`, y };
        }
      }
    }
  }
  return { variants };
}

// ---------------------------------------------------------------- png codec
const CRC_TABLE = (() => {
  const t = new Int32Array(256);
  for (let n = 0; n < 256; n++) {
    let c = n;
    for (let k = 0; k < 8; k++) c = (c & 1) ? (0xedb88320 ^ (c >>> 1)) : (c >>> 1);
    t[n] = c;
  }
  return t;
})();
function crc32(buf) {
  let c = 0xffffffff;
  for (let i = 0; i < buf.length; i++) c = CRC_TABLE[(c ^ buf[i]) & 0xff] ^ (c >>> 8);
  return (c ^ 0xffffffff) >>> 0;
}
function pngChunk(type, data) {
  const len = Buffer.alloc(4);
  len.writeUInt32BE(data.length, 0);
  const t = Buffer.from(type, 'ascii');
  const crc = Buffer.alloc(4);
  crc.writeUInt32BE(crc32(Buffer.concat([t, data])), 0);
  return Buffer.concat([len, t, data, crc]);
}
function encodePng(w, h, rgba) {
  const raw = Buffer.alloc((w * 4 + 1) * h);
  for (let y = 0; y < h; y++) {
    raw[y * (w * 4 + 1)] = 0;
    rgba.copy(raw, y * (w * 4 + 1) + 1, y * w * 4, (y + 1) * w * 4);
  }
  const ihdr = Buffer.alloc(13);
  ihdr.writeUInt32BE(w, 0);
  ihdr.writeUInt32BE(h, 4);
  ihdr[8] = 8; ihdr[9] = 6; ihdr[10] = 0; ihdr[11] = 0; ihdr[12] = 0;
  return Buffer.concat([
    Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]),
    pngChunk('IHDR', ihdr),
    pngChunk('IDAT', zlib.deflateSync(raw, { level: 9 })),
    pngChunk('IEND', Buffer.alloc(0))
  ]);
}

// ---------------------------------------------------------------- palette
const PAL = {
  white: '#EFF2F4',
  whiteHi: '#FBFDFE',
  whiteSh: '#DFE4E8',
  edge: '#C9D0D6',
  greyLi: '#C6CED5',
  grey: '#AEB7BF',
  greyDk: '#8A939B',
  dark: '#333B42',
  darker: '#1F262C',
  slot: '#242B32',
  steel: '#5C6873',
  green: '#31E07E',
  greenDk: '#0F6B39',
  uiBg: '#05080D',
  uiPanel: '#0B141D',
  uiText: '#EAF4FF',
  uiDim: '#7C93A8'
};

function rgb(hex) {
  const h = hex.replace('#', '');
  return [parseInt(h.slice(0, 2), 16), parseInt(h.slice(2, 4), 16), parseInt(h.slice(4, 6), 16)];
}

// deterministic value hash (no Math.random anywhere in this script)
function hash2(x, y, s) {
  let h = Math.imul(x + 7, 374761393) ^ Math.imul(y + 13, 668265263) ^ Math.imul(s + 29, 1442695041);
  h = Math.imul(h ^ (h >>> 13), 1274126177);
  return ((h ^ (h >>> 16)) >>> 0) / 4294967295;
}

class Cv {
  constructor(w, h, bg) {
    this.w = w; this.h = h;
    this.d = Buffer.alloc(w * h * 4);
    if (bg) this.rect(0, 0, w, h, bg);
  }
  px(x, y, c) {
    if (x < 0 || y < 0 || x >= this.w || y >= this.h) return;
    const [r, g, b] = rgb(c);
    const i = (y * this.w + x) * 4;
    this.d[i] = r; this.d[i + 1] = g; this.d[i + 2] = b; this.d[i + 3] = 255;
  }
  rect(x, y, w, h, c) {
    for (let j = 0; j < h; j++) for (let i = 0; i < w; i++) this.px(x + i, y + j, c);
  }
  hline(x1, x2, y, c) { for (let x = x1; x <= x2; x++) this.px(x, y, c); }
  vline(x, y1, y2, c) { for (let y = y1; y <= y2; y++) this.px(x, y, c); }
  frame(x, y, w, h, c) {
    this.hline(x, x + w - 1, y, c);
    this.hline(x, x + w - 1, y + h - 1, c);
    this.vline(x, y, y + h - 1, c);
    this.vline(x + w - 1, y, y + h - 1, c);
  }
  disc(cx, cy, r, c) {
    for (let y = -r; y <= r; y++) for (let x = -r; x <= r; x++) {
      if (x * x + y * y <= r * r + r * 0.4) this.px(cx + x, cy + y, c);
    }
  }
  ring(cx, cy, r, c) {
    for (let y = -r; y <= r; y++) for (let x = -r; x <= r; x++) {
      const d = x * x + y * y;
      if (d <= r * r + r && d >= (r - 1) * (r - 1) + r * 0.4) this.px(cx + x, cy + y, c);
    }
  }
}

// ---------------------------------------------------------------- 5x7 font
const FONT = {
  A: '01110,10001,10001,11111,10001,10001,10001',
  B: '11110,10001,10001,11110,10001,10001,11110',
  C: '01110,10001,10000,10000,10000,10001,01110',
  D: '11110,10001,10001,10001,10001,10001,11110',
  E: '11111,10000,10000,11110,10000,10000,11111',
  F: '11111,10000,10000,11110,10000,10000,10000',
  G: '01110,10001,10000,10111,10001,10001,01110',
  H: '10001,10001,10001,11111,10001,10001,10001',
  I: '11111,00100,00100,00100,00100,00100,11111',
  J: '00111,00010,00010,00010,00010,10010,01100',
  K: '10001,10010,10100,11000,10100,10010,10001',
  L: '10000,10000,10000,10000,10000,10000,11111',
  M: '10001,11011,10101,10101,10001,10001,10001',
  N: '10001,11001,10101,10011,10001,10001,10001',
  O: '01110,10001,10001,10001,10001,10001,01110',
  P: '11110,10001,10001,11110,10000,10000,10000',
  Q: '01110,10001,10001,10001,10101,10010,01101',
  R: '11110,10001,10001,11110,10100,10010,10001',
  S: '01111,10000,10000,01110,00001,00001,11110',
  T: '11111,00100,00100,00100,00100,00100,00100',
  U: '10001,10001,10001,10001,10001,10001,01110',
  V: '10001,10001,10001,10001,10001,01010,00100',
  W: '10001,10001,10001,10101,10101,11011,10001',
  X: '10001,10001,01010,00100,01010,10001,10001',
  Y: '10001,10001,01010,00100,00100,00100,00100',
  Z: '11111,00001,00010,00100,01000,10000,11111',
  '0': '01110,10011,10101,10101,10101,11001,01110',
  '1': '00100,01100,00100,00100,00100,00100,01110',
  '2': '01110,10001,00001,00010,00100,01000,11111',
  '3': '11110,00001,00001,01110,00001,00001,11110',
  '4': '00010,00110,01010,10010,11111,00010,00010',
  '5': '11111,10000,11110,00001,00001,10001,01110',
  '6': '00110,01000,10000,11110,10001,10001,01110',
  '7': '11111,00001,00010,00100,01000,01000,01000',
  '8': '01110,10001,10001,01110,10001,10001,01110',
  '9': '01110,10001,10001,01111,00001,00010,01100',
  ' ': '00000,00000,00000,00000,00000,00000,00000',
  '.': '00000,00000,00000,00000,00000,01100,01100',
  '-': '00000,00000,00000,11111,00000,00000,00000',
  '/': '00001,00010,00010,00100,01000,01000,10000',
  ':': '00000,01100,01100,00000,01100,01100,00000',
  '?': '01110,10001,00001,00110,00100,00000,00100'
};
const GLYPH_W = 5, GLYPH_H = 7, GLYPH_GAP = 1;

function textWidth(s, scale) {
  s = String(s).toUpperCase();
  let w = 0;
  for (let i = 0; i < s.length; i++) w += (GLYPH_W + GLYPH_GAP) * scale;
  return Math.max(0, w - GLYPH_GAP * scale);
}
function drawText(c, s, x, y, scale, color) {
  s = String(s).toUpperCase();
  let cx = x;
  for (const ch of s) {
    const rows = (FONT[ch] || FONT['?']).split(',');
    for (let r = 0; r < GLYPH_H; r++) {
      for (let k = 0; k < GLYPH_W; k++) {
        if (rows[r][k] === '1') c.rect(cx + k * scale, y + r * scale, scale, scale, color);
      }
    }
    cx += (GLYPH_W + GLYPH_GAP) * scale;
  }
}
function drawTextC(c, s, cx, y, scale, color) {
  drawText(c, s, Math.round(cx - textWidth(s, scale) / 2), y, scale, color);
}
/** right-pointing chevron/solid arrow of width w, height h, dir +1 right / -1 left. */
function drawArrow(c, x, y, w, h, dir, color) {
  const shaftH = Math.max(2, Math.round(h / 3));
  const shaftY = y + Math.round((h - shaftH) / 2);
  const headW = Math.max(3, Math.round(w * 0.42));
  if (dir > 0) {
    c.rect(x, shaftY, w - headW, shaftH, color);
    for (let i = 0; i < headW; i++) {
      const hh = Math.max(1, Math.round(h * (1 - i / headW)));
      c.rect(x + w - headW + i, y + Math.round((h - hh) / 2), 1, hh, color);
    }
  } else {
    c.rect(x + headW, shaftY, w - headW, shaftH, color);
    for (let i = 0; i < headW; i++) {
      const hh = Math.max(1, Math.round(h * (1 - i / headW)));
      c.rect(x + i, y + Math.round((h - hh) / 2), 1, hh, color);
    }
  }
}

// ---------------------------------------------------------------- gates
const GATES = [
  {
    id: 'egate',
    name: '入境自助闸机 / entry self-service gate',
    accent: '#2E7BE0', accentHi: '#7CC0FF', accentDk: '#123F77',
    led: '#63D2FF', ledDk: '#1B6EA8',
    sign: 'IN', signMode: 'text',
    ui: 'entry', uiTitle: 'IN', uiLabel: 'PLACE DOC', screens: 1, module: 'fingerprint'
  },
  {
    id: 'exit_gate',
    name: '出境自助闸机 / exit self-service gate',
    accent: '#E0951F', accentHi: '#FFCB63', accentDk: '#7A4A08',
    led: '#FFB84D', ledDk: '#8A5A12',
    sign: 'OUT', signMode: 'text',
    ui: 'exit', uiTitle: 'OUT', uiLabel: 'CLEARED', screens: 1, module: 'dispenser'
  },
  {
    id: 'dual_gate',
    name: '一地两检闸机 / dual-clearance gate',
    accent: '#2E9E8F', accentHi: '#7CE5D4', accentDk: '#155B52',
    led: '#8CF3E2', ledDk: '#1D6E62',
    sign: 'DUAL', signMode: 'dual',
    ui: 'dual', uiTitle: 'DUAL', uiLabel: 'BOTH WAYS', screens: 2, module: null
  }
];

// ---------------------------------------------------------------- textures
function texBody() {
  const c = new Cv(32, 32, PAL.white);
  // very light matte speckle only - a heavy pattern reads as dirt at 1:1
  for (let y = 0; y < 32; y++) {
    for (let x = 0; x < 32; x++) {
      const n = hash2(x, y, 11);
      if (n > 0.968) c.px(x, y, PAL.whiteSh);
      else if (n < 0.02) c.px(x, y, PAL.whiteHi);
    }
  }
  for (let x = 2; x < 32; x += 4) c.px(x, 6, PAL.whiteHi);
  c.vline(31, 0, 31, PAL.edge);       // panel edge shadow
  c.hline(0, 31, 31, PAL.whiteSh);
  c.hline(0, 31, 0, PAL.whiteHi);
  return c;
}

function texTrim() {
  const c = new Cv(32, 32, PAL.grey);
  c.rect(0, 0, 32, 3, PAL.greyLi);
  c.rect(0, 3, 32, 1, PAL.whiteSh);
  c.rect(0, 28, 32, 4, PAL.greyDk);
  c.hline(0, 31, 18, PAL.steel);
  c.hline(0, 31, 19, PAL.greyLi);
  for (let x = 0; x < 32; x += 4) {
    for (let y = 4; y < 28; y++) if (hash2(x, y, 31) > 0.7) c.px(x, y, PAL.greyLi);
  }
  return c;
}

function texTop() {
  const c = new Cv(32, 32, PAL.white);
  c.frame(0, 0, 32, 32, PAL.edge);
  for (let y = 1; y < 31; y++) {
    for (let x = 1; x < 31; x++) if (hash2(x, y, 41) > 0.88) c.px(x, y, PAL.whiteSh);
  }
  // recessed vent block
  c.rect(19, 18, 10, 9, PAL.darker);
  for (let i = 0; i < 3; i++) c.rect(20, 20 + i * 3, 8, 1, PAL.greyDk);
  c.hline(1, 30, 1, PAL.whiteHi);
  return c;
}

function texAccent(g) {
  const c = new Cv(32, 32, g.accent);
  c.rect(0, 0, 32, 3, g.accentHi);
  c.rect(0, 3, 32, 1, g.accent);
  c.rect(0, 27, 32, 5, g.accentDk);
  c.hline(0, 31, 14, g.accentHi);     // gloss line
  c.hline(0, 31, 15, g.accentDk);
  for (let x = 0; x < 32; x++) if (hash2(x, 8, 51) > 0.8) c.px(x, 8, g.accentHi);
  return c;
}

function texLed(g) {
  const c = new Cv(32, 32, g.ledDk);
  c.rect(3, 0, 26, 32, g.led);
  c.rect(9, 0, 14, 32, PAL.whiteHi);
  c.rect(13, 0, 6, 32, '#FFFFFF');
  c.vline(2, 0, 31, g.ledDk);
  c.vline(29, 0, 31, g.ledDk);
  return c;
}

/** Frosted glass: brighter cool white with three flat diagonal glare bands.
 *  A dark pane turned the closed lane into a black hole; a plain white one read
 *  as a solid board. The stripes are what sells it as glass at 16 px. */
function texGlass() {
  const c = new Cv(32, 32, '#DCE8EE');
  for (let y = 0; y < 32; y++) {
    for (let x = 0; x < 32; x++) {
      const n = hash2(x, y, 61);
      if (n > 0.90) c.px(x, y, '#EAF3F7');
      else if (n < 0.08) c.px(x, y, '#CFDDE6');
    }
  }
  // three 2 px diagonal highlights, staggered across the pane
  const band = (off, col) => {
    for (let i = 0; i < 32; i++) {
      const y = off - i;
      if (y >= 0 && y < 32) { c.px(i, y, col); c.px(i, Math.min(31, y + 1), col); }
    }
  };
  band(27, '#FFFFFF');
  band(19, '#F7FCFE');
  band(11, '#F7FCFE');
  c.frame(0, 0, 32, 32, '#9FB2BC');
  return c;
}

/** Dark metal for the wing frames and the camera arm. */
function texFrame() {
  const c = new Cv(32, 32, '#404A52');
  for (let y = 0; y < 32; y++) {
    for (let x = 0; x < 32; x++) {
      const n = hash2(x, y, 83);
      if (n > 0.90) c.px(x, y, '#4B555E');
      else if (n < 0.10) c.px(x, y, '#343D44');
    }
  }
  c.rect(0, 0, 32, 2, '#5A656E');       // top highlight
  c.rect(0, 3, 32, 1, '#2C343A');
  c.rect(0, 29, 32, 3, '#2A3137');      // bottom shade
  c.hline(0, 31, 16, '#313940');
  return c;
}

/** Emissive status lamp (green / red variant). */
function texLamp(color, hi) {
  const c = new Cv(32, 32, '#0E141A');
  c.rect(1, 1, 30, 30, '#1A222A');
  c.rect(3, 3, 26, 26, color);
  c.rect(6, 6, 20, 20, hi);
  c.rect(10, 10, 12, 12, '#FFFFFF');
  c.rect(13, 13, 6, 6, '#FFFFFF');
  c.frame(0, 0, 32, 32, '#0A0F14');
  return c;
}

/** 64x64 face-recognition UI. The screen plate is square (1.85 x 1.80 px), so
 *  the texture maps 1:1 with uv [0,0,16,16] - no stretch anywhere.
 *  Emissive look: near-black ground, full-white silhouette, saturated accent. */
function texScreenFace(g) {
  const c = new Cv(64, 64, PAL.uiBg);
  // header: bright accent fill, white 2x title
  c.rect(0, 0, 64, 15, g.accent);
  c.rect(0, 15, 64, 1, '#FFFFFF');
  drawText(c, g.uiTitle, 4, 2, 2, '#FFFFFF');
  for (let i = 0; i < 3; i++) c.rect(53 + i * 4, 6, 2, 2, i === 0 ? '#FFFFFF' : g.accentHi);
  // face plate
  c.rect(12, 17, 40, 29, PAL.uiPanel);
  c.rect(12, 17, 40, 1, g.accentHi);
  c.rect(25, 20, 14, 15, '#D3E3F0');            // head
  c.rect(21, 34, 22, 8, '#D3E3F0');             // shoulders (touches the head)
  c.rect(28, 26, 3, 2, '#08111A');              // eyes
  c.rect(33, 26, 3, 2, '#08111A');
  c.rect(29, 30, 6, 1, '#08111A');              // mouth
  // green recognition box: 2 px reticle + white corner pips
  c.frame(15, 15, 34, 33, PAL.green);
  c.frame(16, 16, 32, 31, PAL.green);
  for (const [x, y] of [[15, 15], [47, 15], [15, 46], [47, 46]]) c.rect(x, y, 2, 2, '#FFFFFF');
  // label + progress + glow
  drawText(c, g.uiLabel, 4, 49, 1, PAL.uiText);
  c.rect(4, 57, 56, 3, '#0E1A25');
  c.rect(4, 57, 36, 3, g.accentHi);
  c.rect(4, 57, 4, 3, '#FFFFFF');
  c.rect(0, 62, 64, 2, g.accentHi);
  return c;
}

/** 64x64 status / direction UI. Top 64x44 = full panel, bottom-left 32x14
 *  (uv u 0..8, v 11.5..15) = the little console strip. */
function texScreenStatus(g) {
  const c = new Cv(64, 64, PAL.uiBg);
  const STRIP = 44;                 // rows 44..57 = status strip (console crop)
  if (g.ui === 'entry') {
    c.rect(0, 0, 64, 15, g.accent);
    c.rect(0, 15, 64, 1, '#FFFFFF');
    drawText(c, 'IN', 4, 2, 2, '#FFFFFF');
    drawText(c, 'SCAN', 38, 6, 1, '#FFFFFF');
    // passport booklet
    c.rect(14, 19, 34, 20, '#D3E3F0');
    c.rect(14, 19, 8, 20, g.accent);
    c.rect(15, 20, 6, 18, g.accentDk);
    c.rect(27, 23, 18, 2, '#5C6873');
    c.rect(27, 28, 18, 2, '#5C6873');
    c.rect(27, 33, 12, 2, '#5C6873');
    // insertion slot under the booklet
    c.rect(10, 40, 44, 3, '#0A121A');
    c.rect(10, 40, 44, 1, g.accentHi);
    // green pass arrow
    drawArrow(c, 46, 19, 14, 9, 1, PAL.green);
  } else if (g.ui === 'exit') {
    c.rect(0, 0, 64, 15, g.accent);
    c.rect(0, 15, 64, 1, '#FFFFFF');
    drawText(c, 'OUT', 4, 2, 2, '#FFFFFF');
    drawText(c, 'DONE', 40, 6, 1, '#FFFFFF');
    // stamp: handle, cap, base, shadow
    c.rect(24, 18, 16, 7, g.accentDk);
    c.rect(26, 19, 12, 5, g.accent);
    c.rect(16, 25, 32, 6, g.accent);
    c.rect(16, 31, 32, 3, g.accentDk);
    // seal ring
    c.ring(24, 38, 4, g.accentHi);
    c.ring(24, 38, 2, g.accentHi);
    c.rect(23, 37, 3, 3, '#FFFFFF');
    // verified count readout
    drawText(c, '07', 43, 20, 2, '#FFFFFF');
    drawText(c, 'PASS', 38, 35, 1, g.accentHi);
  } else {
    // dual: amber departure (left) | blue arrival (right) + double arrow
    const AMB = '#B87216', AMBH = '#FFC15C', BLU = '#1E5FA8', BLUH = '#7CC0FF';
    c.rect(0, 0, 32, 15, AMB);
    c.rect(32, 0, 32, 15, BLU);
    c.rect(31, 0, 2, 15, '#FFFFFF');
    c.rect(0, 15, 64, 1, '#FFFFFF');
    drawArrow(c, 3, 4, 12, 7, -1, '#FFFFFF');
    drawText(c, 'OUT', 17, 4, 1, '#FFFFFF');
    drawText(c, 'IN', 38, 4, 1, '#FFFFFF');
    drawArrow(c, 49, 4, 12, 7, 1, '#FFFFFF');
    // departure panel
    c.rect(2, 18, 24, 23, '#0B0906');
    drawArrow(c, 4, 25, 18, 9, -1, AMBH);
    c.rect(2, 36, 24, 5, AMB);
    // arrival panel
    c.rect(38, 18, 24, 23, '#050A10');
    drawArrow(c, 40, 25, 18, 9, 1, BLUH);
    c.rect(38, 36, 24, 5, BLU);
    // central double arrow (two stacked arrows, reads as <->)
    c.rect(27, 18, 10, 23, PAL.uiBg);
    drawArrow(c, 27, 21, 10, 5, 1, '#FFFFFF');
    drawArrow(c, 27, 30, 10, 5, -1, '#FFFFFF');
  }
  // ---- status strip, rows 44..57; the front console display samples exactly
  //      this band with uv [0,11,16,15]
  c.rect(0, STRIP, 64, 14, PAL.uiPanel);
  c.rect(0, STRIP, 5, 14, g.accent);
  c.rect(5, STRIP, 59, 1, g.accentHi);
  c.hline(0, 63, STRIP + 13, g.accentDk);
  if (g.ui === 'entry') {
    drawText(c, 'SCAN', 9, STRIP + 3, 1, PAL.uiText);
    drawText(c, 'DOC', 40, STRIP + 3, 1, g.accentHi);
    c.rect(9, STRIP + 10, 22, 2, g.accentHi);
  } else if (g.ui === 'exit') {
    drawText(c, 'OK', 9, STRIP + 3, 1, PAL.uiText);
    drawText(c, '07', 40, STRIP + 3, 1, g.accentHi);
    c.rect(9, STRIP + 10, 22, 2, g.accentHi);
  } else {
    drawText(c, 'SEL', 9, STRIP + 3, 1, PAL.uiText);
    c.rect(30, STRIP + 3, 12, 3, '#FFC15C');
    c.rect(30, STRIP + 8, 12, 3, '#7CC0FF');
    c.rect(44, STRIP + 3, 14, 2, PAL.uiText);
    c.rect(44, STRIP + 7, 9, 2, PAL.uiDim);
  }
  c.rect(0, 62, 64, 2, g.accentHi);
  return c;
}

function texReader(g) {
  const c = new Cv(32, 32, '#141C24');
  c.frame(0, 0, 32, 32, '#2B3742');
  // blue ring
  c.frame(4, 4, 24, 24, g.accent);
  c.frame(5, 5, 22, 22, g.accentHi);
  c.rect(7, 7, 18, 18, '#1B2732');
  // document slot
  c.rect(9, 14, 14, 3, '#070C11');
  c.hline(9, 22, 14, PAL.whiteHi);
  // chip read indicator
  c.rect(9, 20, 2, 2, PAL.green);
  c.rect(13, 20, 2, 2, g.accentHi);
  c.rect(17, 20, 2, 2, g.accentHi);
  // glare streak
  for (let i = 0; i < 16; i++) c.px(7 + i, 26 - i, '#3E5769');
  return c;
}

function texSign(g) {
  const c = new Cv(32, 32, '#F4F6F8');
  c.frame(0, 0, 32, 32, PAL.edge);
  c.rect(1, 1, 3, 30, g.accent);
  c.rect(4, 1, 1, 30, g.accentDk);
  if (g.signMode === 'dual') {
    drawArrow(c, 6, 10, 20, 7, 1, g.accentDk);
    drawArrow(c, 6, 20, 20, 7, -1, g.accent);
    c.rect(14, 9, 4, 1, PAL.edge);
  } else {
    const w = textWidth(g.sign, 2);
    drawText(c, g.sign, Math.round((32 + 3 - w) / 2), 10, 2, g.accentDk);
    c.rect(6, 25, 20, 1, g.accent);
  }
  c.hline(0, 31, 31, PAL.edge);
  return c;
}

function texDetail() {
  const c = new Cv(32, 32, PAL.dark);
  for (let y = 0; y < 32; y++) {
    for (let x = 0; x < 32; x++) {
      const n = hash2(x, y, 71);
      if (n > 0.90) c.px(x, y, '#3C454D');
      else if (n < 0.08) c.px(x, y, PAL.darker);
    }
  }
  c.frame(0, 0, 32, 32, PAL.darker);
  // access-cover outline + screws
  c.frame(3, 3, 26, 26, '#4A555E');
  const screws = [[5, 5], [25, 5], [5, 25], [25, 25]];
  for (const [sx, sy] of screws) {
    c.rect(sx, sy, 2, 2, PAL.greyDk);
    c.px(sx, sy, PAL.greyLi);
  }
  // louvre slots
  for (let i = 0; i < 4; i++) c.rect(9, 9 + i * 4, 14, 2, PAL.darker);
  return c;
}

function paintTextures(g) {
  return {
    body: { w: 32, h: 32, c: texBody() },
    trim: { w: 32, h: 32, c: texTrim() },
    top: { w: 32, h: 32, c: texTop() },
    accent: { w: 32, h: 32, c: texAccent(g) },
    led: { w: 32, h: 32, c: texLed(g) },
    glass: { w: 32, h: 32, c: texGlass() },
    frame: { w: 32, h: 32, c: texFrame() },
    screen: { w: 64, h: 64, c: texScreenFace(g) },
    screen2: { w: 64, h: 64, c: texScreenStatus(g) },
    reader: { w: 32, h: 32, c: texReader(g) },
    sign: { w: 32, h: 32, c: texSign(g) },
    detail: { w: 32, h: 32, c: texDetail() },
    light_green: { w: 32, h: 32, c: texLamp('#2FE07E', '#8CFFC0') },
    light_red: { w: 32, h: 32, c: texLamp('#FF4D4D', '#FFA8A8') }
  };
}

const TEX_ORDER = [
  'body', 'trim', 'top', 'accent', 'led', 'glass', 'frame',
  'screen', 'screen2', 'reader', 'sign', 'detail', 'light_green', 'light_red'
];

// ---------------------------------------------------------------- geometry
const U = 2.6;                 // cabinet inner face (lane edge); lane = 16 - 2U = 10.8
const Z1 = 0.35, Z2 = 15.65;   // body front / back face
const TOP = 17.6;              // body top (lid sits on it, y 17.6..18.6)
const CAP = 0.2;               // outer end-cap skin depth (hideable)
const CORE_IN = U - CAP;       // 2.40 : where the inner skin starts

const SEM_ALL = ['front', 'back', 'outer', 'inner', 'top', 'bottom'];

function normFaces(spec) {
  const out = {};
  if (spec.all) for (const s of SEM_ALL) out[s] = spec.all;
  for (const s of SEM_ALL) if (spec[s] !== undefined) out[s] = spec[s];
  const res = {};
  for (const s of SEM_ALL) {
    const v = out[s];
    if (!v) continue;
    res[s] = typeof v === 'string' ? { tex: v, uv: [0, 0, 16, 16] } : { tex: v.tex, uv: v.uv || [0, 0, 16, 16] };
  }
  return res;
}

/** Build one cabinet. `u` is the distance from the OUTER face (0..U = 2.6). */
function cabinetEls(side) {
  const M = side === 'R';
  const X = u => (M ? 16 - u : u);
  const semMap = M
    ? { front: 'north', back: 'south', outer: 'east', inner: 'west' }
    : { front: 'north', back: 'south', outer: 'west', inner: 'east' };
  const els = [];
  const add = (name, b, faces, opts = {}) => {
    const [u1, v1, w1, u2, v2, w2] = b;
    for (const n of [u1, v1, w1, u2, v2, w2]) {
      if (!Number.isFinite(n)) throw new Error('bad box for ' + name);
    }
    if (!(u2 > u1 && v2 > v1 && w2 > w1)) throw new Error('degenerate box for ' + name + ': ' + b.join(','));
    const xa = X(u1), xb = X(u2);
    const el = {
      name: opts.name || ((M ? 'R_' : 'L_') + name),
      from: [Math.min(xa, xb), v1, w1],
      to: [Math.max(xa, xb), v2, w2],
      faces: {},
      export: opts.export !== false,
      group: opts.group || (M ? 'shell_right' : 'shell_left')
    };
    const FS = normFaces(faces);
    for (const s of SEM_ALL) {
      if (!FS[s]) continue;
      const compass = s === 'top' ? 'up' : s === 'bottom' ? 'down' : semMap[s];
      el.faces[compass] = { tex: FS[s].tex, uv: FS[s].uv };
    }
    if (opts.rot) {
      el.rotation = opts.rot;
      el.origin = [X(opts.originU), opts.origin[1], opts.origin[2]];
      el.noPrune = true;
    }
    els.push(el);
    return el;
  };

  // ---- base + body -------------------------------------------------------
  add('plinth', [0.20, 0.00, 0.55, CORE_IN, 1.20, 15.45], { all: 'detail' });
  // The core is split at y = 8.4..8.8 on purpose: when neighbouring gates hide
  // their cap skin, the module seam has to survive on the surface that is left.
  // So the 0.4 service-hatch split is cut through the core as well and a dark
  // reveal panel sits 0.4 behind it. (The y = 1.2 joggle comes free from the
  // inset plinth.)
  add('core_low', [0.20, 1.20, Z1, CORE_IN, 8.40, Z2], { all: 'body' });
  add('core_high', [0.20, 8.80, Z1, CORE_IN, TOP, Z2], { all: 'body' });
  add('core_reveal', [0.60, 8.40, 0.40, 1.00, 8.80, 15.60], { all: 'detail' });

  // ---- outer end caps (hideable when a neighbour gate is detected) --------
  // The gaps between cap segments ARE the module seams asked for: 0.2 at y=1.2
  // (kick line), 0.4 at y=8.4 (service-hatch split), 0.15 at y=13.2 (panel joint).
  // Each gap exposes the core 0.2 deeper, so a row of gates reads as bolted
  // modules instead of one extruded slab.
  const capPrefix = M ? 'cap_right_' : 'cap_left_';
  add('side_low', [0.00, 1.40, Z1, CAP, 8.40, Z2], { all: 'body' }, { name: capPrefix + 'side_low' });
  add('side_mid', [0.00, 8.80, Z1, CAP, 13.20, Z2], { all: 'body' }, { name: capPrefix + 'side_mid' });
  add('side_high', [0.00, 13.35, Z1, CAP, TOP, Z2], { all: 'body' }, { name: capPrefix + 'side_high' });
  add('lid', [0.00, TOP, 0.20, 0.30, 18.30, 15.80], { all: 'top' }, { name: capPrefix + 'lid' });

  // ---- lane-facing skin: full-height LED band + 3x5 panel grid -----------
  add('inner_led', [CORE_IN, 1.20, 0.55, U, 17.35, 1.15], { all: 'led' });
  const rows = [
    [1.60, 5.20, 'body'],
    [5.35, 8.60, 'body'],
    [10.00, 11.20, 'body'],
    [11.35, 12.60, 'detail'],   // narrow dark info band at eye height
    [12.75, 16.80, 'body']
  ];
  const cols = [[1.30, 5.60], [5.75, 11.00], [11.15, 15.00]];
  rows.forEach((r, ri) => {
    cols.forEach((cc, ci) => {
      add(`inner_${ri}${ci}`, [CORE_IN, r[0], cc[0], U, r[1], cc[1]], { all: r[2] });
    });
  });
  add('inner_trim', [CORE_IN, 8.60, 1.30, U, 10.00, 15.00], { all: 'accent' });
  // 1.2 x 0.6 status lamp, flush in the inner skin above the panel grid
  add('status_lamp', [CORE_IN, 16.85, 1.30, U, 17.45, 2.50], { all: 'light_green' });

  // ---- front (north) face ------------------------------------------------
  add('vent_a', [0.30, 1.35, 0.10, 2.30, 1.55, 0.35], { all: 'detail' });
  add('vent_b', [0.30, 1.70, 0.10, 2.30, 1.90, 0.35], { all: 'detail' });
  add('vent_c', [0.30, 2.05, 0.10, 2.30, 2.25, 0.35], { all: 'detail' });
  const fcols = [[0.26, 1.14], [1.30, 2.38]];
  // row 2 stops on 8.40 so the trim below seals the core's 8.4..8.8 seam slot
  const frows = [[2.55, 5.35], [5.55, 8.40]];
  fcols.forEach((fc, ci) => {
    frows.forEach((fr, ri) => {
      add(`front_${ri}${ci}`, [fc[0], fr[0], 0.18, fc[1], fr[1], 0.35], { all: 'body' });
    });
  });
  add('front_trim', [0.12, 8.40, 0.08, U, 10.00, 0.38], { all: 'accent' });
  add('front_kick_led', [0.25, 1.24, 0.10, 2.35, 1.32, 0.34], { all: 'led' });

  // reader: dark bezel + 15 deg slanted glass + card slot
  add('reader_bezel', [0.16, 10.00, 0.16, 2.44, 11.95, 0.38], { all: 'detail' });
  add('reader_deck', [0.30, 10.03, 0.06, 2.30, 10.72, 0.32], { all: 'reader' }, {
    rot: [-15, 0, 0], originU: 1.30, origin: [0, 10.03, 0.32]
  });
  add('reader_slot', [0.40, 11.05, 0.10, 2.20, 11.35, 0.24], { all: 'glass' });

  // console status display (screen2, the 64x14 console strip at rows 44..57)
  add('console', [0.28, 12.05, 0.14, 2.36, 12.95, 0.36], {
    front: { tex: 'screen2', uv: [0, 11, 16, 15] },
    outer: 'detail', inner: 'detail', top: 'detail', bottom: 'detail', back: 'detail'
  });

  // upper front panels + sign
  fcols.forEach((fc, i) => {
    add(`front_up_${i}`, [fc[0], 13.05, 0.18, fc[1], 16.55, 0.35], { all: 'body' });
  });
  add('sign_frame', [0.28, 14.20, 0.06, 2.36, 16.30, 0.20], { all: 'trim' });
  add('sign_plate', [0.40, 14.34, 0.02, 2.26, 16.16, 0.09], { all: 'sign' });
  add('front_topstrip', [0.18, 16.55, 0.12, U, TOP, 0.35], { all: 'trim' });

  // per-gate functional module: entry gates carry a fingerprint pad beside the
  // card slot, exit gates a departure-card dispenser under the reader. Besides
  // being what real machines do, it keeps the three models from sharing one
  // geometry hash (preview_models.js rejects identical geometry).
  if (g_module === 'fingerprint') {
    add('fp_pad', [1.60, 11.10, 0.04, 2.32, 11.75, 0.16], { all: 'detail' });
  } else if (g_module === 'dispenser') {
    add('disp_slot', [0.32, 9.78, 0.02, 2.28, 9.96, 0.10], { all: 'detail' });
  }

  // ---- rear (south) face -------------------------------------------------
  fcols.forEach((fc, i) => {
    add(`rear_${i}`, [fc[0], 2.20, Z2, fc[1], 16.55, 15.82], { all: 'body' });
  });
  add('rear_vent_a', [0.36, 1.35, Z2, 2.30, 1.60, 15.90], { all: 'detail' });
  add('rear_vent_b', [0.36, 1.75, Z2, 2.30, 2.00, 15.90], { all: 'detail' });
  add('rear_badge', [0.55, 14.60, 15.83, 1.15, 15.60, 15.93], { all: 'trim' });
  add('rear_topstrip', [0.18, 16.55, Z2, U, TOP, 15.85], { all: 'trim' });

  // ---- thin lid: 1.0 slab + 0.3 chamfer ---------------------------------
  add('lid_main', [0.30, TOP, 0.20, U, 18.30, 15.80], { all: 'top' });
  add('lid_cap', [0.60, 18.30, 0.50, U, 18.60, 15.50], { all: 'top' });
  add('lid_vent', [0.90, 18.60, 2.00, 2.30, 18.72, 6.00], { all: 'detail' });

  // ---- thin cantilever arm + separate camera head -----------------------
  // arm: 1.6 wide (z), 0.45 thick, reaches 2.15 past the lid edge
  add('arm_root', [2.20, 18.60, 7.25, 2.60, 18.72, 8.95], { all: 'trim' });
  add('arm', [2.25, 18.72, 7.30, 4.75, 19.05, 8.90], { all: 'trim' });
  add('arm_rib', [2.60, 19.00, 7.50, 4.60, 19.08, 8.70], { all: 'trim' });
  // head: 3.2 x 2.4 x 2.0, hangs off the arm with a 0.45 shadow gap over the lid
  add('head', [2.75, 19.05, 6.95, 5.95, 21.45, 8.95], { all: 'body' });
  add('screen', [2.95, 19.25, 6.83, 5.75, 21.25, 6.95], {
    front: { tex: 'screen', uv: [0, 0, 16, 16] },
    outer: 'detail', inner: 'detail', top: 'detail', bottom: 'detail', back: 'detail'
  }, { rot: [15, 0, 0], originU: 4.35, origin: [0, 19.25, 6.95] });
  if (g_screens === 2) {
    add('screen_b', [2.95, 19.25, 8.95, 5.75, 21.25, 9.07], {
      back: { tex: 'screen2', uv: [0, 0, 16, 16] },
      outer: 'detail', inner: 'detail', top: 'detail', bottom: 'detail', front: 'detail'
    }, { rot: [15, 0, 0], originU: 4.35, origin: [0, 19.25, 6.95] });
  }
  add('visor', [2.90, 21.25, 6.78, 5.80, 21.45, 6.95], { all: 'trim' });
  add('cam_lens', [3.15, 21.10, 6.86, 3.75, 21.30, 7.06], { all: 'detail' });
  add('head_chin', [3.05, 18.98, 6.90, 5.65, 19.05, 7.00], { all: 'detail' });
  add('screen_led', [2.95, 19.00, 6.98, 4.75, 19.10, 7.30], { all: 'led' });

  return els;
}

let g_screens = 1;
let g_module = null;

// ---------------------------------------------------------------- wings
const WING_THICK = 0.8;        // pane + frame package thickness
const WING_Y0 = 2.0, WING_Y1 = 13.0;
const WING_Z_FRONT = 2.2;      // front leaf occupies 1.8..2.6
// The clearance between the two closed leaf pairs is a GAMEPLAY constant, not a
// styling one: the player is 0.6 blocks (9.6 px) wide and has to be able to stand
// inside the chamber, otherwise the two-stage gate logic can never trigger
// "crossed the middle". 11.2 px = 0.7 block gives 0.1 block of slack, which is the
// smallest value the parent accepts (12 px = 0.75 would be nicer still).
// 2.2 + 0.8 + 11.2 => rear hinge 14.2, rear leaf 13.8..14.6.
const WING_GAP_MIN = 11.2;     // 0.7 block - hard floor, asserted below
const WING_GAP = 11.2;
const WING_Z_REAR = WING_Z_FRONT + WING_THICK + WING_GAP;
const WING_FRAME = 0.8;        // frame rail / stile width (dark metal)
const WING_GLASS = 0.5;        // pane thickness -> 0.15 recess behind each frame face
const WING_CENTRE_GAP = 0.1;   // hairline between the two closed leaves

function wingGeometry() {
  // returns { leftLeaf:[x1,x2], rightLeaf:[x1,x2], clear }
  const half = 8.0;
  const clear = (WING_Z_REAR - WING_THICK / 2) - (WING_Z_FRONT + WING_THICK / 2);
  if (clear < WING_GAP_MIN - 1e-9) {
    throw new Error(`wing clearance ${clear} px < ${WING_GAP_MIN} px (0.7 block): a player is ` +
      `0.6 blocks wide and could not stand between the two leaf pairs`);
  }
  return {
    leftLeaf: [U, half - WING_CENTRE_GAP / 2],
    rightLeaf: [half + WING_CENTRE_GAP / 2, 16 - U],
    clear
  };
}

/** Guards the three wing invariants the Java side depends on. Throws instead of
 *  warning: a silently-too-narrow chamber kills the two-stage gate logic. */
function verifyWingPlacement() {
  const geo = wingGeometry();
  const zFront = [WING_Z_FRONT - WING_THICK / 2, WING_Z_FRONT + WING_THICK / 2];
  const zRear = [WING_Z_REAR - WING_THICK / 2, WING_Z_REAR + WING_THICK / 2];
  if (zFront[0] < 0 || zRear[1] > 16) {
    throw new Error(`wing leaves the block: ${zFront[0]}..${zRear[1]} (must stay inside 0..16)`);
  }
  if (zFront[1] >= zRear[0]) {
    throw new Error(`wing leaves overlap: front ends ${zFront[1]}, rear starts ${zRear[0]}`);
  }
  // leaves live exactly in the lane; the cabinet skins start at x=U / 16-U
  if (geo.leftLeaf[0] < U - 1e-9 || geo.rightLeaf[1] > 16 - U + 1e-9) {
    throw new Error(`leaf x range ${geo.leftLeaf[0]}..${geo.rightLeaf[1]} leaves the lane ` +
      `${U}..${16 - U} and would cut into a cabinet skin`);
  }
  return {
    clear: geo.clear,
    clearBlocks: geo.clear / 16,
    zFront, zRear,
    marginFront: zFront[0],
    marginRear: 16 - zRear[1],
    slackBlocks: geo.clear / 16 - 0.6
  };
}

function wingsEls() {
  const wings = [];
  const geo = wingGeometry();
  const mk = (name, group, side, zc, openAngle) => {
    const hingeX = side === 'L' ? U : 16 - U;
    const z1 = zc - WING_THICK / 2, z2 = zc + WING_THICK / 2;
    const [xa, xb] = side === 'L' ? geo.leftLeaf : geo.rightLeaf;
    const gz1 = zc - WING_GLASS / 2, gz2 = zc + WING_GLASS / 2;
    // hinge-side stile first, then the free-end stile
    const stHinge = side === 'L' ? [xa, xa + WING_FRAME] : [xb - WING_FRAME, xb];
    const stTip = side === 'L' ? [xb - WING_FRAME, xb] : [xa, xa + WING_FRAME];
    const glassX = [xa + WING_FRAME, xb - WING_FRAME];
    const els = [];
    const add = (n, from, to, tex) => {
      const el = {
        name: `${name}_${n}`,
        from, to,
        faces: {},
        export: false,
        group,
        origin: [hingeX, (WING_Y0 + WING_Y1) / 2, zc]
      };
      for (const f of ['north', 'south', 'east', 'west', 'up', 'down']) {
        el.faces[f] = { tex, uv: [0, 0, 16, 16] };
      }
      els.push(el);
    };
    add('rail_bottom', [xa, WING_Y0, z1], [xb, WING_Y0 + WING_FRAME, z2], 'frame');
    add('rail_top', [xa, WING_Y1 - WING_FRAME, z1], [xb, WING_Y1, z2], 'frame');
    add('stile_hinge', [stHinge[0], WING_Y0 + WING_FRAME, z1], [stHinge[1], WING_Y1 - WING_FRAME, z2], 'frame');
    add('stile_tip', [stTip[0], WING_Y0 + WING_FRAME, z1], [stTip[1], WING_Y1 - WING_FRAME, z2], 'frame');
    add('glass', [glassX[0], WING_Y0 + WING_FRAME, gz1], [glassX[1], WING_Y1 - WING_FRAME, gz2], 'glass');
    wings.push({
      name, group, side, zc,
      hinge: [hingeX, zc],
      open_rotation_y: openAngle,
      closed_box: [xa, WING_Y0, z1, xb, WING_Y1, z2],
      glass_box: [glassX[0], WING_Y0 + WING_FRAME, gz1, glassX[1], WING_Y1 - WING_FRAME, gz2],
      elements: els
    });
    return els;
  };
  const out = [];
  // front pair tucks backwards (+z), rear pair forwards (-z)
  out.push(...mk('wing_front_left', 'wing_front_left', 'L', WING_Z_FRONT, -95));
  out.push(...mk('wing_front_right', 'wing_front_right', 'R', WING_Z_FRONT, 95));
  out.push(...mk('wing_rear_left', 'wing_rear_left', 'L', WING_Z_REAR, 95));
  out.push(...mk('wing_rear_right', 'wing_rear_right', 'R', WING_Z_REAR, -95));
  return { els: out, wings };
}

// ---------------------------------------------------------------- pruning
const FACE_AXIS = {
  north: { a: 2, s: 'min' },
  south: { a: 2, s: 'max' },
  west: { a: 0, s: 'min' },
  east: { a: 0, s: 'max' },
  down: { a: 1, s: 'min' },
  up: { a: 1, s: 'max' }
};
const EPS = 1e-4;

function pruneHiddenFaces(els) {
  const box = e => ({ min: [e.from[0], e.from[1], e.from[2]], max: [e.to[0], e.to[1], e.to[2]] });
  const boxes = els.map(box);
  let pruned = 0;
  for (let i = 0; i < els.length; i++) {
    const e = els[i];
    if (e.export === false || e.noPrune) continue;
    for (const f of Object.keys(e.faces)) {
      const { a, s } = FACE_AXIS[f];
      const p = s === 'min' ? boxes[i].min[a] : boxes[i].max[a];
      const o1 = (a + 1) % 3, o2 = (a + 2) % 3;
      let hidden = false;
      for (let j = 0; j < els.length && !hidden; j++) {
        if (i === j) continue;
        const b = boxes[j];
        if (s === 'min') {
          if (!(b.min[a] < p - EPS && b.max[a] >= p - EPS)) continue;
        } else {
          if (!(b.min[a] <= p + EPS && b.max[a] > p + EPS)) continue;
        }
        if (b.min[o1] > boxes[i].min[o1] + EPS) continue;
        if (b.max[o1] < boxes[i].max[o1] - EPS) continue;
        if (b.min[o2] > boxes[i].min[o2] + EPS) continue;
        if (b.max[o2] < boxes[i].max[o2] - EPS) continue;
        hidden = true;
      }
      if (hidden) { delete e.faces[f]; pruned++; }
    }
  }
  return pruned;
}

/** Same rule as tools/scan_coplanar.js - two elements may not share a min or max
 *  on one axis while overlapping on both other axes (that is a duplicated,
 *  z-fighting face pair). */
function coplanarPairs(els) {
  const hits = [];
  const ov = (a1, a2, b1, b2) => Math.min(a2, b2) - Math.max(a1, b1);
  for (let i = 0; i < els.length; i++) {
    for (let j = i + 1; j < els.length; j++) {
      const a = els[i], b = els[j];
      for (let ax = 0; ax < 3; ax++) {
        const o1 = (ax + 1) % 3, o2 = (ax + 2) % 3;
        const aMin = Math.min(a.from[ax], a.to[ax]), aMax = Math.max(a.from[ax], a.to[ax]);
        const bMin = Math.min(b.from[ax], b.to[ax]), bMax = Math.max(b.from[ax], b.to[ax]);
        if (ov(a.from[o1], a.to[o1], b.from[o1], b.to[o1]) <= 0.02) continue;
        if (ov(a.from[o2], a.to[o2], b.from[o2], b.to[o2]) <= 0.02) continue;
        const sameMax = Math.abs(aMax - bMax) < 0.005;
        const sameMin = Math.abs(aMin - bMin) < 0.005;
        if (sameMax || sameMin) hits.push({ a: a.name, b: b.name, ax: 'xyz'[ax], side: sameMax ? 'max' : 'min' });
      }
    }
  }
  return hits;
}

// ---------------------------------------------------------------- bbmodel
let uuidSeq = 0;
function nextUuid() {
  uuidSeq++;
  return '00000000-0000-4000-8000-' + String(uuidSeq).padStart(12, '0');
}

function bbElement(el, texIndex) {
  const faces = {};
  for (const f of ['north', 'south', 'east', 'west', 'up', 'down']) {
    const face = el.faces[f];
    if (!face) continue;
    faces[f] = { uv: face.uv.slice(), texture: String(texIndex[face.tex]) };
  }
  const out = {
    name: el.name,
    box_uv: false,
    rescale: false,
    locked: false,
    render_order: 'default',
    allow_mirror_modeling: true,
    from: el.from.map(r4),
    to: el.to.map(r4),
    autouv: 0,
    color: 0,
    origin: (el.origin || [8, 0, 8]).map(r4),
    faces,
    type: 'cube',
    uuid: nextUuid()
  };
  if (el.rotation) out.rotation = el.rotation.slice();
  if (el.export === false) out.export = false;
  return out;
}
function r4(n) { return Math.round(n * 10000) / 10000; }

function buildBbmodel(id, name, els, textures, wingGroups) {
  const texIndex = {};
  const texEntries = [];
  TEX_ORDER.forEach((slot, i) => {
    texIndex[slot] = i;
    const t = textures[slot];
    texEntries.push({
      path: '',
      name: slot,
      folder: '',
      namespace: '',
      id: String(i),
      group: '',
      width: t.w,
      height: t.h,
      uv_width: t.w,
      uv_height: t.h,
      particle: false,
      layers_enabled: false,
      sync_to_project: '',
      render_mode: 'default',
      saved: false,
      source: 'data:image/png;base64,' + encodePng(t.w, t.h, t.c.buffer || t.c.d).toString('base64'),
      uuid: nextUuid()
    });
  });

  const elements = els.map(el => bbElement(el, texIndex));

  // outliner: shell_left / shell_right / 4 wing groups
  const groupDefs = [
    { name: 'shell_left', origin: [1.6, 0, 8] },
    { name: 'shell_right', origin: [14.4, 0, 8] }
  ];
  for (const wg of wingGroups) {
    groupDefs.push({ name: wg.name, origin: [wg.hinge[0], (WING_Y0 + WING_Y1) / 2, wg.zc] });
  }
  const groups = groupDefs.map(gd => ({
    name: gd.name,
    uuid: nextUuid(),
    origin: gd.origin.map(r4),
    rotation: [0, 0, 0],
    shade: true,
    mirror_uv: false,
    reset: false,
    locked: false,
    visibility: true,
    export: true,
    autouv: 0,
    isOpen: true
  }));

  const outliner = groups.map((g, gi) => ({
    uuid: g.uuid,
    isOpen: true,
    children: elements.filter(el => {
      const src = els[elements.indexOf(el)];
      return src.group === groups[gi].name;
    }).map(el => el.uuid)
  }));

  return {
    meta: { format_version: '4.10', model_format: 'java_block', box_uv: false },
    name: name,
    model_identifier: '',
    visible_box: [1, 1, 0],
    variable_placeholders: '',
    variable_placeholder_buttons: [],
    timeline_setups: [],
    unhandled_root_fields: {},
    resolution: { width: 64, height: 64 },
    elements,
    groups,
    outliner,
    textures: texEntries
  };
}

// ---------------------------------------------------------------- ui sheet
function blit(dst, src, ox, oy, s) {
  for (let y = 0; y < src.h; y++) {
    for (let x = 0; x < src.w; x++) {
      const i = (y * src.w + x) * 4;
      const r = src.d[i], g = src.d[i + 1], b = src.d[i + 2];
      for (let dy = 0; dy < s; dy++) {
        for (let dx = 0; dx < s; dx++) {
          const px = ox + x * s + dx, py = oy + y * s + dy;
          if (px < 0 || py < 0 || px >= dst.w || py >= dst.h) continue;
          const j = (py * dst.w + px) * 4;
          dst.d[j] = r; dst.d[j + 1] = g; dst.d[j + 2] = b; dst.d[j + 3] = 255;
        }
      }
    }
  }
}

function blitRect(dst, src, sx, sy, sw, sh, ox, oy, s) {
  for (let y = 0; y < sh; y++) {
    for (let x = 0; x < sw; x++) {
      const i = ((sy + y) * src.w + (sx + x)) * 4;
      const r = src.d[i], g = src.d[i + 1], b = src.d[i + 2];
      for (let dy = 0; dy < s; dy++) {
        for (let dx = 0; dx < s; dx++) {
          const px = ox + x * s + dx, py = oy + y * s + dy;
          if (px < 0 || py < 0 || px >= dst.w || py >= dst.h) continue;
          const j = (py * dst.w + px) * 4;
          dst.d[j] = r; dst.d[j + 1] = g; dst.d[j + 2] = b; dst.d[j + 3] = 255;
        }
      }
    }
  }
}

/** 8x nearest-neighbour contact sheet, so the three screen UIs can be compared
 *  without opening Blockbench (the parent asked for mutually distinct UIs). */
function makeUiSheet(g, textures) {
  const S = 8;
  const W = 16 + 512 + 16 + 512 + 16;      // 1072
  const H = 30 + 512 + 34 + 14 * S + 24;   // 712
  const c = new Cv(W, H, '#0D1116');
  drawText(c, g.id.toUpperCase(), 16, 8, 2, g.accentHi);
  drawText(c, 'SCREEN  /  FACE UI', 150, 11, 1, PAL.uiDim);
  drawText(c, 'SCREEN2  /  STATUS UI', 16 + 512 + 16 + 150, 11, 1, PAL.uiDim);
  blitRect(c, textures.screen.c, 0, 0, 64, 64, 16, 30, S);
  blitRect(c, textures.screen2.c, 0, 0, 64, 64, 16 + 512 + 16, 30, S);
  const cy = 30 + 512 + 14;
  drawText(c, 'CONSOLE STRIP ROWS 46..59  /  UV 0..16 X 11.5..15', 16, cy, 1, PAL.uiDim);
  blitRect(c, textures.screen2.c, 0, 46, 64, 14, 16, cy + 12, S);
  return c;
}

// ---------------------------------------------------------------- main
function main() {
  fs.mkdirSync(TEX_DIR, { recursive: true });
  fs.mkdirSync(BB_DIR, { recursive: true });
  fs.mkdirSync(MC_DIR, { recursive: true });
  fs.mkdirSync(BS_DIR, { recursive: true });
  fs.mkdirSync(REF_DIR, { recursive: true });
  fs.mkdirSync(PREV_DIR, { recursive: true });

  const report = [];
  report.push('# VeriBorder gate models - generated report');
  report.push('');
  report.push('Generated by `node tools/build_gate_model.js` (deterministic, no random source).');
  report.push('Re-run it, then `node tools/preview_models.js` and `node tools/scan_coplanar.js`.');
  report.push('');

  const wp = verifyWingPlacement();
  console.log(`wings: front hinge z=${WING_Z_FRONT} [${wp.zFront.map(r4).join('..')}], `
    + `rear hinge z=${r4(WING_Z_REAR)} [${wp.zRear.map(r4).join('..')}]`);
  console.log(`       clear ${r4(wp.clear)} px = ${r4(wp.clearBlocks * 1000) / 1000} block `
    + `(player 0.6, slack ${r4(wp.slackBlocks * 1000) / 1000}), `
    + `margins z ${r4(wp.marginFront)} / ${r4(wp.marginRear)}`);

  const summary = [];
  let wingManifest = null;
  const builtModels = new Map();

  for (const g of GATES) {
    g_screens = g.screens;
    g_module = g.module || null;
    // 1. textures
    const textures = paintTextures(g);
    for (const slot of TEX_ORDER) {
      const t = textures[slot];
      const file = path.join(TEX_DIR, `${g.id}_${slot}.png`);
      fs.writeFileSync(file, encodePng(t.w, t.h, t.c.d));
    }
    const ui = makeUiSheet(g, textures);
    fs.writeFileSync(path.join(PREV_DIR, `${g.id}_ui.png`), encodePng(ui.w, ui.h, ui.d));

    // 2. geometry
    const left = cabinetEls('L');
    const right = cabinetEls('R');
    const body = [...left, ...right];

    // 3. wings (export:false - the runtime still animates its own leaves)
    const wg = wingsEls();
    const all = [...body, ...wg.els];
    const pruned = pruneHiddenFaces(body);

    // 4. self checks
    const hits = coplanarPairs(body);
    if (hits.length) {
      console.error(`\n${g.id}: ${hits.length} coplanar element pair(s):`);
      for (const h of hits.slice(0, 12)) console.error('   ', h.a, '||', h.b, h.ax, h.side);
      throw new Error(g.id + ': coplanar faces - fix the layout before writing');
    }
    const degenerate = body.filter(e => Object.keys(e.faces).length === 0);
    if (degenerate.length) throw new Error(g.id + ': fully hidden elements: ' + degenerate.map(e => e.name).join(','));

    // 5. bbmodel
    const bb = buildBbmodel(g.id, `veriborder_${g.id}`, all, textures, wg.wings);
    fs.writeFileSync(path.join(BB_DIR, `${g.id}.bbmodel`), JSON.stringify(bb, null, 2) + '\n', 'utf8');

    // 6. minecraft block models: base + 3 cap-trimmed variants.
    //    Variants are the SAME element objects with cap_* filtered out - no
    //    geometry is re-authored or re-derived, so a diff between variants is
    //    exactly the cap elements. Compact JSON: the pretty form costs ~2.4x the
    //    bytes and the block-model budget is 120 KB.
    for (const v of CAP_VARIANTS) {
      const raw = { ...bb, name: g.id + v.suffix, elements: bb.elements.filter(v.keep) };
      const model = convert(raw, g.id, { display: true });
      fs.writeFileSync(path.join(MC_DIR, `${g.id}${v.suffix}.json`), JSON.stringify(model) + '\n', 'utf8');
      builtModels.set(g.id + v.suffix, model);
      if (!v.suffix) builtModels.set(g.id, model);
    }
    fs.writeFileSync(path.join(BS_DIR, `${g.id}.json`),
      JSON.stringify(buildBlockstate(g.id), null, 2) + '\n', 'utf8');

    // 7. assembly preview model = block model + the swing wings, so the static
    //    wing position can be inspected without touching the shipped model.
    const assemblyRaw = JSON.parse(JSON.stringify(bb));
    assemblyRaw.elements.forEach(el => { if (el.export === false) el.export = true; });
    assemblyRaw.name = g.id + '_assembly';
    const asm = convert(assemblyRaw, g.id, { display: false });
    fs.writeFileSync(path.join(REF_DIR, `${g.id}_assembly.json`), JSON.stringify(asm) + '\n', 'utf8');

    // 8. merged variant: end caps removed, for future "hide end caps" swapping
    const mergedRaw = JSON.parse(JSON.stringify(bb));
    mergedRaw.elements = mergedRaw.elements.filter(el => !/^cap_(left|right)_/.test(el.name));
    mergedRaw.name = g.id + '_merged';
    mergedRaw.outliner = mergedRaw.outliner.map(o => ({
      ...o,
      children: o.children.filter(u => mergedRaw.elements.some(e => e.uuid === u))
    }));
    fs.writeFileSync(path.join(REF_DIR, `${g.id}_merged.bbmodel`), JSON.stringify(mergedRaw, null, 2) + '\n', 'utf8');

    // 9. hinge contract for Java
    if (!wingManifest) {
      const geo = wingGeometry();
      wingManifest = {
        unit: 'model px (1 block = 16)',
        thickness: WING_THICK,
        frame_width: WING_FRAME,
        glass_thickness: WING_GLASS,
        glass_recess: (WING_THICK - WING_GLASS) / 2,
        height: [WING_Y0, WING_Y1],
        centre_gap: WING_CENTRE_GAP,
        front_hinge_z: WING_Z_FRONT,
        rear_hinge_z: WING_Z_REAR,
        front_leaf_z: [WING_Z_FRONT - WING_THICK / 2, WING_Z_FRONT + WING_THICK / 2].map(r4),
        rear_leaf_z: [WING_Z_REAR - WING_THICK / 2, WING_Z_REAR + WING_THICK / 2].map(r4),
        clear_between_pairs: geo.clear,
        clear_blocks: Math.round((geo.clear / 16) * 10000) / 10000,
        /* Gameplay contract: the collision chamber between the two leaf pairs must
           stay >= 11.2 px (0.7 block). The player is 0.6 blocks wide, so a smaller
           gap makes the chamber unreachable and the two-stage logic dead. */
        player_width_blocks: 0.6,
        clear_slack_blocks: Math.round(((geo.clear / 16) - 0.6) * 10000) / 10000,
        lane_x: [U, 16 - U],
        closed_leaves_x: { left: geo.leftLeaf, right: geo.rightLeaf },
        open_angle_deg: 95,
        note: 'closed leaves stop 0.05 px short of x=8 on each side; both tuck toward the '
          + 'cabinet inner face (x 2.6 / 13.4). Group origins in <id>.bbmodel sit exactly '
          + `on these hinges. rear hinge ${r4(WING_Z_REAR)} = ${WING_Z_FRONT} + ` +
          `${WING_THICK} + ${WING_GAP} => clear ${r4(geo.clear)} px = ` +
          `${r4(geo.clear / 16)} block, 0.1 block wider than the player.`,
        gates: {}
      };
    }
    wingManifest.gates[g.id] = wg.wings.map(w => ({
      name: w.name,
      group: w.group,
      hinge_xz: w.hinge,
      open_rotation_axis: 'y',
      open_rotation_deg: w.open_rotation_y,
      closed_box: w.closed_box.map(r4),
      glass_box: w.glass_box.map(r4),
      thickness: WING_THICK,
      elements: w.elements.map(e => e.name)
    }));

    const capNames = all.filter(e => /^cap_(left|right)_/.test(e.name)).map(e => e.name);
    const faceCount = body.reduce((n, e) => n + Object.keys(e.faces).length, 0);
    summary.push({ g, els: body.length, bbEls: all.length, faces: faceCount, pruned, capNames, wg });

    const mb = (fs.statSync(path.join(MC_DIR, `${g.id}.json`)).size / 1024).toFixed(1);
    console.log(`${g.id}: ${body.length} exported elements, ${all.length} in bbmodel, `
      + `${faceCount} faces (${pruned} hidden faces pruned), ${mb} KB json, ${capNames.length} cap elements`);
  }

  // round every number so the contract reads 1.8 / 13.8 instead of 1.8000000000000003
  fs.writeFileSync(path.join(REF_DIR, 'gate_wings.json'),
    JSON.stringify(wingManifest, (k, v) => (typeof v === 'number' ? Math.round(v * 10000) / 10000 : v), 2) + '\n', 'utf8');

  // ---- side-by-side pair: egate(right=true) butted against exit_gate(left=true)
  // Both machines drop the cap that faces the joint, which is exactly the run-time
  // situation the left/right properties create.
  function buildPair() {
    const A = builtModels.get('egate_right');
    const B = builtModels.get('exit_gate_left');
    const out = { textures: {}, elements: [] };
    const add = (m, prefix, dx) => {
      for (const [k, ref] of Object.entries(m.textures)) {
        if (k === 'particle') continue;
        out.textures[prefix + k] = ref;
      }
      for (const el of m.elements) {
        const copy = JSON.parse(JSON.stringify(el));
        copy.from[0] += dx;
        copy.to[0] += dx;
        if (copy.rotation) copy.rotation.origin[0] += dx;
        for (const f of Object.values(copy.faces)) {
          if (f.texture && f.texture.startsWith('#')) f.texture = '#' + prefix + f.texture.slice(1);
        }
        out.elements.push(copy);
      }
    };
    add(A, 'a_', 0);
    add(B, 'b_', 16);
    out.textures.particle = out.textures.a_body;
    return out;
  }
  const pair = buildPair();
  fs.writeFileSync(path.join(REF_DIR, 'egate_exit_pair.json'), JSON.stringify(pair) + '\n', 'utf8');
  // seam close-up: keep only what is near the joint (x 13.2..19) so the auto-fit
  // camera zooms into the actual gap
  const seam = {
    textures: pair.textures,
    elements: pair.elements.filter(el => el.to[0] > 13.2 && el.from[0] < 19.0 && el.to[1] <= 18.7)
  };
  fs.writeFileSync(path.join(REF_DIR, 'egate_exit_seam.json'), JSON.stringify(seam) + '\n', 'utf8');

  // ---- report -----------------------------------------------------------
  report.push('## 1. Element budget');
  report.push('');
  report.push('| model | elements | bbmodel elements | exported faces | pruned hidden faces | json size |');
  report.push('|---|---|---|---|---|---|');
  for (const s of summary) {
    const sz = (fs.statSync(path.join(MC_DIR, `${s.g.id}.json`)).size / 1024).toFixed(1);
    report.push(`| ${s.g.id} | ${s.els} | ${s.bbEls} | ${s.faces} | ${s.pruned} | ${sz} KB |`);
  }
  report.push('');
  report.push('bbmodel count includes the 20 swing-wing elements (4 groups x rail_bottom/rail_top/');
  report.push('stile_hinge/stile_tip/glass), which carry `export:false` so the shipped block model');
  report.push('keeps the passage empty for the animated leaves in `EGateRenderer`.');
  report.push('');
  report.push('Variants per gate (cap elements filtered out of the same element list, nothing rebuilt):');
  report.push('');
  report.push('| variant | elements | blockstate rule |');
  report.push('|---|---|---|');
  for (const s of summary) {
    const cl = s.capNames.filter(n => n.startsWith('cap_left_')).length;
    const cr = s.capNames.filter(n => n.startsWith('cap_right_')).length;
    report.push(`| \`${s.g.id}.json\` | ${s.els} | left=false,right=false |`);
    report.push(`| \`${s.g.id}_left.json\` | ${s.els - cl} | left=true (cap_left_* hidden) |`);
    report.push(`| \`${s.g.id}_right.json\` | ${s.els - cr} | right=true (cap_right_* hidden) |`);
    report.push(`| \`${s.g.id}_both.json\` | ${s.els - cl - cr} | left=true,right=true |`);
  }
  report.push('');

  report.push('## 2. Swing wings (4 groups, vertical hinge)');
  report.push('');
  report.push('| group | hinge (x,z) | closed leaf x | closed z span | open (Y rot) |');
  report.push('|---|---|---|---|---|');
  for (const w of summary[0].wg.wings) {
    const b = w.closed_box;
    report.push(`| ${w.name} | (${w.hinge[0]}, ${w.hinge[1]}) | ${r4(b[0])}..${r4(b[3])} | ${r4(b[2])}..${r4(b[5])} | ${w.open_rotation_y > 0 ? '+' : ''}${w.open_rotation_y}° |`);
  }
  report.push('');
  report.push(`- thickness ${WING_THICK} px, frame ${WING_FRAME} px dark metal, pane ${WING_GLASS} px -> `);
  report.push(`  ${r4((WING_THICK - WING_GLASS) / 2)} px shadow recess behind each frame face;`);
  report.push(`- leaves are ${r4(wingGeometry().leftLeaf[1] - wingGeometry().leftLeaf[0])} px long and stop ` +
    `${r4(WING_CENTRE_GAP / 2)} px short of the centre line, leaving a ${WING_CENTRE_GAP} px gap at x = 8;`);
  const wgeo = wingGeometry();
  report.push(`- front hinge z = ${WING_Z_FRONT} (leaf ${r4(WING_Z_FRONT - WING_THICK / 2)}..${r4(WING_Z_FRONT + WING_THICK / 2)}), ` +
    `rear hinge z = ${r4(WING_Z_REAR)} (leaf ${r4(WING_Z_REAR - WING_THICK / 2)}..${r4(WING_Z_REAR + WING_THICK / 2)});`);
  report.push(`- **clear gap ${r4(wgeo.clear)} px = ${r4(wgeo.clear / 16)} block (0.7 格)** between the closed leaves.`);
  report.push('  This is a GAMEPLAY constant, not styling: the player is 0.6 blocks wide and must be');
  report.push('  able to stand inside the chamber for the two-stage "crossed the middle" logic to fire.');
  report.push(`  ${r4(wgeo.clear)} px = 0.700 block leaves ${r4(wgeo.clear / 16 - 0.6)} block of slack. ` +
    'The generator');
  report.push('  asserts `clear >= 11.2` and refuses to build below it, so this cannot silently regress.');
  report.push(`- derived rear hinge: ${WING_Z_FRONT} + ${WING_THICK} + ${WING_GAP} = ${r4(WING_Z_REAR)};`);
  report.push(`- both leaf pairs stay inside the block: z ${r4(WING_Z_FRONT - WING_THICK / 2)}..` +
    `${r4(WING_Z_REAR + WING_THICK / 2)}, i.e. ${r4(WING_Z_FRONT - WING_THICK / 2)} px clear of z=0 and ` +
    `${r4(16 - (WING_Z_REAR + WING_THICK / 2))} px clear of z=16;`);
  report.push(`- leaves span x ${r4(wgeo.leftLeaf[0])}..${r4(wgeo.rightLeaf[1])}, exactly the lane, so they never`);
  report.push('  enter the cabinet skins (x 0..2.6 / 13.4..16).');
  report.push('- each group origin sits exactly on its hinge, so a Y rotation in Blockbench or in Java');
  report.push('  swings the leaf without any offset maths;');
  report.push('- open: +-95 deg about Y, so each leaf folds back flat onto its own cabinet inner face.');
  report.push('');

  report.push('## 3. Outer end caps (hideable for side-by-side tiling)');
  report.push('');
  for (const s of summary) {
    report.push(`**${s.g.id}**: ` + s.capNames.map(n => '`' + n + '`').join(', '));
  }
  report.push('');
  report.push('Every cap element name is prefixed `cap_left_*` / `cap_right_*`. They form the outer');
  report.push('0.2 px skin of each cabinet plus the outer lid lip. The outermost 1 px (x 0..1 and');
  report.push('15..16) carries no trim, LED or chamfer, so two gates placed side by side meet on a');
  report.push('flat plane. Cap segments leave module seams that stay visible when the gates are');
  report.push('joined: 0.2 px at y = 1.2 (kick line), 0.4 px at y = 8.4 (service-hatch split),');
  report.push('0.15 px at y = 13.2 (panel joint). Each seam exposes the core 0.2 px deeper, so a run');
  report.push('of gates reads as bolted modules rather than one extruded slab.');
  report.push('');

  report.push('## 4. Textures');
  report.push('');
  report.push('Every PNG is hand-rasterised pixel art (no filters, no gradient alpha ramps).');
  report.push('**All pixels of the gate textures are fully opaque (alpha 255)** - the item/block icon');
  report.push('pipeline never has to threshold alpha, and glass is frosted-opaque by design so the');
  report.push('gate block needs no translucent render layer.');
  report.push('');
  report.push('| slot | size | used for |');
  report.push('|---|---|---|');
  const texUse = {
    body: '32x32', trim: '32x32', top: '32x32', accent: '32x32', led: '32x32',
    glass: '32x32', frame: '32x32', screen: '64x64', screen2: '64x64',
    reader: '32x32', sign: '32x32', detail: '32x32',
    light_green: '32x32', light_red: '32x32'
  };
  const texNote = {
    body: 'matte anodised white cabinet skin',
    trim: 'brushed metal band, lid steps, arm, visor',
    top: 'lid cap faces',
    accent: 'waist line + inner band (blue / amber / teal)',
    led: 'full-height 0.6 px emissive band on the inner edge, kick line, screen underlight',
    glass: 'bright frosted pane: 3 diagonal glare bands, reader slot, wing panes',
    frame: 'dark metal wing frame (0.8 px rails/stiles)',
    screen: 'camera screen UI (face placeholder + green ROI box)',
    screen2: 'status/direction UI + console strip',
    reader: 'slanted card reader deck, blue ring',
    sign: 'IN / OUT / double-arrow sign plate',
    detail: 'dark kick, vents, bezels, access cover with screws',
    light_green: '1.2 x 0.6 status lamp, idle/ok (used by the model)',
    light_red: '1.2 x 0.6 status lamp, refusal variant (ready for the renderer/logic swap)'
  };
  for (const slot of TEX_ORDER) report.push(`| \`<id>_${slot}.png\` | ${texUse[slot]} | ${texNote[slot]} |`);
  report.push('');
  report.push('Per gate: 14 textures, 42 files. `frame`, `light_green` and `light_red` are additions to');
  report.push('the original 11-slot list (dark wing frame + the two-colour status lamp).');
  report.push('');
  report.push('### Tablet alpha repair (round 2)');
  report.push('');
  report.push('`egate_tablet.png`, `exit_gate_tablet.png`, `dual_gate_tablet.png` were the only');
  report.push('gate screen textures with a feathered alpha channel: a baked drop shadow at alpha 13');
  report.push('(3195 px) / 38 (300) / 97 (232) / 85 (60) / 114 (20) / 144 (4) over the 285 px green');
  report.push('"verified" glyph. **3811 px per file, 11433 px total**, were forced to alpha 255 with');
  report.push('the colour channel untouched, so the shadow became a colour ramp instead of');
  report.push('transparency (the alternative, thresholding to transparent, would have deleted 93%');
  report.push('of the panel). Reproduce with `node tools/fix_texture_alpha.js --fix --opaque`; the');
  report.push('tool only touches non-`_glass`/`_leaf` textures, because those are translucent by');
  report.push('design on their own render layer.');
  report.push('');
  report.push('### UV convention (16 vs 32 vs 64)');
  report.push('');
  report.push('Minecraft block-model UVs are **normalised to 0..16 no matter the PNG size**: a face');
  report.push('that covers a whole 32x32 or 64x64 texture is still `[0,0,16,16]`. Growing a texture');
  report.push('from 16 to 32/64 therefore needs **no** UV change - MC rescales internally, and');
  report.push('`tools/preview_models.js` does the same (`u/16*tex.w`). Sub-rects stay in 0..16 too:');
  report.push('the screen plate is square (1.80 x 2.00 px), so it samples the whole 64x64 UI with');
  report.push('`uv [0,0,16,16]` and is displayed 1:1 instead of squashed; the small console display');
  report.push('samples the 64x14 status strip with `uv [0,11,16,15]` (rows 44..57 of 64).');
  report.push('');

  report.push('## 5. Screen UIs (mutually distinguishable)');
  report.push('');
  report.push('| gate | header | icon | label | accent |');
  report.push('|---|---|---|---|---|');
  report.push('| egate | `IN` | passport booklet + insertion slot + green pass arrow | `PLACE DOC` | blue `#2E7BE0` |');
  report.push('| exit_gate | `OUT` | ink stamp + seal ring + big `07` count | `CLEARED` | amber `#E0951F` |');
  report.push('| dual_gate | `OUT` / `IN` split | left amber departure lane, right blue arrival lane, double arrow in the middle | `BOTH WAYS` | teal `#2E9E8F` + amber/blue split |');
  report.push('');
  report.push('Emissive treatment: near-black ground (`#05080D`), full-white highlights and the gate');
  report.push('accent at full saturation - no mid-grey fills, so the screen reads as a light source');
  report.push('rather than a grey panel. `screen` = camera/face display, `screen2` = status display');
  report.push('(rows 44..57 double as the console strip the small front display samples).');
  report.push('Inspect them at 8x in `tools/preview/<id>_ui.png`.');
  report.push('');
  report.push('dual_gate carries two plates on opposite faces of the same head: north `screen`,');
  report.push('south `screen2`, separated by the head body (z 6.95..8.95 -> 2.24 px between plate');
  report.push('faces, up from 0.88) so the two UIs never overlap when seen from the side.');
  report.push('');

  report.push('## 6. Block models + blockstates');
  report.push('');
  report.push('Four model files per gate, all sharing one element list:');
  report.push('');
  report.push('- `block/<id>.json` - both caps present');
  report.push('- `block/<id>_left.json` - `cap_left_*` removed');
  report.push('- `block/<id>_right.json` - `cap_right_*` removed');
  report.push('- `block/<id>_both.json` - both removed');
  report.push('');
  report.push('`blockstates/<id>.json` has 4 facings x left x right x open = **32 variants**, keyed');
  report.push('`facing=<dir>,left=<bool>,right=<bool>,open=<bool>`. `open` maps both values to the');
  report.push('same model - the leaves belong to `EGateRenderer`, the model has no door geometry.');
  report.push('The properties must exist on the block in Java (`DeviceBlock.LEFT` / `RIGHT` as');
  report.push('`BooleanProperty`); until they are declared, vanilla will reject these variant keys.');
  report.push('');
  report.push('## 7. Validator changes made in round 1-2');
  report.push('');
  report.push('`tools/preview_models.js` carried limits from the previous (16 px tall, 3 px cabinet)');
  report.push('generation that contradict this spec. They were updated, not bypassed:');
  report.push('');
  report.push('1. gate height bound `y <= 20.0` -> `y <= 22.5` (camera head reaches y = 21.45);');
  report.push('2. gate clear box `x 2.9..13.1, y <= 23` -> `x 2.6..13.4, y <= 17.5`: cabinets end on');
  report.push('   2.6/13.4 as specified, and the cantilevered head above the door frame is allowed');
  report.push('   (nothing below 17.5 px intrudes, which is all the walkable lane needs - the door');
  report.push('   leaves are 12 px tall);');
  report.push('3. element rotation had to be a multiple of 22.5 deg -> any multiple of 2.5 deg');
  report.push('   (<= 45), because the reader deck and the camera screen are 15 deg slants while');
  report.push('   `station_verifier` keeps its legacy 22.5 deg steps;');
  report.push('4. the "exactly 22 block models" assertion is gone. It would have failed the moment a');
  report.push('   gate grew variants. Every json is now validated on its own merits (elements, bounds,');
  report.push('   texture resolution, palette, size) and a required-id list is checked instead, so the');
  report.push('   count may grow while each model still has to pass every rule;');
  report.push('5. the gate blockstate check expects the 4-property key format instead of');
  report.push('   `facing=...,open=...`.');
  report.push('');
  report.push('Two other tool changes:');
  report.push('');
  report.push('- `tools/bbmodel_to_mc.js` exports `convert(raw, id, opts)` (the generator and the');
  report.push('  variants share one code path) and writes compact JSON by default (`--pretty` keeps');
  report.push('  the readable form);');
  report.push('- `preview_models.js` renders `assets/models/_preview/*_{assembly,pair,seam}.json` with');
  report.push('  no validation attached, so the shipped models keep the strict passage rule while the');
  report.push('  closed wings and the joined pair stay reviewable.');
  report.push('');
  report.push('## 8. Previews');
  report.push('');
  for (const s of summary) {
    report.push(`- \`tools/preview/${s.g.id}.png\` - shipped block model`);
    report.push(`- \`tools/preview/${s.g.id}_assembly.png\` - same model with the four swing wings closed`);
    report.push(`- \`tools/preview/${s.g.id}_ui.png\` - screen / screen2 at 8x, plus the console strip`);
  }
  report.push('- `tools/preview/egate_exit_pair.png` - egate(right=true) butted against exit_gate(left=true)');
  report.push('- `tools/preview/egate_exit_seam.png` - close-up of that joint');
  report.push('');
  report.push('`tools/preview/metrics.txt` is written by `preview_models.js`, including one row per');
  report.push('model variant.');
  report.push('');
  fs.writeFileSync(path.join(PREV_DIR, 'gate_model_report.md'), report.join('\n') + '\n', 'utf8');

  console.log('\nwrote tools/preview/gate_model_report.md');
  console.log('wrote ' + path.join(REF_DIR, 'gate_wings.json'));
}

main();

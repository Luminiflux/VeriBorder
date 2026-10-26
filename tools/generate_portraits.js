'use strict';
/*
 * VeriBorder 26.4.1 - ID-photo portrait asset generator
 * -----------------------------------------------------
 * SINGLE SOURCE OF TRUTH for src/main/resources/assets/veriborder/textures/portrait/
 * (p00.png .. p23.png + palette.json). This script is the only thing allowed to
 * write those files. Any other tool that needs the cast at another size must
 * READ the 32x32 presets and scale them (nearest neighbour) at runtime - do not
 * generate a second set of PNGs into this directory, or the 32x32 spec below
 * silently breaks. Ownership arbitrated by the project lead (26.4.1).
 *
 * Emits the 24 credential-photo presets used by the document / ID UI:
 *
 *   src/main/resources/assets/veriborder/textures/portrait/p00.png .. p23.png  (32x32)
 *   src/main/resources/assets/veriborder/textures/portrait/palette.json
 *   tools/preview/portraits_sheet.png                                         (6x4 contact sheet)
 *
 * Art direction (frozen - a preset that breaks one of these is a bug)
 * -------------------------------------------------------------------
 *   - plain studio ID photo: head-and-shoulders, nothing else in frame
 *   - head ~60 % of the canvas height (rows 4..22 of 32), centred on x = 15.5,
 *     14 px wide so it reads as a close-up "big head" crop
 *   - both ears visible on every preset (ears are painted above the hair)
 *   - backdrop   #D8DCE0 -> #EEF1F3 vertical gradient (7 ordered-dither bands)
 *     + studio floor shadow over the bottom 10 % of the photo
 *   - 1 px dark-grey photo border #3A3F45, with >= 2 px fully transparent
 *     margin outside the border (x/y = 0,1 and 30,31)
 *   - alpha is BINARY: 255 inside the photo card, 0 outside. No feathering.
 *   - <= 30 distinct colours per preset (grain is 1-bit ordered dither, never
 *     random noise), so the textures stay crisp at GUI scale
 *   - SEX MUST BE VISIBLE, NOT JUST IN palette.json. Men and women are drawn
 *     from different vocabularies: six tight male crops vs six female
 *     silhouettes that change the head outline (bob / shoulder / long / low
 *     ponytail / top bun / braid), plus female-only face rules (1 px brow,
 *     1 px eye + soft lash, fuller lip, narrower jaw) and 3 earrings.
 *   - glasses are rings around the eye, never a visor: thin = 1 px wire,
 *     thick = 2 px acetate #2A2E33, both leaving the eye pixels readable and
 *     at least 1 px of skin between the frame and the cheek.
 *   - three face shapes (narrow / standard / square) vary the jaw; a narrow
 *     face never gets thick frames (there is not enough face for 2 px frame +
 *     2 px eye + 1 px skin on both sides).
 *
 * Determinism
 * -----------
 * There is no random source anywhere: every pixel is placed by an explicit
 * coordinate table, and the only dithering is a fixed 4x4 Bayer matrix. Two
 * runs produce byte-identical PNGs, and generate() is safe to re-run.
 *
 * Shape / shading contract (shared by all 24 presets)
 * --------------------------------------------------
 *   rows  4..9   hair cap            row  11      brows
 *   row   13     eyes (3 px wide)    rows 15..16  nose
 *   row   18     mouth               rows 13..15  ears (x7..8 / x23..24)
 *   row   23     neck                rows 24..28  shoulders / outfit
 *
 *   key light sits upper-right  ->  every part has a 1 px form shadow on its
 *   LEFT edge and a 1 px rim light on its RIGHT edge ("soft contour light").
 *
 * The file is intentionally pure ASCII (Chinese UI strings are \u escaped) so
 * a careless editor / shell round-trip cannot corrupt the palette strings.
 *
 * Usage:  node tools/generate_portraits.js
 */

const fs = require('fs');
const path = require('path');
const zlib = require('zlib');

// the family font (3x5 glyphs) is reused for the contact sheet labels
const { FONT } = require('./lib/screen_kit');

const ROOT = path.resolve(__dirname, '..');
const TEX_DIR = path.join(ROOT, 'src', 'main', 'resources', 'assets', 'veriborder', 'textures', 'portrait');
const SHEET = path.join(ROOT, 'tools', 'preview', 'portraits_sheet.png');

// ---------------------------------------------------------------- PNG codec
// RGBA8, filter 0, same encoder shape as tools/lib/screen_kit.js.
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
function chunk(type, data) {
  const len = Buffer.alloc(4);
  len.writeUInt32BE(data.length, 0);
  const t = Buffer.from(type, 'ascii');
  const crc = Buffer.alloc(4);
  crc.writeUInt32BE(crc32(Buffer.concat([t, data])), 0);
  return Buffer.concat([len, t, data, crc]);
}
function writePng(file, w, h, rgba) {
  const stride = w * 4;
  const raw = Buffer.alloc((stride + 1) * h);
  for (let y = 0; y < h; y++) {
    raw[y * (stride + 1)] = 0; // filter: none
    Buffer.from(rgba.buffer, rgba.byteOffset + y * stride, stride).copy(raw, y * (stride + 1) + 1);
  }
  const ihdr = Buffer.alloc(13);
  ihdr.writeUInt32BE(w, 0);
  ihdr.writeUInt32BE(h, 4);
  ihdr[8] = 8; ihdr[9] = 6; ihdr[10] = 0; ihdr[11] = 0; ihdr[12] = 0;
  const png = Buffer.concat([
    Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]),
    chunk('IHDR', ihdr),
    chunk('IDAT', zlib.deflateSync(raw, { level: 9 })),
    chunk('IEND', Buffer.alloc(0))
  ]);
  fs.mkdirSync(path.dirname(file), { recursive: true });
  fs.writeFileSync(file, png);
}

// ---------------------------------------------------------------- colour
const hexRgb = (s) => [parseInt(s.slice(1, 3), 16), parseInt(s.slice(3, 5), 16), parseInt(s.slice(5, 7), 16)];
const rgbHex = (c) => '#' + c.map((v) => Math.max(0, Math.min(255, Math.round(v))).toString(16).padStart(2, '0')).join('');
/** Linear blend; t = 0 returns a, t = 1 returns b. */
const mixHex = (a, b, t) => {
  const A = hexRgb(a), B = hexRgb(b);
  return rgbHex([A[0] + (B[0] - A[0]) * t, A[1] + (B[1] - A[1]) * t, A[2] + (B[2] - A[2]) * t]);
};
/** Darken toward black by factor t (0..1). */
const darkenHex = (a, t) => mixHex(a, '#000000', t);
/** Perceived luminance 0..255 - shared with tools/audit_portrait_assets.js. */
const luma = (hex) => {
  const [r, g, b] = hexRgb(hex);
  return 0.2126 * r + 0.7152 * g + 0.0722 * b;
};

// ---------------------------------------------------------------- raster
/**
 * Minimal opaque/transparent RGBA canvas with strict colour bookkeeping.
 * Alpha is only ever 0 or 255 by construction (px() writes 255, the buffer
 * starts at 0), which is what the "binary alpha" acceptance criterion checks.
 */
class Raster {
  constructor(w, h, bg) {
    this.w = w; this.h = h;
    this.d = new Uint8Array(w * h * 4);
    if (bg) this.rect(0, 0, w - 1, h - 1, bg);
  }
  px(x, y, hex) {
    x |= 0; y |= 0;
    if (x < 0 || y < 0 || x >= this.w || y >= this.h) return;
    const c = hexRgb(hex), i = (y * this.w + x) * 4;
    this.d[i] = c[0]; this.d[i + 1] = c[1]; this.d[i + 2] = c[2]; this.d[i + 3] = 255;
  }
  span(y, x0, x1, hex) { for (let x = x0; x <= x1; x++) this.px(x, y, hex); }
  rect(x0, y0, x1, y1, hex) { for (let y = y0; y <= y1; y++) this.span(y, x0, x1, hex); }
  at(x, y) {
    const i = (y * this.w + x) * 4;
    return [this.d[i], this.d[i + 1], this.d[i + 2], this.d[i + 3]];
  }
  /** Nearest-neighbour scale blit - used to build the contact sheet. */
  blit(src, dx, dy, s) {
    for (let y = 0; y < src.h; y++) {
      for (let x = 0; x < src.w; x++) {
        const [r, g, b, a] = src.at(x, y);
        if (!a) continue;
        const col = rgbHex([r, g, b]);
        for (let j = 0; j < s; j++) for (let i = 0; i < s; i++) this.px(dx + x * s + i, dy + y * s + j, col);
      }
    }
  }
  uniqueColors() {
    const set = new Set();
    for (let i = 0; i < this.d.length; i += 4) {
      if (!this.d[i + 3]) continue;
      set.add((this.d[i] << 16) | (this.d[i + 1] << 8) | this.d[i + 2]);
    }
    return [...set];
  }
}

// ---------------------------------------------------------------- 3x5 text
function drawText(r, x, y, str, hex, scale) {
  const s = scale || 1;
  let cx = x;
  for (const ch of String(str).toUpperCase()) {
    const g = FONT[ch] || FONT['?'];
    for (let gy = 0; gy < 5; gy++) {
      for (let gx = 0; gx < 3; gx++) {
        if (g[gy][gx] !== '#') continue;
        r.rect(cx + gx * s, y + gy * s, cx + gx * s + s - 1, y + gy * s + s - 1, hex);
      }
    }
    cx += 4 * s;
  }
  return cx;
}
const textW = (str, s) => (String(str).length ? String(str).length * 4 * (s || 1) - (s || 1) : 0);

// ================================================================ design data
const W = 32, H = 32;

/** Photo card: frame rect (1 px #3A3F45) and the interior it encloses. */
const CARD = { x0: 2, y0: 2, x1: 29, y1: 29 };
const INNER = { x0: CARD.x0 + 1, y0: CARD.y0 + 1, x1: CARD.x1 - 1, y1: CARD.y1 - 1 };
const FRAME_INK = '#3A3F45';

/** Hard colour budget per preset (the brief allowed 48; the lead settled on 30). */
const MAX_COLOURS = 30;

/** Backdrop ramp endpoints (spec) + studio floor shadow depth. */
const BG_TOP = '#D8DCE0', BG_BOT = '#EEF1F3';
const BG_BANDS = 7;
const BAYER4 = [0, 8, 2, 10, 12, 4, 14, 6, 3, 11, 1, 9, 15, 7, 13, 5].map((v) => (v + 0.5) / 16);

/** Vertical landmarks - every preset shares them so the grid reads as one set. */
const HEAD_TOP = 4;    // first row of skull (hair may start here too)
const CHIN = 22;       // last row of the face
const BROW_Y = 11;
const EYE_Y = 13;
const MOUTH_Y = 19;
const NECK_Y = 23;
const BODY_TOP = 24;

/**
 * Half-width (px) of the face per row; 19 entries, index = y - HEAD_TOP.
 *   y:            4  5  6  7  8  9 10 11 12 13 14 15 16 17 18 19 20 21 22
 *   male width:   4  8 12 14 14 14 14 14 14 14 14 14 12 12 10 10  8  8  6
 *   female width: 4  8 12 14 14 14 14 14 14 14 14 14 12 10 10  8  8  6  6
 * The female jaw is one step narrower from the cheekbone down.
 */
const FACE_K_M = [2, 4, 6, 7, 7, 7, 7, 7, 7, 7, 7, 7, 6, 6, 5, 5, 4, 4, 3];
const FACE_K_F = [2, 4, 6, 7, 7, 7, 7, 7, 7, 7, 7, 7, 6, 5, 5, 4, 4, 3, 2];

/**
 * Face shapes (the "same mould" fix): every preset carries one of three skull
 * silhouettes on top of the base profile above.
 *   narrow   - one step slimmer from the temple down (12 px face, 6 px jaw)
 *   standard - the profile above
 *   square   - cheekbones held wide down to the jaw, chin still rounded (14 px)
 * Narrow faces are never combined with thick glasses: a 2 px frame plus a 2 px
 * eye plus 1 px of skin on both sides needs 14 px of face, which a narrow face
 * does not have. That constraint is asserted by tools/audit_portrait_assets.js.
 */
const SHAPE = {
  narrow: (k, y) => (y >= 7 ? Math.max(2, k - 1) : k),
  standard: (k) => k,
  square: (k, y) => (y >= 16 && y <= 19 ? Math.min(7, k + 1) : k)
};

/** Shoulder trapezoid: [y, x0, x1] - flares to the full card width by y27. */
const BODY_ROWS = [[24, 10, 21], [25, 7, 24], [26, 5, 26], [27, INNER.x0, INNER.x1], [28, INNER.x0, INNER.x1]];

// 4 skin tones x 5 hair colours x 6 hairstyles x 3 glasses x 2 beards x 4 outfits
const SKIN = {
  fair: { base: '#F2D8BE', light: '#FBEBDB', shade: '#DDBA97', deep: '#BC9271', lip: '#C08A80' },
  light: { base: '#E7C3A0', light: '#F4DDC4', shade: '#CBA47D', deep: '#A67D5B', lip: '#B47E74' },
  tan: { base: '#CD9769', light: '#E3B892', shade: '#AC7950', deep: '#8A5B39', lip: '#A2655A' },
  deep: { base: '#96603E', light: '#B27E58', shade: '#7A4728', deep: '#5A311A', lip: '#81443B' }
};

const HAIR = {
  black: { base: '#2A2C31', shade: '#1A1C20', light: '#464B53' },
  darkbrown: { base: '#40291D', shade: '#2B1A11', light: '#5E3F2D' },
  chestnut: { base: '#6B3B24', shade: '#4A2615', light: '#8D5534' },
  blond: { base: '#B78B4E', shade: '#8C6736', light: '#D9B274' },
  grey: { base: '#A9AEB5', shade: '#868C94', light: '#C9CED5' }
};

const OUTFIT = {
  suit_dark: { base: '#2F3742', shade: '#222932', light: '#49535F', shirt: '#E8EDF1', accent: '#151B22', tie: '#5A6776', band: '#49535F' },
  suit_light: { base: '#C2CAD2', shade: '#A3ACB6', light: '#DEE5EB', shirt: '#F8FBFC', accent: '#8D97A2', tie: '#3D5A6B', band: '#A3ACB6' },
  shirt: { base: '#DCE7EF', shade: '#BCCCD8', light: '#F2F8FC', shirt: '#F2F8FC', accent: '#A7B9C6', tie: null, band: '#C6D6E2' },
  uniform: { base: '#26374A', shade: '#182533', light: '#3C5269', shirt: '#E4EBF1', accent: '#C9A85C', tie: null, band: '#93AEC6' },
  // female-only cuts: a round-neck blouse and a blazer with a bright shell
  blouse: { base: '#EDF2F7', shade: '#CBD8E3', light: '#FBFDFE', shirt: '#FFFFFF', accent: '#B3C4D2', tie: null, band: '#DCE6EE' },
  suit_female: { base: '#3B4552', shade: '#2A323D', light: '#55606F', shirt: '#F6FAFC', accent: '#9FB3C4', tie: null, band: '#4E5A69' }
};

const LIP_LINE_DARK = '#4A2A28'; // generic mouth line, blended per skin tone below
const SCLERA = '#F1F4F6';
const PUPIL = '#23272C';
const GLASS_THIN = '#6C747C';   // thin wire frame, 1 px
const GLASS_THICK = '#3E444B';  // acetate frame: 1 px like the wire, just darker
const EARRING = '#D8B45C';      // 1 px gold stud, only on a few women

// ---------------------------------------------------------------- hairstyles
// rows: [y, x0, x1, role]   dots: [x, y, role]   fringeY: forehead shadow row
// role: 'b' base, 's' shade, 'l' light, 'm' mixed (scalp stubble of the buzz cut)
//
// The men's six styles keep the head outline tight (cap only). The women's six
// deliberately CHANGE the silhouette - length past the jaw, a bun on top, a braid
// down one side - because hair colour alone did not read as female at 32 px.
/** Vertical hair curtain beside the head: the silhouette of the long styles. */
function curtain(y0, y1, leftX, rightX, taperFrom) {
  const rows = [];
  for (let y = y0; y <= y1; y++) {
    const t = (taperFrom !== undefined && y >= taperFrom) ? 1 : 0;
    rows.push([y, leftX[0] + t, leftX[1], 'b'], [y, rightX[0], rightX[1] - t, 'b']);
  }
  return rows;
}

const HAIRSTYLES = {
  // ===== men ==============================================================
  short: {
    fringeY: 10,
    rows: [
      [4, 13, 18, 'b'], [5, 11, 20, 'b'], [6, 9, 22, 'b'], [7, 8, 23, 'b'],
      [8, 8, 23, 'b'], [9, 9, 22, 'b'],
      [10, 8, 9, 's'], [10, 22, 23, 's'], [11, 8, 9, 's'], [11, 22, 23, 's'],
      [12, 8, 8, 's'], [12, 23, 23, 's']
    ],
    dots: [[17, 5, 'l'], [18, 5, 'l'], [16, 6, 'l'], [15, 7, 'l']]
  },
  slick: {
    fringeY: 8,
    rows: [
      [4, 11, 20, 'b'], [5, 9, 22, 'b'], [6, 8, 23, 'b'], [7, 8, 23, 'b'],
      [8, 8, 9, 's'], [8, 22, 23, 's'], [9, 8, 9, 's'], [9, 22, 23, 's'],
      [10, 8, 8, 's'], [10, 23, 23, 's']
    ],
    dots: [[14, 4, 'l'], [15, 4, 'l'], [16, 4, 'l'], [17, 4, 'l'], [13, 5, 'l'], [12, 6, 'l'],
      [18, 5, 's'], [19, 6, 's'], [20, 7, 's']]
  },
  curly: {
    fringeY: 10,
    rows: [
      [4, 11, 14, 'b'], [4, 17, 20, 'b'], [5, 10, 21, 'b'], [6, 8, 23, 'b'],
      [7, 7, 24, 'b'], [8, 8, 23, 'b'], [9, 9, 22, 'b'],
      [10, 8, 9, 's'], [10, 22, 23, 's'], [11, 8, 8, 's'], [11, 23, 23, 's'],
      [12, 8, 8, 's'], [12, 23, 23, 's']
    ],
    dots: [
      [12, 4, 'l'], [15, 4, 'l'], [18, 4, 'l'],
      [10, 5, 'l'], [14, 5, 'l'], [19, 5, 'l'],
      [9, 6, 'l'], [13, 6, 'l'], [20, 6, 'l'],
      [10, 7, 'l'], [16, 7, 'l'], [21, 7, 'l'],
      [11, 4, 's'], [19, 4, 's'], [15, 7, 's']
    ]
  },
  buzz: {
    fringeY: 8,
    rows: [
      [4, 14, 17, 'm'], [5, 11, 20, 'm'], [6, 9, 22, 'm'], [7, 10, 21, 'm'],
      [8, 8, 9, 's'], [8, 22, 23, 's'], [9, 8, 8, 's'], [9, 23, 23, 's']
    ],
    dots: [[13, 5, 'l'], [14, 5, 'l']]
  },
  bald: {
    fringeY: 0,
    rows: [],
    dots: [[16, 4, 'l'], [17, 4, 'l'], [18, 5, 'l'], [13, 5, 'l'], [19, 6, 'l']]
  },

  // ===== women: six silhouettes that read at 1x ============================
  // bob: a wide mass that stops at the jaw, so the outline is round and short
  w_bob: {
    fringeY: 10,
    rows: [
      [4, 13, 18, 'b'], [5, 11, 20, 'b'], [6, 9, 22, 'b'], [7, 8, 23, 'b'],
      [8, 9, 22, 'b'], [9, 9, 11, 'b']
    ],
    sideMass: { y0: 9, y1: 19, out: 4 },
    dots: [[15, 5, 'l'], [16, 5, 'l'], [14, 6, 'l'], [5, 12, 'l'], [26, 12, 'l'], [6, 15, 's'], [25, 15, 's']]
  },
  // shoulder-length straight: a solid side mass that hugs the face down to the jaw
  w_shoulder: {
    fringeY: 10,
    rows: [
      [4, 13, 18, 'b'], [5, 11, 20, 'b'], [6, 9, 22, 'b'], [7, 8, 23, 'b'],
      [8, 9, 22, 'b']
    ],
    sideMass: { y0: 9, y1: 22, out: 6 },
    dots: [[15, 5, 'l'], [16, 5, 'l'], [14, 6, 'l'], [6, 12, 'l'], [25, 12, 'l'], [7, 19, 's'], [24, 19, 's']]
  },
  // long straight: same mass, longer - it reaches the shoulder line and runs 1 px wider
  w_long: {
    fringeY: 10,
    rows: [
      [4, 13, 18, 'b'], [5, 11, 20, 'b'], [6, 9, 22, 'b'], [7, 8, 23, 'b'],
      [8, 9, 22, 'b']
    ],
    sideMass: { y0: 8, y1: 24, out: 5 },
    dots: [[15, 5, 'l'], [16, 5, 'l'], [14, 6, 'l'], [5, 13, 'l'], [26, 13, 'l'], [6, 23, 's'], [25, 23, 's']]
  },
  // low ponytail: tail hangs at the nape, 2-3 px visible past the jaw
  w_lowpony: {
    fringeY: 10,
    rows: [
      [4, 13, 18, 'b'], [5, 11, 20, 'b'], [6, 9, 22, 'b'], [7, 8, 23, 'b'],
      [8, 9, 22, 'b'], [9, 8, 9, 'b'], [9, 21, 22, 'b'],
      [10, 8, 9, 'b'], [10, 22, 23, 'b'],
      [11, 8, 8, 'b'], [11, 23, 24, 'b'],
      [12, 23, 24, 'b'], [13, 24, 25, 'b'], [14, 24, 26, 'b'], [15, 24, 26, 'b'],
      [16, 24, 26, 'b'], [17, 24, 26, 'b'], [18, 25, 26, 'b'], [19, 25, 26, 'b'],
      [20, 25, 26, 'b'], [21, 25, 26, 's'], [22, 25, 26, 's']
    ],
    dots: [[15, 5, 'l'], [16, 5, 'l'], [25, 14, 'l'], [24, 17, 'l'], [25, 20, 's']]
  },
  // top bun: a broad knob above the crown, separated from the head by a band
  w_bun: {
    fringeY: 9,
    rows: [
      [3, 14, 17, 'b'], [4, 12, 19, 'b'],
      [5, 11, 20, 'b'], [6, 9, 22, 'b'], [7, 8, 23, 'b'], [8, 9, 22, 'b'],
      [9, 8, 9, 's'], [9, 22, 23, 's'], [10, 8, 8, 's'], [10, 23, 23, 's']
    ],
    dots: [[13, 3, 'l'], [14, 3, 'l'], [15, 3, 'l'],
      [13, 5, 's'], [14, 5, 's'], [15, 5, 's'], [16, 5, 's'], [17, 5, 's'], [18, 5, 's'],
      [16, 4, 'l'], [17, 4, 'l']]
  },
  // braid: zig-zag segments down one side, tied off at the end
  w_braid: {
    fringeY: 10,
    rows: [
      [4, 13, 18, 'b'], [5, 11, 20, 'b'], [6, 9, 22, 'b'], [7, 8, 23, 'b'],
      [8, 9, 22, 'b'], [9, 8, 9, 'b'], [9, 21, 22, 'b'],
      [10, 8, 9, 'b'], [10, 22, 24, 'b'],
      [11, 23, 24, 'b'], [12, 24, 25, 'b'], [13, 23, 24, 'b'], [14, 24, 25, 'b'],
      [15, 23, 24, 'b'], [16, 24, 25, 'b'], [17, 23, 24, 'b'], [18, 24, 25, 'b'],
      [19, 23, 24, 'b'], [20, 23, 24, 's']
    ],
    dots: [[15, 5, 'l'], [16, 5, 'l'], [24, 12, 'l'], [24, 16, 'l'], [23, 19, 's']]
  }
};

// ---------------------------------------------------------------- spec table
// 24 presets. Every value of every axis is used at least once (see coverage()).
// Plausibility rules: beards / shaved heads only on men, ponytails only on women,
// grey hair only on the middle-aged band, and thick frames never on a narrow face
// (a 2 px frame + 2 px eye + 1 px skin each side needs the full 14 px face).
const SPECS = [
  // ---- men (12) ------------------------------------------------------------
  { id: 'p00', sex: 'male', age: 'youth', skin: 'fair', hairColor: 'black', hairStyle: 'short', glasses: 'none', beard: 'none', outfit: 'suit_dark', faceShape: 'square' },
  { id: 'p01', sex: 'male', age: 'middle', skin: 'light', hairColor: 'darkbrown', hairStyle: 'slick', glasses: 'thin', beard: 'stubble', outfit: 'suit_light', faceShape: 'standard' },
  { id: 'p02', sex: 'male', age: 'youth', skin: 'tan', hairColor: 'chestnut', hairStyle: 'buzz', glasses: 'thick', beard: 'stubble', outfit: 'shirt', faceShape: 'square' },
  { id: 'p03', sex: 'male', age: 'youth', skin: 'deep', hairColor: 'darkbrown', hairStyle: 'bald', glasses: 'none', beard: 'stubble', outfit: 'uniform', faceShape: 'square' },
  { id: 'p04', sex: 'male', age: 'middle', skin: 'fair', hairColor: 'blond', hairStyle: 'curly', glasses: 'thin', beard: 'stubble', outfit: 'suit_dark', faceShape: 'narrow' },
  { id: 'p05', sex: 'male', age: 'middle', skin: 'light', hairColor: 'grey', hairStyle: 'slick', glasses: 'thick', beard: 'stubble', outfit: 'shirt', faceShape: 'square' },
  { id: 'p06', sex: 'male', age: 'youth', skin: 'tan', hairColor: 'black', hairStyle: 'short', glasses: 'thick', beard: 'stubble', outfit: 'suit_light', faceShape: 'standard' },
  { id: 'p07', sex: 'male', age: 'youth', skin: 'deep', hairColor: 'darkbrown', hairStyle: 'buzz', glasses: 'thin', beard: 'stubble', outfit: 'uniform', faceShape: 'narrow' },
  { id: 'p08', sex: 'male', age: 'middle', skin: 'fair', hairColor: 'grey', hairStyle: 'slick', glasses: 'none', beard: 'none', outfit: 'uniform', faceShape: 'standard' },
  { id: 'p09', sex: 'male', age: 'youth', skin: 'light', hairColor: 'chestnut', hairStyle: 'bald', glasses: 'thin', beard: 'none', outfit: 'suit_dark', faceShape: 'narrow' },
  { id: 'p10', sex: 'male', age: 'middle', skin: 'tan', hairColor: 'blond', hairStyle: 'bald', glasses: 'thick', beard: 'stubble', outfit: 'shirt', faceShape: 'square' },
  { id: 'p11', sex: 'male', age: 'middle', skin: 'deep', hairColor: 'grey', hairStyle: 'bald', glasses: 'none', beard: 'none', outfit: 'suit_light', faceShape: 'standard' },
  // ---- women (12) ----------------------------------------------------------
  // Female presets use the six female silhouettes (w_*) and, for half of them,
  // the two female-only outfits, so the sex reads from the drawing itself.
  { id: 'p12', sex: 'female', age: 'youth', skin: 'fair', hairColor: 'black', hairStyle: 'w_bun', glasses: 'none', beard: 'none', outfit: 'uniform', faceShape: 'narrow' },
  { id: 'p13', sex: 'female', age: 'middle', skin: 'light', hairColor: 'chestnut', hairStyle: 'w_bob', glasses: 'thin', beard: 'none', outfit: 'blouse', faceShape: 'narrow', earring: true },
  { id: 'p14', sex: 'female', age: 'youth', skin: 'tan', hairColor: 'darkbrown', hairStyle: 'w_shoulder', glasses: 'thick', beard: 'none', outfit: 'suit_female', faceShape: 'square' },
  { id: 'p15', sex: 'female', age: 'middle', skin: 'deep', hairColor: 'chestnut', hairStyle: 'w_long', glasses: 'none', beard: 'none', outfit: 'suit_light', faceShape: 'standard' },
  { id: 'p16', sex: 'female', age: 'middle', skin: 'fair', hairColor: 'blond', hairStyle: 'w_braid', glasses: 'thick', beard: 'none', outfit: 'blouse', faceShape: 'square' },
  { id: 'p17', sex: 'female', age: 'middle', skin: 'light', hairColor: 'grey', hairStyle: 'w_bob', glasses: 'thick', beard: 'none', outfit: 'shirt', faceShape: 'standard' },
  { id: 'p18', sex: 'female', age: 'youth', skin: 'tan', hairColor: 'black', hairStyle: 'w_lowpony', glasses: 'thin', beard: 'none', outfit: 'suit_female', faceShape: 'narrow' },
  { id: 'p19', sex: 'female', age: 'youth', skin: 'deep', hairColor: 'darkbrown', hairStyle: 'w_shoulder', glasses: 'none', beard: 'none', outfit: 'uniform', faceShape: 'standard' },
  { id: 'p20', sex: 'female', age: 'youth', skin: 'fair', hairColor: 'chestnut', hairStyle: 'w_bun', glasses: 'thick', beard: 'none', outfit: 'blouse', faceShape: 'square', earring: true },
  { id: 'p21', sex: 'female', age: 'middle', skin: 'light', hairColor: 'blond', hairStyle: 'w_long', glasses: 'none', beard: 'none', outfit: 'suit_light', faceShape: 'standard' },
  { id: 'p22', sex: 'female', age: 'middle', skin: 'tan', hairColor: 'grey', hairStyle: 'w_lowpony', glasses: 'thin', beard: 'none', outfit: 'suit_dark', faceShape: 'narrow', earring: true },
  { id: 'p23', sex: 'female', age: 'youth', skin: 'deep', hairColor: 'black', hairStyle: 'w_braid', glasses: 'thin', beard: 'none', outfit: 'suit_female', faceShape: 'narrow' }
];

const AXES = {
  skin: ['fair', 'light', 'tan', 'deep'],
  hairColor: ['black', 'darkbrown', 'chestnut', 'blond', 'grey'],
  // 12 style values: 6 male crops (p00..p11) + 6 female silhouettes (p12..p23).
  // The male tied-back cut was retired when the women moved to their own set;
  // 低马尾 (w_lowpony) carries the ponytail idea instead.
  hairStyle: ['short', 'slick', 'curly', 'buzz', 'bald',
    'w_bob', 'w_shoulder', 'w_long', 'w_lowpony', 'w_bun', 'w_braid'],
  glasses: ['none', 'thin', 'thick'],
  beard: ['none', 'stubble'],
  outfit: ['suit_dark', 'suit_light', 'shirt', 'uniform', 'blouse', 'suit_female'],
  sex: ['male', 'female'],
  age: ['youth', 'middle'],
  faceShape: ['narrow', 'standard', 'square']
};

/** palette.json display strings (Chinese, required by the UI contract). */
const ZH = {
  sex: { male: '\u7537', female: '\u5973' },
  age: { youth: '\u9752\u5e74', middle: '\u4e2d\u5e74' },
  hairStyle: {
    short: '\u77ed\u53d1',
    slick: '\u80cc\u5934',
    curly: '\u5377\u53d1',
    ponytail: '\u9a6c\u5c3e',
    buzz: '\u5bf8\u5934',
    bald: '\u5149\u5934',
    w_bob: '\u9f50\u8033\u77ed\u53d1',
    w_shoulder: '\u9f50\u80a9\u76f4\u53d1',
    w_long: '\u957f\u76f4\u53d1',
    w_lowpony: '\u4f4e\u9a6c\u5c3e',
    w_bun: '\u4e38\u5b50\u5934',
    w_braid: '\u7f16\u53d1'
  },
  glasses: { none: '\u65e0', thin: '\u7ec6\u6846', thick: '\u7c97\u6846' },
  outfit: {
    suit_dark: '\u6df1\u8272\u897f\u88c5',
    suit_light: '\u6d45\u8272\u897f\u88c5',
    shirt: '\u886c\u886b',
    uniform: '\u5236\u670d\u9886',
    blouse: '\u5706\u9886\u886c\u886b',
    suit_female: '\u5973\u58eb\u897f\u88c5'
  }
};

// ================================================================ drawing
/** Face half-width at row y for this sex + face shape. 0 = no face on that row. */
const faceHalf = (y, female, shape) => {
  const tbl = female ? FACE_K_F : FACE_K_M;
  const k = tbl[y - HEAD_TOP];
  if (k === undefined) return 0;
  return (SHAPE[shape] || SHAPE.standard)(k, y);
};

/** Studio backdrop: dithered 7-band gradient + shadow across the bottom 10 %. */
function drawBackdrop(r) {
  const rows = INNER.y1 - INNER.y0 + 1;                    // 26
  const bands = [];
  for (let i = 0; i < BG_BANDS; i++) bands.push(mixHex(BG_TOP, BG_BOT, i / (BG_BANDS - 1)));
  const shadowRows = Math.max(1, Math.round(rows * 0.10)); // 3
  const shadowFrom = INNER.y1 - shadowRows + 1;            // 26
  for (let y = INNER.y0; y <= INNER.y1; y++) {
    if (y >= shadowFrom) {
      // studio floor shadow: the darker the closer to the bottom edge
      const depth = (y - shadowFrom + 1) / shadowRows;     // 1/3 .. 1
      const c = darkenHex(bands[BG_BANDS - 1], 0.05 + 0.06 * depth);
      r.span(y, INNER.x0, INNER.x1, c);
      continue;
    }
    const t = (y - INNER.y0) / (rows - 1);
    const f = t * (BG_BANDS - 1);
    const i = Math.floor(f), frac = f - i;
    for (let x = INNER.x0; x <= INNER.x1; x++) {
      const thr = BAYER4[(y & 3) * 4 + (x & 3)];
      r.px(x, y, frac > thr ? bands[Math.min(BG_BANDS - 1, i + 1)] : bands[i]);
    }
  }
}

/** Neck stub, 1 row, held in shadow so the chin stays the focal point. */
function drawNeck(r, skin) {
  r.span(NECK_Y, 13, 18, skin.shade);
  r.span(NECK_Y, 15, 16, skin.deep);    // deepest part right under the chin
}

/** Shoulders + outfit. Edges carry the form shadow (left) and rim light (right). */
function drawOutfit(r, entry, o) {
  for (const [y, x0, x1] of BODY_ROWS) r.span(y, x0, x1, o.base);
  for (const [y, x0, x1] of BODY_ROWS) { r.px(x0, y, o.shade); r.px(x1, y, o.light); }

  if (entry.outfit === 'suit_dark' || entry.outfit === 'suit_light') {
    // jacket + shirt V; the tie (men) or the open neckline (women) closes it
    r.span(24, 13, 18, o.shirt);
    r.span(25, 14, 17, o.shirt);
    r.span(26, 14, 17, o.shirt);
    r.span(27, 14, 17, o.shirt);
    for (const [x, y] of [[12, 24], [19, 24], [13, 25], [18, 25], [13, 26], [18, 26], [13, 27], [18, 27], [12, 28], [19, 28]]) {
      r.px(x, y, o.accent);                       // lapel edge
    }
    if (entry.sex === 'male') {
      r.span(25, 15, 16, o.tie);                  // knot
      r.span(26, 15, 16, o.tie);                  // blade
      r.span(27, 15, 16, o.tie);
      r.span(28, 15, 16, o.tie);
      r.px(15, 25, o.accent); r.px(16, 25, o.accent);
    } else {
      r.span(26, 15, 16, o.shirt);                // open neckline
      r.span(27, 15, 16, o.shirt);
      r.span(28, 15, 16, o.shirt);
      r.px(15, 26, o.accent); r.px(16, 26, o.accent);
    }
  } else if (entry.outfit === 'shirt') {
    // dress shirt: collar leaves + button placket
    r.span(24, 11, 14, o.light);
    r.span(24, 17, 20, o.light);
    r.span(24, 15, 16, o.shade);                  // collar gap under the chin
    r.px(14, 25, o.accent); r.px(17, 25, o.accent);
    r.span(25, 15, 16, o.accent);                 // placket
    r.span(26, 15, 16, o.accent);
    r.span(27, 15, 16, o.accent);
    r.span(28, 15, 16, o.accent);
    r.px(15, 26, o.light);                        // buttons
    r.px(15, 28, o.light);
  } else if (entry.outfit === 'blouse') {
    // round-neck blouse: soft rolled collar over a pale body, no placket line
    r.span(24, 13, 18, o.band);                   // rolled collar
    r.span(25, 14, 17, o.band);
    r.px(13, 25, o.shade); r.px(18, 25, o.shade);
    r.span(26, 15, 16, o.shirt);                  // neckline opening
    r.span(27, 15, 16, o.shirt);
    r.px(15, 26, o.light);                        // 1 px pearl button
    r.px(15, 28, o.shade);
  } else if (entry.outfit === 'suit_female') {
    // blazer with a bright shell: deep V, no tie, 1 px lapel piping
    r.span(24, 13, 18, o.shirt);
    r.span(25, 14, 17, o.shirt);
    r.span(26, 14, 17, o.shirt);
    r.span(27, 14, 17, o.shirt);
    r.span(28, 14, 17, o.shirt);
    for (const [x, y] of [[12, 24], [19, 24], [13, 25], [18, 25], [13, 26], [18, 26], [12, 27], [19, 27], [12, 28], [19, 28]]) {
      r.px(x, y, o.accent);
    }
    r.px(15, 25, o.band); r.px(16, 25, o.band);   // soft neckline shadow
  } else {
    // uniform: standing collar with a bright tab, shoulder boards that run down
    // the slope, and one gold button - reads as service dress next to a suit
    r.span(24, 12, 19, o.band);
    r.px(15, 24, o.shade); r.px(16, 24, o.shade); // collar split
    r.px(12, 24, o.accent); r.px(19, 24, o.accent); // collar tab, 1 px of gold
    r.span(25, 15, 16, o.band);
    r.span(26, 15, 16, o.shade);
    r.span(27, 15, 16, o.shade);
    r.span(28, 15, 16, o.shade);
    r.span(25, 8, 10, o.accent);                  // shoulder board, left
    r.span(25, 21, 23, o.accent);                 // shoulder board, right
    r.span(26, 6, 7, o.accent);                   // ... continuing down the slope
    r.span(26, 24, 25, o.accent);
    r.px(15, 26, o.accent);                       // button
  }
}

/** Face oval; 1 px form shadow on the left edge, 1 px rim light on the right. */
function drawHead(r, skin, female, shape) {
  for (let y = HEAD_TOP; y <= CHIN; y++) {
    const k = faceHalf(y, female, shape);
    if (k <= 0) continue;
    const x0 = 16 - k, x1 = 15 + k;
    r.span(y, x0, x1, skin.base);
    if (x1 - x0 >= 2) { r.px(x0, y, skin.shade); r.px(x1, y, skin.light); }
  }
}

function drawHair(r, hair, entry) {
  const style = HAIRSTYLES[entry.hairStyle];
  const female = entry.sex === 'female';
  const shape = entry.faceShape;
  const scalp = mixHex(hair.base, SKIN[entry.skin].base, 0.45); // buzz-cut stubble
  const role = { b: hair.base, s: hair.shade, l: hair.light, m: scalp };
  for (const [y, x0, x1, k] of style.rows) {
    r.span(y, x0, x1, role[k]);
    if (x1 - x0 >= 1) { r.px(x0, y, hair.shade); r.px(x1, y, hair.light); }
  }
  // Long styles: a side mass that runs from a fixed outer column inward to the
  // cheek edge, recomputed per row. It therefore grows as the jaw narrows, which
  // is what makes a bob/shoulder/long silhouette read instead of a colour change.
  if (style.sideMass) {
    const m = style.sideMass;
    const out = m.out + (shape === 'narrow' ? 1 : 0);
    for (let y = m.y0; y <= m.y1; y++) {
      const k = faceHalf(Math.min(y, CHIN), female, shape);
      if (!k) continue;
      const lx1 = 16 - k - 1, rx0 = 15 + k + 1;
      // round both ends of the mass so it reads as hair, not as a rectangle
      const dTop = y - m.y0, dBot = m.y1 - y;
      const inset = (dTop === 0 || dBot === 0) ? 2 : ((dTop === 1 || dBot === 1) ? 1 : 0);
      const lx0 = Math.min(out + inset, lx1 - 1);
      const rx1 = 31 - lx0;
      r.span(y, lx0, lx1, hair.base);
      r.span(y, rx0, rx1, hair.base);
      r.px(lx0, y, hair.shade); r.px(rx1, y, hair.light);
    }
  }
  for (const [x, y, k] of style.dots) r.px(x, y, role[k]);
  // soft shadow the fringe throws on the forehead
  if (style.fringeY) {
    const k = faceHalf(style.fringeY, female, shape);
    r.span(style.fringeY, 16 - k + 1, 15 + k - 1, SKIN[entry.skin].shade);
  }
}

/**
 * Ears: 2 px wide, always glued to the cheek at eye level. Their columns follow
 * the face shape, so a narrow face still has ears instead of a floating pair.
 * `earring` (women only, 3 presets) adds one gold pixel on each lobe.
 */
function drawEars(r, skin, female, shape, earring) {
  const k = faceHalf(14, female, shape);   // widest row at ear height
  const lx = 16 - k - 2, rx = 15 + k + 2;  // outer columns of the two ears
  for (const y of [13, 14]) {
    r.px(lx, y, skin.shade); r.px(lx + 1, y, skin.deep);
    r.px(rx, y, skin.shade); r.px(rx - 1, y, skin.deep);
  }
  r.px(lx + 1, 15, skin.shade); r.px(rx - 1, 15, skin.shade); // lobe
  if (earring) { r.px(lx + 1, 15, EARRING); r.px(rx - 1, 15, EARRING); }
}

function drawFace(r, skin, hair, entry) {
  const female = entry.sex === 'female';
  // brows: men 2 px tall and 4 px wide, women 1 px tall and 3 px wide.
  // Either way they sit one clear row above the glasses rim (y12).
  if (female) {
    r.span(BROW_Y, 12, 14, hair.shade);
    r.span(BROW_Y, 17, 19, hair.shade);
  } else {
    r.span(BROW_Y - 1, 12, 14, hair.shade);   // tapered upper brow ridge
    r.span(BROW_Y - 1, 17, 19, hair.shade);
    r.span(BROW_Y, 11, 14, hair.shade);
    r.span(BROW_Y, 17, 20, hair.shade);
  }
  // eyes: 2 px wide - men 2 px tall, women 1 px tall + a 1 px lash underneath
  if (female) {
    r.px(12, EYE_Y, SCLERA); r.px(13, EYE_Y, PUPIL);
    r.px(18, EYE_Y, PUPIL); r.px(19, EYE_Y, SCLERA);
    // 1 px tall eye: the row below stays bare skin, so a lens always has a
    // light row under the iris. The gender cue is the outer lash flick instead.
    r.px(12, 12, hair.shade); r.px(19, 12, hair.shade);
  } else {
    // 2 px tall, but only the top row is the iris - the row below is eye white.
    // Two dark rows plus a lens rim is exactly what read as sunglasses before.
    r.px(12, EYE_Y, SCLERA); r.px(13, EYE_Y, PUPIL);
    r.px(18, EYE_Y, PUPIL); r.px(19, EYE_Y, SCLERA);
    r.span(EYE_Y + 1, 12, 13, SCLERA);
    r.span(EYE_Y + 1, 18, 19, SCLERA);
  }
  // nose: bridge shade + nostril shadow
  r.span(15, 15, 16, skin.shade);
  r.span(16, 14, 17, skin.shade);
  r.span(16, 15, 16, skin.deep);
  // mouth: women get one extra warm row (fuller lower lip)
  const lipLine = mixHex(skin.lip, LIP_LINE_DARK, 0.45);
  r.span(MOUTH_Y, 14, 17, skin.lip);
  r.span(MOUTH_Y, 15, 16, lipLine);
  if (female) {
    r.span(MOUTH_Y + 1, 14, 17, mixHex(skin.lip, '#FFFFFF', 0.34));  // fuller lower lip
  }
  // middle-aged: nasolabial fold + a soft line under each eye
  if (entry.age === 'middle') {
    const k = faceHalf(17, female, entry.faceShape);
    r.px(16 - k + 2, 17, skin.shade);
    r.px(15 + k - 2, 17, skin.shade);
    if (!female) {
      r.span(14, 12, 13, skin.shade);
      r.span(14, 18, 19, skin.shade);
    }
  }
  // chin highlight keeps the jaw from reading flat
  r.span(21, 15, 16, skin.light);
}

/** Neat short beard: a light growth along the jaw + chin, always clear of the mouth. */
function drawBeard(r, hair, skin, shape) {
  const c = mixHex(hair.shade, skin.base, 0.58);
  // jaw outline tracks the face shape so the beard never spills onto the backdrop
  const inset = shape === 'narrow' ? 1 : 0;
  const wide = shape === 'square' ? 1 : 0;
  const rows = [
    [18, 14, 17],                                         // moustache, above the mouth
    [19, 11 - wide, 11 - wide], [19, 20 + wide, 20 + wide], // jaw corners
    [20, 12 - wide, 13 - wide], [20, 18 + wide, 19 + wide],
    [21, 12 - inset - wide, 13 - inset], [21, 18, 19 + inset + wide],
    [22, 13 - inset, 18 + inset]                          // chin
  ];
  for (const [y, x0, x1] of rows) r.span(y, x0, x1, c);
}

/**
 * Glasses. Both grades are a 1 px ring around the lens - "thick" is a DARKER
 * acetate with a bigger lens and a temple arm, never a heavier bar (a 2-3 px
 * frame on a 14 px face reads as sunglasses at this size).
 *   thin  - 1 px #6C747C wire, 4 px lens, interior = the two eye pixels, 2 px skin each side
 *   thick - 1 px #3E444B acetate, 5 px lens with 3 px clear interior and a 2 px
 *           skin gap between the two lenses, so the pair never merges into one bar
 * Frame rows y12..y15, interior y13..14: the iris row on top, eye white (men) or
 * a soft lash (women) underneath, so nothing inside a lens is a solid dark block.
 */
function drawGlasses(r, kind) {
  if (kind === 'none') return;
  const thick = kind === 'thick';
  const col = thick ? GLASS_THICK : GLASS_THIN;
  const top = 12, bot = 15;
  const boxes = [[11, 14], [17, 20]];   // same lens as the wire frame
  for (const [x0, x1] of boxes) {
    r.span(top, x0, x1, col);
    r.span(bot, x0, x1, col);
    for (let y = top; y <= bot; y++) {
      r.px(x0, y, col);
      r.px(x1, y, col);
    }
  }
  // thin gets a 2 px bridge; thick leaves a 2 px skin gap between the lenses
  // (plus 1 px temples) so the pair can never merge into one horizontal bar
  if (!thick) r.span(top, 15, 16, col);
  r.px(10, 13, col);                            // 1 px temple arm toward the ear
  r.px(21, 13, col);
}

/** Paints the 1 px photo border; everything outside stays fully transparent. */
function drawFrame(r) {
  r.rect(CARD.x0, CARD.y0, CARD.x1, CARD.y0, FRAME_INK);
  r.rect(CARD.x0, CARD.y1, CARD.x1, CARD.y1, FRAME_INK);
  r.rect(CARD.x0, CARD.y0, CARD.x0, CARD.y1, FRAME_INK);
  r.rect(CARD.x1, CARD.y0, CARD.x1, CARD.y1, FRAME_INK);
}

// ---------------------------------------------------------------- compose
function renderPortrait(entry) {
  const r = new Raster(W, H);
  const skin = SKIN[entry.skin];
  const hair = HAIR[entry.hairColor];
  const outfit = OUTFIT[entry.outfit];
  drawBackdrop(r);
  drawOutfit(r, entry, outfit);
  drawNeck(r, skin);
  drawHead(r, skin, entry.sex === 'female', entry.faceShape);
  drawHair(r, hair, entry);
  drawEars(r, skin, entry.sex === 'female', entry.faceShape, entry.earring);
  drawFace(r, skin, hair, entry);
  if (entry.beard === 'stubble') drawBeard(r, hair, skin, entry.faceShape);
  drawGlasses(r, entry.glasses);
  drawFrame(r);
  return r;
}

// ---------------------------------------------------------------- contact sheet
const SHEET_COLS = 6, SHEET_ROWS = 4;
const TILE = 128, TILE_SCALE = TILE / W;   // 4x nearest neighbour
const LABEL_H = 22, GAP = 10, PAD = 14, HEADER_H = 34;
const SHEET_W = PAD * 2 + SHEET_COLS * TILE + (SHEET_COLS - 1) * GAP;
const SHEET_H = PAD * 2 + HEADER_H + SHEET_ROWS * (TILE + LABEL_H) + (SHEET_ROWS - 1) * GAP;

const CODE = {
  sex: { male: 'M', female: 'F' },
  age: { youth: 'YOU', middle: 'MID' },
  hairStyle: {
    short: 'SHORT', slick: 'SLICK', curly: 'CURLY', ponytail: 'PONY', buzz: 'BUZZ', bald: 'BALD',
    w_bob: 'BOB', w_shoulder: 'SHLDR', w_long: 'LONG', w_lowpony: 'LPONY', w_bun: 'BUN', w_braid: 'BRAID'
  },
  glasses: { none: 'NOG', thin: 'THIN', thick: 'THICK' },
  beard: { none: 'NOB', stubble: 'BEARD' },
  outfit: {
    suit_dark: 'SUITDK', suit_light: 'SUITLT', shirt: 'SHIRT',
    uniform: 'UNIF', blouse: 'BLOUSE', suit_female: 'SUITF'
  }
};

function buildSheet(rasters) {
  const sheet = new Raster(SHEET_W, SHEET_H, '#F4F6F8');
  drawText(sheet, PAD, PAD - 2, 'VERIBORDER - ID PHOTO PORTRAIT PRESETS (24)', '#2A2F35', 2);
  drawText(sheet, PAD, PAD + 15, '32X32 / BINARY ALPHA / 30 COLOUR CAP / DETERMINISTIC', '#6B7480', 1);
  const y0 = PAD + HEADER_H;
  for (let i = 0; i < SPECS.length; i++) {
    const col = i % SHEET_COLS, row = Math.floor(i / SHEET_COLS);
    const x = PAD + col * (TILE + GAP);
    const y = y0 + row * (TILE + LABEL_H + GAP);
    // slot: 1 px frame so the transparent margin around the photo card reads
    sheet.rect(x - 1, y - 1, x + TILE, y + TILE, '#B7BEC6');
    sheet.rect(x, y, x + TILE - 1, y + TILE - 1, '#FFFFFF');
    sheet.blit(rasters[i], x, y, TILE_SCALE);
    const spec = SPECS[i];
    const idW = drawText(sheet, x, y + TILE + 3, spec.id.toUpperCase(), '#2A2F35', 2);
    drawText(sheet, idW + 6, y + TILE + 3, rasters[i].uniqueColors().length + 'C', '#9AA2AB', 2);
    const line = [CODE.sex[spec.sex], CODE.age[spec.age], CODE.hairStyle[spec.hairStyle], CODE.glasses[spec.glasses], CODE.beard[spec.beard], CODE.outfit[spec.outfit]].join(' ');
    drawText(sheet, x, y + TILE + 15, line, '#6B7480', 1);
  }
  return sheet;
}

// ---------------------------------------------------------------- main
function writePalette() {
  const rows = SPECS.map((s) => ({
    id: s.id,
    '\u6027\u522b': ZH.sex[s.sex],
    '\u5e74\u9f84\u6bb5': ZH.age[s.age],
    '\u53d1\u578b': ZH.hairStyle[s.hairStyle],
    '\u773c\u955c': ZH.glasses[s.glasses],
    '\u670d\u88c5': ZH.outfit[s.outfit]
  }));
  const file = path.join(TEX_DIR, 'palette.json');
  fs.writeFileSync(file, JSON.stringify(rows, null, 2) + '\n', 'utf8');
  return file;
}

/** Axis coverage report - printed on every run, asserted by the audit script. */
function coverage() {
  const out = {};
  for (const axis of Object.keys(AXES)) {
    const counts = {};
    for (const v of AXES[axis]) counts[v] = 0;
    for (const s of SPECS) counts[s[axis]] = (counts[s[axis]] || 0) + 1;
    out[axis] = counts;
  }
  return out;
}

function generate() {
  fs.mkdirSync(TEX_DIR, { recursive: true });
  fs.mkdirSync(path.dirname(SHEET), { recursive: true });
  const rasters = [];
  let maxColors = 0;
  for (const spec of SPECS) {
    const r = renderPortrait(spec);
    const n = r.uniqueColors().length;
    maxColors = Math.max(maxColors, n);
    if (n > MAX_COLOURS) throw new Error(`${spec.id}: ${n} colours exceeds the ${MAX_COLOURS}-colour cap`);
    writePng(path.join(TEX_DIR, spec.id + '.png'), W, H, r.d);
    rasters.push(r);
  }
  writePalette();
  writePng(SHEET, SHEET_W, SHEET_H, buildSheet(rasters).d);
  return { maxColors, tiles: rasters.length };
}

if (require.main === module) {
  const started = Date.now();
  const res = generate();
  const cov = coverage();
  const line = (o) => Object.entries(o).map(([k, v]) => `${k}:${v}`).join(' ');
  console.log(`VeriBorder portraits 26.4.1 - ${res.tiles} presets, max ${res.maxColors} colours/preset`);
  console.log('  out   ' + path.relative(ROOT, TEX_DIR) + '/p00..p23.png');
  console.log('  out   ' + path.relative(ROOT, TEX_DIR) + '/palette.json');
  console.log('  out   ' + path.relative(ROOT, SHEET));
  for (const axis of Object.keys(cov)) console.log(`  cover ${axis.padEnd(9)} ${line(cov[axis])}`);
  console.log(`  done in ${Date.now() - started} ms`);
}

module.exports = {
  generate, renderPortrait, coverage, buildSheet,
  SPECS, AXES, ZH, SKIN, HAIR, OUTFIT, HAIRSTYLES,
  TEX_DIR, SHEET, W, H, CARD, INNER, FRAME_INK, BG_TOP, BG_BOT,
  HEAD_TOP, CHIN, BODY_TOP, NECK_Y, EYE_Y, BROW_Y, MOUTH_Y,
  FACE_K_M, FACE_K_F, faceHalf, SHAPE, BODY_ROWS, Raster, writePng, mixHex, darkenHex, luma, hexRgb, drawText, textW
};

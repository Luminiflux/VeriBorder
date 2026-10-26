'use strict';
/*
 * VeriBorder — device screen design system (26.4.1)
 * -------------------------------------------------
 * One shared kit for every non-gate device screen so 19 terminals read as
 * members of the same product family while each one keeps its own identity.
 *
 * Design system contract (do not fork per device):
 *   - 3x5 glyph grid, 1 px stroke, 4 px advance (the ONLY font in the kit)
 *   - 1 px rounded border, corners cut, inset 1 px from the canvas edge
 *   - shared state palette (ok / warn / err / info) — identical everywhere
 *   - per-device accent: exactly one hue per device, used for identity + CTA
 *   - background stays in the 0x04..0x14 luminance band so accents "glow"
 *   - alpha is binary: either 255 (screen on) or 0 (off) — no feathering
 *
 * Output is a plain RGBA8 PNG, filter 0, same encoder shape as
 * tools/preview_models.js so the model validator can read it back.
 */

const fs = require('fs');
const path = require('path');
const zlib = require('zlib');

// ---------------------------------------------------------------- PNG codec
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
    raw[y * (stride + 1)] = 0;
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

// ---------------------------------------------------------------- palette
// Shared across all devices. Never redefine per device.
const C = {
  off:     [0x00, 0x00, 0x00], // transparent (alpha 0)
  void:    [0x03, 0x07, 0x0C], // outermost bezel line
  bg:      [0x06, 0x11, 0x1C], // screen base — very dark, never mid grey
  bg2:     [0x0A, 0x1D, 0x2C], // panel
  bg3:     [0x10, 0x2A, 0x3D], // raised panel / empty track
  grid:    [0x0A, 0x1A, 0x28], // scanline — only just above bg
  line:    [0x1C, 0x44, 0x60], // separator
  dim:     [0x6C, 0x93, 0xAB], // secondary text
  fg:      [0xE8, 0xF6, 0xFF], // primary text — near white
  icon:    [0xA8, 0xCC, 0xE0], // neutral iconography
  paper:   [0xE9, 0xF3, 0xF8], // document / paper fill
  ink:     [0x11, 0x1E, 0x28], // dark ink printed on paper
  // shared state colours — identical on every device
  ok:      [0x3B, 0xF5, 0x9B],
  warn:    [0xFF, 0xC2, 0x3D],
  err:     [0xFF, 0x4D, 0x6D],
  info:    [0x4D, 0xE1, 0xFF]
};

const hex = (s) => [parseInt(s.slice(1, 3), 16), parseInt(s.slice(3, 5), 16), parseInt(s.slice(5, 7), 16)];
/** Mix two palette entries; used for "glow" 1 px halos around accents. */
const mix = (a, b, t) => [
  Math.round(a[0] + (b[0] - a[0]) * t),
  Math.round(a[1] + (b[1] - a[1]) * t),
  Math.round(a[2] + (b[2] - a[2]) * t)
];
/** Darkened accent, for frames / inactive chips. */
const dark = (c, t) => mix(c, C.void, t);

// ---------------------------------------------------------------- 3x5 font
// The single glyph grid of the design system. 3 wide, 5 tall, 1 px stroke.
const FONT = {
  A: ['.##', '#.#', '###', '#.#', '#.#'],
  B: ['##.', '#.#', '##.', '#.#', '##.'],
  C: ['.##', '#..', '#..', '#..', '.##'],
  D: ['##.', '#.#', '#.#', '#.#', '##.'],
  E: ['###', '#..', '##.', '#..', '###'],
  F: ['###', '#..', '##.', '#..', '#..'],
  G: ['.##', '#..', '#.#', '#.#', '.##'],
  H: ['#.#', '#.#', '###', '#.#', '#.#'],
  I: ['###', '.#.', '.#.', '.#.', '###'],
  J: ['..#', '..#', '..#', '#.#', '.#.'],
  K: ['#.#', '#.#', '##.', '#.#', '#.#'],
  L: ['#..', '#..', '#..', '#..', '###'],
  M: ['#.#', '###', '###', '#.#', '#.#'],
  // N/M share the 3 px width; the full row differs in position so N reads as
  // "diagonal" against H (`#.#`,`#.#`,`###`,`#.#`,`#.#`) without becoming "И".
  N: ['#.#', '###', '#.#', '#.#', '#.#'],
  O: ['.##', '#.#', '#.#', '#.#', '##.'],
  P: ['##.', '#.#', '##.', '#..', '#..'],
  Q: ['.##', '#.#', '#.#', '##.', '.##'],
  R: ['##.', '#.#', '##.', '#.#', '#.#'],
  S: ['.##', '#..', '.#.', '..#', '##.'],
  T: ['###', '.#.', '.#.', '.#.', '.#.'],
  U: ['#.#', '#.#', '#.#', '#.#', '###'],
  V: ['#.#', '#.#', '#.#', '#.#', '.#.'],
  W: ['#.#', '#.#', '###', '###', '#.#'],
  X: ['#.#', '#.#', '.#.', '#.#', '#.#'],
  Y: ['#.#', '#.#', '.#.', '.#.', '.#.'],
  Z: ['###', '..#', '.#.', '#..', '###'],
  0: ['###', '#.#', '#.#', '#.#', '###'],
  1: ['.#.', '##.', '.#.', '.#.', '###'],
  2: ['##.', '..#', '.#.', '#..', '###'],
  3: ['##.', '..#', '.#.', '..#', '##.'],
  4: ['#.#', '#.#', '###', '..#', '..#'],
  5: ['###', '#..', '##.', '..#', '##.'],
  6: ['.##', '#..', '##.', '#.#', '.#.'],
  7: ['###', '..#', '.#.', '.#.', '.#.'],
  8: ['.#.', '#.#', '.#.', '#.#', '.#.'],
  9: ['.#.', '#.#', '.##', '..#', '##.'],
  ' ': ['...', '...', '...', '...', '...'],
  '.': ['...', '...', '...', '...', '.#.'],
  ':': ['...', '.#.', '...', '.#.', '...'],
  '-': ['...', '...', '###', '...', '...'],
  '_': ['...', '...', '...', '...', '###'],
  '/': ['..#', '..#', '.#.', '#..', '#..'],
  '\\': ['#..', '#..', '.#.', '..#', '..#'],
  '+': ['...', '.#.', '###', '.#.', '...'],
  '=': ['...', '###', '...', '###', '...'],
  '%': ['#.#', '..#', '.#.', '#..', '#.#'],
  '#': ['#.#', '###', '#.#', '###', '#.#'],
  '!': ['.#.', '.#.', '.#.', '...', '.#.'],
  '?': ['##.', '..#', '.#.', '...', '.#.'],
  '<': ['..#', '.#.', '#..', '.#.', '..#'],
  '>': ['#..', '.#.', '..#', '.#.', '#..'],
  '(': ['..#', '.#.', '.#.', '.#.', '..#'],
  ')': ['#..', '.#.', '.#.', '.#.', '#..'],
  '[': ['##.', '#..', '#..', '#..', '##.'],
  ']': ['.##', '..#', '..#', '..#', '.##'],
  '*': ['...', '#.#', '.#.', '#.#', '...'],
  "'": ['.#.', '.#.', '...', '...', '...'],
  ',': ['...', '...', '...', '.#.', '#..'],
  '|': ['.#.', '.#.', '.#.', '.#.', '.#.']
};
const GLYPH_W = 3, GLYPH_H = 5, ADVANCE = 4;

/** Pixel width a string occupies on the 3x5 grid. */
function textW(s) { return s.length ? s.length * ADVANCE - 1 : 0; }

// ---------------------------------------------------------------- canvas
class Canvas {
  constructor(w, h, bg) {
    this.w = w; this.h = h;
    this.d = new Uint8Array(w * h * 4); // all zero => transparent
    if (bg) this.fill(0, 0, w, h, bg);
  }
  put(x, y, c) {
    x |= 0; y |= 0;
    if (x < 0 || y < 0 || x >= this.w || y >= this.h) return;
    const i = (y * this.w + x) * 4;
    this.d[i] = c[0]; this.d[i + 1] = c[1]; this.d[i + 2] = c[2]; this.d[i + 3] = 255;
  }
  clearPx(x, y) {
    x |= 0; y |= 0;
    if (x < 0 || y < 0 || x >= this.w || y >= this.h) return;
    const i = (y * this.w + x) * 4;
    this.d[i] = 0; this.d[i + 1] = 0; this.d[i + 2] = 0; this.d[i + 3] = 0;
  }
  fill(x, y, w, h, c) {
    for (let j = 0; j < h; j++) for (let i = 0; i < w; i++) this.put(x + i, y + j, c);
  }
  /** Horizontal rule. hl(x,y,w,color) or hl(x,y,w,h,color). */
  hl(x, y, w, h, c) {
    if (c === undefined) { c = h; h = 1; }
    this.fill(x, y, w, h, c);
  }
  /** Vertical rule. vl(x,y,h,color) or vl(x,y,h,w,color). */
  vl(x, y, h, w, c) {
    if (c === undefined) { c = w; w = 1; }
    this.fill(x, y, w, h, c);
  }
  /** 1 px rectangle outline. */
  box(x, y, w, h, c) {
    this.hl(x, y, w, c); this.hl(x, y + h - 1, w, c);
    this.vl(x, y, h, c); this.vl(x + w - 1, y, h, c);
  }
  /** 1 px rounded outline — the design system's border rule (1 px corner cut). */
  round(x, y, w, h, c) {
    this.hl(x + 1, y, w - 2, c); this.hl(x + 1, y + h - 1, w - 2, c);
    this.vl(x, y + 1, h - 2, c); this.vl(x + w - 1, y + 1, h - 2, c);
  }
  /** Filled rounded panel. */
  panel(x, y, w, h, c) {
    this.fill(x + 1, y, w - 2, h, c);
    this.fill(x, y + 1, w, h - 2, c);
  }
  text(x, y, s, c) {
    s = String(s).toUpperCase();
    let cx = x;
    for (const ch of s) {
      const g = FONT[ch] || FONT['?'];
      for (let r = 0; r < GLYPH_H; r++) {
        const row = g[r];
        for (let k = 0; k < GLYPH_W; k++) if (row[k] === '#') this.put(cx + k, y + r, c);
      }
      cx += ADVANCE;
    }
    return cx;
  }
  textRight(xr, y, s, c) { return this.text(xr - textW(String(s)), y, s, c); }
  textCenter(xc, y, s, c) { return this.text(Math.round(xc - textW(String(s)) / 2), y, s, c); }

  /** Thin 1 px circle outline (midpoint). Used for stamps, emblems, radios. */
  ring(cx, cy, r, c) {
    let x = r, y = 0, err = 1 - r;
    while (x >= y) {
      for (const [a, b] of [[x, y], [y, x], [-y, x], [-x, y], [-x, -y], [-y, -x], [y, -x], [x, -y]]) {
        this.put(cx + a, cy + b, c);
      }
      y++;
      if (err < 0) err += 2 * y + 1;
      else { x--; err += 2 * (y - x) + 1; }
    }
  }
  disc(cx, cy, r, c) {
    for (let j = -r; j <= r; j++) for (let i = -r; i <= r; i++) {
      if (i * i + j * j <= r * r + r * 0.5) this.put(cx + i, cy + j, c);
    }
  }
  /** Chunky tick mark (4x5) — shared "valid" glyph. */
  check(x, y, c) {
    const g = ['...#', '..#.', '#.#.', '.#..', '#...'];
    for (let r = 0; r < 5; r++) for (let k = 0; k < 4; k++) if (g[r][k] === '#') this.put(x + k, y + r, c);
  }
  cross(x, y, c) {
    const g = ['#..#', '.##.', '.##.', '#..#', '....'];
    for (let r = 0; r < 4; r++) for (let k = 0; k < 4; k++) if (g[r][k] === '#') this.put(x + k, y + r, c);
  }
  /** Horizontal progress / fill bar with a 1 px track outline. */
  bar(x, y, w, h, pct, fgC, bgC) {
    this.panel(x, y, w, h, bgC);
    const inner = Math.round((w - 2) * Math.max(0, Math.min(1, pct)));
    if (inner > 0) this.fill(x + 1, y + 1, inner, h - 2, fgC);
  }
  /** Vertical progress bar (for the tall strip screens). */
  vbar(x, y, w, h, pct, fgC, bgC) {
    this.panel(x, y, w, h, bgC);
    const inner = Math.round((h - 2) * Math.max(0, Math.min(1, pct)));
    if (inner > 0) this.fill(x + 1, y + h - 1 - inner, w - 2, inner, fgC);
  }
  /** Deterministic pseudo-barcode. */
  barcode(x, y, w, h, c, seed) {
    let s = seed >>> 0 || 0x9e3779b9;
    let cx = x;
    while (cx < x + w) {
      s = (s * 1664525 + 1013904223) >>> 0;
      const bar = 1 + (s & 1);
      const ww = Math.min(bar, x + w - cx);
      this.fill(cx, y, ww, h, c);
      cx += bar;
      s = (s * 1664525 + 1013904223) >>> 0;
      const gap = 1 + ((s >>> 3) & 1);
      cx += gap;
    }
  }
  /** Five-point star outline (national emblem placeholder). */
  star(cx, cy, r, c) {
    const pts = [];
    for (let i = 0; i < 10; i++) {
      const a = -Math.PI / 2 + i * Math.PI / 5;
      const rr = i % 2 === 0 ? r : r * 0.45;
      pts.push([cx + Math.cos(a) * rr, cy + Math.sin(a) * rr]);
    }
    for (let i = 0; i < 10; i++) this.lineTo(pts[i], pts[(i + 1) % 10], c);
  }
  lineTo(a, b, c) {
    let [x0, y0] = a, [x1, y1] = b;
    x0 = Math.round(x0); y0 = Math.round(y0); x1 = Math.round(x1); y1 = Math.round(y1);
    const dx = Math.abs(x1 - x0), dy = Math.abs(y1 - y0);
    const sx = x0 < x1 ? 1 : -1, sy = y0 < y1 ? 1 : -1;
    let e = dx - dy;
    for (;;) {
      this.put(x0, y0, c);
      if (x0 === x1 && y0 === y1) break;
      const e2 = 2 * e;
      if (e2 > -dy) { e -= dy; x0 += sx; }
      if (e2 < dx) { e += dx; y0 += sy; }
    }
  }
  /** Hazard / caution band (blacklist, loss). */
  hazard(x, y, w, h, a, b) {
    this.fill(x, y, w, h, a);
    for (let i = 0; i < w + h; i += 4) {
      for (let k = 0; k < 2; k++) {
        for (let j = 0; j < h; j++) {
          const px = x + i - j;
          if (px >= x && px < x + w) this.put(px + k, y + j, b);
        }
      }
    }
  }
  /** Warning triangle with exclamation. */
  warnTri(x, y, w, h, c, mark) {
    const cx = x + Math.floor(w / 2);
    for (let r = 0; r < h; r++) {
      const half = Math.round((r / (h - 1)) * (w / 2));
      if (r < h - 1) {
        this.put(cx - half, y + r, c);
        this.put(cx + half, y + r, c);
      }
    }
    this.hl(cx - Math.floor(w / 2), y + h - 1, w, c);
    if (mark) { this.vl(cx, y + Math.round(h * 0.38), Math.max(1, Math.round(h * 0.26)), mark); this.put(cx, y + h - 3, mark); }
  }
  arrowDown(x, y, len, c) {
    this.vl(x, y, len, c);
    for (let i = 0; i < 3; i++) { this.put(x - 1 - i, y + len - 3 + i, c); this.put(x + 1 + i, y + len - 3 + i, c); }
  }
  arrowUp(x, y, len, c) {
    this.vl(x, y, len, c);
    for (let i = 0; i < 3; i++) { this.put(x - 1 - i, y + 2 - i, c); this.put(x + 1 + i, y + 2 - i, c); }
  }
  /** Scaled 3x5 text — used for the one "hero" number on ID/boarding passes. */
  textBig(x, y, s, c, sc) {
    s = String(s).toUpperCase();
    let cx = x;
    for (const ch of s) {
      const g = FONT[ch] || FONT['?'];
      for (let r = 0; r < GLYPH_H; r++) {
        for (let k = 0; k < GLYPH_W; k++) {
          if (g[r][k] === '#') this.fill(cx + k * sc, y + r * sc, sc, sc, c);
        }
      }
      cx += ADVANCE * sc;
    }
  }
  /** Thick line (brush of t x t blocks) — stamp rings, big ticks. */
  thickLine(ax, ay, bx, by, c, t) {
    const steps = Math.max(Math.abs(bx - ax), Math.abs(by - ay)) * 2;
    const o = Math.floor(t / 2);
    for (let i = 0; i <= steps; i++) {
      const p = steps ? i / steps : 0;
      const x = Math.round(ax + (bx - ax) * p);
      const y = Math.round(ay + (by - ay) * p);
      this.fill(x - o, y - o, t, t, c);
    }
  }
  /** Big "approved" tick for ok-badges. */
  bigCheck(cx, cy, size, c) {
    const t = Math.max(1, Math.round(size / 5));
    const w = size, h = size * 0.9;
    this.thickLine(cx - w * 0.34, cy - h * 0.02, cx - w * 0.08, cy + h * 0.30, c, t);
    this.thickLine(cx - w * 0.08, cy + h * 0.30, cx + w * 0.36, cy - h * 0.32, c, t);
  }
  /** Radio / step dot. */
  radio(cx, cy, on, accent, r) {
    const rr = r || 2;
    if (on) { this.disc(cx, cy, rr, accent); this.disc(cx, cy, Math.max(1, rr - 2), C.void); }
    else this.ring(cx, cy, rr, C.dim);
  }
  /** Row of count pips (endorsement "times remaining"). */
  pips(x, y, filled, total, w, h, gap, fgC) {
    for (let i = 0; i < total; i++) {
      const px = x + i * (w + gap);
      this.panel(px, y, w, h, i < filled ? fgC : C.bg3);
      if (i >= filled) this.round(px, y, w, h, C.line);
    }
  }
  /** Rotate 180 (used to mirror the exit stamp family). */
  rot180() {
    const n = new Canvas(this.w, this.h);
    for (let y = 0; y < this.h; y++) for (let x = 0; x < this.w; x++) {
      const i = (y * this.w + x) * 4;
      if (this.d[i + 3]) n.put(this.w - 1 - x, this.h - 1 - y, [this.d[i], this.d[i + 1], this.d[i + 2]]);
    }
    return n;
  }
  export(file) { writePng(file, this.w, this.h, this.d); }
}

// ---------------------------------------------------------------- frame
/**
 * The design system's screen chrome: dark bezel line, base fill, rounded
 * accent border, and a faint scanline texture so the panel reads as active
 * glass rather than flat paint.
 */
function baseScreen(w, h, accent, opts) {
  const o = opts || {};
  const cv = new Canvas(w, h, C.void);
  cv.fill(1, 1, w - 2, h - 2, C.bg);
  // corner-cut accent border (1 px inset) — the family signature
  cv.round(0, 0, w, h, dark(accent, 0.55));
  cv.round(1, 1, w - 2, h - 2, dark(accent, 0.8));
  // faint horizontal scanlines: 1 px every 4 px, barely above bg
  if (!o.noScan) for (let y = 3; y < h - 1; y += 4) cv.hl(2, y, w - 4, C.grid);
  return cv;
}

/** Header strip: accent title on the left, live status dot on the right. */
function header(cv, accent, title, rightDot) {
  cv.text(3, 2, title, accent);
  // accent underline that shows the panel is "on"
  cv.hl(3, 8, Math.min(textW(title) + 2, cv.w - 8), dark(accent, 0.45));
  // only when the title leaves room — narrow canvases drop the dot
  if (rightDot && textW(title) + 10 < cv.w) {
    cv.put(cv.w - 4, 4, rightDot);
    cv.put(cv.w - 4, 3, mix(rightDot, C.fg, 0.35));
  }
}

module.exports = {
  C, hex, mix, dark, Canvas, baseScreen, header,
  textW, FONT, GLYPH_W, GLYPH_H, ADVANCE, writePng
};

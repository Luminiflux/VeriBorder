'use strict';
/*
 * VeriBorder 26.4.1 - portrait asset audit
 * ----------------------------------------
 * Acceptance gate for the 24 ID-photo presets produced by
 * tools/generate_portraits.js. Everything is measured on the PNG bytes on
 * disk (not on the in-memory model), then re-rendered once to prove the
 * checked-in files still match the generator source.
 *
 *   node tools/audit_portrait_assets.js        -> prints PASS / FAIL, exit code
 *
 * Checks
 *   - p00..p23 exist, 32x32, RGBA8, alpha binary (0 or 255 only)
 *   - non-empty (opaque coverage), <= 48 distinct colours
 *   - 1 px photo border #3A3F45 + >= 2 px fully transparent margin outside it
 *   - backdrop is the #D8DCE0 -> #EEF1F3 vertical ramp, monotonically lighter
 *     downwards, with a darker studio shadow over the bottom ~10 %
 *   - head occupies 54..66 % of the canvas height, centred on x, both ears
 *     present and painted in skin tones
 *   - all 24 presets pairwise different (SHA-1 over the RGBA buffer)
 *   - palette.json: 24 rows, exact key set, legal Chinese values, every axis
 *     value used at least once, consistent with the rendered files
 *   - determinism: on-disk pixels == fresh in-memory render
 */

const fs = require('fs');
const path = require('path');
const zlib = require('zlib');
const crypto = require('crypto');

const gen = require('./generate_portraits');

const ROOT = path.resolve(__dirname, '..');
const TEX_DIR = gen.TEX_DIR;
const PALETTE = path.join(TEX_DIR, 'palette.json');
const SHEET = gen.SHEET;

const W = gen.W, H = gen.H;
const MAX_COLORS = 30;        // arbitrated cap (the original brief allowed up to 48)
const FRAME_INK = gen.FRAME_INK;

// ---------------------------------------------------------------- PNG decode
function readPng(file) {
  const buf = fs.readFileSync(file);
  if (buf.length < 8 || buf.readUInt32BE(0) !== 0x89504e47) throw new Error(`${file}: not a PNG`);
  let p = 8, w = 0, h = 0, depth = 0, color = 0, interlace = 0;
  const idat = [];
  while (p + 8 <= buf.length) {
    const len = buf.readUInt32BE(p);
    const type = buf.toString('ascii', p + 4, p + 8);
    const data = buf.slice(p + 8, p + 8 + len);
    if (type === 'IHDR') {
      w = data.readUInt32BE(0); h = data.readUInt32BE(4);
      depth = data[8]; color = data[9]; interlace = data[12];
    } else if (type === 'IDAT') idat.push(data);
    else if (type === 'IEND') break;
    p += 12 + len;
  }
  if (depth !== 8 || color !== 6) throw new Error(`${file}: expected 8-bit RGBA`);
  if (interlace) throw new Error(`${file}: interlaced PNG unsupported`);
  const raw = zlib.inflateSync(Buffer.concat(idat));
  const stride = w * 4;
  const out = Buffer.alloc(stride * h);
  for (let y = 0; y < h; y++) {
    const f = raw[y * (stride + 1)];
    const line = raw.slice(y * (stride + 1) + 1, y * (stride + 1) + 1 + stride);
    for (let x = 0; x < stride; x++) {
      const a = x >= 4 ? out[y * stride + x - 4] : 0;
      const b = y > 0 ? out[(y - 1) * stride + x] : 0;
      const c = (x >= 4 && y > 0) ? out[(y - 1) * stride + x - 4] : 0;
      let v = line[x];
      if (f === 1) v += a;
      else if (f === 2) v += b;
      else if (f === 3) v += (a + b) >> 1;
      else if (f === 4) {
        const pa = Math.abs(b - c), pb = Math.abs(a - c), pc = Math.abs(a + b - 2 * c);
        v += (pa <= pb && pa <= pc) ? a : (pb <= pc ? b : c);
      }
      out[y * stride + x] = v & 0xff;
    }
  }
  return { w, h, d: out };
}

const px = (img, x, y) => {
  const i = (y * img.w + x) * 4;
  return [img.d[i], img.d[i + 1], img.d[i + 2], img.d[i + 3]];
};
const hex = (c) => '#' + c.slice(0, 3).map((v) => v.toString(16).padStart(2, '0')).join('');
const dist = (a, b) => Math.abs(a[0] - b[0]) + Math.abs(a[1] - b[1]) + Math.abs(a[2] - b[2]);
const luma = (c) => 0.2126 * c[0] + 0.7152 * c[1] + 0.0722 * c[2];
/** Backdrop families are neutral greys; everything drawn on top is not. */
const isNeutralGrey = (c) => Math.abs(c[0] - c[1]) <= 6 && Math.abs(c[1] - c[2]) <= 6 && luma(c) >= 190;

// ---------------------------------------------------------------- results
const results = [];
function check(name, ok, detail) {
  results.push({ name, ok: !!ok, detail });
  return ok;
}

// ---------------------------------------------------------------- run
function main() {
  const specs = gen.SPECS;
  const byId = new Map(specs.map((s) => [s.id, s]));

  // ---- 1. files, format, alpha, colours --------------------------------
  const ids = [];
  const hashes = new Map();
  let maxColors = 0, minCoverage = 1, maxCoverage = 0;
  const images = new Map();
  const problems = [];

  for (let i = 0; i < 24; i++) {
    const id = 'p' + String(i).padStart(2, '0');
    ids.push(id);
    const file = path.join(TEX_DIR, id + '.png');
    if (!fs.existsSync(file)) { problems.push(`${id}.png missing`); continue; }
    let img;
    try { img = readPng(file); } catch (e) { problems.push(String(e.message)); continue; }
    images.set(id, img);
    if (img.w !== W || img.h !== H) {
      // provenance matters here: a foreign generator overwriting the path shows
      // up as a wrong IHDR size, so record the mtime for the incident report
      const st = fs.statSync(file);
      problems.push(`${id}: IHDR ${img.w}x${img.h} != 32x32 (mtime ${st.mtime.toISOString()}, ${st.size} B)`);
    }

    const colors = new Set();
    let opaque = 0, badAlpha = 0;
    for (let p = 0; p < img.w * img.h; p++) {
      const a = img.d[p * 4 + 3];
      if (a !== 0 && a !== 255) badAlpha++;
      if (a === 255) {
        opaque++;
        colors.add((img.d[p * 4] << 16) | (img.d[p * 4 + 1] << 8) | img.d[p * 4 + 2]);
      }
    }
    if (badAlpha) problems.push(`${id}: ${badAlpha} non-binary alpha pixels`);
    if (colors.size > MAX_COLORS) problems.push(`${id}: ${colors.size} colours > ${MAX_COLORS}`);
    const cov = opaque / (img.w * img.h);
    if (cov < 0.5) problems.push(`${id}: only ${(cov * 100).toFixed(1)}% opaque (empty texture?)`);
    minCoverage = Math.min(minCoverage, cov);
    maxCoverage = Math.max(maxCoverage, cov);
    maxColors = Math.max(maxColors, colors.size);
    hashes.set(id, crypto.createHash('sha1').update(img.d).digest('hex'));
  }
  check('files 24x 32x32 RGBA8, alpha binary, non-empty, <=30 colours',
    problems.length === 0 && images.size === 24,
    problems.length ? problems.slice(0, 6).join(' | ') : `${images.size}/24 opaque ${(minCoverage * 100).toFixed(1)}..${(maxCoverage * 100).toFixed(1)}%, max ${maxColors} colours`);

  // ---- 2. frame + transparent margin ----------------------------------
  const frameProblems = [];
  for (const [id, img] of images) {
    // >= 2 px fully transparent margin on every side
    for (let y = 0; y < H; y++) {
      for (let x = 0; x < W; x++) {
        if (x >= 2 && x <= 29 && y >= 2 && y <= 29) continue;
        if (px(img, x, y)[3] !== 0) { frameProblems.push(`${id}: opaque pixel at margin (${x},${y})`); y = H; break; }
      }
    }
    // 1 px border #3A3F45 around the card (2,2)-(29,29)
    const ink = [0x3a, 0x3f, 0x45];
    for (let x = 2; x <= 29; x++) {
      for (const y of [2, 29]) {
        const c = px(img, x, y);
        if (c[3] !== 255 || dist(c, ink) > 0) frameProblems.push(`${id}: border ${hex(c)} at (${x},${y})`);
      }
    }
    for (let y = 2; y <= 29; y++) {
      for (const x of [2, 29]) {
        const c = px(img, x, y);
        if (c[3] !== 255 || dist(c, ink) > 0) frameProblems.push(`${id}: border ${hex(c)} at (${x},${y})`);
      }
    }
  }
  check('1 px border #3A3F45 + >=2 px transparent margin', frameProblems.length === 0, frameProblems.slice(0, 4).join(' | ') || '24/24');

  // ---- 3. backdrop ramp + studio shadow -------------------------------
  const bgProblems = [];
  const top = gen.BG_TOP, bot = gen.BG_BOT;
  const topRgb = [parseInt(top.slice(1, 3), 16), parseInt(top.slice(3, 5), 16), parseInt(top.slice(5, 7), 16)];
  const botRgb = [parseInt(bot.slice(1, 3), 16), parseInt(bot.slice(3, 5), 16), parseInt(bot.slice(5, 7), 16)];
  for (const [id, img] of images) {
    // x = 3 is the outermost interior column: above the shoulders it is always
    // backdrop, so the ramp and the shadow can be measured there without mixing
    // in hair or clothing.
    const c3 = px(img, 3, 3), c24 = px(img, 3, 24);
    if (dist(c3, topRgb) > 3) bgProblems.push(`${id}: top backdrop ${hex(c3)} != ${top}`);
    if (!isNeutralGrey(c24)) bgProblems.push(`${id}: (3,24) not backdrop`);
    if (luma(c24) - luma(c3) < 12) bgProblems.push(`${id}: gradient only ${(luma(c24) - luma(c3)).toFixed(1)} luma`);
    // monotonic-ish upwards: the 4x4 dither may repeat a band, never skip one
    for (let y = 5; y <= 24; y++) {
      const d = luma(px(img, 3, y)) - luma(px(img, 3, y - 1));
      if (d < -5) { bgProblems.push(`${id}: backdrop dips ${d.toFixed(1)} at y=${y}`); break; }
    }
    // the last un-shadowed row must sit at the light end of the ramp
    const c25 = px(img, 3, 25);
    if (dist(c25, botRgb) > 16) bgProblems.push(`${id}: (3,25) ${hex(c25)} not near ${bot}`);
    let rampMin = 255, rampMax = 0;
    for (let y = 3; y <= 25; y++) {
      const l = luma(px(img, 3, y));
      rampMin = Math.min(rampMin, l);
      rampMax = Math.max(rampMax, l);
    }
    if (rampMax - rampMin > 26) bgProblems.push(`${id}: ramp span ${(rampMax - rampMin).toFixed(1)} too wide for 7 bands`);
    // studio shadow: the lowest backdrop patch still visible beside the shoulder
    // (x=3, y=26) has to be darker than the row above it
    const shadowPx = px(img, 3, 26);
    if (!isNeutralGrey(shadowPx)) bgProblems.push(`${id}: (3,26) not backdrop`);
    if (!(luma(shadowPx) < luma(c25) - 1)) bgProblems.push(`${id}: no studio shadow at y=26`);
  }
  check('backdrop #D8DCE0 -> #EEF1F3 ramp + bottom-10% shadow', bgProblems.length === 0, bgProblems.slice(0, 4).join(' | ') || '24/24');

  // ---- 4. head size, centring, ears -----------------------------------
  const headProblems = [];
  let ratioMin = 1, ratioMax = 0;
  for (const [id, img] of images) {
    const spec = byId.get(id);
    const skin = gen.SKIN[spec.skin];
    const subjectRow = (y) => {
      let n = 0, first = -1, last = -1;
      for (let x = 0; x < W; x++) {
        const c = px(img, x, y);
        if (c[3] !== 255) continue;
        if (dist(c, [0x3a, 0x3f, 0x45]) === 0) continue;
        if (isNeutralGrey(c)) continue;
        n++; if (first < 0) first = x; last = x;
      }
      return { n, first, last };
    };
    const headRows = [];
    for (let y = 4; y <= 22; y++) {
      const r = subjectRow(y);
      if (r.n > 0) headRows.push(y);
      if (r.n === 0) headProblems.push(`${id}: empty head row y=${y}`);
      if (r.first < 3 || r.last > 28) headProblems.push(`${id}: head row ${y} escapes the card`);
    }
    const headTop = headRows[0], headBot = headRows[headRows.length - 1];
    const ratio = (headBot - headTop + 1) / H;
    ratioMin = Math.min(ratioMin, ratio);
    ratioMax = Math.max(ratioMax, ratio);
    if (ratio < 0.54 || ratio > 0.66) headProblems.push(`${id}: head ${(ratio * 100).toFixed(1)}% of canvas`);
    // centred: widest head row must be symmetric about x = 15.5
    let wide = 0, wideY = 0;
    for (let y = 4; y <= 22; y++) { const r = subjectRow(y); if (r.n > wide) { wide = r.n; wideY = y; } }
    const r = subjectRow(wideY);
    const cx = (r.first + r.last) / 2;
    if (Math.abs(cx - 15.5) > 1.5) headProblems.push(`${id}: head centre ${cx} at y=${wideY}`);
    if (wide < 12) headProblems.push(`${id}: head only ${wide} px wide`);
    // both ears: the ear pixels are skin, never hair / backdrop / frame. Their
    // columns follow the face shape, so they are derived, not hard-coded.
    const kEar = gen.faceHalf(14, spec.sex === 'female', spec.faceShape);
    const lx = 16 - kEar - 2, rx = 15 + kEar + 2;
    for (const [x, y] of [[lx, 13], [lx + 1, 13], [lx, 14], [lx + 1, 14],
      [rx, 13], [rx - 1, 13], [rx, 14], [rx - 1, 14]]) {
      const c = px(img, x, y);
      const skinish = [skin.base, skin.shade, skin.deep].some((h) => dist(c, gen.hexRgb(h)) === 0);
      if (c[3] !== 255 || !skinish) headProblems.push(`${id}: ear pixel (${x},${y}) = ${hex(c)}`);
    }
  }
  check('head 54..66 % of canvas, centred, both ears visible',
    headProblems.length === 0,
    headProblems.slice(0, 4).join(' | ') || `${(ratioMin * 100).toFixed(1)}..${(ratioMax * 100).toFixed(1)}% (target ~60%)`);

  // ---- 4b. the women must be readable as women ------------------------
  // Sex may not live only in palette.json: every female preset has to change the
  // head silhouette - hair mass at least 3 px outside the cheek on several rows,
  // or hair above the crown (bun), or an asymmetric tail/braid.
  const femaleProblems = [];
  const femaleIds = [];
  for (const spec of specs) {
    if (spec.sex !== 'female') continue;
    femaleIds.push(spec.id);
    const img = images.get(spec.id);
    if (!img) { femaleProblems.push(`${spec.id}: missing`); continue; }
    const isSubject = (x, y) => {
      const c = px(img, x, y);
      if (c[3] !== 255) return false;
      if (dist(c, [0x3a, 0x3f, 0x45]) === 0) return false;
      return !isNeutralGrey(c);
    };
    let wideRows = 0, asymRows = 0, aboveCrown = false;
    for (let y = 3; y <= 24; y++) {
      const k = gen.faceHalf(Math.min(y, gen.CHIN), true, spec.faceShape);
      if (!k) continue;
      const faceL = 16 - k, faceR = 15 + k;
      let left = 0, right = 0;
      for (let x = 0; x < W; x++) if (isSubject(x, y)) { if (x < faceL - 3) left++; if (x > faceR + 3) right++; }
      if (left >= 1 || right >= 1) wideRows++;
      if ((left >= 1) !== (right >= 1)) asymRows++;
    }
    // a bun sits above the crown, where there is no face row to compare against
    for (let y = 3; y < gen.HEAD_TOP; y++) if (isSubject(15, y)) aboveCrown = true;
    if (!(wideRows >= 4 || aboveCrown || asymRows >= 6)) {
      femaleProblems.push(`${spec.id}: silhouette stays on the face (wide ${wideRows}, asym ${asymRows}, crown ${aboveCrown})`);
    }
  }
  check('female presets change the head silhouette (hair mass / bun / tail)',
    femaleProblems.length === 0,
    femaleProblems.slice(0, 4).join(' | ') || `${femaleIds.length}/12 women carry a hair silhouette`);

  // ---- 4c. glasses must read as glasses, not as a visor ---------------
  // The failure mode this guards against: a frame thick enough (or a lens dark
  // enough) that the pair turns into one horizontal bar across the face.
  const glassProblems = [];
  let glassCount = 0;
  const THIN = [0x6c, 0x74, 0x7c], THICK = [0x3e, 0x44, 0x4b];
  for (const spec of specs) {
    if (spec.glasses === 'none') continue;
    glassCount++;
    const img = images.get(spec.id);
    if (!img) { glassProblems.push(`${spec.id}: missing`); continue; }
    const frame = spec.glasses === 'thick' ? THICK : THIN;
    const isFrame = (c) => dist(c, frame) === 0;
    const skinTones = gen.SKIN[spec.skin];
    const clearTones = ['#F1F4F6', skinTones.base, skinTones.light, skinTones.shade].map((h) => gen.hexRgb(h));
    // 1. the eye keeps one bright row under the iris: (12..13|18..19, 14) is eye
    //    white or bare skin - never frame, and never another dark eye pixel
    for (const x of [12, 13, 18, 19]) {
      const c = px(img, x, 14);
      if (isFrame(c)) glassProblems.push(`${spec.id}: dark lens block at (${x},14)`);
      else if (!clearTones.some((t) => dist(c, t) === 0)) glassProblems.push(`${spec.id}: (${x},14) is ${hex(c)}, not eye white / skin`);
    }
    // 2. only 1 px of frame: nothing may be frame-coloured on the row below the rim
    for (const x of [11, 12, 13, 14, 17, 18, 19, 20]) {
      if (isFrame(px(img, x, 16))) glassProblems.push(`${spec.id}: frame thicker than 1 px at (${x},16)`);
    }
    // 3. thin keeps a 2 px bridge; thick must leave a skin gap there instead
    for (const x of [15, 16]) {
      const c = px(img, x, 12);
      if (spec.glasses === 'thin' && !isFrame(c)) glassProblems.push(`${spec.id}: thin bridge missing at (${x},12)`);
      if (spec.glasses === 'thick' && isFrame(c)) glassProblems.push(`${spec.id}: thick lenses merged into one bar at (${x},12)`);
    }
    // 4. 1 px temple arm toward each ear
    for (const x of [10, 21]) {
      if (!isFrame(px(img, x, 13))) glassProblems.push(`${spec.id}: no temple arm at (${x},13)`);
    }
  }
  check('glasses read as glasses (1 px rim, clear eye row, thick keeps a lens gap)',
    glassProblems.length === 0,
    glassProblems.slice(0, 4).join(' | ') || `${glassCount}/24 worn, thin bridge / thick gap / temples verified`);

  // ---- 5. pairwise distinct -------------------------------------------
  const seen = new Map();
  const dupes = [];
  for (const [id, h] of hashes) {
    if (seen.has(h)) dupes.push(`${seen.get(h)} == ${id}`);
    seen.set(h, id);
  }
  check('all 24 presets pairwise different (pixel hash)', dupes.length === 0, dupes.join(', ') || `${seen.size} unique SHA-1`);

  // ---- 6. palette.json ------------------------------------------------
  const palProblems = [];
  let pal = null;
  if (!fs.existsSync(PALETTE)) palProblems.push('palette.json missing');
  else {
    try { pal = JSON.parse(fs.readFileSync(PALETTE, 'utf8')); } catch (e) { palProblems.push('palette.json is not valid JSON'); }
  }
  const KEYS = ['id', '\u6027\u522b', '\u5e74\u9f84\u6bb5', '\u53d1\u578b', '\u773c\u955c', '\u670d\u88c5'];
  if (pal) {
    if (!Array.isArray(pal)) palProblems.push('palette.json is not an array');
    else {
      if (pal.length !== 24) palProblems.push(`palette.json has ${pal.length} rows`);
      pal.forEach((row, i) => {
        const keys = Object.keys(row);
        if (keys.length !== KEYS.length || KEYS.some((k) => !(k in row))) palProblems.push(`row ${i}: keys ${keys.join(',')}`);
        if (row.id !== 'p' + String(i).padStart(2, '0')) palProblems.push(`row ${i}: id ${row.id}`);
        const spec = byId.get(row.id);
        if (!spec) { palProblems.push(`row ${i}: unknown id`); return; }
        const expect = {
          '\u6027\u522b': gen.ZH.sex[spec.sex],
          '\u5e74\u9f84\u6bb5': gen.ZH.age[spec.age],
          '\u53d1\u578b': gen.ZH.hairStyle[spec.hairStyle],
          '\u773c\u955c': gen.ZH.glasses[spec.glasses],
          '\u670d\u88c5': gen.ZH.outfit[spec.outfit]
        };
        for (const k of Object.keys(expect)) {
          if (row[k] !== expect[k]) palProblems.push(`row ${i} (${row.id}) ${k}: "${row[k]}" != "${expect[k]}"`);
        }
      });
    }
  }
  check('palette.json: 24 rows, exact key set, matches the drawings',
    palProblems.length === 0, palProblems.slice(0, 5).join(' | ') || '24 rows / 6 fields');

  // ---- 7. axis coverage ----------------------------------------------
  const cov = gen.coverage();
  const uncovered = [];
  for (const axis of Object.keys(gen.AXES)) {
    for (const v of gen.AXES[axis]) if (!cov[axis][v]) uncovered.push(`${axis}:${v}`);
  }
  const axisSummary = Object.keys(gen.AXES)
    .map((a) => `${a}=${Object.keys(cov[a]).length}`)
    .join(' ');
  check('every axis value covered (skin/hair/style/glasses/beard/outfit/sex/age)',
    uncovered.length === 0,
    uncovered.length ? `missing ${uncovered.join(', ')}` : axisSummary);

  // ---- 8. determinism / source parity --------------------------------
  const parityProblems = [];
  for (const spec of specs) {
    const r = gen.renderPortrait(spec);
    const img = images.get(spec.id);
    if (!img) { parityProblems.push(`${spec.id}: no file`); continue; }
    if (Buffer.compare(Buffer.from(r.d), img.d) !== 0) parityProblems.push(`${spec.id}: on-disk pixels differ from a fresh render`);
  }
  check('deterministic: on-disk pixels == fresh render', parityProblems.length === 0, parityProblems.slice(0, 3).join(' | ') || '24/24 byte-identical');

  // ---- 9. contact sheet ----------------------------------------------
  let sheetOk = false, sheetDetail = 'missing';
  if (fs.existsSync(SHEET)) {
    try {
      const s = readPng(SHEET);
      sheetOk = s.w >= 6 * 32 * 3 && s.h >= 4 * 32 * 3;
      sheetDetail = `${s.w}x${s.h}`;
    } catch (e) { sheetDetail = e.message; }
  }
  check('contact sheet tools/preview/portraits_sheet.png', sheetOk, sheetDetail);

  // ---------------------------------------------------------------- report
  const failed = results.filter((r) => !r.ok);
  const mtimes = [];
  for (let i = 0; i < 24; i++) {
    const f = path.join(TEX_DIR, 'p' + String(i).padStart(2, '0') + '.png');
    if (fs.existsSync(f)) mtimes.push(fs.statSync(f).mtime.toISOString());
  }
  mtimes.sort();
  console.log('VeriBorder portrait asset audit (26.4.1)');
  console.log(`  assets   ${path.relative(ROOT, TEX_DIR)}\\p00..p23.png + palette.json`);
  console.log(`  mtimes   png ${mtimes[0]} .. ${mtimes[mtimes.length - 1]}` +
    (fs.existsSync(PALETTE) ? ` / palette ${fs.statSync(PALETTE).mtime.toISOString()}` : ''));
  for (const r of results) {
    console.log(`  ${r.ok ? 'PASS' : 'FAIL'}  ${r.name}`);
    if (r.detail) console.log(`        ${r.detail}`);
  }
  if (failed.length) {
    console.log(`\nFAIL (${failed.length}/${results.length} checks)`);
    process.exitCode = 1;
  } else {
    console.log(`\nPASS (${results.length}/${results.length} checks, 24 portraits)`);
  }
}

main();

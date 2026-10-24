const fs = require('fs');
const p = 'tools/bbmodel_to_mc.js';
let t = fs.readFileSync(p, 'utf8');
const start = t.indexOf("if (process.argv.includes('--display')) {");
const endMarker = "\n}\n\nfs.mkdirSync";
const end = t.indexOf(endMarker, start);
if (start < 0 || end < 0) { console.error('markers', start, end); process.exit(1); }
const block = `if (process.argv.includes('--display')) {
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
    gui: { rotation: [30, 225, 0], translation: [0, ty, 0], scale: [gs, gs, gs] },
    ground: { translation: [0, 3 + ty * 0.25, 0], scale: [gds, gds, gds] },
    fixed: { rotation: [0, 180, 0], translation: [0, ty * 0.5, 0], scale: [fs, fs, fs] }
  };
}`;
t = t.slice(0, start) + block + t.slice(end);
fs.writeFileSync(p, t);
console.log('display patched');

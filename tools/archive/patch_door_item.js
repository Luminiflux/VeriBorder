const fs = require('fs');

// --- door leaves: 6 px each, full 12 px lane, 17 px tall -------------------
const r = 'src/main/java/com/veriborder/client/EGateRenderer.java';
let t = fs.readFileSync(r, 'utf8');

t = t.replace('private static final float DOOR_WIDTH = 5.0F;', 'private static final float DOOR_WIDTH = 6.0F;');
t = t.replace('private static final float DOOR_SLIDE = 5.0F;', 'private static final float DOOR_SLIDE = 6.0F;');

// left leaf x -6..0, right 0..6; rails/stiles/mid at the new height
const pairs = [
  ['-5.0F, 16.6F, -1.0F, 5.0F, 1.4F, 2.0F', '-6.0F, 15.6F, -1.0F, 6.0F, 1.4F, 2.0F'],
  ['-5.0F, 2.0F, -1.0F, 5.0F, 1.4F, 2.0F', '-6.0F, 0.0F, -1.0F, 6.0F, 1.4F, 2.0F'],
  ['-5.0F, 2.0F, -1.0F, 1.4F, 16.0F, 2.0F', '-6.0F, 0.0F, -1.0F, 1.4F, 17.0F, 2.0F'],
  ['-1.4F, 2.0F, -1.0F, 1.4F, 16.0F, 2.0F', '-1.4F, 0.0F, -1.0F, 1.4F, 17.0F, 2.0F'],
  ['-5.0F, 9.2F, -1.0F, 5.0F, 1.2F, 2.0F', '-6.0F, 7.8F, -1.0F, 6.0F, 1.2F, 2.0F'],
  ['-3.6F, 3.4F, -0.9F, 2.2F, 5.8F, 1.8F', '-4.6F, 1.4F, -0.9F, 3.2F, 6.4F, 1.8F'],
  ['-3.6F, 10.4F, -0.9F, 2.2F, 6.2F, 1.8F', '-4.6F, 9.0F, -0.9F, 3.2F, 6.6F, 1.8F'],
  ['0.0F, 16.6F, -1.0F, 5.0F, 1.4F, 2.0F', '0.0F, 15.6F, -1.0F, 6.0F, 1.4F, 2.0F'],
  ['0.0F, 2.0F, -1.0F, 5.0F, 1.4F, 2.0F', '0.0F, 0.0F, -1.0F, 6.0F, 1.4F, 2.0F'],
  ['0.0F, 2.0F, -1.0F, 1.4F, 16.0F, 2.0F', '0.0F, 0.0F, -1.0F, 1.4F, 17.0F, 2.0F'],
  ['3.6F, 2.0F, -1.0F, 1.4F, 16.0F, 2.0F', '4.6F, 0.0F, -1.0F, 1.4F, 17.0F, 2.0F'],
  ['0.0F, 9.2F, -1.0F, 5.0F, 1.2F, 2.0F', '0.0F, 7.8F, -1.0F, 6.0F, 1.2F, 2.0F'],
  ['1.4F, 3.4F, -0.9F, 2.2F, 5.8F, 1.8F', '1.4F, 1.4F, -0.9F, 3.2F, 6.4F, 1.8F'],
  ['1.4F, 10.4F, -0.9F, 2.2F, 6.2F, 1.8F', '1.4F, 9.0F, -0.9F, 3.2F, 6.6F, 1.8F']
];
for (const [a, b] of pairs) {
  if (!t.includes(a)) console.log('MISS', a);
  t = t.split(a).join(b);
}
fs.writeFileSync(r, t);
console.log('door patched');

// --- item display: half the downward shift so 2-block icons centre ----------
const c = 'tools/bbmodel_to_mc.js';
let s = fs.readFileSync(c, 'utf8');
s = s.replace("gui: { rotation: [30, 225, 0], translation: [0, ty, 0], scale: [gs, gs, gs] },",
  "gui: { rotation: [30, 225, 0], translation: [0, ty * 0.5, 0], scale: [gs, gs, gs] },");
fs.writeFileSync(c, s);
console.log('display patched');

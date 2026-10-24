const fs = require('fs');
const path = require('path');

const DIR = 'src/main/resources/assets/veriborder/models/block';
const files = fs.readdirSync(DIR).filter(f => f.endsWith('.json') && f !== 'device_extension.json');
const EPS = 0.02;

function vol(e) {
  return Math.abs((e.to[0] - e.from[0]) * (e.to[1] - e.from[1]) * (e.to[2] - e.from[2]));
}
function ov(a1, a2, b1, b2) { return Math.min(a2, b2) - Math.max(a1, b1); }

function conflicts(els) {
  const out = [];
  for (let i = 0; i < els.length; i++) {
    for (let j = i + 1; j < els.length; j++) {
      const a = els[i], b = els[j];
      for (let ax = 0; ax < 3; ax++) {
        const o1 = (ax + 1) % 3, o2 = (ax + 2) % 3;
        const aMin = Math.min(a.from[ax], a.to[ax]), aMax = Math.max(a.from[ax], a.to[ax]);
        const bMin = Math.min(b.from[ax], b.to[ax]), bMax = Math.max(b.from[ax], b.to[ax]);
        const d1 = ov(Math.min(a.from[o1], a.to[o1]), Math.max(a.from[o1], a.to[o1]),
                      Math.min(b.from[o1], b.to[o1]), Math.max(b.from[o1], b.to[o1]));
        const d2 = ov(Math.min(a.from[o2], a.to[o2]), Math.max(a.from[o2], a.to[o2]),
                      Math.min(b.from[o2], b.to[o2]), Math.max(b.from[o2], b.to[o2]));
        if (d1 <= 0.02 || d2 <= 0.02) continue;
        const sameMax = Math.abs(aMax - bMax) < 0.005;
        const sameMin = Math.abs(aMin - bMin) < 0.005;
        if (sameMax || sameMin) out.push({ i, j, ax, side: sameMax ? 'max' : 'min' });
      }
    }
  }
  return out;
}

let totalFixed = 0;
for (const f of files) {
  const p = path.join(DIR, f);
  const m = JSON.parse(fs.readFileSync(p, 'utf8'));
  const els = m.elements || [];
  let fixed = 0;
  for (let pass = 0; pass < 3; pass++) {
    const cs = conflicts(els);
    if (!cs.length) break;
    const touched = new Set();
    for (const c of cs) {
      const a = els[c.i], b = els[c.j];
      const small = vol(a) <= vol(b) ? a : b;
      const key = (vol(a) <= vol(b) ? c.i : c.j) + ':' + c.ax + ':' + c.side;
      if (touched.has(key)) continue;
      touched.add(key);
      if (c.side === 'max') {
        if (small.to[c.ax] >= small.from[c.ax]) small.to[c.ax] += EPS;
        else small.from[c.ax] += EPS;
      } else {
        if (small.to[c.ax] >= small.from[c.ax]) small.from[c.ax] -= EPS;
        else small.to[c.ax] -= EPS;
      }
      fixed++;
    }
  }
  if (fixed) {
    fs.writeFileSync(p, JSON.stringify(m, null, 2) + '\n', 'utf8');
    totalFixed += fixed;
    console.log(f, 'nudged', fixed);
  }
}
console.log('TOTAL nudges', totalFixed);

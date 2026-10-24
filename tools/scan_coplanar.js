const fs = require('fs');
const path = require('path');

const DIR = 'src/main/resources/assets/veriborder/models/block';
const files = fs.readdirSync(DIR).filter(f => f.endsWith('.json') && f !== 'device_extension.json');

function vol(e) {
  return Math.abs((e.to[0] - e.from[0]) * (e.to[1] - e.from[1]) * (e.to[2] - e.from[2]));
}
function overlap(a1, a2, b1, b2) {
  return Math.min(a2, b2) - Math.max(a1, b1);
}

let total = 0;
for (const f of files) {
  const m = JSON.parse(fs.readFileSync(path.join(DIR, f), 'utf8'));
  const els = m.elements || [];
  const hits = [];
  for (let i = 0; i < els.length; i++) {
    for (let j = i + 1; j < els.length; j++) {
      const a = els[i], b = els[j];
      for (let ax = 0; ax < 3; ax++) {
        const o1 = (ax + 1) % 3, o2 = (ax + 2) % 3;
        const aMin = Math.min(a.from[ax], a.to[ax]), aMax = Math.max(a.from[ax], a.to[ax]);
        const bMin = Math.min(b.from[ax], b.to[ax]), bMax = Math.max(b.from[ax], b.to[ax]);
        const ov1 = overlap(Math.min(a.from[o1], a.to[o1]), Math.max(a.from[o1], a.to[o1]),
                            Math.min(b.from[o1], b.to[o1]), Math.max(b.from[o1], b.to[o1]));
        const ov2 = overlap(Math.min(a.from[o2], a.to[o2]), Math.max(a.from[o2], a.to[o2]),
                            Math.min(b.from[o2], b.to[o2]), Math.max(b.from[o2], b.to[o2]));
        if (ov1 <= 0.02 || ov2 <= 0.02) continue;
        const sameMax = Math.abs(aMax - bMax) < 0.005;
        const sameMin = Math.abs(aMin - bMin) < 0.005;
        if (sameMax || sameMin) {
          hits.push({ i, j, ax, side: sameMax ? 'max' : 'min' });
        }
      }
    }
  }
  if (hits.length) {
    total += hits.length;
    console.log(f, 'conflicts', hits.length);
    for (const h of hits.slice(0, 4)) {
      console.log('   ', h.i, h.j, 'axis', 'xyz'[h.ax], h.side,
        JSON.stringify(els[h.i].from), JSON.stringify(els[h.i].to),
        '||', JSON.stringify(els[h.j].from), JSON.stringify(els[h.j].to));
    }
  }
}
console.log('TOTAL', total);

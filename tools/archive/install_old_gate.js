const fs = require('fs');
const path = require('path');

const SRC = 'assets/pipeline_test/oldgate';
const DST = 'src/main/resources/assets/veriborder/models/block';
const TEX_DST = 'src/main/resources/assets/veriborder/textures/block';

// current display block, so tall item icons still fit
const current = JSON.parse(fs.readFileSync(path.join(DST, 'egate.json'), 'utf8'));
const display = current.display;

for (const id of ['egate', 'exit_gate']) {
  const model = JSON.parse(fs.readFileSync(path.join(SRC, id + '.json'), 'utf8'));
  const before = model.elements.length;
  // the base is the only full-width element that starts at y=0 and is <1 px tall
  model.elements = model.elements.filter(e => {
    const y1 = Math.min(e.from[1], e.to[1]);
    const y2 = Math.max(e.from[1], e.to[1]);
    const x1 = Math.min(e.from[0], e.to[0]);
    const x2 = Math.max(e.from[0], e.to[0]);
    const isBase = y1 <= 0.01 && y2 <= 1.0 && (x2 - x1) > 18;
    return !isBase;
  });
  // drop everything by the removed base height so the pillars stand on y=0
  const drop = 0.9;
  for (const e of model.elements) {
    e.from = e.from.map((v, i) => (i === 1 ? +(v - drop).toFixed(3) : v));
    e.to = e.to.map((v, i) => (i === 1 ? +(v - drop).toFixed(3) : v));
  }
  if (display) model.display = display;
  fs.writeFileSync(path.join(DST, id + '.json'), JSON.stringify(model, null, 2) + '\n', 'utf8');
  console.log(id, 'elements', before, '->', model.elements.length);
}

for (const f of fs.readdirSync(SRC)) {
  if (f.endsWith('.png')) {
    fs.copyFileSync(path.join(SRC, f), path.join(TEX_DST, f));
  }
}
console.log('textures copied');

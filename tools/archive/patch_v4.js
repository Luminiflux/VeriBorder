const fs = require('fs');
let t = fs.readFileSync('tools/build_gate_r9_v3.js', 'utf8');

t = t.replace("['screen', 'veriborder:block/r9_screen']",
  "['screen', 'veriborder:block/r9_screen_v2']");

const topOld = "    els.push(el('top_plate', [x0, 23.86, 0.5], [x1, 24, 15.5], 'silver_flat'));";
const topNew = [
  "    els.push(el('top_step1', [x0, 23.4, 0.5], [x1, 23.75, 15.5], 'silver_flat'));",
  "    els.push(el('top_step2', [x0 + 0.3, 23.75, 0.8], [x1 - 0.3, 23.95, 15.2], 'shell'));",
  "    els.push(el('top_step3', [x0 + 0.55, 23.95, 1.05], [x1 - 0.55, 24.0, 14.95], 'silver_flat'));",
  "    if (x0 > 1) els.push(el('top_pod', [x0 + 0.5, 24.0, 6.0], [x1 - 0.5, 24.4, 10.0], 'shell2'));"
].join('\n');
t = t.replace(topOld, topNew);

const bevelOld = "    els.push(el('front_bevel', [x0, 1, 0.45], [x1, 1.4, 1.0], 'silver_flat'));";
const bevelNew = [
  "    els.push(el('front_bevel', [x0, 1, 0.45], [x1, 1.4, 1.0], 'silver_flat'));",
  "    els.push(el('seam_mid', [x0, 11.95, 0.5], [x1, 12.08, 1.05], 'dark'));",
  "    els.push(el('seam_low', [x0, 17.95, 0.5], [x1, 18.08, 1.05], 'dark'));"
].join('\n');
t = t.replace(bevelOld, bevelNew);

fs.writeFileSync('tools/build_gate_r9_v4.js', t);
console.log('v4 written', t.length);

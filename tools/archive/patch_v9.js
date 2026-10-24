const fs = require('fs');
const p = 'tools/build_gate_r9_v8.js';
let t = fs.readFileSync(p, 'utf8');

// add the arrow screen texture slot
t = t.replace("  ['screen', 'veriborder:block/r9_screen_ui'],",
  "  ['screen', 'veriborder:block/r9_screen_ui'],\n  ['gate_screen', 'veriborder:block/gate_screen_arrow'],");

// pillars sit directly on the ground: drop the extra dark base
t = t.replace("    els.push(el('pillar', [x0, 1, 1], [x1, 17, 15], 'shell'));",
  "    els.push(el('pillar', [x0, 0, 1], [x1, 17, 15], 'shell'));");

// remove the extra base, floor strip, arrow decal and both side label plates
t = t.split('\n').filter(line => {
  const l = line;
  return !(l.includes("el('base_trim'") || l.includes("el('lane_rail'") ||
           l.includes("el('lane_arrow'") || l.includes("el('label_face_plate'") ||
           l.includes("el('label_passport_plate'") || l.includes("{ west: 'label_face' }") ||
           l.includes("{ east: label }"));
}).join('\n');

// gate screens use the pixel green arrow
t = t.replace("els.push(el('screen', [x0 + 0.3, 6.7, 0.3], [x1 - 0.3, 12.3, 0.4], 'screen'));",
  "els.push(el('screen', [x0 + 0.3, 6.7, 0.3], [x1 - 0.3, 12.3, 0.4], 'gate_screen'));");

fs.writeFileSync('tools/build_gate_r9_v9.js', t);
console.log('v9 written', t.length);

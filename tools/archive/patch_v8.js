const fs = require('fs');
const p = 'tools/build_gate_r9_v8.js';
let t = fs.readFileSync(p, 'utf8');
t = t.split('[[0, 3], [13, 16]]').join('[[0, 2.9], [13.1, 16]]');
t = t.split('x0 === 0 ? 2.94 : 13.0').join('x0 === 0 ? 2.84 : 13.1');
t = t.split('x0 === 0 ? 3.0 : 13.06').join('x0 === 0 ? 2.9 : 13.16');
t = t.split('[[0.35, 2.65], [13.35, 15.65]]').join('[[0.35, 2.55], [13.45, 15.65]]');
fs.writeFileSync(p, t);
console.log('patched');

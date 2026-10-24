const fs = require('fs');
const p = 'src/test/java/com/veriborder/border/DualGateEntryTest.java';
let t = fs.readFileSync(p, 'utf8');
const lines = t.split('\n');
for (let i = 0; i < lines.length; i++) {
  if (lines[i].includes('clean.getString("exitPort")')) {
    lines[i] = '        assertEquals("深圳湾口岸", clean.getString("exitPort"));';
  }
}
fs.writeFileSync(p, lines.join('\n'), 'utf8');
console.log('fixed');

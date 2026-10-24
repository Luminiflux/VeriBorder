const fs = require('fs');
let t = fs.readFileSync('tools/build_gate_r9_v4.js', 'utf8');

t = t.replace("['screen', 'veriborder:block/r9_screen_v2']",
  "['screen', 'veriborder:block/r9_screen_v3']");

// larger, more legible indicator LEDs with a silver bezel
t = t.replace(
  "  els.push(el('reader_led', [13.3, 18.0, 0.2], [13.8, 18.55, 0.34], exit ? 'led_red' : 'led_green'));",
  "  els.push(el('reader_led_bezel', [13.15, 17.85, 0.18], [13.95, 18.75, 0.3], 'silver_flat'));\n" +
  "  els.push(el('reader_led', [13.25, 17.95, 0.28], [13.85, 18.65, 0.4], exit ? 'led_red' : 'led_green'));"
);
t = t.replace(
  "  els.push(el('face_led', [2.2, 18.0, 0.2], [2.7, 18.55, 0.34], exit ? 'led_green' : 'led_red'));",
  "  els.push(el('face_led_bezel', [2.05, 17.85, 0.18], [2.85, 18.75, 0.3], 'silver_flat'));\n" +
  "  els.push(el('face_led', [2.15, 17.95, 0.28], [2.75, 18.65, 0.4], exit ? 'led_green' : 'led_red'));"
);

fs.writeFileSync('tools/build_gate_r9_v5.js', t);
console.log('v5 written', t.length);

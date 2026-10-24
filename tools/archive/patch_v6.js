const fs = require('fs');
let t = fs.readFileSync('tools/build_gate_r9_v5.js', 'utf8');

const start = t.indexOf('  // right cabinet: recessed passport reader + screen');
const end = t.indexOf('  // readable English on the outer side faces');
if (start < 0 || end < 0) { console.error('markers not found'); process.exit(1); }

const block = `  // right cabinet: passport reader (frame around an open slot) + screen (frame + recessed glass)
  els.push(el('reader_frame_t', [13.2, 12.0, 0.10], [15.8, 12.35, 0.55], 'silver'));
  els.push(el('reader_frame_b', [13.2, 9.05, 0.10], [15.8, 9.4, 0.55], 'silver'));
  els.push(el('reader_frame_l', [13.2, 9.05, 0.10], [13.55, 12.35, 0.55], 'silver'));
  els.push(el('reader_frame_r', [15.45, 9.05, 0.10], [15.8, 12.35, 0.55], 'silver'));
  els.push(el('reader_slot', [13.7, 10.35, 0.06], [15.3, 10.95, 0.16], 'dark'));
  els.push(el('reader_slot_lip', [13.6, 10.2, 0.04], [15.4, 11.1, 0.08], 'silver_flat'));

  els.push(el('reader_scr_frame_t', [13.25, 16.95, 0.10], [15.75, 17.3, 0.55], 'silver_dark'));
  els.push(el('reader_scr_frame_b', [13.25, 12.95, 0.10], [15.75, 13.3, 0.55], 'silver_dark'));
  els.push(el('reader_scr_frame_l', [13.25, 12.95, 0.10], [13.6, 17.3, 0.55], 'silver_dark'));
  els.push(el('reader_scr_frame_r', [15.4, 12.95, 0.10], [15.75, 17.3, 0.55], 'silver_dark'));
  els.push(el('reader_screen', [13.6, 13.3, 0.26], [15.4, 16.95, 0.36], 'screen'));

  els.push(el('reader_led_ring', [13.15, 17.75, 0.06], [13.95, 18.75, 0.14], 'silver_flat'));
  els.push(el('reader_led', [13.25, 17.85, 0.04], [13.85, 18.65, 0.1], exit ? 'led_red' : 'led_green'));

  // left cabinet: face camera (frame + lens) + screen
  els.push(el('camera_frame_t', [0.45, 19.1, 0.10], [2.55, 19.45, 0.55], 'silver'));
  els.push(el('camera_frame_b', [0.45, 16.35, 0.10], [2.55, 16.7, 0.55], 'silver'));
  els.push(el('camera_frame_l', [0.45, 16.35, 0.10], [0.8, 19.45, 0.55], 'silver'));
  els.push(el('camera_frame_r', [2.2, 16.35, 0.10], [2.55, 19.45, 0.55], 'silver'));
  els.push(el('camera_lens', [1.0, 17.1, 0.16], [2.0, 18.7, 0.3], 'dark'));
  els.push(el('camera_glass', [1.2, 17.3, 0.12], [1.8, 18.5, 0.17], 'glass'));

  els.push(el('face_scr_frame_t', [0.3, 15.75, 0.10], [2.7, 16.1, 0.55], 'silver_dark'));
  els.push(el('face_scr_frame_b', [0.3, 12.35, 0.10], [2.7, 12.7, 0.55], 'silver_dark'));
  els.push(el('face_scr_frame_l', [0.3, 12.35, 0.10], [0.65, 16.1, 0.55], 'silver_dark'));
  els.push(el('face_scr_frame_r', [2.35, 12.35, 0.10], [2.7, 16.1, 0.55], 'silver_dark'));
  els.push(el('face_screen', [0.65, 12.7, 0.26], [2.35, 15.75, 0.36], 'screen'));

  els.push(el('face_led_ring', [2.05, 17.75, 0.06], [2.85, 18.75, 0.14], 'silver_flat'));
  els.push(el('face_led', [2.15, 17.85, 0.04], [2.75, 18.65, 0.1], exit ? 'led_green' : 'led_red'));

`;
t = t.slice(0, start) + block + t.slice(end);
fs.writeFileSync('tools/build_gate_r9_v6.js', t);
console.log('v6 written', t.length);

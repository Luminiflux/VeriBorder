#!/usr/bin/env node
// One-shot generator for the 12 "remaining" device block models.
// Writes src/main/resources/assets/veriborder/models/block/<id>.json
// Does NOT touch tools/, egate/exit_gate, the 7 issuer machines, or Java code.
'use strict';
const fs = require('fs');
const path = require('path');

const ROOT = path.resolve(__dirname, '..', '..');
const OUT = path.join(ROOT, 'src', 'main', 'resources', 'assets', 'veriborder', 'models', 'block');

const DISPLAY = {
  thirdperson_righthand: { rotation: [75, 45, 0], translation: [0, 2.5, 0], scale: [0.375, 0.375, 0.375] },
  thirdperson_lefthand: { rotation: [75, 45, 0], translation: [0, 2.5, 0], scale: [0.375, 0.375, 0.375] },
  firstperson_righthand: { rotation: [0, 45, 0], scale: [0.4, 0.4, 0.4] },
  firstperson_lefthand: { rotation: [0, 45, 0], scale: [0.4, 0.4, 0.4] },
  gui: { rotation: [30, 225, 0], scale: [0.625, 0.625, 0.625] },
  ground: { translation: [0, 3, 0], scale: [0.25, 0.25, 0.25] },
  fixed: { rotation: [0, 180, 0], scale: [0.5, 0.5, 0.5] }
};

function box(f, t, r) {
  const faces = {};
  for (const n of ['north', 'south', 'east', 'west', 'up', 'down']) {
    faces[n] = { texture: '#' + t, uv: [0, 0, 16, 16] };
  }
  const el = { from: [f[0], f[1], f[2]], to: [f[3], f[4], f[5]], faces };
  if (r) el.rotation = r;
  return el;
}

// element shorthand: [x1,y1,z1,x2,y2,z2, texture, rotation?]
const E = (a) => ({ f: a.slice(0, 6), t: a[6], r: a[7] || undefined });

const DEVICES = [
  // 1. entry_stamp — standing stamper: dark press head, orange accent, green/red LEDs
  {
    id: 'entry_stamp',
    slots: ['body', 'body2', 'trim', 'accent', 'screen', 'screen2', 'led_green', 'led_red', 'led_amber', 'slot', 'paper'],
    els: [
      [2.5, 0, 3, 13.5, 1, 13, 'trim'],
      [3, 1, 3.5, 13, 9.5, 12.5, 'body'],
      [2.9, 4.6, 3.3, 13.1, 5.4, 12.7, 'accent'],
      [2.5, 9.5, 3, 13.5, 10.4, 13, 'body2'],
      [4.5, 9.7, 4.5, 11.5, 10.0, 11.5, 'paper'],
      [7.0, 10.4, 7.5, 9.0, 13.8, 9.5, 'body2'],
      [4.8, 10.4, 6.0, 11.2, 11.3, 10.0, 'trim'],
      [4.2, 13.8, 5.5, 11.8, 14.9, 10.5, 'body2'],
      [4.2, 14.9, 5.5, 11.8, 15.5, 10.5, 'accent'],
      [4.2, 6.8, 3.2, 7.4, 8.2, 3.5, 'screen'],
      [8.2, 6.8, 3.2, 10.4, 7.8, 3.5, 'screen2'],
      [3.2, 6.0, 3.2, 4.3, 6.8, 3.6, 'led_green'],
      [3.2, 6.9, 3.2, 4.3, 7.7, 3.6, 'led_red'],
      [3.2, 7.8, 3.2, 4.3, 8.6, 3.6, 'led_amber'],
      [12.7, 2.5, 6, 13.2, 4.2, 10, 'slot']
    ]
  },
  // 2. exit_stamp — same family, blue accent, horizontal LED row, side pillar
  {
    id: 'exit_stamp',
    slots: ['body', 'body2', 'trim', 'accent', 'screen', 'led_green', 'led_red', 'led_amber', 'slot', 'paper'],
    els: [
      [2.5, 0, 3, 13.5, 1, 13, 'trim'],
      [3, 1, 3.5, 13, 9.2, 12.5, 'body'],
      [2.9, 5.8, 3.3, 13.1, 6.4, 12.7, 'accent'],
      [2.5, 9.2, 3, 13.5, 10.1, 13, 'body2'],
      [4.5, 9.4, 4.5, 11.5, 9.7, 11.5, 'paper'],
      [7.2, 10.1, 7.8, 8.8, 13.6, 9.2, 'body2'],
      [5.2, 10.1, 6.5, 10.8, 10.9, 9.5, 'trim'],
      [4.6, 13.6, 5.5, 11.4, 14.8, 10.5, 'body2'],
      [4.6, 14.8, 5.5, 11.4, 15.4, 10.5, 'accent'],
      [4.2, 6.6, 3.2, 7.0, 7.8, 3.5, 'screen'],
      [7.6, 6.6, 3.2, 8.6, 7.4, 3.6, 'led_green'],
      [9.0, 6.6, 3.2, 10.0, 7.4, 3.6, 'led_amber'],
      [10.4, 6.6, 3.2, 11.4, 7.4, 3.6, 'led_red'],
      [2.6, 1, 3.4, 3.0, 9.2, 12.6, 'accent'],
      [12.7, 2.5, 6, 13.2, 4.2, 10, 'slot']
    ]
  },
  // 3. stamp_history — standing query kiosk: big glowing screen, card tray, gold accent
  {
    id: 'stamp_history',
    slots: ['body', 'body2', 'trim', 'accent', 'screen', 'led_green', 'led_amber', 'led_blue', 'paper'],
    els: [
      [2.5, 0, 3, 13.5, 1, 13, 'trim'],
      [3, 1, 3.5, 13, 6.8, 12.5, 'body'],
      [2.9, 4.0, 3.3, 13.1, 4.7, 12.7, 'accent'],
      [3, 6.8, 5, 13, 7.4, 11, 'body2'],
      [5, 7.4, 6, 11, 7.55, 10, 'paper'],
      [3.2, 7.4, 3.7, 12.8, 14.2, 12.3, 'body2'],
      [4.2, 8.6, 3.1, 11.8, 13.4, 3.6, 'screen'],
      [3.2, 7.4, 3.5, 3.8, 14.2, 12.5, 'accent'],
      [12.2, 7.4, 3.5, 12.8, 14.2, 12.5, 'accent'],
      [3.2, 14.2, 3.7, 12.8, 14.9, 12.3, 'body'],
      [4.4, 7.6, 3.1, 5.4, 8.2, 3.5, 'led_green'],
      [5.8, 7.6, 3.1, 6.8, 8.2, 3.5, 'led_amber'],
      [7.2, 7.6, 3.1, 8.2, 8.2, 3.5, 'led_blue']
    ]
  },
  // 4. print_preview — screen + paper outlet with protruding sheet, red/gray accent
  {
    id: 'print_preview',
    slots: ['body', 'body2', 'trim', 'accent', 'screen', 'led_green', 'led_red', 'led_amber', 'led_blue', 'slot', 'paper'],
    els: [
      [2.5, 0, 3, 13.5, 1, 13, 'trim'],
      [3, 1, 3.5, 13, 11, 12.5, 'body'],
      [2.9, 5.8, 3.3, 13.1, 6.4, 12.7, 'accent'],
      [4.2, 7.0, 3.1, 11.8, 10.2, 3.5, 'screen'],
      [3, 11, 3.5, 13, 13, 12.5, 'body2'],
      [5.5, 10.6, 3.1, 10.5, 11.0, 3.5, 'slot'],
      [5.5, 10.0, 2.3, 10.5, 10.6, 3.1, 'paper'],
      [3, 13, 3.5, 13, 13.8, 12.5, 'accent'],
      [2.7, 1, 3.4, 3.1, 11, 12.6, 'body2'],
      [12.9, 1, 3.4, 13.3, 11, 12.6, 'accent'],
      [4.4, 6.2, 3.1, 5.4, 6.8, 3.5, 'led_green'],
      [6.0, 6.2, 3.1, 7.0, 6.8, 3.5, 'led_red'],
      [7.6, 6.2, 3.1, 8.6, 6.8, 3.5, 'led_amber'],
      [9.2, 6.2, 3.1, 10.2, 6.8, 3.5, 'led_blue']
    ]
  },
  // 5. hotel_reader — small counter reader: card slot, tiny screen, status LEDs
  {
    id: 'hotel_reader',
    slots: ['body', 'body2', 'trim', 'accent', 'screen', 'screen2', 'led_green', 'led_amber', 'led_blue', 'slot', 'paper', 'detail'],
    els: [
      [4.5, 0, 4.5, 11.5, 0.6, 11.5, 'trim'],
      [5, 0.6, 5, 11, 5.2, 11, 'body'],
      [5, 2.0, 4.9, 11, 2.5, 11.1, 'accent'],
      [4.8, 5.2, 4.8, 11.2, 5.5, 11.2, 'trim'],
      [6, 5.2, 6, 10, 5.5, 10, 'slot'],
      [6.8, 5.2, 6.8, 9.2, 6.6, 9.2, 'paper'],
      [5.8, 3.2, 4.7, 8.2, 4.6, 5.0, 'screen'],
      [5.8, 2.6, 4.7, 6.6, 3.0, 5.0, 'screen2'],
      [8.6, 3.2, 4.7, 9.4, 3.7, 5.0, 'led_green'],
      [8.6, 3.9, 4.7, 9.4, 4.4, 5.0, 'led_amber'],
      [8.6, 4.6, 4.7, 9.4, 5.0, 5.0, 'led_blue'],
      [4.9, 0.6, 4.9, 5.3, 5.2, 11.1, 'accent'],
      [10.7, 0.6, 4.9, 11.1, 5.2, 11.1, 'accent'],
      [5.6, 0.6, 4.7, 10.4, 1.1, 5.0, 'detail']
    ]
  },
  // 6. desk_scanner — flat scanner: glass bed, protruding document, LED strip
  {
    id: 'desk_scanner',
    slots: ['body', 'body2', 'trim', 'accent', 'screen', 'led_green', 'led_amber', 'led_blue', 'led_red', 'slot', 'paper', 'glass'],
    els: [
      [1.5, 0, 2, 14.5, 1, 14, 'trim'],
      [2, 1, 2.5, 14, 3.9, 13.5, 'body'],
      [3, 3.9, 3.5, 13, 4.4, 12.5, 'glass'],
      [5, 3.9, 2.2, 11, 4.1, 3.5, 'paper'],
      [2, 2.2, 2.4, 14, 2.6, 13.6, 'accent'],
      [3, 4.4, 4, 13, 4.7, 12, 'body2'],
      [2, 1, 2.2, 14, 2.6, 2.5, 'body2'],
      [9.5, 1.2, 2.2, 12.5, 2.4, 2.5, 'screen'],
      [3, 1.4, 2.2, 4, 2.2, 2.5, 'led_green'],
      [4.5, 1.4, 2.2, 5.5, 2.2, 2.5, 'led_amber'],
      [6, 1.4, 2.2, 7, 2.2, 2.5, 'led_blue'],
      [7.5, 1.4, 2.2, 8.5, 2.2, 2.5, 'led_red'],
      [1.8, 1.6, 6, 2.0, 2.6, 9, 'slot']
    ]
  },
  // 7. manual_terminal — standing big screen + console/keyboard deck + LEDs
  {
    id: 'manual_terminal',
    slots: ['body', 'body2', 'trim', 'accent', 'screen', 'screen2', 'led_green', 'led_amber', 'led_red', 'led_blue', 'detail'],
    els: [
      [2.5, 0, 3, 13.5, 1, 13, 'trim'],
      [3, 1, 3.5, 13, 9, 12.5, 'body'],
      [2.9, 5.0, 3.3, 13.1, 5.7, 12.7, 'accent'],
      [3, 9, 4, 13, 9.6, 12, 'body2'],
      [4, 9.2, 4.5, 12, 9.4, 11.5, 'detail'],
      [4.5, 9.2, 5, 5.2, 9.35, 5.7, 'trim'],
      [6, 9.2, 5, 6.7, 9.35, 5.7, 'trim'],
      [7.5, 9.2, 5, 8.2, 9.35, 5.7, 'trim'],
      [3, 9.6, 3.5, 13, 14, 12.5, 'body2'],
      [4.2, 10.6, 3.1, 11.8, 13.2, 3.5, 'screen'],
      [4.2, 9.9, 3.1, 7.2, 10.4, 3.5, 'screen2'],
      [3, 14, 3.5, 13, 14.7, 12.5, 'body'],
      [2.7, 1, 3.4, 3.1, 9, 12.6, 'accent'],
      [12.9, 1, 3.4, 13.3, 9, 12.6, 'accent'],
      [4.2, 7.6, 3.1, 5.2, 8.2, 3.5, 'led_green'],
      [5.6, 7.6, 3.1, 6.6, 8.2, 3.5, 'led_amber'],
      [7.0, 7.6, 3.1, 8.0, 8.2, 3.5, 'led_red'],
      [8.4, 7.6, 3.1, 9.4, 8.2, 3.5, 'led_blue']
    ]
  },
  // 8. airport_checkin — cabinet + big screen + doc sensing pad + green arrow
  {
    id: 'airport_checkin',
    slots: ['body', 'body2', 'trim', 'accent', 'screen', 'led_green', 'led_amber', 'led_blue', 'led_red', 'paper', 'arrow'],
    els: [
      [2.5, 0, 3, 13.5, 1, 13, 'trim'],
      [3, 1, 3.5, 13, 8.6, 12.5, 'body'],
      [2.9, 5.0, 3.3, 13.1, 5.7, 12.7, 'accent'],
      [3, 8.6, 4.5, 13, 9.2, 12.5, 'body2'],
      [5, 9.2, 5.5, 11, 9.4, 11.5, 'paper'],
      [3, 8.6, 3.5, 13, 14.2, 12.5, 'body2'],
      [4.2, 9.6, 3.1, 11.8, 13.4, 3.5, 'screen'],
      [11.6, 11.4, 3.1, 12.8, 12.6, 3.5, 'arrow'],
      [3, 14.2, 3.5, 13, 14.9, 12.5, 'body'],
      [2.7, 1, 3.4, 3.1, 8.6, 12.6, 'accent'],
      [12.9, 1, 3.4, 13.3, 8.6, 12.6, 'accent'],
      [4.2, 7.4, 3.1, 5.2, 8.0, 3.5, 'led_green'],
      [5.6, 7.4, 3.1, 6.6, 8.0, 3.5, 'led_amber'],
      [7.0, 7.4, 3.1, 8.0, 8.0, 3.5, 'led_blue'],
      [8.4, 7.4, 3.1, 9.4, 8.0, 3.5, 'led_red']
    ]
  },
  // 9. station_verifier — cabinet + screen + slanted ID card slot (rotated) + status lights
  {
    id: 'station_verifier',
    slots: ['body', 'body2', 'trim', 'accent', 'screen', 'screen2', 'led_green', 'led_amber', 'led_red', 'led_blue', 'slot', 'paper'],
    els: [
      [2.5, 0, 3, 13.5, 1, 13, 'trim'],
      [3, 1, 3.5, 13, 14, 12.5, 'body'],
      [2.9, 5.4, 3.3, 13.1, 6.1, 12.7, 'accent'],
      [4.2, 10.4, 3.1, 11.8, 13.4, 3.5, 'screen'],
      [4.2, 9.7, 3.1, 7.2, 10.3, 3.5, 'screen2'],
      [4.2, 7.2, 3.1, 11.8, 7.6, 4.2, 'trim'],
      [4.2, 7.6, 3.1, 11.8, 9.2, 4.2, 'body2'],
      [5.2, 8.0, 2.6, 10.8, 8.4, 3.0, 'slot', { axis: 'x', angle: -22.5, origin: [8, 8.2, 2.8] }],
      [5.6, 8.05, 2.2, 10.4, 8.35, 2.6, 'paper', { axis: 'x', angle: -22.5, origin: [8, 8.2, 2.8] }],
      [3, 14, 3.5, 13, 14.7, 12.5, 'body'],
      [2.7, 1, 3.4, 3.1, 14, 12.6, 'accent'],
      [12.9, 1, 3.4, 13.3, 14, 12.6, 'accent'],
      [4.4, 6.6, 3.1, 5.4, 7.2, 3.5, 'led_green'],
      [5.8, 6.6, 3.1, 6.8, 7.2, 3.5, 'led_amber'],
      [7.2, 6.6, 3.1, 8.2, 7.2, 3.5, 'led_red'],
      [8.6, 6.6, 3.1, 9.6, 7.2, 3.5, 'led_blue']
    ]
  },
  // 10. blacklist_terminal — standing terminal + red warning beacon on top + screen
  {
    id: 'blacklist_terminal',
    slots: ['body', 'body2', 'trim', 'accent', 'screen', 'screen2', 'led_green', 'led_amber', 'led_red', 'slot', 'warning'],
    els: [
      [2.5, 0, 3, 13.5, 1, 13, 'trim'],
      [3, 1, 3.5, 13, 11, 12.5, 'body'],
      [2.9, 6.0, 3.3, 13.1, 6.7, 12.7, 'warning'],
      [4.2, 7.6, 3.1, 11.8, 10.8, 3.5, 'screen'],
      [4.2, 7.0, 3.1, 7.2, 7.5, 3.5, 'screen2'],
      [3, 11, 3.5, 13, 11.8, 12.5, 'accent'],
      [6.5, 11.8, 6.5, 9.5, 12.4, 9.5, 'body2'],
      [7, 12.4, 7, 9, 13.4, 9, 'warning'],
      [7.6, 13.4, 7.6, 8.4, 13.9, 8.4, 'led_red'],
      [2.7, 1, 3.4, 3.1, 11, 12.6, 'warning'],
      [12.9, 1, 3.4, 13.3, 11, 12.6, 'accent'],
      [12.7, 2, 6, 13.2, 4, 10, 'slot'],
      [4.4, 5.2, 3.1, 5.4, 5.8, 3.5, 'led_red'],
      [5.8, 5.2, 3.1, 6.8, 5.8, 3.5, 'led_amber'],
      [7.2, 5.2, 3.1, 8.2, 5.8, 3.5, 'led_green']
    ]
  },
  // 11. loss_terminal — terminal + print/stamp port + protruding paper + LEDs
  {
    id: 'loss_terminal',
    slots: ['body', 'body2', 'trim', 'accent', 'screen', 'led_green', 'led_amber', 'led_red', 'led_blue', 'slot', 'paper'],
    els: [
      [2.5, 0, 3, 13.5, 1, 13, 'trim'],
      [3, 1, 3.5, 13, 10, 12.5, 'body'],
      [2.9, 5.2, 3.3, 13.1, 5.9, 12.7, 'accent'],
      [4.2, 7.6, 3.1, 11.8, 9.6, 3.5, 'screen'],
      [4.2, 6.0, 3.1, 11.8, 7.2, 4.0, 'body2'],
      [5.5, 6.4, 2.9, 10.5, 6.9, 3.1, 'slot'],
      [5.5, 6.0, 2.3, 10.5, 6.5, 2.9, 'paper'],
      [5.5, 6.9, 3.1, 7.5, 7.2, 3.5, 'slot'],
      [3, 10, 3.5, 13, 10.8, 12.5, 'body2'],
      [3, 10.8, 3.5, 13, 11.2, 12.5, 'accent'],
      [2.7, 1, 3.4, 3.1, 10, 12.6, 'accent'],
      [12.9, 1, 3.4, 13.3, 10, 12.6, 'accent'],
      [4.4, 5.0, 3.1, 5.4, 5.6, 3.5, 'led_green'],
      [5.8, 5.0, 3.1, 6.8, 5.6, 3.5, 'led_amber'],
      [7.2, 5.0, 3.1, 8.2, 5.6, 3.5, 'led_red'],
      [8.6, 5.0, 3.1, 9.6, 5.6, 3.5, 'led_blue']
    ]
  },
  // 12. renewal_machine — cabinet + screen + intake/outlet ports + LEDs
  {
    id: 'renewal_machine',
    slots: ['body', 'body2', 'trim', 'accent', 'screen', 'screen2', 'led_green', 'led_amber', 'led_blue', 'led_red', 'slot', 'paper'],
    els: [
      [2.5, 0, 3, 13.5, 1, 13, 'trim'],
      [3, 1, 3.5, 13, 9, 12.5, 'body'],
      [2.9, 5.0, 3.3, 13.1, 5.7, 12.7, 'accent'],
      [3, 9, 4, 13, 9.7, 12, 'body2'],
      [4, 9.3, 4.5, 7, 9.6, 7.5, 'slot'],
      [4.2, 9.4, 3.6, 6.8, 9.6, 4.5, 'paper'],
      [9, 9.3, 4.5, 12, 9.6, 7.5, 'slot'],
      [9.2, 9.4, 3.2, 11.8, 9.6, 4.5, 'paper'],
      [3, 9.7, 3.5, 13, 14, 12.5, 'body2'],
      [4.2, 10.8, 3.1, 11.8, 13.2, 3.5, 'screen'],
      [4.2, 10.2, 3.1, 7.2, 10.7, 3.5, 'screen2'],
      [3, 14, 3.5, 13, 14.7, 12.5, 'accent'],
      [4.2, 7.6, 3.1, 5.2, 8.2, 3.5, 'led_green'],
      [5.6, 7.6, 3.1, 6.6, 8.2, 3.5, 'led_amber'],
      [7.0, 7.6, 3.1, 8.0, 8.2, 3.5, 'led_blue'],
      [8.4, 7.6, 3.1, 9.4, 8.2, 3.5, 'led_red']
    ]
  }
];

fs.mkdirSync(OUT, { recursive: true });
const report = [];
for (const d of DEVICES) {
  const textures = { particle: 'veriborder:block/' + d.id + '_body' };
  for (const s of d.slots) textures[s] = 'veriborder:block/' + d.id + '_' + s;
  const elements = d.els.map(a => box(E(a).f, E(a).t, E(a).r));
  const model = { textures, elements, display: DISPLAY };
  const out = path.join(OUT, d.id + '.json');
  fs.writeFileSync(out, JSON.stringify(model, null, 2) + '\n');
  report.push(`${d.id.padEnd(20)} els=${String(elements.length).padStart(2)} textures=${d.slots.length + 1}`);
}
console.log(report.join('\n'));
console.log(`wrote ${DEVICES.length} models to ${OUT}`);

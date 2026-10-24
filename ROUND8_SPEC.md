# ROUND8_SPEC — 26.2 model rebuild (Blender/Blockbench toolchain)

Frozen spec. Every builder reads this; nobody invents geometry conventions.

## 0. Pipeline (proven in this repo)

```
Blockbench .bbmodel  (MC coords, 16 px = 1 block)
      │  node tools/bbmodel_to_mc.js <in.bbmodel> <out.json> --display
      ▼
models/block/<id>.json   +   textures/block/<id>_<slot>.png
```

- Texture convention: bbmodel `<id>.bbmodel`, texture entry named `body` →
  `veriborder:block/<id>_body` → PNG at
  `src/main/resources/assets/veriborder/textures/block/<id>_body.png`.
- English signage: `powershell -File tools/make_label.ps1 -Text "PASSPORT" -Out <png> -W 64 -H 32`.
  Label panels are separate texture slots (e.g. `label_passport`, `label_face`) mapped to a
  dedicated flat front face. **MC UV space is always 0..16 regardless of texture resolution**,
  so a full-face label uses UV `[0,0,16,16]` — never `[0,0,64,32]` (that samples outside the
  sprite and garbles in game). **In-game ground truth (26.2, real client): an un-flipped label
  on the north face reads correctly, so do NOT pre-flip textures.** Use plain labels, north
  face only (other faces get trim/body), and verify with the fixed preview.
- Do NOT hand-write element arrays in a generator script. Geometry must come from the
  `.bbmodel` (Blockbench is the modeler). The converter is a format translator only.

## 1. Coordinate / scale rules

- MC block model space: `0..16` px, Y up, 16 px = 1 block.
- Footprint must stay inside `0..16` on X/Z unless explicitly stated (gate: exactly 16×16).
- Device height 10..16 px; gate 24 px (1.5 blocks).
- Element count: devices 14..40, gate 40..90.
- Every model: >= 4 distinct light/emissive colours, one screen, one status LED.

## 2. Gate (egate / exit_gate)

Hard requirements from the user:

1. **Footprint exactly 16×16 px** so gates tile seamlessly when placed side by side in any
   direction (adjacent cabinets merge into one shared pillar).
2. **Height 24 px (1.5 blocks)** and **collision 1.5 blocks solid** — no second invisible
   air wall, and the player cannot jump over it. Collision shape must match the visual shell.
3. **Passport recognition area**: mounted on the **right pillar front** (x 13.1..15.9,
   y 8..14, z 0.2..1.0), label `PASS`, plus a slot below it. Never spans the passage.
4. **Face recognition screen**: mounted on the **left pillar front** (x 0.1..2.9,
   y 14..22, z 0.2..1.0), label `FACE`, plus a camera dot.
5. **Wide function label**: on the top-cap front band (x 0..16, y 22.2..23.8, z 0.2..0.5):
   `ENTRY` for egate, `EXIT` for exit_gate. This is the only place with room for readable
   English on a 16 px footprint.
6. The lane (x 3..13, z 0.3..15.7) must stay completely empty in the static model; the
   door is drawn by EGateRenderer at z 7..9, y 2..14.
5. **Real sliding door**: two leaves that slide into the pillars (BER animation), visible when
   closed. Door leaves 5 px wide × 12 px tall, 2 px thick, dark metal frame + frosted glass.
6. **Remove extra particle effects** (no happy-villager / end-rod bursts on pass/fail).
7. **Gate sounds**: a short servo/motor sound on open, a soft latch on close, a buzzer on deny.
8. Both `egate` and `exit_gate` share the shell; exit uses red accent + `EXIT` label.

## 3. Devices (20 non-gate)

Each device must be individually recognisable and non-homogeneous. Front face carries an
English label of its function. Required labels:

| id | label |
|---|---|
| id_issuer | ID CARD |
| passport_issuer | PASSPORT |
| permit_issuer | HK MACAU |
| visa_issuer | VISA |
| endorsement_machine | ENDORSE |
| self_visa | SELF VISA |
| self_endorsement | SELF ENDORSE |
| hotel_reader | HOTEL |
| desk_scanner | SCAN |
| manual_terminal | MANUAL |
| airport_checkin | BOARDING |
| station_verifier | VERIFY |
| blacklist_terminal | BLACKLIST |
| loss_terminal | LOSS |
| renewal_machine | RENEW |
| entry_stamp | ENTRY STAMP |
| exit_stamp | EXIT STAMP |
| stamp_history | HISTORY |
| print_preview | PRINT |

Form guidance (do not make them all the same box):
- issuers: counter/kiosk with a document slot and a card tray.
- self-service: tall slim kiosk with a large screen and a passport insertion slot.
- readers/scanners: low desktop units (6..10 px tall).
- terminals: standing console with screen + input shelf.
- stamp machines: press head above a document platform.
- print/history: screen + paper output tray with a sheet sticking out.

## 4. Acceptance (every builder)

1. `blockbench_validate` on the `.bbmodel` — zero errors.
2. Converter output: every `textures` entry resolves to an existing PNG (32 or 64 px).
3. `node tools/preview_models.js` → the device's own checks pass; no new FAILURES.
4. `models/block/<id>.json` has no element outside `0..16` (gate: Y 0..24 allowed).
5. Report: element count, texture list, preview PNG path, label text.

# ROUND9_SPEC — realistic minimalist immigration equipment (26.2 redo)

Supersedes ROUND8 style guidance. The previous "sci-fi with cyan neon" look is
**rejected**. Every model is rebuilt from scratch against this spec.

## 0. Hard style rules (from the user)

1. Real commercial immigration/self-service machines, **not** plain black cubes.
   Rounded edges where appropriate, believable panel thickness.
2. Palette: **matte off-white shell** (primary), **brushed silver** (secondary),
   **clear acrylic/glass**. No pure-black main casing.
3. Visible functional details only: embedded flat touchscreen, card/passport slot,
   small indicator light, scan window. **No neon strips, no fantasy curves, no
   sci-fi glow.**
4. Low-poly for Minecraft: reasonable element count, clean industrial textures
   (tileable, subtle noise/brushed, baked edge shading).
5. No cartoon/chibi/stylized-fantasy look.

## 1. Pipeline (must stay Blockbench-based)

```
assets/models/<id>.bbmodel   (Blockbench project, MC coords 0..16, Y up)
      │ node tools/bbmodel_to_mc.js <in> <out.json> --display
      ▼
models/block/<id>.json  +  textures/block/<id>_<slot>.png
```

- The `.bbmodel` is the source of truth and must open in Blockbench
  (`blockbench_validate` clean; `blockbench_open` works).
- Shared material kit (already generated): `r9_shell`, `r9_shell2`,
  `r9_silver_v2`, `r9_silver_flat`, `r9_silver_dark_v2`, `r9_glass`,
  `r9_dark`, `r9_screen`, `r9_led_green`, `r9_led_red`.
  Reference them by full path `veriborder:block/r9_shell` in the bbmodel texture
  `path`; the converter passes resource locations through verbatim.
- Labels: `tools/make_label.ps1` (plain, no `-FlipX`; in-game ground truth shows
  unflipped text reads correctly on the north face). Label texture only on the
  north (or outer side) face; other faces use shell/silver.
- MC UV space is 0..16 (full face = `[0,0,16,16]`).

## 2. Height / footprint

- Devices may be **1 or 2 blocks tall** like real machines: counter-top units
  6..10 px, standing consoles 14..24 px, full kiosks **up to 32 px (2 blocks)**.
- Footprint: 10..16 px square, centered in the block.
- Gate: 16×16 footprint, 24 px tall (1.5 blocks) for anti-jump, 10 px lane.
- Tall models (>16 px) must keep the block collision/shape sane: `EGateBlock`
  already handles the gate; for devices the default 1-block shape is acceptable
  unless a 2-block-tall cabinet would let players walk through it — prefer
  keeping the solid part within the lower block.

## 3. Per-device form (no homogenisation)

- **Issuers** (id/passport/permit/visa/endorse): counter or 2-block kiosk, with a
  document/passport slot, card tray, embedded screen. Passport issuer = taller
  kiosk with a vertical insertion slot and a reading platen.
- **Self-service** (self_visa/self_endorsement): 2-block-tall slim kiosk, large
  embedded screen, passport slot, small status LED.
- **Readers/scanners** (hotel_reader/desk_scanner): low counter-top units
  (4..10 px), card slot + small screen / glass scan platen.
- **Terminals** (manual/airport/station/blacklist/loss/renewal): standing console
  with embedded screen, input shelf; blacklist adds a small red beacon; loss/print
  add a paper output slot.
- **Stamps** (entry/exit): press head above a document platform, silver guides,
  orange (entry) / blue (exit) accent **only as a small painted panel**, not neon.
- **History/print**: screen + document tray / paper output with a sheet.

## 4. Materials / detail rules

- Shell: `r9_shell` / `r9_shell2`; silver: `r9_silver_v2` / `r9_silver_flat`;
  glass: `r9_glass`; recess: `r9_dark`; screen: `r9_screen`.
- Screens must be **recessed**: bezel in front, a dark shadow ring, then the screen
  face set back 0.1..0.2 px.
- Add panel seams: 0.1..0.2 px dark strips at 1–2 horizontal split lines.
- Add edge shading by using `r9_shell2` on side faces and `r9_silver_flat` on
  bevel strips; avoid a single flat colour over the whole body.
- LED: one small 0.4..0.6 px indicator per machine (green/red), not a strip.

## 5. Acceptance

1. `blockbench_validate` on each `.bbmodel` — 0 errors / 0 warnings.
2. Converter output: every texture slot resolves to a PNG; elements within bounds.
3. `node tools/preview_models.js` → 21 models, FAILURES none (gate: 16 wide,
   24 tall, 10 px passage).
4. Visual: matte white/silver/glass reads correctly; no neon; English label
   readable on the front or outer side; devices are visually distinct.

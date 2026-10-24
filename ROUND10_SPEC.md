# ROUND10_SPEC — 26.2 feature pass (7 items)

Frozen contract for the 26.2 feature pass. Read this before touching code.

## 1. Document names are locked by default
- Issuer screens get a `lockName` toggle, default **true**.
- Locked: the name field is read-only and the document always uses the preset
  sample name for its style. Unlocked keeps today's free-text behaviour.
- `DocumentFactory.fromForm` must ignore a custom `name` when the form says
  `lockName=true`.

## 2. Document numbers are locked by default
- Issuer screens get a `lockNumber` toggle, default **true**.
- Locked: the number is always system-generated (`DocumentFactory.generateNumber`).
- CN_ID layout (18 digits): `RRRRRR` region (6, configurable, default from
  `style.numberPrefix()`), `YYYYMMDD` birth, `SS` sequence (2, configurable,
  default random), `G` gender digit (odd = male, even = female, auto),
  `C` ISO 7064 MOD 11-2 check digit (auto).
  The existing `generateNumber` already does prefix+birth+3-digit sequence+check;
  extend it to accept `region` and `sequence2` overrides and to force the gender
  digit parity.
- Passport/HK/permit keep their current generators.

## 3. Passport MRZ issuing-country code
- Issuer screen (passport styles) gets a 3-letter `mrzCountry` field, default
  `style.mrzCountry()`.
- `MrzUtil.generate` gains an overload taking the override; `DocumentData.sign`
  passes `tag.getString("mrzCountry")` when non-blank.
- Stored as `mrzCountry` on the document tag (signed).

## 4. Border Terminal item (admin)
- New item `veriborder:border_terminal`, creative tab, no recipe required.
- Right-click opens a screen listing **all online players** (name, uuid, border
  session state) and, for the selected player, every document they carry:
  style, number, status, entry/exit session, visas/endorsements/permanent codes.
- Actions (server-authoritative, permission level >= 2):
  - change a document's `DocumentStatus`
  - clear / create the player's border session (force entry or force exit)
  - edit the selected document's core fields (name, number, status, permanent
    codes) — reuse `DocumentData` setters and re-sign.
- Packet: `BorderTerminalActionPacket` (action enum + player uuid + doc slot +
  payload). Screen: `BorderTerminalScreen`. Menu not required (custom screen with
  a packet per action, like `OfficerScreen`).
- Registration: `ModItems`, `ClientScreens`, `ModNetwork`, lang keys.

## 5. Permanent code slot (permit + passport)
- Backed by the existing `DocumentData.visaFreeCodes` list (unlimited, unique,
  signed). No new storage needed.
- Shown only in: issuer screen, universal modifier screen, border terminal.
- Purpose: returning to the issuing country. Gates that check visa,
  endorsement, or both must accept a document when its permanent-code list is
  non-empty (kind `visa_free`), in addition to their normal match.
- Update `BorderService.resolveEntry` / the gate paths so `gateMode=endorsement`
  and `gateMode=both` also fall back to `visaFreeCodes`.

## 6. New documents start in the entry state
- Issuer screen gets `issueEntryState` (default **true**).
- After a successful issue, when `issueEntryState` is true and the player has no
  active session, create one via `BorderService.beginEntry` with kind ALL so the
  player can immediately use an exit gate.
- Never overwrite an existing session.

## 7. Dual-clearance gate (一地两检)
- New block `veriborder:dual_gate` (DeviceType `DUAL_GATE`, category GATE).
- Model: same geometry as `egate`, but the accent/trim area (the part circled in
  the reference) is **dark green** (`#1F5E3A`); everything else identical.
  Ship `models/block/dual_gate.json`, blockstate, item model, textures.
- Dedicated brush `veriborder:dual_gate_brush` + `DualGateBrushScreen`, config:
  - exit authority / exit officer / exit port
  - entry authority / entry officer / entry port
  - gate mode for each stage (exit stage checks session; entry stage checks
    visa / endorsement / both / permanent code)
- Use logic (server):
  1. **Exit stage**: require an active `BorderSession`; `finishExit`; add an OUT
     stamp with the exit authority/officer/port.
  2. **Entry stage**: `resolveEntry` with the entry-stage mode; on success
     `beginEntry`; add an IN stamp with the entry authority/officer/port.
  3. Both stages must pass; a failure at either stage denies and leaves the
     session untouched (exit stage failure: no session; entry stage failure:
     session preserved as-is).
- Sounds/particles: same as the other gates (no particle bursts).
- Lang keys for the block, item, brush screen.

## File ownership (avoid collisions)
- Doc/issuer agent: `doc/DocumentData.java`, `doc/DocumentFactory.java`,
  `doc/MrzUtil.java`, `client/screen/IssuerScreen.java`,
  `client/screen/ModifierScreen.java`, `item/DocumentModifierItem.java`,
  `doc/*Test.java`.
- Gate agent: `block/DeviceType.java`, `block/EGateBlock.java` (or new
  `DualGateBlock`), `registry/ModBlocks.java`, `item/DualGateBrushItem.java`,
  `client/screen/DualGateBrushScreen.java`, `client/EGateRenderer.java`,
  `block/DeviceService.java`, `border/BorderService.java`,
  models/blockstates/textures for `dual_gate`.
- Terminal agent (runs after the two above): `item/BorderTerminalItem.java`,
  `client/screen/BorderTerminalScreen.java`, `network/BorderTerminalPacket.java`,
  `registry/ModItems.java`, `client/screen/ClientScreens.java`,
  `network/ModNetwork.java`.
- Shared: `lang/en_us.json`, `lang/zh_cn.json` — append only your own keys.

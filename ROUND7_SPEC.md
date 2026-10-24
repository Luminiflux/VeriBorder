# VeriBorder 26.1.1 — gate redesign, two-stage border, gate config brush

Base: 26.1. Jar only this round; do not upload GitHub.

## 1. Gate model (visual)

Reference image: `sha256:facabc63ca3be6166a58c83be584fce9998a02145d9eeecc6c14ee8e57886379`
(available to vision tools). Key facts:
- split dual-pillar gate, no overhead gantry;
- matte white body, dark grey/black curved top, vertical tablet screen, cyan LED strip,
  green arrow LED, transparent sliding glass gate;
- pillar height ~1.3 blocks, narrow (~4 px in our 16 px block scale), deep;
- passage width 12 px in our model; glass barrier visible from y2..y13.

Model changes:
- Replace the current low 16 px shell with **two ~22 px tall pillars** (x -2..2 and
  14..18, z 1..15), white body, black top cap/curved rear overhang (stacked/rotated boxes),
  vertical screen/reader on the inner/front face, cyan LED strip, green arrow.
- Keep the 12 px clear central passage and the 20 px overall width.
- Add **solid visible glass door leaves** with metal frames; the BER wings must be clearly
  visible closed and open; do not hide them inside the cabinet.
- Identity-stage variant should look like the same gate with a camera/face-scan module
  (extra element or darker screen); no separate model file if possible.
- Keep all existing gate config/collision behavior; collision remains two blocks high.
- Generate through `tools/lib_device_models.ps1` + `generate_assets.ps1`; add textures if
  needed. Run preview; 21 models, FAILURES none.
- No other device remodel this pass (can follow after gate acceptance).

## 2. Two-stage border verification

Gate stage config:
```text
gateStage       "document" | "identity"   default document
identityRequired boolean                 default true for new gates; legacy NBT missing -> false
```
`DeviceBlockEntity` persists/syncs/configTag both.

### Document stage
- Validate document + visa/endorsement code as 26.1.
- If `identityRequired`:
  - do **not** create a BorderSession; create a short-lived **PendingEntry** (player UUID,
    document number, recordId, code, kind, gate pos, created time, expiry 200 ticks/10s)
    in BorderTravelData; log `ALLOW_DOCUMENT`; open this gate.
  - if a pending already exists -> deny `PENDING_EXISTS` ("complete identity verification").
  - no stamp and no consumption yet.
- If `identityRequired` false: existing one-stage behavior (create session, auto IN stamp).

### Identity stage (`gateStage=identity`)
- Require a pending entry for this player; none/expired -> deny `NO_PENDING_ENTRY`
  ("complete document verification first").
- Document number must match; otherwise deny.
- Identity check:
  - `DocumentData.owner` UUID (new NBT string, signed).
  - if owner blank: bind to the current player UUID (first-use enrollment), log
    `IDENTITY_ENROLL`; allow.
  - if owner != player UUID: deny `IDENTITY_MISMATCH` ("contact officer").
- On pass: create the real BorderSession from pending, clear pending, auto IN stamp,
  log `ALLOW_IN`, open identity gate.
- No visa/endorsement consumption at identity stage.
- `identityRequired` ignored on identity stage.

### Pending storage
- `BorderTravelData` gets pending entries keyed by player UUID, NBT-persisted, expiry.
- `PendingEntry` fields: player, document, recordId, code, kind, gatePos, dimension,
  createdAt, expiresAt.
- Pending is cleared on success, expiry, officer clear, or document-stage restart.

### Logs
- Add results: `ALLOW_DOCUMENT`, `DENY_DOCUMENT`, `ALLOW_IDENTITY`, `DENY_IDENTITY`,
  `IDENTITY_ENROLL` (or reuse direction IN with these results; keep officer UI readable).
- Existing IN/OUT logs remain for final entry/exit.
- Officer console/lists must tolerate the new results; UI-A/UI-B update.

### Owner
- `DocumentData.owner` String UUID, NBT key `owner`, signed.
- `DocumentData.ownerName` optional? Store UUID only; display short.
- New docs start blank; identity gate binds first user. Admin modifier can set/clear owner
  (add a text field; owner UUID/name? use UUID string).
- Existing docs with blank owner enroll on first identity pass.

## 3. Gate config brush

New item `gate_config_brush`:
- Creative-only; no survival recipe; OP level 2 for save/apply.
- Item NBT `VeriBorderGateConfig` stores:
  `gateMode`, `gateCodes` ListTag, `autoStamp`, `overstayDays`, `gateStage`,
  `identityRequired` (and optional `port`/`country`/`officer`? not required).
- **Sneak + right-click air** opens a Gate Config Brush screen (same gate controls as
  DeviceConfigScreen: mode, codes batch, autoStamp, overstay, stage, identityRequired).
- Save writes to the held brush item on the server (new packet).
- **Right-click a gate** (not sneaking) with the brush applies stored config to the gate BE;
  requires OP 2; message success/failure; does not open the normal config.
- Normal `device_brush` behavior unchanged (open full per-block config).
- Item texture/item model; creative tab.

## 4. UI
- `DeviceConfigScreen` gate section: add `gateStage` cycle (Document/Identity) and
  `identityRequired` toggle; save keys.
- `EGateScreen`: show stage/identity requirement.
- New `GateConfigBrushScreen` (520x420 or similar) with gate mode, batch codes,
  autoStamp, overstay, stage, identityRequired; save to brush.
- Officer screen: tolerate `ALLOW_DOCUMENT` etc. (UI-B owns; add lang keys).
- Lang en/zh parity.

## 5. Permissions / release
- Gate brush creative-only; OP 2 for save/apply.
- Jar `veriborder-26.1.1.jar`; do not push GitHub until user verifies.
- No other model rework this pass.

## 6. Acceptance
- Unit: PendingEntry NBT/expiry, owner bind/mismatch, stage defaults; existing 69 tests.
- Client: document gate -> pending + open; identity gate -> session + stamp; re-enter
  document gate while pending denied; identity without pending denied; wrong owner denied;
  blank owner enrolls; brush save -> apply to gate -> config copied; old gate default
  one-stage.
- Preview 21 models, no failures; no debug classes.

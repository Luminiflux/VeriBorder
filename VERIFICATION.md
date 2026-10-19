# VeriBorder — Verification Report

Date: 2026-10-17  
Environment: Minecraft 1.20.1 / Forge 47.2.0 / Java 17.0.20.1 (Azul)  
Project: `C:\Users\GhostBubbles\Documents\deepseek-harness\default-workspace\VeriBorder`

## 26.1 formal release verification

```text
DocumentCoreTest   46
BorderDataTest     13
VisaLockTest       10
total              69 tests, 0 failures
```

Real Forge client:

```text
BorderCoreSmoke: 22/22 PASS
  entry session created; usedEntries unchanged; IN stamp + ALLOW_IN log
  re-entry denied ALREADY_ENTERED; single session; no consume
  exit clears session; consumes exactly one; IN+OUT stamps; ALLOW_OUT log
  exit without session denied; wrong document keeps session and is denied
  permit entry/exit consumes and stamps twice; session cleared
  overstay exit allowed + OVERSTAY log + session cleared
  officer clear + OFFICER_CLEAR log

Ui261Smoke: 3/3 PASS
  OfficerScreen, gate config (autoStamp/overstay/batch), passport attached-visa panel
  all render magenta=0
```

Additional checks:

- `device_brush` and `document_modifier` recipe JSONs are absent; both items remain registered
  and available in the creative tab.
- OP level 2 checks on config, modifier and officer paths.
- `preview_models.js`: 21 models, CHECKLIST all PASS, FAILURES none.
- No debug classes in the jar.

---

## 1.2.1 passport-attached visa / universal locks verification

```text
DocumentCoreTest: 46 tests, 0 failures
FeatureSmoke121:  30/30 PASS
```

Real Forge client (`run/smoke-121/result.txt`):

- Passport Issuer locks: style HK passport, place `LOCK PLACE`, issue date
  `2020-05-06`, validity 7 years (`2027-05-06`), authority `LOCK AUTH`; malicious form
  values were overridden.
- Passport attached visa: exactly one visa in the new passport NBT; country
  `LOCK PLACE`, type `business`, code `00001234`, entries 9, issue/expiry inherited
  from the passport.
- Self-service visa: type `student`, country `MACHINE LAND`, issue `2021-01-01`,
  expiry `2024-01-01`, code `00002222`, ignoring player fields.
- Self-service endorsement: type `other`, issue `2022-02-02`, expiry `2024-02-02`,
  code `00003333`.
- Passport GUI: style button disabled, visa include/country/code/entries widgets present;
  passport/config/self-visa/world renders magenta=0.
- Standalone Visa Issuing Terminal code path remains unchanged.
- 1.2.0-hotfix2 E-Gate 2-block collision remains present.

---

## 1.2.0-hotfix2 gate jump-over collision

Problem: the E-Gate collision was only one block high (`Shapes.block()`), so a
player could jump onto the gate top and cross the border.

Fix in `EGateBlock`:

- Closed gate: full **2-block-high** collision wall.
- Open gate: side cabinet strips are also 2 blocks high; the 12 px centre passage
  remains clear from floor to 2 blocks.
- Outline/collision shapes share the same height.

Real Forge client, four-facing test (`GateHeightSmoke`):

```text
N/E/S/W closed: height >= 2, centre blocked
N/E/S/W open:   height >= 2, centre passable, strips rotated correctly
20/20 PASS, failures=0
```

Unit tests: 43 tests, 0 failures.

---

## 1.2.0-hotfix creative-tab crash fix

Crash:

```text
java.lang.NullPointerException: Registry Object not present: veriborder:self_visa
  at com.veriborder.registry.ModCreativeTabs.lambda$static$1(ModCreativeTabs.java:33)
```

Cause: the creative tab was filled from a `displayItems` lambda that called
`DeviceItems.values().get()` on every device, including the newly added `self_visa`,
before its registry object was guaranteed present on Connector/heavily-modded clients.

Fix:

- Removed the `displayItems` lambda from `CreativeModeTab.builder()`.
- Added `ModCreativeTabs.buildContents(BuildCreativeModeTabContentsEvent)`, registered on
  the mod event bus; it runs after registries are ready.
- Every device registry object is guarded with `isPresent()` before `event.accept(...)`.

Verification:

```text
DocumentCoreTest: 43 tests, 0 failures
registry: block=true item=true roBlock=true roItem=true mapSize=21
creative inventory opened: magenta=0
```

The client smoke opened `CreativeModeInventoryScreen` successfully and captured
`registry-creative.png`; no `Registry Object not present` exception.

---

## 1.2.0 self-service visa / fixed-code verification

```text
gradlew.bat clean build -> BUILD SUCCESSFUL
DocumentCoreTest          -> 43 tests, 0 failures, 0 errors
node tools/preview_models.js -> 21 models, CHECKLIST all PASS, FAILURES: none
```

Real Forge client (`run/smoke-120/result.txt`, 22 checks, failures=0):

- `SELF_VISA` placed and rendered; world + config + self-service screenshots have magenta=0.
- Self-service visa issue (player sent `country=HACKLAND`, `authority=HACK AUTH`, `code=99999999`,
  `entries=99`, dates 2000) produced: country `Testland` (machine), authority `VISA MACH`,
  fixed code `00001111`, default entries `1`, current dates.
- Self-service endorsement issue produced authority `END MACH` and fixed code `00002222`,
  ignoring player authority/code/entries/dates.
- Permit issuer initial endorsement produced code `00003333` and authority `PERMIT AUTH`
  from its lock configuration, ignoring the malicious form.
- Self-service visa player UI has **no** country/entries/issue/expiry/authority/code/template
  widgets; only the type cycle + Issue + Close.
- 21 machine models, 21 blockstates, 254 block textures, 26 recipes, 21 loot tables.
- Shared fixed code `00001111` is accepted by gate modes `visa` and `both` (unit test).

---

## 1.1.5 credential-code / lock / self-service verification

```text
gradlew.bat clean build -> BUILD SUCCESSFUL
DocumentCoreTest          -> 41 tests, 0 failures, 0 errors
```

Real Forge client (`run/smoke-115/result.txt`, 16 checks, failures=0):

- Locked issuer (`ID_ISSUER` with lockDocumentName/lockedName and lockAuthority/lockedAuthority):
  issued ID got `displayName=LOCKED NAME`, `authority=LOCKED AUTH` even when the test form
  supplied `HACK NAME` / `HACK AUTH`; integrity valid.
- Locked UI fields show locked values and are non-editable; personal fields (name, Latin name,
  birth date, address, etc.) remain editable (verified by code inspection of `IssuerScreen`;
  only `displayName` and `authority` receive `setEditable(false)`).
- Self-service endorsement (`SELF_ENDORSEMENT`): player form sent malicious
  `authority=HACK AUTH`, `code=99999999`, `entries=99`, dates 2000; issued record used
  `authority=MACHINE AUTH`, a fresh 8-digit code (not 99999999), the type default entries,
  and current dates. The player screen contains **no authority/code/template/renew/revoke/delete
  widgets** — only the preset type cycle + Issue + Close.
- Gate brush config save: `gateMode=both`, codes `00001234`, `123`, duplicate `00001234`,
  `87654321` -> stored exactly `[00001234, 87654321]` (leading zero kept, invalid dropped,
  duplicate removed); unlimited list storage.
- Four GUI renders (DeviceConfigScreen, IssuerScreen locked, self-service EndorsementScreen,
  EGateScreen): no crash, magenta=0.
- Four gate modes, exact code matching, visa-free matching, wrong code/kind/no-codes,
  legacy mode normalization and duplicate-code repair are covered by the 41 unit tests.

Known test scope note: this pass did not re-run the gate open/close animation (unchanged since
1.1.3, whose four-facing collision test was 8/8). The self-service code rule is intentionally
auto-only; admins can set its authority/templates via brush config, but there is no player-facing
code-pattern field.

---

## 1.1.3 gate collision/flicker hotfix verification

Real Forge client, four-facing collision test (server-side block states + `Level.noCollision`
with a 0.5 × 1.8 × 0.5 centre box):

| Facing | Closed | Open |
|---|---|---|
| North | blocked | 12 px centre passable, strips on X |
| East | blocked | 12 px centre passable, strips on Z |
| South | blocked | 12 px centre passable, strips on X |
| West | blocked | 12 px centre passable, strips on Z |

Result: **8/8 PASS, failures=0**.

Flicker fixes:

- Gate blocks now use `RenderType.cutout()`; the animated blades remain
  `entityTranslucent` in `EGateRenderer`.
- All relief panels/screens/LEDs/reader decks were offset by 0.02–0.04 px and
  fully de-overlapped. `egate` and `exit_gate` now have **0 AABB overlaps**
  (previously 51 each), removing z-fighting.
- BER status lenses moved to the cabinet front face (z −7.08, protruding
  0.08 px) so they cannot share a plane with the static shell.
- `preview_models.js` still reports 20 models, CHECKLIST all PASS, `FAILURES: none`.

Unit tests: 23 tests, 0 failures.

---

## 1.1.2 gate rebuild verification

```text
gradlew.bat clean build -> BUILD SUCCESSFUL
DocumentCoreTest         -> 23 tests, 0 failures, 0 errors
node tools/preview_models.js -> 20 models, CHECKLIST all PASS, FAILURES: none
```

Real Forge client (`smoke-gate`, quick-play world):

- `gallery-20-devices`: 20 device item models rendered, **magenta=0**, bright=42,426.
- `world-both-gates`: entry `egate` and `exit_gate` placed and rendered in a flat world,
  **magenta=0**, bright=345,254; no missing model/texture in the client log.

Model metrics:

```text
gates: egate 20.2x16.5x14.1 (54 els, 6 rotated)
       exit_gate 20.2x17.5x14.1 (55 els, 6 rotated)
passage: 12 px clear
CHECKLIST: all PASS, FAILURES: none
```

The gate is an original single-lane ticket-barrier design. Its geometry and textures
were authored for VeriBorder by the project asset scripts; no third-party model or
texture assets are redistributed.

Remaining limitation: this is a single-lane 20 px visual with a 1-block controller
collision, not a true 2-block multiblock. Human visual review of the new silhouette is
still recommended.

---

## 1.1.1 exit-gate hotfix verification

```text
gradlew.bat compileJava test -> BUILD SUCCESSFUL
DocumentCoreTest             -> 23 tests, 0 failures, 0 errors
```

Behavior verified by unit tests:

- `exitGateOnlyChecksDocumentValidity`:
  - passport with all visas removed → valid at `EXIT_GATE`;
  - permit with all endorsements removed → valid at `EXIT_GATE`;
  - identity card → valid at `EXIT_GATE`;
  - lost passport → `LOST` (still rejected);
  - `validateGate(EXIT_GATE, "passport", "work", ...)` still passes (binding ignored);
  - `validateGate(EXIT_GATE, "permit", ..., "three_month_single")` still passes (binding ignored).
- `entryGateStillRequiresVisaOrEndorsement`:
  - passport without visas at `EGATE` → `NO_VISA`;
  - permit without endorsements at `EGATE` → `NO_ENDORSEMENT`.

Code changes:

- `DocumentValidator`: gate binding block only runs for `device.isGate() && device != EXIT_GATE`.
- `DeviceService.useGate`: `consumeGateEntry` is skipped for `EXIT_GATE`.
- `DeviceConfigScreen`: exit gate hides gate-mode/visa/endorsement controls, shows an “exit checks document validity only” hint, and clears stale bindings on save.
- `EGateScreen`: exit gate shows “document validity only” instead of a binding/required type.
- Lang: added `screen.veriborder.config.exit_gate_hint` and `screen.veriborder.gate.binding.exit` (en/zh).

No change to entry-gate binding, brush, self-service kiosk or asset generation.

---

## 1.1.0 round-2 verification (gates, brush, self-service)

```text
gradlew.bat clean build   -> BUILD SUCCESSFUL
DocumentCoreTest          -> 22 tests, 0 failures, 0 errors
node tools/preview_models.js -> 20 models, CHECKLIST all PASS, FAILURES: none
```

Real Forge client, quick-play flat world, dedicated harness using direct server-side
block placement/inventory/teleport (no chat commands). Final result: **16/16 checks PASS**.

| Check | Result | Evidence |
|---|---|---|
| A1 E-Gate config GUI (passport + Work visa) | PASS | DeviceConfigScreen, 4 labelled fields, gate/visa buttons visible, endorsement hidden, panel 70.2%, magenta 0 |
| A2 Exit-gate config GUI (permit + Three-month Single) | PASS | DeviceConfigScreen, gate/endorsement buttons visible, visa hidden, panel 69.8%, magenta 0 |
| A3 Self-service endorsement screen | PASS | EndorsementScreen, title “Self-service Endorsement Machine”, 5 fields / 10 buttons, panel 73.8% |
| A4 20-device model gallery | PASS | 20/20 cells populated, 20 models, 408 distinct bright colours, magenta 0 |
| B1 World devices placed | PASS | reader, issuer, self-service kiosk, entry gate, exit gate at spec coords, facing north |
| B2 Sample passport | PASS | CN_PASSPORT E12345678, 1 visa, integrity true |
| B3 Brush opens config | PASS | right-click reader with brush → DeviceConfigScreen (panel 77.6%, magenta 0) |
| B4 No brush opens reader | PASS | right-click reader without brush → ReaderScreen |
| B5 Bound passport + Work visa rejects tourist visa | PASS | open=false, progress=0.00, state=IDLE after 30 ticks; rejected before animation |
| B6 Bound passport + any visa opens | PASS | open=true, progress=1.00, state=GRANTED after 80 ticks |
| B7 Exit gate opens | PASS | open=true, progress=1.00, state=GRANTED after 80 ticks |
| B8 Self-service kiosk with permit | PASS | EndorsementScreen titled “Self-service Endorsement Machine” |
| B0 World render sanity | PASS | magenta 0, 227,637 bright pixels |

Model validation (`tools/preview/metrics.txt`):

```text
models: 20   blockstates: 20   variants: 88   textures on disk: 242
min elements: 19   unique geometry hashes: 20
gates: egate 32.0x24.0x20.4 (106 els)   exit_gate 32.0x24.0x20.4 (115 els)
passage: 8 px clear
CHECKLIST: all PASS, FAILURES: none
```

Remaining limitations for this round:

- The gate visual is 32×24 px and may extend into neighbouring blocks, but collision is
  still the single controller block (1-block passage). A true 2-block-wide multiblock is
  future work.
- Gate visa/endorsement records are consumed when verification starts, matching the
  previous timing.
- Human visual review of the larger gate, glass/wing motion and EXIT signage is still
  recommended (acceptance is programmatic framebuffer/pixel + model metrics).

---

# VeriBorder hotfix-2 — previous verification report

Date: 2026-10-17  
Environment: Minecraft 1.20.1 / Forge 47.2.0 / Java 17.0.20.1 (Azul)  
Project: `C:\Users\GhostBubbles\Documents\deepseek-harness\default-workspace\VeriBorder`

## 1. Build and unit tests

```text
gradlew.bat clean build   -> BUILD SUCCESSFUL
gradlew.bat test          -> DocumentCoreTest: 17 tests, 0 failures, 0 errors
```

The 17 tests cover all eight official layouts, mainland/HK check digits, NBT
tamper detection, airport visa matching, station ID-only rule, E-Gate
visa/endorsement requirements, blacklist/expiry rules, NBT round-trip, custom
covers, the new `displayName` field (round-trip, fallback, custom-style
interaction, integrity) and form generation.

## 2. Real-client GUI acceptance

A temporary `SmokeHarness` opened every screen in a real Forge client
(framebuffer 854×480, GUI scale 2 → 427×240 viewport), grabbed the framebuffer
with `Screenshot.takeScreenshot`, and asserted:

- the opaque panel is actually rendered (panel-colour coverage ≥ 55%);
- text/labels are drawn (text-pixel count ≥ 120);
- no magenta missing-texture pixels;
- every expected labelled `EditBox` exists;
- no `EditBox` overlaps another widget;
- all widgets are inside the design panel;
- document tabs have the correct count per kind, clicks switch content, and the
  list area changes by thousands of pixels;
- long custom names/addresses remain clipped inside the card.

Result: **27/27 GUI scenarios PASS**, including ID / passport / permit viewers,
tab clicks, Generator, Modifier, three issuers, visa, endorsement, six readers,
E-Gate screen, loss, renewal, print preview, blacklist, device config, custom
cover, very long text and the 18-item model gallery.

The harness was deleted before packaging. No `SmokeHarness`, `ModelGalleryScreen`
or other debug class is present in the final jar.

## 3. World / interaction acceptance

A quick-play flat world was generated on a dedicated server, then loaded in the
client. The harness placed all 18 machines with `ServerLevel.setBlockAndUpdate`
(no command spam, no cheats), teleported the player, and asserted:

| Scenario | Result |
|---|---|
| 18 machines placed and rendered, no missing textures | PASS |
| Right-click air with a valid passport opens `DocumentScreen` | PASS |
| Right-click hotel reader opens `ReaderScreen` | PASS |
| Right-click issuer opens `IssuerScreen` | PASS |
| `DeviceService.saveFromModifier` edits passport NBT, re-signs, integrity valid | PASS |
| `DeviceService.generateFromModifier` creates custom-name ID, integrity valid, in inventory | PASS |
| E-Gate granted: `OPEN=true`, collision shape no longer the full cube, `openProgress=1.00` | PASS |
| E-Gate expires: `OPEN=false`, full-cube collision restored, wings closed | PASS |

Result: **10/10 world/interaction scenarios PASS**.

## 4. Model and texture validation

`node tools/preview_models.js` renders an isometric textured PNG per model with
a software rasterizer and writes `tools/preview/metrics.txt` plus a
normalised 2D-shape hash. `tools/preview_models.ps1` also builds
`tools/preview/contact-sheet.png`.

```text
models: 18   blockstates: 18   variants: 76   textures on disk: 216
min elements: 19 (E-Gate 43)   unique geometry hashes: 18/18
distinct light colours: 51–85 per device
CHECKLIST: all PASS, FAILURES: none
```

- Every model has ≥ 10 elements, is not a single full cube, has ≥ 1 rotated
  (22.5° multiple) element, lies inside −0.6…16.6, and references only existing
  32/64 px textures.
- Every device has its own 12-texture set (body, body2, trim, top, accent,
  detail, LED, glass, paper, warning, screen, screen2); body average luminance
  202–225 and 18 distinct body RGB values (no black, no identical machines).
- E-Gate: 43 elements, 8 facing×open blockstate variants, clear centre volume
  for the animated wings; `egate_door.png` is 64×64 with 3712 transparent
  pixels and semi-transparent glass (alpha 61–140), while the frame is opaque.

## 5. E-Gate specifics

- `EGateBlock.OPEN` controls both collision and the glass-wing animation
  (`DeviceBlockEntity.animate()`), so the visible door cannot disagree with the
  physical passage.
- All device blocks are registered as `RenderType.translucent()` in
  `ClientEvents.clientSetup`, so the glass alpha is honoured.
- Four-state LEDs, scanning beam, verification cycle, sounds and particles are
  unchanged from the previous implementation and remain wired to the server
  state machine.
- Door/light/scan textures are drawn at the exact `texOffs` regions used by
  `EGateRenderer` (4×15×1 doors, 4×2×1 lights, 8×1×1 scan).

## 6. Remaining limitations

- The acceptance is programmatic; human visual review of proportions, texture
  readability at 0.64 UI scale, and animation feel is still recommended.
- `uiScale` is clamped to a minimum of 0.5; an extremely small viewport can
  still overflow.
- The ISO previewer uses a painter's algorithm; occasional interpenetrating
  elements can be sorted differently than in-game (Minecraft uses a depth
  buffer).
- The successful-issuance chat message still uses the official template name;
  the item name, tooltip, card, readers and print preview use `displayName`.

## 7. Reproduce

```bat
set JAVA_HOME=<JDK 17>
gradlew.bat clean build
gradlew.bat test

powershell -NoProfile -ExecutionPolicy Bypass -File .\tools\generate_assets.ps1
powershell -NoProfile -ExecutionPolicy Bypass -File .\tools\generate_recipes.ps1
powershell -NoProfile -ExecutionPolicy Bypass -File .\tools\preview_models.ps1
```

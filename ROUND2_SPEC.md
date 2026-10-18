# VeriBorder round-2 specification (for parallel agents)

Project: `C:\Users\GhostBubbles\Documents\deepseek-harness\default-workspace\VeriBorder`
Base: 1.0.0-hotfix-2. Author: Luminiflux. Minecraft 1.20.1 / Forge 47.2.0 / Java 17.
All document data stays in `ItemStack.tag.VeriBorder`. No external transport-mod dependency.

## 1. New DeviceType ids

Add exactly two ids (logic agent owns `DeviceType.java`):

- `exit_gate` — category `GATE`, screen `EGATE`, accent amber/red. Exit-side E-Gate.
- `self_endorsement` — category `ISSUER`, screen `ENDORSEMENT`, accent violet. Self-service endorsement kiosk.

Both gates use `EGateBlock` (OPEN blockstate, collision, BE state machine) and `EGateRenderer`.
`ModBlocks` must use `type.isGate()` for EGateBlock + light level. `EGateRenderer` must render for
`deviceType().isGate()` (not only `DeviceType.EGATE`).

## 2. Gate binding (visa / endorsement)

`DeviceBlockEntity` gains (persisted, synced, in `configTag`):

```text
gateMode                String: "any" | "passport" | "permit"   default "any"
requiredVisaType        String: VisaType id, or "" = any         default ""
requiredEndorsementType String: EndorsementType id, or "" = any  default ""
```

- `gateMode="passport"`: document must be a PASSPORT and have at least one active visa; if
  `requiredVisaType` is non-blank, it must have an active visa of exactly that type.
- `gateMode="permit"`: document must be a PERMIT and have at least one active endorsement; if
  `requiredEndorsementType` is non-blank, it must have an active endorsement of exactly that type.
- `gateMode="any"`: previous behaviour (valid document; passport may need any active visa, permit
  any active endorsement). ID cards are accepted.
- Wrong kind / missing matching record must fail before `startVerification`; the player gets a
  clear chat message naming the required type.
- On grant, consume the matching visa/endorsement record (the one matching the configured type),
  not just the first active one.
- `DocumentValidator` must expose an overload used by gates; add codes/reasons and en/zh lang:
  - `NO_MATCHING_ENDORSEMENT` (new code)
  - reasons `verify.veriborder.reason.visa_type_mismatch`,
    `verify.veriborder.reason.endorsement_type_mismatch`,
    `verify.veriborder.reason.gate_requires_passport`,
    `verify.veriborder.reason.gate_requires_permit`
  - detail should be the required type display name, so the UI says which one is required.
- `DeviceConfigScreen` (opened only by brush) shows for gates:
  - Gate mode cycle: Any valid document / Passport + visa / Permit + endorsement
  - Required visa type cycle (Any + VisaType values), visible for passport mode
  - Required endorsement type cycle (Any + endorsement types), visible for permit mode
  - Existing port/country/officer/airport fields remain.
- `EGateScreen` title/description and binding summary use the actual gate type/payload.

## 3. Brush (only brush can configure)

- New item id `device_brush` (`DeviceBrushItem`), creative tab, recipe, texture/model, tooltip.
- Right-click a `DeviceBlock` **while holding the brush** opens `DeviceConfigScreen` for that block.
  Normal right-click without the brush keeps the existing document/operational behaviour.
- `DeviceService.openConfig(ServerPlayer, BlockPos)` sends `OpenDevicePacket(pos, "config", payload)`
  with `deviceType` and config; `ClientPacketHandler` handles `"config"` by opening `DeviceConfigScreen`.
- The config GUI must **not** be reachable without the brush:
  - remove the ReaderScreen “config” button;
  - stamp machine with no document must not open DeviceConfigScreen (show the normal stamp/empty view);
  - `save_config` server-side must verify the player holds `DeviceBrushItem` in either hand, otherwise
    reject with `message.veriborder.brush.required`.
- Brush recipe: cheap utility item, e.g. feather + stick + gold nugget (shapeless), recipe generated
  by `tools/generate_recipes.ps1`.

## 4. Self-service endorsement machine

- New `self_endorsement` device uses `EndorsementScreen` (permit target, issue/renew/revoke, templates).
- `EndorsementScreen` must take the actual `DeviceType` (constructor or payload) and use its
  translation/description for the header, so the new kiosk reads “Self-service Endorsement Machine”.
- `DeviceService.openEndorsement` sends the actual device type; `requiredKind` treats both
  `ENDORSEMENT_MACHINE` and `SELF_ENDORSEMENT` as PERMIT.
- Staff `endorsement_machine` and self-service kiosk have distinct models/textures/recipes.

## 5. Endorsement type consolidation

- Remove `TEAM` and `INDIVIDUAL` from `EndorsementType` as selectable types. Keep only:
  `ONE_YEAR_MULTI`, `THREE_MONTH_SINGLE`, `OTHER`.
- `EndorsementType.byId("team"|"individual")` must map to `OTHER` for old NBT documents.
- `next()` / UI cycle / templates / tests use the remaining types only.
- Update `endorsement.type.veriborder.*` lang and the hint `screen.veriborder.endorsement.types`
  to the remaining set. Do not leave dead enum constants.

## 6. E-Gate size + animation (visual agent)

- New static gate model is **larger than one block**: at least 24 px wide × 20 px tall; target
  32 px wide (2 blocks) × 24 px tall, centred on the controller block, with a clear 8 px central
  passage. The block collision remains the 1-block passage / side cabinets; visuals may extend
  into neighbouring blocks. Document this as “visual model larger than the controller block”.
- Doors: two leaves (or four panels) with a metal frame + transparent glass, smooth easing and
  partial-tick interpolation. `DeviceBlockEntity.openProgress(float partialTick)` should interpolate
  and ease; renderer uses it. Keep `OPEN` blockstate authoritative for collision/animation.
- Improve the gate look: taller pedestals, sloped tops, overhead light bar, card reader, LED strips,
  approach/scan glow. Keep the centre volume clear for the animated wings.
- `exit_gate` gets its own model/texture set and exit signage (red/amber accent), same mechanics.
- New `self_endorsement` kiosk model/texture set (distinct from `endorsement_machine`).
- Brush item texture/model.
- Regenerate blockstates, item models, loot tables, block/entity textures, previews, metrics.
  `tools/preview_models.js` CHECKLIST must be FAILURES: none.

## 7. File ownership (do not cross)

Logic agent:
`block/DeviceType.java`, `DeviceBlock.java`, `DeviceBlockEntity.java`, `DeviceService.java`,
`doc/EndorsementType.java`, `DocumentValidator.java`, `VerificationResult.java`,
`item/DeviceBrushItem.java`, `registry/ModItems.java`, `registry/ModBlocks.java`,
`registry/ModCreativeTabs.java`, `client/ClientPacketHandler.java`,
`client/screen/DeviceConfigScreen.java`, `EndorsementScreen.java`, `EGateScreen.java`,
`ReaderScreen.java`, `lang/en_us.json`, `zh_cn.json`, `src/test/**`,
`tools/generate_recipes.ps1`.

Visual agent:
`client/EGateRenderer.java`, `tools/lib_device_models.ps1`, `tools/lib_device_textures.ps1`,
`tools/generate_assets.ps1`, `tools/preview_models.js`, `tools/preview_models.ps1`,
`resources/assets/veriborder/models/block/**`, `blockstates/**`, `models/item/**`,
`textures/block/**`, `textures/entity/**`, `textures/item/device_brush.png`,
`models/item/device_brush.json`, `data/veriborder/loot_tables/blocks/**`.
No other Java.

## 8. Verification

- `gradlew.bat compileJava` and `gradlew.bat test` pass.
- `node tools/preview_models.js` → `FAILURES: none`, 20 models (18 + exit gate + self-service).
- Real client: brush right-click opens config; normal right-click with document still opens the
  operational GUI; wrist configuration without brush impossible; gate mode binding accepts only the
  configured visa/endorsement type; exit gate opens/closes; self-service endorsement issues a permit
  endorsement; TEAM/INDIVIDUAL no longer selectable.
- Keep debug harnesses out of the final jar.

# VeriBorder 1.2.0 specification — self-service visa + machine fixed codes

Base: 1.1.5. No external transport-mod dependency. 21 devices.

## 1. New SELF_VISA device

- `DeviceType.SELF_VISA("self_visa", Category.ISSUER, ScreenKind.VISA_ISSUER, 0x3E8FC4)`.
- New model `Build-SelfVisa` (32 elements, low blue passport desk, sloped screen, tray,
  fingerprint pad, receipt slot), 12 block textures, blockstate, item model, loot, recipe.
- Player UI (`VisaIssuerScreen` self-service branch): target passport card + visa type cycle +
  Issue + Close. No country, entries, issue, expiry, authority, code, template or Issue All.
- Server `issueVisa` detects `SELF_VISA` and ignores player country/authority/code/entries/dates.
  Uses machine country, `visaMachineAuthority`, 1-year expiry, `VisaType.defaultEntries()`,
  and `issuedCode` (or auto).

## 2. Machine fixed issued code

- `DeviceBlockEntity.issuedCode` (8 digits, blank = auto), persisted + synced + configTag.
- Admin brush config for `SELF_VISA` and `SELF_ENDORSEMENT` shows an “Issued code” field.
- `SELF_VISA` / `SELF_ENDORSEMENT` issuance:
  - valid `issuedCode` -> exact code for every credential from that kiosk;
  - blank -> auto-generated unique code.
- `DocumentData.addVisaExact` / `addEndorsementExact` preserve explicit fixed codes, even if
  shared; normal staff paths still use the unique-code `addVisa`/`addEndorsement`.
- Gate matches the fixed code exactly; one kiosk code can be entered once in the gate.

## 3. Permit issuer initial endorsement code lock

- `DeviceBlockEntity.lockEndorsementCode` + `lockedEndorsementCode`.
- Brush config for `PERMIT_ISSUER`: toggle + 8-digit field.
- `issueDocument` uses the locked code verbatim (`addEndorsementExact`) when enabled.

## 4. UI / server isolation

- Self-service kiosks send only the type.
- Server ignores forged authority/code/entries/dates for self-service.
- Admin authority/locks remain available through the brush config.
- `DeviceConfigScreen`: self-service issued code field; permit initial endorsement code
  toggle/field; existing port/country/officer, gate codes, lock fields retained.

## 5. Acceptance

- 43 unit tests (fixed/shared codes, gate matching, lock fields, visa-free, four modes).
- Real client 1.2.0 FeatureSmoke 22/22: self visa issue, fixed codes, initial endorsement lock,
  UI isolation, 21 model/config/world renders with magenta=0.
- `preview_models.js`: 21 models, all PASS.

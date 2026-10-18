# VeriBorder 1.1.5 specification — codes, locks, self-service

Base: 1.1.3. All document state stays in `ItemStack.tag.VeriBorder`.
No external transport-mod dependency. Author Luminiflux. Do not change the gate model/assets.

## 1. 8-digit credential codes (source of truth for gates)

Add `doc/CredentialCode.java`:

```java
static boolean isValid(String code)        // exactly 8 digits, regex \d{8}
static String random()                     // 00000000..99999999, 8 chars
static String generateUnique(Collection<String> used)
static String legacy(String id)            // deterministic 8 digits for old records
```

- `VisaRecord` and `EndorsementRecord` gain a final `String code` component.
  - `fromTag` reads `"code"`; if blank use `CredentialCode.legacy(id)`.
  - `toTag` writes `"code"`.
  - Canonical constructor: blank/invalid code -> `legacy(id)` (or random if id blank).
- `DocumentData`:
  - NBT list `visaFreeCodes` (ListTag of StringTag) in `createEmpty`/`fromTag`.
  - `List<String> visaFreeCodes()`, `addVisaFreeCode(String)`, `removeVisaFreeCode(String)`,
    `clearVisaFreeCodes()`. Validate 8 digits, dedupe, sign.
  - `addVisa`/`addEndorsement`: if code blank/invalid, assign a random code unique among the
    document's existing credential codes. Keep an explicitly supplied valid code.
  - `updateVisa`/`updateEndorsement`: if code blank/invalid, generate; never silently drop.
  - `allCredentialCodes()` is useful for tests: active/inactive raw codes are fine.
- `DocumentFactory.createVisa/createEndorsement` gain an overload accepting `code`; current
  methods delegate with a generated code.
- `DeviceService.issueVisa`: code from form `"code"` if valid, else random unique. Batch templates
  must each get a distinct code. Forge `allTemplates` uses generated codes, never copies one code.
- `DeviceService.issueEndorsement`: code from form `"code"` for staff; self-service always auto.
- Permit initial endorsement: auto code; if form has `"initialEndorsementCode"`, use it.
- Old documents without codes: `legacy(id)` is deterministic and shown by the UI, so the admin can
  enter that code at the gate. `toTag` persists it when the document is saved/edited.

## 2. Gate modes and code matching

`DeviceBlockEntity`:

```text
gateMode    "all" | "visa" | "endorsement" | "both"   default "all"
gateCodes   ListTag of StringTag (8-digit strings, no limit, dedupe)
```

Legacy modes normalize: `any -> all`, `passport -> visa`, `permit -> endorsement`.
Keep `requiredVisaType`/`requiredEndorsementType` fields only for old NBT compatibility but do not
use them in validation or show them in the UI.

`DocumentValidator.validateGate(DocumentData doc, DeviceType device, String gateMode, List<String> gateCodes)`:

- Always run the normal document checks first (integrity/style/status/name/number/dates/expiry).
- `all`: any valid document; no visa/endorsement/code requirement.
- `visa`: document must be PASSPORT. Candidate codes = active visas' `code()` + `visaFreeCodes`.
  If any candidate exactly equals a configured gate code -> valid. No match -> `NO_MATCHING_CODE`.
- `endorsement`: document must be PERMIT. Candidate codes = active endorsements' `code()`.
  No match -> `NO_MATCHING_CODE`.
- `both`: PASSPORT (visa + visa-free) or PERMIT (endorsements); any exact code match.
- Empty `gateCodes` in visa/endorsement/both -> reject with `NO_MATCHING_CODE` reason
  `verify.veriborder.reason.gate_no_codes`.
- Wrong kind -> `WRONG_KIND` with `gate_requires_passport` / `gate_requires_permit` /
  `gate_requires_visa_or_endorsement`.
- `detail` should contain the matched code on success and the required mode on failure.
- Add `VerificationResult.Code.NO_MATCHING_CODE` and lang keys
  `verify.veriborder.no_matching_code`, `reason.gate_no_codes`, `reason.gate_code_mismatch`,
  `reason.gate_requires_visa_or_endorsement`.

`DeviceService.useGate`:
- Uses actual gate type; invalid result rejects before `startVerification`, sends EGateScreen check
  + chat message.
- `consumeGateEntry` scans active visas/endorsements **whose code is in gateCodes** and increments
  that record once. Visa-free-only matches are not consumed. `all` mode does not consume.
- Gate code list is unlimited; validate/dedupe strings on save.

## 3. Locked issuer fields

`DeviceBlockEntity` adds persisted+synced:

```text
lockDocumentName     boolean
lockedDocumentName   String, default ""
lockAuthority        boolean
lockedAuthority      String, default ""
```

`DeviceService` server-side enforcement (client UI is only cosmetic):
- `issueDocument`: if `lockDocumentName` and locked value non-blank, force form `"displayName"`;
  if `lockAuthority`, force form `"authority"`. This happens before `DocumentFactory.fromForm`.
- Permit initial endorsement authority uses `lockedAuthority` when locked.
- `issueVisa`/`issueEndorsement`: if `lockAuthority`, force authority (for endorsement also force in
  self-service mode).
- `DeviceConfigScreen` (brush/admin only): for issuer devices (`device.isIssuer()`), add:
  - toggle “Lock document name”; text field for the locked name;
  - toggle “Lock issuing authority”; text field for the locked authority;
  - save all four fields.
- IssuerScreen: read payload locks; when locked, set field value to locked value and
  `setEditable(false)`. Server override remains authoritative.
- VisaIssuerScreen/EndorsementScreen: authority locked -> non-editable; server override.

## 4. Self-service endorsement machine isolation

- `DeviceService.openEndorsement` payload includes `selfService` (true iff
  `device.deviceType() == SELF_ENDORSEMENT`) and the machine authority (`lockedAuthority` if set,
  else device.country-based default).
- `EndorsementScreen` when `selfService`:
  - show only the preset endorsement **type cycle** + Issue button + a short info line;
  - hide authority, template name/template buttons, code field, renew/revoke/delete controls;
  - no custom document name/authority entry.
- `DeviceService.issueEndorsement` for `SELF_ENDORSEMENT`:
  - ignore player-supplied authority/code/entries/dates (or use type defaults + today);
  - authority = lockedAuthority if set, else device country + “出入境管理局”;
  - code auto-generated; add to permit; reopen with actual device type.
- Admin can still set authority/locks/codes for the self-service machine via the brush config.

## 5. Gate configuration GUI

`DeviceConfigScreen` for gates:
- mode cycle: `All documents / Visa only / Endorsement only / Visa or endorsement`;
- 8-digit code entry + Add;
- show count and a short list/last codes; Remove last / Clear all;
- save `gateMode` and `gateCodes` ListTag.
- Show a hint that codes are the only gate validation source.
- `EGateScreen`: display the 4-mode label + number of configured codes; no required visa/endorsement
  type labels.

## 6. Code editing UI

- VisaIssuerScreen: add a `code` field (8 digits, blank = auto) with hint; show generated code in
  result/record list if feasible.
- EndorsementScreen (staff): add `code` field (blank = auto); hide for self-service.
- ModifierScreen:
  - visa/endorsement editor: code field (8 digits, blank = auto/keep);
  - passport profile or visa tab: visa-free code management (add/remove/clear, 8-digit validation).
- Document cards/lists may show the code (small mono text) where space allows; not mandatory.

## 7. File ownership

DATA agent:
`doc/CredentialCode.java` (new), `VisaRecord`, `EndorsementRecord`, `DocumentData`,
`DocumentFactory`, `DocumentValidator`, `VerificationResult`, `block/DeviceBlockEntity`,
`block/DeviceService`, `src/test/**`.

UI agent:
`client/screen/*` (IssuerScreen, VisaIssuerScreen, EndorsementScreen, DeviceConfigScreen,
EGateScreen, ModifierScreen, AbstractDocumentScreen if needed), `client/ClientPacketHandler`,
`lang/en_us.json`, `lang/zh_cn.json`. No doc/block/DeviceService edits.

Main agent: build, client acceptance, version/docs/dist.

## 8. Acceptance

- `gradlew clean build` + `test` pass; tests must cover:
  - code validation/generation/legacy;
  - visa/endorsement code NBT round-trip;
  - visa-free codes round-trip/sign/integrity;
  - all four gate modes, exact-code match, visa-free match, wrong code/wrong kind/no codes;
  - lock fields NBT round-trip.
- Real client smoke:
  - brush opens config; lock toggles/save; normal issuer UI fields non-editable and server issues
    locked name/authority;
  - self-service UI hides authority/template/code/renew/revoke and issues with machine authority;
  - gate config accepts 8-digit codes (including leading zeros, dedupe, unlimited);
  - gate modes: visa code, endorsement code, visa-free code, wrong code reject, all-mode pass;
  - gate still opens/closes and collision works for N/E/S/W.
- No debug classes in final jar; version 1.1.5.

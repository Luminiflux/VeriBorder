# VeriBorder 26.1 specification — first formal release

Base: 1.2.1. This is the **first formal version**, version string `26.1`.
No external transport-mod dependency.

## 1. Creative-only admin items

- `device_brush` and `document_modifier` become **creative-only**: remove their recipes
  from `tools/generate_recipes.ps1` and delete the generated recipe JSONs.
- Server-side permission for admin actions: **OP level 2** (`player.hasPermissions(2)`):
  - `DeviceService.openConfig` / `save_config`
  - `DocumentModifierItem` open/generate/save (`openModifier`, `generateFromModifier`,
    `saveFromModifier`)
  - officer actions.
- Normal device blocks remain craftable; only the admin tools are creative-only.
- Do not rely on the client for permissions.

## 2. Border session state machine

New package `com.veriborder.border`:
- `BorderSession` (UUID player, playerName, documentNumber, recordId, code, kind,
  dimension, gatePos, entryEpochMillis, port, overstayDays, overstay)
- `BorderLog` (epochMillis, player UUID/name, direction, result, documentNumber, code,
  kind, gate, detail)
- `BorderTravelData extends SavedData`: active sessions map + capped log list (500);
  persisted in the overworld data storage; NBT save/load.

Entry gate (`EGATE`):
1. If player already has an active session -> deny `ALREADY_ENTERED`, log DENY_IN,
   message "already entered, contact officer"; do not animate.
2. Validate gate mode/codes as today. On failure log DENY_IN.
3. On success determine matched source:
   - `all`: kind ALL, no record/code.
   - `visa`: active visa or visa-free code; kind VISA / VISA_FREE; recordId/code.
   - `endorsement`: active endorsement; kind ENDORSEMENT; recordId/code.
   - `both`: either.
4. Create session; **never increment usedEntries at entry**.
5. If `autoStamp`, add an IN stamp to passport/permit.
6. Start gate animation.

Exit gate (`EXIT_GATE`):
1. Require active session; none -> deny `NO_ENTRY_RECORD` "contact officer", log DENY_OUT.
2. Document number must equal session document -> else `SESSION_DOCUMENT_MISMATCH`.
3. For VISA/ENDORSEMENT, find record by id/code; missing/inactive -> `SESSION_RECORD_MISSING`.
4. Consume exactly that record (`usedEntries + 1`) on successful exit; visa-free/ALL do not
   consume.
5. Clear session.
6. If `autoStamp`, add an OUT stamp.
7. Overstay: if now - entryEpochMillis > overstayDays*24h, allow exit but set overstay flag,
   log `OVERSTAY`, show warning. overstayDays comes from the entry gate (default 30).
8. Exit gate ignores gateCodes/mode for validation; it reads the session/record.

Re-entry with active session is always denied regardless of visa entries.

## 3. Officer workflow

- `MANUAL_TERMINAL` right-click by OP -> officer screen; non-OP -> normal reader.
- Officer payload: `officer=true`, `sessions` ListTag, `logs` ListTag.
  Session compound: player UUID/name, document, kind, code, gateX/Y/Z, dimension,
  entryTime, overstay, overstayDays.
  Log compound: time, player/name, direction, result, document, code, kind, gate, detail.
- Officer actions: `officer_clear` (target UUID), `officer_refresh`.
- Server checks OP for open/action. Clear logs an officer entry.
- Commands (OP 2):
  - `/veriborder border status <player>`
  - `/veriborder border clear <player>`
  - `/veriborder border log [count]`
- Officer screen has session list, clear button, recent log list; no player access.

## 4. Gate config additions

`DeviceBlockEntity`:
- `autoStamp` boolean default true
- `overstayDays` int default 30
Persist/sync/configTag.
`DeviceConfigScreen` for gates:
- auto-stamp toggle
- overstay days field
- batch gate-code input: accept comma/space/semicolon-separated 8-digit codes in the
  existing add field; add all valid codes, reject invalid; no pagination.

## 5. Stamps

- Gate auto stamps on IN/OUT when autoStamp is true:
  `doc.addStamp(IN/OUT, device.port(), device.country(), device.officer())`.
- Only PASSPORT and PERMIT are stamped; ID cards are skipped.
- Allow HK/Macau permits in stamp machines/history:
  - `DeviceService.useStamp`: PASSPORT **or** PERMIT.
  - `openHistory`: PASSPORT **or** PERMIT.
  - `DocumentValidator.requiredKind` for stamps: allow either (null) with explicit checks.
  - UI labels/messages should say passport or permit.

## 6. Passport-attached visa locks / fields

Add to `DeviceBlockEntity`:
```text
lockIncludeVisa       boolean   lockedIncludeVisa       boolean
lockVisaEntries       boolean   lockedVisaEntries       int (0=unlimited)
lockVisaCountry       boolean   lockedVisaCountry       String
lockVisaIssueDate     boolean   lockedVisaIssueDate     String YYYY-MM-DD
lockVisaValidity      boolean   lockedVisaValidityYears int (0=permanent)
lockVisaName          boolean   lockedVisaName          String
```

- `VisaRecord` gains a `String label` component; `fromTag/toTag` key `label`;
  `DocumentFactory.createVisa(..., authority, code, label)` overload; existing overload
  passes blank.
- Passport attached visa:
  - includeVisa is locked to `lockedIncludeVisa` when `lockIncludeVisa`.
  - entries locked; country locked; name locked; issue/expiry locked as specified.
  - if `lockVisaIssueDate` valid -> visa issue = that date; else passport issue.
  - if `lockVisaValidity` -> visa expiry = lockedExpiry(visaIssue, years);
    else inherit passport expiry or +1 year.
  - if `lockVisaName` -> label = lockedVisaName; else form `visaName`.
- Standalone visa issuer:
  - `lockVisaName` and label field; existing type/code/country/date/validity locks.
- Display visa label if nonblank; otherwise country/type as before.

## 7. UI

- `IssuerScreen` passport panel: includeVisa lock, visa entries lock, visa country lock,
  visa issue/validity lock, visa name lock; fields non-editable/seeded.
- `VisaIssuerScreen`: visa name field + lock; existing locks.
- `DeviceConfigScreen`: all new locks above; filter controls by device; batch gate codes;
  autoStamp/overstay; keep 480px design height and automatic scaling.
- `OfficerScreen` new (UI agent B): sessions/logs/clear/refresh; 520x420; no player access.
- `ClientPacketHandler`: officer payload -> OfficerScreen; manual terminal non-op -> reader.
- Lang: all new keys en/zh, parity.

## 8. Permissions / resources

- `device_brush`/`document_modifier` recipes removed. Keep items in creative tab.
- OP checks server-side for config, modifier, officer.
- Version `26.1`; docs/README rewritten as whole-mod overview without per-version changelog.
- GitHub: delete old releases/tags, publish only `26.1`.

## 9. Acceptance

- Unit: BorderTravelData NBT roundtrip; session overstay; attached visa new locks/label;
  permit stamp acceptance; existing 46 tests.
- Client:
  - entry -> no usedEntries increment; re-entry denied; exit consumes exactly one and clears;
  - exit without session denied; wrong document denied;
  - auto IN/OUT stamps on passport and permit;
  - officer screen shows session/logs, clear works as OP, non-op cannot;
  - batch codes add multiple; autoStamp/overstay persist;
  - passport attached visa locks/label.
- Build/clean jar; no debug; preview 21 models.

## 10. File ownership

DATA-A (border): new `border/**`, `DeviceService` gate/officer/stamp/permission,
`DeviceBlockEntity` autoStamp/overstay, `DocumentValidator`, `DocumentService` commands,
tests for border data.

DATA-B (locks/label, after DATA-A): `VisaRecord`, `DocumentFactory`, `DocumentData` label,
`DeviceBlockEntity` new locks, `DeviceService` attached/visa locks, tests.

UI-A (locks/config): `IssuerScreen`, `VisaIssuerScreen`, `DeviceConfigScreen`, `lang`.

UI-B (officer/border UI): new `OfficerScreen`, `ReaderScreen`/`ClientPacketHandler` if needed,
no lang (UI-A owns lang; report keys).

MAIN: recipes/creative-only, docs, version, release.

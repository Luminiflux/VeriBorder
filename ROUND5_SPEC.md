# VeriBorder 1.2.1 specification — passport-attached visa + universal issuer locks

Base: 1.2.0-hotfix2. No external transport-mod dependency. All document data stays in
`ItemStack.tag.VeriBorder`; device config stays in the block entity NBT.

## 1. New device lock fields

Add to `DeviceBlockEntity` (persisted, synced, configTag, legacy defaults neutral):

```text
lockStyle              boolean   lockedStyle              String (DocStyle id)
lockPlace              boolean   lockedPlace              String
lockIssueDate          boolean   lockedIssueDate          String YYYY-MM-DD
lockValidity           boolean   lockedValidityYears      int   (0 = permanent)
lockVisaType           boolean   lockedVisaType           String (VisaType id)
lockVisaCode           boolean   lockedVisaCode           String (8 digits)
lockEndorsementType    boolean   lockedEndorsementType    String (EndorsementType id)
```

- `lockStyle` applies to ID_ISSUER, PASSPORT_ISSUER, PERMIT_ISSUER.
- `lockVisaType` applies to VISA_ISSUER, SELF_VISA and passport-attached visas.
- `lockEndorsementType` applies to ENDORSEMENT_MACHINE, SELF_ENDORSEMENT.
- `lockPlace` = place of issue for documents, country for visas (and passport-attached visas).
- Invalid locked values are ignored (fall back to normal form/default), but the fields must
  round-trip exactly for valid values.

## 2. Universal server-side enforcement

`DeviceService` is authoritative; client UI is cosmetic.

### Document issuance (`issueDocument`, ID/passport/permit)

1. Resolve `DocStyle style`:
   - if `lockStyle` and `DocStyle.byId(lockedStyle).kind() == expected` -> use it;
   - otherwise use submitted style.
2. Build a copy of the form and force:
   - `displayName` / `authority` existing locks (unchanged);
   - `placeOfIssue` = `lockedPlace` if `lockPlace`;
   - `issueDate` = `lockedIssueDate` if `lockIssueDate` and valid;
   - `style` = style.id().
3. `DocumentData doc = DocumentFactory.fromForm(style, form)`.
4. If `lockValidity`, override `doc.setExpiryDate(...)`:
   - `lockedValidityYears <= 0` -> `DateUtil.PERMANENT`;
   - else `DateUtil.plusYears(doc.issueDate(), lockedValidityYears)`.
   - Existing permanent styles stay permanent if years <= 0.
5. Passport attached visa (`expected == PASSPORT`):
   - form fields: `includeVisa` boolean, `visaCountry`, `visaType`, `visaCode`,
     `visaEntries` (default 1).
   - If `includeVisa`:
     - country = lockPlace ? lockedPlace : form visaCountry (blank -> style.defaultNationality());
     - type = lockVisaType ? lockedVisaType : form visaType (fallback TOURIST);
     - code = lockVisaCode ? lockedVisaCode : form visaCode (blank -> auto-unique);
     - issue = doc.issueDate(); expiry = doc.expiryDate() if valid non-permanent,
       otherwise `plusYears(issue, 1)`;
     - authority = existing authority lock if set, else doc.authority();
     - add the visa **after** the passport is built and signed enough to carry data;
       write the passport stack afterwards. If locked code is explicit, preserve it
       (`addVisaExact`); otherwise use `addVisa` for unique code.
6. Permit initial endorsement keeps working; initial endorsement code lock from 1.2.0
   remains.

### Visa issuance (`issueVisa`)

- Force `type` = lockedVisaType if valid.
- Force `country` = lockedPlace if lockPlace.
- Force `issueDate` = lockedIssueDate if valid.
- If `lockValidity`, compute expiry = years<=0 ? PERMANENT : plusYears(issueDate, years);
  otherwise keep existing template/form expiry logic.
- Self-service (`SELF_VISA`) ignores player-supplied country/type/code/date/expiry/entries
  except the type only if not locked; machine `issuedCode` remains.
- Passport-attached visa is additional; standalone visa issuer remains unchanged.

### Endorsement issuance (`issueEndorsement`)

- Force `type` = lockedEndorsementType if valid.
- Force `issueDate` = lockedIssueDate if valid.
- If `lockValidity`, compute expiry = years<=0 ? PERMANENT : plusYears(issueDate, years).
- Self-service ignores player type if locked; otherwise type cycle remains.

## 3. Passport issuer GUI (player)

`IssuerScreen` for `PASSPORT_ISSUER` gains an optional visa panel (only kind == PASSPORT):

- toggle “Include a visa” (`includeVisa`);
- country field;
- visa type cycle;
- visa code field (8 digits, blank = auto);
- entries field (default 1);
- compact summary of dates (`issue`/`expiry` inherited from passport).
- Locked fields are non-editable and seeded with locked values.
- Style button disabled when `lockStyle`.
- `issueDocument` form includes `includeVisa`, `visaCountry`, `visaType`, `visaCode`,
  `visaEntries`.
- The panel must not break the existing passport form; increase screen height as needed.

## 4. All issuer GUIs

- `IssuerScreen`: style lock; place/issue/expiry locks; visa panel for passports.
- `VisaIssuerScreen`: visa type lock; country/place lock; issue/expiry lock; staff and
  self-service.
- `EndorsementScreen`: endorsement type lock; issue/expiry lock; staff and self-service.
- Self-service UIs stay minimal: if type is locked, show the fixed type (no cycle or a
  disabled cycle); no authority/code/template controls.
- Dates default to `DateUtil.today()` (current game date); locked values override.
- Expiry display should reflect auto-calculated years when `lockValidity` is on.

## 5. Admin config UI

`DeviceConfigScreen` adds lock controls, conditionally:

- document issuers: lock document layout (DocStyle cycle), lock place, lock issue date,
  lock validity years (0 = permanent);
- visa issuers/self-visa: lock visa type, lock visa code, lock country/place, issue date,
  validity;
- endorsement machines/self-endorsement: lock endorsement type, issue date, validity;
- passport issuer also: lock attached-visa type/code.

Save keys exactly as section 1. Keep existing port/country/officer/airport, gate mode/codes,
name/authority locks, preset fields.

Screen may grow taller; keep all controls reachable at GUI scale 2.

## 6. Tests / acceptance

- Unit: DeviceBlockEntity lock NBT round-trip; locked style/type/date/validity helpers if
  factored; passport visa data addition; existing 43 tests still pass.
- Client: issue a passport with includeVisa + malicious fields -> passport NBT has exactly
  one new visa with locked/typed values; verify NBT integrity.
- Client: lock style/place/issue/validity on passport issuer -> issued passport uses locked
  values.
- Client: self-service visa/endorsement with locked type -> player cannot change type;
  server uses locked type.
- Client: standalone visa issuer still appends visa after right-click; old flow unchanged.
- `preview_models` 21 models, FAILURES none.
- Final version 1.2.1; no debug classes; release assets.

## 7. File ownership

DATA agent:
`block/DeviceBlockEntity`, `block/DeviceService`, `doc/DocumentData` (only if helpers needed),
`doc/DocumentFactory` (only if helper needed), `src/test/**`.

UI agent:
`client/screen/IssuerScreen`, `VisaIssuerScreen`, `EndorsementScreen`, `DeviceConfigScreen`,
`ClientPacketHandler` (payload fields only), `lang/en_us.json`, `lang/zh_cn.json`.

Main: build, client smoke, docs, git/release.

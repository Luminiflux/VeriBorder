# VeriBorder / 证界边检

**VeriBorder** is a Minecraft **1.20.1 Forge** mod by **Luminiflux** that recreates the
complete real-world document chain and border-control workflow: identity cards, passports,
exit-entry permits, visas, endorsements, immigration stamps, issuing machines, verification
terminals and automated border gates.

Everything is stored in the item NBT, so every document is a real, editable object rather
than a menu entry. Machine configuration is stored in each block entity and survives world
saves.

- Minecraft 1.20.1
- Forge 47.x
- Java 17
- Version **26.2**

## Documents

- **Identity cards**: mainland China and Hong Kong layouts.
- **Passports**: PRC, Hong Kong SAR, Macao SAR, United States and Japan layouts, with
  machine-readable zones and custom cover data.
- **HK & Macau Exit-Entry Permit**.
- **Visas**: tourist, business, student, work, family and other, with issuing country,
  authority, entries, dates and a unique 8-digit credential code.
- **Endorsements**: one-year multiple, three-month single and other, with the same code
  system.
- **Visa-free codes**: passports can carry 8-digit visa-free codes directly in NBT, with no
  physical visa page.
- **Stamps**: entry and exit records with port, country, date and officer. Both passports
  and HK/Macau permits can be stamped.
- Custom document names, colours, types, numbers, photos and layouts are editable with the
  Universal Document Modifier.

## Credential codes

- Every visa and endorsement has an 8-digit numeric code, including leading zeros.
- Auto-generated codes are unique within a document. A machine may intentionally issue a
  fixed shared code (for example, one code per self-service kiosk).
- Passport-attached visas, standalone visas, endorsements and visa-free codes are all
  matched by exact 8-digit code at the gate.
- E-Gates support an unlimited code list and four modes: all documents, visa only,
  endorsement only, visa or endorsement.

## Border workflow

- **Entry E-Gate**: validates the document and code, creates a persistent border session and
  writes an entry stamp if auto-stamping is enabled. It does **not** consume visa or
  endorsement entries yet.
- **Exit E-Gate**: reads the active session, checks the same document and credential, consumes
  exactly one entry, writes the exit stamp and clears the session.
- Re-entering while already inside is denied with a “contact an officer” message.
- Exit without a session, with a different document, or with an invalidated credential is
  denied.
- Overstay is detected on exit; the traveller is allowed to leave, an overstay warning is
  recorded, and the officer log is updated.
- Border sessions and logs persist across server restarts and player logouts.

## Officer workflow

- OP-level 2 officers can right-click a **Manual Verification Terminal** to open the Officer
  Console.
- The console lists active border sessions and recent passage logs, and can clear an entry
  session (for example after a lost document or a border exception).
- Officer clears are logged.
- Commands for OP level 2:
  - `/veriborder border status <player>`
  - `/veriborder border clear <player>`
  - `/veriborder border log [count]`

## Issuing machines

- **Identity Card Issuer**, **Passport Issuer**, **HK & Macau Permit Issuer**.
- **Visa Issuing Terminal**: writes visas into a held/inventory passport; templates and
  batch template application.
- **Endorsement Processing Machine**: issue, renew and revoke endorsements.
- **Self-service Visa Machine** and **Self-service Endorsement Machine**: the player chooses
  only the credential type; authority, code, dates and validity are machine parameters.
- **Passport Issuer** can optionally attach a visa at passport creation. The visa is written
  into the passport NBT in the same operation. Standalone visa issuance still works.
- All issuers support lockable:
  - document layout / visa type / endorsement type;
  - place of issue or visa country;
  - issue date;
  - validity years (0 = permanent), with automatic expiry calculation;
  - visa name and 8-digit code;
  - passport attached-visa include/entries/country/issue/validity/name.
- Locked fields are enforced by the server; forged packets cannot override them.

## Verification and gates

- **Hotel Check-in Card Reader**, **Desktop Document Scanner**, **Manual Verification
  Terminal**, **Airport Boarding Verification Gate**, **Station Real-name Verifier**.
- **Border Self-service E-Gate** and **Border Exit E-Gate**:
  - split dual-pillar design: white body, dark curved top cap, vertical reader screen,
    cyan LED strip and green direction arrow;
  - 12 px clear passage, 20 px overall width, full-height collision wall;
  - real animated glass door: two framed glass leaves slide smoothly into the pillars when
    the gate opens and meet in the centre when closed;
  - status lights, scan beam, sounds and particles;
  - unlimited 8-digit code list, batch code entry, four modes;
  - optional automatic entry/exit stamps.

## Gate configuration brush

- A creative-only **Gate Configuration Brush** stores one complete gate setup in its NBT:
  port name, country, inspecting officer, gate mode, code list, auto-stamp and overstay days.
- Sneak + right-click opens the brush editor (the same fields as the gate's own config
  screen); right-click a gate to copy the stored config into it. OP level 2 required.

## Machines and recipes

- **21 machines** with 21 block models, 21 blockstates and 24 recipes; every device model is
  authored in Blender and exported to Minecraft block models.
- **Device Configuration Brush** and **Universal Document Modifier** are creative-only admin
  tools and require OP level 2 for configuration/modification actions.
- Self-service kiosks, gates and terminals remain craftable; servers can place them in
  controlled border areas.

## Build and test

```bat
set JAVA_HOME=<JDK 17>
gradlew.bat clean build
gradlew.bat test
```

Asset generation:

```bat
powershell -NoProfile -ExecutionPolicy Bypass -File .\tools\generate_assets.ps1
powershell -NoProfile -ExecutionPolicy Bypass -File .\tools\generate_recipes.ps1
node tools\preview_models.js
powershell -NoProfile -ExecutionPolicy Bypass -File .\tools\preview_models.ps1
```

## License

MIT. See [LICENSE](LICENSE).

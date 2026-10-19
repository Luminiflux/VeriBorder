# VeriBorder

**VeriBorder / 证界边检** is a Minecraft **1.20.1 Forge** mod by **Luminiflux** that recreates the complete real-world document chain — identity cards, passports, exit-entry permits, visas, endorsements and immigration stamps — together with the machines found in airports, land ports, hotels, government service halls and immigration halls.

**Current release:** `1.2.0-hotfix2`.

> **1.2.0-hotfix2:** the E-Gate now has a full-height collision wall. Closed gates block two blocks of height; open gates keep the 12 px centre passage clear but raise both side cabinets to the same height. This prevents jumping onto the gate top and crossing the border.
>
> **1.2.0-hotfix:** fixed a startup/creative-inventory crash caused by filling the creative tab from the `displayItems` lambda before every device item's `RegistryObject` was present. Creative contents now come from Forge's `BuildCreativeModeTabContentsEvent` after registries are ready, with an `isPresent()` guard. The crash was `Registry Object not present: veriborder:self_visa`.

> **1.2.0 feature update — self-service visa kiosk and machine-controlled codes.**
>
> - New **Self-service Visa Machine** (`self_visa`): player chooses only a visa type, inserts a passport and issues. Country, issuing authority, dates, entries and code are admin-configured; no authority/template/code fields are exposed to players.
> - **Self-service Visa Machine and Self-service Endorsement Machine can set a fixed 8-digit issued code** in the brush config. If set, every credential issued by that kiosk uses that exact code, so one gate code works. Blank falls back to auto-generation.
> - The **HK & Macau Permit Issuer** can lock the initial endorsement code (`lockEndorsementCode` + 8-digit value) instead of auto-generating it.
> - Self-service fixed codes are intentionally allowed to repeat; auto-generated codes remain unique. The gate still matches the exact 8-digit code and accepts a shared machine code.
> - New block, 32-element kiosk model, textures, blockstate, item model, loot table and recipe. Now **21 machine models, 254 block textures, 26 recipes**.
> - The self-service player UI contains only the preset type + Issue; all authority/template/code/entries/date/renew/revoke/delete controls are hidden and server-ignored.
>
> **1.1.5 feature update — 8-digit credential codes and locked issuer fields.**
>
> - Every visa and endorsement carries an **8-digit numeric code**; auto-generated codes are unique, while an administrator may intentionally pin a shared fixed code. Old records get a deterministic legacy code. The admin can type a custom code in the visa/endorsement/issuer screens, or leave blank for auto-generation.
> - Passports can carry **visa-free 8-digit codes** directly in NBT (Modifier → visa tab → visa-free manager), with no physical visa page.
> - E-Gate validation is now **code-only**: the gate stores an unlimited list of 8-digit codes and matches them against active visas, active endorsements and passport visa-free codes. Names/types/authorities no longer affect the match.
> - Four gate modes: **All documents / Visa only (incl. visa-free) / Endorsement only / Visa or endorsement**. Legacy `any`/`passport`/`permit` values normalize automatically.
> - Issuer/endorsement machines can **lock document name and issuing authority** via the brush config. Normal players see those fields locked and cannot override them; the server enforces the lock even against a forged packet.
> - The **self-service endorsement machine** is isolated from staff machines: player UI offers only the preset endorsement type + Issue. Authority, templates, code, renew/revoke/delete are hidden and server-ignored; admins set its authority via brush config.
>
> **1.1.3 hotfix:** fixed the E-Gate collision shape for all four facings (east/west now have their cabinet strips on the Z edges and a passable centre). Fixed in-game flicker by switching gates to the **cutout** render layer, removing every overlapping/coplanar element from the gate models (AABB overlap count now 0 for both gates), and moving the BER status lenses in front of the cabinet shell.

> **1.1.2 hotfix:** the E-Gate was rebuilt as a **waist-high single-lane ticket barrier**: two 4 px cabinets straddling the controller block, a 12 px clearance, 16 px body, angled card reader, screen, orange/green base strip and ENTRY/EXIT arrow signs. The two 6×9 glass wings are drawn by the smooth BER animation.

> **1.1.1 hotfix:** the **Exit E-Gate** checks only the document itself (valid, not lost/cancelled/suspended/expired/tampered). It no longer requires a visa or endorsement, ignores any entry binding, and does not consume visa/endorsement entries. Entry-gate visa/endorsement binding is unchanged.

## What changed in 1.1.0

- **Entry and exit E-Gates.** A new `exit_gate` joins the entry gate. Both are **20 px single-lane barriers**: two 4 px cabinets, a 12 px passage, 16 px waist-high body, angled card reader, screen, coloured base strip and ENTRY/EXIT arrow sign. Two 6×9 transparent glass wings are eased and partial-tick interpolated by the BER; the OPEN block state remains authoritative for collision and animation.
- **Gate visa/endorsement binding (entry gate).** Each entry gate can be configured as *Any valid document*, *Passport + visa*, or *Permit + endorsement*, optionally requiring a specific visa type (tourist/business/student/work/family/other) or endorsement type (one-year multiple / three-month single / other). A traveller without the configured record is rejected before the gate animates, and the message names the required type. On success the matching record is consumed. The **exit gate** deliberately ignores this binding: leaving the country only requires a valid document.
- **Device Configuration Brush.** Only a player holding the new `device_brush` can open a terminal's configuration screen. Normal right-click keeps the document/operational behaviour; all old “Config” buttons and the no-document config shortcut were removed server-side.
- **Self-service Endorsement Machine.** A new `self_endorsement` kiosk issues, renews and revokes endorsements on a held/placed permit, with its own model, textures and recipe, distinct from the staff endorsement machine.
- **Endorsement type consolidation.** Group Tour and Individual Tour are no longer separate selectable types; old `team`/`individual` NBT records are still readable and resolve to **Other**. The selectable set is now One-year Multiple / Three-month Single / Other.
- **20 machine models, 25 recipes.** The two new devices and the brush have their own models, blockstates, item models, loot and recipes; `tools/preview_models.js` validates all 20 models with `FAILURES: none`.
- Hotfix 2 remains included: explicit GUI backgrounds, responsive scaling, labelled inputs, custom document names and the reworked 18 original machines.

## What changed in hotfix 2

- **Interactive GUIs actually render.** In 1.20.1 `Screen#render` does not call `renderBackground`; every VeriBorder screen now draws its own opaque panel explicitly. This fixes the “right-click does nothing” symptom on documents and machines.
- **Readable, responsive interface.** Opaque/semi-transparent panels, a labelled input well and hint for every field, and a global UI downscale so the 520×360 / 482×310 layouts fit a 427×240 viewport (854×480 at GUI scale 2) with clickable bottom tabs. Scissor rectangles follow the pose transform.
- **Custom document names everywhere.** A new `displayName` NBT field overrides the official template title. The Generator, all three Issuer GUIs and the Universal Modifier expose it; it appears on the item name, tooltip, document card, reader results and print preview.
- **All 18 machine models rebuilt.** Each device now has a bespoke multi-part model and its own 12-texture set (body, trim, screen, accent, LEDs, glass, paper, warning, …) instead of a shared cube. Light industrial palettes are differentiated per machine; screens, buttons, card slots, vents and feet are modelled individually.
- **E-Gate rebuilt.** White/light-grey pedestals, angled tops, glass insets, card reader, LED strips and an overhead light bar. The transparent glass wings slide open from the OPEN block state, so the visible animation always matches the collision shape; four-state lights, scanning beam, sounds and particles are retained.
- **Verified in-game.** Automated client acceptance covers 27 GUI screens (panel coverage, text, labels, field/button collisions, tab clicks, long-text clipping), all 18 block-item models, right-click with a document, right-click readers/issuers, the modifier generate/edit chain, and E-Gate open/close (progress 1.00 → 0).

All documents are ordinary items whose complete state lives in **item NBT**. The official layouts are only presets: every holder field, number, date, photo layout, visa, endorsement and stamp can be edited in-game. Every reader performs strict validation of type, dates, document status, visa/endorsement validity, destination and NBT integrity.

> 中文简介：本模组复刻身份证、护照、港澳通行证、签证、签注和出入境盖章体系，并提供发行、读取、核验、盖章、挂失、续期、打印预览与黑名单查询设备。全部证件数据都存在物品 NBT 中；官方版式只是默认模板，玩家可通过万能证照修改器自由修改所有字段、版式、签证、签注和出入境记录。

---

## Requirements

| Component | Version |
|---|---|
| Minecraft | 1.20.1 |
| Forge | 47.x (built against 47.2.0) |
| Java | 17 |

## Build

```bat
gradlew.bat build
```

The reobfuscated mod jar is produced at:

```text
build/libs/veriborder-1.2.0-hotfix2.jar
```

The project also contains domain tests for the NBT model, integrity signing and validation rules:

```bat
gradlew.bat test
```

## Installation

1. Install Minecraft Forge 47.x for 1.20.1.
2. Put `veriborder-1.2.0-hotfix2.jar` into the `mods` folder.
3. Launch the game. The creative tab **VeriBorder · Documents & Terminals** contains every document sample and every machine.

---

## Document system

### Identity cards — 2 official layouts

- PRC Resident Identity Card (`cn_id`)
- Hong Kong Permanent Identity Card (`hk_id`)

Fields: name, Latin name, document number, date of birth, sex, address, issuing authority, issue date, expiry date, nationality, place of issue, status.

The mainland number generator creates a valid 18-digit number with the official mod-11-2 check digit. The Hong Kong number generator creates a check-digit-valid format such as `A123456(3)`. Hong Kong permanent cards use a permanent validity date.

### Passports — 5 official layouts

- People's Republic of China Passport
- Hong Kong SAR Passport
- Macao SAR Passport
- United States Passport
- Japan Passport

Fields: holder, Latin name, passport number, nationality, date of birth, sex, place of issue, issuing authority, issue date, expiry date, status, plus unlimited visas and unlimited entry/exit stamps. A TD3-style 44-character MRZ is regenerated from the current fields every time the document is signed.

### HK & Macau Exit-Entry Permit

Fields: holder, permit number, dates, authority, status, plus unlimited endorsements of these types:

- One-year Multiple Entry
- Three-month Single Entry
- Other (legacy `team` / `individual` records also resolve here)
- Other

### Visas

Visas are **not separate items**. They are stored inside the target passport NBT and are limited only by NBT size:

- issuing country
- visa type (tourist / business / student / work / family visit / other)
- number of entries (`0` = unlimited)
- used entries
- issue date
- expiry date
- issuing authority
- status (active / revoked / expired)

### Endorsements

Endorsements are stored inside the HK & Macau permit NBT with type, entries, used entries, dates, authority and status. Both the staff endorsement machine and the self-service kiosk can issue, renew and revoke them. The permit issuer can attach an initial endorsement during issuance.

### Immigration stamps

Entry and exit stamp machines append records to a passport's NBT:

- direction (entry / exit)
- port / checkpoint
- country / region
- date
- officer

The stamp history terminal reads the entire history.

### NBT integrity

Each document contains a deterministic SHA-256 `integrity` value over every field. The modifier recalculates it after legitimate edits; changing NBT externally makes every reader and the E-Gate report a tamper failure.

---

## Machine catalogue

### Issuing devices

| Block | Function |
|---|---|
| Identity Card Issuer | Issue mainland or Hong Kong IDs from a form. |
| Passport Issuer | Issue any of the five passport layouts. |
| HK & Macau Permit Issuer | Issue a permit plus an optional initial endorsement; the initial endorsement code can be locked to an 8-digit value. |
| Visa Issuing Terminal | Write visas directly into a held/inventory passport; save templates and batch-issue every saved template. |
| Endorsement Processing Machine | Staff machine: issue, renew and revoke endorsements on a permit. |
| Self-service Visa Machine | Self-operated kiosk: choose a visa type, insert a passport and issue. Country/authority/code/dates are admin-set; player UI has no authority, template or code fields. |
| Self-service Endorsement Machine | Self-operated kiosk: choose an endorsement type and issue. No authority/template/code/renew/revoke/delete controls; admin sets a fixed code via brush config. |

All issuers have forms, preset save/apply/delete, validation and bilingual labels. Presets are stored in the block entity NBT, while every issued document's data is stored in the resulting item NBT. Self-service kiosks expose only the type choice; their issuing authority and fixed code are configured with the Device Configuration Brush.

### Reading and verification devices

| Block | Function |
|---|---|
| Hotel Check-in Card Reader | Accepts IDs, passports and permits; shows validity and all details. |
| Desktop Document Scanner | Full-screen inspection of all visas, endorsements and stamps. |
| Manual Verification Terminal | Deep border-counter inspection. |
| Airport Boarding Verification Gate | Passport + matching valid visa; destination configurable per terminal. |
| Station Real-name Verifier | Identity cards only. |
| Border Self-service E-Gate | 20 px single-lane barrier: two cabinets, 12 px clearance, 16 px waist-high body, angled card reader, screen, ENTRY sign, orange base strip, two animated 6×9 glass wings, four states, sounds and particles. Validates an unlimited list of 8-digit codes against visas, endorsements and passport visa-free codes; four modes: all documents / visa only / endorsement only / visa or endorsement. |
| Border Exit E-Gate | Exit-side variant with green base strip and EXIT signage; same animation/collision, checks only document validity and never requires or consumes a visa/endorsement. |

### Stamp and service devices

| Block | Function |
|---|---|
| Entry Stamp Machine | Right-click with a passport to append an entry stamp. Configurable port, country and officer. |
| Exit Stamp Machine | Same for exit stamps. |
| Stamp History Terminal | Read-only full travel history. |
| Loss & Cancellation Terminal | Mark lost / cancelled / suspended / restore valid. Every reader honours the blacklist. |
| Document Renewal Machine | Extend validity and refresh the issue date. |
| Document Print Preview | High-resolution layout preview with MRZ and integrity report. |
| Border Blacklist Terminal | Audit every carried document and its blacklist status. |

### Device Configuration Brush

Right-click a terminal while holding the **Device Configuration Brush** to open its configuration screen
(port, country, officer, airport destination; for gates also the document mode and required visa/endorsement
type). Without the brush, right-click keeps the normal document/operational behaviour. The brush is
craftable from a feather, a stick and a gold nugget. Terminal configuration cannot be opened or saved
without it.

---

## Universal Document Modifier

Right-click with the modifier to open the universal generator/editor. If the inventory has no documents, the generator opens directly; the **Generate** button is also available inside the editor.

**Generate directly into the inventory**
- Give the document its own **Document name** (stored as `displayName`); leave it empty to use the official template title.
- Choose the base kind: identity card, passport or permit.
- Choose an official template, or enable **Custom cover**.
- Custom documents have their own cover name, short name, document type, and a 24-bit cover colour (`#RRGGBB`) with a palette shortcut.
- Fill in the holder fields and click **Generate Document**; the resulting item is added to the player's inventory.
- Custom covers use a tinted item model, while the GUI card, border, status and type use the chosen colour.

**Edit carried documents**
- Edit the document name independently of the official layout or custom cover.
- Switch any official layout for the document kind, or apply/reset a custom cover.
- Edit all profile fields, including status and sex.
- Add, edit and delete unlimited visas.
- Add, edit and delete unlimited endorsements.
- Delete individual stamps or clear the complete stamp history.
- Save the complete document; the server re-signs the NBT integrity value.

---

## Interaction summary

- **Right-click a document item** → full document viewer. Identity cards show only **Profile**; passports show **Profile / Visas / Stamps**; permits show **Profile / Endorsements / Stamps**.
- **Right-click a machine** → its operational GUI. Readers use the held document (main hand or off hand).
- **Right-click a machine with the Device Configuration Brush** → its configuration GUI (ports, countries, officers, destination, gate binding). Configuration cannot be opened or saved without the brush.
- **Right-click an entry/exit stamp machine with a passport** → immediate stamp + result screen.
- **Right-click an E-Gate with a document** → verification animation. An **entry** gate may be configured for passports (matching active visa, optionally a specific visa type), permits (matching active endorsement, optionally a specific type) or any valid document; wrong documents are rejected before the wings move. An **exit** gate only checks that the document itself is valid and never requires or consumes a visa/endorsement.
- **Sneak is not required** for any operation.

## Data layout (abridged)

```text
ItemStack.tag
├── CustomModelData             # layout model selector
└── VeriBorder
    ├── schema                  # 1
    ├── kind, style
    ├── name, nameLatin, number, sex, birthDate
    ├── address, authority, issueDate, expiryDate
    ├── nationality, placeOfIssue, status
    ├── mrz1, mrz2
    ├── visas: [ { id, country, type, entries, usedEntries,
    │               issueDate, expiryDate, authority, status } ]
    ├── endorsements: [ { id, type, entries, usedEntries,
    │                     issueDate, expiryDate, authority, status } ]
    ├── stamps: [ { id, direction, port, country, date, officer } ]
    ├── displayName              # optional custom name overriding the template title
    ├── customStyle, customName, customShortName, customType, customColor
    └── integrity               # SHA-256 over all other fields
```

Terminal configuration (port, country, officer, airport destination, saved templates) is stored in each terminal block entity's NBT, so it survives world saves and is not global state.

## Bilingual support

Every item, block, tooltip, button, message, verification reason and GUI label has `en_us` and `zh_cn` translations. Minecraft's language setting selects the language.

## License

MIT. See [LICENSE](LICENSE).

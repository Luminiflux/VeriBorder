# VeriBorder 26.2

The first stable release after 26.1. This version reworks most of the border mechanics and completes the UI, registry and server compatibility work.

26.1 之后的第一个正式版。这一版几乎重做了边境机制的核心部分，并把界面、注册体系和服务端兼容性补齐。

## Border mechanics / 边境机制

**Gate mode `all` removed.** Only `visa`, `endorsement` and `both` remain; legacy values migrate to `both`. Entry now always requires a visa, an endorsement or a permanent code instead of "any valid document".

**移除闸机 `all` 模式。** 只保留 `visa`（签证）/ `endorsement`（签注）/ `both`（两者），旧值自动迁移为 `both`。入境必须命中签证、签注或永久编码，不再"任何有效证件放行"。

**One person, many documents.** Entry records are keyed by the traveller (name + date of birth) and stored as `BorderEntry`. A renewed passport can leave on the old one, while a same-name different-birthday holder is never treated as the same person. Legacy `sessions` migrate to `entries`.

**一人多证。** 入境记录改为按"人"（姓名 + 出生日期）建模，新增 `BorderEntry`。换发护照后可用旧护照出境；同名但生日不同不会被误判为同一人。旧存档的 `sessions` 自动迁移到 `entries`。

**Entry and exit are configured separately.** An entry gate only offers its entry mode, entry codes and entry visa-free country list; an exit gate only offers its exit codes and exit country list. The exit stage of a dual-clearance gate shares the exact same check as a standalone exit gate.

**出入境方向分离。** 入境闸机只配入境查验模式、入境编码、入境免签国家/地区白名单；出境闸机只配出境编码、出境放行国家/地区白名单。一地两检出境段与单出境闸机共用同一套判定。

**Exit consumption fixed.** Exit code matching is read-only; after it passes, only the record bound at entry is consumed and `usedEntries` increases by exactly one.

**出境核销修正。** 出境编码校验为只读匹配，通过后只核销入境时绑定的那一条记录，`usedEntries` 只 +1。

**Expired travel is not trapped.** Documents, visas and endorsements that expired or ran out still allow exit; the crossing is stamped `OVERSTAY` and logged. Entry remains strict about expiry.

**过期/逾期允许出境。** 证件、签证、签注过期或次数用尽仍可出境，标记 `OVERSTAY` 并写日志，不会把人困在境内；入境仍严格拒绝过期。

**Dual clearance.** Both stages are validated read-only before anything is committed; the two stamps can carry their own authority, port, officer and timestamp.

**一地两检。** 两个阶段先只读校验再提交；两枚印章可各自配置机关、口岸、官员与时刻。

## Documents and registry / 证件与注册

**Issuing country is the MRZ code.** A single `issuerCountry` field feeds MRZ line 1 positions 3–5 and is stored for ID cards and permits as well. The issuer's nationality and country are one selector.

**签发国 = MRZ 三字码。** 统一为单一字段 `issuerCountry`，护照 MRZ 第 1 行 3–5 位直接读取；身份证与通行证同样记录。发证机的"国籍"与签发国合并为一个选择器。

**New registry item.** `credential_registry` registers countries/regions (three-letter code, Chinese and English names, default nationality) and server-wide document templates. Entries are synced to every client and shared by issuers, gate whitelists and templates.

**新增注册器物品。** `credential_registry` 登记国家/地区（三字码、中英文名、默认国籍）与全局证件模板；数据同步到所有客户端，发证机、闸机白名单、模板共用。

**Templates are kind-scoped.** A template belongs to ID card, passport or permit and only appears in that kind's issuing machine. Colours and icons use real pickers.

**模板按证件类型隔离。** 模板归属身份证 / 护照 / 通行证，只在对应类型的发证机出现；颜色与图标使用真正的选择器。

**Permanent codes upgraded.** Each code carries `{code, issuerCountry, purpose, expiry}`, capped at 16, matched by `return_home` / `visa_free` against the gate country. The UI folds them into an "advanced (case-by-case exemption)" section.

**永久编码升级。** 每条编码带 `{code, issuerCountry, purpose, expiry}`，上限 16 条，按 `return_home` / `visa_free` 匹配闸机国家；界面上收进"高级（个案豁免）"。

**Stamps record the time.** `StampRecord` gained `HH:mm:ss` and an epoch; older stamps show `--:--`.

**印章记录时刻。** `StampRecord` 增加 `HH:mm:ss` 与 epoch，旧印章显示 `--:--`。

## Interface / 界面

**One dropdown component everywhere.** Every control with three or more options is now a dropdown (layout, sex, gate mode, document status, visa/endorsement type, template kind and more).

**统一下拉组件。** 所有 3 项以上的循环按钮改为下拉（发证机版式、性别、闸机模式、证件状态、签证/签注类型、模板类型等）。

**Seven-page document viewer.** Identity, MRZ, visas, endorsements, stamps, history and integrity, with photo placeholder, monospace MRZ strip, visa stickers, stamp ink and a barcode.

**证件查看器 7 页。** 身份资料 / 机读区 / 签证 / 签注 / 印章 / 历史 / 完整性，含照片占位、MRZ 等宽条、签证贴纸、印章墨迹、条码。

**Custom document names are consistent.** A locked or custom title replaces the cover, the header, the item name and the issue message, and never mixes with the holder's name.

**自定义证件名一致。** 锁定或自定义的证件名会替换封面、标题、物品名与签发提示，与持有人姓名严格区分。

**Derived credential states.** Visas and endorsements show valid, exhausted, expired or revoked; gates only accept valid records with remaining entries.

**派生态。** 签证/签注显示"有效 / 已用完 / 已过期 / 已吊销"，闸机只接受有效且有余次的记录。

**Two-tier refusal reasons.** Travellers read natural sentences ("You have no entry record, please use the entry lane"), while officers and logs keep the reason code.

**拒绝理由双轨。** 旅客看到自然语言（"您还没有入境记录，请先走入境通道"），管理员与日志保留原因代码。

**Copy brush.** Sneak-right-click an issuing machine to copy its configuration, then right-click another machine of the same type to paste it. The device brush now opens the dual-clearance editor directly for dual gates.

**复制刷。** 潜行右键发证机复制配置，右键同型号发证机粘贴。设备配置刷指向一地两检时直接打开双检配置界面。

## Engineering / 工程

**Network protocol 3.** A 26.1 client is refused with a clear message; new packet fields are null-safe on decode.

**网络协议升到 3。** 26.1 客户端会被明确拒绝并提示升级；新增包字段解码做空值兜底。

**Schema migrations.** Document, device and border data migrate automatically; a tampered document is neither migrated nor re-signed.

**schema 迁移。** 文档、设备、边境数据自动升级；被篡改的文档不迁移、不重签。

**Caching and persistence.** Signed template cache, batched border writes every 100 ticks and a forced flush on shutdown.

**缓存与落盘。** 签名模板缓存、边境数据每 100 tick 批量落盘、关服强制 flush。

**Diagnostics and tooling.** `/veriborder diagnose` reports protocol matrix, schemas, registries, template cache and session state. Idempotent asset generators, a language-key parity check and a model golden manifest are included; one-off scripts moved to `tools/archive/`.

**诊断与工具链。** `/veriborder diagnose` 输出协议矩阵、schema、注册表、模板缓存与会话状态；提供幂等资源生成入口、语言 key 一致性检查、模型 golden 清单；一次性脚本归档到 `tools/archive/`。

**Dedicated server smoke test.** A scripted smoke run verifies startup and checks for client-class leakage.

**专用服务端冒烟。** 脚本化启动验证，并检查客户端类泄漏。

## Tests / 测试

299 automated tests pass, covering document validation, border flow, dual clearance, multi-document travellers, renewed-passport exit, registry, templates, protocol, migrations and localisation parity. The dedicated server starts cleanly with no client classes loaded.

299 项自动化测试全部通过，覆盖文档校验、边境流转、一地两检、一人多证、换发出境、注册器、模板、协议、迁移与语言一致性。专用服务端启动正常，未加载任何客户端类。

## Upgrade notes / 升级说明

- Server and client must both run 26.2; older versions are refused by the protocol check.
- Saves migrate automatically; back up your world and `config` before upgrading.
- `mrzCountry` remains readable for compatibility, but every new write uses `issuerCountry`.

- 服务端与客户端必须同时升级到 26.2；旧版本会被协议校验明确拒绝。
- 旧存档会自动迁移；升级前建议备份世界与 `config`。
- `mrzCountry` 旧字段仅保留只读兼容，新写入统一使用 `issuerCountry`。

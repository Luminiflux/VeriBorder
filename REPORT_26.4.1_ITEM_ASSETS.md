# VeriBorder 26.4.1 — 物品资产升级报告（设备/工具类全量 19 台）

更新时间：2026-10-25  ·  范围：**除闸机外的全部设备类 / 工具类物品（19 台）**
状态：**4 项验收全部 PASS**；`check_generators -Only items` 幂等无漂移；设备/工具线已无剩余工作。

---

## 0. 验收

| 验收命令 | 结果 |
|---|---|
| `powershell -File tools/generate_item_textures.ps1 -AuditOnly` | **PASS** — `ALPHA_BINARY (72 textures) + ITEM_OVERLAP (19 item model(s), 0 conflicts)` |
| `node tools/preview_models.js` | **exit 0 / FAILURES: none**（22 block models 未受影响） |
| `node tools/scan_coplanar.js` | **TOTAL 0** |
| `node tools/audit_item_assets.js` | **PASS** — 183 个资产（19 个专用 3D item model + 92 个平面 item model + 72 张 item 贴图） |

附带：`tools/check_generators.ps1 -Only items` → **IDEMPOTENT + NO DRIFT**；`-Only devices` → **IDEMPOTENT**（drift 仅历史遗留警告，见 §8）。验收日志：`item-upgrade-acceptance.log`。

---

## 1. 边界与归属

### 1.1 没有碰的东西
`assets/models/*gate*.bbmodel`、`models/block/{egate,exit_gate,dual_gate}.json`、`textures/block/*gate*`、
`models/item/{egate,exit_gate,dual_gate}.json`、`blockstates/*gate*`、**任何** `textures/block/**`、Java 源码。
`tools/generate_assets.ps1` 只按授权删除了**物品图标**那两段（§1.3），block 侧逻辑一行未改。

### 1.2 证件类：**生成器归属已正式移交**
以下 11 张 `textures/item/*.png` 的**生成器归属已移交证件渲染线**（双层可染色 + 徽记 + 自定义贴图）。本线不再生成、不再修改：

```
id_card_cn.png       id_card_hk.png        hk_macao_permit.png
passport_cn.png      passport_hk.png       passport_mo.png
passport_us.png      passport_jp.png       custom_id_card.png
custom_passport.png  custom_permit.png
```

* 本线在边界调整前已重绘落盘，按要求**保留不回退**；此后未再写入，现已被证件线的新版本取代。
* `models/item/` 下的证件模型（`id_card*`、`passport*`、`hk_macao_permit`、`custom_*`、`*_slot_N`）**归属同样已移交**，本线从未修改；证件线现在用 `"parent": "builtin/entity"`（BEWLR）实现双层可染色（本线审计已支持识别 `builtin/*`）。
* **⚠ 交接风险（证件线必须补）**：`check_generators -Only items` 会先清空 `textures/item/` 再重生成，而 items 步骤现在只产 37 张设备/工具贴图 → 干净 scratch 树里这 11 张证件**不会被重新生成**。证件线需把它们纳入自己的生成器，否则“删目录重生成”会丢图。
* **⚠ 另一处覆盖源**：`tools/generate_assets.ps1` 里的 `New-CardTexture` / `New-CustomDocTexture`（约 490-578 行，受 `-NoDocuments` 控制）**仍会画这 11 张旧证件**。`generate.ps1` 的 devices 步骤永远带 `-NoDocuments`，正常链路安全；但**手工直接跑 `generate_assets.ps1`（不带 -NoDocuments）会覆盖证件线的新贴图**。建议证件线接管时把这段删掉；本次按“只删那两段”的授权未动。

### 1.3 已删除的重复绘制（本轮执行）
`tools/generate_assets.ps1` 中两段物品图标绘制已删除（**纯物品分支**，block 侧未动）：
* `# modifier icon` 段 → `document_modifier.png`（原约 580-594 行）
* `# ---- device brush icon ...` 段 → `device_brush.png`（原约 596-650 行）

替换为指向物品线的说明注释；两个物品的 **model json 写入保留**（只是模型定义，纹理由物品线提供）。
备份：`tools/archive/generate_assets.pre-2641-item-removal.ps1`。
效果：`generate.ps1 -Only devices` 不再回退这两个图标；devices 步骤仍 **IDEMPOTENT**。

---

## 2. 贴图（本线拥有 37 张，全部 32×32 / 二值 alpha）

### 2.1 统一美术系统（`tools/lib_item_art.ps1` + `tools/lib_item_font.ps1`）
* **一套调色板**：磨砂白机身 / 拉丝金属 / 深蓝玻璃 / 青色 LED / 纸 / 木 + 金（`IA-P`）。
* **单侧主光（左上）**：`IA-Lit` / `IA-Edge` / 所有 ramp 上+左提亮、下+右压暗。
* **1px 深色描边** `#0A0E14`；笔刷类用**逐 alpha 轮廓描边**（行渲染的斜向缺口已修）。
* **alpha 二值**：绘画只用不透明色；`IA-HardenAlpha` 保存时兜底并清零 `a=0` 的 RGB（防 mipmap 拖黑边）。
* **屏幕文字用 3×5 点阵字体**（GDI+ 在 6px 以下会糊且有 hinting 差异；`M`/`N` 已拉开差异）。

### 2.2 工具类图标（7 张）

| 贴图 | 前 | 后 | 说明 |
|---|---|---|---|
| device_brush | 32×32 | 32×32 | 重绘：青绿刷毛 + 拉丝箍 + 木柄 + 轮廓描边 |
| config_copy_brush | **16×16** | **32×32** | 蓝色标识环 |
| gate_config_brush | **16×16** | **32×32** | 琥珀标识环 |
| dual_gate_brush | **16×16** | **32×32** | 绿色标识环 |
| credential_registry | **16×16** | **32×32** | 深蓝账本 + 金箍 + 书脊 + 页口 + 火漆 + LED |
| border_terminal | 32×32 | 32×32 | 磨砂白 + 拉丝顶盖 + 玻璃名册 + 通风槽 + LED |
| document_modifier | 32×32 | 32×32 | 纸 + 折角 + 金色笔尖钢笔 + 握环 + LED |

### 2.3 物品屏幕（19 张，逐台差异化 —— 没有两台共用同一块屏）

| 设备 | 类别 | 屏幕内容 | 标题 |
|---|---|---|---|
| id_issuer | issuer | 证件卡缩略 + 数据行 + MRZ | ISSUE |
| passport_issuer | issuer | 护照本缩略 | ISSUE |
| permit_issuer | issuer | 许可证缩略 | ISSUE |
| visa_issuer | issuer | 签证页 + 斜色带 + QR | ISSUE |
| self_visa | issuer | 签证页 + 斜色带 + QR | ISSUE |
| endorsement_machine | issuer | 加注表单 + 火漆环 | ISSUE |
| self_endorsement | issuer | 加注表单 + 火漆环 | ISSUE |
| desk_scanner | reader | 证件卡 + 扫描线 + **绿勾/红叉** | VERIFY |
| hotel_reader | reader | 护照页 + MRZ + **放大镜** | SCAN |
| manual_terminal | reader | 证件卡 + **数字键盘 3×3** | INPUT |
| station_verifier | reader | 证件卡 + 绿勾 + **排队计数条** | QUEUE |
| airport_checkin | reader | **登机牌**（缺口 + 条码） | CHECK |
| entry_stamp / exit_stamp | stamp | 印章环 + 星形 + 日期 | STAMP |
| stamp_history | stamp | **带日期的盖章记录行** | HISTORY |
| loss_terminal | terminal | 失物行 + 放大镜 | LOST |
| renewal_machine | terminal | **到期日历网格** + EXP 标记 | RENEW |
| blacklist_terminal | terminal | 名单行 + **红色大叉** | BLACK |
| print_preview | print | 纸张 + **进度条** | PRINT |

### 2.4 物品材质瓦片（11 张）
`item_shell`（磨砂白）、`item_trim`（拉丝金属）、`item_glass`（深蓝玻璃）、`item_led`、`item_paper`、`item_panel`（通风/插槽）、`item_accent_{blue,teal,amber,violet,red}`（5 个族色）。

> 为什么不复用 `textures/block/**`：那些机身贴图逐设备差异极大（`id_issuer_body` 均值 rgb(50,77,103) 深蓝、`desk_scanner_body` rgb(205,214,217) 近白、`entry_stamp_body` rgb(167,85,28) 橙棕），直接继承会让物品图标不成套，且发证机不会是要求里的“磨砂白机身”。

### 2.5 alpha 二值化修正像素数

| 图标 | 前 → 后 | 非二值像素 |
|---|---|---|
| config_copy_brush | 16×16 → 32×32 | **178 → 0** |
| custom_id_card / custom_passport / custom_permit | 64×64 → 64×64 | 各 **3 → 0**（已交接） |
| 其余（4 brushes / registry / terminal / modifier / 19 screens / 11 tiles） | → 32×32 | 0 → 0 |

旧管线在 `Save-Bitmap` 里已硬化过一次，多数文件本来就是 0；真实修正是 `config_copy_brush`（178 px，不走那条保存路径）与三张 custom 证件（各 3 px）。现在**全目录 72 张**都过 `ALPHA_BINARY`。

---

## 3. 模型（19 个专用物品模型，全部脱离方块模型）

| 原型 | 设备 | element 数 | 高度(px) |
|---|---|---|---|
| ISSUER TOWER | id_issuer / passport_issuer / permit_issuer / visa_issuer / self_visa / endorsement_machine / self_endorsement | 26 | 10.0 – 13.25 |
| READER WEDGE | desk_scanner / station_verifier / hotel_reader / manual_terminal | 22 | 7.6 – 8.85 |
| SERVICE KIOSK | airport_checkin / loss_terminal / renewal_machine / blacklist_terminal / print_preview | 20 | 9.4 – 10.9 |
| STAMP PRESS | entry_stamp / exit_stamp / stamp_history | 24 | 11.5 – 12.5 |

全部满足：
* **20–40 elements**、**`"gui_light": "front"`**、**无 `parent`**（本次升级的核心）；
* **无旋转元素**（轴对齐，避免 Iris 下的面剔除/法线问题）；
* **等距小样**：正面玻璃屏（贴图 1:1 映射）、侧面通风鳍/细节、**阶梯式斜切顶面**（3–4 级内缩叠箱）；
* **无共面重合、无被包裹内部体**（生成器内置校验，见 §4）；
* **display 段**用原版物品变换（gui `30/225/0` `0.625`；第三人称 `75/45/0` `+2.5y` `0.375`；第一人称 `0/45/0` `0.4`；ground/fixed 齐备）→ 不穿手、不溢出格子。

---

## 4. 光影（Iris / OptiFine）安全

### 4.1 已自动门禁
1. **`ALPHA_BINARY`**：全画布扫描 `0<a<255` 与（`a==0` 但 RGB≠0），逐张报数。羽化边会让整张图标掉进排序半透明通道 → 透视/漏光。
2. **`ITEM_OVERLAP`**：`models/item/*.json` 逐 element 两两检查
   * **同向共面**：同轴 `a.max==b.max` 或 `a.min==b.min` 且另两轴重叠 >0.02 → z-fight / 漏光；
   * **完全包裹**：一个 element 被另一个包住（>0.01 余量）→ 六面全不可见。
   * 背靠背贴合（`a.max==b.min`）允许，与原 `scan_coplanar.js` 口径一致。

### 4.2 `RenderType.translucent → cutout`：核实结论
你的判断正确：**1.20.1 的 `ItemBlockRenderTypes` 只有 `setRenderLayer(Block, RenderType)`，没有按 Item 的重载**，改 Block 会连带方块玻璃。我查了 Forge 47.2 的 `IClientItemExtensions` 源码，结论：

**唯一干净的替代是 BEWLR，但代价不小**
* `IClientItemExtensions.getCustomRenderer()` 确实存在，但源码注释明确：**只有 `BakedModel.isCustomRenderer()` 返回 true（或 blockstate 为 `ENTITYBLOCK_ANIMATED`）时才会被调用**。
* 完整方案 = `IClientItemExtensions` + 包装原模型的 `BakedModel`（`isCustomRenderer() → true`，其余委托）+ `BlockEntityWithoutLevelRenderer` 手工 `getQuads(...)` 并按 `RenderType.entityCutout(atlas)` 提交。约 100–150 行，19 个物品逐个注册。
* **代价**：绕开原版物品渲染链（附魔光效、`overrides` 自定义模型数据都要自己处理）。

**建议：保持现状（不改）。** 依据不是妥协，而是**物品侧现在已无半透明像素**：
* flat 图标（`item/generated`）只有 1 个 quad → 无线内排序问题；
* 19 个专用 3D 物品模型所有 quad 都不透明（屏幕是**不透明**贴图，不是玻璃片）→ alpha 混合在数学上是恒等变换；
* Minecraft 在**物品/实体批次内不做逐 quad 深度排序**（排序只发生在区块 section），所以 translucent 对物品没有可见副作用。

也就是说：**“物品看起来透视”的原始 bug 是贴图层面的羽化 alpha，二值化已根治**；方块侧继续用 translucent（玻璃需要）没有连带问题。除非将来物品模型里要放**真的半透明玻璃片**，才值得上 BEWLR。

`EGateRenderer.java` 的 `entityTranslucent` 属闸机线，不在本范围。

---

## 5. 工具

**新增**

| 文件 | 作用 |
|---|---|
| `tools/lib_item_art.ps1` | 物品美术库：调色板、左上主光原语、玻璃/拉丝/纸张/LED、轮廓描边、19 种屏幕画法、11 张材质瓦片 |
| `tools/lib_item_font.ps1` | 3×5 点阵字体（屏幕标题/日期/IN-OUT），确定性、无 hinting 依赖 |
| `tools/generate_item_models.js` | 19 个专用物品模型生成器 + **内置几何校验**（共面/包裹/边界/元素数/贴图解析），非 0 退出即失败 |
| `tools/audit_item_assets.js` | 独立审计：所有 `models/item/*.json`（合法 JSON、`gui_light`、20–40 元素、`display`、无旋转、无共面/包裹、贴图解析、parent 解析**含 `builtin/*`**）+ 所有 `textures/item/*.png`（PNG 解码、非空、32/64、alpha 二值） |
| `tools/preview_items.js` | 物品预览渲染器（2D 整数放大 + 3D 软件光栅化；`--models/--tex-item/--out/--ids` 可指向快照） |
| `tools/preview_items.ps1` | 前后对比图生成 |

**扩展**：`tools/generate_item_textures.ps1` — 新增 `ALPHA_BINARY`、`ITEM_OVERLAP`，**保留原表格列与格式**；绘制逻辑迁入 `lib_item_art.ps1`（不再从 `generate_assets.ps1` AST 抠函数）。

**修改**：`tools/generate_assets.ps1` — 仅删除两段物品图标绘制（§1.3）。

---

## 6. 预览图

| 文件 | 内容 |
|---|---|
| `tools/preview/items_devices_compare.png` | **19 台设备 + 刷子的 BEFORE ‖ AFTER 全量对比**（4 列 × 5 行） |
| `tools/preview/item_before/` | 升级前快照（models / textures / render） |
| `tools/preview/item_after/<id>.png` | 每个物品的新渲染（512×512） |
| `tools/preview/item_after/tex/` | 单张贴图整数放大 + `_screens_sheet.png`（19 屏对照） |

---

## 7. 剩余工作

设备/工具类**已全部完成（19/19）**。剩余相关事项都在别的线上：
* 证件 3 类 + 11 张贴图 + `models/item` 证件模型 → 证件线（含把 11 张纳入其生成器、删掉 `generate_assets.ps1` 的旧证件绘制，见 §1.2）。
* 闸机 3 个物品 → 闸机线（本线审计对它们豁免结构检查，只查 JSON/parent/贴图，不会误报）。

---

## 8. 备注

1. `tools/audit_item_assets.js` 把**整个** `textures/item/` 纳入 alpha 二值 / 32-64px / 非空检查（含证件线的 35 张）。若证件线将来需要**故意半透明**的染色彩层，这条门禁会失败 —— 届时告知我，可把严格 alpha 规则限定到本线拥有的文件。
2. 为跑验收执行过 `node tools/preview_models.js`，它按当前闸机模型重写了 `tools/preview/{egate,exit_gate,dual_gate}.png`（无害）。
3. `check_generators -Only devices` 报 179 个历史遗留漂移（round-9 起的 block/entity 美术 + 闸机线改过的模型），属既有状态，非本轮引入；该步骤本身 **IDEMPOTENT**、退出码 0。`-Only items` 完全干净。

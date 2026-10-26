# VeriBorder 26.4.1 — 设备屏幕 UI 重绘（非闸机 19 台）

> 任务：让 22 台设备的屏幕不再长得一模一样。
> 范围：`textures/block/*_screen*.png` + 每个模型的 `screen` 纹理绑定；闸机不在范围内。

---

## 0. 结论

| 项目 | 结果 |
|---|---|
| 重绘屏幕贴图 | **38 张**（19 台 × `_screen` / `_screen2`），全部逐台独立设计 |
| 对照图 | `tools/preview/screens_before_after.png`（1250×2710，19 行网格 + 设备名） |
| 改动的模型文件 | 19 个 `models/block/<device>.json`，**每个只改 1 行**（`screen` 纹理指向） |
| 几何 / UV / blockstates / Java | **零改动**（`tools/verify_screen_changes.js` 逐字节断言） |
| `node tools/preview_models.js` | **exit 0**，`FAILURES: none`，14 条检查全 PASS |
| 所有屏幕 PNG | 合法 PNG、`8-bit RGBA`、**alpha 二值且全不透明**、边长 ∈ {32, 64} |

---

## 1. 先修根因：19 台设备根本没有用自己的屏幕贴图

这不是配色问题，是**绑定问题**。所有 19 个非闸机模型的 `textures` 段都是：

```json
"screen": "veriborder:block/r9_screen_ui"
```

19 台共用同一张 `r9_screen_ui.png` —— 这才是"看起来像同一台"的真正原因。
同时 `*_screen.png` / `*_screen2.png`（38 个）是**孤儿资源**：全仓库检索 `_screen` 引用，只有 egate / exit_gate / dual_gate 引用自己的屏幕。

所以只重绘 `*_screen.png` 游戏里不会有任何变化。本次按授权把每个模型的 `screen` 改指向自己的图（**只改这一行的值**，格式、缩进、elements、UV 全保留，git diff 每文件 1 行）：

```diff
-    "screen": "veriborder:block/r9_screen_ui",
+    "screen": "veriborder:block/id_issuer_screen",
```

`r9_screen_ui.png` **保留未删**；改完后 `src/main/resources` 下已无任何 `r9_screen*` 引用（已确认：物品模型没有引用它）。

> 附注：任务表里的"改件机（modifier）"只有物品模型 `models/item/document_modifier.json`、没有方块模型，所以不在这 19 台里。19 = 任务表 17 项展开后的非闸机方块全集（含 机场登机核验机、盖章记录查询终端 两台）。

---

## 2. 共享设计系统（所有屏幕同一套规则）

统一的是**语言**，不是画面。

| 规则 | 取值 |
|---|---|
| 字模 | 单一 **3×5 像素字格**，1px 描边，4px 步进（无第二套字体） |
| 描边 / 圆角 | 1px 描边 + 1px 切角（`round()`），内缩 1px |
| 状态色（全局同色） | 绿 `#3BF59B` 通过 · 黄 `#FFC23D` 待处理 · 红 `#FF4D6D` 拒绝 · 青 `#4DE1FF` 提示 |
| 底色 | `#06111C` / 面板 `#0A1D2C` / 抬升 `#102A3D` —— **亮度压在 0x14 以下** |
| 前景 | 主文近白 `#E8F6FF`、次级 `#6C93AB` |
| 强调色 | **每台一个专属色相**，用于标题、身份条、CTA |
| 发光感 | 深底（近黑）+ 高对比亮色元素；全屏无中间灰大面积色块 |
| alpha | 二值：255 或 0。本套屏幕**全部像素不透明**（屏幕是实体面板，透明会穿透机身） |

`tools/lib/screen_kit.js` 是这个系统的唯一实现（画布 / 字模 / 圆角描边 / 进度条 / 条码 / 印章圆环 / 警示三角 / 指纹环 / 双色 pip / 大对勾）。

---

## 3. 逐台设计要点

> `屏面比例` = 该设备模型里屏幕四边形 `dx : dy`（从 model json 实测）。
> `UV` 一列：**全部为 `[0,0,16,16]` 归一化百分比** —— 所以改分辨率完全不会破坏 UV，无需动模型。

| # | 设备 | 强调色 | 尺寸 | 屏面比例 | UV | 屏幕内容 / 设计要点 |
|---|---|---|---|---|---|---|
| 1 | 身份证发行机 `id_issuer` | `#4FA8FF` 天蓝 | 64×32 | 2.28 | 0..16 不变 | 左：证件缩略（人像剪影 + 打码字段条 + 版号条码）；右：3 行「标签条／值条」表单；底部整条 `ISSUE ›` 实心 CTA。 |
| 2 | 护照发行机 `passport_issuer` | `#E85D5D` 深红 | 64×64 | 1.09 | 0..16 不变 | 左上**双环 + 五角星国徽占位**；右上 3 组 TYPE/NAME/NO 字段；`PRINT` 62% 进度条；底部**两条 13 位 MRZ 样张**（等宽、斑马纹感）。 |
| 3 | 通行证发行机 `permit_issuer` | `#35D9C0` 青绿 | 64×32 | 2.16 | 0..16 不变 | 左**圆形签注章预览**（双环 + END）；右上 `X2` 次数徽章；右侧 `+ ADD` 加签按钮；底部 4 格次数 pip（3 满 1 空）。 |
| 4 | 签证签发机 `visa_issuer` | `#B980FF` 紫 | 64×32 | 2.37 | 0..16 不变 | 左：**带右下折角的贴纸签证**（横条文本块 + 折角斜边）；右上 `VALID` / `2029-12` 有效期；右下 29px 条形码。 |
| 5 | 签注办理机 `endorsement_machine` | `#FFB43D` 琥珀 | 32×64 | 0.66 | 0..16 不变 | **竖屏**：`TYPE` 三行单选（GRP 高亮 + 实心单选点 / IND / TRN 空心）；`TIMES` 4 格 pip；底部 `NEXT`。窄竖版型本身就是身份。 |
| 6 | 自助签证机 `self_visa` | `#6B7BFF` 靛蓝 | 64×64 | 0.82 | 0..16 不变 | 左侧**纵向步骤条** ①实心 ②③空心 + 连接竖线，标注 PICK TYPE / PAYMENT / PRINT；底部 `TOUCH TO START` 触摸条（自助机专属引导语言）。 |
| 7 | 自助签注办理机 `self_endorsement` | `#FF7CC6` 粉 | 64×64 | 0.93 | 0..16 不变 | **横向步骤条**（与自助签证同语言、方向相反）；两张 `ENDORSE 1X / 2X` 方案卡（首张选中）；底部**同心指纹环** + `TOUCH HERE`。 |
| 8 | 前台人工核验终端 `manual_terminal` | `#45E2FF` 青 | 64×64 | 1.52 | 0..16 不变 | **唯一的多人列表**：3 行旅客卡（头像 + 姓名条 + 证件条）+ **IN / OUT 状态徽章**（绿 = 入境、红 = 出境）；底部 `PAX 03`。 |
| 9 | 车站实名核验机 `station_verifier` | `#62E67E` 绿 | 64×64 | 1.03 | 0..16 不变 | **以物理插口为核心**：证件书（书脊 + 徽记 + 文字线）→ 粗壮实心下箭头 → 插槽三线开口；`INSERT DOC` / `PLACE DOC` 双提示。 |
| 10 | 入境盖章机 `entry_stamp` | `#FF8A45` 橙 | 64×32 | 2.00 | 0..16 不变 | 左：**圆形双环入境章**（ENTRY 字样）；右：`DATE` + `26-10-25` + **下箭头** + `IN` + `AUTO`。 |
| 11 | 出境盖章机 `exit_stamp` | `#FFDD4D` 亮黄 | 64×32 | 2.18 | 0..16 不变 | 入境章的镜像：**方形内框出境章**（内嵌上箭头 + OUT）在右，日期在左。**圆章 ↔ 方章、下箭头 ↔ 上箭头**成对。 |
| 12 | 酒店入住读卡器 `hotel_reader` | `#C77BFF` 紫罗兰 | 64×32 | 3.05 | 0..16 不变 | 最扁的超宽屏：证件卡图标 → `ROOM 1420` 房号 → 条码 + `AUTH OK` → 右侧**大面积绿色对勾圆盘**。 |
| 13 | 台式证件扫描仪 `desk_scanner` | `#3DFFB0` 薄荷 | 64×32 | 2.27 | 0..16 不变 | 横置证件卡 + 一道**高亮扫描光束横带**（含 1px 亮边）；右侧绿色对勾圆盘 = 读取通过（同位置换红叉即失败态）。 |
| 14 | 证件过期刷新机 `renewal_machine` | `#9BE04F` 黄绿 | 32×64 | **0.38** | 0..16 不变 | 全项目最窄的屏：**7 行复选框表单条目**铺满 + 右缘一条**纵向进度条** + `45%`。密度就是识别特征。 |
| 15 | 证件挂失注销终端 `loss_terminal` | `#FF6B4F` 珊瑚 | 64×32 | 2.45 | 0..16 不变 | 顶部**斜纹 hazard 警示带**；左侧红底**感叹号三角**；2 条表单条目；红色 `REPORT` 实心 CTA。 |
| 16 | 口岸黑名单查询机 `blacklist_terminal` | `#FF2E63` 洋红 | 64×64 | 1.30 | 0..16 不变 | **整条红色标题横幅**（近黑字压亮红底，最高警示级）；4 行命中记录（叉号 + 姓名条 + 命中条）；底部 `17 MATCHES` 红色汇总条。 |
| 17 | 证照打印预览机 `print_preview` | `#8FC7E8` 钢蓝 | 64×32 | 1.73 | 0..16 不变 | 左侧一张**亮白「纸」**（全屏最亮面：墨线 + 小圆章）= 打印页缩略；右侧 `QUEUE` 35% 进度 + `A4 01` + `READY`。 |
| 18 | 机场登机核验机 `airport_checkin` | `#4FD8FF` 天青 | 64×64 | 0.83 | 0..16 不变 | 整屏就是一张**登机牌**：撕线穿孔（含上下缺口）、`GATE 12` / `SEAT 22A` 用**2 倍字模**放大、右半为**竖排条码**。唯一用 scale-2 字模的屏。 |
| 19 | 盖章记录查询终端 `stamp_history` | `#E8C46B` 砂金 | 64×64 | 1.20 | 0..16 不变 | **唯一的真表格**：表头底纹 + 横纵网格线 + 5 行（日期／口岸／迷你章／IN-OUT）。查询类终端的气质与发行类完全不同。 |

### `*_screen2.png`（19 张）
非闸机模型没有 `screen2` 键，这 19 张目前**不被任何模型引用**。仍按同一设计系统重绘为**「校验通过」完成态**（设备强调色 + 专属短码 + 大对勾 + 满进度条），保证"所有屏幕类贴图"都不再是同一张图，也为将来接事件态留好资源。

---

## 4. 尺寸与 UV

- **约束来源**：`tools/preview_models.js` 第 534 行硬断言 `每个被声明的贴图边长必须是 32 或 64`。所以只能取 `32×32 / 64×32 / 32×64 / 64×64` 四种。
- **按屏面比例选型**：在四种里挑最接近该设备 `dx:dy` 的，把横向拉伸降到最低。

| 屏面比例区间 | 选用 | 台数 |
|---|---|---|
| ≥ 1.9（宽屏） | 64×32 | 9（id / permit / visa / entry / exit / hotel / desk / loss / print） |
| 0.9 – 1.9（近方） | 64×64 | 7（passport / manual / station / blacklist / self_visa / self_endorsement / stamp_history / airport → 8，含 airport 0.83） |
| ≤ 0.8（竖条） | 32×64 | 2（endorsement 0.66 / renewal 0.38） |

拉伸比（屏面比例 ÷ 贴图比例）：**14 台 ≤ 1.2×**，最差的是 hotel_reader 1.53×（3.05 : 2.0，受"只能 32/64"限制，已是最优解）。

- **UV 影响：无。** 19 台全部屏幕面都是 `uv: [0,0,16,16]`（归一化百分比，不是像素值）。贴图从 32×32 改成 64×32 之类，UV 一个数都不用动。已逐面核对并写进 `tools/verify_screen_changes.js` 的断言（`elements` 必须与 HEAD 完全一致）。

---

## 5. 改动清单

**新增工具（4 个）**

| 文件 | 作用 |
|---|---|
| `tools/lib/screen_kit.js` | 设计系统实现：PNG 编码 / 3×5 字模 / 圆角描边 / 进度条 / 条码 / 印章环 / 警示三角 / 指纹环 / 大对勾 |
| `tools/gen_device_screens.js` | 19 台屏幕的唯一生成器（含 alpha 门禁：不透明 + 二值，违规即 exit 1） |
| `tools/rebind_device_screens.js` | 把 19 个模型的 `screen` 指向自己的图；只替换值，不动格式 |
| `tools/preview_screens_sheet.ps1` | 合成对照图（中文标注，需 UTF-8 BOM） |
| `tools/verify_screen_changes.js` | 对 git HEAD 断言改动范围（1 行/模型、几何 UV 不变、闸机不动） |

**改动文件**

| 类别 | 数量 | 路径 |
|---|---|---|
| 屏幕贴图 | 38 | `textures/block/<device>_screen.png`、`<device>_screen2.png`（19 台 × 2） |
| 模型绑定 | 19 | `models/block/<device>.json` —— 每文件**恰好 1 行**：`screen` 的值 |

**未改动**：`*_body/_trim/_top/_accent/_glass/_led/_detail/_label`、`*_paper`、`*_passport`、`*_slot`、闸机的全部贴图与模型、`blockstates/**`、任何 `.java`。

> 说明：本工作区同时有另外两个代理在改材质与闸机。`git status` 里能看到的 `*_body/_trim/...`、`egate_*`、`dual_gate_*`、`item/*`、`*.java`、`*.bbmodel` **都不是本次改动**。本任务只写过上表 38 + 19 个文件。

**对照图里的 `_screens_before/`**：改动前 53 张屏幕贴图的备份，用于重生成对照图；`_screens_manifest.json` 是设备表（名称/强调色/尺寸）的唯一数据源。

---

## 6. 验收

```
$ node tools/preview_models.js
...
CHECKLIST
  [PASS] every model >= 10 elements (gates >= 20)
  [PASS] rotated elements optional
  [PASS] no single full cube
  [PASS] unique geometry per device (dual_gate may mirror egate)
  [PASS] >= 4 light palette colors
  [PASS] all texture refs resolve
  [PASS] textures are 32 or 64 px
  [PASS] model files < 120 KB
  [PASS] blockstates reference existing models
  [PASS] item model parents resolve
  [PASS] all gates keep the lane and are >= 16 px tall
  [PASS] 10 px gate passage is clear
  [PASS] 22 models + blockstates + item models + loot
  [PASS] egate + exit_gate + dual_gate have 8 facing/open variants

FAILURES: none
→ exit 0
```

```
$ node tools/verify_screen_changes.js
  ok   id_issuer: elements + UVs identical to HEAD
  ok   id_issuer: 1-line diff  "screen": "veriborder:block/r9_screen_ui", -> "screen": "veriborder:block/id_issuer_screen",
  ...（19 台同构）
  ok   changed non-gate *_screen*.png: 38
  ok   gate egate: untouched, screen = veriborder:block/egate_screen
  ok   gate exit_gate: untouched, screen = veriborder:block/exit_gate_screen
  ok   gate dual_gate: untouched, screen = veriborder:block/dual_gate_screen

PASS — 19 models changed by exactly one texture-binding line each;
       geometry and UVs untouched; 38 non-gate screen textures updated;
       gate screens untouched.
→ exit 0
```

**PNG 体检（38/38 通过）**：合法 PNG 签名、`bitDepth 8`、`colorType 6 (RGBA)`、非隔行、边长 ∈ {32,64}、alpha 取值集合 ⊆ {0,255}、**透明像素 0 个**（全不透明）。

---

## 7. 已知限制 / 建议后续

1. **3×5 字模里 N 与 H 天生易混**（孤立字母时尤其明显，如 `ON` 会像 `OH`）。已按传统 3×5 写法取 `#.#/###/#.#/#.#/#.#`，并把容易误读的 `ON TIME` 改成 `READY`。若要彻底消除，需要 4px 宽字格（会改动整套字模）。
2. **屏面拉伸**：`hotel_reader` 屏面 3.05:1、`renewal_machine` 0.38:1，受"贴图只能 32/64"的校验限制，无法做到 1:1 无失真。若允许非 32/64 尺寸（例如 64×20、32×84），这 19 张还能再准一档——但需要同时放宽 `preview_models.js` 的断言，属于改校验器，本次未动。
3. **`*_label.png`（64×32 机名铭牌）本次未改**。它们不属于"屏幕"，且与正在改材质/闸机的代理不冲突但也不必要，保持原样以免扩大 diff。如果希望铭牌也带上各自强调色，可作为下一轮小改动。
4. **闸机 3 个 `*_tablet.png` 存在半透明 alpha**（114/38/144/85/97/13 等），是全仓仅有的不合规屏幕类贴图；**本次未触碰**（`git status` 证明未修改），留给闸机代理。
5. 提示文案全为 ASCII 大写缩写。3×5 字模无法承载中文；若将来要做中文屏，需要另起一套字模（会破坏"同一字体网格"的前提，应作为独立设计决策）。

---

## 8. 复现

```powershell
cd <repo>
node tools/gen_device_screens.js --zoom 8      # 38 张屏幕贴图 + 8x 审阅图 + alpha 门禁
node tools/rebind_device_screens.js            # 19 个模型 screen 绑定（幂等）
powershell -NoProfile -ExecutionPolicy Bypass -File .\tools\preview_screens_sheet.ps1
node tools/preview_models.js                   # 模型校验，应 exit 0
node tools/verify_screen_changes.js            # 改动范围断言，应 exit 0
```

`gen_device_screens.js` 的 alpha 门禁会在任何一次填充漏画（出现透明像素或半透明）时直接 `exit 1` —— 这套屏幕全部要求不透明，所以它是有效的回归网。

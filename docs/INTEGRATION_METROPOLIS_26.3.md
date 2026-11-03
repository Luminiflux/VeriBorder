# VeriBorder × Metropolis 联动可行性分析（26.3 方向）

分析对象：`Metropolis-0.4.0-beta-MTR3.2.2+fabric-1.20.1_20260228.jar`（590,132 B，600 entries）
被测环境：`D:\pcl2\.minecraft\versions\1.20.1-ForgeTest\mods`
（Forge 47.x + Sinytra Connector 1.0.0-beta.49 + Fabric API 0.92.6 + Architectury 9.2.14 + MTR-forge 3.6.3 + VeriBorder 26.2.1）

---

## 一、模组结构解析（实测）

### 1.1 基本身份

| 项 | 值 |
|---|---|
| mod id | `metropolis` |
| 版本 | `0.4.0-beta` |
| 加载器 | **Fabric**（`fabric.mod.json`，无 mods.toml） |
| 依赖 | `fabricloader >=0.16.13`、`fabric`、`minecraft 1.20.1`、`mtr >=1.20-3.2.2` |
| 许可证 | MIT |
| 源码 | `github.com/yomi-china/Metropolis-1.20.1-fabric` |
| 入口点 | main `team.dovecotmc.metropolis.Metropolis`；client `...client.MetropolisClient` |
| Mixin | `metropolis.mixin.json`（Train / TrainServer / GameRenderer / BlockOutlineRender） |

要点：**没有 Forge 版本**。它能跑在你的整合包里，是因为 **Sinytra Connector** 在 Forge 上提供了 Fabric 运行时。

### 1.2 票务数据模型（这是联动的核心）

两类“票”物品，均实现空标记接口 `InterfaceTicket`：

```
ItemTicket（单程票）
  NBT: balance / entered_station / entered_zone / start_station / end_station
  字段: disposable

ItemCard（IC 卡）
  NBT: balance / max_value / entered_station / entered_zone
  字段: infiniteBalance
```

### 1.3 闸机判定逻辑（`BlockTurnstile` 反编译）

```
BlockTurnstile extends BlockBase implements InventoryProvider
  常量 CLOSE_DELAY = 80 tick（4 秒）
  属性 OPEN / TYPE / CONNECTED / 字段 icOnly

onUse(state, world, pos, player, hand, hit):
  Station station = MtrStationUtil.getStationByPos(pos, world)   // mtr.data.Station
  ItemStack held = player.getStackInHand(hand)

  if held 是 ItemCard:
      if card.infiniteBalance → 直接放行
      else 读 balance，够票价则扣款
  else if held 是 ItemTicket:
      读 entered_station / entered_zone
      未进站 → 写入 entered_station = station.name, entered_zone = station.zone
      已进站 → 计算 zone 差票价，扣 balance 或作废票
  else → 拒绝

  通过后：setBlockState(OPEN = true)，CLOSE_DELAY 后自动关闭
```

关键结论：**闸机只认 `instanceof ItemCard` / `instanceof ItemTicket`**，VeriBorder 的 `DocumentItem` 不在判定范围内，拿身份证刷卡必然被拒。

### 1.4 售票机

```
BlockTicketVendor / BlockTicketVendorUp / BlockTicketVendorTop
  → BlockEntityTicketVendor（Inventory，存放付款用的货币物品）
GUI（客户端）:
  TicketVendorScreen1  主菜单：车票 / 优惠车票 / 充值 / 购买月票 / 购买IC卡 / 退票
  TicketVendorScreen2  购单程票
  TicketVendorScreen3  ...
  TicketVendorScreen4  ...
  TicketVendorScreenBuyIC   买卡
  TicketVendorPaymentScreen 付款
网络: MetroServerNetwork / MetroClientNetwork
  通道标识: metropolis:ticket_vendor_gui / ticket_vendor_result /
            ticket_vendor_close / remove_inventory_item / fare_adj_gui ...
```

语言键 `gui.metropolis.ticket_vendor_1.button.buy_commuter` = **“购买月票”**，且 `zh_cn.json` 里存在该键，但**服务端没有对应实现**——正是截图里那个灰色按钮。

### 1.5 与 MTR 的关系

- 站点、区域（zone）、票价全部来自 **MTR**：`mtr.data.Station`、`MtrStationUtil.getStationByPos`
- Metropolis 只做闸机 / 售票机 / 站台门等“车站设施”
- 你的整合包是 **MTR Forge 3.6.3**，而 Metropolis 编译时依赖 **MTR Fabric 3.2.2**，经 Connector 桥接运行——这层桥接本身是既有风险点（当前能跑，但 API 漂移会导致崩溃）

---

## 二、联动的四个技术障碍

| # | 障碍 | 说明 | 严重度 |
|---|---|---|---|
| 1 | **加载器鸿沟** | Metropolis 是 Fabric（类名经 intermediary 映射，如 `net.minecraft.class_1792`）；VeriBorder 是 Forge（official/SRG 名）。**Forge 侧无法 compileOnly 引用它的类**，只能运行时反射或 Mixin | 高 |
| 2 | **闸机类型判定** | `instanceof ItemCard / ItemTicket` 硬编码，身份证不是这两类 | 中（可 patch） |
| 3 | **售票机 GUI 在 Fabric 客户端** | 按钮、Screen、网络通道都是 Fabric API。Forge 侧无法注册 Fabric payload，也就无法参与“点击按钮 → 服务端发货”的完整链路 | 高 |
| 4 | **身份证是签名文档** | VeriBorder 的 `DocumentData` 有 SHA-256 完整性校验；写入乘车记录必须同时重签，否则被判 `tampered` | 中 |

---

## 三、三条可选路线

### 路线 A：VeriBorder 侧伴生 + Mixin（不动 Metropolis 本体）

- Mixin 注入 `BlockTurnstile.onUse`，手持 `DocumentItem` 且带乘车凭证时接管放行
- Mixin 注入 `BlockTicketVendor.onUse`，手持身份证右键售票机 → 用 VeriBorder 自己的界面购票
- 数据写在身份证 NBT 的 `transit` 子标签，走 VeriBorder 签名

| 优点 | 缺点 |
|---|---|
| Metropolis 无需改版，可跟随上游升级 | 售票体验不在原 GUI 里（用户明确要求“在购票机内”会打折） |
| 逻辑主要落在自己代码里 | Mixin 目标方法是 intermediary 名（`method_9534`），版本变动易碎 |
| 分发简单：只发 VeriBorder 一个包 | 需要验证 Forge Mixin 能否作用于 Connector 加载的 Fabric 类（**关键前置**） |

### 路线 B：Fork / Patch Metropolis（MIT 允许）

- 在 `TicketVendorScreen1` 把“购买月票”改为“身份证购票”，新增 Screen + C2S 包
- 服务端收到包 → 读玩家手持身份证（反射 VeriBorder API）→ 写 `transit` 并重签 → 回结果
- `BlockTurnstile.onUse` 增加 `DocumentItem` 分支，按凭证放行

| 优点 | 缺点 |
|---|---|
| **完全满足需求**：就在原售票机 GUI 内买票 | 要分发改版 jar（`Metropolis-...-vb.jar`），用户需替换 |
| 闸机、GUI、网络都在 Fabric 侧，无跨加载器障碍 | 上游升级需重新合并补丁 |
| 票价/站点直接用 MTR API，无需反射 | 需要维护补丁集，长期成本高 |

### 路线 C：绕开闸机判定（不推荐）

购票时发一张 Metropolis 的 `ItemCard`，身份证只做“账户”。问题：闸机读的是**手持物品**，玩家仍要拿卡通过，违背“身份证直接过闸”的目标。

---

## 四、推荐方案：B 为主线，A 作为降级

**理由**：用户要的核心体验是“在原售票机里用身份证买票，然后拿身份证过闸”。这条链路的三个环节（GUI、网络、闸机判定）全部在 Metropolis/Fabric 侧，把补丁放进 Metropolis 是最短路径；放在 Forge 侧要跨两层（加载器 + 网络），失败点更多。

**降级策略**：若不能接受分发改版 jar，则退到路线 A——保留“身份证过闸”，但购票改由 VeriBorder 自己的方块完成。

---

## 五、数据契约设计（两条路线共用）

身份证 NBT 新增 `transit` 子标签：

```nbt
transit: {
  mode:        "commuter" | "ic" | "single"
  zoneFrom:    <int>      // MTR Station.zone
  zoneTo:      <int>
  validUntil:  <long>     // epoch day，月票用
  balance:     <int>      // IC 卡余额（分/元按项目约定）
  enteredStation: "…"     // 进站站名，出站后清空
  enteredZone:    <int>
  issuedAt:    <long>
}
```

规则：

1. **签名**：`transit` 必须纳入 VeriBorder 的签名范围，每次写入调用 `DocumentData.sign()` 重签
2. **月票**：`mode=commuter` 时闸机只校验 `validUntil >= today` 且 `zone ∈ [zoneFrom, zoneTo]`，不扣费
3. **IC**：`mode=ic` 时按 MTR zone 差扣 `balance`
4. **单程票**：`mode=single` 时进站记录 `enteredStation`，出站即清除（等价于 Metropolis 的 disposable ticket）
5. **兼容**：没有 `transit` 的旧证件行为不变（仍是被闸机拒绝）

---

## 六、实施计划（按可验收的里程碑拆分）

### M0 · 技术验证（1 天，**必须先做**）

- [ ] Forge Mixin 能否注入 Connector 加载的 `BlockTurnstile`（打日志即算成功）
- [ ] 从 Metropolis 侧能否通过反射读到 VeriBorder 的 `DocumentData`（含签名重算）
- [ ] 结论决定走 B 还是 A

### M1 · 身份证过闸（3 天）

- 闸机判定增加 `DocumentItem` 分支（commuter / ic / single）
- 放行动画、声音、`CLOSE_DELAY=80` 沿用原逻辑
- 单测：凭证解析、有效期、区间校验、余额不足

### M2 · 售票机购票（4 天）

- `TicketVendorScreen1` 的“购买月票”改为“身份证购票”
- 新 Screen：选择区间（读 MTR 站点列表）+ 期限（1/3/6/12 个月）+ 价格
- C2S 包 `metropolis:id_card_purchase`，服务端写 `transit` 并重签、扣款
- 失败路径：无身份证 / 余额不足 / 区间非法 / 已有有效月票

### M3 · 附属设备（2 天）

- 充值机（`BlockFareAdjMachine`）支持给身份证 IC 余额充值
- 闸机屏幕显示凭证状态（月票剩余天数 / 余额）

### M4 · 打磨与交付（2 天）

- 中英双语 lang、配置项（票价倍率、月票价格表）
- 打包：VeriBorder 26.3 + Metropolis 补丁 jar，写清安装顺序
- 文档与兼容性矩阵（Metropolis 版本、MTR 版本、Connector 版本）

**总计约 12 个工作日**（含 M0 验证）。

---

## 七、风险与缓解

| 风险 | 影响 | 缓解 |
|---|---|---|
| Connector 加载 Fabric 类的 Mixin 不生效 | 路线 A 直接不可行 | M0 先验证；失败即转 B |
| Metropolis 上游改版导致补丁失效 | 需要重新适配 | 补丁尽量小、集中在 2 个方法；用 `@Inject` 而非 `@Overwrite` |
| intermediary 方法名变动 | Mixin 目标失配 | 用 Connector 的 remap 支持或在 M0 阶段确认映射 |
| 身份证签名被写坏 | 证件被判 `tampered` | 所有写入走 `DocumentData` 的公开 API，禁止直接改 NBT |
| MTR Forge 3.6.3 与 Metropolis 依赖的 3.2.2 API 差异 | 站点/票价读取异常 | M0 阶段实测 `MtrStationUtil.getStationByPos` 返回值 |
| 分发改版 jar 的合规性 | 法务 | MIT 允许修改分发，需保留 LICENSE 与版权声明，并在 README 说明改动 |

---

## 八、建议的第一步

**只做 M0**，用 1 天时间回答两个问题：

1. Forge Mixin 能不能碰 Connector 加载的 Fabric 类？
2. Metropolis 能不能安全读写 VeriBorder 的签名证件？

两个都是“能/不能”的二元结果，做完就能确定走 B 还是 A，避免直接投入两周后卡在加载器层面。

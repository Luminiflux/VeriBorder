# VeriBorder 交接文档

> 版本：26.1（首个正式版）  
> 作者：Luminiflux  
> 项目目录：`C:\Users\GhostBubbles\Documents\deepseek-harness\default-workspace\VeriBorder`

## 1. 项目概览

Minecraft 1.20.1 Forge 模组，用物品 NBT 模拟完整证件链与边检流程：

- 身份证、护照、港澳通行证
- 签证、签注、免签编码、出入境章
- 发证机、自助机、核验终端、出入境闸机
- 入境会话、出境消耗、通行日志、officer 覆盖、逾期停留

- Minecraft 1.20.1 / Forge 47.x / Java 17
- 版本：26.1
- 21 台设备、21 个模型、254 张 block 贴图、24 条配方
- 69 项单元测试
- 不依赖任何外部交通模组

## 2. 构建

```bat
set JAVA_HOME=<JDK 17>
gradlew.bat clean build
gradlew.bat test
```

资源生成：

```bat
powershell -NoProfile -ExecutionPolicy Bypass -File .\tools\generate_assets.ps1
powershell -NoProfile -ExecutionPolicy Bypass -File .\tools\generate_recipes.ps1
node tools\preview_models.js
powershell -NoProfile -ExecutionPolicy Bypass -File .\tools\preview_models.ps1
```

产物：

- `build/libs/veriborder-26.1.jar`
- `dist/veriborder-26.1.jar`
- `dist/veriborder-26.1-src.zip`
- `dist/SHA256SUMS.txt`

## 3. 数据模型

- `DocumentData`：证件字段、签证、签注、印章、免签编码、完整性签名。
- `VisaRecord`：8 位 code + 可选 `label`（签证名称）。
- `EndorsementRecord`：8 位 code。
- `CredentialCode`：8 位纯数字、自动/固定、legacy、规范化。
- `DocumentData.visaFreeCodes`：护照免签编码。
- 所有锁字段在 `DeviceBlockEntity`，通过 `Locks` 持久化/同步/configTag。

## 4. 边境会话

`com.veriborder.border`：

- `BorderSession`：玩家、证件号、recordId、code、类型、口岸、入境时间、overstay。
- `BorderLog`：时间、玩家、方向、结果、证件、code、gate、detail。
- `BorderTravelData`：SavedData，按 UUID 保存活跃会话，日志上限 500。
- `BorderService`：入境匹配、开始会话、拒绝日志、出境完成、officer payload。

流程：

1. 入境：无会话 -> 验证 gate mode/code -> 绑定来源（ALL/VISA/VISA_FREE/ENDORSEMENT）
   -> 创建会话，**不消耗 entries**；有会话 -> ALREADY_ENTERED，联系 officer。
2. 出境：必须有会话；证件号一致；绑定 record 仍有效；成功后消耗该 record 一次，
   清会话，写出境章；visa-free/ALL 不消耗。
3. 逾期：入境时间 + overstayDays 后仍允许出境，但记录 OVERSTAY。
4. 会话/日志持久化，重启/登出保留。

## 5. Officer

- OP level 2 右键 Manual Verification Terminal -> Officer Console。
- 显示活跃会话、最近日志、逾期；可清除会话。
- 命令：`/veriborder border status|clear|log`，OP 2。
- 清除操作写 OFFICER_CLEAR 日志。

## 6. 发证/自助机

- 护照发行机可附带签证，写入同一护照 NBT；独立签证机仍可事后贴签。
- 全发行机锁：style/type、place/country、issueDate、validity years、visa code/name、
  includeVisa、visaEntries、visa country/issue/validity。
- 自助签证/签注机只显示类型 + 签发；锁定值由服务端强制。
- `autoStamp`（默认开）和 `overstayDays`（默认 30）在闸机配置中设置。
- 护照和港澳通行证均可盖章；ID 卡不盖章。

## 7. 权限与物品

- `device_brush`、`document_modifier` 为创造模式专属，无生存配方。
- 配置、修改器保存/生成、officer 操作要求 OP level 2。
- 普通设备方块仍可合成。

## 8. 测试

```text
DocumentCoreTest: 46
BorderDataTest:   13
VisaLockTest:     10
total:            69 tests, 0 failures
```

客户端验收记录见 `VERIFICATION.md`。

## 9. 已知限制

- 闸机是 20px 单格视觉 + 1 格控制器碰撞，2 格高碰撞墙；不是真正多格 multiblock。
- 固定 code 是一台自助机一个 code，没有号段/递增规则。
- 出境暂不校验维度（可在后续加）。
- 真人视觉验收、专属音效、外部交通模组扩展暂未做。

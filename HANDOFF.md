# VeriBorder 交接文档

> 版本：1.2.0-hotfix  
> 作者：Luminiflux  
> 项目目录：`C:\Users\GhostBubbles\Documents\deepseek-harness\default-workspace\VeriBorder`

## 1. 项目概览

VeriBorder / 证界边检是 Minecraft 1.20.1 Forge 模组，用物品 NBT 模拟完整证件链：
身份证、护照、港澳通行证、签证、签注、出入境章，以及发证、核验、签注、闸机等设备。

- Minecraft 1.20.1 / Forge 47.x / Java 17
- 版本：1.2.0-hotfix
- 21 台设备、21 个 block model、21 个 blockstate、254 张 block 贴图、26 条配方、21 个 loot table
- 43 项单元测试、真实客户端 22 项功能验收通过
- 不依赖任何外部交通模组

> 1.2.0-hotfix：修复进入创造模式物品栏时 `Registry Object not present: veriborder:self_visa` 崩溃。
> 创造栏内容改由 `BuildCreativeModeTabContentsEvent` 在注册完成后填充，并加 `isPresent()` 保护。

## 2. 构建

```bat
set JAVA_HOME=<JDK 17>
gradlew.bat clean build
gradlew.bat test
```

资源由脚本生成：

```bat
powershell -NoProfile -ExecutionPolicy Bypass -File .\tools\generate_assets.ps1
powershell -NoProfile -ExecutionPolicy Bypass -File .\tools\generate_recipes.ps1
node tools\preview_models.js
powershell -NoProfile -ExecutionPolicy Bypass -File .\tools\preview_models.ps1
```

产物：

- `build/libs/veriborder-1.2.0-hotfix.jar`
- `dist/veriborder-1.2.0-hotfix.jar`
- `dist/veriborder-1.2.0-hotfix-src.zip`
- `dist/SHA256SUMS.txt`

## 3. 数据模型

所有证件数据在 `ItemStack.tag.VeriBorder`，设备配置在方块实体 NBT。

- `DocumentData`：证件字段、签证、签注、印章、免签编码、完整性签名。
- `VisaRecord` / `EndorsementRecord`：新增 8 位 `code`。
- `CredentialCode`：8 位纯数字、自动生成、确定性旧记录 code、规范化。
- `DocumentData.visaFreeCodes`：护照自带免签 8 位编码。
- `DocumentData.addVisaExact` / `addEndorsementExact`：保留自助机固定 code 或发证机锁定 code。
- 普通人工发证的自动 code 仍保持唯一；固定机器 code 允许重复。

## 4. 闸机

- `EGateBlock`：`OPEN` 状态驱动碰撞和闸翼；四朝向碰撞按 `FACING` 旋转。
- `EGateRenderer`：6×9 玻璃闸翼、状态灯、扫描光束；smoothstep + partial tick。
- 四模式：`all` / `visa` / `endorsement` / `both`。
- `gateCodes` 为 8 位字符串 ListTag，无数量上限、去重、保留前导零。
- 校验优先级：先常规证件有效性，再按模式精确匹配 code。
- 消耗：仅消耗命中的有效签证/签注；免签 code 不消耗；`all` 不消耗；出境闸机只验证件。
- 闸机模型为原创单车道闸机，几何和贴图由项目脚本生成，不包含任何第三方模型资产。

## 5. 发证机 / 自助机

- `DeviceBlockEntity`：
  - `gateMode`、`gateCodes`
  - `lockDocumentName` / `lockedDocumentName`
  - `lockAuthority` / `lockedAuthority`
  - `issuedCode`（自助签证机/自助签注机固定发行 code）
  - `lockEndorsementCode` / `lockedEndorsementCode`（港澳通行证发证机初始签注 code）
- `DeviceService` 服务端强制执行锁字段，客户端界面只读。
- `SELF_VISA`：玩家只选签证类型 + 签发；国家/机关/日期/entries/code 由刷子配置。
- `SELF_ENDORSEMENT`：玩家只选签注类型 + 签发；无机关/模板/编码/续签/撤销/删除。
- 自助机固定 code：刷子配置 `Issued code`；留空自动生成，填写则重复使用该 code。
- 港澳通行证初始签注：可锁定 8 位 code，不再自动生成。

## 6. 测试与验收

```text
DocumentCoreTest: 43 tests, 0 failures
preview_models.js: 21 models, CHECKLIST all PASS, FAILURES: none
FeatureSmoke120: 22/22 PASS
```

客户端验收覆盖：自助签证机签发、固定 code、初始签注 code 锁定、自助机 UI 隔离、
21 模型/配置/世界渲染无 magenta。详见 `VERIFICATION.md` 与 `ROUND4_SPEC.md`。

## 7. 已知限制

- 闸机是 20px 单格视觉 + 1 格控制器碰撞，不是真正 2 格宽 multiblock。
- 自助机固定 code 是“一台机器一个 code、所有签发共用”；没有号段/递增规则。
- 真人视觉审美仍需确认。
- 专属音效尚未制作。
- 不依赖外部交通模组，也不包含任何第三方模型/贴图。

## 8. 1.2.0-hotfix 交付

- `dist/veriborder-1.2.0-hotfix.jar`
- `dist/veriborder-1.2.0-hotfix-src.zip`
- `dist/SHA256SUMS.txt`
- jar SHA-256：`39F0A8A0988DEBE9496CDA894569240E80AEB92111F66BE4276A8EF68F438B75`
- 源码包 SHA-256 以 `dist/SHA256SUMS.txt` 为准。

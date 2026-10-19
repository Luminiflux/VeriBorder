# VeriBorder 新会话交接提示词

把下面代码块中的内容直接粘贴到新对话即可。  
完整背景、文件地图、风险清单见同目录的 `HANDOFF.md`，验收记录见 `VERIFICATION.md`。

---

```text
你接手一个已经存在且可编译的 Minecraft 1.20.1 Forge 模组项目：VeriBorder / 证界边检。

项目路径：
C:\Users\GhostBubbles\Documents\deepseek-harness\default-workspace\VeriBorder

第一步必须先阅读：
- HANDOFF.md
- README.md
- VERIFICATION.md

当前状态：
- 作者：Luminiflux
- 版本：1.2.0-hotfix2
- 目标：Minecraft 1.20.1 / Forge 47.x / Java 17
- 成品：dist/veriborder-1.2.0-hotfix2.jar
- 源码包：dist/veriborder-1.2.0-hotfix2-src.zip
- 43 个 DocumentCoreTest 测试通过
- 21 个设备模型（18 原有 + exit_gate + self_endorsement + self_visa）、21 blockstate、254 张 block 贴图、26 条配方
- tools/preview_models.js CHECKLIST: FAILURES: none
- 真实客户端 1.2.0 功能验收记录见 VERIFICATION.md（自助签证机/固定 code/签注 code 锁定/UI 隔离）
- 1.2.0-hotfix2：闸机碰撞墙提升到 2 格高，关闭时完整阻挡，打开时中央 12px
  通道保持通行，两侧柜体碰撞也提高到 2 格，防止跳上闸机顶部越境
- 1.2.0-hotfix：创造栏改为 BuildCreativeModeTabContentsEvent 延迟填充，修复
  `Registry Object not present: veriborder:self_visa` 崩溃
- 不依赖任何外部交通模组；不要加入外部模组依赖
- 所有证件数据继续存物品 NBT；设备配置/预设存 BlockEntity NBT

1.2.0 功能：
- 新增 SELF_VISA 自助签证机：玩家只选签证类型 + 签发；国家/机关/日期/entries/code
  全部由刷子配置；玩家端无 country/authority/code/template 控件。
- 自助签证机/自助签注机支持 `issuedCode` 固定 8 位发行编码；固定 code 可重复使用，
  使一台自助机可用一个闸机 code；留空仍自动生成。
- 港澳通行证发证机支持 `lockEndorsementCode` + `lockedEndorsementCode`，初始签注
  不再强制自动生成。
- 自助机服务端忽略玩家提交的 authority/code/entries/dates；只使用机器参数。
- 新增/变更核心：DeviceType.SELF_VISA、DeviceBlockEntity.issuedCode/
  lockEndorsementCode、DocumentData.addVisaExact/addEndorsementExact、
  VisaIssuerScreen self-service 分支、DeviceConfigScreen 发行编码/初始签注锁定、
  Build-SelfVisa 模型、self_visa 配方。

1.1.5 功能：
- 8 位纯数字编码：签证/签注自动分配唯一 code，管理员可在发行/签注界面自定义；
  护照 NBT 支持 visaFreeCodes 免签编码；闸机按 code 精确匹配，忽略名称/类型/机关差异。
- 四模式闸机：all（任意有效证件）/ visa（签证+免签）/ endorsement（签注）/
  both（签证或签注）；gateCodes 无数量上限。
- 发证/签注机具可锁定证件名 + 发证机关；普通玩家界面只读，服务端强制覆盖。
- 自助签注机玩家端仅保留预设签注类型 + 签发；机关/模板/编码/续签/撤销全部隐藏，
  服务端忽略玩家提交，使用机器机关 + 配置的固定/自动 code。
- 新增/变更核心：doc/CredentialCode.java、VisaRecord.code、EndorsementRecord.code、
  DocumentData.visaFreeCodes、DocumentValidator.validateGate(..., gateCodes)、
  DeviceBlockEntity.gateCodes/locks、DeviceConfigScreen 编码/锁配置、Modifier 免签编码管理。

1.1.3 hotfix：闸机碰撞按 FACING 旋转（东西朝向也能过闸）；闸机改用 cutout
渲染层、清除所有重叠/共面元素（AABB 重叠 0）并把 BER 灯移到柜体前表面，
修复游戏内闪烁。四朝向碰撞 8/8 PASS。

1.1.2 hotfix：闸机改为原创的单车道闸机：
20px 单格壳、两个 4px 柜体、12px 通道、16px 高、6×9 透明玻璃闸翼；
不再使用旧的 32×24 门楣模型。

1.1.1 hotfix：出境闸机只核验证件本身有效性，不要求也不消耗签证/签注；
配置界面不显示出境闸机的签证/签注绑定；入场闸机绑定逻辑不变。

1.1.0 已完成：
1. 入场/出境双 E-Gate：32px 宽 × 24px 高的跨格视觉模型、8px 中央通道；
   透明玻璃门扇、缓动 + partialTick 插值、灯条、扫描光束；OPEN 状态同时驱动
   碰撞与可见门扇；出境闸机独立贴图/EXIT 标识。
2. 闸机绑定：gateMode = any / passport / permit；passport 模式可指定
   requiredVisaType（tourist/business/student/work/family/other），permit 模式可指定
   requiredEndorsementType（one_year_multi/three_month_single/other）。
   不匹配在核验前拒绝，聊天消息明确所需类型；通过后只消耗匹配记录。
   （1.1.5 已改为 8 位编码 + 四模式；以上类型绑定仅历史说明，不再生效。）
3. Device Configuration Brush（veriborder:device_brush）：
   只有手持刷子右键设备才打开 DeviceConfigScreen；普通右键保持证件/操作逻辑；
   save_config 服务端再次校验刷子；旧 Config 按钮/无证件配置入口已移除。
4. 自助签注机 self_endorsement：复用 EndorsementScreen，但标题/描述/强调色来自实际设备，
   仅接受通行证，支持签发/续期/注销/模板。
5. 签注类型合并：移除 TEAM/INDIVIDUAL，仅保留 ONE_YEAR_MULTI / THREE_MONTH_SINGLE / OTHER；
   旧 NBT team/individual 仍可读并解析为 OTHER；发行机初始签注选项同步更新。
6. GUI/显示名称/模型重做等 hotfix-2 内容仍保留。

关键约束：
- 不要推倒重写项目，先读 HANDOFF.md 与现有源码。
- 不要加入外部模组依赖；不要改变作者 Luminiflux。
- 所有证件数据继续存在物品 NBT。
- 改资源后必须重新生成并 build：
  powershell -NoProfile -ExecutionPolicy Bypass -File .\tools\generate_assets.ps1
  powershell -NoProfile -ExecutionPolicy Bypass -File .\tools\generate_recipes.ps1
  gradlew.bat build
- 最终 jar 不能包含 debug/SmokeHarness/ModelGallery 等临时类。
- 模型/贴图改动后用 node tools\preview_models.js 与 powershell -File tools\preview_models.ps1 复核。

构建环境：
- Gradle Wrapper 8.1.1；JDK 17
- 之前开发机 JDK（临时，可能失效）：
  C:\Users\GhostBubbles\AppData\Local\Temp\lmep_build\zulu17.68.203-ca-crac-jdk17.0.20.1-win_x64
- build：gradlew.bat clean build
- test：gradlew.bat test

建议后续：
1. 真人查看 E-Gate 32×24 跨格视觉、玻璃门、灯条、EXIT 标识在生存/服务器中的观感；
   当前碰撞仍是 1 格控制器，真正 2 格宽多方块结构可作为下一阶段。
2. 在 854×480 / GUI scale 2 下实际点击配置界面的三个闸机循环按钮并保存。
3. 用不同签证/签注类型实测闸机拒绝/放行与消耗记录。
4. 检查 25 条配方、20 个掉落表、创造标签顺序（self_endorsement/exit_gate/brush）。
5. 更新 README/HANDOFF/VERIFICATION 与 dist 哈希。

最终回复请给出：
1. 实际修复/验收内容；
2. 构建与测试结果；
3. 新 jar/源码包路径；
4. 新 SHA-256；
5. 仍未完成或需确认事项。
```

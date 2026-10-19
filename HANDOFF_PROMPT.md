# VeriBorder 新会话交接提示词

把下面代码块中的内容直接粘贴到新对话即可。  
完整背景、文件地图、风险清单见同目录的 `HANDOFF.md`，验收记录见 `VERIFICATION.md`。

---

```text
你接手一个 Minecraft 1.20.1 Forge 模组项目：VeriBorder / 证界边检。

项目路径：
C:\Users\GhostBubbles\Documents\deepseek-harness\default-workspace\VeriBorder

第一步必须先阅读：
- HANDOFF.md
- README.md
- VERIFICATION.md
- ROUND6_SPEC.md

当前状态：
- 作者：Luminiflux
- 版本：26.1（首个正式版）
- 目标：Minecraft 1.20.1 / Forge 47.x / Java 17
- 成品：dist/veriborder-26.1.jar
- 源码包：dist/veriborder-26.1-src.zip
- 69 个单元测试（DocumentCoreTest 46 + BorderDataTest 13 + VisaLockTest 10）
- 21 台设备、21 个模型、254 张 block 贴图、24 条配方
- tools/preview_models.js CHECKLIST: FAILURES: none
- 不依赖任何外部交通模组

核心机制：
- 入境闸机验证后创建 BorderSession，不消耗签证/签注次数；
- 出境闸机读取会话，消耗绑定记录一次并清除会话；
- 已入境再次入闸 -> ALREADY_ENTERED，提示联系 officer；
- 出境无会话/证件不匹配/记录失效 -> 拒绝并提示 officer；
- 会话和日志持久化（SavedData），officer 可清除并写日志；
- 逾期停留允许出境但记录 OVERSTAY；
- 闸机自动入境/出境盖章，护照和港澳通行证均可盖章；
- 护照发行机可附带签证（同一 NBT），独立签证机仍可事后贴签；
- 全发行机支持锁定 style/type、签发地/国家、签发日期、有效期年数、
  签证编码/名称、includeVisa、签证次数、附签国家/日期/有效期；
- 自助签证/签注机只显示类型+签发，锁定值由服务端强制。

权限：
- device_brush、document_modifier 创造模式专属，无生存配方；
- 配置、修改器、officer 操作要求 OP level 2；
- 普通设备方块仍可合成。

工程要求：
- 复杂功能拆子代理；主会话只做集成/验收/发布。
- 不要加入外部交通模组依赖。
- 不要改变作者 Luminiflux。
- 所有证件数据存在物品 NBT，设备配置存在 BlockEntity NBT。
- 发布前必须 clean build、69 tests、preview FAILURES none、真实客户端验收。
```

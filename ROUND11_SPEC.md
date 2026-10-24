# ROUND11_SPEC — 26.3 全面升级（已获用户批准）

## P0 机制
1. **删除 all 模式**：`GateBrushConfig.MODES` = visa|endorsement|both；`normalizeGateMode` 把 all/旧值映射为 both；`BorderService.resolveEntry` 删除 ALL 分支（入境必须命中签证/签注/永久编码）；默认 mode 改 both；DualGate 入境模式同步；lang 删 all 项。
2. **一人多证**：
   - 证件增加签名键 `personKey` = normalize(name)+"|"+birthDate（签发时写入）。
   - 新增 `border/BorderEntry`（personKey, documentNumber, holder, recordId, code, kind, dimension, gatePos, entryEpochMillis, port, overstayDays, overstayed）。
   - `BorderTravelData` 改为 `entries: Map<personKey, BorderEntry>` + `documentIndex: Map<documentNumber, personKey>`；保留旧 `sessions` 读入迁移（无 personKey 用 holder+number 兜底）。
   - `BorderService`：`beginEntry` 写 Entry；`findEntry(player, doc)` 先按号码再按 personKey；`replaceEntry` 按 personKey 替换；`finishExit` 消费入境证件的绑定记录。
   - 出境/双检用 `findEntry`；终端按人展示全部 entry。
3. **永久编码语义**：`DocumentData` 增加 `permanentCodes` 记录列表 `{code, issuerCountry, purpose(return_home|visa_free), expiry?}`（NBT list），上限 16；保留 `visaFreeCodes` 读兼容并迁移。闸机匹配：return_home 仅当闸机 country==issuerCountry；visa_free 按目的国；无 issuerCountry 的旧码保持万能兼容。UI 沿用“永久编码”名称。
4. **印章时刻**：`StampRecord` 加 `time`(HH:mm:ss) 与 `epochMillis`；`DocumentData.addStamp` 写服务器当前时间；旧章无 time 显示 `--:--`；证件查看器/tooltip 显示日期+时刻。

## P1 体验
5. **UI 升级 + 分页**：新增共享组件 `client/ui/PagedList`、`TabStrip`、`CardPanel`；新增 `DocumentViewScreen` 分页：Identity/MRZ/Visas/Endorsements/Stamps/History/Integrity，含照片占位、MRZ 等宽条、签证贴纸、印章墨迹、条码占位；终端/发证机/配置界面改 Tab+分页；入口：终端、阅读器、修改器、手持证件右键。
6. **复制刷**：新物品 `config_copy_brush`（无界面）。潜行右键发证机=复制其完整配置（Locks+Issuer policy+机构/口岸/官员）到刷子 NBT 并记录 sourceType；右键另一台发证机=服务端校验同 DeviceType 后整体应用，不同则提示“只能粘贴同型号”。ModItems/创意标签/item model/贴图/lang。

## P2 工程
7. **协议**：`ModNetwork.PROTOCOL` 升 "2"；握手失败提示；新包字段空值兜底。
8. **迁移**：BorderTravelData、DeviceBlockEntity、DocumentData 加 schema 版本与迁移函数，旧档读入自动升级。
9. **资源生成**：以 `generate_item_textures.ps1` 为模板拆幂等生成器；一次性 patch 脚本归档 `tools/archive/`；加“重跑无 diff”校验脚本。
10. **测试/诊断**：闸机流程集成测试、包 round-trip 测试、模型 golden 测试；`/veriborder diagnose` 输出协议/注册表/模板/会话摘要；专用服务端启动脚本。

## 文件所有权
- A（P0）：border/*、doc/DocumentData、doc/StampRecord、block/DeviceService、相关测试。
- B（P1 UI）：client/screen/*、client/screen/ClientScreens、client/ClientScreenRouter、client/ui/*。
- C（P1 复制刷）：item/ConfigCopyBrush*、registry/ModItems、lang、资源。
- D（P2）：network/ModNetwork、border/BorderTravelData 迁移、tools/*、命令、测试基础设施。
共享：lang 只追加自己的 key；不要整文件覆盖。

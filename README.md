# SudoersEMC

> 为 ProjectE 未定价的物品自动生成 EMC 值的 Minecraft Forge 模组。
> An independent Minecraft Forge mod that generates EMC values for items ProjectE does not price.

[![CI](https://github.com/ItzPlana/SudoersEMC/actions/workflows/ci.yml/badge.svg)](https://github.com/ItzPlana/SudoersEMC/actions/workflows/ci.yml)
[![License: CC BY-NC-SA 4.0](https://img.shields.io/badge/License-CC%20BY--NC--SA%204.0-lightgrey.svg)](https://creativecommons.org/licenses/by-nc-sa/4.0/)

---

## 语言约定 / Language Policy

**本项目以中文为主、英文为辅。** 文档、提交信息、代码注释与讨论默认使用中文；关键概念会附英文对照，方便非中文使用者阅读。

**This project is Chinese-first with English as a secondary language.** Documentation, commit messages, code comments and discussions default to Chinese; key terms include English equivalents so non-Chinese readers can follow along. Issues and pull requests written in English are welcome.

---

## 项目简介

SudoersEMC 是一个**独立实现**的 Minecraft Forge 1.20.1 模组。它扫描服务器上已注册的物品与配方，为那些 **ProjectE 尚未赋予 EMC 值**的物品计算 EMC，并把结果持久化到自己的数据文件；安装 ProjectE 时，这些值会通过官方扩展点注入。

模组不会修改 ProjectE 的源码，也不依赖任何非公开接口。所有注入都通过 ProjectE 官方的映射扩展点完成。

### 核心原则

| 原则                 | 说明                                                                                                                                                                                                                                                                                                                                                                                                                           |
| -------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------ |
| **默认不覆盖手动值** | `config/ProjectE/custom_emc.json` 默认只读，注入阶段也会跳过手动值。仅当 `overflow.overwriteScope = entire_custom_emc` 与 `overflow.allowProjectEFileRewrite = true` 双开关同时开启时，才会对该文件中严格超过阈值的条目做受控改写（先备份、原子替换、保留未知字段）。                                                                                                                                                          |
| **归属可追踪**       | 每个生成值都记录来源（配方 / 标签 / 稀有度 / 层级 / 兜底）、配方 ID、归属、NBT 变体哈希（如有）与时间戳。                                                                                                                                                                                                                                                                                                                      |
| **计算可解释**       | SudoersEMC 已生成的无 NBT 变体普通条目可用 `/sudoemc explain <item>` 查看来源、配方、归属与最多 5 条备选成本（同一轮求解中其它有效配方的成本）；带 NBT 变体的条目暂不支持，ProjectE 原生值与手动值只在没有生成记录时单独提示。                                                                                                                                                                                                 |
| **安全优先**         | 生成候选值的大幅降价会在非干跑模式下写入 `config/sudoersemc/quarantined_drops.json` 等待审批（`/sudoemc approve` 默认立即写入降价值；`safety.approveAppliesImmediately=false` 时仅标记审批状态）；超出上限的生成候选值会被重置到受控区间；创造专属物品保护默认开启，排除项、固定值、命名空间与降价豁免均可配置，自动识别默认只覆盖原版 command/structure 物品；检测到 owned 条目被外部或手动值修改时会释放所有权并保留外部值。 |
| **分阶段推进**       | 扫描按阶段跨 tick 推进并更新进度计数，并在每个 tick 边界采样耗时与堆内存；采集、求解与应用仍在服务器线程整体执行，尚未实现工作线程或真正分片；Phase 23 起取消令牌可在采集、求解与应用循环的检查点被观察到，取消/失败时会写出部分诊断报告。                                                                                                                                                                                     |
| **可选依赖**         | 未安装 ProjectE 时模组正常加载，仍会扫描并持久化生成值，但不会向 ProjectE 注入 EMC。                                                                                                                                                                                                                                                                                                                                           |

---

## 当前状态

项目按阶段推进。当前代码基线为 Phase 26（分支 `phase-26-approve-apply`），单元测试统计见构建输出；Phase 18–26 已完成，Goety 与 NBT 变体的真实环境验证、真实模组创造物品识别、Phase 23 取消路径的实机触发、以及 ProjectE-Integration 外部基线的真实数据验证仍待进行。

| 阶段       | 内容                                                                                                                                                                                                                                                                                                                         | 状态                                                                               |
| ---------- | ---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | ---------------------------------------------------------------------------------- |
| Phase 0-1  | Forge 1.20.1 工程骨架、ProjectE 1.0.1 集成调研、查询/写入、归属持久化、溢出保护数学、配置、单元测试                                                                                                                                                                                                                          | ✅ 完成                                                                            |
| Phase 2-13 | 物品与配方扫描（含 Goety 仪式）、扫描管线接线、递归求解、兜底定价、安全层、命令、诊断                                                                                                                                                                                                                                        | ✅ 完成                                                                            |
| Phase 14   | 归属生命周期（owned 值刷新）与溢出审计落盘                                                                                                                                                                                                                                                                                   | ✅ 完成                                                                            |
| Phase 15   | `ENTIRE_CUSTOM_EMC` 受控改写（双开关、时间戳备份、原子替换、未知字段保留）                                                                                                                                                                                                                                                   | ✅ 完成（已实机验证）                                                              |
| Phase 16   | 配置生命周期（热重载、交叉字段校验接线、`fallback.minEmc` 配置键）                                                                                                                                                                                                                                                           | ✅ 完成                                                                            |
| Phase 17   | NBT 变体端到端接线（配方输出 NBT → `VariantScanner` → 带 `variantHash` 的 `ItemSnapshot`）                                                                                                                                                                                                                                   | ✅ 完成（真实环境未验证）                                                          |
| Phase 18   | solve → fallback 多轮收敛；创造物品保护配置化（`safety.creativeItemExclusions` / `creativeItemSpecialValues` / `creativeNamespaces`）                                                                                                                                                                                        | ✅ 完成                                                                            |
| Phase 19   | 空 NBT 归一、排除规则覆盖配方路径、陈旧 owned 条目清理                                                                                                                                                                                                                                                                       | ✅ 完成                                                                            |
| Phase 20   | 默认 `{Damage:0}` 变体归一、Goety 读取器 fail-closed 加固                                                                                                                                                                                                                                                                    | ✅ 完成（Goety 实机未验证）                                                        |
| Phase 21   | 隔离降价落盘与 `/sudoemc approve`、`GeneratedEmcEntry.alternativeCosts`（schema v1→v2）、`/sudoemc explain` 备选成本                                                                                                                                                                                                         | ✅ 完成（开发服实机验证）                                                          |
| Phase 22   | 诊断可观测性：配方扫描统计与失败明细、tick 耗时采样（最大/平均/P95）、峰值堆采样、`extraCounters`、取消/失败部分报告                                                                                                                                                                                                         | ✅ 完成（开发服实机验证；代表性模组包未测）                                        |
| Phase 23   | 五通道增量指纹与启动跳过、协作式取消、有界 LRU 求解缓存、`recipe.selectionStrategy` 配方选择策略                                                                                                                                                                                                                             | ✅ 完成（开发服三场景验证；取消实机未触发）                                        |
| Phase 24   | 降价保护豁免、创造物品自动识别、外部修改检测、配方扫描完整性（shaped/shapeless、非 3x3、多输出、容器返还）                                                                                                                                                                                                                   | ✅ 完成（开发服四场景验证；真实模组创造物品未覆盖）                                |
| Phase 25   | `schemaVersion` 配置迁移（解析前迁移、备份、原子替换、高版本拒绝）、`fallback.useProjectEIntegrationBaselines` 真实化（读取 ProjectE-Integration `pe_custom_conversions` 的 `values.before`，经 EXTERNAL_BASELINE 候选路径）、`diagnostic.debugLogging` 限流日志、`ResolverRegistry` 生产接线（第三方可注册 NBT 材料解析器） | ✅ 完成（开发服四场景验证；无 ProjectE-Integration 环境，baseline 真实数据未验证） |
| Phase 26   | `/sudoemc approve` 批准后自动应用隔离降价值：`PriceSource.APPROVED`、`safety.approveAppliesImmediately`、审计式批准、手动值/排除/最小 EMC/溢出阈值 fail-closed、双存储落盘顺序与失败回滚、`applied` 持久化与 schema 升级                                                                                                     | ✅ 完成（单元测试覆盖；开发服实机未验证）                                          |

Phase 1 曾在专用服务器上验证写入生效、重启后自动恢复、手动值不被覆盖与缺少 ProjectE 时的降级行为；该记录属于历史验证，不代表当前全链路已在所有模组组合下复测。Phase 15 的双开关改写已在独立环境实机验证：默认配置与单独开启任一开关都不会改动 `custom_emc.json`；双开关同时开启时，严格超过阈值的条目被重置，并生成时间戳备份与 `overflow_resets.json` 审计记录。Phase 17 的 NBT 变体路径只有单元测试；由于原版配方不产生 NBT 输出，真实环境尚未验证。Phase 18–25 的新增能力均有单元测试；其中隔离落盘与审批流程已在开发服实机验证（Phase 21），Goety 反射路径仍未在真实环境验证。Phase 22 的计数器、耗时采样、峰值堆采样与取消/失败部分报告均有单元测试；开发服记录到配方扫描总数 1322（可用 1281 / 不支持 41 / 无效 0 / 失败 0）、13 个 tick 样本与 868.3 MiB 峰值堆，但采集、求解与应用仍是单 tick 重活，代表性模组包性能未测量。Phase 23 在开发服完成三场景验证：Run1 冷启动自动扫描生成 75 条，ProjectE 1368→1443，`scan_state.json` 五通道指纹齐全，报告 `solverCacheHits=1208` / `solverCacheMisses=1354`、`solverRounds=2`、扫描总耗时 751 ms；Run2 输入未变化，自动扫描因「指纹未变化且已有缓存」被拒绝，未执行生成映射流程，`last_report.txt` 未重写；Run3 将 `[recipe] selectionStrategy` 从 `lowest_cost` 改为 `prefer_simple` 后，日志报告变化通道 `[config]`，自动扫描重新执行并生成 75 条。取消路径的实机触发未构造（开发服扫描 <1s），由单元测试覆盖；三次服务器均优雅停止，无残留 Java 进程。Phase 24 在开发服完成四场景验证：Run1 使用默认配置无回归，生成条目 0→75、ProjectE 1368→1443，报告 `shapedRecipes=683`、`shapelessRecipes=285`、`ownershipExternalModifications=0`、`ownershipReleasedEntries=0`；Run2 将 `minecraft:amethyst_cluster` 改为 100000 并配置 `safety.dropProtectionExemptItems=["minecraft:amethyst_cluster"]`，重扫后值回到 16，未生成 `quarantined_drops.json`；Run3 配置 `creativeItemExclusions=["minecraft:amethyst_cluster"]` 后生成条目 75→74、`retiredEntries=1`、ProjectE 1443→1442；Run4 对 `bee_nest` 设置 ProjectE 手动值 123，扫描时 manual skip=1，条目 `owned=false` 且未被自动值覆盖，报告 `ownershipExternalModifications=1`、`ownershipReleasedEntries=1`。四次运行均以 SIGTERM 优雅停止（日志包含 `All dimensions are saved`），无残留 Java 进程。真实模组创造物品识别与代表性模组包性能仍未验证。Phase 25 在开发服完成四类验收：A 场景默认配置无回归，生成条目 0→75、ProjectE 1368→1443，无新增告警；B6 场景 legacy TOML 迁移后值与备份正确、重复加载幂等，但注释与未知键会被 Forge 首次加载后的规范化重写丢弃（记录为 Forge 限制）；C1/C2 场景验证 `diagnostic.debugLogging` 关闭时零输出、开启后按类别限流（每秒最多 20 条）；D 场景在缺少 ProjectE-Integration 或 ProjectE provider 不可用时，外部基线与 resolver 退化为零候选，默认行为与 Phase 24 一致。由于当前没有 ProjectE-Integration 环境，`pe_custom_conversions` 的真实基线数据尚未验证。

### Phase 18–26 已完成能力

- **solve → fallback 多轮收敛** —— Phase 18 完成。`ScanPipeline` 最多执行 4 轮「配方求解 → 兜底补种」；本轮既没有新增求解值也没有新增兜底值时提前收敛。
- **创造物品保护配置化** —— Phase 18 完成。`safety.creativeItemExclusions`、`safety.creativeItemSpecialValues`、`safety.creativeNamespaces` 由 `CreativeItemProtection.parseSettings(...)` 解析并接入定价排除；非法条目会被跳过并汇总警告。
- **空 NBT 与默认伤害归一** —— Phase 19/20 完成。配方输出中的空 `CompoundTag`（包括序列化为 `{}` 的形式）按无 NBT 处理；仅包含默认 `{Damage:0}` 的标签不再生成伪变体。
- **排除规则覆盖配方路径** —— Phase 19 完成。`ScanPipeline.isExcludedFromPricing(...)` 同时用于配方候选与兜底候选，被层级规则或创造物品保护排除的物品不会经配方路径重新进入存储。
- **陈旧 owned 条目清理** —— Phase 19 完成。`ScanPipeline.retireStaleOwnedEntries(...)` 会删除本轮既不在候选中、也不在受保护既有值中的自有条目；干跑模式或空快照时跳过。
- **隔离降价落盘与审批** —— Phase 21 引入记录落盘与 `/sudoemc approve`。非干跑模式下每次隔离写入 `quarantined_drops.json`（同一物品保留一条，最多 5000 条）；Phase 26 起 `/sudoemc approve <item>` 默认把降价值写入 `generated_entries.json`（来源 `APPROVED`）并请求 ProjectE 重映射，不再只是标记状态。
- **备选成本与 schema v2** —— Phase 21 完成。`GeneratedEmcEntry.alternativeCosts` 记录同一轮求解中其它有效配方的成本；`generated_entries.json` 从 schema v1 升到 v2（新增 `alternatives` 数组），仍可读取 v1 文件但只写出 v2；`/sudoemc explain` 最多显示 5 条备选成本。
- **诊断报告计数器与失败明细** —— Phase 22 完成。`last_report.txt` 新增配方扫描区块，输出总数/可用/不支持/无效/失败计数，并展示最多 10 条失败配方明细（超出部分只显示剩余总数）；`PipelineResult.extraCounters` 同时承载求解轮数、隔离/清理数量、干跑标记等计数器。
- **扫描耗时与峰值堆采样** —— Phase 22 完成。`ScanManager` 在每个 tick 边界采样耗时与堆内存；报告输出扫描总耗时、tick 采样数、tick 最大/平均/P95 耗时（最近秩法）以及峰值堆内存和报告时瞬时堆内存。
- **取消/失败部分报告** —— Phase 22 完成。`ScanManager.AbortListener` 在取消或失败时通知管线，由 `ScanPipeline.writeCancelledReport` / `writeFailedReport` 写出部分报告，标记 `partialReport=1`、`scanCancelled`/`scanFailed` 与 `scanAborts`。
- **五通道增量指纹与启动跳过** —— Phase 23 完成。`ScanFingerprint` 分别对物品（注册表 id + 稀有度，条目去重）、配方（完整内容摘要）、标签（物品标签并集）、配置（影响定价的配置白名单）与基线（ProjectE 手动值/标签）排序后计算 SHA-256 截断摘要；`ScanStateStore.compare(...)` 返回发生变化的通道。服务器启动时，在「已有缓存且未开启 `alwaysRescan`」的情况下计算当前指纹并比较，完全一致则跳过扫描；指纹计算失败退化为需要扫描。`/sudoemc rescan-all` 直接请求扫描，不受该跳过逻辑影响。
- **协作式取消** —— Phase 23 完成。`ScanCancellation` 令牌由 `ScanManager` 与 `ScanPipeline` 共享；物品/配方扫描、NBT 变体生成、既有值读取、求解与应用循环均设有检查点。取消时不更新扫描指纹、不执行陈旧条目清理与 `custom_emc.json` 改写；已 stage 的条目可能保留，报告写出 `scanCancelled` / `partialReport` 标记。开发服扫描 <1s，实机取消未触发，该路径由单元测试覆盖。
- **有界 LRU 求解缓存** —— Phase 23 完成。`RecipeSolver` 使用默认容量 8192 的访问序 LRU，键包含配方内容、评估策略、评估器注册表与输入值快照；`ScanPipeline` 持有单个 `RecipeSolver` 实例并跨求解轮次复用。报告新增 `solverCacheHits` / `solverCacheMisses` 计数器。
- **配方选择策略** —— Phase 23 完成。新增 `[recipe] selectionStrategy`，支持 `lowest_cost`（默认，成本相同按配方 id）、`prefer_simple`（成本相同优先输入/输出/容器返还更少且非催化剂的配方，最后按 id）与 `stable_id`（成本相同按配方 id）。所有策略都以成本为第一排序键；非法值回退 `lowest_cost` 并只警告一次。
- **降价保护豁免** —— Phase 24 完成。新增 `safety.dropProtectionExemptNamespaces` / `safety.dropProtectionExemptItems`，接入 `LargeDropProtection.Settings` 与 `Sudoersemc.createDropProtection()`；按命名空间（忽略 path）或精确物品 id 跳过大幅降价隔离，非法条目会被跳过并汇总为一条警告。
- **创造物品自动识别** —— Phase 24 完成。新增 `safety.autoDetectCreativeOnlyItems`（默认 `true`）、`safety.creativeOnlyItemPatterns`（默认只匹配原版 command/structure 物品）与 `safety.autoDetectCreativeOnlyNamespaces`（默认空）；现有显式排除、固定值与 `creativeNamespaces` 规则保持不变，自动识别只匹配配置的正则/命名空间，不会隐式影响普通模组物品。
- **OwnershipManager 外部修改检测** —— Phase 24 完成。新增 `safety.preserveExternallyModifiedValues`（默认 `true`）；应用前比较 owned 条目的持久化值与本轮后端观测值，并识别 ProjectE 手动值，命中后释放所有权并保留外部值，阻止本轮自动值覆盖；报告新增 `ownershipExternalModifications` / `ownershipReleasedEntries`。
- **配方扫描完整性** —— Phase 24 完成。`RecipeScanner` 区分 shaped/shapeless（报告新增 `shapedRecipes` / `shapelessRecipes`）、按 `canCraftInDimensions` 与实际 width×height 支持非 3x3 合成，并读取容器返还（`recipe.scanContainerRemainders`，默认 `true`）；`StandardRecipeEvaluator` 支持容器返还按 `recipe.creditContainerRemainders`（默认 `true`）抵扣成本，以及可选的 `recipe.priceSecondaryOutputs`（默认 `false`）多输出按总单位拆分计价；关闭副产物定价时整份净成本只归主输出，避免重复计价。
- **配置 schema 迁移接入 Forge TOML** —— Phase 25 完成。根级新增 `schemaVersion`（默认 legacy `1`，当前构建支持 `2`）；`Sudoersemc` 在 `SudoersemcConfig.register()` 之前直接迁移磁盘 TOML，这是 Forge 按 spec 重写文件前唯一能保留原始内容的时机；`ConfigTomlMigration` 采用临时文件修改、时间戳 `.bak` 备份与原子替换，高版本拒绝迁移，非法版本保留原文件。`ModConfigEvent` 内的迁移入口作为 reload/兜底且幂等。
- **ProjectE-Integration 外部基线** —— Phase 25 完成。`fallback.useProjectEIntegrationBaselines`（默认 `true`）在模组 `projecteintegration` 已加载且 ProjectE provider 可用时，读取数据包 `pe_custom_conversions` 中 `values.before` 的正整数条目；`#tag` 键、损坏条目与损坏文件按跳过处理。基线只作为 `EXTERNAL_BASELINE` 候选进入兜底链，仍会经过排除、手动值、`fallback.minEmc`、大幅降价与溢出保护。
- **`diagnostic.debugLogging` 限流日志** —— Phase 25 完成。该键在运行时重建时生效；关闭时高频路径只做一次 volatile 读取且不构造消息，开启后按类别使用每秒最多 20 条的固定窗口限流，并通过 `DebugLog.summary()` 汇总 `total/emitted/suppressed`。
- **ResolverRegistry 生产接线** —— Phase 25 完成。`Sudoersemc.resolverRegistry()` 暴露进程级注册表，第三方模组可注册 NBT 材料解析器；解析按注册顺序进行，第一个返回值大于 0 的解析器获胜，解析器异常只记录警告并继续下一个。注册表跨配置重建存活，其类名指纹参与基线通道，因此新增/移除解析器会触发重扫。
- **审批后自动应用** —— Phase 26 完成。`/sudoemc approve <item>` 先经过 `ApprovedDropApplier` 的 fail-closed 规则（已应用不重复、ProjectE 手动值、排除规则、最小 EMC、`overflow.threshold`、不得抬高旧值/当前值），再把 `proposedValue` 写入 `GeneratedEntryStore`（来源 `APPROVED`），确认落盘后才标记隔离记录的 `applied` 并请求 ProjectE 重映射。写入失败会回滚生成条目且不标记批准；`safety.approveAppliesImmediately=false` 时退化为审计模式，只标记 `approved` 不修改 EMC。Phase 21 遗留的 `approved=true` 记录与审计模式记录在开关恢复后仍可应用；`applied` 字段跨重启保留。

### 尚未实现 / 当前不生效

以下能力**尚未实现**、**当前不生效**，或存在需要留意的已知限制：

- **真正分片的分批扫描与工作线程** —— 尚未实现。`performance.itemsPerTick` 与 `performance.adaptiveBatchSizing` 只影响进度推进的批量粒度；采集、求解与应用仍在服务器线程整体执行一次，没有线程池或后台线程。Phase 22 已加入 tick 耗时与堆内存采样，但采集/应用仍是单 tick 重活（“伪分片”），P95 在样本少时会被稀释，应同时查看最大 tick 耗时；代表性模组包性能仍未测量。Phase 23 的取消检查点与求解缓存、Phase 24 的配方扫描完整性、Phase 25 的配置迁移/外部基线/调试日志与 resolver 接线都不改变这一单线程执行模型。
- **Forge 会重写 TOML（已知限制）** —— Forge 在加载后会按 spec 重写配置文件，未知键与手写注释可能在首次加载时被 Forge 丢弃；SudoersEMC 的解析前迁移保证迁移前内容完整备份为时间戳 `.bak`，但无法阻止 Forge 的后续规范化。
- **审批应用的边界（已知限制）** —— `/sudoemc approve <item>` 默认立即写入降价值，但只支持普通物品 id（不支持 NBT 变体），且不会自动触发重扫；若随后执行扫描，该物品会按正常定价与降价保护重新判定，APPROVED 来源可能被新候选覆盖。`safety.approveAppliesImmediately=false` 时只记录审批状态。生成条目写入失败会回滚且不标记批准；若生成条目已落盘但隔离文件保存失败，命令会报告警告，重启后再次 approve 会自动补齐 `applied` 标记。
- **Goety 真实环境验证** —— 尚未进行。Phase 20 已把必需字段读取失败改为 fail-closed（跳过整条配方），但反射路径仍只有在真实 Goety 环境中才能完整验证；当前没有实机记录。
- **代表性模组包性能测量** —— 尚未进行。Phase 22 只在开发服记录了扫描耗时、tick 采样与峰值堆，没有在代表性模组包上测量，也没有基线对比。
- **NBT 变体的真实环境验证** —— Phase 17 已接线并有单元测试，Phase 19/20 只补充空 NBT 与默认 `{Damage:0}` 归一；原版配方不产生 NBT 输出，真实环境尚未验证，`/sudoemc explain` 也不覆盖带变体条目。

---

## 环境要求

| 项目      | 版本                                                                      |
| --------- | ------------------------------------------------------------------------- |
| Minecraft | 1.20.1                                                                    |
| Forge     | 47.2.0 及以上（47.x）                                                     |
| Java      | 17                                                                        |
| ProjectE  | 1.0.1（可选；缺失时模组仍会扫描并保存生成值，但不会向 ProjectE 注入 EMC） |
| Gradle    | 8.1.1（随 Wrapper 提供）                                                  |

### 可选联动

- **ProjectE-Integration** — 与本模组互不干扰。通过 `IEMCProxy` 可见的已有 EMC 会被视为「已定价」，本模组不会覆盖；Phase 25 起 `fallback.useProjectEIntegrationBaselines`（默认 `true`）会在模组 `projecteintegration` 与 ProjectE provider 均可用时，读取数据包 `pe_custom_conversions` 中 `values.before` 的正整数条目，作为 `EXTERNAL_BASELINE` 候选进入兜底链；缺少模组或 provider 时退化为空基线。
- **Goety** — 代码层支持 Goety 2.5.60.6 的 `goety:ritual`：只有 `goety:craft` 类型参与物品定价，召唤、转化、附魔、传送与定位仪式会被跳过。反射解析路径尚未在真实 Goety 环境实机验证；解析失败时会降级为「不可用」并跳过仪式。

---

## 构建

```bash
# Linux / macOS
./gradlew build

# Windows
gradlew.bat build
```

产物位于 `build/libs/sudoersemc-<version>.jar`。

仅编译主代码（跳过测试）：

```bash
./gradlew compileJava
```

运行单元测试：

```bash
./gradlew test
```

启动开发用专用服务器：

```bash
./gradlew runServer
```

可选参数：

| 参数                | 作用                                             |
| ------------------- | ------------------------------------------------ |
| `-PsmokeTest`       | 启动后向内嵌探针写入一个测试 EMC 值并触发重算    |
| `-PsmokeQuery`      | 只查询内嵌探针的值，用于验证重启后持久化是否生效 |
| `-PwithoutProjectE` | 不加载 ProjectE，用于验证降级路径                |

> 这些参数只影响开发环境，不会进入发布 jar 的行为。

---

## 安装

1. 安装 Minecraft Forge 1.20.1（47.x）。
2. 将 `sudoersemc-<version>.jar` 放入 `mods/` 目录。
3. 如需让生成的 EMC 在游戏内生效，同时安装 ProjectE 1.0.1。
4. 启动服务器或客户端。首次启动会生成配置文件。

---

## 配置

配置文件：`config/sudoersemc-common.toml`

> 下列键由 Forge 生成到配置文件中，当前文档列出的键均由生产代码读取。Phase 16 已接入热重载：`ModConfigEvent` 会重建运行时组件，`/sudoemc reload` 也会用当前已加载的配置值触发同一重建路径。扫描进行中时重建会延迟到扫描结束；交叉字段校验失败时拒绝重建并保留上一份有效组件。Phase 25 起根级 `schemaVersion` 已接入：模块加载时会在 Forge 解析前迁移磁盘 TOML，迁移前内容备份为时间戳 `.bak`。

### 自动扫描

`general.enabled = true` 时启用启动自动扫描；`general.alwaysRescan = true` 每次启动都强制重扫；两者都不满足时，启动会枚举当前物品注册表/配方并计算五通道输入指纹：仅在「从未扫描」「已生成条目为空」或指纹与 `scan_state.json` 保存值不一致时触发一次扫描，否则复用缓存。指纹计算失败会退化为需要扫描。`/sudoemc rescan-all` 直接请求扫描，不受该跳过判断影响。自动扫描与是否安装 ProjectE 无关。这两个键只在服务器启动时读取，修改后需重启服务器才会改变自动扫描决策；其余扫描相关键在 `/sudoemc reload` 或 Forge 配置重载时通过重建组件生效。

```toml
schemaVersion = 1   # 根级；请勿手动修改（当前构建支持 2）

[general]
enabled = true
alwaysRescan = false

[performance]
itemsPerTick = 100
adaptiveBatchSizing = true

[recipe]
solveRecipes = true
maxRecipePasses = 20
allowRecipeValueIncrease = false
selectionStrategy = "lowest_cost"   # lowest_cost | prefer_simple | stable_id
scanContainerRemainders = true      # Phase 24；读取合成容器返还（桶、瓶子等）
creditContainerRemainders = true    # Phase 24；按返还容器价值抵扣配方成本
priceSecondaryOutputs = false       # Phase 24；true 时多输出按总单位拆分计价，默认只给主输出

[fallback]
useFallbackForUnsolvable = true
useTagPricing = true
useRarityPricing = true
useProjectEIntegrationBaselines = true   # Phase 25 已生效；需要 ProjectE-Integration 且 ProjectE provider 可用
minEmc = 1

[safety]
largeDropProtection = true
maxAllowedDropRatio = 0.5
protectCreativeOnlyItems = true
creativeItemExclusions = []          # Phase 18 已接线；默认空列表
creativeItemSpecialValues = []       # Phase 18 已接线；格式 namespace:path=正整数；默认空列表
creativeNamespaces = []              # Phase 18 已接线；默认空列表
dropProtectionExemptNamespaces = []  # Phase 24；按命名空间豁免大幅降价隔离（path 忽略）
dropProtectionExemptItems = []       # Phase 24；按 namespace:path 精确豁免
autoDetectCreativeOnlyItems = true   # Phase 24；启用已知创造专属物品自动识别
creativeOnlyItemPatterns = [         # Phase 24；默认仅匹配原版 command/structure 物品
  "^minecraft:(barrier|chain_command_block|command_block|command_block_minecart|debug_stick|jigsaw|knowledge_book|light|repeating_command_block|structure_block|structure_void)$"
]
autoDetectCreativeOnlyNamespaces = [] # Phase 24；默认空，避免误伤普通模组物品
preserveExternallyModifiedValues = true # Phase 24；外部/手动值修改时释放所有权并保留外部值

[overflow]
enabled = true
threshold = 999999
resetMin = 100
resetMax = 999000
bucketCount = 100
weightCap = 20
overwriteScope = "owned_only"   # owned_only | entire_custom_emc
allowProjectEFileRewrite = false
randomSeed = 0                  # 0 = 每次启动重新随机

[nbt]
scanNbtVariants = true          # Phase 17 已接线；真实环境尚未验证
maxNbtVariantsPerItem = 100     # Phase 17 已接线；实际扫描受硬上限 1000 限制

[diagnostic]
debugLogging = false            # Phase 25 已生效；开启后按类别每秒最多 20 条
generateReports = true
```

`recipe.selectionStrategy` 决定同一物品存在多条同成本配方时的取舍：`lowest_cost`（默认，成本相同按配方 id）、`prefer_simple`（成本相同优先输入/输出/容器返还更少且非催化剂的配方，最后按 id）、`stable_id`（成本相同按配方 id）。所有策略都以成本为第一排序键，不会接受高于最低可行成本的配方；非法值回退 `lowest_cost` 并只记录一次警告。该键参与五通道配置指纹，修改后会在下一次服务器启动时触发增量扫描。

Phase 24 新增的 `[recipe]` 键：`scanContainerRemainders`（默认 `true`）在扫描合成配方时读取容器返还；`creditContainerRemainders`（默认 `true`）在评估成本时按返还容器的已知 EMC 抵扣，避免桶/瓶等被重复计价；`priceSecondaryOutputs`（默认 `false`）开启后，多输出配方按全部产出的总单位拆分净成本，所有产出获得同一单位值，关闭时整份净成本只归主输出。

Phase 24 新增的 `[safety]` 键：`dropProtectionExemptNamespaces` / `dropProtectionExemptItems` 分别按命名空间和精确物品 id 跳过大幅降价隔离（非法条目跳过并汇总警告）；`autoDetectCreativeOnlyItems`（默认 `true`）启用自动识别，`creativeOnlyItemPatterns` 默认只匹配原版 command/structure 物品，`autoDetectCreativeOnlyNamespaces` 默认空，自动识别不会隐式影响普通模组物品；显式排除、固定值与 `creativeNamespaces` 规则保持原有语义。`preserveExternallyModifiedValues`（默认 `true`）在应用前检测 owned 条目被外部/ProjectE 手动值修改，命中后释放所有权并保留外部值，阻止本轮自动值覆盖。真实模组创造物品仍需显式配置。
Phase 25 新增/更新的配置键：根级 `schemaVersion` 默认 `1`（legacy），当前构建支持 `2`，请勿手动修改；模块会在 Forge 解析前把旧文件迁移到当前版本并备份原文件。`fallback.useProjectEIntegrationBaselines`（默认 `true`）已实际生效：仅当 ProjectE-Integration 已安装且 ProjectE provider 可用时，读取 `pe_custom_conversions` 数据包中的 `values.before` 作为外部基线，否则返回空基线。`diagnostic.debugLogging`（默认 `false`）已实际生效：开启后按类别输出调试日志，每类别每秒最多 20 条，被抑制条目在扫描结束时汇总；关闭时高频路径只做一次 volatile 读取。

### 溢出保护

当 SudoersEMC 准备写入的**生成候选值严格大于** `threshold` 时，它会被替换为 `[resetMin, resetMax]` 区间内的加权随机值：

- 区间被切成 `bucketCount` 个桶，桶权重按二次曲线向低值倾斜；
- 每个桶的权重被硬性限制在 `[1, weightCap]`，因此最高值始终可达，但不会被某个桶以接近 100% 的概率垄断；
- `randomSeed` 非 0 时结果可复现，便于测试；`0` 表示每次启动重新随机；
- 已处于重置区间内的值不会被再次随机化（幂等）。

ProjectE 原生值与 `custom_emc.json` 中的手动值不会进入这条候选值路径；手动文件只能通过下方的 `ENTIRE_CUSTOM_EMC` 受控改写修改。

### 改写范围

| 取值                 | 行为                                                                                                                                                                                                                                                                                                                                                                                                                                             |
| -------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------ |
| `owned_only`（默认） | 只重置由 SudoersEMC 自己生成并准备写入的候选条目。                                                                                                                                                                                                                                                                                                                                                                                               |
| `entire_custom_emc`  | 受控改写 ProjectE 的 `custom_emc.json`，**需要同时开启 `allowProjectEFileRewrite`**。启用后，文件中严格大于 `threshold` 的条目会被重置到 `[resetMin, resetMax]`；写前生成 `custom_emc.json.<时间戳>.bak` 备份，写入采用临时文件 + 原子替换，未知字段与键顺序保留。文件缺失、损坏或结构不符时保持原样。改写结果记入 `overflow_resets.json`（`scope = ENTIRE_CUSTOM_EMC`）。仅开启其中任一开关都不会改写文件；scope 冲突会在加载或重载时记录警告。 |

作用域与开关在运行时重建时读取；`/sudoemc reload` 或 Forge 配置重载后可以生效。

---

## 命令

所有子命令都注册在 `/sudoemc` 下，并要求权限等级 2（管理员）。

| 命令                      | 作用                                           | 说明                                                                                                                                                                 |
| ------------------------- | ---------------------------------------------- | -------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `/sudoemc status`         | 查看扫描阶段、进度、上次耗时与上次结果         | 只读内存进度与扫描状态文件                                                                                                                                           |
| `/sudoemc rescan-all`     | 请求一次全量重新扫描                           | 已有扫描进行中时返回失败提示；不受启动指纹跳过影响                                                                                                                   |
| `/sudoemc cancel`         | 请求取消当前扫描                               | 采集、求解与应用循环在检查点响应；取消时不更新扫描指纹，报告标记为部分报告                                                                                           |
| `/sudoemc reload`         | 按当前已加载的配置重建运行时组件               | 扫描中延后到扫描结束；校验失败时保留上一份有效配置                                                                                                                   |
| `/sudoemc report`         | 读取 `config/sudoersemc/last_report.txt`       | 尚无报告时提示先执行一次扫描                                                                                                                                         |
| `/sudoemc explain <item>` | 解释某个已生成条目的来源、配方、归属与备选成本 | 只支持无 NBT 变体的普通条目；最多显示 5 条备选成本；ProjectE 原生值/手动值会单独提示                                                                                 |
| `/sudoemc approve <item>` | 批准该物品当前的隔离降价记录                   | 默认立即写入降价值（来源 `APPROVED`）并请求 ProjectE 重映射；`safety.approveAppliesImmediately=false` 时只标记状态；不触发重扫，仅支持普通物品 id（不支持 NBT 变体） |

---

## 配置校验与迁移

### 当前实际行为

- **单字段范围**由 Forge 的 `defineInRange` 在加载时校验，越界值会被纠正到允许区间。覆盖的键包括 `performance.itemsPerTick`、`recipe.maxRecipePasses`、`fallback.minEmc`、`overflow.threshold`、`overflow.resetMin`、`overflow.resetMax`、`overflow.bucketCount`、`overflow.weightCap`、`safety.maxAllowedDropRatio` 与 `nbt.maxNbtVariantsPerItem`。
- **运行时交叉字段校验**已由 Phase 16 接线：`ConfigValidator.validateCurrent()` 在配置加载/重载事件与 `/sudoemc reload` 时执行 `validateCrossField(...)`。`overflow.threshold <= resetMax`、`overflow.resetMin > resetMax`、`overflow.resetMin < 1`、`bucketCount < 1`、`weightCap < 1` 会拒绝重建并保留上一份有效组件；`overwriteScope = entire_custom_emc` 且 `allowProjectEFileRewrite = false` 只记录警告，不改写文件。
- 非法的 `overflow.overwriteScope` 会在解析时回退为 `owned_only` 并记录警告，不会拒绝重建。
- 非法的 `recipe.selectionStrategy` 会在解析时回退为 `lowest_cost` 并只记录一次警告，不会拒绝重建；该键参与配置指纹，修改后会在下一次服务器启动时触发增量扫描。
- Phase 24 新增的列表键 `safety.dropProtectionExemptNamespaces` / `dropProtectionExemptItems` / `creativeOnlyItemPatterns` / `autoDetectCreativeOnlyNamespaces` 由 `ConfigValidator` 逐条校验；非法条目只会被跳过并汇总警告，不会拒绝重建。
- `safety.maxAllowedDropRatio` 的 Forge 范围是 `[0.0, 1.0]`；若设为 `0.0`，`LargeDropProtection.Settings` 构造会失败，扫描管线回退到默认 `0.5`（不是拒绝整个配置）。
- `nbt.maxNbtVariantsPerItem` 的 Forge 范围是 `[1, Integer.MAX_VALUE]`；运行时不拒绝大于 `1000` 的值，但 `VariantScanner` 会用硬上限 `1000` 截断实际扫描数量。
- 启动时若溢出设置构造失败，扫描管线会回退到内置默认值；运行期重建采用「全有或全无」：新组件全部构建成功后才替换旧组件。
- `/sudoemc reload` 会用当前已加载的配置值重建运行时组件；扫描进行中时返回「将在扫描结束后生效」，校验失败时返回「已保留上一份有效配置」。它不会主动重新读取磁盘上的 TOML；直接编辑配置文件后，Forge 检测到变化时会触发 `ModConfigEvent.Reloading`，走同一重建路径。
- `ConfigValidator` 与 `ConfigWatcher` 已接入生产配置加载流程；`ConfigMigrations` 已由 `ConfigTomlMigration` 接入：模块加载时在 Forge 解析前迁移磁盘 TOML，`ModConfigEvent` 内的入口作为 reload/兜底。

### 默认值

| 配置项                                                                               | 默认值                      | 说明                                                                                          |
| ------------------------------------------------------------------------------------ | --------------------------- | --------------------------------------------------------------------------------------------- |
| `schemaVersion`                                                                      | `1`                         | Phase 25；根级键，默认 legacy 1，当前构建迁移到 2；请勿手动修改                               |
| `performance.itemsPerTick`                                                           | `100`                       | 每 tick 推进的物品进度上限；不切分实际工作                                                    |
| `performance.adaptiveBatchSizing`                                                    | `true`                      | 按观测耗时调整进度批量                                                                        |
| `recipe.maxRecipePasses`                                                             | `20`                        | 单次扫描的配方求解遍数上限                                                                    |
| `recipe.allowRecipeValueIncrease`                                                    | `false`                     | 是否允许配方推导值高于输入之和                                                                |
| `recipe.selectionStrategy`                                                           | `lowest_cost`               | 同一物品多条同成本配方时的取舍：`lowest_cost` / `prefer_simple` / `stable_id`；非法值回退默认 |
| `recipe.scanContainerRemainders`                                                     | `true`                      | Phase 24；扫描合成配方时读取容器返还（桶、瓶子等）                                            |
| `recipe.creditContainerRemainders`                                                   | `true`                      | Phase 24；按返还容器的已知 EMC 抵扣配方成本                                                   |
| `recipe.priceSecondaryOutputs`                                                       | `false`                     | Phase 24；true 时多输出按总单位拆分计价，否则整份净成本只归主输出                             |
| `fallback.minEmc`                                                                    | `1`                         | 生成 EMC 下限；同时作为配方求解的 `minimumEmc`                                                |
| `fallback.useProjectEIntegrationBaselines`                                           | `true`                      | Phase 25；需要安装 ProjectE-Integration 且 ProjectE provider 可用，否则空基线                 |
| `overflow.threshold`                                                                 | `999999`                    | 严格大于该值的生成候选值会被重置                                                              |
| `overflow.resetMin`                                                                  | `100`                       | 重置区间下界（含）                                                                            |
| `overflow.resetMax`                                                                  | `999000`                    | 重置区间上界（含）                                                                            |
| `overflow.bucketCount`                                                               | `100`                       | 加权采样桶数量                                                                                |
| `overflow.weightCap`                                                                 | `20`                        | 单个桶的权重上限                                                                              |
| `overflow.overwriteScope`                                                            | `owned_only`                | 重置范围，可选 `owned_only` / `entire_custom_emc`                                             |
| `overflow.allowProjectEFileRewrite`                                                  | `false`                     | 是否允许改写 ProjectE 的 `custom_emc.json`                                                    |
| `overflow.randomSeed`                                                                | `0`                         | `0` 表示每次启动重新随机                                                                      |
| `safety.maxAllowedDropRatio`                                                         | `0.5`                       | 单次生成值允许的最大相对跌幅                                                                  |
| `nbt.maxNbtVariantsPerItem`                                                          | `100`                       | 每个物品最多保留的 NBT 变体数；实际还受硬上限 `1000` 限制                                     |
| `safety.creativeItemExclusions` / `creativeItemSpecialValues` / `creativeNamespaces` | 空列表                      | Phase 18 已接线；排除项与命名空间默认为空、无固定值；非法条目会被跳过并汇总警告               |
| `safety.dropProtectionExemptNamespaces` / `dropProtectionExemptItems`                | 空列表                      | Phase 24；按命名空间/精确物品 id 豁免大幅降价隔离；非法条目跳过并汇总警告                     |
| `safety.autoDetectCreativeOnlyItems`                                                 | `true`                      | Phase 24；启用已知创造专属物品自动识别                                                        |
| `safety.creativeOnlyItemPatterns`                                                    | 原版 command/structure 正则 | Phase 24；默认只匹配 `minecraft:barrier`、`command_block`、`structure_block` 等原版物品       |
| `safety.autoDetectCreativeOnlyNamespaces`                                            | 空列表                      | Phase 24；默认空，避免误伤普通模组物品                                                        |
| `safety.preserveExternallyModifiedValues`                                            | `true`                      | Phase 24；外部/ProjectE 手动值修改时释放所有权并保留外部值                                    |
| `safety.approveAppliesImmediately`                                                   | `true`                      | Phase 26；`/sudoemc approve` 是否立即写入降价值；`false` 时只记录审批状态                     |
| `diagnostic.debugLogging`                                                            | `false`                     | Phase 25；开启后按类别每秒最多 20 条的限流调试日志                                            |

### 交叉字段校验（Phase 16 已接线）

| 条件                                                                       | 当前行为                                                                |
| -------------------------------------------------------------------------- | ----------------------------------------------------------------------- |
| `overflow.threshold <= resetMax`                                           | 加载阶段记录警告；运行时重建被拒绝，保留上一份有效组件                  |
| `overflow.resetMin > resetMax`                                             | 同上                                                                    |
| `overflow.resetMin < 1` / `bucketCount < 1` / `weightCap < 1`              | Forge `defineInRange` 在加载时纠正越界值；运行时校验也会拒绝重建        |
| `overwriteScope = entire_custom_emc` 且 `allowProjectEFileRewrite = false` | 只记录警告；不改写 `custom_emc.json`                                    |
| 非法 `overwriteScope`                                                      | 回退为 `owned_only` 并记录警告；不拒绝重建                              |
| `nbt.maxNbtVariantsPerItem > 1000`                                         | 运行时不拒绝；`VariantScanner` 以硬上限 `1000` 截断实际扫描数量         |
| `safety.maxAllowedDropRatio = 0.0`                                         | Forge 接受；`LargeDropProtection.Settings` 构造失败，管线回退默认 `0.5` |
| `fallback.minEmc < 0`                                                      | Forge `defineInRange` 在加载时纠正到 `0`                                |

### 警告条件

| 条件                                                                                                                                              | 提示                                               |
| ------------------------------------------------------------------------------------------------------------------------------------------------- | -------------------------------------------------- |
| `overwriteScope = entire_custom_emc` 且 `allowProjectEFileRewrite = false`                                                                        | ProjectE 文件改写会被跳过                          |
| `threshold <= resetMax` / `resetMin > resetMax` / `resetMin < 1`                                                                                  | 加载阶段记录配置警告；运行时重建被拒绝并保留旧组件 |
| `bucketCount < 1` / `weightCap < 1`                                                                                                               | 同上                                               |
| 非法 `overwriteScope`                                                                                                                             | 记录警告并回退 `owned_only`                        |
| 非法 `recipe.selectionStrategy`                                                                                                                   | 记录一次警告并回退 `lowest_cost`                   |
| 非法 `safety.dropProtectionExemptNamespaces` / `dropProtectionExemptItems` / `creativeOnlyItemPatterns` / `autoDetectCreativeOnlyNamespaces` 条目 | 跳过该条目并汇总警告                               |

`ConfigWatcher` 对相同警告文本做去重（每个实例生命周期内只记录一次）；`SudoersemcConfig.validateLoadedConfig()` 在每次加载/重载时仍会直接记录溢出配置警告，因此日志中可能同时看到两条来源不同的提示。

### 迁移（Phase 25 已接入）

Forge 1.20.1 没有公开的「解析前」配置钩子。Phase 25 在 `SudoersemcConfig.register()` 之前直接迁移磁盘上的 `config/sudoersemc-common.toml`，这是 Forge 按 spec 重写文件前唯一能保留原始内容的时机；`ModConfigEvent.Loading/Reloading` 内的迁移入口作为 reload/兜底，已迁移文件返回 `NO_OP`。

- **当前版本**：`schemaVersion = 2`，与 `ConfigMigrations.SCHEMA_VERSION` 一致；根级键默认值为 legacy `1`。
- **v1 → v2**：缺少 `overflow.threshold` 时补充 `999999`，缺少 `overflow.overwriteScope` 时补充 `owned_only`；已有值保持不变。
- **v2 → v2**：不做任何修改，也不产生提示。
- **高于 v2**：拒绝迁移并保留原文件（`REFUSED_NEWER`），绝不覆盖未来版本的配置。
- **未知版本（`<= 0`）**：`schemaVersion = 0` 按 legacy 1 处理并迁移；缺失键同样按 legacy 1 处理。
- **非法版本**：负数、非数字字符串、布尔/数组/小数等非法类型保留原文件并记录警告（`FAILED_RETAINED`）。
- **写盘策略**：先在临时文件上修改已知键，成功后把原文件备份为 `sudoersemc-common.toml.<时间戳>.bak`，再以原子替换写回；文件系统不支持原子移动时降级为普通替换。任何失败都保留原文件并清理临时文件。
- 迁移只读写 `schemaVersion`、`overflow.threshold` 与 `overflow.overwriteScope`，不会整体重写配置。

> **已知限制：** Forge 加载后会按 spec 重写 TOML，未知键与手写注释可能在首次加载时被 Forge 丢弃。解析前迁移保证迁移前内容完整备份为时间戳 `.bak`，但无法阻止 Forge 的后续规范化。另外，`schemaVersion` 超过 `Integer.MAX_VALUE` 时解析前迁移会拒绝，但 Forge 随后仍可能把越界值纠正为默认 `1`。

> **English:** Phase 25 wires config migration before Forge parses the TOML. Legacy files are migrated to schema 2, backed up as a timestamped `.bak`, then replaced atomically; newer versions are refused and left untouched. Forge still normalizes the file on load, so comments and unknown keys may be dropped after the first load, and an out-of-range `schemaVersion` may be corrected to the default `1` by Forge.

---

## 数据文件

| 路径                                       | 说明                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                |
| ------------------------------------------ | --------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `config/sudoersemc/generated_entries.json` | 生成值、来源（含 Phase 26 的 `APPROVED`）、配方、归属、NBT `variantHash`（如有）、备选成本与时间戳；schema v3，仍可读取 v1/v2；只允许更新/删除 `owned=true` 的条目；Phase 24 检测到外部修改时会把条目释放为 `owned=false`                                                                                                                                                                                                                                                                                                                                                                                                                           |
| `config/sudoersemc/overflow_resets.json`   | 溢出重置审计记录；只记录经溢出处理且实际改变数值的条目（含 `ENTIRE_CUSTOM_EMC` 文件改写），服务器停止时落盘                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                         |
| `config/sudoersemc/scan_state.json`        | 上次扫描时间、耗时、已定价/未解析数量与五通道输入指纹（item/recipe/tag/config/baseline）；启动时用于判断输入是否变化并跳过扫描，指纹计算失败或文件缺失时触发扫描                                                                                                                                                                                                                                                                                                                                                                                                                                                                                    |
| `config/sudoersemc/quarantined_drops.json` | 大幅降价隔离记录：旧值、拟写入值、降幅、原因、时间戳、`approved` 与 `applied`（schema v2；旧版缺失 `applied` 按 false 读取）；同一物品最多一条，最多 5000 条，首次重写前备份；干跑模式不写盘                                                                                                                                                                                                                                                                                                                                                                                                                                                        |
| `config/sudoersemc/last_report.txt`        | 每轮扫描后写出的诊断报告；Phase 22 起包含配方扫描统计与失败明细、tick 耗时（最大/平均/P95）、峰值堆与报告时堆内存；Phase 23 起另含求解轮次与 `solverCacheHits` / `solverCacheMisses`；Phase 24 起另含 `shapedRecipes` / `shapelessRecipes`、`multiOutputRecipes` / `containerRemainderRecipes` 与 `ownershipExternalModifications` / `ownershipReleasedEntries`；Phase 25 起另含 `externalBaselineAvailable` / `externalBaselineEntries` / `externalBaselineApplied` 与 `resolverRegistered` / `resolverApplied` 计数器；Phase 26 起新增 `已批准应用` 行与 `APPROVED` 来源计数（扫描开始时仍生效的已批准条目）；取消/失败时写出标记为部分报告的版本 |
| `config/ProjectE/custom_emc.json`          | ProjectE 手动配置；**默认只读**；`entire_custom_emc` + `allowProjectEFileRewrite` 双开关开启时会被受控改写并备份为 `custom_emc.json.<时间戳>.bak`                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                   |

本模组自有 JSON 与 `last_report.txt` 的写入采用「临时文件 + 原子替换」；ProjectE 文件改写同样先备份再原子替换。加载失败只告警不崩溃，遇到更高 schema 版本时拒绝覆盖。`scan_state.json` 的五通道指纹参与启动跳过判断；取消的扫描不会更新它。隔离降价会写入 `quarantined_drops.json`；Phase 26 起审批会先确认 `generated_entries.json` 落盘，再标记 `approved/applied`，写入失败会回滚且不标记批准；干跑模式不产生隔离文件。Phase 24 的外部修改检测不会新增数据文件：命中时会释放 `generated_entries.json` 中对应条目的所有权并保留外部值，计数写入 `last_report.txt`。Phase 25 的配置迁移会先把原文件备份为 `config/sudoersemc-common.toml.<时间戳>.bak`，再以原子替换写回迁移后的 TOML；Forge 后续仍会按 spec 规范化该文件。

---

## 工作原理

服务器启动时，模块会先在 Forge 解析前迁移磁盘 TOML；若自动扫描已启用且未设置 `alwaysRescan`，模组会再计算五通道输入指纹并与 `scan_state.json` 比较；一致时跳过本轮扫描，不一致或计算失败时进入下列流程。`/sudoemc rescan-all` 始终直接请求扫描。

1. **查询** — 通过 `IEMCProxy` 判断物品是否已有 EMC；已有值（SudoersEMC 自有条目除外）会被视为已定价。
2. **计算** — 从配方、标签、稀有度、层级与兜底规则推导候选值；标签与稀有度取自实际物品快照（`getTags()` / `getRarity()`）。同一物品有多个配方时，按多轮求解（含循环检测）取最低的有效确定性成本，成本相同时按 `[recipe] selectionStrategy` 取舍；Phase 23 起 `RecipeSolver` 使用有界 LRU 评估缓存并在配方循环中检查取消令牌。Phase 24 起 `RecipeScanner` 区分 shaped/shapeless 并按实际 width×height 支持非 3x3，容器返还写入配方快照；`StandardRecipeEvaluator` 在 `recipe.creditContainerRemainders` 开启时抵扣返还容器价值，并在 `recipe.priceSecondaryOutputs` 开启时按全部产出的总单位拆分净成本。Phase 17 起，配方输出中观测到的 NBT 会生成带 `variantHash` 的变体快照并进入同一求解流程；Phase 19/20 起空 NBT 与仅含默认 `{Damage:0}` 的标签按普通物品处理。Phase 25 起，兜底链可在 TAG/RARITY 之前使用 ProjectE-Integration 外部基线或第三方 NBT resolver 的 `EXTERNAL_BASELINE` 候选，并同样先经过排除、手动值、最小 EMC 与溢出阈值检查。原版配方不产生 NBT 输出，真实环境未验证。求解只读取不可变快照、在服务器线程执行，当前没有后台线程。
3. **安全** — 对生成候选值应用降价保护（超过阈值的降价写入隔离记录并等待审批，支持按命名空间/物品豁免）、创造专属物品保护（支持显式配置与自动识别，自动识别默认只匹配原版 command/structure 物品）与溢出保护；应用前检测 owned 条目是否被外部或 ProjectE 手动值修改，命中则释放所有权并保留外部值，阻止本轮覆盖；双开关开启时另做 `custom_emc.json` 的受控文件改写。
4. **持久化** — 把结果写入 `generated_entries.json`，并标记归属（`owned=true`）；后续扫描会先清空自有条目再重新求解，并清理不再参与定价的陈旧自有条目，使配方或标签变化能够反映到生成值上。Phase 24 检测到外部修改的条目会以 `owned=false` 保留外部值，本轮不再覆盖。取消的扫描不会更新 `scan_state.json` 指纹，并跳过陈旧条目清理与 `custom_emc.json` 改写。
5. **写入** — 通过 ProjectE 官方的 `@EMCMapper(priority = -1000)` 扩展点，在 ProjectE 重算 EMC 时以 `setValueBefore` 注入自有条目；手动值会被跳过。
6. **重算** — 在服务器线程触发一次资源重载，ProjectE 随即重建 EMC 映射。
7. **诊断** — 扫描管线把配方扫描统计、求解轮数、求解缓存命中/未命中、隔离/清理数量、tick 耗时与堆内存采样集中到 `PipelineResult.extraCounters`，扫描结束后写入 `last_report.txt`；Phase 24 另输出 `shapedRecipes` / `shapelessRecipes`、`multiOutputRecipes` / `containerRemainderRecipes` 与 `ownershipExternalModifications` / `ownershipReleasedEntries`；Phase 25 另输出 `externalBaselineAvailable` / `externalBaselineEntries` / `externalBaselineApplied` 与 `resolverRegistered` / `resolverApplied` 计数器。取消或失败时由 `AbortListener` 写出部分报告，保留已采集快照的统计与终止原因标记。

---

## 开发

```bash
# 完整构建
./gradlew build

# 只运行单元测试
./gradlew test
```

测试覆盖：加权采样分布与权重重置、阈值边界、幂等性、持久化往返、损坏文件容错、schema 版本保护（含 v1→v2 兼容）、归属刷新、陈旧条目清理、`custom_emc.json` 受控改写（备份、原子替换、未知字段保留）、配置热重载决策、NBT 变体规范化（含空 NBT 与默认 `{Damage:0}`）、隔离记录存储与审批、备选成本持久化与 `explain` 输出、Goety 读取失败语义、Phase 22 的诊断报告计数器与失败明细、tick 耗时最大/平均/P95（最近秩法）、峰值堆采样、`extraCounters` 持久化、取消/失败部分报告、Phase 23 的五通道指纹与变化通道、启动跳过策略、协作式取消检查点、有界 LRU 求解缓存命中/未命中与配方选择策略（含非法值回退），以及扫描接线、Phase 24 的降价豁免解析与判定、创造物品自动识别与优先级、外部修改检测与所有权释放、shaped/shapeless 统计、非 3x3 网格、容器返还扫描与成本抵扣、多输出拆分计价，以及配置校验/报告计数器、Phase 25 的解析前配置迁移（默认版本、备份、原子替换失败路径、高版本/超大版本/非法版本拒绝、幂等、未知键保留）、外部基线 JSON 解析与不可用退化、基础候选优先级/排除/最小 EMC/降价隔离/溢出阈值、debug 日志开关与每秒限流、resolver 注册顺序/异常隔离/非正值忽略/指纹变化，以及诊断报告外部基线计数。Phase 26 新增覆盖：审批默认立即应用、`safety.approveAppliesImmediately` 审计模式、历史 `approved=true`/审计记录在开关恢复后可应用、`applied` 持久化与旧 schema 读取、溢出阈值/不得抬高价格 fail-closed、生成条目写盘失败回滚且不标记批准、APPROVED 诊断计数接线。当前基线共 **730 个单元测试通过**（60 suites / 0 failures / 0 errors / 0 skipped）。

---

## 许可证

本项目采用 **Creative Commons Attribution-NonCommercial-ShareAlike 4.0 International（CC BY-NC-SA 4.0）** 许可。

- 允许：共享、改编，需署名、非商业用途、以相同许可证分发。
- 完整条款见 [LICENSE](LICENSE)。
- 版权所有者：**ItzPlana**

---

## English Summary

SudoersEMC is an independent Minecraft Forge 1.20.1 mod that generates EMC values for items that ProjectE does not price. Current baseline: Phase 26 (branch `phase-26-approve-apply`); see the build output for unit-test statistics. Phases 18–26 are complete, while live Goety and NBT-variant verification, real modded creative-item coverage, a live cancellation trigger, and real ProjectE-Integration baseline data are still pending.

- **Queries** go through ProjectE's public `IEMCProxy`.
- **Writes** use ProjectE's official `@EMCMapper` extension point (`priority = -1000`, `setValueBefore`), never ProjectE internals.
- **Manual values** in `custom_emc.json` are read-only by default and are always skipped during injection. Only when both `overflow.overwriteScope = entire_custom_emc` and `overflow.allowProjectEFileRewrite = true` are set does the mod rewrite entries strictly above `threshold`, after writing a timestamped backup, using atomic replacement and preserving unknown fields.
- **Ownership** is tracked per entry; owned values are re-solved on later scans so recipe or tag changes are picked up, while values not owned by this mod stay untouched. Phase 24 adds external-modification detection: owned entries whose backend value drifted or that are pinned in ProjectE's manual file release ownership and keep the external value, blocking the current round's overwrite.
- **Incremental scanning**: Phase 23 computes a five-channel input fingerprint (item registry id + rarity, full recipe content, item-tag union, pricing-config whitelist, and ProjectE manual values/tags), stores it in `scan_state.json`, and compares it at startup. With an existing cache and `alwaysRescan=false`, an unchanged fingerprint skips the scan; fingerprint calculation failures fall back to scanning. `/sudoemc rescan-all` always requests a scan. Dev-server runs covered a cold scan, an unchanged-input skip, and a config-change rescan.
- **Scanning** advances phase by phase across server ticks and samples tick duration and heap usage at every tick boundary, but collection, solving and application still run as single server-thread calls. There is no worker thread and no true work slicing. Phase 23 adds cooperative cancellation checkpoints inside item/recipe scanning, NBT variant generation, existing-value reads, solving and apply loops; a cancelled scan does not update the fingerprint or retire stale entries, and writes a partial report. Basic scan metrics were measured on a dev server, but performance on a representative modpack has not been measured. Live cancellation was not triggered because dev-server scans finished in under one second; that path is covered by unit tests.
- **Pricing**: multi-pass recipe solving with cycle detection and lowest-cost selection per item, a configurable tie-break strategy (`lowest_cost` default, `prefer_simple`, `stable_id`), and a bounded LRU evaluation cache (default 8192 entries keyed by recipe content, evaluation policy, evaluator registry and input-value snapshot) reused across solve rounds, plus a TAG → TIER → RARITY → FALLBACK chain using real item tags and rarity. `fallback.minEmc` is a real TOML key wired into the solver's minimum value. Phase 24 adds container-remainder crediting (default true) and optional multi-output unit splitting (`priceSecondaryOutputs=false` by default, which keeps the whole net cost on the primary output). Phase 25 adds an `EXTERNAL_BASELINE` source fed by ProjectE-Integration `pe_custom_conversions` (`values.before`) or third-party NBT resolvers; it runs after the exclusion check and before TAG/RARITY, and values above the overflow threshold are discarded before seeding.
- **NBT variants**: Phase 17 wires recipe-output NBT into `VariantScanner` and `ItemSnapshot.variantHash`, and the `[nbt]` keys are effective. Phases 19/20 normalize empty NBT and the default `{Damage:0}` tag to a plain item. However, vanilla recipes produce no NBT output, so this path has not been verified in a real environment; `/sudoemc explain` still only accepts a plain item id.
- **Overflow protection** resets generated candidate values above `999999` into a weighted range of `100..999000` with a hard per-bucket weight cap; ProjectE-native and manual values are not touched by this path.
- **Diagnostics**: `last_report.txt` after each scan, `overflow_resets.json` persisted on server stop, `quarantined_drops.json` for large price drops, and `/sudoemc status|rescan-all|cancel|reload|report|explain|approve`. `explain` shows up to five alternative recipe costs; since Phase 26, `approve` writes the approved drop to `generated_entries.json` (source `APPROVED`) and requests a ProjectE remap, or only marks the record when `safety.approveAppliesImmediately=false`. Phase 22 adds recipe-scan totals with up to 10 failed-recipe details, tick max/avg/P95 timing (nearest-rank), peak heap sampling, all counters in `PipelineResult.extraCounters`, and partial reports on cancellation/failure via `ScanManager.AbortListener`. Phase 23 adds solver rounds and solver-cache hit/miss counters to the report, and marks cooperative-cancellation partial reports with `scanCancelled` / `partialReport`. Phase 24 adds `shapedRecipes` / `shapelessRecipes`, `multiOutputRecipes` / `containerRemainderRecipes` and `ownershipExternalModifications` / `ownershipReleasedEntries` counters. Phase 25 adds `externalBaselineAvailable` / `externalBaselineEntries` / `externalBaselineApplied` and `resolverRegistered` / `resolverApplied` counters.
- **Not implemented yet**: true batched/parallel scanning and worker threads, live Goety verification, real ProjectE-Integration baseline data verification (no PEI environment yet), and performance measurement on a representative modpack. Collection/solving/application remain single-threaded single-tick heavy work; a live cancellation trigger has not been exercised. NBT-variant verification is also still pending. Phase 24 creative-only auto-detection is conservative and defaults to vanilla command/structure items, so real modded creative-item coverage is still pending. Phase 25 config migration, external baselines, rate-limited debug logging and resolver wiring do not change the execution model. Forge still rewrites the TOML after loading, so unknown keys and hand-written comments may be dropped even though the pre-parse migration writes a timestamped backup.
- **Phases 18–26 completed**: four-round solve → fallback convergence, configurable creative-item protection, empty-NBT/default-damage normalization, exclusion rules on the recipe path, stale owned-entry cleanup, quarantine persistence and `/sudoemc approve`, alternative costs in `generated_entries.json` schema v3 and `explain`, plus Phase 22 diagnostics (recipe-scan stats/failed details, tick max/avg/P95, peak heap sampling, extra counters and partial abort reports), Phase 23 incremental fingerprinting, cooperative cancellation, a bounded LRU solver cache and a configurable recipe selection strategy, Phase 24 large-drop exemptions, conservative creative-only auto-detection, external-modification detection, shaped/shapeless/non-3x3 recipe scanning, container-remainder crediting and optional multi-output splitting, Phase 25 pre-parse config migration, ProjectE-Integration `pe_custom_conversions` baselines through the `EXTERNAL_BASELINE` candidate path, rate-limited debug logging and third-party `ResolverRegistry` wiring, and Phase 26 immediate quarantine application through `/sudoemc approve` (source `APPROVED`), fail-closed overflow/manual/exclusion checks, atomic entry-before-approval persistence with rollback, and persisted `approved`/`applied` semantics.
- **Configuration**: Phase 16 wired hot reload and cross-field overflow validation. Invalid cross-field combinations reject the rebuild and keep the previous valid components; scope conflicts only log warnings. Phase 23 adds `[recipe] selectionStrategy` with `lowest_cost` (default), `prefer_simple` and `stable_id`; unknown values fall back to `lowest_cost` with a single warning. Phase 24 adds `[recipe] scanContainerRemainders` (default `true`), `creditContainerRemainders` (default `true`) and `priceSecondaryOutputs` (default `false`), plus `[safety] dropProtectionExemptNamespaces` / `dropProtectionExemptItems` (default empty), `autoDetectCreativeOnlyItems` (default `true`), `creativeOnlyItemPatterns` (vanilla command/structure defaults), `autoDetectCreativeOnlyNamespaces` (default empty) and `preserveExternallyModifiedValues` (default `true`). Invalid list entries are skipped with one summarized warning. Phase 25 adds root `schemaVersion` (legacy default `1`, current schema `3`), `[fallback] useProjectEIntegrationBaselines` (default `true`) and `[diagnostic] debugLogging` (default `false`); the schema is migrated before Forge parses the file and the original file is backed up. Phase 26 adds `[safety] approveAppliesImmediately` (default `true`) and bumps `generated_entries.json` to schema 3 (source `APPROVED`) and `quarantined_drops.json` to schema 2 (`applied` flag), refusing to load/overwrite newer schemas. `/sudoemc reload` rebuilds from the currently loaded values and does not re-read the TOML itself.
- **Config migration and external baselines (Phase 25)**: `schemaVersion` is migrated on disk before Forge rewrites the TOML, with a timestamped `.bak` backup and atomic replacement; newer schemas are refused. When `fallback.useProjectEIntegrationBaselines=true` and ProjectE-Integration plus a working ProjectE provider are present, positive entries from `pe_custom_conversions` `values.before` become `EXTERNAL_BASELINE` candidates, still subject to exclusions, manual values, `fallback.minEmc`, large-drop protection and overflow protection. Third-party mods can register NBT material resolvers via `Sudoersemc.resolverRegistry()`; their class names participate in the baseline fingerprint.
- Goety ritual recipes (`goety:ritual`, `craft` type only) are parsed via reflection, but the reflection path has not been verified against a live Goety install. Without ProjectE the mod still scans and persists generated values but does not inject them.

Licensed under CC BY-NC-SA 4.0. Copyright (c) 2026 ItzPlana.

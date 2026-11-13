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

SudoersEMC 是一个**独立实现**的 Minecraft Forge 1.20.1 模组。它扫描服务器上已注册的物品与配方，为那些 **ProjectE 尚未赋予 EMC 值**的物品计算并写入 EMC。

模组不会修改 ProjectE 的源码，也不依赖任何非公开接口。所有写入都通过 ProjectE 官方的映射扩展点完成。

### 核心原则

| 原则             | 说明                                                                                                                                                      |
| ---------------- | --------------------------------------------------------------------------------------------------------------------------------------------------------- |
| **不覆盖手动值** | `config/ProjectE/custom_emc.json` 中由管理员配置的条目永远不会被改写                                                                                      |
| **归属可追踪**   | 每个生成值都记录来源（配方 / 标签 / 稀有度 / 层级 / 兜底）、配方 ID 与时间戳                                                                              |
| **计算可解释**   | 已生成的普通条目可用 `/sudoemc explain` 查看来源、配方 ID 与归属；NBT 变体与 ProjectE 原生值尚不支持                                                      |
| **安全优先**     | 大幅降价会被隔离；超出上限的数值会被重置到受控区间                                                                                                        |
| **主线程边界**   | 扫描由服务器 tick 驱动，状态机按批推进；求解与文件写入在后台守护线程执行。`tickBudgetNanos` 只影响进度步长与自适应批量，**不约束**单批采集/应用的实际耗时 |
| **可选依赖**     | 未安装 ProjectE 时模组正常加载，只是不生成任何值                                                                                                          |

---

## 当前状态

项目按阶段线性推进，当前分支（`phase-11-release`）包含 Phase 0–10 的组件与 Phase 11 的入口接线：

| 阶段       | 内容                                                             | 状态                          |
| ---------- | ---------------------------------------------------------------- | ----------------------------- |
| Phase 0    | Forge 1.20.1 工程骨架、ProjectE 1.0.1 集成调研                   | ✅ 完成                       |
| Phase 1    | ProjectE 查询/写入、归属持久化、溢出保护数学、配置、单元测试     | ✅ 完成                       |
| Phase 2–10 | 扫描、配方求解、兜底定价、NBT 变体、批处理、安全保护、诊断、命令 | ✅ 组件实现，单元测试通过     |
| Phase 11   | 模组入口接线与发布验收                                           | 🚧 入口接线完成，实机验收待补 |

**已知限制（Phase 11）**：真实扫描管线已在服务器启动时接线（`ScanCollectionPipeline` + 分片 `ScanManager`），但尚未在大型整合包与专用服务器完成端到端实机验证；`KnownItemsStore` / `QuarantinedDropsStore` 组件尚未接入扫描管线；`last_report.txt` 尚未由扫描流程自动写入；`entire_custom_emc` 文件改写未实现；ProjectE Integration 的映射优先级尚未在运行期验证。命令、调度、持久化、安全与定价组件已通过单元/组件测试。

Phase 1 已在专用服务器上验证：写入生效、重启后自动恢复、手动值不被覆盖、缺少 ProjectE 时优雅降级。

---

## 环境要求

| 项目      | 版本                                |
| --------- | ----------------------------------- |
| Minecraft | 1.20.1                              |
| Forge     | 47.2.0 及以上（47.x）               |
| Java      | 17                                  |
| ProjectE  | 1.0.1（可选，缺失时模组不生成 EMC） |
| Gradle    | 8.1.1（随 Wrapper 提供）            |

### 可选联动

- **ProjectE Integration** — 仅为 ProjectE 尚未定价的物品生成值。本模组与 ProjectE Integration 的映射优先级尚未在运行期验证，发布说明不承诺“始终让位于已存在的值”。
- **Goety** — 若安装了 Goety 2.5.60.6，其仪式配方（`goety:ritual`）会被单独识别。只有 `goety:craft` 类型参与物品定价，召唤、转化、附魔、传送与定位仪式会被跳过。

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
| `-PwithoutProjectE` | 不加载 ProjectE，用于验证优雅降级                |

> 这些参数只影响开发环境，不会进入发布 jar 的行为。

---

## 安装

1. 安装 Minecraft Forge 1.20.1（47.x）。
2. 将 `sudoersemc-<version>.jar` 放入 `mods/` 目录。
3. 如需 EMC 生成，同时安装 ProjectE 1.0.1。
4. 启动服务器或客户端。首次启动会生成配置文件。

---

## 配置

配置文件：`config/sudoersemc-common.toml`

```toml
# Root-level schema version (managed by ConfigFileMigration; do not edit manually).
schemaVersion = 2

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

[fallback]
useFallbackForUnsolvable = true
useTagPricing = true
useRarityPricing = true
useProjectEIntegrationBaselines = true

[safety]
largeDropProtection = true
maxAllowedDropRatio = 0.5
protectCreativeOnlyItems = true

[overflow]
enabled = true
threshold = 999999
resetMin = 100
resetMax = 999000
bucketCount = 100
weightCap = 20
overwriteScope = "owned_only"   # owned_only | entire_custom_emc
allowProjectEFileRewrite = false
randomSeed = 0                  # 0 = 非确定性

[nbt]
scanNbtVariants = true
maxNbtVariantsPerItem = 100

[diagnostic]
debugLogging = false
generateReports = true
```

### 溢出保护

当某个 EMC 值**严格大于** `threshold` 时，它会被替换为 `[resetMin, resetMax]` 区间内的加权随机值：

- 区间被切成 `bucketCount` 个桶，桶权重按二次曲线向低值倾斜；
- 每个桶的权重被硬性限制在 `[1, weightCap]`，因此最高值始终可达，但不会被某个桶以接近 100% 的概率垄断；
- `randomSeed` 非 0 时结果可复现，便于测试；
- 已处于重置区间内的值不会被再次随机化（幂等）。

### 改写范围

| 取值                 | 行为                                                                                                                                          |
| -------------------- | --------------------------------------------------------------------------------------------------------------------------------------------- |
| `owned_only`（默认） | 只重置由 SudoersEMC 自己生成的条目                                                                                                            |
| `entire_custom_emc`  | **当前未实现**：配置值会被接受并通过校验，但不会改写 ProjectE 的 `custom_emc.json`，运行时行为等同 `owned_only`（见 `CHANGELOG.md` 已知限制） |

---

## 配置校验与迁移

### 默认值

| 配置项                              | 默认值       | 说明                                              |
| ----------------------------------- | ------------ | ------------------------------------------------- |
| `performance.itemsPerTick`          | `100`        | 服务端线程每 tick 最多处理的物品数                |
| `recipe.maxRecipePasses`            | `20`         | 单次扫描的配方求解遍数上限                        |
| `overflow.threshold`                | `999999`     | 严格大于该值的 EMC 会被重置                       |
| `overflow.resetMin`                 | `100`        | 重置区间下界（含）                                |
| `overflow.resetMax`                 | `999000`     | 重置区间上界（含）                                |
| `overflow.bucketCount`              | `100`        | 加权采样桶数量                                    |
| `overflow.weightCap`                | `20`         | 单个桶的权重上限                                  |
| `overflow.overwriteScope`           | `owned_only` | 重置范围，可选 `owned_only` / `entire_custom_emc` |
| `overflow.allowProjectEFileRewrite` | `false`      | 是否允许改写 ProjectE 的 `custom_emc.json`        |
| `nbt.maxNbtVariantsPerItem`         | `100`        | 每个物品最多扫描的 NBT 变体数                     |
| `safety.maxAllowedDropRatio`        | `0.5`        | 单次生成值允许的最大相对跌幅                      |

> **`minEmc` 说明**：当前构建没有 `minEmc` 配置键。求解器下限固定为
> `SolveSettings.DEFAULT_MINIMUM_EMC = 1`（与 `EvaluationPolicy.DEFAULT_MINIMUM_EMC` 相同）。
> 配置化该值属于后续阶段；此前的 README 把它列为配置项是错误的，已移除。

> **接线状态（Phase 11）**：`[general]`、`[performance]` 以及扫描管线使用的 `[recipe]`、`[fallback]`、
> `[safety]`、`[nbt]`、`[overflow]` 配置均已接入运行时；`[diagnostic].*` 会参与扫描指纹与配置快照，
> 但报告自动落盘仍待后续阶段接线。`[overflow].allowProjectEFileRewrite` 与 `entire_custom_emc`
> 文件改写仍未实现，运行时行为等同 `owned_only`。

### 校验矩阵

加载或重载配置后会执行交叉字段校验。以下取值会被判定为非法，并阻止新配置生效：

| 配置项                       | 拒绝条件                                                         |
| ---------------------------- | ---------------------------------------------------------------- |
| `performance.itemsPerTick`   | `< 1`                                                            |
| `recipe.maxRecipePasses`     | `< 1`                                                            |
| `overflow.overwriteScope`    | 不是 `owned_only` 或 `entire_custom_emc`（忽略大小写与首尾空白） |
| `overflow.resetMin`          | `< 1`                                                            |
| `overflow.resetMin`          | `> resetMax`                                                     |
| `overflow.threshold`         | `<= resetMax`                                                    |
| `overflow.bucketCount`       | `< 1`                                                            |
| `overflow.weightCap`         | `< 1`                                                            |
| `nbt.maxNbtVariantsPerItem`  | `< 1` 或 `> 1000`                                                |
| `safety.maxAllowedDropRatio` | 不在 `(0.0, 1.0]` 内，包括 `0.0`、负数与 `NaN`                   |

所有 `overflow.*` 区间不变量始终校验；`overflow.enabled = false` 只表示运行时跳过重置，
不会放宽配置合法性。`OverflowProtection.Settings` 即使关闭也会构造采样器并强制这些不变量，
因此校验器必须在关闭时继续拒绝非法区间，否则非法配置会在组件重建时抛异常。

接线方式：`SudoersemcConfig.onConfigLoading` / `onConfigReloading` 会从当前 Forge 值构造
`ConfigValidator.Values`，调用 `ConfigValidator.validate(...)`，再交给
`ConfigWatcher.fireReload(...)`。校验通过时重建并缓存不可变快照（`overflowSettings()` 返回该
快照）；失败时 `fireReload` 不通知监听器，上一份有效快照继续生效，首次加载即非法时使用内置
安全默认值。事件可能由配置加载线程触发，处理器只读取配置并交换 volatile 不可变快照，不访问
服务器或世界状态。

### 警告条件

以下情况不会阻止配置生效，但会记录一条警告：

| 条件                                                                       | 提示                                                          |
| -------------------------------------------------------------------------- | ------------------------------------------------------------- |
| `overwriteScope = entire_custom_emc` 且 `allowProjectEFileRewrite = false` | ProjectE 文件改写将被跳过（当前构建即使为 `true` 也不会改写） |
| `itemsPerTick > 5000`                                                      | 单 tick 处理量过大可能导致卡顿                                |
| `maxRecipePasses > 100`                                                    | 扫描耗时可能显著增加                                          |
| `overflow.enabled = false`                                                 | 超大 EMC 值不会被重置                                         |

同一条警告在一次运行中只记录一次：`ConfigWatcher.fireReload` 对成功结果统一调用
`logConflictWarnings`，重复重载不会刷屏。

### 迁移

配置根级键 `schemaVersion` 表示 schema 版本，当前为 `2`，旧版本为 `1`；缺失该键的文件按
`1` 处理。启动时 `SudoersemcConfig.register()` 在 Forge 解析 TOML 之前调用
`ConfigFileMigration`，后者使用 `ConfigMigrations` 迁移文件：

- **v1 → v2**：缺少 `overflow.threshold` 时补充 `999999`，缺少 `overflow.overwriteScope`
  时补充 `owned_only`；已有值保持不变。
- **v2 → v2**：不做任何修改，也不产生提示。
- **高于 v2**：保持原样不做修改，并记录提示，防止旧构建覆盖新版本配置。
- **未知版本（`<= 0`）**：按当前版本处理并记录提示。
- `ConfigMigrations` 的 raw map 支持扁平键（`threshold`）、点分键（`overflow.threshold`）
  和嵌套 `overflow` map；迁移沿用输入已有的键形状，不会把默认值写到错误层级。
- 迁移只改写 `schemaVersion`、`overflow.threshold`、`overflow.overwriteScope`，保留注释与
  未知键；先写同目录临时文件，尽力备份后原子替换，任何失败都保留原文件。

> **English:** Schema version 2 validates numeric ranges and cross-field invariants in pure Java.
> Invalid reloads keep the previously active snapshot, each distinct warning is logged once, and
> v1 migrations only fill in missing `overflow.threshold` (`999999`) and `overflow.overwriteScope`
> (`"owned_only"`). Configs declaring a newer schema are left untouched. There is no `minEmc`
> config key in this phase; the solver lower bound is hard-coded to `1`.

---

## 数据文件

| 路径                                       | 说明                                                                       |
| ------------------------------------------ | -------------------------------------------------------------------------- |
| `config/sudoersemc/generated_entries.json` | 生成值、来源、配方、归属与时间戳                                           |
| `config/sudoersemc/known_items.json`       | 已扫描物品目录；组件已实现，当前扫描管线未接线，通常不会生成               |
| `config/sudoersemc/quarantined_drops.json` | 危险降价隔离记录；组件已实现，当前扫描管线未接线，通常不会生成             |
| `config/sudoersemc/overflow_resets.json`   | 溢出重置审计记录；扫描管线触发溢出保护时追加写入                           |
| `config/ProjectE/custom_emc.json`          | ProjectE 手动配置；本模组只读，`entire_custom_emc` 文件改写尚未实现        |
| `config/sudoersemc/scan_state.json`        | 上次扫描指纹/耗时/计数；扫描管线启动时加载、成功后原子写入，停服时也会保存 |
| `config/sudoersemc/last_report.txt`        | 最近一次诊断报告；`ReportManager` 支持原子写入，但扫描流程尚未自动调用     |

所有写入采用「临时文件 + 原子替换」，加载失败只告警不崩溃，遇到更高版本 schema 时拒绝覆盖。

---

## 工作原理

> 下列链路已在 Phase 11 接线：服务器启动时构建扫描管线，分片采集由服务器 tick 驱动，求解与文件写入在后台守护线程执行；`tickBudgetNanos` 只影响进度步长与自适应批量，不会硬中断单批工作。

1. **查询** — 通过 `IEMCProxy` 判断物品是否已有 EMC。
2. **计算** — 从配方、标签、稀有度、层级规则推导候选值；同一物品有多个配方时取最低的有效确定性成本。
3. **安全** — 应用降价保护、创造专属物品保护与溢出保护。
4. **持久化** — 把结果写入 `generated_entries.json`，并标记归属。
5. **写入** — 通过 ProjectE 官方的 `@EMCMapper` 扩展点，在 ProjectE 重算 EMC 时以 `setValueBefore` 注入自有条目。
6. **重算** — 在服务器线程触发一次资源重载，ProjectE 随即重建 EMC 映射。

---

## 开发

```bash
# 完整构建
./gradlew build

# 只运行单元测试
./gradlew test
```

测试覆盖：加权采样分布与权重重置、阈值边界、幂等性、持久化往返、损坏文件容错、schema 版本保护、分片扫描管线、命令层、扫描状态机、General 开关降级与停服清理。

当前共 **554** 个单元/组件测试，0 failure / 0 error；尚未加入专用服务器端到端测试。

---

## 许可证

本项目采用 **Creative Commons Attribution-NonCommercial-ShareAlike 4.0 International（CC BY-NC-SA 4.0）** 许可。

- 允许：共享、改编，需署名、非商业用途、以相同许可证分发。
- 完整条款见 [LICENSE](LICENSE)。
- 版权所有者：**ItzPlana**

---

## English Summary

SudoersEMC is an independent Minecraft Forge 1.20.1 mod that generates EMC values for items that ProjectE does not price.

- **Queries** go through ProjectE's public `IEMCProxy`.
- **Writes** use ProjectE's official `@EMCMapper` extension point, never ProjectE internals.
- **Manual values** in `custom_emc.json` are never overwritten.
- **Ownership** is tracked per entry, so only values created by this mod can later be updated or removed.
- **Overflow protection** resets values above `999999` into a weighted range of `100..999000`, with a hard per-bucket weight cap so the top of the range stays reachable without dominating. Note: in the current Phase 11 build the protection components are not instantiated by the runtime yet.
- **Optional integration**: values are only intended for items ProjectE does not already price; the mapping priority against ProjectE Integration has not been runtime-verified, so "ProjectE Integration values always win" is not guaranteed. Goety ritual recipes (`goety:ritual`, `craft` type only) are evaluated when Goety is installed.
- Runs on dedicated servers. Loads normally without ProjectE.
- **Current status (Phase 11)**: the scan pipeline is not wired yet. The jar starts, registers commands and persists state, but generates no EMC values; `/sudoemc rescan-all` only runs an empty state machine.

Build with `./gradlew build`. Output: `build/libs/sudoersemc-<version>.jar`.

Licensed under CC BY-NC-SA 4.0. Copyright (c) 2026 ItzPlana.

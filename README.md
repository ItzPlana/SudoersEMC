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

| 原则             | 说明                                                                         |
| ---------------- | ---------------------------------------------------------------------------- |
| **不覆盖手动值** | `config/ProjectE/custom_emc.json` 中由管理员配置的条目永远不会被改写         |
| **归属可追踪**   | 每个生成值都记录来源（配方 / 标签 / 稀有度 / 层级 / 兜底）、配方 ID 与时间戳 |
| **计算可解释**   | 任意一个 EMC 值都能回答「它从哪里来、为什么是这个数」                        |
| **安全优先**     | 大幅降价会被隔离；超出上限的数值会被重置到受控区间                           |
| **不阻塞主线程** | 扫描分批次执行，重计算在快照之上离线完成                                     |
| **可选依赖**     | 未安装 ProjectE 时模组正常加载，只是不生成任何值                             |

---

## 当前状态

项目按阶段推进，已完成前两个阶段：

| 阶段     | 内容                                                         | 状态      |
| -------- | ------------------------------------------------------------ | --------- |
| Phase 0  | Forge 1.20.1 工程骨架、ProjectE 1.0.1 集成调研               | ✅ 完成   |
| Phase 1  | ProjectE 查询/写入、归属持久化、溢出保护数学、配置、单元测试 | ✅ 完成   |
| Phase 2  | 物品与配方扫描（含仪式配方）                                 | 🚧 进行中 |
| Phase 3+ | 递归求解、兜底定价、NBT 变体、批处理、命令、诊断             | ⏳ 计划中 |

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

- **ProjectE Integration** — 与本模组互不干扰。两者都向 ProjectE 提供 EMC 映射，本模组始终让位于已存在的值。
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

| 取值                 | 行为                                                                                                          |
| -------------------- | ------------------------------------------------------------------------------------------------------------- |
| `owned_only`（默认） | 只重置由 SudoersEMC 自己生成的条目                                                                            |
| `entire_custom_emc`  | 额外重置 ProjectE `custom_emc.json` 中所有超限条目，需同时开启 `allowProjectEFileRewrite`，并会先做时间戳备份 |

---

## 配置校验与迁移

### 默认值

| 配置项                              | 默认值       | 说明                                              |
| ----------------------------------- | ------------ | ------------------------------------------------- |
| `performance.itemsPerTick`          | `100`        | 服务端线程每 tick 最多处理的物品数                |
| `recipe.maxRecipePasses`            | `20`         | 单次扫描的配方求解遍数上限                        |
| `minEmc`                            | `1`          | 生成 EMC 值的下限                                 |
| `overflow.threshold`                | `999999`     | 严格大于该值的 EMC 会被重置                       |
| `overflow.resetMin`                 | `100`        | 重置区间下界（含）                                |
| `overflow.resetMax`                 | `999000`     | 重置区间上界（含）                                |
| `overflow.bucketCount`              | `100`        | 加权采样桶数量                                    |
| `overflow.weightCap`                | `20`         | 单个桶的权重上限                                  |
| `overflow.overwriteScope`           | `owned_only` | 重置范围，可选 `owned_only` / `entire_custom_emc` |
| `overflow.allowProjectEFileRewrite` | `false`      | 是否允许改写 ProjectE 的 `custom_emc.json`        |
| `nbt.maxNbtVariantsPerItem`         | `100`        | 每个物品最多扫描的 NBT 变体数                     |
| `safety.maxAllowedDropRatio`        | `0.5`        | 单次生成值允许的最大相对跌幅                      |

### 校验矩阵

加载或重载配置后会执行交叉字段校验。以下取值会被判定为非法，并阻止新配置生效：

| 配置项                       | 拒绝条件                                                         |
| ---------------------------- | ---------------------------------------------------------------- |
| `performance.itemsPerTick`   | `< 1`                                                            |
| `recipe.maxRecipePasses`     | `< 1`                                                            |
| `minEmc`                     | `< 0`                                                            |
| `overflow.overwriteScope`    | 不是 `owned_only` 或 `entire_custom_emc`（忽略大小写与首尾空白） |
| `overflow.resetMin`          | 溢出保护开启且 `< 1`                                             |
| `overflow.resetMin`          | 溢出保护开启且 `> resetMax`                                      |
| `overflow.threshold`         | 溢出保护开启且 `<= resetMax`                                     |
| `overflow.bucketCount`       | 溢出保护开启且 `< 1`                                             |
| `overflow.weightCap`         | 溢出保护开启且 `< 1`                                             |
| `nbt.maxNbtVariantsPerItem`  | `< 1` 或 `> 1000`                                                |
| `safety.maxAllowedDropRatio` | 不在 `(0.0, 1.0]` 内，包括 `0.0`、负数与 `NaN`                   |

溢出保护关闭时，所有 `overflow.*` 范围校验都会被跳过；`maxNbtVariantsPerItem` 与 `maxAllowedDropRatio` 仍然始终校验。

校验失败时，模组保留上一份有效的组件实例，不会用非法配置重建，并记录一条汇总警告。

### 警告条件

以下情况不会阻止配置生效，但会记录一条警告：

| 条件                                                                       | 提示                           |
| -------------------------------------------------------------------------- | ------------------------------ |
| `overwriteScope = entire_custom_emc` 且 `allowProjectEFileRewrite = false` | ProjectE 文件改写将被跳过      |
| `itemsPerTick > 5000`                                                      | 单 tick 处理量过大可能导致卡顿 |
| `maxRecipePasses > 100`                                                    | 扫描耗时可能显著增加           |
| `overflow.enabled = false`                                                 | 超大 EMC 值不会被重置          |

同一条警告在一次运行中只记录一次，避免重载时刷屏。

### 迁移

配置 schema 版本为 `2`，旧版本为 `1`：

- **v1 → v2**：缺少 `overflow.threshold` 时补充 `999999`，缺少 `overwriteScope` 时补充 `owned_only`；已有值保持不变。
- **v2 → v2**：不做任何修改，也不产生提示。
- **高于 v2**：保持原样不做修改，并记录提示，防止旧构建覆盖新版本配置。
- **未知版本（`<= 0`）**：按当前版本处理并记录提示。
- 迁移始终返回新的映射，不会修改调用方传入的原始数据；持久化由调用方以「临时文件 + 原子替换」方式完成。

> **English:** Schema version 2 validates numeric ranges and cross-field invariants in pure Java. Invalid reloads keep the previously active components, each distinct warning is logged once, and v1 migrations only fill in missing `overflow.threshold` (`999999`) and `overwriteScope` (`"owned_only"`). Configs declaring a newer schema are left untouched.

---

## 数据文件

| 路径                                       | 说明                                                          |
| ------------------------------------------ | ------------------------------------------------------------- |
| `config/sudoersemc/generated_entries.json` | 生成值、来源、配方、归属与时间戳                              |
| `config/sudoersemc/overflow_resets.json`   | 溢出重置审计记录                                              |
| `config/ProjectE/custom_emc.json`          | ProjectE 手动配置（本模组只读，`entire_custom_emc` 模式除外） |

所有写入采用「临时文件 + 原子替换」，加载失败只告警不崩溃，遇到更高版本 schema 时拒绝覆盖。

---

## 工作原理

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

测试覆盖：加权采样分布与权重重置、阈值边界、幂等性、持久化往返、损坏文件容错、schema 版本保护。

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
- **Overflow protection** resets values above `999999` into a weighted range of `100..999000`, with a hard per-bucket weight cap so the top of the range stays reachable without dominating.
- **Optional integration**: ProjectE Integration values always win, and Goety ritual recipes (`goety:ritual`, `craft` type only) are evaluated when Goety is installed.
- Runs on dedicated servers. Loads normally without ProjectE.

Build with `./gradlew build`. Output: `build/libs/sudoersemc-<version>.jar`.

Licensed under CC BY-NC-SA 4.0. Copyright (c) 2026 ItzPlana.

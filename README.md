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

| 原则                 | 说明                                                                                                                                                                                                                                                                  |
| -------------------- | --------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| **默认不覆盖手动值** | `config/ProjectE/custom_emc.json` 默认只读，注入阶段也会跳过手动值。仅当 `overflow.overwriteScope = entire_custom_emc` 与 `overflow.allowProjectEFileRewrite = true` 双开关同时开启时，才会对该文件中严格超过阈值的条目做受控改写（先备份、原子替换、保留未知字段）。 |
| **归属可追踪**       | 每个生成值都记录来源（配方 / 标签 / 稀有度 / 层级 / 兜底）、配方 ID、归属、NBT 变体哈希（如有）与时间戳。                                                                                                                                                             |
| **计算可解释**       | SudoersEMC 已生成的无 NBT 变体普通条目可用 `/sudoemc explain <item>` 查看来源、配方与归属；备选成本、ProjectE 原生值、手动值与 NBT 变体暂不支持。                                                                                                                     |
| **安全优先**         | 生成候选值的大幅降价会被隔离（仅内存统计，未落盘）；超出上限的生成候选值会被重置到受控区间；创造专属物品保护默认开启，Phase 18 正把排除项、固定值与命名空间配置化。                                                                                                   |
| **分阶段推进**       | 扫描按阶段跨 tick 推进并更新进度计数；采集、求解与应用仍在服务器线程整体执行，尚未实现工作线程或真正分片；取消只在 tick 边界被观察；性能未测量。                                                                                                                      |
| **可选依赖**         | 未安装 ProjectE 时模组正常加载，仍会扫描并持久化生成值，但不会向 ProjectE 注入 EMC。                                                                                                                                                                                  |

---

## 当前状态

项目按阶段推进。当前代码基线为 Phase 17 提交 `fe956b5`（分支 `phase-18-chain-creative`），**449 个单元测试通过**；Phase 18 仍在进行中。

| 阶段       | 内容                                                                                                                                  | 状态                                  |
| ---------- | ------------------------------------------------------------------------------------------------------------------------------------- | ------------------------------------- |
| Phase 0-1  | Forge 1.20.1 工程骨架、ProjectE 1.0.1 集成调研、查询/写入、归属持久化、溢出保护数学、配置、单元测试                                   | ✅ 完成                               |
| Phase 2-13 | 物品与配方扫描（含 Goety 仪式）、扫描管线接线、递归求解、兜底定价、安全层、命令、诊断                                                 | ✅ 完成                               |
| Phase 14   | 归属生命周期（owned 值刷新）与溢出审计落盘                                                                                            | ✅ 完成                               |
| Phase 15   | `ENTIRE_CUSTOM_EMC` 受控改写（双开关、时间戳备份、原子替换、未知字段保留）                                                            | ✅ 完成（已实机验证）                 |
| Phase 16   | 配置生命周期（热重载、交叉字段校验接线、`fallback.minEmc` 配置键）                                                                    | ✅ 完成                               |
| Phase 17   | NBT 变体端到端接线（配方输出 NBT → `VariantScanner` → 带 `variantHash` 的 `ItemSnapshot`）                                            | ✅ 完成（449 个测试；真实环境未验证） |
| Phase 18   | solve → fallback 多轮收敛；创造物品保护配置化（`safety.creativeItemExclusions` / `creativeItemSpecialValues` / `creativeNamespaces`） | 🚧 进行中                             |

Phase 1 曾在专用服务器上验证写入生效、重启后自动恢复、手动值不被覆盖与缺少 ProjectE 时的降级行为；该记录属于历史验证，不代表当前全链路已在所有模组组合下复测。Phase 15 的双开关改写已在独立环境实机验证：默认配置与单独开启任一开关都不会改动 `custom_emc.json`；双开关同时开启时，严格超过阈值的条目被重置，并生成时间戳备份与 `overflow_resets.json` 审计记录。Phase 17 的 NBT 变体路径只有单元测试；由于原版配方不产生 NBT 输出，真实环境尚未验证。

### Phase 18 进行中

Phase 18 的目标是收敛定价链并让创造物品保护可配置。以下两项尚未完成，README 其余部分描述的是 Phase 17 基线的实际行为：

- **solve → fallback 多轮收敛** —— 当前基线仍是「一次配方求解 → 对未覆盖物品追加兜底候选」的单轮流程；Phase 18 正在把该链路改为多轮收敛。完成前不要按多轮行为调参。
- **创造物品保护配置化** —— `safety.creativeItemExclusions`、`safety.creativeItemSpecialValues`、`safety.creativeNamespaces` 是 Phase 18 新增键；当前基线的 `CreativeItemProtection` 仍使用硬编码的空排除集、空特殊值与空命名空间，完成前这三个键不改变行为。

### 尚未实现 / 当前不生效

以下能力**尚未实现**或**当前不生效**，请勿按已完成能力使用：

- **真正分片的分批扫描与工作线程** —— 尚未实现。`performance.itemsPerTick` 与 `performance.adaptiveBatchSizing` 只影响进度推进的批量粒度；采集、求解与应用仍在服务器线程整体执行一次，没有线程池或后台线程，性能未测量。
- **增量扫描指纹复用** —— 尚未实现。`scan_state.json` 只记录指纹与统计，`ScanStateStore.matches(...)` 没有调用方，不参与跳过判断；自动扫描只按「上次扫描时间 + 已生成条目数」决定是否整次跳过。
- **可中断的取消** —— 尚未实现。`/sudoemc cancel` 只设置取消标志，下一次服务器 tick 才会观察到；正在执行的采集、求解或应用是单次调用，无法被中断。
- **RecipeSolver 的未变化配方缓存与选择策略** —— 尚未实现。每次扫描都会重新求解配方，没有「未变化配方复用」缓存，也没有可配置的配方选择策略。
- **配置迁移接入 Forge TOML** —— 尚未实现。`ConfigMigrations` 只有单元测试，配置文件没有 `schemaVersion` 字段，启动与重载都不会执行迁移。
- **隔离降价落盘与管理员审批** —— 尚未实现。`quarantined_drops.json` 不会生成，也没有审批命令；`QuarantinedDropsStore` 只有持久化工具与测试，生产流程没有调用。隔离数量只出现在 `last_report.txt` 的统计中。
- **`explain` 的备选成本** —— 尚未实现。解释文本中的备选成本固定为空；命令只覆盖 `generated_entries.json` 中无 NBT 变体的普通物品条目，不支持 ProjectE 原生值、手动值与 NBT 变体。
- **Goety 真实环境验证** —— 尚未进行。Goety 仪式只有单元测试，没有真实环境记录。
- **代表性模组包性能测量** —— 尚未进行。没有在代表性模组包上测量扫描耗时、内存或单 tick 停顿。
- **`fallback.useProjectEIntegrationBaselines`** —— 当前未生效。该键没有外部基线读取路径；兜底链运行时传入的外部基线固定为 `0`。
- **`diagnostic.debugLogging`** —— 当前未生效。配置键存在，但生产代码没有读取它。
- **Phase 18 的两项工作** —— 见上一节；完成前 solve → fallback 仍是单轮，三个创造保护配置键不改变行为。
- **NBT 变体的真实环境验证** —— Phase 17 已接线并有单元测试，但原版配方不产生 NBT 输出，真实环境尚未验证；`/sudoemc explain` 也不覆盖带变体条目。

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

- **ProjectE-Integration** — 与本模组互不干扰。通过 `IEMCProxy` 可见的已有 EMC 会被视为「已定价」，本模组不会覆盖；`fallback.useProjectEIntegrationBaselines` 当前是未生效的占位配置，没有外部基线读取路径。
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

> 下列键由 Forge 生成到配置文件中。标注 **（当前未生效）** 的键不会被生产代码读取，修改后不会产生运行时效果。Phase 16 已接入热重载：`ModConfigEvent` 会重建运行时组件，`/sudoemc reload` 也会用当前已加载的配置值触发同一重建路径。扫描进行中时重建会延迟到扫描结束；交叉字段校验失败时拒绝重建并保留上一份有效组件。`ConfigMigrations` 仍未接入，TOML 没有 `schemaVersion` 字段。

### 自动扫描

`general.enabled = true` 时启用启动自动扫描；`general.alwaysRescan = true` 每次启动都强制重扫；两者都不满足时，仅在「从未扫描」或「已生成条目为空」时触发一次扫描，否则复用缓存。自动扫描与是否安装 ProjectE 无关。这两个键只在服务器启动时读取，修改后需重启服务器才会改变自动扫描决策；其余扫描相关键在 `/sudoemc reload` 或 Forge 配置重载时通过重建组件生效。

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
useProjectEIntegrationBaselines = true   # 当前未生效（没有外部基线读取路径）
minEmc = 1

[safety]
largeDropProtection = true
maxAllowedDropRatio = 0.5
protectCreativeOnlyItems = true
creativeItemExclusions = []          # Phase 18 新增（进行中；当前未生效）
creativeItemSpecialValues = []       # Phase 18 新增（进行中；当前未生效；形式以 Phase 18 实现为准）
creativeNamespaces = []              # Phase 18 新增（进行中；当前未生效）

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
debugLogging = false            # 当前未生效（生产代码未读取）
generateReports = true
```

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

| 命令                      | 作用                                     | 说明                                                               |
| ------------------------- | ---------------------------------------- | ------------------------------------------------------------------ |
| `/sudoemc status`         | 查看扫描阶段、进度、上次耗时与上次结果   | 只读内存进度与扫描状态文件                                         |
| `/sudoemc rescan-all`     | 请求一次全量重新扫描                     | 已有扫描进行中时返回失败提示                                       |
| `/sudoemc cancel`         | 请求取消当前扫描                         | 只在下一个服务器 tick 被观察到；不能中断正在执行的采集、求解或应用 |
| `/sudoemc reload`         | 按当前已加载的配置重建运行时组件         | 扫描中延后到扫描结束；校验失败时保留上一份有效配置                 |
| `/sudoemc report`         | 读取 `config/sudoersemc/last_report.txt` | 尚无报告时提示先执行一次扫描                                       |
| `/sudoemc explain <item>` | 解释某个已生成条目的来源、配方与归属     | 只支持无 NBT 变体的普通条目；不显示备选成本                        |

---

## 配置校验与迁移

### 当前实际行为

- **单字段范围**由 Forge 的 `defineInRange` 在加载时校验，越界值会被纠正到允许区间。覆盖的键包括 `performance.itemsPerTick`、`recipe.maxRecipePasses`、`fallback.minEmc`、`overflow.threshold`、`overflow.resetMin`、`overflow.resetMax`、`overflow.bucketCount`、`overflow.weightCap`、`safety.maxAllowedDropRatio` 与 `nbt.maxNbtVariantsPerItem`。
- **运行时交叉字段校验**已由 Phase 16 接线：`ConfigValidator.validateCurrent()` 在配置加载/重载事件与 `/sudoemc reload` 时执行 `validateCrossField(...)`。`overflow.threshold <= resetMax`、`overflow.resetMin > resetMax`、`overflow.resetMin < 1`、`bucketCount < 1`、`weightCap < 1` 会拒绝重建并保留上一份有效组件；`overwriteScope = entire_custom_emc` 且 `allowProjectEFileRewrite = false` 只记录警告，不改写文件。
- 非法的 `overflow.overwriteScope` 会在解析时回退为 `owned_only` 并记录警告，不会拒绝重建。
- `safety.maxAllowedDropRatio` 的 Forge 范围是 `[0.0, 1.0]`；若设为 `0.0`，`LargeDropProtection.Settings` 构造会失败，扫描管线回退到默认 `0.5`（不是拒绝整个配置）。
- `nbt.maxNbtVariantsPerItem` 的 Forge 范围是 `[1, Integer.MAX_VALUE]`；运行时不拒绝大于 `1000` 的值，但 `VariantScanner` 会用硬上限 `1000` 截断实际扫描数量。
- 启动时若溢出设置构造失败，扫描管线会回退到内置默认值；运行期重建采用「全有或全无」：新组件全部构建成功后才替换旧组件。
- `/sudoemc reload` 会用当前已加载的配置值重建运行时组件；扫描进行中时返回「将在扫描结束后生效」，校验失败时返回「已保留上一份有效配置」。它不会主动重新读取磁盘上的 TOML；直接编辑配置文件后，Forge 检测到变化时会触发 `ModConfigEvent.Reloading`，走同一重建路径。
- `ConfigValidator` 与 `ConfigWatcher` 已接入生产配置加载流程；`ConfigMigrations` 尚未接入，schema 迁移当前不生效。

### 默认值

| 配置项                                                                               | 默认值        | 说明                                                                  |
| ------------------------------------------------------------------------------------ | ------------- | --------------------------------------------------------------------- |
| `performance.itemsPerTick`                                                           | `100`         | 每 tick 推进的物品进度上限；不切分实际工作                            |
| `performance.adaptiveBatchSizing`                                                    | `true`        | 按观测耗时调整进度批量                                                |
| `recipe.maxRecipePasses`                                                             | `20`          | 单次扫描的配方求解遍数上限                                            |
| `recipe.allowRecipeValueIncrease`                                                    | `false`       | 是否允许配方推导值高于输入之和                                        |
| `fallback.minEmc`                                                                    | `1`           | 生成 EMC 下限；同时作为配方求解的 `minimumEmc`                        |
| `overflow.threshold`                                                                 | `999999`      | 严格大于该值的生成候选值会被重置                                      |
| `overflow.resetMin`                                                                  | `100`         | 重置区间下界（含）                                                    |
| `overflow.resetMax`                                                                  | `999000`      | 重置区间上界（含）                                                    |
| `overflow.bucketCount`                                                               | `100`         | 加权采样桶数量                                                        |
| `overflow.weightCap`                                                                 | `20`          | 单个桶的权重上限                                                      |
| `overflow.overwriteScope`                                                            | `owned_only`  | 重置范围，可选 `owned_only` / `entire_custom_emc`                     |
| `overflow.allowProjectEFileRewrite`                                                  | `false`       | 是否允许改写 ProjectE 的 `custom_emc.json`                            |
| `overflow.randomSeed`                                                                | `0`           | `0` 表示每次启动重新随机                                              |
| `safety.maxAllowedDropRatio`                                                         | `0.5`         | 单次生成值允许的最大相对跌幅                                          |
| `nbt.maxNbtVariantsPerItem`                                                          | `100`         | 每个物品最多保留的 NBT 变体数；实际还受硬上限 `1000` 限制             |
| `safety.creativeItemExclusions` / `creativeItemSpecialValues` / `creativeNamespaces` | Phase 18 定义 | Phase 18 新增；当前基线尚未接线，默认值与列表格式以 Phase 18 实现为准 |

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

| 条件                                                                       | 提示                                               |
| -------------------------------------------------------------------------- | -------------------------------------------------- |
| `overwriteScope = entire_custom_emc` 且 `allowProjectEFileRewrite = false` | ProjectE 文件改写会被跳过                          |
| `threshold <= resetMax` / `resetMin > resetMax` / `resetMin < 1`           | 加载阶段记录配置警告；运行时重建被拒绝并保留旧组件 |
| `bucketCount < 1` / `weightCap < 1`                                        | 同上                                               |
| 非法 `overwriteScope`                                                      | 记录警告并回退 `owned_only`                        |

`ConfigWatcher` 对相同警告文本做去重（每个实例生命周期内只记录一次）；`SudoersemcConfig.validateLoadedConfig()` 在每次加载/重载时仍会直接记录溢出配置警告，因此日志中可能同时看到两条来源不同的提示。

### 迁移

`ConfigMigrations` 已实现 schema 2 的纯 Java 迁移规则并有单元测试，但 **Forge TOML 没有 `schemaVersion` 字段，也没有任何调用方**。以下均为工具类行为，当前不会在启动或重载时执行：

- **v1 → v2**：缺少 `overflow.threshold` 时补充 `999999`，缺少 `overwriteScope` 时补充 `owned_only`；已有值保持不变。
- **v2 → v2**：不做任何修改，也不产生提示。
- **高于 v2**：保持原样不做修改，并记录提示。
- **未知版本（`<= 0`）**：按当前版本处理并记录提示。
- 迁移始终返回新的映射，不会修改调用方传入的原始数据。

> **English:** Only per-field ranges are enforced by Forge `defineInRange` today. Phase 16 wired `ConfigValidator.validateCurrent()` (cross-field overflow invariants) into runtime rebuilds: invalid combinations reject the rebuild and keep the previous valid components, while scope conflicts only log warnings. `/sudoemc reload` rebuilds from the currently loaded config values; it does not re-read the TOML itself. `ConfigMigrations` is still not wired and the Forge TOML has no `schemaVersion` field.

---

## 数据文件

| 路径                                       | 说明                                                                                                                                              |
| ------------------------------------------ | ------------------------------------------------------------------------------------------------------------------------------------------------- |
| `config/sudoersemc/generated_entries.json` | 生成值、来源、配方、归属、NBT `variantHash`（如有）与时间戳；只允许更新/删除 `owned=true` 的条目                                                  |
| `config/sudoersemc/overflow_resets.json`   | 溢出重置审计记录；只记录经溢出处理且实际改变数值的条目（含 `ENTIRE_CUSTOM_EMC` 文件改写），服务器停止时落盘                                       |
| `config/sudoersemc/scan_state.json`        | 上次扫描时间、耗时、已定价/未解析数量与指纹；**已生成，但 `ScanStateStore.matches(...)` 没有调用方，不参与跳过判断**                              |
| `config/sudoersemc/quarantined_drops.json` | **尚未生成**。`QuarantinedDropsStore` 只有持久化工具与测试，生产流程没有调用；隔离数量只出现在 `last_report.txt` 的统计中                         |
| `config/sudoersemc/last_report.txt`        | 每轮扫描后写出的诊断报告                                                                                                                          |
| `config/ProjectE/custom_emc.json`          | ProjectE 手动配置；**默认只读**；`entire_custom_emc` + `allowProjectEFileRewrite` 双开关开启时会被受控改写并备份为 `custom_emc.json.<时间戳>.bak` |

本模组自有 JSON 与 `last_report.txt` 的写入采用「临时文件 + 原子替换」；ProjectE 文件改写同样先备份再原子替换。加载失败只告警不崩溃，遇到更高 schema 版本时拒绝覆盖。隔离降价只在 `last_report.txt` 中统计，`quarantined_drops.json` 当前不会生成。

---

## 工作原理

1. **查询** — 通过 `IEMCProxy` 判断物品是否已有 EMC；已有值（SudoersEMC 自有条目除外）会被视为已定价。
2. **计算** — 从配方、标签、稀有度、层级与兜底规则推导候选值；标签与稀有度取自实际物品快照（`getTags()` / `getRarity()`）。同一物品有多个配方时，按多轮求解（含循环检测）取最低的有效确定性成本。Phase 17 起，配方输出中观测到的 NBT 会生成带 `variantHash` 的变体快照并进入同一求解流程；但原版配方不产生 NBT 输出，真实环境未验证。求解只读取不可变快照、在服务器线程执行，当前没有后台线程。
3. **安全** — 对生成候选值应用降价保护、创造专属物品保护与溢出保护；双开关开启时另做 `custom_emc.json` 的受控文件改写。Phase 18 完成前，创造保护的排除项、固定值与命名空间仍是硬编码空集合。
4. **持久化** — 把结果写入 `generated_entries.json`，并标记归属（`owned=true`）；后续扫描会先清空自有条目再重新求解，使配方或标签变化能够反映到生成值上。
5. **写入** — 通过 ProjectE 官方的 `@EMCMapper(priority = -1000)` 扩展点，在 ProjectE 重算 EMC 时以 `setValueBefore` 注入自有条目；手动值会被跳过。
6. **重算** — 在服务器线程触发一次资源重载，ProjectE 随即重建 EMC 映射。

---

## 开发

```bash
# 完整构建
./gradlew build

# 只运行单元测试
./gradlew test
```

测试覆盖：加权采样分布与权重重置、阈值边界、幂等性、持久化往返、损坏文件容错、schema 版本保护、归属刷新、`custom_emc.json` 受控改写（备份、原子替换、未知字段保留）、配置热重载决策，以及 NBT 变体规范化与扫描接线。当前基线共 **449 个单元测试通过**。

---

## 许可证

本项目采用 **Creative Commons Attribution-NonCommercial-ShareAlike 4.0 International（CC BY-NC-SA 4.0）** 许可。

- 允许：共享、改编，需署名、非商业用途、以相同许可证分发。
- 完整条款见 [LICENSE](LICENSE)。
- 版权所有者：**ItzPlana**

---

## English Summary

SudoersEMC is an independent Minecraft Forge 1.20.1 mod that generates EMC values for items that ProjectE does not price. Current baseline: Phase 17 commit `fe956b5`, **449 unit tests passing**; Phase 18 is in progress.

- **Queries** go through ProjectE's public `IEMCProxy`.
- **Writes** use ProjectE's official `@EMCMapper` extension point (`priority = -1000`, `setValueBefore`), never ProjectE internals.
- **Manual values** in `custom_emc.json` are read-only by default and are always skipped during injection. Only when both `overflow.overwriteScope = entire_custom_emc` and `overflow.allowProjectEFileRewrite = true` are set does the mod rewrite entries strictly above `threshold`, after writing a timestamped backup, using atomic replacement and preserving unknown fields.
- **Ownership** is tracked per entry; owned values are re-solved on later scans so recipe or tag changes are picked up, while values not owned by this mod stay untouched.
- **Scanning** advances phase by phase across server ticks, but collection, solving and application still run as single server-thread calls. There is no worker thread and no true work slicing; cancellation is only observed at tick boundaries, and performance has not been measured.
- **Pricing**: multi-pass recipe solving with cycle detection and lowest-cost selection per item, plus a TAG → TIER → RARITY → FALLBACK chain using real item tags and rarity. `fallback.minEmc` is a real TOML key wired into the solver's minimum value.
- **NBT variants**: Phase 17 wires recipe-output NBT into `VariantScanner` and `ItemSnapshot.variantHash`, and the `[nbt]` keys are effective. However, vanilla recipes produce no NBT output, so this path has not been verified in a real environment; `/sudoemc explain` still only accepts a plain item id.
- **Overflow protection** resets generated candidate values above `999999` into a weighted range of `100..999000` with a hard per-bucket weight cap; ProjectE-native and manual values are not touched by this path.
- **Diagnostics**: `last_report.txt` after each scan, `overflow_resets.json` persisted on server stop, and `/sudoemc status|rescan-all|cancel|reload|report|explain`.
- **Not implemented yet**: true batched/parallel scanning and worker threads, incremental fingerprint reuse (`scan_state.json` is written but not used for skip decisions), interruptible cancellation, unchanged-recipe caching/selection in `RecipeSolver`, schema-migration wiring (no `schemaVersion` key in the TOML), on-disk quarantine (`quarantined_drops.json`) and approval commands, alternative costs in `explain`, live Goety verification, and performance measurement on a representative modpack.
- **Phase 18 in progress**: solve → fallback multi-round convergence and configurable creative-item protection (`safety.creativeItemExclusions`, `creativeItemSpecialValues`, `creativeNamespaces`). Until it lands, solving remains single-pass and those three keys have no effect.
- **Configuration**: Phase 16 wired hot reload and cross-field overflow validation. Invalid cross-field combinations reject the rebuild and keep the previous valid components; scope conflicts only log warnings. `/sudoemc reload` rebuilds from the currently loaded values and does not re-read the TOML itself.
- Goety ritual recipes (`goety:ritual`, `craft` type only) are parsed via reflection, but the reflection path has not been verified against a live Goety install. Without ProjectE the mod still scans and persists generated values but does not inject them.

Licensed under CC BY-NC-SA 4.0. Copyright (c) 2026 ItzPlana.

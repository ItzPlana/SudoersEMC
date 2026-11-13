# SudoersEMC

**中文** | [英文版](README.md)

> 为 ProjectE 未定价的物品自动生成 EMC 值的 Minecraft Forge 模组。

[![CI](https://github.com/ItzPlana/SudoersEMC/actions/workflows/ci.yml/badge.svg)](https://github.com/ItzPlana/SudoersEMC/actions/workflows/ci.yml)
[![License: CC BY-NC-SA 4.0](https://img.shields.io/badge/License-CC%20BY--NC--SA%204.0-lightgrey.svg)](https://creativecommons.org/licenses/by-nc-sa/4.0/)

---

## 项目简介

SudoersEMC 是一个 Minecraft Forge 1.20.1 模组。它扫描服务器上已注册的物品与配方，为那些 **ProjectE 尚未赋予 EMC 值**的物品计算 EMC，并把结果持久化到自己的数据文件；安装 ProjectE 时，这些值会通过官方扩展点注入。

模组不会修改 ProjectE 的源码，也不依赖任何非公开接口。所有注入都通过 ProjectE 官方的映射扩展点完成。

### 核心原则

| 原则                 | 说明                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                   |
| -------------------- | -------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| **默认不覆盖手动值** | `config/ProjectE/custom_emc.json` 默认只读，注入阶段也会跳过手动值。仅当 `overflow.overwriteScope = entire_custom_emc` 与 `overflow.allowProjectEFileRewrite = true` 双开关同时开启时，才会对该文件中严格超过阈值的条目做受控改写（先备份、原子替换、保留未知字段）。                                                                                                                                                                                                                                                                                                                  |
| **归属可追踪**       | 每个生成值都记录来源（配方 / 标签 / 稀有度 / 层级 / 兜底）、配方 ID、归属、NBT 变体哈希（如有）与时间戳。                                                                                                                                                                                                                                                                                                                                                                                                                                                                              |
| **计算可解释**       | SudoersEMC 已生成的无 NBT 变体普通条目可用 `/sudoemc explain <item>` 查看来源、配方、归属与最多 5 条备选成本（同一轮求解中其它有效配方的成本）；带 NBT 变体的条目暂不支持，ProjectE 原生值与手动值只在没有生成记录时单独提示。                                                                                                                                                                                                                                                                                                                                                         |
| **安全优先**         | 生成候选值的大幅降价会在非干跑模式下写入 `config/sudoersemc/quarantined_drops.json` 等待审批；`/sudoemc approve` 默认把隔离值写入生成存储（来源 `APPROVED`、`owned=true`）并尽力触发 ProjectE 重映射，`[safety] approveAppliesImmediately=false` 时退化为只标记审批状态的审计模式；手动值、排除项、低于 `fallback.minEmc`、非法值与存储不可用一律拒绝（失败即关闭）。超出上限的生成候选值会被重置到受控区间；创造专属物品保护默认开启，排除项、固定值、命名空间与降价豁免均可配置，自动识别默认只覆盖原版命令/结构类物品；检测到自有条目被外部或手动值修改时会释放所有权并保留外部值。 |
| **分片扫描**         | 扫描由真实分片状态机驱动：采集按 `performance.itemsPerTick` / `adaptiveBatchSizing` 跨游戏刻分批推进，纯求解默认提交到守护线程 `sudoersemc-solver`（`[performance] solveOffThread=true`），应用与持久化仍在服务器线程；`solveOffThread=false` 时回退为同一游戏刻求解。取消令牌可在批次边界与 `Future.cancel(true)` 中被观察到，取消/失败时会写出部分诊断报告；停服或重开服会重置调度器，避免旧 `SOLVING` 状态导致永久 `DEFER`。                                                                                                                                                        |
| **可选依赖**         | 未安装 ProjectE 时模组正常加载，仍会扫描并持久化生成值，但不会向 ProjectE 注入 EMC。                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                   |

---

## 当前状态

项目**正在按 Roadmap 开发**。本文档其余部分描述现有代码已支持的行为，新能力将按下方 Roadmap 推进。

---

## Roadmap

### 目标 1：EMC 配置导入导出

对于整合包作者，这个功能很重要。即将支持：

- JSON 格式导入导出。
- CSV 格式导入导出。
- 按 Mod 导出。
- 全量导出。
- 只导出管理员 override（人工覆盖值）。
- 导出自动生成结果。
- 从其他整合包迁移 EMC 配置。

### 目标 2：扩展 explain 键，区分生成值与人工值

增加 `/sudoemc explain` 的输出键数，明确区分「自动生成值」与「人工设置值」，例如使用 `sudoemc-generated` 与 `sudoemc-override` 两类来源标记。

### 目标 3：管理员批量 EMC 配置 GUI

增加只有管理员权限才能打开的批量 EMC 配置 GUI，使用 `/sudoemc gui` 打开。计划支持功能：

- 多选物品，一次性设置 EMC。
- 搜索物品 ID、注册名、显示名称。
- 按 Mod 分类筛选。
- 只显示没有 EMC 的物品。
- 批量设置 EMC 倍率。
- 批量删除 EMC。
- 批量导入、导出配置。
- 支持直接从 JEI/REI/EMI 点击物品进入配置界面。
- 显示当前 EMC、建议 EMC、最终 EMC。
- 批量自动估价后人工确认。
- 只显示异常溢出值物品。
- 调试诊断：排查异常循环配方；对被重复声明配置的 EMC，找出最终生效的是哪个来源（例如 mod1 声明 258 EMC、mod2 声明 555 EMC，最终选了哪个）。

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

- **ProjectE-Integration** — 与本模组互不干扰。通过 `IEMCProxy` 可见的已有 EMC 会被视为「已定价」，本模组不会覆盖；`fallback.useProjectEIntegrationBaselines`（默认 `true`）会在模组 `projecteintegration` 与 ProjectE 提供器均可用时，读取数据包 `pe_custom_conversions` 中 `values.before` 的正整数条目，作为 `EXTERNAL_BASELINE` 候选进入兜底链；缺少模组或提供器时退化为空基线。
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

> 下列键由 Forge 生成到配置文件中，当前文档列出的键均由生产代码读取。已接入热重载：`ModConfigEvent` 会重建运行时组件，`/sudoemc reload` 也会用当前已加载的配置值触发同一重建路径。扫描进行中时重建会延迟到扫描结束；交叉字段校验失败时拒绝重建并保留上一份有效组件。根级 `schemaVersion` 已接入：模块加载时会在 Forge 解析前迁移磁盘 TOML，迁移前内容备份为时间戳 `.bak`。

### 自动扫描

`general.enabled = true` 时启用启动自动扫描；`general.alwaysRescan = true` 每次启动都强制重扫；两者都不满足时，启动会枚举当前物品注册表/配方并计算五通道输入指纹：仅在「从未扫描」「已生成条目为空」或指纹与 `scan_state.json` 保存值不一致时触发一次扫描，否则复用缓存。指纹计算失败会退化为需要扫描。`/sudoemc rescan-all` 直接请求扫描，不受该跳过判断影响。自动扫描与是否安装 ProjectE 无关。这两个键只在服务器启动时读取，修改后需重启服务器才会改变自动扫描决策；其余扫描相关键在 `/sudoemc reload` 或 Forge 配置重载时通过重建组件生效。

```toml
schemaVersion = 1   # 根级；请勿手动修改（当前构建支持 2）

[general]
enabled = true
alwaysRescan = false

[performance]
itemsPerTick = 100                 # 每游戏刻采集批次预算
adaptiveBatchSizing = true         # 按上一批耗时自适应调整（1–8 倍）
solveOffThread = true              # 纯求解提交到守护线程 sudoersemc-solver

[recipe]
solveRecipes = true
maxRecipePasses = 20
allowRecipeValueIncrease = false
selectionStrategy = "lowest_cost"   # lowest_cost | prefer_simple | stable_id
scanContainerRemainders = true      # 读取合成容器返还（桶、瓶子等）
creditContainerRemainders = true    # 按返还容器价值抵扣配方成本
priceSecondaryOutputs = false       # true 时多输出按总单位拆分计价，默认只给主输出

[fallback]
useFallbackForUnsolvable = true
useTagPricing = true
useRarityPricing = true
useProjectEIntegrationBaselines = true   # 已生效；需要 ProjectE-Integration 且 ProjectE 提供器可用
minEmc = 1

[safety]
largeDropProtection = true
maxAllowedDropRatio = 0.5
protectCreativeOnlyItems = true
creativeItemExclusions = []          # 已接线；默认空列表
creativeItemSpecialValues = []       # 已接线；格式 namespace:path=正整数；默认空列表
creativeNamespaces = []              # 已接线；默认空列表
dropProtectionExemptNamespaces = []  # 按命名空间豁免大幅降价隔离（路径忽略）
dropProtectionExemptItems = []       # 按 namespace:path 精确豁免
autoDetectCreativeOnlyItems = true   # 启用已知创造专属物品自动识别
creativeOnlyItemPatterns = [         # 默认仅匹配原版命令/结构类物品
  "^minecraft:(barrier|chain_command_block|command_block|command_block_minecart|debug_stick|jigsaw|knowledge_book|light|repeating_command_block|structure_block|structure_void)$"
]
autoDetectCreativeOnlyNamespaces = [] # 默认空，避免误伤普通模组物品
preserveExternallyModifiedValues = true # 外部/手动值修改时释放所有权并保留外部值
approveAppliesImmediately = true   # false = 仅标记批准，不写 EMC

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
scanNbtVariants = true          # 已接线；真实环境尚未验证
maxNbtVariantsPerItem = 100     # 已接线；实际扫描受硬上限 1000 限制

[diagnostic]
debugLogging = false            # 已生效；开启后按类别每秒最多 20 条
generateReports = true
```

`recipe.selectionStrategy` 决定同一物品存在多条同成本配方时的取舍：`lowest_cost`（默认，成本相同按配方 ID）、`prefer_simple`（成本相同优先输入/输出/容器返还更少且非催化剂的配方，最后按 ID）、`stable_id`（成本相同按配方 ID）。所有策略都以成本为第一排序键，不会接受高于最低可行成本的配方；非法值回退 `lowest_cost` 并只记录一次警告。该键参与五通道配置指纹，修改后会在下一次服务器启动时触发增量扫描。

`[recipe]` 相关键：`scanContainerRemainders`（默认 `true`）在扫描合成配方时读取容器返还；`creditContainerRemainders`（默认 `true`）在评估成本时按返还容器的已知 EMC 抵扣，避免桶/瓶等被重复计价；`priceSecondaryOutputs`（默认 `false`）开启后，多输出配方按全部产出的总单位拆分净成本，所有产出获得同一单位值，关闭时整份净成本只归主输出。

`[safety]` 相关键：`dropProtectionExemptNamespaces` / `dropProtectionExemptItems` 分别按命名空间和精确物品 ID 跳过大幅降价隔离（非法条目跳过并汇总警告）；`autoDetectCreativeOnlyItems`（默认 `true`）启用自动识别，`creativeOnlyItemPatterns` 默认只匹配原版命令/结构类物品，`autoDetectCreativeOnlyNamespaces` 默认空，自动识别不会隐式影响普通模组物品；显式排除、固定值与 `creativeNamespaces` 规则保持原有语义。`preserveExternallyModifiedValues`（默认 `true`）在应用前检测自有条目被外部/ProjectE 手动值修改，命中后释放所有权并保留外部值，阻止本轮自动值覆盖。真实模组创造物品仍需显式配置。
根级 `schemaVersion` 默认 `1`（旧版），当前构建支持 `2`，请勿手动修改；模块会在 Forge 解析前把旧文件迁移到当前版本并备份原文件。`fallback.useProjectEIntegrationBaselines`（默认 `true`）已实际生效：仅当 ProjectE-Integration 已安装且 ProjectE 提供器可用时，读取 `pe_custom_conversions` 数据包中的 `values.before` 作为外部基线，否则返回空基线。`diagnostic.debugLogging`（默认 `false`）已实际生效：开启后按类别输出调试日志，每类别每秒最多 20 条，被抑制条目在扫描结束时汇总；关闭时高频路径只做一次轻量读取。

`[safety] approveAppliesImmediately`（默认 `true`）控制 `/sudoemc approve` 是否在批准后立即把隔离值写入生成存储；设为 `false` 时退化为审计模式，只标记审批状态，不写 EMC、不触发重映射。`[performance] solveOffThread`（默认 `true`）把纯求解提交到守护线程 `sudoersemc-solver`；设为 `false` 时在服务器线程同步求解，行为与旧版一致。`performance.itemsPerTick` 与 `performance.adaptiveBatchSizing` 真正控制采集批次：前者是每游戏刻的基础预算（自适应上限为 8 倍），后者按上一批耗时扩容、缩容或保持。

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
| `entire_custom_emc`  | 受控改写 ProjectE 的 `custom_emc.json`，**需要同时开启 `allowProjectEFileRewrite`**。启用后，文件中严格大于 `threshold` 的条目会被重置到 `[resetMin, resetMax]`；写前生成 `custom_emc.json.<时间戳>.bak` 备份，写入采用临时文件 + 原子替换，未知字段与键顺序保留。文件缺失、损坏或结构不符时保持原样。改写结果记入 `overflow_resets.json`（`scope = ENTIRE_CUSTOM_EMC`）。仅开启其中任一开关都不会改写文件；作用域冲突会在加载或重载时记录警告。 |

作用域与开关在运行时重建时读取；`/sudoemc reload` 或 Forge 配置重载后可以生效。

---

## 命令

所有子命令都注册在 `/sudoemc` 下，并要求权限等级 2（管理员）。

| 命令                      | 作用                                           | 说明                                                                                                                                                    |
| ------------------------- | ---------------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `/sudoemc status`         | 查看扫描阶段、进度、上次耗时与上次结果         | 只读内存进度与扫描状态文件                                                                                                                              |
| `/sudoemc rescan-all`     | 请求一次全量重新扫描                           | 已有扫描进行中时返回失败提示；不受启动指纹跳过影响                                                                                                      |
| `/sudoemc cancel`         | 请求取消当前扫描                               | 采集、求解与应用循环在检查点响应；取消时不更新扫描指纹，报告标记为部分报告                                                                              |
| `/sudoemc reload`         | 按当前已加载的配置重建运行时组件               | 扫描中延后到扫描结束；校验失败时保留上一份有效配置                                                                                                      |
| `/sudoemc report`         | 读取 `config/sudoersemc/last_report.txt`       | 尚无报告时提示先执行一次扫描                                                                                                                            |
| `/sudoemc explain <item>` | 解释某个已生成条目的来源、配方、归属与备选成本 | 只支持无 NBT 变体的普通条目；最多显示 5 条备选成本；ProjectE 原生值/手动值会单独提示                                                                    |
| `/sudoemc approve <item>` | 批准该物品当前的隔离降价记录                   | 默认立即写入降价值（来源 `APPROVED`）并尽力请求 ProjectE 重映射；`approveAppliesImmediately=false` 时只标记并落盘；仅支持普通物品 ID（不支持 NBT 变体） |

---

## 配置校验与迁移

### 当前实际行为

- **单字段范围**由 Forge 的 `defineInRange` 在加载时校验，越界值会被纠正到允许区间。覆盖的键包括 `performance.itemsPerTick`、`recipe.maxRecipePasses`、`fallback.minEmc`、`overflow.threshold`、`overflow.resetMin`、`overflow.resetMax`、`overflow.bucketCount`、`overflow.weightCap`、`safety.maxAllowedDropRatio` 与 `nbt.maxNbtVariantsPerItem`。
- **运行时交叉字段校验**已接线：`ConfigValidator.validateCurrent()` 在配置加载/重载事件与 `/sudoemc reload` 时执行 `validateCrossField(...)`。`overflow.threshold <= resetMax`、`overflow.resetMin > resetMax`、`overflow.resetMin < 1`、`bucketCount < 1`、`weightCap < 1` 会拒绝重建并保留上一份有效组件；`overwriteScope = entire_custom_emc` 且 `allowProjectEFileRewrite = false` 只记录警告，不改写文件。
- 非法的 `overflow.overwriteScope` 会在解析时回退为 `owned_only` 并记录警告，不会拒绝重建。
- 非法的 `recipe.selectionStrategy` 会在解析时回退为 `lowest_cost` 并只记录一次警告，不会拒绝重建；该键参与配置指纹，修改后会在下一次服务器启动时触发增量扫描。
- 列表键 `safety.dropProtectionExemptNamespaces` / `dropProtectionExemptItems` / `creativeOnlyItemPatterns` / `autoDetectCreativeOnlyNamespaces` 由 `ConfigValidator` 逐条校验；非法条目只会被跳过并汇总警告，不会拒绝重建。
- `safety.maxAllowedDropRatio` 的 Forge 范围是 `[0.0, 1.0]`；若设为 `0.0`，`LargeDropProtection.Settings` 构造会失败，扫描管线回退到默认 `0.5`（不是拒绝整个配置）。
- `nbt.maxNbtVariantsPerItem` 的 Forge 范围是 `[1, Integer.MAX_VALUE]`；运行时不拒绝大于 `1000` 的值，但 `VariantScanner` 会用硬上限 `1000` 截断实际扫描数量。
- 启动时若溢出设置构造失败，扫描管线会回退到内置默认值；运行期重建采用「全有或全无」：新组件全部构建成功后才替换旧组件。
- `/sudoemc reload` 会用当前已加载的配置值重建运行时组件；扫描进行中时返回「将在扫描结束后生效」，校验失败时返回「已保留上一份有效配置」。它不会主动重新读取磁盘上的 TOML；直接编辑配置文件后，Forge 检测到变化时会触发 `ModConfigEvent.Reloading`，走同一重建路径。
- `ConfigValidator` 与 `ConfigWatcher` 已接入生产配置加载流程；`ConfigMigrations` 已由 `ConfigTomlMigration` 接入：模块加载时在 Forge 解析前迁移磁盘 TOML，`ModConfigEvent` 内的入口作为重载/兜底。

### 默认值

| 配置项                                                                               | 默认值              | 说明                                                                                          |
| ------------------------------------------------------------------------------------ | ------------------- | --------------------------------------------------------------------------------------------- |
| `schemaVersion`                                                                      | `1`                 | 根级键，默认旧版 1，当前构建迁移到 2；请勿手动修改                                            |
| `performance.itemsPerTick`                                                           | `100`               | 每游戏刻采集批次的基础预算，真正切分采集工作                                                  |
| `performance.adaptiveBatchSizing`                                                    | `true`              | 按观测耗时自适应调整采集批次（1–8 倍基础值）                                                  |
| `performance.solveOffThread`                                                         | `true`              | 纯求解提交到守护线程 `sudoersemc-solver`；false 回退服务器线程同步求解                        |
| `recipe.maxRecipePasses`                                                             | `20`                | 单次扫描的配方求解遍数上限                                                                    |
| `recipe.allowRecipeValueIncrease`                                                    | `false`             | 是否允许配方推导值高于输入之和                                                                |
| `recipe.selectionStrategy`                                                           | `lowest_cost`       | 同一物品多条同成本配方时的取舍：`lowest_cost` / `prefer_simple` / `stable_id`；非法值回退默认 |
| `recipe.scanContainerRemainders`                                                     | `true`              | 扫描合成配方时读取容器返还（桶、瓶子等）                                                      |
| `recipe.creditContainerRemainders`                                                   | `true`              | 按返还容器的已知 EMC 抵扣配方成本                                                             |
| `recipe.priceSecondaryOutputs`                                                       | `false`             | true 时多输出按总单位拆分计价，否则整份净成本只归主输出                                       |
| `fallback.minEmc`                                                                    | `1`                 | 生成 EMC 下限；同时作为配方求解的 `minimumEmc`                                                |
| `fallback.useProjectEIntegrationBaselines`                                           | `true`              | 需要安装 ProjectE-Integration 且 ProjectE 提供器可用，否则空基线                              |
| `overflow.threshold`                                                                 | `999999`            | 严格大于该值的生成候选值会被重置                                                              |
| `overflow.resetMin`                                                                  | `100`               | 重置区间下界（含）                                                                            |
| `overflow.resetMax`                                                                  | `999000`            | 重置区间上界（含）                                                                            |
| `overflow.bucketCount`                                                               | `100`               | 加权采样桶数量                                                                                |
| `overflow.weightCap`                                                                 | `20`                | 单个桶的权重上限                                                                              |
| `overflow.overwriteScope`                                                            | `owned_only`        | 重置范围，可选 `owned_only` / `entire_custom_emc`                                             |
| `overflow.allowProjectEFileRewrite`                                                  | `false`             | 是否允许改写 ProjectE 的 `custom_emc.json`                                                    |
| `overflow.randomSeed`                                                                | `0`                 | `0` 表示每次启动重新随机                                                                      |
| `safety.maxAllowedDropRatio`                                                         | `0.5`               | 单次生成值允许的最大相对跌幅                                                                  |
| `nbt.maxNbtVariantsPerItem`                                                          | `100`               | 每个物品最多保留的 NBT 变体数；实际还受硬上限 `1000` 限制                                     |
| `safety.creativeItemExclusions` / `creativeItemSpecialValues` / `creativeNamespaces` | 空列表              | 排除项与命名空间默认为空、无固定值；非法条目会被跳过并汇总警告                                |
| `safety.dropProtectionExemptNamespaces` / `dropProtectionExemptItems`                | 空列表              | 按命名空间/精确物品 ID 豁免大幅降价隔离；非法条目跳过并汇总警告                               |
| `safety.autoDetectCreativeOnlyItems`                                                 | `true`              | 启用已知创造专属物品自动识别                                                                  |
| `safety.creativeOnlyItemPatterns`                                                    | 原版命令/结构类正则 | 默认只匹配 `minecraft:barrier`、`command_block`、`structure_block` 等原版物品                 |
| `safety.autoDetectCreativeOnlyNamespaces`                                            | 空列表              | 默认空，避免误伤普通模组物品                                                                  |
| `safety.preserveExternallyModifiedValues`                                            | `true`              | 外部/ProjectE 手动值修改时释放所有权并保留外部值                                              |
| `safety.approveAppliesImmediately`                                                   | `true`              | `/sudoemc approve` 默认立即应用隔离值；false 仅标记审批状态，不写 EMC                         |
| `diagnostic.debugLogging`                                                            | `false`             | 开启后按类别每秒最多 20 条的限流调试日志                                                      |

### 交叉字段校验（已接线）

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

### 迁移（已接入）

Forge 1.20.1 没有公开的「解析前」配置钩子。模组在 `SudoersemcConfig.register()` 之前直接迁移磁盘上的 `config/sudoersemc-common.toml`，这是 Forge 按配置规范重写文件前唯一能保留原始内容的时机；`ModConfigEvent.Loading/Reloading` 内的迁移入口作为重载/兜底，已迁移文件返回 `NO_OP`。

- **当前版本**：`schemaVersion = 2`，与 `ConfigMigrations.SCHEMA_VERSION` 一致；根级键默认值为旧版 `1`。
- **v1 → v2**：缺少 `overflow.threshold` 时补充 `999999`，缺少 `overflow.overwriteScope` 时补充 `owned_only`；已有值保持不变。
- **v2 → v2**：不做任何修改，也不产生提示。
- **高于 v2**：拒绝迁移并保留原文件（`REFUSED_NEWER`），绝不覆盖未来版本的配置。
- **未知版本（`<= 0`）**：`schemaVersion = 0` 按旧版 1 处理并迁移；缺失键同样按旧版 1 处理。
- **非法版本**：负数、非数字字符串、布尔/数组/小数等非法类型保留原文件并记录警告（`FAILED_RETAINED`）。
- **写盘策略**：先在临时文件上修改已知键，成功后把原文件备份为 `sudoersemc-common.toml.<时间戳>.bak`，再以原子替换写回；文件系统不支持原子移动时降级为普通替换。任何失败都保留原文件并清理临时文件。
- 迁移只读写 `schemaVersion`、`overflow.threshold` 与 `overflow.overwriteScope`，不会整体重写配置。

> **已知限制：** Forge 加载后会按配置规范重写 TOML，未知键与手写注释可能在首次加载时被 Forge 丢弃。解析前迁移保证迁移前内容完整备份为时间戳 `.bak`，但无法阻止 Forge 的后续规范化。另外，`schemaVersion` 超过 `Integer.MAX_VALUE` 时解析前迁移会拒绝，但 Forge 随后仍可能把越界值纠正为默认 `1`。

---

## 数据文件

| 路径                                       | 说明                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                              |
| ------------------------------------------ | ----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `config/sudoersemc/generated_entries.json` | 生成值、来源、配方、归属、NBT `variantHash`（如有）、备选成本与时间戳；结构版本 v2，仍可读取 v1；只允许更新/删除 `owned=true` 的条目；检测到外部修改时会把条目释放为 `owned=false`                                                                                                                                                                                                                                                                                                                                |
| `config/sudoersemc/overflow_resets.json`   | 溢出重置审计记录；只记录经溢出处理且实际改变数值的条目（含 `ENTIRE_CUSTOM_EMC` 文件改写），服务器停止时落盘                                                                                                                                                                                                                                                                                                                                                                                                       |
| `config/sudoersemc/scan_state.json`        | 上次扫描时间、耗时、已定价/未解析数量与五通道输入指纹（item/recipe/tag/config/baseline）；启动时用于判断输入是否变化并跳过扫描，指纹计算失败或文件缺失时触发扫描                                                                                                                                                                                                                                                                                                                                                  |
| `config/sudoersemc/quarantined_drops.json` | 大幅降价隔离记录：旧值、拟写入值、降幅、原因、时间戳与 `approved`；同一物品最多一条，最多 5000 条，首次重写前备份；干跑模式不写盘                                                                                                                                                                                                                                                                                                                                                                                 |
| `config/sudoersemc/last_report.txt`        | 每轮扫描后写出的诊断报告；包含配方扫描统计与失败明细、游戏刻耗时（最大/平均/P95）、峰值堆与报告时堆内存；另含求解轮次与 `solverCacheHits` / `solverCacheMisses`；并含 `shapedRecipes` / `shapelessRecipes`、`multiOutputRecipes` / `containerRemainderRecipes` 与 `ownershipExternalModifications` / `ownershipReleasedEntries`；以及 `externalBaselineAvailable` / `externalBaselineEntries` / `externalBaselineApplied` 与 `resolverRegistered` / `resolverApplied` 计数器；取消/失败时写出标记为部分报告的版本 |
| `config/ProjectE/custom_emc.json`          | ProjectE 手动配置；**默认只读**；`entire_custom_emc` + `allowProjectEFileRewrite` 双开关开启时会被受控改写并备份为 `custom_emc.json.<时间戳>.bak`                                                                                                                                                                                                                                                                                                                                                                 |

本模组自有 JSON 与 `last_report.txt` 的写入采用「临时文件 + 原子替换」；ProjectE 文件改写同样先备份再原子替换。加载失败只告警不崩溃，遇到更高结构版本时拒绝覆盖。`scan_state.json` 的五通道指纹参与启动跳过判断；取消的扫描不会更新它。隔离降价会写入 `quarantined_drops.json`；审批默认把拟写入值应用为 `APPROVED` 条目并请求 ProjectE 重映射，审计模式（`approveAppliesImmediately=false`）下只改变标记、不修改数值；干跑模式不产生隔离文件。外部修改检测不会新增数据文件：命中时会释放 `generated_entries.json` 中对应条目的所有权并保留外部值，计数写入 `last_report.txt`。配置迁移会先把原文件备份为 `config/sudoersemc-common.toml.<时间戳>.bak`，再以原子替换写回迁移后的 TOML；Forge 后续仍会按配置规范重写该文件。

---

## 工作原理

服务器启动时，模块会先在 Forge 解析前迁移磁盘 TOML；若自动扫描已启用且未设置 `alwaysRescan`，模组会再计算五通道输入指纹并与 `scan_state.json` 比较；一致时跳过本轮扫描，不一致或计算失败时进入下列流程。`/sudoemc rescan-all` 始终直接请求扫描。

1. **查询** — 通过 `IEMCProxy` 判断物品是否已有 EMC；已有值（SudoersEMC 自有条目除外）会被视为已定价。
2. **计算** — 从配方、标签、稀有度、层级与兜底规则推导候选值；标签与稀有度取自实际物品快照（`getTags()` / `getRarity()`）。同一物品有多个配方时，按多轮求解（含循环检测）取最低的有效确定性成本，成本相同时按 `[recipe] selectionStrategy` 取舍；`RecipeSolver` 使用有界 LRU 评估缓存并在配方循环中检查取消令牌。`RecipeScanner` 区分有序/无序合成并按实际宽 × 高支持非 3x3，容器返还写入配方快照；`StandardRecipeEvaluator` 在 `recipe.creditContainerRemainders` 开启时抵扣返还容器价值，并在 `recipe.priceSecondaryOutputs` 开启时按全部产出的总单位拆分净成本。配方输出中观测到的 NBT 会生成带 `variantHash` 的变体快照并进入同一求解流程；空 NBT 与仅含默认 `{Damage:0}` 的标签按普通物品处理。兜底链可在标签/稀有度定价之前使用 ProjectE-Integration 外部基线或第三方 NBT 解析器的 `EXTERNAL_BASELINE` 候选，并同样先经过排除、手动值、最小 EMC 与溢出阈值检查。原版配方不产生 NBT 输出，真实环境未验证。求解只读取不可变快照，默认在守护线程 `sudoersemc-solver` 执行纯计算（`solveOffThread=true`），采集、应用与持久化仍在服务器线程；`solveOffThread=false` 时回退为服务器线程同步求解。
3. **安全** — 对生成候选值应用降价保护（超过阈值的降价写入隔离记录并等待审批，支持按命名空间/物品豁免）、创造专属物品保护（支持显式配置与自动识别，自动识别默认只匹配原版命令/结构类物品）与溢出保护；应用前检测自有条目是否被外部或 ProjectE 手动值修改，命中则释放所有权并保留外部值，阻止本轮覆盖；双开关开启时另做 `custom_emc.json` 的受控文件改写。
4. **持久化** — 把结果写入 `generated_entries.json`，并标记归属（`owned=true`）；后续扫描会先清空自有条目再重新求解，并清理不再参与定价的陈旧自有条目，使配方或标签变化能够反映到生成值上。检测到外部修改的条目会以 `owned=false` 保留外部值，本轮不再覆盖。取消的扫描不会更新 `scan_state.json` 指纹，并跳过陈旧条目清理与 `custom_emc.json` 改写。
5. **写入** — 通过 ProjectE 官方的 `@EMCMapper(priority = -1000)` 扩展点，在 ProjectE 重算 EMC 时以 `setValueBefore` 注入自有条目；手动值会被跳过。
6. **重算** — 在服务器线程触发一次资源重载，ProjectE 随即重建 EMC 映射。
7. **诊断** — 扫描管线把配方扫描统计、求解轮数、求解缓存命中/未命中、隔离/清理数量、游戏刻耗时与堆内存采样集中到 `PipelineResult.extraCounters`，扫描结束后写入 `last_report.txt`；另输出 `shapedRecipes` / `shapelessRecipes`、`multiOutputRecipes` / `containerRemainderRecipes` 与 `ownershipExternalModifications` / `ownershipReleasedEntries`；并输出 `externalBaselineAvailable` / `externalBaselineEntries` / `externalBaselineApplied` 与 `resolverRegistered` / `resolverApplied` 计数器。取消或失败时由 `AbortListener` 写出部分报告，保留已采集快照的统计与终止原因标记。

---

## 开发

```bash
# 完整构建
./gradlew build

# 只运行单元测试
./gradlew test
```

测试覆盖：加权采样分布与权重重置、阈值边界、幂等性、持久化往返、损坏文件容错、结构版本保护（含 v1→v2 兼容）、归属刷新、陈旧条目清理、`custom_emc.json` 受控改写（备份、原子替换、未知字段保留）、配置热重载决策、NBT 变体规范化（含空 NBT 与默认 `{Damage:0}`）、隔离记录存储与审批、备选成本持久化与 `/sudoemc explain` 输出、Goety 读取失败语义、诊断报告计数器与失败明细、游戏刻耗时最大/平均/P95（最近秩法）、峰值堆采样、`extraCounters` 持久化、取消/失败部分报告、五通道指纹与变化通道、启动跳过策略、协作式取消检查点、有界 LRU 求解缓存命中/未命中与配方选择策略（含非法值回退），以及扫描接线、降价豁免解析与判定、创造物品自动识别与优先级、外部修改检测与所有权释放、有序/无序合成统计、非 3x3 网格、容器返还扫描与成本抵扣、多输出拆分计价，以及配置校验/报告计数器、解析前配置迁移（默认版本、备份、原子替换失败路径、高版本/超大版本/非法版本拒绝、幂等、未知键保留）、外部基线 JSON 解析与不可用退化、基础候选优先级/排除/最小 EMC/降价隔离/溢出阈值、调试日志开关与每秒限流、解析器注册顺序/异常隔离/非正值忽略/指纹变化，以及诊断报告外部基线计数；审批应用安全规则、审计模式、幂等与存储写入测试，确定性分页、增量采集会话、纯求解/应用一致性、工作线程、状态机非阻塞轮询、停服重置与自适应批量起始增长测试。当前基线共 **839 个单元测试通过**（0 失败 / 0 错误 / 0 跳过）。

---

## 许可证

本项目采用 **Creative Commons Attribution-NonCommercial-ShareAlike 4.0 International（CC BY-NC-SA 4.0）** 许可。

- 允许：共享、改编，需署名、非商业用途、以相同许可证分发。
- 完整条款见 [LICENSE](LICENSE)。
- 版权所有者：**ItzPlana**

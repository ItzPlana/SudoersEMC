# Changelog

本文件记录 SudoersEMC 的发布变更。格式参考 [Keep a Changelog](https://keepachangelog.com/zh-CN/1.1.0/)，
版本号遵循语义化版本。

## 0.1.0（未发布）

### 新增

- Phase 0–10 组件：物品/配方扫描、递归求解、兜底定价、NBT 变体、批处理、安全保护、诊断与 `/sudoemc` 命令。
- Phase 11 入口接线：命令树注册、服务器 tick 驱动、`ScanCollectionPipeline` 分片扫描/后台求解/持久化、`scan_state.json` 加载/保存、ProjectE 可选集成。
- Phase 12 扫描管线集成：`ScanPipeline` 统一完成分片采集、后台求解、所有权分离、统一排除、TagPricing 锚点、降价/溢出保护、审计落盘与诊断报告；启动缓存按完整输入指纹复用。
- `[general] enabled` 主开关：关闭时不再创建或驱动扫描调度器。
- `[general] alwaysRescan`：开启时在服务器启动后自动请求一次扫描。
- 停服时重置扫描调度器，避免下一个世界继续推进旧扫描。

### 已知限制

- 扫描管线已接线，但尚未在专用服务器与大型整合包完成端到端实机验证；`KnownItemsStore` 尚未接入生产管线；`QuarantinedDropsStore` 与 `last_report.txt` 已由 Phase 12 接线。
- `ENTIRE_CUSTOM_EMC` 的 ProjectE 文件改写未实现。
- Goety 真实环境与大型模组包性能未验证。

### 修正

- 修正 README 中与实现不符的发布声明：`entire_custom_emc` 文件改写、`custom_emc.json` 可写例外、ProjectE Integration 映射优先级、`/sudoemc explain` 覆盖范围、「不阻塞主线程」的预算语义、数据文件表与配置键接线状态。
- 修正 CHANGELOG 中已过期的「配置校验/迁移未接入」条目。
- 配置校验、schema 迁移与重载失败回退已在 Phase 8 修复中接入 `SudoersemcConfig` 的加载/重载流程，原「尚未接入」的限制条目已移除。

### 许可

- CC BY-NC-SA 4.0（与 `LICENSE`、`mods.toml` 的 `mod_license` 一致）。

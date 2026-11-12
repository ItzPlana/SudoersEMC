# Changelog

本文件记录 SudoersEMC 的发布变更。格式参考 [Keep a Changelog](https://keepachangelog.com/zh-CN/1.1.0/)，
版本号遵循语义化版本。

## 0.1.0（未发布）

### 新增

- Phase 0–10 组件：物品/配方扫描、递归求解、兜底定价、NBT 变体、批处理、安全保护、诊断与 `/sudoemc` 命令。
- Phase 11 入口接线：命令树注册、服务器 tick 驱动、`scan_state.json` 加载/保存、ProjectE 可选集成。
- `[general] enabled` 主开关：关闭时不再创建或驱动扫描调度器。
- `[general] alwaysRescan`：开启时在服务器启动后自动请求一次扫描。
- 停服时重置扫描调度器，避免下一个世界继续推进旧扫描。

### 已知限制

- 扫描管线尚未接线：采集器仍返回 `"待接入真实扫描", 0, 0`，当前构建不会生成任何 EMC 值。
- `ENTIRE_CUSTOM_EMC` 的 ProjectE 文件改写未实现。
- `ConfigValidator` / `ConfigWatcher` / `ConfigMigrations` 尚未接入生产配置流程。
- Goety 真实环境与大型模组包性能未验证。

### 许可

- CC BY-NC-SA 4.0（与 `LICENSE`、`mods.toml` 的 `mod_license` 一致）。

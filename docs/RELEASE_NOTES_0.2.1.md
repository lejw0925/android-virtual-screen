## 虚拟屏 0.2.1

稳定性与性能维护版本：减少启动期主线程 IO，限制无障碍节点树的采集时间与输出体积，并把画中画设置移入「显示与控制」。要求 Android 13 或更新版本，并使用已授权的 Root 或 Shizuku / Stellar 后端。

### 主要更新

- **启动期 IO**：MCP Token 的 Android Keystore 读取改为后台预热，服务器启动时再兜底加载；隧道目录清理延迟到隧道实际启动、登录或管理时。`Application.onCreate` 不再同步解密 Token 或递归清理隧道目录。
- **节点树预算**：单次采集增加 1500 ms 时间预算，格式化输出最多 2000 节点；结果新增 `time_budget_ms`、`output_limit` 和 `time_limit`/`output_limit` 截断原因，降低密集页面卡顿与超大 MCP 响应。
- **画中画设置**：画中画预览开关和系统画中画设置入口从「权限与后端」移到「显示与控制」的「画中画」分组。

### 验证范围

- JVM 单元测试 147 项、Android Lint（0 error、69 warning）、Debug 构建通过。
- vivo V2419A / Android 15 / KernelSU Root：`DeviceBackendTest` 通过（6 tests，2 项 Shizuku 因未安装跳过）；`DeviceToolProbeTest` 通过，19/19 个 MCP 工具完成。测试结束后未残留虚拟屏。
- Release 继续使用 R8、资源压缩和按 ABI 分包，由标签触发的 Actions 构建、签名并发布。

### 说明

节点时间预算和输出上限是保护性上限，不改变 5000 节点/100 层/1000 子项的采集上限；`full` 模式在预算内最多返回 2000 个节点。更密集的页面仍建议通过 `mode=tree` 或较小范围观察。

## Virtual Screen 0.2.1

A stability and performance maintenance release: startup work is moved off the main thread, accessibility tree collection and output are bounded, and PiP settings move to **Display & control**. Requires Android 13+ with an authorized Root or Shizuku / Stellar backend.

### Highlights

- **Startup I/O**: MCP token decryption through the Android Keystore is warmed in the background and loaded as a fallback when the server starts; tunnel directory cleanup is deferred until a tunnel actually starts, logs in, or is managed. `Application.onCreate` no longer decrypts the token or recursively cleans the tunnel directory synchronously.
- **Tree budgets**: each collection has a 1,500 ms time budget and formatted output is capped at 2,000 nodes. Results expose `time_budget_ms`, `output_limit`, and `time_limit`/`output_limit` truncation reasons, reducing jank on dense pages and oversized MCP responses.
- **PiP settings**: the PiP preview switch and system PiP settings entry moved from **Permissions and backend** to the **Picture-in-picture** group under **Display & control**.

### Validation

- 147 JVM unit tests, Android Lint (0 errors, 69 warnings), and the Debug build pass.
- vivo V2419A / Android 15 / KernelSU Root: `DeviceBackendTest` passed (6 tests; 2 Shizuku cases skipped because Shizuku is not installed); `DeviceToolProbeTest` passed all 19 MCP tools. No virtual display remained after the runs.
- Release builds continue to use R8, resource shrinking, and per-ABI APKs; the tagged workflow builds, signs, and publishes them.

### Note

The time budget and output cap are protective limits; the collection limits remain 5,000 nodes, depth 100, and 1,000 children per node. `full` output returns at most 2,000 nodes within the budget. For denser pages, prefer `mode=tree` or a narrower observation scope.

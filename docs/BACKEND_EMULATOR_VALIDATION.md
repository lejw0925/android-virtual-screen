# Root 与 Shizuku 模拟器对照验证

日期：2026-10-17。服务器 AVD `xhs_test`，ADB `emulator-5554`，Android 15 / API 35 / x86_64，SELinux `Enforcing`。测试对象为本仓库的 Debug APK 和独立 fixture，不操作其他应用的数据。

## 结论与边界

Shizuku 使用官方 `13.6.0.r1086.2650830c`，由非 Root ADB 启动；服务和应用 UserService 均为 shell UID 2000。其应用授权、Surface 截图、输入、节点、显示重建和 HTTP MCP 工作流均有实际设备验证。

2026-10-17 的最终 Debug APK 通过应用内全量检测：Shizuku 后端 19/19 个 MCP 工具通过，显示创建、截图、输入和节点能力均可用；熄屏运行未在本轮执行，保持“待实际检测”。探测结束后临时虚拟屏已关闭，系统只剩内置屏。

此镜像的 AOSP `su` 为 `4750 root:shell`，SELinux 处于 `Enforcing`。`adb shell su 0 id` 可取得 UID 0，但应用 UID 执行 `/system/xbin/su` 返回 `Permission denied`；应用内授权流程因此提示需要 Root 授权，无法在此镜像启动 Root 全量 MCP 探测。Root 的完整应用授权、预览桥、取消及 MCP 链路在这个镜像上未通过验证。通过 `adb shell su 0 ... app_process` 对真实 Root 引擎进行了独立测试，不能用该结果替代普通应用授权验证。未修改 SELinux 或安装替代 `su`。

| 能力 | Shizuku 普通应用流程 | Root UID 0 直接引擎 |
| --- | --- | --- |
| 创建、验证、关闭独立虚拟屏 | 通过 | 通过，720×1600 / 320 dpi |
| 启动 fixture、获取截图 | 通过 | 通过 |
| 点击、中文输入 | 通过，并核对节点/截图效果 | 通过，截图确认计数为 1、文本为 `Root 中文測試` |
| 长按、滑动、坐标滚动、返回键 | HTTP 命令接受；额外验证长按参数拒绝 | 命令接受，仍需后续观察判断业务效果 |
| 越界坐标、禁止的全局按键 | 拒绝 | 分别返回 `COORDINATE_OUT_OF_BOUNDS`、`KEY_UNSUPPORTED` |
| 无障碍节点、选择器、emoji、清空文本 | 通过 | 普通应用 Root 授权受限，未重跑 |
| 连续重建、旧观察拒绝 | 三次重建通过 | 未重跑完整应用路径 |
| MCP 认证、19 工具、租约、取消、回执隔离 | 真实 HTTP 通过 | 普通应用 Root 授权受限，未重跑 |

## 确认并修复的问题

1. **`su` 接口差异。** 原 Root 启动固定使用 `su -c`，AOSP 接口要求 `su 0 sh -c`。现在先用只读 `id -u` 探测并核对身份，缓存成功的形式；实际输入不自动重发。
2. **Root Unicode 备用输入。** 无可用无障碍编辑框时，需要以 shell UID 初始化剪贴板 Binder。原先固定 `su 2000 -c`，在 AOSP 上失败。现在对 UID 2000 使用同一套语法及身份探测，直接 Root 引擎的中文输入已实际生效。
3. **Root 授权超时。** 探测不能在超时保护前阻塞读取输出。现在先有界等待，再读取少量 UID 输出；超时终止进程，失败清除已保存的授权状态。新增四项 JVM 回归，覆盖退出码、超时、UID 0 和 UID 2000 身份。
4. **节点误判过期。** Android 15 窗口稳定过程中控件移动了 6 px，旧签名包含边界，导致有效节点被拒绝。节点身份改用窗口、资源 ID、类、文本及能力字段；仍校验显示、窗口、节点刷新和观察有效性，布局坐标继续作为观察数据返回。

测试本身也修复了两个时序问题：重配置测试使用独立远程租约，避免 Activity 停止释放手机租约；输入前等待 fixture 的真实控件出现，避免点击 Android 启动画面。节点检查只轮询观察，不重试已接受的输入。

## 实现差异

两种后端共享 `PrivilegedDisplayEngine`，输入限制、虚拟屏作用域和 MCP 协议没有两套实现。Root 通过 `su` 子进程的 JSON 流控制引擎，并额外注册预览桥；Shizuku 通过授权 UserService 的 Binder 调用引擎并直接传递 Surface。Shizuku 在本次测试中已经是 UID 2000；Root 的备用剪贴板输入需要再启动一个 UID 2000 帮助进程。节点操作均依赖应用的无障碍服务。

本次不能比较两个后端的整体延迟，也不能据此断言所有 Root 管理器/ROM 行为相同。后续实机应重点补测 SakiSU/Magisk 的应用授权、预览桥、取消时的清理以及授权撤销。

## 可复查证据

本地运行产物在 `build/validation/2026-10-17/`：

- `shizuku-instrumentation.log`：4 项设备测试通过。
- `shizuku-nodes.log`：普通应用节点点击、中文效果和滚动检查。
- `shizuku-mcp-baseline.log`、`shizuku-mcp-enhanced.log`、`shizuku-mcp-enhanced.json`：HTTP 与工作流结果。
- `shizuku-gestures.log`、`shizuku-gesture-results.json`：真实 HTTP 创建/关闭屏幕、长按、多点滑动路径、坐标滚动和返回键回执；命令接受不等于业务效果确认。
- `root-app-authorization.log`：普通应用 Root 授权失败的完整断言。
- `root-engine-results.json`、`root-engine-run.log`、`root-engine-7.jpg`：UID 0 引擎和输入后截图。

构建验证：88 项 JVM 测试通过；Android Lint 无错误，62 项警告；Debug 主 APK 已构建并安装。警告包含现有非公开 Android API 使用，当前 Android 15 的运行测试不代表其他系统版本兼容性。

最终交付为 `dist/virtual-screen-debug-emulator-20261017.apk`，已校验 APK 签名，并逐字节核对模拟器安装文件。SHA-256：`730b10d28973a02c8f685c4beef474970892f13fabdbaea91a98d5eab7be76d2`。应用恢复为 Shizuku 后端，MCP 保持关闭；临时虚拟屏已释放。

按 [BUILD.md](BUILD.md) 授权设备后，可重跑 `DeviceBackendTest` 和 `scripts/verify-mcp.py`、`scripts/verify-mcp-enhanced.py`。对 Shizuku 设备测试传入 `-e require_shizuku true`，确保未授权时直接失败而不静默跳过。Token 使用权限为 600 的本地文件，不写入报告。

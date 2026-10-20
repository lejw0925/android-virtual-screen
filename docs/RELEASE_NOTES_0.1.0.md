## 虚拟屏 0.1.0

首个稳定版，要求 Android 13 或更新版本，并使用已授权的 Root 或 Shizuku 后端。

### 主要更新

- 更新 Material 3 Expressive 首页、权限、后端与连接设置，统一状态 chip、语义颜色、图标和波浪进度。
- 增加手机端控制权审批弹窗，并修复通知正文跳回首页而不显示请求的问题；三个审批操作在窄屏也保持横排。
- 修复连续手工触控时前一手势的异步错误误报到下一次点击的问题。
- 无应用运行时，MCP 观察返回明确错误、可启动应用清单与恢复步骤；区分无障碍服务连接和节点窗口就绪状态。
- 增加浮动控制的结束操作、改进 PiP 状态通知及设置滚动行为。

### 验证范围

- JVM 单元测试、Android Lint 与正式版 APK 构建通过。
- Xiaomi 24129PN74C / Android 17 / HyperOS 的授权探测通过，19/19 MCP 工具；HTTP MCP 无显示路径返回 `NO_RUNNING_APP` 和 66 个可启动应用。
- 真机审批 PendingIntent 打开有效审批弹窗；无障碍树中「拒绝 / 允许一次 / 始终允许」三项同一行，`始终允许` 无换行。
- 本轮完整 Root 截图和输入 instrumentation 未完成：重装后 KernelSU 未向应用授予 Root。Shizuku 未授权，连续真实触控和授权后节点读取仍需在授权后复测。
- Root / Shizuku 行为、后台策略及隧道网络支持会因 Android 版本和厂商 ROM 而异。

## Virtual Screen 0.1.0

First stable release. Requires Android 13+ and an authorized Root or Shizuku backend.

### Highlights

- Refreshes the Material 3 Expressive home, permission, backend, and connection screens with consistent status chips, semantic colors, icons, and wavy progress.
- Adds an on-device control approval dialog and fixes notification taps that returned to home without showing the request. All three approval actions remain on one row on narrow screens.
- Fixes delayed errors from one manual touch gesture being reported against the next tap.
- MCP observations now return an explicit no-app result, launchable app list, and recovery steps. Accessibility connection and target-window readiness are reported separately.
- Adds an End action to floating controls and improves PiP status notifications and settings scroll behavior.

### Validation

- JVM unit tests, Android Lint, and the release APK build pass.
- Xiaomi 24129PN74C / Android 17 / HyperOS: backend probe passed all 19 MCP tools. The real HTTP no-display path returned `NO_RUNNING_APP` and 66 launchable apps.
- A live approval PendingIntent opened the request dialog on the phone. Its accessibility tree placed Deny, Allow once, and Always allow on the same row without wrapping the Chinese label.
- Full Root screenshot and input instrumentation could not run in this pass: reinstalling cleared the previous app grant and KernelSU did not grant Root to the current APK. Shizuku was not authorized. Re-test real repeated touch and authorized node reading after backend authorization.
- Root / Shizuku behavior, background policy, and tunnel support can vary by Android version and vendor ROM.

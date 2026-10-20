## 虚拟屏 0.1.1

支持 Stellar 后端的维护版本，并增加画中画开关、虚拟屏应用重启自动审批和隧道状态跟随。要求 Android 13 或更新版本，并使用已授权的 Root 或 Shizuku / Stellar 后端。

### 主要更新

- **Stellar 后端**：Shizuku 后端现在可以在运行 Stellar 的设备上通过其原生 API 工作，绕开 Stellar 兼容层在 Android 15+ 丢失 UserService 连接回调的问题；授权、探测与显示命令沿用同一 Shizuku 显示服务。
- **画中画开关**：设置中可关闭画中画预览。关闭后退回主屏不再进入悬浮窗，悬浮控制条隐藏「画中画」入口，MCP 权限检查也不再要求画中画权限；「系统画中画设置」跳转保留。
- **自动审批重启应用**：「显示与控制」新增开关（默认关闭）。开启后远程客户端请求重启虚拟屏中运行的应用时直接批准，不再发送手机端审批通知；关闭时维持原有通知审批，最长等待两分钟。
- **隧道状态跟随**：每次开启 MCP 服务时自动恢复上次连接的隧道方式（frp 或 Cloudflare），手动停止或切换隧道方式会清除该状态。

### 验证范围

- JVM 单元测试、Android Lint、调试与正式版 APK 构建通过。
- 模拟器（Android 14, Shizuku 后端）前台验证：画中画开关双向行为（关闭时 HOME 不回悬浮窗、悬浮条隐藏入口；开启后正常入 PiP）；自动审批开启时 MCP `launch_app` 跨屏冲突约 1 秒返回 `restarted=true` 并落回虚拟屏，关闭时阻塞等待手机审批通知且不强杀应用，手机接管后按 `CONTROL_CHANGED` 正确收尾；重启 MCP 服务后 cloudflared 隧道以新进程自动拉起。
- MCP 设备检测 19/19 工具通过。
- Root / Shizuku / Stellar 行为、后台策略及隧道网络支持会因 Android 版本和厂商 ROM 而异；Stellar 路径尚未在真机回归。

## Virtual Screen 0.1.1

A maintenance release that adds Stellar backend support, a picture-in-picture toggle, auto-approval for virtual-display app restarts, and tunnel state restore. Requires Android 13+ and an authorized Root or Shizuku / Stellar backend.

### Highlights

- **Stellar backend**: the Shizuku backend now talks to Stellar's native API on devices running Stellar, working around the compat layer dropping the UserService connected callback on Android 15+. Authorization, probing, and display commands reuse the same Shizuku display service.
- **Picture-in-picture toggle**: PiP preview can be turned off in Settings. When off, leaving to the home screen no longer enters the floating window, the floating controls hide the PiP entry, and MCP permission checks stop requiring the PiP permission; the system PiP settings shortcut remains.
- **Auto-approve app restarts**: a new Display & control switch (off by default). When on, remote client requests to restart an app running on the virtual display are approved immediately without a phone notification; when off, the original notification approval with a two-minute wait applies.
- **Tunnel state restore**: starting the MCP service restores the previously connected tunnel mode (frp or Cloudflare). Manually stopping or switching the tunnel mode clears the state.

### Validation

- JVM unit tests, Android Lint, and debug/release APK builds pass.
- Emulator foreground checks (Android 14, Shizuku backend): PiP toggle verified in both directions (HOME stays on the launcher and the floating control hides the entry when off; PiP enters normally when on). With auto-approve on, an MCP `launch_app` cross-display conflict returned `restarted=true` in about one second with the app relaunched onto the virtual display; with it off, the call blocked on the phone approval notification without killing the app and ended with `CONTROL_CHANGED` after phone takeover. Restarting the MCP service relaunched the cloudflared tunnel automatically.
- MCP device probe passed all 19 tools.
- Root / Shizuku / Stellar behavior, background policy, and tunnel networking vary by Android version and vendor ROM; the Stellar path has not been regression-tested on physical devices.

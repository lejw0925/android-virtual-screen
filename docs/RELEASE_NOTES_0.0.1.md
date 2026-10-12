## 虚拟屏 0.0.1

给 Agent 一块独立的 Android 屏幕，手机主屏继续由你使用。

- Root / Shizuku 虚拟显示、同步画中画、运行通知和人工接管。
- 10 个带认证和独占租约的 MCP 工具，纯节点 / 截图 / 组合观察、唯一选择器和操作回执。
- 新首页、首次使用引导、简中 / 繁中 / 英文、深浅色与动态配色。
- 内置 frpc / cloudflared，提供局域网和隧道连接。

下载 `virtual-screen-0.0.1.apk` 安装；`SHA256SUMS` 用于校验。要求 Android 13+，Root 或已授权 Shizuku。可在首次引导中配置无障碍服务和 MCP。

已完成三轮实机迭代、28 项单元测试、7 项设备检查以及公开 APK 安装与 MCP 验证；Lint 无错误。真实范围、效率数据及限制见 [验证记录](https://github.com/lejw0925/android-virtual-screen/blob/main/docs/RELEASE_VALIDATION.md)。部分 ROM 的画中画/后台策略和应用多屏适配可能不同；本轮 Cloudflare Quick 因手机到 edge 的 7844 端口连接关闭 / 超时未通过；frpc 的 TCP / HTTP / HTTPS 已通过。许可为 PolyForm Noncommercial 1.0.0。

## Virtual Screen 0.0.1

A separate Android display for your agent while your phone remains usable.

Authenticated MCP with exclusive leases, compact tree observations, unique selectors, optional observations after input, and session-owned request receipts. Includes automatic PiP, status notifications, takeover, onboarding, three UI languages, Root / Shizuku, and bundled frpc / cloudflared.

Install `virtual-screen-0.0.1.apk` on Android 13+ with Root or authorized Shizuku. Verify it against `SHA256SUMS`. Three UI iterations, 28 unit tests, seven device checks, and installation / MCP verification of the published APK were completed. See [validation](https://github.com/lejw0925/android-virtual-screen/blob/main/docs/RELEASE_VALIDATION.md) for the precise scope. Cloudflare Quick failed on this phone network because edge connections on port 7844 closed or timed out; frpc TCP / HTTP / HTTPS passed. Licensed under PolyForm Noncommercial 1.0.0.

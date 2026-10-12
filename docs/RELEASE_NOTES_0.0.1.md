## 虚拟屏 0.0.1

给 Agent 一块独立的 Android 屏幕，手机主屏继续由你使用。

- Root / Shizuku 虚拟显示、同步画中画、运行通知和人工接管。
- 10 个带认证和独占租约的 MCP 工具，纯节点 / 截图 / 组合观察、唯一选择器和操作回执。
- 新首页、首次使用引导、简中 / 繁中 / 英文、深浅色与动态配色。
- 内置 frpc / cloudflared，提供局域网和隧道连接。

下载 `virtual-screen-0.0.1.apk` 安装；`SHA256SUMS` 用于校验。要求 Android 13+，Root 或已授权 Shizuku。可在首次引导中配置无障碍服务和 MCP。

真实验证范围、效率数据及限制见仓库 `docs/RELEASE_VALIDATION.md`。部分 ROM 的画中画/后台策略和应用多屏适配可能不同；Cloudflare 需要手机网络能连接其 edge 服务。许可为 PolyForm Noncommercial 1.0.0。

## Virtual Screen 0.0.1

A separate Android display for your agent while your phone remains usable.

Authenticated MCP with exclusive leases, compact tree observations, unique selectors, optional observations after input, and session-owned request receipts. Includes automatic PiP, status notifications, takeover, onboarding, three UI languages, Root / Shizuku, and bundled frpc / cloudflared.

Install `virtual-screen-0.0.1.apk` on Android 13+ with Root or authorized Shizuku. Verify it against `SHA256SUMS`. See `docs/RELEASE_VALIDATION.md` for physical-device results and network / ROM limitations. Licensed under PolyForm Noncommercial 1.0.0.

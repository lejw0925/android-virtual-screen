# Cloudflare 1033 排查与持续验证（2026-10-19）

设备：Xiaomi 24129PN74C，Android 17 / API 37，HyperOS OS4.0.0.19.XOCCNXM，arm64-v8a，USB ADB。安装目标为新签名并行包 `io.github.lejw0925.virtualscreen.localsigned`，本轮没有对原发行包执行卸载或清数据。

## 现场证据

- 手机历史日志在 00:32:00 注册 edge，00:32:01 公网验证通过，00:33:37 出现 `Lost connection with the edge` / `connection with edge closed`，00:33:57 原进程重新注册，00:34:02 验证恢复。
- 浏览器中的 1033 页面时间为 00:33:41（页面显示 UTC 16:33:41），与上述约 25 秒断线窗口一致。相同地址随后从外部主机返回 HTTP 200 和 `X-Virtual-Screen: mcp`。没有观察到固定一分钟到期。
- 后续补查 `dumpsys greezer`，确认上述故障前存在 HyperOS 后台冻结，解冻时应用才读到断线日志；仅凭应用日志不能确定远端真正断开的时刻。系统时间线与新版前后台对照测试见 [后台断连验证](TUNNEL_BACKGROUND_VALIDATION.md)。
- 原实现只验证第一次公网成功，未持续检查；最后一个连接丢失时仍保留可复制地址，`Lost connection with the edge` 首条事件没有立即触发重连状态。

## 修改

- 首次成功后每 20 秒执行一次有界公网 GET，校验手机服务响应标记。失败立即撤下可用地址并退避复查，恢复后显示同一地址。
- 最后一个 edge 断开、默认网络丢失或子进程退出时清除可用状态；旧 epoch / generation 的请求不能发布结果。进程退出同时取消旧的公网检查。
- 保留 cloudflared 原进程重连机制，不以短暂 1033 频繁重建随机域名，不重放任何 MCP 设备操作。
- 设备用例延长到后台 200 秒；修正旧公网测试把现有 19 个工具写成 20 个的断言，并在设备用例运行期间保留前台服务。

## 验证结果

- `:app:testDebugUnitTest :app:lintDebug`：95 项测试，0 失败 / 错误 / 跳过；Lint 0 错误、63 条既有警告。
- `DeviceDnsTest,DeviceTunnelTest`：`OK (2 tests)`，255.136 秒。UDP / TCP DNS、真实公网 MCP 初始化、错误 Token 的 401、19 个工具发现、`get_status`、DELETE，以及后台每 20 秒连续 10 次 HTTP 200 全部通过；地址不变，结束后子进程清理。
- 原包名和并行包 Release 均构建成功，APK v2 签名校验通过，证书 SHA-256 为 `ad6ae346550453ea104d4ffd76e00a84dea9b482fbb3efb7b6a21b452fc2bab3`。并行包已覆盖更新，临时 instrumentation 包已卸载。
- 最终签名并行包启动后界面显示「已连接」，外部主机访问当前域名返回 HTTP 200 / `X-Virtual-Screen: mcp`；00:58 之后 crash buffer 为空。
- 本轮未修改 VPN、系统代理、公共 DNS 或 Cloudflare 账户配置；未据此宣称任意网络、长时间熄屏 / Doze 均不会断线。

构建与真机完整日志保存在 `build/validation/2026-10-19/tunnel/`。

# 公网隧道验证（2026-10-13）

设备：vivo V2419A，Android 16 / API 36，arm64-v8a；已配对无线 ADB，Root 已授权。主应用使用发行签名覆盖安装，保留原有设置与凭据。

## 真机测试

执行：

```sh
adb -s IP:PORT shell am instrument -w \
  -e class io.github.lejw0925.virtualscreen.DeviceDnsTest,io.github.lejw0925.virtualscreen.DeviceTunnelTest \
  io.github.lejw0925.virtualscreen.test/androidx.test.runner.AndroidJUnitRunner
```

结果：`OK (2 tests)`，共 41.959 秒，无跳过。

| 测试 | 实际结果 |
| --- | --- |
| `DeviceDnsTest` | 同一回环端口上的 UDP / TCP 均返回 Cloudflare edge SRV 记录 |
| `DeviceTunnelTest` | 手机取得真实随机公网地址；完成 MCP 初始化、协议与会话标头检查、错误 Token 的 401 拒绝、11 个工具发现与必填 purpose 检查、`service_status` 调用、DELETE 会话关闭及 cloudflared 进程清理 |

首轮公网测试因 `api.trycloudflare.com` 连续超时失败。复测中仍有一次 API 超时，自动重连后通过；此结果验证当前网络下的连通和重试，不代表长期稳定性。测试恢复了原有加密隧道配置；结束后另行检查没有残留 cloudflared 子进程。

## 页面与构建

- 真机检查浅色、跟随系统深色下的随机地址页，固定域名 Token / 浏览器授权切换、账号配置弹窗及 frpc 表单。
- 实际开启 MCP、连接随机隧道、观察已连接状态和公网地址入口、停止隧道并检查状态及进程清理。检查后恢复原来的「跟随系统」主题。
- 状态与主操作集中在连接卡片；连接配置按方式展开，Cloudflare 管理与高级 frpc 配置放入弹窗，诊断日志单独打开。
- `:app:assembleRelease :app:lintDebug` 通过；Lint 0 错误、55 警告。`TunnelConfigurationTest` 的 4 项单元测试通过，无跳过。

固定域名账号授权、创建隧道和公共 DNS 修改未在此次测试中执行；本次真机自动测试覆盖 DNS 桥接与随机公网隧道。

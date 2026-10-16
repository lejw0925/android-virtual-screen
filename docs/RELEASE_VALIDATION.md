# 0.0.1 实机迭代与发行验证

2026-10-16 首页更新见 [MCP 首页与动效验证](MCP_HOME_VALIDATION.md)。

设备：vivo V2419A，Android 16 / API 36，arm64-v8a；Root 与已授权 Shizuku Plus；日期：2026-10-13。自动输入仅操作本项目的 disposable fixture，主屏界面检查使用本应用。

## 三轮迭代

| 轮次 | 检查及发现 | 修正与复核 |
| --- | --- | --- |
| 1 | 首次引导、权限页、后台创建显示、MCP schema 与节点缓存。系统上下文启动 PiP 被拒绝；新窗口的节点暂不可用；引导主次按钮相近。 | 使用真实 Root / shell Binder 身份启动固定预览；条件等待重试观察；同一观察缓存同一节点树；主按钮强调色、次要按钮透明。 |
| 2 | 自动 PiP 与主屏独立操作；三种观察、唯一选择器、Unicode、条件等待。fixture 按钮文字由系统转换为大写，大小写敏感等待超时。 | 实际 pinned 预览出现，主屏 Settings 保持前台；文本等待默认忽略大小写；选择器仍精确匹配；中文、繁体、emoji、清空均确认效果。 |
| 3 | 设置 10 页、三种语言、深色 / 动态色、1.3 倍字体、横屏、接管与停止、完整回归。大字权限状态挤在一起；旧 PiP 与全屏预览争用 Surface。 | 调整状态列权重 / 间距；首页可滚动；重开指南不嵌套滚动；全屏预览关闭独立 PiP；实机触摸、租约交还、旧观察拒绝复核通过。 |

三轮内持续修正，不以主观“完美”代替验证。首次引导覆盖介绍、Root / Shizuku / 无障碍授权、开启 MCP、复制配置和完成设置；可在设置重新打开。正常页面显示操作和本地化状态，诊断日志改为主动复制，原生构建 JSON 保留在资产而非界面。

## 效率对照

同一静态 fixture、720×1476 显示、USB ADB 转发，每种方式各采样 10 次。字节数为 MCP 工具结果 UTF-8 JSON（包括 Base64 图像），并非实际网络包总量。数据保留在 [基线](benchmarks/0.0.1-baseline.json) 与 [最终采样](benchmarks/0.0.1-enhanced.json)。

| 观察方式 | 中位响应 | 平均结果字节 | 返回节点 |
| --- | ---: | ---: | ---: |
| 优化前截图 + 节点 | 151.73 ms | 108,165 | 88 |
| 新 both / full | 205.57 ms | 145,361 | 88 |
| 新 both / compact | 101.34 ms | 83,429 | 17 |
| 新 tree / compact | 46.08 ms | 16,397 | 17 |

纯节点路径在此场景响应中位值降低 69.6%，结果大小降低 84.8%；完整模式增加状态 / 层级信息，体积与耗时反而提高。精简节点不是无损全树；视觉任务仍需图像。没有据此承诺所有设备 / 应用的性能或成功率，也未统计代表性真实业务的 p95。

## 工作流与自动检查

- 28 项 JVM 测试通过；Lint 0 错误、47 警告，主要是特权显示使用的非 SDK 接口与既有 KTX / 版本建议。
- 真实 HTTP 验证认证拒绝、10 个工具、必填 purpose、两客户端租约竞争、重复请求拒绝、旧观察拒绝、输入、Unicode、滚动、取消、释放和 DELETE。
- 增强工作流确认唯一 / 歧义选择器、按键及长按限制、动作后新观察、文字清空、节点等待、图像坐标缩放、UI 稳定、应用过滤、回执恢复及跨会话隔离。
- 基线曾出现输入已执行但无障碍缓存尚未更新。使用条件等待读取效果，不重放输入。显示重建后的瞬时节点 bounds 也需要等待布局稳定，始终保留坐标范围与实时目标签名检查。
- 本地原生触摸接管后旧租约失效，手势结束可重新申请远程租约；旧观察继续拒绝。全屏预览不再留下黑色 PiP。
- Release 已确认画中画权限拒绝时报告 unavailable 并保持手机设置前台；恢复允许权限并重建显示后自动预览恢复。执行中通知实时显示 MCP purpose。

## 正式签名包

本地签名 0.0.1 已保留数据覆盖安装；未卸载或清除用户数据。APK v3 / RSA-4096 签名校验通过，Release 不含 Debug 接收器或 debuggable 标记，六个原生隧道文件与来源 SHA-256 一致。

签名 Release 实际通过 Root / Shizuku 显示、截图、输入、Unicode、接管、Root 预览 / 创建取消、普通 App UID 原生执行和 UDP / TCP DNS 共 6 项 instrumentation 检查。普通进程的节点行为另由 MCP 工作流验证，以避免 instrumentation 对无障碍绑定的 ROM 差异。签名 Release 的 frpc TCP / HTTP / HTTPS 检查也通过（1 项测试覆盖三种协议）；最新 Release 的传统与增强 MCP 脚本通过，并确认原 Token 保留、自动 pinned 预览和独立主屏界面。

发行证书与 Actions 配置见 [构建文档](BUILD.md)。[云端 main 检查](https://github.com/lejw0925/android-virtual-screen/actions/runs/38292476123) 已通过：Linux / JDK 25 执行 JVM、Lint、Debug / unsigned Release、fixture 和 instrumentation 构建，实际 SDK 包名为 `platforms;android-37.0`。[v0.0.1 自动签名与发布](https://github.com/lejw0925/android-virtual-screen/actions/runs/38293642656) 已通过，公开提供 APK 和 SHA256SUMS。

## 隧道与环境限制

frpc 的 TCP / HTTP / HTTPS 在临时 frps 上验证了初始化、错误认证、工具发现、状态查询与关闭；HTTPS 使用临时证书，测试恢复原加密配置并删除证书。Release 的测试 HTTP 客户端需在 instrumentation 进程内临时允许访问这个私有测试服务，完成后恢复；正式 Manifest 保持默认网络策略。

Cloudflare Quick 本轮未取得可用公网连接：手机到 edge 的 TCP 7844 出现连接关闭 / 超时，90 秒设备测试失败；DNS SRV 的 UDP / TCP 查询通过。2026-10-12 的历史网络曾成功，不能替代本轮结果。没有修改用户 VPN 或网络配置；本机、LAN 和 frpc 不依赖 Cloudflare edge。

固定域名 / 官方授权需要实际账号与站点，本轮未登录或创建 DNS。其他 ROM、Android 13–15、普通 shell UID Shizuku、另外两种 ABI 运行及长时间熄屏 / 后台矩阵仍需验证。本项目使用 PolyForm Noncommercial，不能当作无限制商业许可。

## 公开资产验收

从 [0.0.1 Release](https://github.com/lejw0925/android-virtual-screen/releases/tag/v0.0.1) 下载实际 Actions 资产，APK 大小 80,584,945 字节，SHA-256：

```text
fadb1f3683771fbe4d1b69811f541892cc0d41ba78bdaff2f8980feb93c125bf
```

下载值与 SHA256SUMS 及 GitHub asset digest 一致；发行证书、非 debuggable Manifest、无 Debug 接收器和六个原生文件哈希再次核验。实际公开 APK 保留数据覆盖安装后，再次通过 6 项核心设备检查、增强工作流、自动 PiP、传统 MCP 和真实全屏触摸接管 / 交还 / 旧观察拒绝，旧 Token 仍有效。

触摸接管检查的一次失败来自验证辅助流程：仅发送启动 Intent 时，当前设置页仍在同一任务顶部，辅助脚本把预览坐标点到了设置页。先确认主屏显示全屏预览，再执行真实触摸，完整验证通过。PiP 本身为只读；返回应用全屏预览或通知接管后操作，不把虚拟屏坐标直接用于手机设置界面。

检查完成后停止 MCP、关闭测试显示，恢复字体 / 旋转和应用语言 / 主题设置，删除临时测试证书。保留公开正式版本供使用。

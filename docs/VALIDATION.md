# 验证记录（优化前基线）

本页保留 2026-10-12 的历史结果，当前 0.0.1 结果以 [发行验证](RELEASE_VALIDATION.md) 为准。

验证日期：2026-10-12。设备：vivo V2419A、Android 16、arm64-v8a；通过已配对的无线 ADB 连接。Root 已授权，Shizuku 测试使用这台设备上的 Shizuku Plus 授权服务。所有设备输入均使用本项目的独立测试应用 `io.github.lejw0925.virtualscreen.fixture`。

## 已完成

| 范围 | 实际验证 |
| --- | --- |
| 构建 | 22 项单元测试全部通过；Android Lint 无错误，40 项警告；Debug、Release 和 instrumentation APK 构建通过 |
| Root / Shizuku | 创建和关闭显示、启动测试应用、截图、点击、中文/繁体输入、滑动、接管后旧租约拒绝；Root 预览取消与创建取消的清理回归 |
| 节点 | 普通 App 进程中，Root / Shizuku 分别取得 88 个测试节点；确认节点点击计数和中文字写入效果，并接受节点滚动 |
| 本地预览 | Surface 镜像真实显示；保留手机状态栏；可见区域 1216×2493，虚拟屏及截图 720×1476；白色测试界面到达左右边缘 |
| 悬浮栏 | 空闲主界面不显示；应用运行时显示状态指示器；应用、输入、画中画、设置位于同一行；默认折叠，贴边/闲置自动隐藏；展开 160 ms、折叠 140 ms，阴影跟随同一卡片尺寸 |
| 设置 | miuix 分类卡片、圆形返回按钮与内容左边缘对齐、页面进入/返回动画；简中、繁中、英文有独立界面和工具说明 |
| 画中画 | 系统进入 pinned 模式；测试内容正常显示；进入前后显示 ID 与 720×1476 帧尺寸保持一致 |
| MCP | 真实 HTTP 初始化、10 个工具发现、必填 purpose、错误 Token、两客户端租约竞争、截图 image content、坐标输入、重复请求 ID 拒绝、旧观察拒绝、节点聚焦、中文输入、滚动、取消、释放租约和 DELETE 会话 |
| 本地/远程交接 | MCP 开启后无需额外开关；手机直接触摸使远程旧租约/观察失效；手势处理完成后可重新申请远程租约 |
| 原生程序 | arm64 frpc 0.65.0 与 cloudflared 2025.8.1 以普通 App UID 执行；三 ABI 已构建并校验 ELF 与来源哈希 |
| Android DNS | 同一回环端口上的 UDP / TCP DNS 均取得 Cloudflare edge SRV 记录 |
| frpc | 手机连接临时验证 frps，TCP / HTTP / HTTPS 分别完成初始化、认证拒绝、工具发现、service_status 调用与会话关闭；HTTPS 使用临时测试证书 |
| Cloudflare Quick | 手机实际取得随机公网地址，公网初始化、认证拒绝、工具发现、service_status 调用和关闭成功；停止后未发现残留 cloudflared 子进程 |
| 配置保存 | 隧道模式切换保留另一模式的加密配置；验证测试会恢复原有配置 |

单元测试覆盖租约过期、显示重建与旧观察、取消、重复请求、Token 轮换、端口冲突、参数校验、三种工具语言与虚拟屏尺寸。模拟后端测试不能替代设备能力测试。

Root 的预览取消回归会取消六次正在执行的观察，并在每次取消后验证显示存活和下一张帧的尺寸。传输会排空已经发送请求的对应回复，保持顺序；不会自动重发输入。取消显示创建后，后端会清理已启动的权限进程，并允许创建新显示。

设备截图：[悬浮控制栏](images/floating-controls.png)、[显示设置](images/display-settings.png)、[画中画内容](images/picture-in-picture.png)。截图使用测试内容。

## 重跑

先按 [BUILD.md](BUILD.md) 安装主应用、测试应用与 instrumentation APK，并完成 Root / Shizuku / 无障碍授权。

```sh
adb -s IP:PORT shell am instrument -w \
  -e class io.github.lejw0925.virtualscreen.DeviceBackendTest \
  io.github.lejw0925.virtualscreen.test/androidx.test.runner.AndroidJUnitRunner

adb -s IP:PORT shell am instrument -w \
  -e class io.github.lejw0925.virtualscreen.DeviceDnsTest,io.github.lejw0925.virtualscreen.DeviceTunnelTest \
  io.github.lejw0925.virtualscreen.test/androidx.test.runner.AndroidJUnitRunner
```

`DeviceBackendTest` 中 Shizuku 未授权时会跳过相应测试；当前设备的实际运行没有跳过。个别 ROM 在 instrumentation 结束后需要重新绑定本 App 的无障碍服务，普通 App 进程的节点检查通过 Debug 专属接收器执行。

MCP 的完整设备测试使用 `scripts/verify-mcp.py`。先在虚拟屏启动测试应用并开启 MCP，将电脑端口转发到手机 MCP 端口；Token 放入本机权限为 600 的临时文件，该脚本不打印 Token。

```sh
adb -s IP:PORT forward tcp:18732 tcp:8732
python3 scripts/verify-mcp.py --token-file /private/path/to/token.txt
```

传入 `--manual-marker /private/path/to/marker.json` 可增加手机触摸接管验证。脚本写出测试按钮的虚拟屏坐标；先确认手机显示本应用的全屏预览，按其 viewport 换算为手机触摸坐标，在测试按钮点击一次后创建同名 `.done` 文件。只读 PiP 与设置页不能直接使用这些坐标。脚本会检查计数以及旧租约、旧观察的拒绝。

frpc 测试需要测试主机上的 frps：监听 17000，HTTP 18780，HTTPS 18443，并允许 TCP 18732。为 `screen.example.test` 生成临时证书，将 PEM 证书和私钥放入目标 App 外部私有目录的 `frp-test-cert.pem`、`frp-test-key.pem`。测试在设备内将该验证域名解析到测试主机，不修改公共 DNS。

```sh
adb -s IP:PORT shell am instrument -w -e frps_host TEST_HOST_IP \
  -e class io.github.lejw0925.virtualscreen.DeviceFrpcTest \
  io.github.lejw0925.virtualscreen.test/androidx.test.runner.AndroidJUnitRunner
```

## 尚需特定环境验证

- Cloudflare 固定域名 Token 和官方浏览器授权到证书、隧道创建、DNS 路由及公网调用：需要实际账号、受授权站点和凭据。本次没有替用户登录或创建 DNS 记录。
- 普通 Shizuku 的 shell UID 模式，以及其他 ROM、Android 13–15、armeabi-v7a / x86_64 安装运行。当前 Shizuku Plus 环境不能代表所有权限实现。
- 熄屏、锁屏、长时间后台、强制断网重连、真实权限撤销及后端异常死亡的完整设备矩阵；状态检测和错误处理已实现，本次未覆盖所有设备差异。
- 此历史基线使用 unsigned Release；0.0.1 的正式签名与安装结果见发行验证。ETA 派生代码保留 PolyForm Noncommercial 许可，详见 [来源与许可](../THIRD_PARTY_NOTICES.md)。

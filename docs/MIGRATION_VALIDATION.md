# Material 3 与运行时迁移验证

日期：2026-10-16。验证当前工作区，应用版本仍为 `0.0.1-debug`；这些改动尚未发布到 GitHub Releases。所有自动设备输入仅使用 disposable fixture。

## 构建与 JVM

JDK 25、Gradle 9.7.1、AGP 9.4.1、SDK 37。Debug、unsigned Release、fixture 和 instrumentation APK 均构建成功。65 项 JVM 测试通过，无失败、错误或跳过；Lint 0 错误、60 警告。警告主要来自已有特权非 SDK 接口、KTX 与依赖版本建议。

新增回归覆盖拆分工具的参数隔离、禁止对不存在显示执行配置、Cloudflare 瞬时错误和多个 edge 连接、创建响应超时后的凭据和账号核对、取消清理、日志持久化/轮转/脱敏、公网说明页及未授权连接 IP 记录。原有的租约、观察、审批过期与重放、并发、取消、认证和一分钟以上审批请求仍在全量测试中执行。

## vivo 实体机

vivo V2419A，Android 16 / API 36，arm64-v8a，USB ADB；Root 与 Shizuku Plus 已授权。保留用户数据覆盖安装。

```sh
adb -s DEVICE shell am instrument -w -e require_shizuku true \
  -e class io.github.lejw0925.virtualscreen.DeviceBackendTest,io.github.lejw0925.virtualscreen.DeviceDnsTest,io.github.lejw0925.virtualscreen.DeviceTunnelTest \
  io.github.lejw0925.virtualscreen.test/androidx.test.runner.AndroidJUnitRunner
```

8 项通过，无跳过：Root 和 Shizuku 显示/截图/输入/Unicode/接管、Root 取消清理、Shizuku 连续三次显示重建及旧观察拒绝、普通 App UID 原生可执行程序、UDP/TCP DNS、真实 Cloudflare Quick 公网访问。

公网验证先确认连接说明页返回本机服务标记，再执行初始化、错误 Token 401、20 个工具发现、必填 purpose、`get_status` 和 DELETE。初次测试曾复现 edge 注册后 DNS 尚不可解析；修正后等待“正在验证公网连接”，公网测试通过。日志中的可选 ICMP 和证书查找告警不再显示为连接终止。

在电脑另行访问真实公网地址完成说明页、认证 MCP 与 20 个工具发现，并确认本地日志保存代理报告 IP、实际回环连接 IP、时间、客户端及操作结果。代理报告地址单独标记，不用于认证。

Root 和 Shizuku 的普通 MCP 工作流通过；Root 增强工作流通过，覆盖唯一/歧义选择器、动作后观察、Unicode/清空、节点等待、坐标缩放、稳定等待、应用过滤及回执恢复/跨会话隔离。增强观察每种模式仅采样 3 次，用于回归，不作为普遍性能结论。

## Android 模拟器与普通 Shizuku

新建专用 `AVS_Shizuku_Test` AVD，Android 14 / API 34 Google APIs，arm64-v8a；官方 Shizuku v13.6.0，服务以 UID 2000（shell）运行，不使用 Root 启动。

```sh
adb -s emulator-5556 shell am instrument -w -e require_shizuku true \
  -e class 'io.github.lejw0925.virtualscreen.DeviceBackendTest#shizukuSurfaceScreenshotAndInput,io.github.lejw0925.virtualscreen.DeviceBackendTest#shizukuReconfigurationKeepsTheNewBindingAndInvalidatesOldObservations,io.github.lejw0925.virtualscreen.DeviceBackendTest#bundledExecutablesRunAsOrdinaryAppUid,io.github.lejw0925.virtualscreen.DeviceDnsTest' \
  io.github.lejw0925.virtualscreen.test/androidx.test.runner.AndroidJUnitRunner
```

4 项通过，无跳过。缺少 Shizuku 授权时这些检查必须失败。真实测试发现并修正 shell UID 的包名校验失败及旧 UserService 异步解绑干扰新绑定的问题。普通及增强 MCP HTTP 工作流均通过，包含截图、真实触摸、Unicode、滚动、取消、回执及三种观察路径；工作流之间仅重置测试夹具以恢复其初始滚动位置。

## 界面与功耗

- vivo 深色首页的 MCP 文字、浅色繁体首页、设置、已连接隧道、实时日志和运行日志均实拍检查。
- 模拟器保存 16 张矩阵截图：11 个设置/引导页面，简中/繁中/英文，Dark/Light/System/动态色，1.3 倍字体和横屏；未见文字裁切或横向溢出。另检查最终 APK 的大字体横屏权限弹窗，从标题滚动到“重新检查并开启”和“稍后”均可访问。
- 运行日志真实显示连接时间、操作、结果、耗时、客户端和 IP。私有磁盘轮转、重启恢复、清空及脱敏由 JVM 测试验证。
- 通知 smallIcon 实际资源与新的重叠屏幕/加号单色图标一致。
- vivo MCP 开启、无显示、应用退到后台的约 31 秒采样：进程保持存活，7 次采样没有本应用活动唤醒锁，累计 CPU ticks 增加 20。另确认设备操作期间获取 CPU 锁，操作结束后释放。检查只读取 `dumpsys power` 当前 Wake Locks 区域，排除历史 ACQ/REL 事件。该短测不等同于长期电池续航测量。

截图、原始观察样本、功耗 JSON 和本轮命令结果保存于本地 `build/validation/2026-10-16/`。该目录被 Git 忽略。

## 产物与范围

`dist/virtual-screen-0.0.1-migration-debug.apk` 为已在两台设备覆盖安装的构建；另提供 unsigned Release 和 `dist/MIGRATION_SHA256SUMS`。Debug 和 Release 内六个原生隧道文件均与源码目录的 SHA-256 一致，未修改或剥离。

测试结束关闭测试 MCP、隧道和显示，恢复 vivo 原来的语言/主题/后端及关闭状态，保留凭据与数据，五份原设置/认证偏好与测试前逐项一致；恢复模拟器字体、旋转、通知和 PiP 权限。没有替用户登录 Cloudflare、创建真实固定隧道或修改公共 DNS。固定域名的响应丢失恢复有命令层回归，未据此声称真实账号端到端验收。Android 13、15、其他 ROM、另外两种 ABI 和长时间锁屏/Doze 电量矩阵不在本轮范围内。

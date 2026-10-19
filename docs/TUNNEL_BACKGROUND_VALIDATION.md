# Cloudflare 后台断连排查（2026-10-19）

设备：Xiaomi 24129PN74C，Android 17 / API 37，HyperOS OS4.0.0.19.XOCCNXM，USB ADB。应用包名 `io.github.lejw0925.virtualscreen.localsigned`，UID 10347。

## 结论

旧版两次断线均伴随 HyperOS Greeze 的后台冻结与解冻，已找到比单独 cloudflared 断线日志更直接的系统证据。普通切后台不等于应用主动停止隧道：`MainActivity.onStop()` 只结束手机预览会话，隧道由应用级协程和前台服务持有。

系统冻结会暂停应用执行；因此日志中的 edge 断开时间可能是解冻后才读到输出的时间，不能把它当成远端真正断开的精确时刻。现有证据支持后台冻结是这两次断连的重要原因，尚未证明新版已绕过 HyperOS 的所有冻结策略。

## 原故障时间线

时间均为北京时间 2026-10-19。`dumpsys greezer` 中 `LM FZ [发起方 UID] uid = [被冻结 UID]` 需区分发起方与目标。

| 时间 | 证据 |
| --- | --- |
| 00:29:26.549 | `LM FZ [10337] uid = [... 10347]`；`ExternalInterface: setUidState, uid = 10347 allow = false` |
| 00:31:04.559 | `THAW uid = 10347 ... reason : activity_front`，随后应用读到 edge 断开日志 |
| 00:32:16.285 | `SettingsActivity` 的 `wm_on_stop_called` |
| 00:32:16.609 | `LM FZ [10337] uid = [10347]`，`setUidState ... allow = false` |
| 00:33:37.227–235 | frozen binder 事务触发 `THAW uid = 10347 ... reason : bf`；`setUidState ... allow = true` |
| 00:33:37.234–244 | 应用读到 `Lost connection with the edge` / `connection with edge closed` |
| 00:33:57.640 | 原 cloudflared 进程重新注册连接 |
| 00:34:02.963 | 公网验证恢复 |

两段冻结分别约 98 秒和 81 秒；系统累计记录 `frozen=178s`，与两段相符。发起方 UID 10337 为 `com.moonshot.kimichat`，这是系统的应用切换冻结策略，不能据此归咎于该第三方应用主动断网。未发现上述窗口中应用进程被杀或前台服务被停止。

## 当前签名版实测

使用已安装的最终签名版，不重建、不改电池/网络/冻结配置。通过电脑访问同一公网地址，校验 HTTP 200 和 `X-Virtual-Screen: mcp`，并检查 cloudflared `ha_connections=1`。

| 场景 | 时间 | 结果 |
| --- | --- | --- |
| 应用界面前台 | 01:17:41–01:18:41，60 秒 | 6 / 6 次成功 |
| HOME 返回桌面 | 01:18:41–01:20:41，120 秒 | 12 / 12 次成功；UID 状态为 `FOREGROUND_SERVICE` |
| 熄屏并锁屏 | 01:20:41–01:23:41，180 秒 | 18 / 18 次成功 |

三个场景应用 PID 16670、隧道 PID 2391、域名均不变，前台服务保持运行，无新增 edge 断开或冻结记录。测试时手机正在充电，`mDeviceIdleMode=false`；这不覆盖断电深度 Doze。电脑每 10 秒检查一次公网，应用自身按既有逻辑每 20 秒检查一次，不能据此证明完全无流量时的表现。

熄屏后设备需要用户密码或指纹解锁；首次“切到另一应用且电脑 100 秒不轮询”的尝试被锁屏挡住，已停止，没有把该尝试算作通过。未读取或更改锁屏凭据。用户解锁后的补测见下文。

## 解锁后补测与省电豁免

06:05:15 确认 `com.moonshot.kimichat/.MainActivity` 位于前台，虚拟屏处于后台。电脑在接下来的 180 秒不访问公网地址或隧道 metrics，只通过 root 读取 cgroup 与 `/proc` 状态；应用自身仍保留每 20 秒公网检查。19 次 cgroup 采样均为 `frozen 0`，180 秒后公网请求返回 HTTP 200 / `X-Virtual-Screen: mcp`，`ha_connections=1`。随后继续访问与切换系统设置时连接仍正常。

在这一轮结束、切换界面的 06:09:18.069，系统历史又记录了一次目标 UID 10347 的 `LM FZ`，06:09:18.914 因 `activity_start` 解冻，持续约 0.84 秒，累计冻结从 178 秒变为 179 秒；没有出现对应的公网断连。这个短暂冻结说明当前版本仍可能进入厂商冻结路径，不能把前台服务或周期检查当作冻结豁免。

### 从本机系统代码确认的路径

只读提取本机 `/system_ext/framework/miui-services.jar`，SHA-256 为 `feb5dc6fabc44ac9955728db2d80095cbeca45e025b602a55450820edc41efa0`，检查 `com.miui.server.greeze.AurogonImmobulusMode`：

- `getNoRestrictApps()` 从 `Settings.System` 的应用名单填充 `mNoRestrictAppSet`。
- `triggerLaunchModeAction()` 对名单中的包直接跳过，日志文本为 `is mNoRestrictAppSet list app, skip check!`；这正是旧故障 `LM FZ` 所属的启动应用冻结路径。
- 快速冻结和 Immobulus 的相关路径也检查这一名单。因此首选应用级「无限制」配置，不需要关闭全局 Greeze 或修改系统文件。
- 夜间深度空闲另有策略，不能据此承诺所有 ROM、断电深度 Doze、系统强杀均无中断。

### 已应用的手机配置

通过系统界面进入「应用信息 → 省电策略」，将新签名虚拟屏改为「无限制」。06:10:13.008，PowerKeeper 中该应用的 `bgControl` 从 `miuiAuto` 变为 `noRestrict`；06:10:13.019，系统立即记录：

```text
getNoRestrictApps result=io.github.lejw0925.virtualscreen.localsigned
mNoRestrictAppSet=[io.github.lejw0925.virtualscreen.localsigned]
```

`Settings.System.MILLET_NO_RESTRICT_APP` 同时包含该包名。截图和只包含目标应用的配置前后记录保存在验证目录。没有直接写数据库、修改全局冻结开关或变更其他应用的省电策略。产品源码和已安装 APK 本轮未改变；原有前台服务、断线后自动重连与持续健康检查保留。

### 配置后的验证结果

06:11:42–06:16:09，在同一个已安装签名包和同一个隧道进程上完成：

- 两轮从虚拟屏切到测试应用的冷启动，均确认测试应用实际位于前台。
- 第二轮后从 06:12:23 起，电脑连续 180 秒不请求公网或 metrics。这里只通过 root 读取系统 cgroup；应用自身的周期检查照常保留。
- 35 次 cgroup 采样全部为 `frozen 0`；系统历史无新的目标 UID 冻结，累计冻结保持 179 秒。系统记录该应用豁免原因为 `accessibility|NO_RESTRICT_APP`。
- 静置后以及继续后台检查、恢复前台，6 / 6 次公网请求均为 HTTP 200 / `X-Virtual-Screen: mcp`，`ha_connections=1`。应用 PID 16670、隧道 PID 2391 和域名不变，前台服务一直运行。
- 本轮 crash buffer 为空。临时测试应用在验证后卸载，metrics ADB 转发已移除。

此结果确认配置已进入本机厂商冻结策略的豁免路径，且上述场景未再冻结或断连；改配置前的三分钟测试本身也未出现 1033，不能把这次验证解释为每次切后台都会断线、或已证明任意时长与所有电源状态都不会断线。充电、亮屏、无强制深度 Doze 的测试条件仍需保留。

补测原始记录为 `app-switch-060509/` 与 `unrestricted-061142/`，另有 `battery-policy-change.json`、`unrestricted-applied.log`、`unrestricted-settings.png` 与 `miui-freezer-methods.txt`。

### 后续处理选择

- 同类手机部署时，在应用「后台运行」页面打开「应用后台权限」，按系统入口为虚拟屏设置「无限制」，并保留已有的 Android 电池优化豁免。系统自启动权限用于需要进程被系统回收后的启动场景，不能替代运行时的冻结豁免。
- App 可以提示用户完成后台配置；应用内的健康检查只能发现断线并在恢复执行后重连，无法在整个 UID 已被冻结时自行解冻。
- 本机已持有 Root，但无需把隧道提升为 Root 进程，或以高频轮询、虚假音频播放等方式持续争取系统豁免。

完整原始记录位于 `build/validation/2026-10-19/tunnel-background/`，关键证据为 `freezer-final.txt`、`old-freezer-events.log`、`activity-events.log`、`system-logcat.log`、`samples.jsonl`。

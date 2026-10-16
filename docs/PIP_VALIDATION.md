# MCP 画中画全屏恢复回归

验证日期：2026-10-16。设备：vivo V2419A，Android 16，USB ADB；后端：已授权 Shizuku Plus。验证仅操作独立测试应用 `io.github.lejw0925.virtualscreen.fixture`。

## 问题与修复

MCP 创建的虚拟屏点击画中画菜单中的全屏按钮后，PiP 消失，预览任务停在不可见的全屏状态，虚拟显示本身仍在运行。

设备日志记录了 `PipTaskOrganizer.exitLaunchIntoPipTask`。之前使用的 `ActivityOptions.makeLaunchIntoPip` 让系统在展开时启动宿主任务再移除 PiP；权限进程没有 Activity 宿主，无法恢复预览。这个行为可在 [AOSP PipTaskOrganizer](https://android.googlesource.com/platform/frameworks/base/+/26e492cc4b1ac3dd9fcea6e51c78373864fe2cb7/libs/WindowManager/Shell/src/com/android/wm/shell/pip/PipTaskOrganizer.java) 的 `exitLaunchIntoPipTask` 中核对。

修复后，权限进程启动普通预览任务，由 Viewer 在首次绘制前进入普通 PiP。进入前保留透明窗口处理；PiP 模式变化时恢复窗口可见性。`pip` 启动参数只消费一次，Activity 重建不会再次隐藏全屏界面或自动返回 PiP。

## 已通过

`DevicePreviewTest` 经真实 HTTP MCP 初始化和工具调用创建虚拟屏、启动测试应用，实际点击系统 PiP 的 `expand_button`，连续验证两轮：

- 预览恢复为可见的全屏窗口，显示 ID 和会话 ID 不变。
- 全屏预览持有手机租约，旧远程租约返回 `LEASE_REQUIRED`。
- 全屏 Activity 重建后仍可见，并保持全屏。
- Home 返回画中画后释放手机租约。
- MCP 重新申请控制、观察及点击均成功。
- 主动释放控制时远程小窗收起，保留显示；再次申请控制可重新显示小窗。

该类还包含完整的五分钟闲置回归：保持预览刷新，客户端不释放控制、不发送请求，等待租约到期并检查画中画消失、虚拟显示保留、旧租约拒绝和重新申请后的预览恢复。MCP 传输会话也在闲置五分钟后到期，恢复前先重新初始化连接。该测试会临时保持屏幕唤醒，并在结束时释放测试用唤醒锁。

租约到期由前台服务按最后一次有效操作的截止时间主动发布；预览刷新和状态查询不会续租。远程画中画监听租约释放并关闭，用户全屏接管后的本地预览保持手机控制逻辑。新租约在同一显示会话中也能再次触发预览。

截图：[系统全屏按钮展开后的预览](images/pip-expand-fullscreen.png)。

Debug APK 已覆盖安装到上述设备。2026-10-16 闲置收起回归完成：78 项 JVM 单元测试通过；包含实际五分钟等待的两项真机测试通过，总耗时 314.838 秒；Android Lint、Debug 和 instrumentation APK 构建通过。Root 未在本轮单独运行；Root 与 Shizuku 共用的启动代码已修复，其他 ROM 尚未验证。

## 重跑

先安装主应用、fixture 和 instrumentation APK，授权 Shizuku，保持手机解锁，并确保没有需要保留的虚拟显示。测试会创建和关闭自己的显示，切换手机前台任务。

```sh
./gradlew :app:assembleDebug :device-fixture:assembleDebug :app:assembleDebugAndroidTest
adb -s SERIAL install -r app/build/outputs/apk/debug/app-debug.apk
adb -s SERIAL install -r device-fixture/build/outputs/apk/debug/device-fixture-debug.apk
adb -s SERIAL install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb -s SERIAL shell am instrument -w -e require_shizuku true \
  -e class io.github.lejw0925.virtualscreen.DevicePreviewTest \
  io.github.lejw0925.virtualscreen.test/androidx.test.runner.AndroidJUnitRunner
```

整类运行包含一次实际五分钟等待。测试截图保存在主应用外部私有目录的 `preview-pip-*.png` 和 `preview-fullscreen-*.png`。测试结束后保持 MCP 配置，关闭测试显示。

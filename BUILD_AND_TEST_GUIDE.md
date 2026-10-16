# 构建与测试入口

项目统一使用 JDK 25、Android SDK 37，设备最低 Android 13。

```sh
./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleRelease :device-fixture:assembleDebug :app:assembleDebugAndroidTest
```

环境与签名见 [docs/BUILD.md](docs/BUILD.md)，MCP 客户端升级与 20 个工具参数见 [docs/MCP.md](docs/MCP.md)，本轮设备与模拟器结果见 [docs/MIGRATION_VALIDATION.md](docs/MIGRATION_VALIDATION.md)。

真实设备验证前保留设置和凭据，覆盖安装，不卸载或清除数据。只对 disposable fixture 自动输入。模拟器 Shizuku 用 `require_shizuku=true` 运行指定测试，授权缺失时必须失败，不能跳过。普通和增强 MCP 工作流之间重新启动测试夹具，以重置滚动与编辑状态。

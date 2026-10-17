# 构建与发布

## 环境

| 项目 | 使用版本 |
| --- | --- |
| JDK | 25 |
| Gradle wrapper | 9.7.1 |
| Android Gradle Plugin | 9.4.1 |
| Kotlin / Compose compiler plugin | 2.4.20 |
| Android SDK | compile / target 37，min 33 |
| Compose Material 3 | 1.5.0-alpha29 |
| Ktor CIO | 3.3.3 |
| Shizuku API | 13.1.5 |
| Android NDK | 28.2.13676358 |
| Go，原生构建使用 | 1.25.6 |

SDK 安装包名为 `platforms;android-37.0` 与 `build-tools;37.0.0`。在 `local.properties` 写入本机 SDK 路径，或设置 `ANDROID_HOME`。该文件不随项目分发。Gradle 的 daemon JVM 配置为 JDK 25；Java / Kotlin 代码也使用该工具链。

```sh
export JAVA_HOME=/absolute/path/to/jdk-25
export PATH="$JAVA_HOME/bin:$PATH"
./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleRelease
```

产物：`app/build/outputs/apk/debug/app-debug.apk`、`app/build/outputs/apk/release/app-release-unsigned.apk`。本地交付副本可放在被 Git 忽略的 `dist/`。正式 APK 与 `SHA256SUMS` 由版本标签触发的 Actions 发布到 GitHub Releases。

## 原生程序

APK 已包含三种 ABI 的 frpc 0.65.0 与 cloudflared 2025.8.1。可执行文件命名为 `libfrpc.so` / `libcloudflared.so`，由系统解压到 `nativeLibraryDir`，以普通 App UID 执行。它们是 Android ELF PIE，不是通过 JNI 调用的共享库；不要在打包时移除、重命名或 strip 这些文件。

重新构建：

```sh
export ANDROID_NDK_HOME=/absolute/path/to/android-ndk/28.2.13676358
./scripts/build-tunnels.sh
```

支持 macOS / Linux 构建主机。脚本下载固定标签源码并校验源码归档 SHA-256，为 Android API 33 构建 `arm64-v8a`、`armeabi-v7a`、`x86_64`，ELF LOAD 对齐为 16 KiB。Go DNS 使用本应用的回环 DNS 桥接，桥接调用 Android 系统解析器；cloudflared 补丁还使官方登录遵循指定的 `--origincert` 私有路径。

完整来源、补丁和二进制哈希位于 `app/src/main/assets/native-build.json`。重新构建后该文件由脚本更新。只有 arm64-v8a 已在当前实体机执行，其他 ABI 的构建和 ELF 检查不能替代安装运行验证。

## 发行签名

将以下环境变量设置为自己的发行密钥后运行 `:app:assembleRelease`：

```sh
export AVS_KEYSTORE=/absolute/path/to/release.jks
export AVS_KEY_ALIAS=your-key-alias
# 通过本地安全方式设置 AVS_STORE_PASSWORD 与 AVS_KEY_PASSWORD。
./gradlew :app:assembleRelease
```

不配置密钥时仍可构建 unsigned Release，不能直接作为可安装发行包。Debug 使用开发签名。0.0.1 正式包使用独立 RSA-4096 发行密钥和 APK v3 签名，包含本次本地开发证书到发行证书的签名历史；已验证这台测试机可保留数据升级。其他开发者的 Debug 证书不在该历史中，不能保证覆盖安装。不要通过卸载或清除数据解决用户设备的升级问题。

发行证书 SHA-256：`f5339eaa0dbac2267004dd5d151c5c35c79f007b7cca448d362e4f3578c30d5c`。

## 实体机验证

安装 `device-fixture` 只用于验证本项目的按钮、中文编辑框和滚动，不操作用户的其他应用：

```sh
./gradlew :device-fixture:assembleDebug :app:assembleDebugAndroidTest
adb -s IP:PORT install -r device-fixture/build/outputs/apk/debug/device-fixture-debug.apk
adb -s IP:PORT install -r app/build/outputs/apk/debug/app-debug.apk
adb -s IP:PORT install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb -s IP:PORT shell am instrument -w -e class io.github.lejw0925.virtualscreen.DeviceBackendTest io.github.lejw0925.virtualscreen.test/androidx.test.runner.AndroidJUnitRunner
```

Debug 独有的 `DeviceCheckReceiver` 受 `android.permission.DUMP` 限制，只供 shell / Root 调试。Release 完全不包含该接收器、测试应用或测试入口。

## GitHub Actions

工作流 `.github/workflows/build.yml` 使用固定提交版本的 Actions。推送 `main`、PR 和手动运行会执行 JVM 测试、Lint、Debug / unsigned Release、fixture 与 instrumentation 构建，上传报告和 Debug APK。

仓库维护者通过 GitHub Actions Secrets 配置发行资料：

| Secret | 内容 |
| --- | --- |
| `AVS_KEYSTORE_BASE64` | 发行 JKS 的 Base64 |
| `AVS_LINEAGE_BASE64` | `apksigner rotate` 生成的签名历史 Base64 |
| `AVS_STORE_PASSWORD` / `AVS_KEY_PASSWORD` | 密钥库和私钥密码 |
| `AVS_KEY_ALIAS` | 发行密钥别名 |

私钥、密码和签名历史只保存在维护者私有目录 / Actions Secrets。标签构建将其恢复到临时目录，签名完成后清除。最低版本为 API 33，发布只使用 v3 签名，无需把旧开发私钥交给 CI。`apksigner` 禁止签名 debuggable APK。

将 `versionName` 与 `docs/RELEASE_NOTES_<version>.md` 更新后推送匹配标签。稳定版使用 `vX.Y.Z`，Beta 使用 `vX.Y.Z-beta` 或 `vX.Y.Z-beta.N`；Beta 标签会发布为 GitHub prerelease，不会替代 latest stable release。检查通过才发布 `virtual-screen-<version>.apk` 和 `SHA256SUMS`；签名、标签、版本或发行说明不匹配会使发布失败。维护者应下载发布资产，检查哈希与证书，并做实机升级及 MCP 验证后宣布发行。

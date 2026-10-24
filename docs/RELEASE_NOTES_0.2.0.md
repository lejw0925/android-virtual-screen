## 虚拟屏 0.2.0

界面重做与包体优化版本：首页改为双卡片布局，运行日志改为卡片式展示并统一到 Material 3 规范，安装包按 CPU 架构拆分后约 20 MB。要求 Android 13 或更新版本，并使用已授权的 Root 或 Shizuku / Stellar 后端。

### 主要更新

- **安装包按架构分发**：隧道二进制占安装包主要体积，现改为每个架构单独出包（arm64-v8a、armeabi-v7a、x86_64），并保留 universal 包供不确定架构时使用。单架构包约 20 MB，此前单包 98.8 MB。
- **启用 R8 与资源压缩**：Release 构建裁掉未使用的依赖代码，其中主要是整套 Material 图标（库内 11,100 个类，实际仅使用 46 个）。
- **首页结构**：移除大标题与页面级滚动，只保留卡片内部滚动；后端状态移到左上角，无障碍与设置合并为同一色调的图标按钮，边距与卡片对齐。
- **运行日志**：改为按条卡片展示，严重级别以右下角低透明度图标水印区分，类别、状态与客户端字段用只读 chip 呈现，chip 图标按内容选择；切换「全部 / 错误 / 警告」筛选时列表回到顶部，不再出现卡片盖住列表上方按钮。
- **Material 3 规范**：新增独立的成功/警告语义色，修复警告级日志显示为绿色的问题；六处手写对话框改用 M3 对话框，恢复平台宽度约束、返回键与输入法避让；形状、动效改用主题 token，触摸目标补足 48dp；接入窗口尺寸类别约束正文宽度。

### 验证范围

- JVM 单元测试 146 项、Android Lint、调试与正式版构建通过。
- 模拟器（Android 14，Shizuku 后端）前台验证首页布局、日志卡片与筛选切换、浅色/深色模式及 200% 字号。
- Release（R8）装机验证：19/19 后端检测通过，Cloudflare 快速隧道连通并 `PUBLIC_ORIGIN_VERIFIED`。
- 单架构包与 universal 包的安装行为、Root / Shizuku 偏差及厂商 ROM 的后台策略仍需在更多真机回归。

### 说明

Android 10 起，`targetSdk ≥ 29` 的应用不能执行自身可写目录中的文件。因此隧道二进制无法改为首次运行时下载，必须由系统解压到 `nativeLibraryDir` 后执行。已在应用进程内做过对照验证：同一文件在 `nativeLibraryDir` 可正常执行，移动到应用私有目录后返回 `EACCES`。

## Virtual Screen 0.2.0

A UI rework and package-size release: the home screen uses two cards, the activity log is card-based and aligned with Material 3, and APKs are split per CPU architecture at about 20 MB each. Requires Android 13+ and an authorized Root or Shizuku / Stellar backend.

### Highlights

- **Per-architecture APKs**: the bundled tunnel binaries account for most of the download, so releases now ship separate arm64-v8a, armeabi-v7a, and x86_64 packages, plus a universal package when the architecture is unknown. A single-architecture APK is about 20 MB, down from 98.8 MB.
- **R8 and resource shrinking**: release builds strip unused dependency code, dominated by the full Material icon set (11,100 classes shipped, 46 used).
- **Home screen**: the large title and page-level scrolling are gone; only the card interior scrolls. Backend status moved to the top-left, and accessibility plus settings share one tonal icon-button treatment aligned with the card margins.
- **Activity log**: entries are tonal cards. Severity is a low-opacity watermark in the bottom-right corner, and category, status, and client fields are read-only chips whose glyphs follow their content. Switching the All / Errors / Warnings filter resets the list to the top, so entries no longer draw over the buttons above.
- **Material 3**: success and warning now have dedicated semantic colors, so warning entries no longer render green. Six hand-written dialogs moved to M3 dialogs, restoring the platform width limit, back handling, and IME insets. Shapes and motion use theme tokens, touch targets reach 48dp, and content width follows window size classes.

### Validation

- 146 JVM unit tests, Android Lint, and debug plus release builds pass.
- Emulator foreground checks (Android 14, Shizuku backend): home layout, log cards and filter switching, light and dark themes, and 200% font scale.
- Release (R8) on device: all 19 backend probe tools passed, and a Cloudflare quick tunnel connected with `PUBLIC_ORIGIN_VERIFIED`.
- Install behavior of the split and universal APKs, Root / Shizuku differences, and vendor background policies still need regression coverage on more physical devices.

### Note

Since Android 10, apps targeting API 29+ cannot execute files from their own writable directories. The tunnel binaries therefore cannot be downloaded on first run; they must be extracted to `nativeLibraryDir` by the system. Verified inside the app process: the same file executes from `nativeLibraryDir` but returns `EACCES` from app-private storage.
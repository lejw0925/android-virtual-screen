# Android 17 后端与首页修复验证

验证日期：2026-10-19（Asia/Shanghai）。设备：小米 24129PN74C，Android 17 / API 37，HyperOS OS4.0.0.19.XOCCNXM，1200 × 2670 / 520 dpi。使用 KernelSU Next Root 后端。

## 修复与根因

- 原发行包的 Root app_process 在获得 KeyguardManager 时抛出 `ApplicationSharedMemory not initialized`，后续客户端仅收到 `DISPLAY_GONE`。为独立特权进程补充共享内存单例初始化，已初始化或无此类的系统保持原流程。
- 检测页的启动动画会使节点坐标短暂超出虚拟屏；焦点变更也晚于操作返回。探测现在等待屏内稳定坐标和编辑框焦点，只轮询观察，不重放已接受的输入。点击和文本结果按具体控件精确匹配，避免列表中的数字 1 提前满足点击验证。
- 关闭临时屏时清理旧屏错误；MCP 成功启动后清理无活动屏时残留的 `DISPLAY_GONE` / `NO_VIRTUAL_SCREEN`。
- 首页卡片使用顶部标题、居中内容和底部操作布局；连接卡片按钮保持单行并等高，大字体继续使用纵向按钮。

## 验证结果

- `:app:testDebugUnitTest`：91 项通过，0 失败、0 错误、0 跳过。
- `:app:lintDebug`：0 错误，63 项现有警告。
- `DeviceToolProbeTest`：Root 检测完整执行 19 个工具，全部通过；临时屏、租约和旧错误均已清理。
- 新证书 Release 并存包中实际点击“权限与后端 → 授权并检测 → 开始检测”：显示 **19/19 个工具通过**；显示创建、截图、输入和节点均可用。
- 实际从首页开启 MCP：显示“已就绪”和“关闭服务”，顶部无“虚拟屏未运行，请先启动”。通知和电池优化豁免只授予新安装包。
- 两个连接按钮边界分别为 `[206,2260][626,2416]`、`[659,2260][1080,2416]`，高度均为 156 px，文字均为单行。
- 本轮未验证 Shizuku、熄屏、其他 Android 版本和大字体；熄屏能力保持“待实际检测”。

截图：[授权结果](images/backend-probe-20261019.png)、[服务首页](images/mcp-home-20261019.png)、[连接卡片](images/mcp-connection-20261019.png)。本地详细证据位于 `build/validation/2026-10-19/`。

## 新签名交付

用户要求创建新证书。已生成 RSA-4096 密钥，证书 SHA-256：`ad6ae346550453ea104d4ffd76e00a84dea9b482fbb3efb7b6a21b452fc2bab3`。两个 APK 均通过 `apksigner verify`（APK v2 签名）。

- 标准包：`dist/virtual-screen-0.1.0-beta-local-signed-20261019.apk`，包名 `io.github.lejw0925.virtualscreen`。
- 真机并存包：`dist/virtual-screen-0.1.0-beta-parallel-signed-20261019.apk`，包名 `io.github.lejw0925.virtualscreen.localsigned`，桌面名称“虚拟屏 · 新签名”。已安装并完成上述 Release 实测。
- 两份 APK 均附 `.apk.sha256` 校验文件。私钥和密码仅保存在仓库外的本机私有目录 `/home/lejw/.local/share/android-virtual-screen/signing-20261018/`，不写入仓库。
- 新证书不能覆盖原发行证书的同包名安装。本次验证使用新证书并存包，未对原发行包执行卸载、清数据或重新签名。

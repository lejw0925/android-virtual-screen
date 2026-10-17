## 虚拟屏 0.1.0-beta

Beta 测试版，要求 Android 13 或更新版本，并使用已授权的 Root 或 Shizuku 后端。

### 主要更新

- 重做 Material 3 首页、设置和首次使用流程；服务与连接卡片根据可用高度伸缩，短屏下内容可在卡片内滚动。
- 增加授权后全量运行 MCP 工具的诊断流程，并根据实际虚拟屏操作显示能力和逐工具结果。
- 扩展 MCP 控制、观察、输入、等待和请求回执工作流；输入严格限定在本应用创建的虚拟显示。
- 改进 Root 命令形式探测、Shizuku 授权等待、Root 轻点时序及会话清理。
- 补全中英文仓库说明、用例图、架构图和 Beta 发布验证信息。

### 验证范围

- 88 项 JVM 单元测试通过，Android Lint 无错误。
- Android 15 x86_64 模拟器的 Shizuku 应用内诊断：19/19 MCP 工具通过；显示创建、截图、输入和无障碍节点能力可用。
- 模拟器的 SELinux 策略禁止普通应用执行 AOSP `su`，Root 应用授权和完整 MCP 探测未能在该镜像完成。Root 直接引擎检查不能替代已获授权 Root 应用的验证。
- 熄屏操作、不同厂商 ROM 和所有公网隧道网络环境未全部验证。

## Virtual Screen 0.1.0-beta

Beta build for Android 13+ with an authorized Root or Shizuku backend.

### Highlights

- Refreshes the Material 3 home, settings, and onboarding. Service and connection cards adapt to available height and keep short-screen content scrollable inside each card.
- Adds an authorization-gated diagnostic that runs every MCP tool and reports capabilities and per-tool results from real virtual-display operations.
- Expands MCP control, observation, input, wait, and request-receipt workflows. Input remains scoped to displays created by this app.
- Improves Root command detection, Shizuku grant waiting, Root tap timing, and session cleanup.
- Completes the Chinese and English repository guides, use-case and architecture diagrams, and Beta validation notes.

### Validation

- 88 JVM unit tests passed; Android Lint reported no errors.
- In-app Shizuku diagnostics on an Android 15 x86_64 emulator passed 19/19 MCP tools. Display creation, capture, input, and accessibility nodes were available.
- The emulator's SELinux policy prevents ordinary apps from executing its AOSP `su`; Root app authorization and a full Root MCP probe could not be completed on this image. Direct Root engine checks do not replace validation with Root granted to the app.
- Screen-off operation, all vendor ROMs, and all public-tunnel network environments are not covered.

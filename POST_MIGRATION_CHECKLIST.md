# 迁移验收清单

- [x] Material 3 替换 miuix，沿用可用的 Activity / 预览生命周期，清理临时方案说明。
- [x] JDK 25 与 CI、daemon、fixture 一致；构建 Debug / Release / instrumentation / fixture。
- [x] 首页深色文字、三语言、主题、字号、间距、大小字体与横屏界面检查。
- [x] 20 个 MCP 工具、适用参数 schema、只读标记、客户端说明与验证脚本同步。
- [x] Cloudflare 重试/连接/公网验证状态、浏览器说明页、实时日志。
- [x] 固定隧道创建响应丢失的核对与取消回归，不重复发送创建操作。
- [x] 本地轮转运行日志，连接 IP / 时间 / 操作 / 结果，脱敏与清空。
- [x] 空闲释放 CPU 唤醒锁、减少后端轮询、操作期唤醒锁实测。
- [x] 通知单色图标与应用图标使用相同轮廓。
- [x] vivo Root / Shizuku 与公网设备检查；普通与增强 MCP 工作流。
- [x] Android 14 模拟器普通 shell Shizuku 创建/截图/输入/连续重建/节点/回执检查，无授权跳过。
- [x] APK、SHA-256 和验证记录；恢复测试设置并停止测试服务。

权威结果和范围见 [docs/MIGRATION_VALIDATION.md](docs/MIGRATION_VALIDATION.md)。固定域名真实账号授权/DNS 和长期电池续航没有伪记为已测。

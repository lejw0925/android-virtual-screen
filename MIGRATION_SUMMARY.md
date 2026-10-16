# Material 3 迁移与运行时改进

日期：2026-10-16。当前工作区使用现有 MainActivity、SettingsActivity、ViewerActivity 和保存的设置返回栈，全部 UI 改用 Compose Material 3。临时的 `*ActivityNew`、单 Activity NavGraph 方案没有采用。JDK 统一为 25，与 Gradle daemon、CI 和 device-fixture 一致。

## 已实现

- 移除 miuix Gradle 依赖，使用 Material 3 1.3.1 和 Material Icons；保留历史第三方许可并补充 AndroidX 许可。
- 首页、引导、权限、设置和隧道页面统一语义色、字号与内外边距；根内容色及系统栏跟随主题，修复深色 MCP 文字。
- 共用 Preference 组件使用一个触摸/无障碍目标，开关不会重复触发；下拉对话框支持滚动和三语言关闭按钮，应用图标按需加载。
- 隧道区分连接、重试、公网验证、连接完成和终止错误；Quick / named Cloudflare 公网入口验证成功后再报告连接完成，停止和切换取消旧验证。
- 对创建固定隧道后的响应丢失执行凭据或账号列表核对，避免重复创建；不会覆盖已有 DNS。
- 隧道实时日志可直接查看、复制，停止后保留。运行日志保存时间、连接 IP、客户端、操作与结果，凭据脱敏、私有存储和大小轮转。
- 浏览器访问根地址或 `/mcp` HTML 请求能看到连接说明；实际 MCP 保持 Bearer、Origin、会话与租约验证。
- MCP 从复合操作工具拆分为 20 个工具，详情和升级说明见 [MCP.md](docs/MCP.md)。
- 空闲 MCP 不保持 CPU 唤醒锁、不轮询后端；工作时短期持锁，等待手机审批时释放；显示健康检查降低频率。
- 通知使用与启动图标一致的重叠屏幕与加号轮廓的单色图标。
- 普通 shell Shizuku 使用匹配 UID 的系统包上下文；独立绑定标识和代际检查防止旧解绑销毁新服务。节点服务在 ROM 留下崩溃绑定时只重新绑定自身。

## 验证

以 [迁移验证记录](docs/MIGRATION_VALIDATION.md) 为准。该记录区分 JVM、实机、模拟器、公网与未覆盖范围，不用旧发行记录替代本次结果。

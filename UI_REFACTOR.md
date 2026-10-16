# Material 3 UI 迁移

当前实现沿用现有 Activity 与保存的设置返回栈；此前的临时 New Activity / NavGraph 草案已被替换。

请查看 [迁移总结](MIGRATION_SUMMARY.md)、[验证清单](POST_MIGRATION_CHECKLIST.md) 和 [实测记录](docs/MIGRATION_VALIDATION.md)。UI 源码位于 `app/src/main/kotlin/io/github/lejw0925/virtualscreen/ui/`，共享偏好组件为 `Preferences.kt`。

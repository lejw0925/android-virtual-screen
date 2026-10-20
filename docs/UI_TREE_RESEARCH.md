# UI tree 读取内容与同类仓库对照（实现前基线）

调研日期：2026-10-19。对照对象为当前工作区实现，以及当天通过 GitHub API 解析默认分支后固定的 6 个项目版本。

本文中的本仓库实现细节记录本轮节点树改动前的代码快照；当前 `observe` 默认值、compact/full 字段和预算以 [MCP 接入说明](MCP.md) 为准。

本文是静态源码调研，没有部署第三方项目，没有测量读取延迟、token 消耗或任务成功率，也没有修改本仓库运行逻辑。必须区分三个层次：系统实际提供的节点、采集代码保存的字段、最终返回给模型的字段。

## 结论

本仓库已经采集文本、描述、类名、资源 ID、屏幕边界、父节点关系和常见控件状态，并支持虚拟显示上的多个窗口。主要差异是：

1. 缺少输入框提示、语义状态说明、长按/可聚焦能力，以及节点实际支持的动作列表。
2. 采集了多个窗口，却没有在树输出中保留窗口分组、类型、层级和逐节点包名。
3. `compact` 只减少节点，仍为每个节点输出完整字段；过滤后 `parent_id` 可能指向未返回的父节点。
4. 深度、总节点数和单节点子项限制固定且不可分页。不可见/结构节点也先占用采集预算。
5. 与两个同类 AccessibilityService 项目相比，`isAccessibilityTool` 声明不同，可能造成系统提供的节点范围不同；不能把系统过滤误诊成序列化字段不足。
6. 已有虚拟显示范围、租约、观察消费和节点刷新校验应继续保留。其他项目的数据提取和格式化思路可以借鉴，主屏回退策略不能直接照搬。

## 本仓库实际上读取什么

依据：

- [ScreenAccessibilityService.kt](../app/src/main/kotlin/io/github/lejw0925/virtualscreen/core/ScreenAccessibilityService.kt)：`snapshot` / `act` / `signature`。
- [VirtualScreenBackend.kt](../app/src/main/kotlin/io/github/lejw0925/virtualscreen/backend/VirtualScreenBackend.kt)：`NodeSnapshot` / `NodeInfo`。
- [McpToolRegistry.kt](../app/src/main/kotlin/io/github/lejw0925/virtualscreen/mcp/McpToolRegistry.kt)：`observe` / `nodeValues` / `matches`。
- [accessibility_service.xml](../app/src/main/res/xml/accessibility_service.xml)。

| 类别 | 采集并返回的内容 | 说明 |
| --- | --- | --- |
| 定位和层级 | `id`, `resource_id`, `parent_id`, `class`, `bounds` | `id` 是当前 observation ID 加遍历序号，不是跨观察稳定 ID |
| 文字 | `text`, `description` | 密码节点的 `text` 置空；没有读取 `hintText` / `stateDescription` |
| 交互 | `clickable`, `editable`, `scrollable` | 不返回 `longClickable` / `focusable` / `actionList` |
| 状态 | `enabled`, `focused`, `selected`, `checkable`, `checked`, `visible`, `password` | `focused` 对应输入焦点，不是无障碍焦点 |
| 快照 | `package`, `node_count`, `returned_node_count`, `truncated`, `format`, `node_limit`, `depth_limit` | `package` 仅取排序后第一个根节点；不是逐窗口/逐节点的包名 |

实现细节：

- `getWindowsOnAllDisplays().get(displayId)`，只读取持有的非主显示；应用窗口优先，再按 layer 降序遍历。键盘等同显示窗口也会参与。
- 所有根共用一次 DFS 和预算：最多 500 个节点，根深度为 0，允许到深度 40，每个节点最多访问前 100 个子项。任一限制触发即置 `truncated=true`。
- `full` 返回所有已采集节点，包括不可见和结构节点；它仍受上述限制，不能理解成系统中的全部节点。
- `compact` 保留 `visible && (有 text / description，或 clickable / editable / scrollable / focused / checkable)` 的节点。
- 原始 `parent_id` 不会改写；父节点被过滤后，输出里可能找不到对应父节点。只带 resource ID、只支持长按、仅 focusable 或仅 selected 的节点也可能被过滤。
- `compact` 与 `full` 的单节点字段集合相同，空值、false 状态和较长观察 ID 仍逐节点出现。
- 默认 `observe` 只返回截图；必须显式指定 `mode=tree` 或 `mode=both`。这与“节点读取失败”不同。
- Root / Shizuku 的树读取都调用 `ScreenAccessibilityService.snapshot`，不是 root shell / UiAutomation 抓树。因此 root 权限本身不改变该服务可见的节点范围。
- `setFocusedText` 为执行输入读取了 `textSelectionStart/End`，但这两个字段没有进入树模型或 MCP 树输出。
- 动作执行前会 `refresh()` 并校验签名和窗口/显示；采集时未显式刷新每个子节点或清理 Android framework 节点缓存。清理的是本应用保存的节点引用。

## GitHub 样本与固定版本

完整 SHA、文件链接及下载内容 SHA-256 见 [ui-tree-research-sources.json](ui-tree-research-sources.json)。下面链接均固定到该次检查的 commit。

| 仓库 | 固定 commit | 读取入口 |
| --- | --- | --- |
| [mobile-next/mobile-mcp](https://github.com/mobile-next/mobile-mcp) | `c659f8e` | [mobile-device.ts](https://github.com/mobile-next/mobile-mcp/blob/c659f8ee34873a832adea7daca9c69172d887e70/src/mobile-device.ts)、[android.ts](https://github.com/mobile-next/mobile-mcp/blob/c659f8ee34873a832adea7daca9c69172d887e70/src/android.ts) |
| [CursorTouch/Android-MCP](https://github.com/CursorTouch/Android-MCP) | `a983ca1` | [tree/service.py](https://github.com/CursorTouch/Android-MCP/blob/a983ca16e03fd557e8580f3b90d790a291454a94/src/android_mcp/tree/service.py) |
| [danielealbano/android-remote-control-mcp](https://github.com/danielealbano/android-remote-control-mcp) | `1a09261` | [AccessibilityTreeParser.kt](https://github.com/danielealbano/android-remote-control-mcp/blob/1a09261d7d99840403cc07302684b9f711af0013/app/src/main/kotlin/com/danielealbano/androidremotecontrolmcp/services/accessibility/AccessibilityTreeParser.kt) |
| [droidrun/mobilerun-portal](https://github.com/droidrun/mobilerun-portal)（原 droidrun-portal） | `cc3fa91` | [AccessibilityTreeBuilder.kt](https://github.com/droidrun/mobilerun-portal/blob/cc3fa91b15847ca9f7cc9a5afdd6b8e1db7e4ec2/app/src/main/java/com/mobilerun/portal/core/AccessibilityTreeBuilder.kt) |
| [appium/appium-uiautomator2-server](https://github.com/appium/appium-uiautomator2-server) | `56bf27a` | [UiElementSnapshot.java](https://github.com/appium/appium-uiautomator2-server/blob/56bf27a222b9aa242e6a40cf87825b78b443b7be/app/src/main/java/io/appium/uiautomator2/model/UiElementSnapshot.java) |
| [google-research/android_world](https://github.com/google-research/android_world) | `30583e2` | [AccessibilityTreeCreator.kt](https://github.com/google-research/android_world/blob/30583e22b9fbb8f32f109d3214153517b6f9b8e4/android_world/google/apps/java/com/google/androidenv/accessibilityforwarder/AccessibilityTreeCreator.kt) |

### mobile-next/mobile-mcp：面向模型的精简元素列表

- `MobileDevice` 调用 `mobilecli dump ui`，接收 `ref/type/label/text/name/value/identifier/rect/focused/selected/checked/enabled/children`，递归展平成元素列表；此路径没有向最终元素保留 parent/children。字段是可选接口，不保证每个 Android 后端都会填满。不能仅凭此仓库断言外部 CLI 采集了多少原生字段。
- 仓库内 `android.ts` 的 ADB 路径明确运行 `uiautomator dump /dev/tty`，提取 `class/text/content-desc/hint/resource-id/bounds/focused`；`label` 优先使用 content-desc，其次 hint。保留有文本、描述、hint、资源 ID 或 checkable 的非零尺寸节点。
- [format-elements.ts](https://github.com/mobile-next/mobile-mcp/blob/c659f8ee34873a832adea7daca9c69172d887e70/src/format-elements.ts) 支持文本/JSON，文本短类名、每元素一行；focused/selected/checked 仅为 true 时出现，enabled 仅为 false 时出现。
- 与本仓库相比，定位层级和动作能力字段较少，但有 hint 回退，输出更紧凑。适合借鉴格式化，不宜据此削减本仓库的观察校验。

### CursorTouch/Android-MCP：从整棵 XML 提取可操作元素

- 使用 `uiautomator2.device.dump_hierarchy()`，只挑选 `enabled=true` 的节点，再依据 focusable/clickable/long-clickable/checkable/scrollable/selected/password 或控件类名判断交互性。
- 名称优先 content-desc / text；为空时向非独立交互子树收集 text/content-desc/hint，作为父容器的可读名称。遇独立交互子节点会停止深入，其名称只作为回退，避免把多个操作目标完全混为一项。
- [views.py](https://github.com/CursorTouch/Android-MCP/blob/a983ca16e03fd557e8580f3b90d790a291454a94/src/android_mcp/tree/views.py) 内部存 name/class/中心点/bounds/短 resource ID；文本表格只输出序号、名称、资源 ID、类名和中心点，不输出状态矩阵或父子关系。无名称的候选节点被跳过。
- 与本仓库相比，输出字段更少、偏动作目标，会舍弃纯阅读文本和禁用节点；值得借鉴的是为无文字的可点击父容器推导名称，而不是照搬全部过滤条件。

### android-remote-control-mcp：窗口上下文、表单标签和 WebView 整理

- 基础字段之外，parser 读取 `longClickable/focusable/hintText/labeledByText`，以及 Chromium extras 中的 `chromeRole` / `targetUrl`。
- [AccessibilityData.kt](https://github.com/danielealbano/android-remote-control-mcp/blob/1a09261d7d99840403cc07302684b9f711af0013/privacy/src/main/kotlin/com/danielealbano/androidremotecontrolmcp/services/accessibility/AccessibilityData.kt) 用嵌套 children 保留树，并提供窗口 ID/type/package/title/activity/layer/focused。这不等于所有字段都会在默认紧凑响应中显示。
- [CompactTreeFormatter.kt](https://github.com/danielealbano/android-remote-control-mcp/blob/1a09261d7d99840403cc07302684b9f711af0013/app/src/main/kotlin/com/danielealbano/androidremotecontrolmcp/services/accessibility/CompactTreeFormatter.kt) 默认节点列为 node ID/class/text/desc/resource ID/bounds/flags，另列窗口头和过滤后 hierarchy。忽略结构节点时，会将子项提升到最近保留祖先；不可见但有用的项仍可保留并标 off。按 200 个保留节点分页。
- [WebViewNodeMerger.kt](https://github.com/danielealbano/android-remote-control-mcp/blob/1a09261d7d99840403cc07302684b9f711af0013/app/src/main/kotlin/com/danielealbano/androidremotecontrolmcp/services/accessibility/WebViewNodeMerger.kt) 仅在 Chromium role 标记的子树内按语义块整理文字、链接、图片信息，保留各交互目标。它整理的是已暴露的无障碍节点，不是通用 DOM 获取器。
- [NodeActionTools.kt](https://github.com/danielealbano/android-remote-control-mcp/blob/1a09261d7d99840403cc07302684b9f711af0013/app/src/main/kotlin/com/danielealbano/androidremotecontrolmcp/mcp/tools/NodeActionTools.kt) 的 `getFreshWindows` 先清 framework cache 再读取；parser 对可能的 Compose/WebView 虚拟子节点调用 refresh。树深限制为 100。
- 与本仓库相比，表单命名、窗口归属、层级压缩和 WebView 输出更丰富；其 parser 没有本仓库这组完整的 checked/selected/focused 状态字段，因此也不是各方面都采集更多。

### mobilerun-portal：full 模式接近节点属性转储

- full tree 包含 text/contentDescription/hint/stateDescription/tooltipText/paneTitle/error、屏幕及父坐标、逐节点 package/windowId。
- 交互和状态覆盖 long/context clickable、focusable、input/accessibility focused、selected/checkable/checked、editable/password/showingHint、dismissable/multiline/heading/screenReaderFocusable 等。
- 还读取 textSelectionStart/End、inputType/maxTextLength、rangeInfo（类型/最小/最大/当前值）、collectionInfo（行列数等）、collectionItemInfo、actionList（ID/label/name）、extras、uniqueId、containerTitle。标签/遍历关联只输出是否存在的标记，不能当作完整关联节点内容。
- [JsonBuilders.kt](https://github.com/droidrun/mobilerun-portal/blob/cc3fa91b15847ca9f7cc9a5afdd6b8e1db7e4ec2/app/src/main/java/com/mobilerun/portal/core/JsonBuilders.kt) 的普通 tree 则很精简：index/resourceId/className/text/bounds/children；不能把 full 的能力写成默认输出。
- full 过滤可关闭；开启时按边界可见面积比例过滤，并保留有合格后代的祖先。源码实际阈值为 **1%**，注释仍写 10%，这里以代码常量为准。遍历带循环保护，最大深度 128。
- 与本仓库相比，它适合作为 full/detail 字段设计的参考；默认观察没有必要每次复制全部 extras 和状态。

### Appium UiAutomator2：字段细、按需扩展

- `UiElementSnapshot` / [Attribute.java](https://github.com/appium/appium-uiautomator2-server/blob/56bf27a222b9aa242e6a40cf87825b78b443b7be/app/src/main/java/io/appium/uiautomator2/utils/Attribute.java) 除基础属性外读取 hint、focusable/long-clickable、selection-start/end、input-type、showing-hint、multiline、error、pane-title、tooltip、heading、window-id、a11y-focused 等。
- extras、actions、额外渲染信息（字形尺寸/单位）受独立设置控制，不能当作默认 XML 一定包含的内容。`includeA11yActionsInPageSource` 默认 false。collection-info / collection-item-info 仅按需 getAttribute，不进入 page source；XML 另有 is-collection / is-collection-item 标记。
- 不可见元素默认排除；提供允许不可见元素、特定类继续遍历等设置。`snapshotMaxDepth` 默认 70，可设 1–500。
- 与本仓库相比，更完整地区分输入、可访问性与渲染信息，适合借鉴可选字段/节点详情机制，而不是每次返回所有字段。

### AndroidWorld：采集完整 forest，再构造 agent 的 UIElement

- Kotlin forwarder 保留多个窗口及其 ID/layer/type/active/focus/bounds；节点含 parent/child IDs/depth、package/windowId、hint、tooltip、focusable/longClickable、selectionStart/End、drawingOrder 和 action ID/label。
- 遍历采用 BFS，并用 seen set 去重。
- [representation_utils.py](https://github.com/google-research/android_world/blob/30583e22b9fbb8f32f109d3214153517b6f9b8e4/android_world/env/representation_utils.py) 转 UIElement 时只选择叶节点、有 content description 的节点或 scrollable 节点，可选排除不可见项；保留 text/description/class/hint、常见状态、包名、资源名和像素/归一化边界。底层 actionList/窗口层级等并未全部复制到这个 agent 表示中。
- 与本仓库相比，它更明确分离采集表示与 agent 表示；不能把其完整 protobuf 的字段数直接当成模型每次能看到的字段数。

## 对任务影响最大的差异

| 场景 | 本仓库现在的不足 | 参考实现 | 建议 |
| --- | --- | --- | --- |
| 空输入框 | 无 text 时缺少 hint / 外部 label | android-remote-control-mcp、AndroidWorld、Portal | 加 hint、labeledByText；保留原始字段，另计算可读名称 |
| 开关、进度、验证状态 | checked 不能表达所有语义；没有 stateDescription/error/range | Portal、Appium | 加 state_description/error/range_info |
| 长按、定向滚动、滑块 | 能请求 long_click，但观察中没有 longClickable；scrollable 不说明支持方向/动作 | Portal、AndroidWorld、Appium 可选 actions | 返回 long_clickable/focusable 和有界 action ID/label 列表；执行仍用允许的动作集合 |
| 弹窗、键盘与应用混在一起 | 只保留一个 package，节点缺窗口归属 | android-remote-control-mcp、AndroidWorld | 保留 windows 元数据及每节点 window_id/package；仍仅遍历 owned display |
| 过滤后理解容器关系 | parent_id 可能悬空，空白可点击容器无可读名称 | android-remote-control-mcp、CursorTouch | 加 nearest retained parent，必要时保留原 parent_id；派生名称不跨独立操作目标 |
| 长列表 / 深 WebView | 固定预算可能先被隐藏/结构节点耗尽，后续窗口饥饿 | android-remote-control-mcp 分页、Appium 可调深度 | 配置预算，返回明确截断原因；分页必须绑定同一 observation 快照 |
| 网页内容 | 没有 role/URL 与语义合并 | android-remote-control-mcp | 白名单提取 Chromium role/targetUrl，整理已暴露文本 |
| 动态页面内容陈旧 | 采集时未显式处理 framework cache；执行前 refresh 不等于读取总新鲜 | android-remote-control-mcp | 基于复现评估刷新/清缓存策略，在串行采集中执行并测试代价 |

## 系统是否允许读取：独立于字段数量的问题

本仓库已经启用 `flagIncludeNotImportantViews`、`flagReportViewIds` 和 `flagRetrieveInteractiveWindows`，因此“没开普通结构节点/资源 ID/多窗口读取”不是当前差异。

但本仓库没有声明 `android:isAccessibilityTool=true`；[android-remote-control-mcp 配置](https://github.com/danielealbano/android-remote-control-mcp/blob/1a09261d7d99840403cc07302684b9f711af0013/app/src/main/res/xml/accessibility_service_config.xml) 和 [Portal 配置](https://github.com/droidrun/mobilerun-portal/blob/cc3fa91b15847ca9f7cc9a5afdd6b8e1db7e4ec2/app/src/main/res/xml/accessibility_service_config.xml) 均声明了 true。

[Android API 文档](https://developer.android.com/reference/androidx/core/view/accessibility/AccessibilityNodeInfoCompat#setAccessibilityDataSensitive(boolean)) 说明，SDK 34+ 的敏感节点会对 `isAccessibilityTool=false` 的服务隐藏。[AccessibilityServiceInfo 文档](https://developer.android.com/reference/android/accessibilityservice/AccessibilityServiceInfo#attr_android:isAccessibilityTool) 同时明确，该标志表示服务用于辅助残障用户，不是普通的“读取更全”开关。[Android 官方说明](https://developer.android.com/blog/posts/enhancing-android-security-stop-malware-from-snooping-on-your-app-data) 也说明了对不符合用途却声明该标志的限制。

因此，这是某些页面只剩容器节点时值得核查的系统层差异，不是本次已证实的具体 App 根因，也不应仅为增加采集量就修改声明。即使增加 NodeInfo 字段，也无法恢复系统从未交付的节点。

同样，本次没有证据说明读取 WebView 的缺失文本一定能由 XML 或增加字段解决。需区分：应用不暴露语义、系统过滤、framework 缓存、采集截断、compact 过滤、默认 screenshot 模式。

## 建议实现顺序

1. **先补高价值字段和窗口归属**：hint_text、state_description、long_clickable、focusable、error、window_id、逐节点 package；full/detail 加 selection/input/range/collection 和 actionList。密码 text 继续抑制，对新增 hint/error/extras 另评估信息暴露。
2. **修 compact 的上下文**：保留/提升到最近输出祖先，为独立交互容器派生名称；省略默认布尔值和 null，短 ref 只作为同 observation 内别名。不要改变当前观察消费语义。
3. **再补按需读取**：node_details 或字段选择、分页与可调预算；明确 node/depth/child 截断原因。分页固定同一观察，不能静默重新采集。
4. **按实际复现增强兼容性**：WebView role/URL 合并、framework 缓存策略和循环防护。针对系统敏感节点限制提供准确诊断，不把它与 empty/filtered/truncated 混在一起。

后续实现应覆盖：空输入框、label 关联、无文字长按节点、checked 与 stateDescription、键盘和弹窗窗口、过滤父节点、深度/子数/全局预算、动态 WebView、密码内容、过期 observation 和非 owned-display 拒绝。

## 与已有实机记录的关系

[NODE_TREE_DIAGNOSTICS.md](NODE_TREE_DIAGNOSTICS.md) 已记录 2026-10-19 小红书某主页面：full 220 个节点，compact 41 个，`truncated=false`。这是既有文档结果，本次没有重复实机测试。它说明该次页面存在可读节点且有精简过滤，不能据此认定所有页面都可读，也不能把本次系统配置差异定为其历史点击问题的根因。

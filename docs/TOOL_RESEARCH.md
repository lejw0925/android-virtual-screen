# Android MCP 工具调研与改进建议

调研日期：2026-10-13。建议先完善观察、定位、反馈和条件等待，保留现有 10 个主工具。工具数量不能代表任务完成能力；对本项目更有价值的指标是操作是否选对目标、结果是否可验证，以及每个任务需要多少次往返。

## 调研范围与证据

核对了 5 个提供 Android 控制 MCP 的 GitHub 项目，以及本项目虚拟屏代码的来源 ETA。以源码注册表、参数定义和执行逻辑为依据，固定到下表的 commit；完整名称、源文件链接及 SHA-256 保存在 [来源清单](tool-research-sources.json)。这是静态接口调研，未部署其他项目，也未测量它们的成功率、延迟或 token 成本。

| 项目 | 固定版本 | 工具数量口径 | 定位与可借鉴点 |
| --- | --- | --- | --- |
| [mobile-next/mobile-mcp](https://github.com/mobile-next/mobile-mcp/blob/0a6a38bbc384f10c64bb1029f26016735bf823a6/src/server.ts) | `0a6a38b` | 34 种注册，关闭遥测时 33 | 电脑端、多设备、Android/iOS；元素 ref、精简文本、截图缩放、批量命令 |
| [CursorTouch/Android-MCP](https://github.com/CursorTouch/Android-MCP/blob/0640a5d97d37e9add37236bad325cb88ea56d512/src/android_mcp/__main__.py) | `0640a5d` | 14 个 MCP 注册 | ADB/uiautomator2；选择器点击、条件等待、默认不附截图 |
| [danielealbano/android-remote-control-mcp](https://github.com/danielealbano/android-remote-control-mcp/blob/16f39717ce0969aa81a4ec132ba1cad861ba46cc/docs/MCP_TOOLS.md) | `16f3971` | 57 种，运行目录受权限和配置过滤 | 手机上的 HTTP MCP；综合屏幕状态、节点查询、分页、手势完成与界面稳定分开 |
| [nidhoggfgg/PhoneUse](https://github.com/nidhoggfgg/PhoneUse/blob/fa51691077e5f094dc2e09dcd9471f2f3e81a2bb/app/src/main/java/ai/nidhoggfgg/phoneuse/core/ToolCatalog.kt) | `fa51691` | 手机 17 个；stdio 桥另有 4 个 | 观察标识、目标校验、执行回执、可选动作后观察；与本项目控制语义接近 |
| [hanxi/droidrun-agent](https://github.com/hanxi/droidrun-agent/blob/04cacf51b0d82f687e553801a1513ae8c6d25b15/src/droidrun_agent/mcp_server.py) | `04cacf5` | 20 个声明；部分限 HTTP/WS 后端 | 第三方 DroidRun Portal MCP 封装；精简/完整状态分层、UI 树和图片独立 |
| [Mangi-11/Eta](https://github.com/Mangi-11/Eta/blob/9de3a3f70aba497bd8fd677cb5b876f45115b428/app/src/main/kotlin/io/github/mangi/eta/agent/model/AgentToolCatalog.kt) | `9de3a3f` | 本文选取 21 个相关内部工具 | 内置 Agent 的 LLM 工具目录，**不是对外 MCP 服务清单**；适合借鉴观察和动作反馈 |

`droidrun/droidrun` 当前重定向到 `droidrun/mobilerun`，包含 Agent 框架及 MCP 客户端适配；本文没有将其 SDK 方法算作对外 MCP tool，也没有把 hanxi 的封装称为官方 MCP。

## 同类项目实际提供哪些工具

### mobile-next/mobile-mcp

下表省略共同的 `mobile_` 前缀；`get_more_tools` 除外。注册与参数依据 [server.ts](https://github.com/mobile-next/mobile-mcp/blob/0a6a38bbc384f10c64bb1029f26016735bf823a6/src/server.ts)。

| 分组 | 完整后缀清单 |
| --- | --- |
| 设备与环境，7 | `list_available_devices`、`get_screen_size`、`get_orientation`、`set_orientation`、`fold_device`、`set_location`、`clipboard` |
| 应用，6 | `list_apps`、`get_foreground_app`、`launch_app`、`terminate_app`、`install_app`、`uninstall_app` |
| 输入与导航，7 | `click_on_screen_at_coordinates`、`double_tap_on_screen`、`long_press_on_screen_at_coordinates`、`press_button`、`open_url`、`swipe_on_screen`、`type_keys` |
| 观察，3 | `list_elements_on_screen`、`take_screenshot`、`save_screenshot` |
| 开发诊断，5 | `get_device_logs`、`start_screen_recording`、`stop_screen_recording`、`list_crashes`、`get_crash` |
| 编排，1 | `batch_commands` |
| 云设备，4 | `login_to_cloud_provider`、`list_remote_devices`、`allocate_remote_device`、`release_remote_device` |
| 反馈，1 | `get_more_tools`，仅遥测开启时注册，发送能力需求反馈；不会动态生成新工具 |

关键设计：每次明确传 `device`；点击和长按支持元素 `ref` 或坐标；节点支持 `format=text/json`，精简输出省略默认状态；截图有 `maxSize/scale` 并说明坐标换算；`type_keys` 支持 `submit`。批量命令按序执行，默认遇错停止，可在结尾返回元素；它不提供本项目这样的单次观察消费协议，不能直接套用其批量方式。[输出格式源码](https://github.com/mobile-next/mobile-mcp/blob/0a6a38bbc384f10c64bb1029f26016735bf823a6/src/format-elements.ts)

### CursorTouch/Android-MCP

实际注册 14 个：`ListDevices`、`ConnectDevice`、`Device`、`Click`、`ClickBySelector`、`Snapshot`、`LongClick`、`Swipe`、`Type`、`Drag`、`Press`、`Notification`、`Wait`、`WaitForElement`。[注册源码](https://github.com/CursorTouch/Android-MCP/blob/0640a5d97d37e9add37236bad325cb88ea56d512/src/android_mcp/__main__.py)

`Snapshot(use_vision=false)` 默认只给树；`ClickBySelector` 支持 `text/resourceId/className/description`，`WaitForElement` 等待同类条件。`Device` 与两个设备工具存在功能重叠。另有一个值得警惕的接口实例：`Type(text,x,y,clear)` 虽接收坐标，当前实现只调用 `send_keys`，没有使用 `x/y` 定位。借鉴时应核对执行逻辑，不能只复制参数名。

### android-remote-control-mcp

默认前缀为 `android_`，可配置为 `android_<slug>_`。以下省略前缀；57 种工具分为 14 类。[工具参考](https://github.com/danielealbano/android-remote-control-mcp/blob/16f39717ce0969aa81a4ec132ba1cad861ba46cc/docs/MCP_TOOLS.md)、[生产注册入口](https://github.com/danielealbano/android-remote-control-mcp/blob/16f39717ce0969aa81a4ec132ba1cad861ba46cc/app/src/main/kotlin/com/danielealbano/androidremotecontrolmcp/services/mcp/McpServerService.kt)

| 分组 | 工具后缀 |
| --- | --- |
| 屏幕，1 | `get_screen_state` |
| 系统，6 | `press_back`、`press_home`、`press_recents`、`open_notifications`、`open_quick_settings`、`dismiss_keyboard` |
| 触摸，5 | `tap`、`long_press`、`double_tap`、`swipe`、`scroll` |
| 手势，2 | `pinch`、`custom_gesture` |
| 节点，5 | `find_nodes`、`click_node`、`long_click_node`、`tap_node`、`scroll_to_node` |
| 文本，5 | `type_append_text`、`type_insert_text`、`type_replace_text`、`type_clear_text`、`press_key` |
| 辅助，5 | `get_clipboard`、`set_clipboard`、`wait_for_node`、`wait_for_idle`、`get_node_details` |
| 文件，8 | `list_storage_locations`、`list_files`、`read_file`、`write_file`、`append_file`、`file_replace`、`download_from_url`、`delete_file` |
| 应用，3 | `open_app`、`list_apps`、`close_app` |
| 相机，6 | `list_cameras`、`list_camera_photo_resolutions`、`list_camera_video_resolutions`、`take_camera_photo`、`save_camera_photo`、`save_camera_video` |
| Intent，2 | `send_intent`、`open_uri` |
| 通知，6 | `notification_list`、`notification_open`、`notification_dismiss`、`notification_snooze`、`notification_action`、`notification_reply` |
| 位置，1 | `get_location` |
| 分享，2 | `get_shared_content`、`share_file_via_web` |

最有价值的部分是 `get_screen_state`：整合应用、屏幕和精简 TSV 节点，截图按需附加；大树按 200 节点分页，文字截断后可用 `get_node_details` 补全。源码明确树和截图有采集时间差。`find_nodes` 支持资源 ID 等查询；`wait_for_idle` 比较 UI 树稳定性，和动作工具等待手势完成是两个概念。[屏幕状态](https://github.com/danielealbano/android-remote-control-mcp/blob/16f39717ce0969aa81a4ec132ba1cad861ba46cc/app/src/main/kotlin/com/danielealbano/androidremotecontrolmcp/mcp/tools/ScreenIntrospectionTools.kt)、[节点](https://github.com/danielealbano/android-remote-control-mcp/blob/16f39717ce0969aa81a4ec132ba1cad861ba46cc/app/src/main/kotlin/com/danielealbano/androidremotecontrolmcp/mcp/tools/NodeActionTools.kt)、[等待与详情](https://github.com/danielealbano/android-remote-control-mcp/blob/16f39717ce0969aa81a4ec132ba1cad861ba46cc/app/src/main/kotlin/com/danielealbano/androidremotecontrolmcp/mcp/tools/UtilityTools.kt)

### PhoneUse

手机端 17 个：`get_capabilities`、`get_status`、`cancel`、`acquire_device`、`release_device`、`observe`、`list_apps`、`wait_for_change`、`tap`、`long_press`、`swipe`、`scroll`、`input_text`、`back`、`home`、`recents`、`launch_app`。[ToolCatalog.kt](https://github.com/nidhoggfgg/PhoneUse/blob/fa51691077e5f094dc2e09dcd9471f2f3e81a2bb/app/src/main/java/ai/nidhoggfgg/phoneuse/core/ToolCatalog.kt)

stdio 桥另提供 `phoneuse_connect`、`phoneuse_list_devices`、`phoneuse_call`、`phoneuse_batch`。离线初始目录只有这 4 个；配对后动态发现手机工具，同版本正常目录合计 21 个，多设备目录按工具名去重。[桥接源码](https://github.com/nidhoggfgg/PhoneUse/blob/fa51691077e5f094dc2e09dcd9471f2f3e81a2bb/tools/phoneuse_client.py)

`observe` 支持树/截图/组合、图片尺寸、区域/子树、精简决策输出及显式条件；动作必须有 `request_id`，目标操作要求 `observation_id`。`observe_after` 默认关闭；回执明确执行和业务成功的区别。`input_text` 要求编辑节点，`get_status(request_id)` 可查询保留结果。桥的批量按设备串行，遇到未知、未完成或请求的观察缺失时默认停止；并不是在手机端自动解析下一步的节点引用。

### hanxi/droidrun-agent

下表省略 `portal_` 前缀，20 个声明均由 `tools/list` 返回。[注册及分发源码](https://github.com/hanxi/droidrun-agent/blob/04cacf51b0d82f687e553801a1513ae8c6d25b15/src/droidrun_agent/mcp_server.py)

| 分组 | 工具后缀 |
| --- | --- |
| 状态，9 | `ping`、`screenshot`、`get_state`、`get_state_full`、`get_a11y_tree`、`get_a11y_tree_full`、`get_phone_state`、`get_version`、`get_time` |
| 输入，6 | `tap`、`swipe`、`global_action`、`input_text`、`clear_input`、`press_key` |
| 应用，4 | `get_packages`、`start_app`、`stop_app`、`install` |
| 辅助，1 | `set_overlay_offset` |

`ping/get_a11y_tree/get_a11y_tree_full/get_phone_state` 仅支持 HTTP 后端；`get_time/install` 仅支持 WS。这说明“工具被列出”和“当前设备支持”需要分开表达；本项目应让能力状态帮助模型选择可执行的动作。

### ETA：虚拟屏来源项目的内部工具

相关目录包括 `get_current_context`、`search_apps`、`launch_app`、`open_uri`、`observe_screen`；`tap`、`tap_area`、`tap_element`、`long_press`、`long_press_element`、`swipe`、`scroll`、`scroll_element`；`type_text`、`set_clipboard`、`get_clipboard`、`press_key`、`wait`、`wait_for_text`、`wait_for_package`、`open_system_panel`。[应用与观察](https://github.com/Mangi-11/Eta/blob/9de3a3f70aba497bd8fd677cb5b876f45115b428/app/src/main/kotlin/io/github/mangi/eta/agent/model/AgentContextAppToolCatalog.kt)、[手势](https://github.com/Mangi-11/Eta/blob/9de3a3f70aba497bd8fd677cb5b876f45115b428/app/src/main/kotlin/io/github/mangi/eta/agent/model/AgentGestureToolCatalog.kt)、[文本与等待](https://github.com/Mangi-11/Eta/blob/9de3a3f70aba497bd8fd677cb5b876f45115b428/app/src/main/kotlin/io/github/mangi/eta/agent/model/AgentTextSystemToolCatalog.kt)

观察默认树优先，支持 `max_nodes` 和可选截图；文本有目标 index、模式和可选提交；动作后摘要提供新的观察标识、语义变化、窗口/应用变化和精简节点。这个反馈模型适合本项目，迁移时仍需保留来源许可。[动作后摘要](https://github.com/Mangi-11/Eta/blob/9de3a3f70aba497bd8fd677cb5b876f45115b428/app/src/main/kotlin/io/github/mangi/eta/agent/tool/AgentAfterActionSummary.kt)

## 本项目对照与研判

现有工具已经覆盖租约、状态、虚拟屏生命周期、应用发现/启动、截图/节点、坐标和节点输入、文字、按键与等待。单屏隔离、手机接管、观察消费、取消和重复请求拒绝构成应保留的基础；同类工具的全手机操作接口不能直接替代这些约束。

| 能力 | 本项目现状 | 研判 |
| --- | --- | --- |
| 观察 | 每次先截图，节点需 `include_nodes=true`；可输出最多 500 节点 | 应提供树优先和精简输出，并明确截断与采集时间 |
| 目标定位 | `node_id` 已绑定观察；动作前已有窗口、节点签名校验 | 保留现有校验，补资源 ID、状态和层级字段，支持明确的唯一匹配 |
| 文字 | 已支持 Unicode、替换、按选择区插入及部分读回校验 | 优先补编辑节点目标及统一验证反馈；清空已可用 `text="", replace=true`，无需新增工具 |
| 等待 | 已有 delay、帧变化和节点包含文本 | 补节点出现/消失、应用前台和语义稳定；避免图片变化被当作业务完成 |
| 动作反馈 | Root 有 `input_finished/effect`，文字可能有 `verified`；不自动附新观察 | 统一“是否执行、是否观察、条件是否满足”；可选附动作后观察 |
| 应用 | list 最多 500 个，launch 要求 component，已有可选 URI | 补过滤/分页及包名解析；当前 URI 能力应完善，不必再造重复 `open_url` |
| 手势 | 单点点击/长按、多点路径 swipe、竖向 scroll | 先补方向/区域滚动；drag、double tap、pinch 按任务需求逐步增加 |

## P0：优先修正接口一致性并完善观察

### 1. 让 schema 与执行约束一致

当前 [McpToolRegistry.kt](../app/src/main/kotlin/io/github/lejw0925/virtualscreen/mcp/McpToolRegistry.kt) 第 54 行允许 `key_code=0..123`，但 [PrivilegedDisplayEngine.kt](../app/src/main/kotlin/io/github/lejw0925/virtualscreen/backend/PrivilegedDisplayEngine.kt) 只接受 15 个键码：`4,19,20,21,22,23,61,66,67,92,93,111,112,122,123`。长按 schema 的下限是 100 ms，执行下限是 200 ms。修正为动作对应的约束，并增加可选的 `key=back/enter/tab/...` 枚举，保留兼容键码。

`node_action(set_text)` 的 `value`、`wait(frame_changed)` 的 `fingerprint`、`wait(node_text)` 的 `text` 应在分支 schema 中表达必填；不适用参数也应在 schema 中拒绝。现有 `allOf` 已表达部分分支必填，改造应补齐，不能退化成只靠说明文字。为关键工具补 `title`、参数说明和 `outputSchema`，后者是 MCP 支持的输出契约，不能将其缺失称为协议违规。[MCP 工具规范](https://modelcontextprotocol.io/specification/2025-11-25/server/tools)

验收：schema 与执行层的枚举、范围和分支必填一致；显示尺寸、权限及实时状态仍需运行时校验；不支持的值在派发输入前拒绝，纯参数错误不消耗观察。

### 2. 把 observe 做成统一状态入口

建议新增可选 `mode=tree/screenshot/both`、`detail=compact/full`、`max_nodes`、`max_dimension`、`region`。旧调用维持原有行为，新客户端显式选择模式。树不可用时明确 `nodes_status`，允许请求或显式配置图像回退；树模式的坐标操作仍要验证显示几何，不能简单删除截图步骤后放行旧坐标。

返回补充 `foreground_app`、虚拟屏尺寸/旋转、焦点/键盘状态、`truncated`、节点总量/返回量、各通道采集时间和可用性。节点增加 `resource_id`、`parent_id`、窗口、`enabled/focused/selected/checkable/checked`、支持动作及提示文字；精简输出保留可操作节点和必要标签，布尔默认值可省略。当前 [ScreenAccessibilityService.kt](../app/src/main/kotlin/io/github/lejw0925/virtualscreen/core/ScreenAccessibilityService.kt) 到达 500 节点时直接停止，未暴露截断，且输出没有上述关键状态。

先做节点预算和过滤，再考虑固定快照分页；分页不能每页重新采集却继续沿用旧观察。缩放/裁剪截图必须返回图像到显示坐标的映射，或者接受坐标空间并由服务转换。树和截图分别采集，应报告时间差，不能宣称严格原子快照。

验收：普通表单可通过树完成选择；Canvas 可走截图；截断可识别；裁剪、缩放、旋转后的坐标准确；失效节点不能用于后续动作。图像 token 与文本 token 分开记录，收益通过任务测量确认。

### 3. 统一结果，并可选附动作后观察

为 `pointer/node_action/input_text/press_key/applications(launch)` 增加默认关闭的 `observe_after` 和有界观察选项。先消费旧观察、执行一次，再验证租约/epoch，按请求采集新观察。手机接管或观察失败时，保留“动作可能已执行”的结果，不能重发输入。

保留原有 `ok/code`，新增一致的执行状态、效果验证、观察状态和条件结果。`effect=unconfirmed` 与“系统接受输入”不能展示成业务成功；`text_verified` 只能由读回证据产生。现有 [RootInputInjector.kt](../app/src/main/kotlin/io/github/lejw0925/virtualscreen/backend/RootInputInjector.kt) 和文字输入已有部分证据字段，应统一而非抹掉。

对于连续 5 次动作、每次都需要观察的流程，当前约需 `1+5×2=11` 次工具调用；附动作后观察可降为 `1+5=6` 次，减少约 45% 的调用数。这是调用链推算，**不是实测延迟或 token 节省**；任务等待与采集成本依然存在。

## P1：增强目标、等待和断线后的判断能力

| 工具 | 建议参数/行为 | 实现边界与验收 |
| --- | --- | --- |
| `node_action` | `node_id` 或 `selector={resource_id,text,description,...}`，仍要求观察标识 | 在该观察的目标集合中匹配，唯一结果才执行；多结果返回候选。执行前继续验证实时签名和显示，不默认点第一项 |
| `input_text` | 可选 `node_id`、明确 `mode=insert/replace`、显式 `submit=false` | 保留旧 `replace`；目标校验、写入、读回分别报告。提交前再验证焦点；写入成功但提交失败时不能重复写入 |
| `wait` | `node_present/node_absent/app_foreground/ui_stable`，选择器、超时、稳定窗口 | 返回 `matched/timed_out/unavailable` 与新观察。稳定表示短时无变化，业务就绪用具体节点/应用条件验证；树条件尽量避免每轮 JPEG 捕获 |
| `applications` | `query/package/offset/limit`；launch 可选包名解析 | 保留 component 与 URI；多个候选不自动启动。URI 解析和启动须限定目标虚拟屏，跨屏重启继续手机确认 |
| `pointer` | scroll 的 `direction/region/amount`，可选 double tap、drag 的持续时间 | 明确“内容滚动方向”和“手指移动方向”；drag 需按住时序，不能只把 swipe 政名。用 fixture 验证真实效果 |
| `service_status` | 显示配置、前台应用、具体支持动作、权限原因、恢复提示；可选查询操作回执 | 维护按 MCP 客户端绑定的有界结果缓存，查询不触发输入；不向其他客户端泄漏回执 |

操作回执值得单独设计：当前 [McpServerController.kt](../app/src/main/kotlin/io/github/lejw0925/virtualscreen/mcp/McpServerController.kt) 记录已见请求 ID 并拒绝重复，却没有结果查询。响应丢失后，客户端应能查询 `operation_id` 的 `not_executed/executed/unknown`。查询标识必须在执行前由客户端提供或使用其请求 ID；不能只放在可能丢失的响应里。缓存过期、服务重启后返回 unknown，不承诺跨重启 exactly-once。

### 建议接口草案：一次动作接上一次新观察

以下参数与结果是建议设计，当前版本尚不支持。先用 `observe(mode="tree", detail="compact")` 取得 `o1` 和节点，再执行：

```json
{
  "name": "node_action",
  "arguments": {
    "action": "click",
    "node_id": "o1:7",
    "observation_id": "o1",
    "lease_token": "<current-lease>",
    "purpose": "打开搜索输入框",
    "observe_after": true,
    "observation_options": {"mode": "tree", "detail": "compact"}
  }
}
```

建议结果保留 `ok/code`，并补充：

```json
{
  "ok": true,
  "execution_status": "executed",
  "effect": "unconfirmed",
  "observation_status": "available",
  "observation": {"observation_id": "o2", "nodes": []}
}
```

示例省略实际节点内容。客户端用 `o2` 选择下一步；只有新观察显示搜索框已打开，才认为目标达成。动作已执行但采集失败时，执行状态应保留，并给出观察失败原因；不能把两者合并成一个可安全重试的失败。

## P2：有证据后增加编排与高级手势

先用上述增强保持 10 个工具。只有统计显示调用往返仍是主要瓶颈，再评估第 11 个 `sequence`：小规模、有界、按序、默认遇错停止，每步在执行前验证租约、显示与目标，并从内部新观察解析下一步，不能让全部步骤共用已消费的观察标识。结果要列出已执行、未执行和不确定步骤，取消也不得重播。

pinch/多指、录屏、OCR、差分树按实际任务引入。OCR 定位结果应区分于原生节点，只能作为带坐标与置信信息的视觉证据。多设备编排优先放在客户端桥中，保留手机端单屏模型。

文件、Shell、通知、位置、相机、安装卸载和全局 HOME/POWER 属于其他样本的全设备或开发工具定位。它们不能改善虚拟屏内的观察与输入闭环，且会扩大控制范围；本轮建议继续以虚拟屏操作为主，不因竞品有这些工具就加入。通用系统剪贴板也不应作为默认新能力；现有 Unicode 输入优先完善。

## 推荐推进顺序与验证方案

1. 修复 schema 不一致，补参数说明和结果契约；保持现有工具名和旧调用兼容。
2. 实现精简状态观察、节点状态/资源 ID、截断与坐标映射；再接入唯一选择器和目标文字输入。
3. 统一执行结果，增加可选动作后观察与条件等待；完善断线回执查询。
4. 以数据决定方向滚动、高级手势和 sequence 的必要性。

验证沿用 JUnit/Robolectric、`device-fixture` 和 [MCP 验证脚本](../scripts/verify-mcp.py)。使用已连接的 Root 真机覆盖：重复标签选择、不可用节点、Unicode/选择区替换、页面异步加载、树缺失时图像操作、旋转/裁剪、响应丢失、取消中途手机接管和显示重建。新功能还需分别验证 Shizuku 能力，Root 的结果不能代表 Shizuku。

每个固定任务记录成功率、首次目标命中率、错误输入次数、工具往返数、p50/p95 耗时、文本/图像用量和 unknown 结果比例。与当前实现用同一 fixture、同一流程对照；性能目标先建立基线再设阈值。硬约束始终是：旧观察被拒绝、输入不落到主屏、手机接管可终止后续操作、跨屏重启不绕过手机确认。

## 0.0.1 落地情况

上文是固定源码版本与优化前本项目接口的调研快照；建议接口草案不是最终 API。最终参数以 [MCP 文档](MCP.md) 和 `tools/list` 为准。

| 调研方向 | 0.0.1 实现 |
| --- | --- |
| schema 与运行限制一致 | 15 个显示限定按键；长按至少 200 ms；set_text 必填 value；条件等待必填对应参数 |
| 低成本观察 | `mode=tree/screenshot/both`、`format=compact/full`、状态和层级属性、500 节点 / 40 层截断提示 |
| 坐标与图像 | `max_image_width`、输出图像尺寸和 `image_to_display_scale`；输入始终使用显示像素 |
| 歧义与新鲜目标 | 同一观察缓存同一节点树；精确唯一 selector；实时目标签名复核；截断或多结果拒绝 |
| 输入反馈 | 四类输入可选 `observe_after`；接受与效果分别报告；后续观察失败不重放输入 |
| 条件等待 | 节点存在 / 不存在、文本、前台应用、帧变化、500 ms 稳定；节点条件不截图 |
| 恢复判断 | service_status 查询当前会话最近 32 个原 JSON-RPC 请求回执；取消和未知效果要求重新观察 |
| 应用发现 | query / package 过滤；保留显式 component 启动与手机重启确认 |

目标文字编辑可用 `node_action(action=set_text)`，保留 `input_text(replace)` 兼容入口。分页、包名自动解析、双击 / drag、多指、sequence、OCR 尚未加入；目前证据不足以证明增加这些接口能改善本次 fixture 工作流。没有扩大到全设备 Shell、文件或 Home / Power 控制。真实效果与采样数据见 [发行验证](RELEASE_VALIDATION.md)。

# MCP 接入

## 服务与认证

首页“MCP 服务”开关或设置 → MCP 连接可以开关服务。开启前检查 Root / Shizuku、通知、画中画和后台运行权限；缺失时弹窗提供授权入口。授权 Root 或 Shizuku 后自动启用虚拟屏节点服务，并移除本服务的系统无障碍快捷按钮，保留其他服务。默认地址为 `http://127.0.0.1:8732/mcp`；通过 ADB 使用 `adb forward tcp:8732 tcp:8732` 即可在电脑访问。开启局域网访问后设置页显示手机的局域网地址。

“权限与后端”页显示系统无障碍授权及服务连接状态，并提供 Root / Shizuku 一键授权和系统设置入口。自动写入设置后会等候服务绑定，必要时仅重绑本应用服务；若绑定仍未成功，会提示手动启用。Android 16 QPR1 / API 36.1 及更高版本可选择允许原生 Live Updates：有活动虚拟屏会话时，运行通知会请求显示状态栏 Chip，包含当前远程、本机、Agent 操作或待审批状态；划掉可收起当前会话的胶囊。空闲 MCP 使用普通前台通知。实时通知开关、Chip 是否显示及设备厂商的展示策略由 Android 系统控制。

胶囊图标按状态使用手指操作、电脑屏幕、手机、审批盾牌或警告标识。展开通知显示当前控制方的最新 purpose（包括已完成操作），不显示其他客户端的目的；控制权和跨屏重启审批优先显示申请人、操作目的、审批提示和对应按钮。活动虚拟屏提供带图标的“人工接管”“关闭虚拟屏”，关闭只释放对应会话的虚拟屏，不停止 MCP 或公网隧道；旧通知不能关闭新会话。Android 17 额外请求图标加文字的按钮样式，低版本由系统模板决定。

原生 Live Updates 不允许自定义 RemoteViews，无法为系统模板的 purpose 设置跑马灯。当前使用 BigTextStyle 保留完整原文并交给系统多行展开；实际显示行数仍受系统通知高度限制。

服务启动独立于界面生命周期，后台保持前台通知；仅执行设备操作时持有有超时保护的 CPU 唤醒锁，空闲监听及等待手机审批时释放。没有虚拟屏时不轮询后端；系统回收进程后恢复已开启的 MCP。手动关闭服务或系统强行停止仍会停止运行。部分 ROM 还需在应用后台权限中允许自启动。

每个请求都需要随机 Bearer Token。初始化成功后保存响应的 `Mcp-Session-Id`，后续请求携带该标识与协商的 `MCP-Protocol-Version`。轮换 Token 会立即使旧 Token 失效并断开所有客户端。浏览器 Origin 默认拒绝；面向原生 MCP HTTP 客户端使用。

复制的客户端配置示例（用手机生成的 Token 替换占位符）：

```json
{
  "mcpServers": {
    "android-virtual-screen": {
      "type": "http",
      "url": "http://127.0.0.1:8732/mcp",
      "headers": {"Authorization": "Bearer <phone-generated-token>"}
    }
  }
}
```

使用 Streamable HTTP 的 JSON 响应方式。POST 的 `Accept` 必须包含 `application/json, text/event-stream`；经过认证的协议 GET 返回 405，不提供 SSE 推送流。浏览器访问根地址显示连接说明，访问 `/mcp` 的 HTML 请求跳转到说明页；页面不包含 Token，MCP POST 的认证要求不变。支持 2025-11-25、2025-06-18、2025-03-26；不支持的版本在初始化时协商为服务器支持版本。初始化后发送 `notifications/initialized`；`DELETE /mcp` 关闭当前会话；`notifications/cancelled` 取消指定请求。

## Minis 接入

手机上的 Minis 1.15-beta.4 已通过原生 `minis-mcp-cli` 验证。设置 → MCP 集成中使用 `http://127.0.0.1:8732/mcp`，保留 `Authorization`，在请求头新增一行：

```text
MCP-Protocol-Version: 2025-06-18
```

此版 Minis 协商 `2025-06-18`，会保存会话 ID，但未自动发送协议版本头，导致后续调用 HTTP `400`。保存后运行 `minis-mcp-cli tools android-virtual-screen --refresh` 重新握手。其他客户端应发送自己实际协商的版本。会话过期出现 `404` 时，此版 Minis 也可用 `--refresh` 重新连接。

## 会话自动回收

未完成 `notifications/initialized` 的会话闲置 1 分钟回收；完成握手的会话闲置 5 分钟回收。有效请求会刷新活动时间，执行中的请求及仍持有有效控制租约的会话受到保护，空闲计时从请求完成后开始。服务每 15 秒清理一次，新连接和会话请求到来时也会先清理过期会话。

最多保留 32 个会话；清理后仍满员时，初始化返回 HTTP `503` 和 `Retry-After: 15`。回收释放会话、回执及审批记录，保留虚拟显示。过期会话返回 HTTP `404`，客户端应重新 `initialize` 和发送 `notifications/initialized`，再申请控制权并重新观察；不能重放之前的输入。

## 工具

工具的机器名称稳定，说明、结果消息和 `purpose` 的语言提示跟随手机系统语言；与用户选择的 App 界面语言分别处理。简中、繁中、英文均有对应说明。每个工具必填 `purpose`，长度 1–300，表达本次操作要完成什么。

| 工具 | 操作 |
| --- | --- |
| `acquire_control` | 申请五分钟独占控制权，不传 Token |
| `release_control` | 使用必填 `lease_token` 释放当前控制权 |
| `request_control` | `request` 申请占用接管；`status` 查询审批；`cancel` 取消 |
| `get_status` | 查看后端、能力、虚拟屏及控制方；无需租约 |
| `get_request_receipt` | 必填 `request_id` 查询当前会话的请求回执；无需租约 |
| `create_screen` | 创建虚拟屏，可指定宽、高、DPI、旋转、熄屏和空闲参数 |
| `configure_screen` | 重建已有虚拟屏，使旧观察失效；未指定参数保留 |
| `close_screen` | 关闭虚拟屏，仅接受租约和 purpose |
| `list_apps` | 只读查询，可按 `query` 或 `package` 过滤 |
| `launch_app` | 必填 `component`，可选 `uri`；必要时等待手机重启审批 |
| `observe` | 默认 `mode=tree`、`format=compact`；可显式请求 `screenshot/both` 或 `full` |
| `tap_at` | 必填 `x/y`；不接受滑动路径、时长或滚动量 |
| `long_press_at` | 必填 `x/y`，可选 `duration_ms`，200–2000 ms |
| `swipe_path` | 必填 `points`（2–64 个 `[x,y]`），可选 `duration_ms` |
| `scroll_at` | 必填 `x/y/delta`；delta 为 -20 到 20 的非零整数 |
| `input_text` | Unicode 输入，`replace` 替换当前编辑框内容 |
| `node_action` | 点击、长按、聚焦、滚动、设置文字 |
| `press_key` | 显示限定的导航和编辑按键，默认返回键 4 |
| `wait` | 延迟、帧变化、节点文本、存在 / 不存在、前台应用、稳定状态；最长 30 秒 |

共 19 个工具。原 `control/screen/applications/pointer/service_status` 已拆分，客户端升级后需刷新工具发现。`get_status/get_request_receipt/list_apps/observe/wait` 提供只读提示，启动和输入操作不提供只读提示。

以 `tools/list` 返回的 schema 为准。不能把其他 action 的参数混入当前操作，不能调用主屏 Home / Power 等全局按键。

## 调用顺序

1. `initialize` → `notifications/initialized` → `tools/list`。
2. `acquire_control(purpose=...)`，保存 `lease_token`。每个带租约的操作会自动把有效期顺延到操作时刻后五分钟；最后一次操作后五分钟租约失效，无需手动续租。租约到期或主动释放时，远程画中画自动收起；虚拟显示保留，再次取得控制时恢复预览。用户展开全屏接管后的本地预览按手机操作处理。
3. 用 `create_screen` 创建屏幕，或先查询现有状态。默认使用手机可用显示区域的像素分辨率和 DPI，保持应用实际密度；只指定分辨率时同比缩放 DPI，显式 `density` 则覆盖自动计算。设置中可保留自定义参数。
4. 发现并启动应用。跨屏冲突时，`launch_app(component=...,lease_token=...,purpose=...)` 保持等待，手机显示原生悬浮审批通知；用户允许后继续启动并返回最终结果，客户端不能自动强制重启。
5. `observe` 省略 `mode` 时返回 compact 节点树，不采集截图；需要视觉信息时显式用 `mode=both`。节点不可用时，`tree` 返回节点错误且不会自动截图，`both` 仍可返回截图和 `nodes_error`。`screenshot` 无节点；旧 `include_nodes=true/false` 分别等同 `both/screenshot`，不能与 `mode` 同时传入。
6. 每次输入携带租约与当前观察标识；会使观察失效的输入、启动应用和重配置显示操作默认在结果中附带 `tree` 模式的新 compact 观察，也可指定 `observe_after=tree/screenshot/both`，或用 `observe_after=none` 关闭。手机接管、取消、释放控制权或后端丢失时没有可用的新观察，需按错误中的恢复工具重新申请和观察。
7. 完成后 `release_control(lease_token=...,purpose=...)`，再 DELETE 当前 MCP 会话。

手机系统语言为简体中文时，一次点击的参数示例：

```json
{
  "name": "tap_at",
  "arguments": {
    "x": 200,
    "y": 350,
    "lease_token": "<current-lease>",
    "observation_id": "<fresh-observation>",
    "purpose": "打开应用的搜索框，查找要查看的内容"
  }
}
```

## 控制交接与失败处理

跨屏应用重启使用独立的高重要性系统通知，显示客户端、目标应用和 `purpose`，提供“允许重启”“拒绝”。无需打开虚拟屏 App；不会提前返回 `APP_RESTART_CONFIRMATION_REQUIRED`。等待审批最长两分钟；拒绝或划掉通知返回 `APP_RESTART_DENIED`，未确认返回 `APP_RESTART_TIMEOUT`，均不结束原应用。系统通知权限或审批通知类别被关闭时返回 `NOTIFICATION_PERMISSION_REQUIRED`。

允许后保留发起客户端的租约，校验原显示会话再重启；关闭虚拟屏、接管、取消、断开会话或租约失效会撤销旧审批。每次只处理一个应用启动请求，并行启动返回 `APP_LAUNCH_BUSY`。启动调用的客户端超时应至少设为 150 秒，覆盖两分钟审批和后端执行；Minis 当前的五分钟超时满足要求。

开启 MCP 即允许远程控制。本地触摸立即接管并使远程旧观察失效；手势事件处理完成后自动释放本地租约。远程客户端须重新申请租约并观察，不能重放先前的输入。

收到 `CONTROL_BUSY` 时调用 `request_control(action=request,purpose=...)`，手机会显示当前客户端名称与操作目的，以及“允许一次”“始终允许”“拒绝”。返回 `approval_status=pending` 和 `approval_id` 后，每秒调用 `request_control(action=status,approval_id=...,purpose=...)`；获批结果包含新的 `lease_token`，必须重新观察。审批最长两分钟，过期、取消、断开会话或控制方改变后失效。正在进行的手机手势不能被审批打断。

“始终允许”仅允许持有当前 Bearer Token 的已认证客户端申请接管手机占用；争用另一 Agent 的控制权仍需通知审批。可在 MCP 设置中撤销，轮换 Token 会清除此授权。批准旧通知不会接管后续会话。

工具结果包含 `structuredContent`，其中有 `ok`、`code`、`session_id`、`language`、`purpose` 与具体结果。截图在 `content` 中以 `image/jpeg` 返回。`CONTROL_BUSY`、`LEASE_REQUIRED`、`STALE_OBSERVATION`、能力缺失和后端死亡均返回明确代码。

同一 MCP 会话重复使用请求 ID 会得到 `-32001`，不会重新执行输入。取消返回 `-32800`；网络恢复后查询状态和回执并重新观察，不能假定上一次输入没有执行。每个会话最多接受 2048 个唯一请求 ID，达到上限后初始化新会话。

## 观察与目标选择

省略 `mode` 时默认只返回 compact 节点树且不采集图片；`mode=tree` 可显式表达同一行为，`screenshot` 只返回图像，`both` 同时返回图像和树。旧参数 `include_nodes=true` 等同于 `both`，`false` 等同于 `screenshot`；该参数不能与 `mode` 同时传入。tree 模式节点不可用时返回错误，不会退回截图；both 模式仍可返回截图和 `nodes_error`。

`format=compact` 为默认值。它保留可见的文字、description、hint、stateDescription、交互目标，以及 focused/selected 节点；禁用但含阅读内容的节点也保留，纯 resource ID 结构节点会省略。省略节点后，`parent_id` 指向同窗口最近的保留祖先。无名称交互容器可从同窗口、可见且非独立交互后代的文字或 hint 得到 `label`；不会穿过其他交互目标或密码子树，也不会移除原文字节点。compact 省略空文字和默认状态键（`enabled/visible=true`，其余布尔值为 false）；依赖每个状态键都存在的客户端应改用 `full`。

`full` 返回系统提供且预算内采集到的全部节点，包括结构节点、不可见节点和原始父子关系；它不绕过系统隐藏节点或隐私处理。节点保留完整类名、资源 ID、bounds、逐节点 `package`、`window_id`、`hint_text`、`state_description`、`long_clickable`、`focusable`、支持动作 `actions` 及原始 `action_list`。两种格式都有 `display_id` 和 `windows`；每个窗口含 ID、display ID、类型、包名、标题、layer、focused、active。顶层 `package` 保持排序后首个根节点的包名语义。

密码节点的 `text` 与 `state_description` 始终隐藏。`node_count` 表示本次实际采集数量，不表示系统树总量；`returned_node_count` 是本次格式输出数量。安全上限为 5000 节点、深度 100、每节点 1000 个子项；触发上限会返回 `truncated=true`、`truncation_reasons` 和采用的三个 limit 字段。未截断的 full 必须满足两个计数相等。

节点 ID 只属于该观察；新的观察替换旧缓存。`node_action` 要求 `node_id` 与 `selector` 二选一；后者支持 `resource_id/text/description/class` 的精确 AND 匹配。只能唯一匹配一项，不能默认选择第一项。多结果返回 `AMBIGUOUS_NODE`，无结果返回 `NODE_NOT_FOUND`；截断树的 selector 操作也拒绝。执行前仍会刷新并验证目标显示与签名。

```json
{
  "name": "node_action",
  "arguments": {
    "action": "set_text",
    "selector": {"resource_id": "com.example:id/search"},
    "value": "中文搜索",
    "lease_token": "<current-lease>",
    "observation_id": "<fresh-observation>",
    "observe_after": "tree",
    "purpose": "填写当前应用的搜索框并检查文字"
  }
}
```

`set_text` 的 value 可为空；`input_text(replace=true)` 也允许清空当前焦点框。文字读回确认不等同于业务提交成功。

图像宽度由 `max_image_width` 限制在 320–2048，默认 2048。结果含 `width/height`（显示尺寸）、`image_width/image_height`（图像尺寸）与 `image_to_display_scale=[x,y]`。输入坐标始终是显示像素；例如 720 宽显示缩为 360 宽图像，则图像横坐标应乘 2。节点 bounds 已是显示坐标，无需再乘。

## 输入结果与条件等待

tap_at、long_press_at、swipe_path、scroll_at、input_text、node_action、press_key、`launch_app` 和 `configure_screen` 支持 `observe_after=none/tree/screenshot/both`，默认 `tree`，返回 compact 节点树且不采集图片。结果中 `execution_status=accepted` 表示后端接受输入；`effect_status=verified/unconfirmed` 区分已读回效果与尚未确认。超时或传输不确定可能是 unknown，不能盲目重试。

输入接受后若新观察失败，仍保留 `ok=true` 与 accepted，同时返回 `observation_status=failed`、`observation_error`。输入不会重放；重新观察并用业务状态决定下一步。操作被拒绝时先处理租约 / 观察 / 参数问题。

| wait condition | 必填目标 | 行为 |
| --- | --- | --- |
| delay | 无 | 等待 timeout_ms 后返回 compact 节点树，不采集截图；默认 1000 ms |
| frame_changed | fingerprint | 与给定帧指纹不同 |
| node_text | text | 可见节点文字或描述包含目标；默认忽略大小写，case_sensitive=true 可区分 |
| node_present / node_absent | selector | 精确目标存在 / 不存在；截断树不推断不存在 |
| app_foreground | package | 目标虚拟屏应用包名匹配 |
| ui_stable | 无 | 帧指纹保持 500 ms 不变 |

除帧变化和稳定性外，条件等待只取节点。暂时缺少新应用窗口时重试观察，每次间隔 250 ms，最长 30 秒。匹配返回 `condition_matched=true` 和新观察；超时返回 `WAIT_TIMEOUT`、false 与当前观察。短时 UI 稳定不能代替业务就绪条件。

按键仅接受 `4,19,20,21,22,23,61,66,67,92,93,111,112,122,123`。长按默认 600 ms，范围 200–2000；swipe 默认 300 ms，范围 100–2000。

## 断线后的回执

工具结果返回 `request_id`，也可直接使用客户端在发送前保存的原 JSON-RPC id 字符串。在原 MCP 会话中调用 `get_request_receipt(request_id=...,purpose=...)` 返回 `receipt_found` 与 metadata receipt，包括 tool、phase、ok、code 和可用的执行 / 效果 / 观察状态。无需控制租约，查询不会重新执行原操作。

每会话保留最近 32 条、服务全局至多 1024 条，不存参数、截图或凭据；其他会话不能读取。连接恢复且原会话仍有效时可查询；DELETE、服务重启或缓存过期会丢失回执。`receipt_found=false` 表示无记录，不能据此推断“从未执行”。running / cancelled / unknown 要结合新观察判断，不承诺跨会话或跨重启 exactly-once。

## 手机端日志

设置 → 运行日志可按软件 / MCP / 隧道筛选、复制或清空。软件记录包含应用及 Activity 生命周期、前台服务、悬浮屏会话、Root / Shizuku 能力、缺少的运行权限、预览状态、后台限制与异常。MCP 和隧道记录保留请求结果、客户端名、实际连接 IP 与经过脱敏的进程输出，不记录请求参数、输入内容、截图或认证 Token。崩溃记录只保留异常类型、错误码、有限调用栈；系统最近一次进程退出原因会在下次启动后补入历史。Cloudflare 转发的来源 IP 单独标记为“代理报告 IP”，不用于认证。日志保存在 App 私有 no-backup 目录，最多三个 512 KiB 文件；界面显示最近 500 条。写盘在后台批量处理，过载时记录丢弃数量。

复制日志会一并附上 App 版本、设备型号、Android 版本 / ABI、通知与电池优化状态、无障碍服务状态、屏幕后端能力、MCP 与隧道状态及当前筛选出的记录。诊断报告由用户主动复制，应用不会上传日志。

Cloudflare edge 注册后先显示“正在验证公网连接”，只有随机 / 固定域名实际返回手机服务的连接说明页后才显示“已连接”。DNS 传播和暂时的连接失败会继续等待并记录，不会提前显示终止失败。

公网隧道页还提供实时日志区域；可在连接和重试过程中查看，停止后保留最近记录。瞬时日志错误不会将仍在重试的隧道标记为终止失败。

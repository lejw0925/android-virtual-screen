package io.github.lejw0925.virtualscreen.mcp

import android.graphics.Bitmap
import android.util.Base64
import io.github.lejw0925.virtualscreen.backend.*
import io.github.lejw0925.virtualscreen.core.*
import io.github.lejw0925.virtualscreen.ui.UiLanguage
import io.github.lejw0925.virtualscreen.ui.localizedError
import java.io.ByteArrayOutputStream
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.json.JSONArray
import org.json.JSONObject
import kotlin.coroutines.coroutineContext

data class McpTool(val name: String,val description: String,val schema: JSONObject,val readOnly: Boolean = false)
data class ToolActivity(val id: String,val owner: String,val tool: String,val purpose: String,val phase: String,val code: String? = null)

class McpToolRegistry(private val sessions: VirtualScreenSessionManager,
    private val approvals: ControlApprovalManager = ControlApprovalManager(sessions),
    private val clientName: (String) -> String = { "MCP client" },
    private val language: () -> UiLanguage = { sessions.systemLanguage }) {
    private val defaultOperationObservation = "tree"
    private val current=MutableStateFlow<ToolActivity?>(null)
    val activity=current.asStateFlow()
    private val receipts=RequestReceipts()
    fun forgetClient(owner: String) { receipts.forget(owner); approvals.forgetClient(owner) }
    private val lease=mapOf("lease_token" to string())
    private val observed=lease+("observation_id" to string())
    private val after=mapOf("observe_after" to choice("none","tree","screenshot","both").put("default",defaultOperationObservation),"max_image_width" to integer(320,2048))
    private val selector=JSONObject().put("type","object").put("minProperties",1).put("additionalProperties",false)
        .put("properties",JSONObject(mapOf("resource_id" to string(),"text" to string(),"description" to string(),"class" to string())))
    private val display=mapOf("width" to integer(320,4096),"height" to integer(480,4096),"density" to integer(120,640),"rotation" to integer(0,3),
        "allow_screen_off" to boolean(),"idle_timeout_minutes" to integer(0,240))
    private val xy=mapOf("x" to integer(0,4095),"y" to integer(0,4095))
    // Generate descriptions for every tools/list so a system language change takes effect immediately.
    val tools: List<McpTool> get() {
        val l=language()
        fun t(h: String,e: String,z: String)=l.text(h,e,z)
        return listOf(
            tool(l,"acquire_control",t("申请独占控制权，无需 lease_token。每个带租约的操作自动续期，闲置五分钟释放控制并收起远程画中画。身份绑定当前 MCP 会话。","Acquire exclusive control for this MCP session. No lease_token is needed. Each lease-authenticated operation refreshes the five-minute idle deadline; expiry releases control and hides the remote PiP.","申請獨佔控制權，無需 lease_token。每個帶租約的操作自動續期，閒置五分鐘釋放控制並收起遠端子母畫面。身分繫結目前 MCP 工作階段。")),
            tool(l,"release_control",t("释放当前会话的控制权并收起远程画中画，使旧租约和观察失效。","Release this session's control, hide the remote PiP, and invalidate its lease and observations.","釋放目前工作階段的控制權並收起遠端子母畫面，使舊租約和觀察失效。"),lease,lease.keys),
            tool(l,"request_control",t("控制权被手机或其他 Agent 占用时，发送通知申请接管。用户可允许一次或始终允许；立即返回 pending，不阻塞服务。用 action=status 和 approval_id 每秒查询，获批后使用新 lease_token 并重新观察。始终允许仅自动接管手机占用，不能绕过其他 Agent 的审批。","Request occupied control through a phone notification. The user can allow once or always. Returns pending immediately; poll action=status with approval_id once per second. Approval returns a new lease_token; observe again. Always allow applies only to phone occupancy; competing agents still require approval.","控制權被手機或其他 Agent 佔用時，傳送通知申請接管。使用者可允許一次或始終允許；立即傳回 pending。用 action=status 和 approval_id 每秒查詢，獲批後使用新 lease_token 並重新觀察。始終允許僅自動接管手機佔用，其他 Agent 仍需審批。"),
                mapOf("action" to choice("request","status","cancel"),"approval_id" to string(128)),setOf("action"),
                branches=mapOf("status" to setOf("approval_id"),"cancel" to setOf("approval_id"))),
            tool(l,"get_status",t("查看状态、后端能力和当前控制方，无需租约。","Read status, backend capabilities and the current controller without a lease.","檢視狀態、後端能力和目前控制方，無需租約。"),readOnly=true),
            tool(l,"get_request_receipt",t("用原 JSON-RPC id 字符串查询当前 MCP 会话最近 32 个请求的回执，无需租约。查询不会重发输入。","Look up one of this MCP session's last 32 request receipts by the original JSON-RPC id string. No lease required; never replays input.","用原 JSON-RPC id 字串查詢目前 MCP 工作階段最近 32 個請求的回執，無需租約。查詢不會重播輸入。"),mapOf("request_id" to string(128).put("minLength",1)),setOf("request_id"),readOnly=true),
            tool(l,"create_screen",t("创建唯一虚拟屏，默认匹配手机可用分辨率和 DPI。只指定宽或高时按比例计算另一边及 DPI。需要控制租约。","Create the isolated display using the phone viewport and DPI by default. A width or height override scales the other dimension and density. Requires control.","建立唯一虛擬螢幕，預設符合手機可用解析度和 DPI。只指定寬或高時按比例計算另一邊及 DPI。需要控制租約。"),lease+display,lease.keys),
            tool(l,"configure_screen",t("重新配置已有虚拟屏。保留未指定参数；重建显示并使旧观察失效，默认返回重建后的新观察。","Reconfigure an existing display, retaining unspecified settings. Rebuilds the display, invalidates old observations, and returns a fresh post-rebuild observation by default.","重新設定現有虛擬螢幕。保留未指定參數；重建顯示並使舊觀察失效，預設傳回重建後的新觀察。"),lease+display+after,lease.keys),
            tool(l,"close_screen",t("关闭虚拟屏并使旧观察失效。需要当前控制租约。","Close the isolated display and invalidate its observations. Requires the current control lease.","關閉虛擬螢幕並使舊觀察失效。需要目前控制租約。"),lease,lease.keys),
            tool(l,"list_apps",t("查询可启动应用，可按 query 或 package 过滤。需要控制租约，不会启动应用。","List launchable apps, optionally filtering by query or package. Requires control; does not launch apps.","查詢可啟動應用程式，可按 query 或 package 篩選。需要控制租約，不會啟動應用程式。"),lease+mapOf("query" to string(),"package" to string()),lease.keys,readOnly=true),
            tool(l,"launch_app",t("在虚拟屏启动 component，可传 uri。启动会使旧观察失效，默认直接返回启动后的新观察；跨屏冲突时等待手机通知确认重启，最长两分钟。","Launch component on the isolated display with an optional uri. Launching invalidates the old observation and returns a fresh post-launch observation by default. Cross-display conflicts await phone restart approval for up to two minutes.","在虛擬螢幕啟動 component，可傳 uri。啟動會使舊觀察失效，預設直接傳回啟動後的新觀察；跨螢幕衝突時等待手機通知確認重新啟動，最長兩分鐘。"),lease+after+mapOf("component" to string().put("minLength",1),"uri" to string()),lease.keys+"component"),
            tool(l,"observe",t("取得新观察，默认只返回 compact 节点树，不采集截图。mode=screenshot/both 显式请求图片；format=full 返回预算内全部采集节点及完整字段。compact 保留可读内容和交互目标，并省略默认状态键。节点含窗口归属、hint 和 stateDescription；树上限为 5000 节点、100 层、每节点 1000 个子项。","Get a fresh observation. Defaults to a compact node tree without image capture. Set mode=screenshot/both to request an image; format=full returns every collected node and field within budget. Compact keeps readable content and interaction targets while omitting default state keys. Nodes include window ownership, hints and state descriptions. Tree limits: 5,000 nodes, depth 100 and 1,000 children per node.","取得新觀察，預設只傳回 compact 節點樹，不擷取截圖。mode=screenshot/both 可明確要求圖片；format=full 傳回預算內所有已擷取節點與完整欄位。compact 保留可讀內容和互動目標，並省略預設狀態鍵。節點包含視窗歸屬、hint 和 stateDescription；樹上限為 5000 個節點、100 層、每節點 1000 個子項。"),
                lease+mapOf("include_nodes" to boolean().put("description",t("旧参数：true 等同 both，false 等同 screenshot。省略 mode 时优先采用此参数。","Legacy parameter: true maps to both and false maps to screenshot when mode is omitted.","舊參數：true 等同 both，false 等同 screenshot；省略 mode 時優先採用此參數。")),"mode" to choice("tree","screenshot","both").put("default","tree"),"format" to choice("compact","full").put("default","compact"),"max_image_width" to integer(320,2048)),lease.keys,readOnly=true),
            tool(l,"tap_at",t("使用最新未消耗观察，在虚拟屏像素坐标 x/y 点击。","Tap display pixel coordinates x/y using a fresh unconsumed observation.","使用最新未消耗觀察，在虛擬螢幕像素座標 x/y 點按。"),observed+after+xy,observed.keys+xy.keys),
            tool(l,"long_press_at",t("在虚拟屏 x/y 长按，duration_ms 为200–2000，默认600。消耗当前观察。","Long press display coordinates x/y for 200–2000 ms (default 600). Consumes the observation.","在虛擬螢幕 x/y 長按，duration_ms 為200–2000，預設600。消耗目前觀察。"),observed+after+xy+("duration_ms" to integer(200,2000)),observed.keys+xy.keys),
            tool(l,"swipe_path",t("沿2–64个 [x,y] 虚拟屏像素坐标滑动，duration_ms 默认300。消耗当前观察。","Swipe along 2–64 display-pixel [x,y] points. duration_ms defaults to 300. Consumes the observation.","沿2–64個 [x,y] 虛擬螢幕像素座標滑動，duration_ms 預設300。消耗目前觀察。"),observed+after+mapOf("duration_ms" to integer(100,2000),
                "points" to JSONObject().put("type","array").put("minItems",2).put("maxItems",64).put("items",JSONObject().put("type","array").put("minItems",2).put("maxItems",2).put("items",integer(0,4095)))),observed.keys+"points"),
            tool(l,"scroll_at",t("在虚拟屏 x/y 滚动。delta 为-20至20的非零整数，正数向下。消耗当前观察。","Scroll at display coordinates x/y. delta is a nonzero integer from -20 to 20; positive scrolls down. Consumes the observation.","在虛擬螢幕 x/y 捲動。delta 為-20至20的非零整數，正數向下。消耗目前觀察。"),observed+after+xy+("delta" to integer(-20,20).put("not",JSONObject().put("const",0))),observed.keys+xy.keys+"delta"),
            tool(l,"input_text",t("向虚拟屏焦点编辑框输入 Unicode 文字。replace=true 替换全部。","Insert Unicode into the virtual display's focused editor. replace=true replaces all text.","向虛擬螢幕焦點編輯框輸入 Unicode 文字。replace=true 取代全部。"),observed+after+mapOf("text" to string(20000),"replace" to boolean()),observed.keys+"text"),
            tool(l,"node_action",t("对当前观察中的节点操作。node_id 和 selector 二选一；selector 字段为精确匹配，必须唯一，歧义或截断会拒绝。set_text 必填 value，可设为空。不会降级到主屏。observe_after 可返回新观察，接受输入后观察失败不会重放输入。","Act on the current observation. Supply exactly one of node_id/selector. Selector fields match exactly and must identify one node; ambiguity/truncation is rejected. set_text requires value and allows empty text. No phone-display fallback. observe_after returns a fresh observation; a failed observation never replays accepted input.","對目前觀察中的節點操作。node_id 和 selector 二選一；selector 欄位為精確比對，必須唯一，歧義或截斷會拒絕。set_text 必填 value，可設為空。不會降級到主螢幕。observe_after 可傳回新觀察，接受輸入後觀察失敗不會重播輸入。"),
                observed+after+mapOf("node_id" to string(),"selector" to selector,"action" to choice("click","long_click","focus","scroll_forward","scroll_backward","set_text"),"value" to string(20000)),observed.keys+"action",branches=mapOf("set_text" to setOf("value"))),
            tool(l,"press_key",t("发送虚拟屏导航或编辑按键。默认返回键 4；禁止电源、主屏等全局按键。","Send a display-scoped navigation or editing key. Default: Back (4). Global power/home keys are rejected.","傳送虛擬螢幕導覽或編輯按鍵。預設返回鍵 4；禁止電源、主螢幕等全域按鍵。"),observed+after+("key_code" to integer(4,123).put("enum",JSONArray(DisplayKeys.codes))),observed.keys),
            tool(l,"wait",t("最多等待 30 秒。delay 返回 compact 节点树且不截图；frame_changed 需 fingerprint；node_text 需 text；node_present/absent 需 selector；app_foreground 需 package；ui_stable 要求画面持续稳定 500 毫秒。节点条件不截图。返回新观察及 condition_matched；超时返回当前状态，取消后重新观察。","Wait up to 30 seconds. delay returns a compact node tree without an image; frame_changed requires fingerprint; node_text requires text; node_present/absent requires selector; app_foreground requires package; ui_stable requires 500 ms of unchanged frames. Node conditions avoid screenshots. Return a fresh observation and condition_matched; timeout returns current state, and cancellation requires re-observation.","最多等待 30 秒。delay 傳回 compact 節點樹且不截圖；frame_changed 需 fingerprint；node_text 需 text；node_present/absent 需 selector；app_foreground 需 package；ui_stable 要求畫面持續穩定 500 毫秒。節點條件不截圖。傳回新觀察及 condition_matched；逾時傳回目前狀態，取消後重新觀察。"),
                lease+mapOf("condition" to choice("delay","frame_changed","node_text","node_present","node_absent","app_foreground","ui_stable"),"text" to string(),"case_sensitive" to boolean(),"fingerprint" to string(),"selector" to selector,"package" to string(),"timeout_ms" to integer(0,30000)),lease.keys,readOnly=true)
        ).onEach { spec ->
            if(spec.name=="node_action") spec.schema.put("oneOf",JSONArray(listOf(
                JSONObject().put("required",JSONArray(listOf("node_id"))).put("not",JSONObject().put("required",JSONArray(listOf("selector")))),
                JSONObject().put("required",JSONArray(listOf("selector"))).put("not",JSONObject().put("required",JSONArray(listOf("node_id")))))))
            if(spec.name=="node_action") spec.schema.getJSONArray("allOf").getJSONObject(0)
                .put("else",JSONObject().put("not",JSONObject().put("required",JSONArray(listOf("value")))))
            if(spec.name=="observe") spec.schema.put("not",JSONObject().put("required",JSONArray(listOf("include_nodes","mode"))))
            if(spec.name=="request_control") spec.schema.getJSONArray("allOf").put(
                conditional("action","request",emptyList()).also { branch ->
                    branch.getJSONObject("then").put("not",JSONObject().put("required",JSONArray(listOf("approval_id"))))
                })
            if(spec.name=="wait") {
                val fields=setOf("text","fingerprint","selector","package","case_sensitive")
                val branches=listOf("delay","frame_changed","node_text","node_present","node_absent","app_foreground","ui_stable").map { condition ->
                    val applicable=when(condition) {
                        "frame_changed" -> setOf("fingerprint")
                        "node_text" -> setOf("text","case_sensitive")
                        "node_present","node_absent" -> setOf("selector")
                        "app_foreground" -> setOf("package")
                        else -> emptySet()
                    }
                    val required=applicable-"case_sensitive"
                    val branch=conditional("condition",condition,required.toList())
                    branch.getJSONObject("then").put("not",JSONObject().put("anyOf",JSONArray((fields-applicable).map {
                        JSONObject().put("required",JSONArray(listOf(it)))
                    })))
                    if(condition=="delay") branch.getJSONObject("if").remove("required")
                    branch
                }
                spec.schema.put("allOf",JSONArray(branches))
            }
        }
    }
    suspend fun call(name: String,args: JSONObject,owner: String,requestId: String = UUID.randomUUID().toString()): JSONObject {
        val l=language()
        val purpose=args.optString("purpose").trim()
        var activity: ToolActivity?=null
        return try {
            coroutineContext.ensureActive()
            val spec=tools.firstOrNull { it.name == name } ?: error("UNKNOWN_TOOL")
            require(purpose.isNotBlank()) { "PURPOSE_REQUIRED" }
            validate(spec.schema,args)
            validateAction(name,args)
            receipts.record(owner,requestId,mapOf("tool" to name,"phase" to "running","execution_status" to "unknown"))
            activity=ToolActivity(UUID.randomUUID().toString(),owner,name,purpose,"running")
            // Other clients' status queries and rejected lease requests must not
            // replace the purpose of the operation currently controlling the screen.
            current.update { previous ->
                if(previous?.phase != "running" || (previous.owner == owner && name !in setOf("get_status","get_request_receipt"))) activity else previous
            }
            val token=args.optString("lease_token"); val observation=args.optString("observation_id")
            val result=when(name) {
                "acquire_control" -> leaseResult(sessions.acquireLease(owner))
                "release_control" -> when(val decision=sessions.releaseLease(owner,token)) {
                    is LeaseDecision.Granted -> OperationEnvelope.success("released" to true)
                    is LeaseDecision.Rejected -> OperationEnvelope.failure(decision.code)
                }
                "request_control" -> when(args.getString("action")) {
                    "request" -> approvals.request(owner,clientName(owner),purpose)
                    "status" -> approvals.status(owner,args.getString("approval_id"))
                    else -> approvals.cancel(owner,args.getString("approval_id"))
                }
                "get_status" -> {
                    val controller=sessions.leaseStatus(); val state=sessions.state.value; val c=state.capabilities
                    OperationEnvelope(true,sessionId=state.sessionId,values=mapOf("backend" to state.backend.wireName,"status" to state.status.name.lowercase(),"display_id" to state.displayId,
                        "controller" to controller?.owner,"preview_status" to state.previewStatus.name.lowercase(),"lease_remaining_ms" to controller?.let { (it.expiresAtMillis-System.nanoTime()/1000000).coerceAtLeast(0) },
                        "capabilities" to mapOf("display" to c.displayCreation.name,"capture" to c.frameCapture.name,"input" to c.inputInjection.name,"nodes" to c.accessibilityNodes.name,"screen_off" to c.screenOff.name),"error_code" to state.errorCode))
                }
                "get_request_receipt" -> {
                    val receipt=receipts.get(owner,args.getString("request_id"))
                    OperationEnvelope.success("receipt" to receipt,"receipt_found" to (receipt!=null))
                }
                "create_screen" -> sessions.create(owner,token,config(args))
                "configure_screen" -> {
                    check(sessions.state.value.displayId != null) { "NO_VIRTUAL_SCREEN" }
                    withPostObservation(owner,token,sessions.configure(owner,token,config(args)),args.optString("observe_after",defaultOperationObservation))
                }
                "close_screen" -> sessions.close(owner,token)
                    "launch_app" -> withPostObservation(owner,token,
                        sessions.launch(owner,token,args.getString("component"),args.optString("uri").takeIf(String::isNotBlank),purpose,clientName(owner)),
                        args.optString("observe_after",defaultOperationObservation))
                "list_apps" -> {
                    check(sessions.touchLease(owner,token) is LeaseDecision.Granted) { "LEASE_REQUIRED" }
                    val query=args.optString("query")
                    OperationEnvelope.success("applications" to launchableApplications(args.optString("package"), query))
                }
                "observe" -> observe(owner,token,when {
                    args.has("mode") -> args.getString("mode")
                    args.has("include_nodes") -> if(args.getBoolean("include_nodes")) "both" else "screenshot"
                    else -> "tree"
                },args.optString("format","compact"))
                "wait" -> waitFor(owner,token,args)
                else -> {
                    val operationArgs=JSONObject(args.toString())
                    if(name=="node_action" && args.has("selector")) {
                        val snapshot=sessions.nodes(owner,token,observation).getOrThrow()
                        val matches=snapshot.nodes.filter { matches(it,args.getJSONObject("selector")) }
                        check(matches.isNotEmpty()) { "NODE_NOT_FOUND" }
                        check(matches.size==1 && !snapshot.truncated) { "AMBIGUOUS_NODE" }
                        operationArgs.put("node_id",matches.single().id)
                    }
                    val action=sessions.operate(owner,token,observation,operation(name,operationArgs))
                    val outcome=action.copy(values=action.values+mapOf("execution_status" to if(action.ok) "accepted" else if(action.code in setOf("UI_INPUT_TIMEOUT","UI_MANUAL_CONTROL","INPUT_DISPATCH_UNCONFIRMED")) "unknown" else "rejected","effect_status" to if(action.values["verified"]==true) "verified" else "unconfirmed","requires_observation" to true))
                    withPostObservation(owner,token,outcome,args.optString("observe_after",defaultOperationObservation))
                }
            }
            complete(activity,if(result.ok) "completed" else "failed",result.code)
            receipts.record(owner,requestId,mapOf("tool" to name,"phase" to if(result.ok) "completed" else "failed","ok" to result.ok,"code" to result.code,"session_id" to result.sessionId)+
                result.values.filterKeys { it in setOf("execution_status","effect_status","observation_status","observation_error","observation_id","requires_observation") })
            encode(result.copy(values=result.values+("request_id" to requestId)),l,purpose,args.optInt("max_image_width",2048))
        } catch(c: CancellationException) {
            sessions.cancelActive(owner); complete(activity,"cancelled","CANCELLED")
            receipts.record(owner,requestId,mapOf("tool" to name,"phase" to "cancelled","execution_status" to "unknown","requires_observation" to true)); throw c
        } catch(e: Exception) {
            complete(activity,"failed",e.code())
            receipts.record(owner,requestId,mapOf("tool" to name,"phase" to "failed","ok" to false,"code" to e.code(),"execution_status" to if(activity!=null) "unknown" else "rejected"))
            encode(OperationEnvelope.failure(e.code(),sessions.state.value.sessionId,"request_id" to requestId),l,purpose)
        }
    }
    private fun complete(a: ToolActivity?,phase: String,code: String?) { if(a != null) current.compareAndSet(a,a.copy(phase=phase,code=code)) }
    private fun launchableApplications(packageName: String = "", query: String = "") = sessions.discoverApplications()
        .filter { it.exported && it.packageName != io.github.lejw0925.virtualscreen.BuildConfig.APPLICATION_ID &&
            (packageName.isBlank() || it.packageName == packageName) &&
            (query.isBlank() || it.label.contains(query, true) || it.packageName.contains(query, true)) }
        .map { mapOf("label" to it.label, "package" to it.packageName, "component" to it.component) }

    private suspend fun observe(owner: String,token: String,mode: String,format: String = "compact"): OperationEnvelope {
        val frame=sessions.observe(owner,token,captureFrame=mode!="tree").getOrElse { failure ->
            if (failure.code() !in setOf("NO_RUNNING_APP", "NO_VIRTUAL_SCREEN")) throw failure
            val hasDisplay = sessions.state.value.displayId != null
            return OperationEnvelope.failure("NO_RUNNING_APP", sessions.state.value.sessionId,
                "application_running" to false, "nodes" to emptyList<Any>(), "windows" to emptyList<Any>(),
                "applications" to launchableApplications(), "requires_launch" to true,
                "requires_display" to !hasDisplay,
                "recovery_tools" to if (hasDisplay) listOf("launch_app", "observe") else listOf("create_screen", "launch_app", "observe"))
        }
        if(mode=="screenshot") return frame
        val observationId = frame.values.getValue("observation_id") as String
        var nodes = sessions.nodes(owner, token, observationId)
        repeat(3) {
            if (nodes.exceptionOrNull()?.code() in setOf("NODE_WINDOW_NOT_READY", "ACCESSIBILITY_NODES_UNAVAILABLE")) {
                delay(150)
                nodes = sessions.nodes(owner, token, observationId)
            }
        }
        if(mode=="tree") nodes.getOrThrow()
        return frame.copy(values=frame.values+nodes.fold({ snapshot -> nodeValues(snapshot,format) },{ failure ->
            mapOf("nodes" to emptyList<Any>(),"nodes_error" to failure.code(),"nodes_message" to localizedError(language(),failure.code())) }))
    }
    private suspend fun withPostObservation(owner: String,token: String,result: OperationEnvelope,mode: String): OperationEnvelope {
        if(!result.ok) return result
        if(mode=="none") {
            return if(result.values.containsKey("requires_observation")) result
            else result.copy(values=result.values+mapOf("requires_observation" to true))
        }
        // Never replay an accepted input if the subsequent observation fails.
        return try {
            val next=observe(owner,token,mode,"compact")
            if (next.ok) result.copy(values=result.values+next.values+mapOf("observation_status" to "available","requires_observation" to false))
            else result.copy(values=result.values+next.values+mapOf("observation_status" to "failed",
                "observation_error" to next.code, "observation_message" to localizedError(language(), next.code ?: "BACKEND_OPERATION_FAILED"),
                "requires_observation" to true))
        } catch(c: CancellationException) { throw c }
          catch(e: Exception) { result.copy(values=result.values+mapOf("observation_status" to "failed","observation_error" to e.code(),"requires_observation" to true)) }
    }
    private fun matches(n: NodeInfo,s: JSONObject): Boolean = n.visible &&
        (!s.has("resource_id") || n.resourceId==s.getString("resource_id")) &&
        (!s.has("text") || n.text==s.getString("text")) &&
        (!s.has("description") || n.contentDescription==s.getString("description")) &&
        (!s.has("class") || n.className==s.getString("class"))
    private fun nodeValues(n: NodeSnapshot,format: String = "compact"): Map<String,Any?> = NodeTreeFormatter.values(n,format)
    private suspend fun waitFor(owner: String,token: String,a: JSONObject): OperationEnvelope {
        val condition=a.optString("condition","delay")
        if(condition == "frame_changed") require(a.optString("fingerprint").isNotBlank()) { "FINGERPRINT_REQUIRED" }
        if(condition == "node_text") require(a.optString("text").isNotBlank()) { "TEXT_REQUIRED" }
        val timeout=a.optLong("timeout_ms",1000); val deadline=System.nanoTime()/1000000+timeout
        if(condition == "delay") {
            delay(timeout)
            val observed = observe(owner,token,"tree")
            if (!observed.ok) return observed.copy(values = observed.values + ("condition_matched" to false))
            return observed.copy(values=observed.values+("condition_matched" to true))
        }
        var previous: String?=null; var stableSince=System.nanoTime()/1000000
        while(true) {
            val observed=try { observe(owner,token,if(condition in setOf("frame_changed","ui_stable")) "screenshot" else "tree") }
                catch(c: CancellationException) { throw c }
                catch(e: Exception) {
                    if(e.code() !in setOf("ACCESSIBILITY_NODES_UNAVAILABLE","NODE_WINDOW_NOT_READY","DISPLAY_FRAME_PENDING") || System.nanoTime()/1000000>=deadline) throw e
                    delay(250); continue
                }
            val now=System.nanoTime()/1000000
            if (!observed.ok) return observed.copy(values = observed.values + ("condition_matched" to false))
            val matched=when(condition) {
                "frame_changed" -> observed.values["fingerprint"] != a.getString("fingerprint")
                "ui_stable" -> {
                    val fingerprint=observed.values["fingerprint"] as? String
                    if(fingerprint!=previous) { previous=fingerprint; stableSince=now }
                    fingerprint!=null && now-stableSince>=500
                }
                "app_foreground" -> observed.values["package"]==a.getString("package")
                else -> {
                    val snapshot=sessions.nodes(owner,token,observed.values.getValue("observation_id") as String).getOrThrow()
                    if(condition=="node_text") snapshot.nodes.any { it.visible && (it.text?.contains(a.getString("text"),ignoreCase=!a.optBoolean("case_sensitive"))==true || it.contentDescription?.contains(a.getString("text"),ignoreCase=!a.optBoolean("case_sensitive"))==true) }
                    else {
                        check(!snapshot.truncated) { "NODE_TREE_TRUNCATED" }
                        val present=snapshot.nodes.any { matches(it,a.getJSONObject("selector")) }
                        if(condition=="node_present") present else !present
                    }
                }
            }
            if(matched) return observed.copy(values=observed.values+("condition_matched" to true))
            if(now >= deadline) return observed.copy(ok=false,code="WAIT_TIMEOUT",values=observed.values+("condition_matched" to false))
            delay(250)
        }
    }
    private fun config(a: JSONObject): VirtualScreenConfig {
        val c=if(sessions.state.value.displayId==null) sessions.defaultDisplayConfig else sessions.state.value.config
        val width=a.optInt("width",if(a.has("height")) (c.width.toDouble()*a.getInt("height")/c.height).toInt().coerceIn(320,4096) else c.width)
        val height=a.optInt("height",if(a.has("width")) (c.height.toDouble()*width/c.width).toInt().coerceIn(480,4096) else c.height)
        val density=a.optInt("density",(c.density.toDouble()*width/c.width).toInt().coerceIn(120,640))
        return VirtualScreenConfig(width,height,density,a.optInt("rotation",c.rotation),a.optBoolean("allow_screen_off",c.allowScreenOff),a.optInt("idle_timeout_minutes",c.idleTimeoutMinutes))
    }
    private fun operation(name: String,a: JSONObject): BackendOperation = when(name) {
        "tap_at" -> BackendOperation.Tap(a.getInt("x"),a.getInt("y"))
        "long_press_at" -> BackendOperation.LongPress(a.getInt("x"),a.getInt("y"),a.optInt("duration_ms",600))
        "swipe_path" -> a.getJSONArray("points").let { p -> BackendOperation.Swipe(List(p.length()) { i -> p.getJSONArray(i).let { it.getInt(0) to it.getInt(1) } },a.optInt("duration_ms",300)) }
        "scroll_at" -> BackendOperation.Scroll(a.getInt("x"),a.getInt("y"),a.getInt("delta"))
        "input_text" -> BackendOperation.Text(a.getString("text"),a.optBoolean("replace"))
        "press_key" -> BackendOperation.Key(a.optInt("key_code",4))
        "node_action" -> BackendOperation.NodeAction(a.getString("node_id"),a.getString("action"),a.optString("value").takeIf { a.has("value") })
        else -> error("UNKNOWN_TOOL")
    }
    private fun validateAction(name: String,a: JSONObject) {
        val action=a.optString("action")
        val forbidden=when(name) {
            "request_control" -> if(action == "request") setOf("approval_id") else emptySet()
            "node_action" -> {
                require(a.has("node_id") xor a.has("selector")) { "INVALID_NODE_TARGET" }
                if(action != "set_text") setOf("value") else { require(a.has("value")) { "MISSING_ARGUMENT" }; emptySet() }
            }
            "observe" -> { require(!(a.has("include_nodes") && a.has("mode"))) { "ARGUMENT_NOT_APPLICABLE" }; emptySet() }
            "wait" -> setOf("text","fingerprint","selector","package","case_sensitive")-when(a.optString("condition","delay")) {
                "frame_changed" -> setOf("fingerprint")
                "node_text" -> setOf("text","case_sensitive")
                "node_present","node_absent" -> setOf("selector")
                "app_foreground" -> setOf("package")
                else -> emptySet()
            }
            else -> emptySet()
        }
        require(forbidden.none(a::has)) { "ARGUMENT_NOT_APPLICABLE" }
    }
    private fun leaseResult(d: LeaseDecision)=when(d) {
        is LeaseDecision.Granted -> OperationEnvelope.success("client_id" to d.lease.owner,"lease_token" to d.lease.token,"idle_timeout_ms" to 300000,"generation" to d.lease.generation)
        is LeaseDecision.Rejected -> OperationEnvelope.failure(d.code,null,"controller" to d.currentOwner)
    }
    companion object {
        fun encode(e: OperationEnvelope,l: UiLanguage = UiLanguage.EN,purpose: String = "",maxImageWidth: Int = 2048): JSONObject {
            val data=JSONObject().put("ok",e.ok).put("code",e.code ?: JSONObject.NULL).put("session_id",e.sessionId ?: JSONObject.NULL).put("language",l.tag).put("purpose",purpose)
            e.values.filterKeys { it != "frame" && it != "message" }.forEach { (k,v) -> data.put(k,JSONObject.wrap(v)) }
            val frame=e.values["frame"] as? Bitmap
            val image=frame?.let { if(it.width>maxImageWidth) Bitmap.createScaledBitmap(it,maxImageWidth,(it.height.toDouble()*maxImageWidth/it.width).toInt().coerceAtLeast(1),true) else it }
            if(image!=null) data.put("image_width",image.width).put("image_height",image.height).put("coordinate_space","display")
                .put("image_to_display_scale",JSONArray(listOf(frame.width.toDouble()/image.width,frame.height.toDouble()/image.height)))
            data.put("message",if(e.ok) l.text("操作完成","Operation completed","操作完成") else localizedError(l,e.code ?: "BACKEND_OPERATION_FAILED"))
            if (e.code in setOf("LEASE_REQUIRED", "CONTROL_CHANGED", "LEASE_EXPIRED", "LEASE_TOKEN_INVALID")) {
                data.put("requires_control",true).put("requires_observation",true)
                    .put("recovery_tools",JSONArray(listOf("get_status","acquire_control","observe")))
            } else if (e.code == "APP_MOVED_TO_PHONE") {
                data.put("requires_relaunch",true).put("requires_observation",true)
                    .put("recovery_tools",JSONArray(listOf("launch_app","observe")))
            }
            val content=JSONArray().put(JSONObject().put("type","text").put("text",data.toString()))
            image?.let { bitmap -> val bytes=ByteArrayOutputStream(); bitmap.compress(Bitmap.CompressFormat.JPEG,80,bytes)
                content.put(JSONObject().put("type","image").put("mimeType","image/jpeg").put("data",Base64.encodeToString(bytes.toByteArray(),Base64.NO_WRAP)))
                if(bitmap!==frame) bitmap.recycle() }
            return JSONObject().put("isError",!e.ok).put("structuredContent",data).put("content",content)
        }
        private fun string(max: Int = 8192)=JSONObject().put("type","string").put("maxLength",max)
        private fun choice(vararg values: String)=string().put("enum",JSONArray(values.toList()))
        private fun boolean()=JSONObject().put("type","boolean")
        private fun integer(min: Int,max: Int)=JSONObject().put("type","integer").put("minimum",min).put("maximum",max)
        private fun conditional(field: String,value: String,required: List<String>,properties: Map<String,JSONObject> = emptyMap()) = JSONObject()
            .put("if",JSONObject().put("properties",JSONObject().put(field,JSONObject().put("const",value))).put("required",JSONArray(listOf(field))))
            .put("then",JSONObject().put("required",JSONArray(required)).put("properties",JSONObject(properties)))
        private fun tool(l: UiLanguage,name: String,description: String,properties: Map<String,JSONObject> = emptyMap(),required: Collection<String> = emptyList(),readOnly: Boolean = false,branches: Map<String,Set<String>> = emptyMap()): McpTool {
            val language=l.text("简体中文","English","繁體中文")
            val purpose=string(300).put("minLength",1).put("description",l.text("用手机系统语言（$language）说明此操作要完成什么，不要包含凭据。","Explain the goal of this operation in the phone's system language ($language). Do not include credentials.","用手機系統語言（$language）說明此操作要完成什麼，不要包含憑證。"))
            val schema=JSONObject().put("type","object").put("properties",JSONObject(properties+("purpose" to purpose))).put("required",JSONArray(required+"purpose")).put("additionalProperties",false)
            if(branches.isNotEmpty()) schema.put("allOf",JSONArray(branches.map { (action,fields) -> JSONObject().put("if",JSONObject().put("properties",JSONObject().put("action",JSONObject().put("const",action))))
                .put("then",JSONObject().put("required",JSONArray(fields.toList()))) }))
            return McpTool(name,description+" "+l.text("每次调用必须填写 purpose。","Every call requires purpose.","每次呼叫必須填寫 purpose。"),schema,readOnly)
        }
        private fun validate(schema: JSONObject,args: JSONObject) {
            val required=schema.getJSONArray("required"); repeat(required.length()) { require(args.has(required.getString(it))) { "MISSING_ARGUMENT" } }
            val props=schema.getJSONObject("properties")
            args.keys().forEach { key -> require(props.has(key)) { "UNKNOWN_ARGUMENT" }; validateValue(props.getJSONObject(key),args.get(key)) }
            schema.optJSONArray("allOf")?.let { branches -> repeat(branches.length()) { i ->
                val branch=branches.getJSONObject(i)
                val conditions=branch.getJSONObject("if").getJSONObject("properties")
                if(conditions.keys().asSequence().all { args.optString(it)==conditions.getJSONObject(it).getString("const") }) {
                    val then=branch.getJSONObject("then")
                    val fields=then.getJSONArray("required"); repeat(fields.length()) { require(args.has(fields.getString(it))) { "MISSING_ARGUMENT" } }
                    then.optJSONObject("properties")?.let { p -> p.keys().forEach { if(args.has(it)) validateValue(p.getJSONObject(it),args.get(it)) } }
                }
            } }
        }
        private fun validateValue(s: JSONObject,v: Any) {
            if(s.has("not")) {
                val forbidden=s.getJSONObject("not").opt("const")
                require(if(v is Number && forbidden is Number) v.toDouble()!=forbidden.toDouble() else v!=forbidden) { "INVALID_ARGUMENT" }
            }
            when(s.getString("type")) {
                "string" -> require(v is String && v.length in s.optInt("minLength",0)..s.getInt("maxLength") && '\u0000' !in v && (!s.has("enum") || (0 until s.getJSONArray("enum").length()).any { s.getJSONArray("enum").getString(it)==v })) { "INVALID_ARGUMENT" }
                "integer" -> require(v is Number && v.toDouble().isFinite() && v.toDouble()==v.toLong().toDouble() && v.toLong() in s.getLong("minimum")..s.getLong("maximum") && (!s.has("enum") || (0 until s.getJSONArray("enum").length()).any { s.getJSONArray("enum").getInt(it)==v.toInt() })) { "INVALID_ARGUMENT" }
                "boolean" -> require(v is Boolean) { "INVALID_ARGUMENT" }
                "object" -> { require(v is JSONObject && v.length()>=s.optInt("minProperties",0)) { "INVALID_ARGUMENT" }; val props=s.getJSONObject("properties"); v.keys().forEach { require(props.has(it)) { "UNKNOWN_ARGUMENT" }; validateValue(props.getJSONObject(it),v.get(it)) } }
                "array" -> { require(v is JSONArray && v.length() in s.getInt("minItems")..s.getInt("maxItems")) { "INVALID_ARGUMENT" }; repeat(v.length()) { validateValue(s.getJSONObject("items"),v.get(it)) } }
            }
        }
    }
}

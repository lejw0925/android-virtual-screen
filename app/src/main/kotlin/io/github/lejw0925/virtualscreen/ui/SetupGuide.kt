package io.github.lejw0925.virtualscreen.ui

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.github.lejw0925.virtualscreen.VirtualScreenApplication
import io.github.lejw0925.virtualscreen.backend.BackendKind
import io.github.lejw0925.virtualscreen.core.code
import kotlinx.coroutines.*
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.preference.*
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable internal fun SetupGuide(app: VirtualScreenApplication, notifications: () -> Unit = {}, scrollable: Boolean = true, onDone: () -> Unit) {
    val l=LocalLanguage.current
    val state by app.sessions.state.collectAsState()
    val server by app.mcp.flow.collectAsState()
    var page by rememberSaveable { mutableIntStateOf(0) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope=rememberCoroutineScope()
    fun task(block: suspend () -> Unit) { scope.launch {
        busy=true; error=null
        try { withContext(Dispatchers.IO) { app.awaitShutdown(); block() } }
        catch(c: CancellationException) { throw c }
        catch(e: Exception) { error=e.code() }
        finally { busy=false }
    } }
    val scroll=rememberScrollState()
    Column(Modifier.fillMaxWidth().then(if(scrollable) Modifier.verticalScroll(scroll) else Modifier).padding(horizontal=24.dp,vertical=24.dp),
        horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.spacedBy(20.dp)) {
        ScreenMark(Modifier.size(if(page==0) 128.dp else 80.dp))
        Text(l.text("${page+1} / 3 · 开始使用","${page+1} / 3 · Getting started","${page+1} / 3 · 開始使用"),style=MiuixTheme.textStyles.footnote1,color=MiuixTheme.colorScheme.primary)
        Text(when(page) {
            0 -> l.text("给 Agent 一块独立屏幕","A separate screen for your agent","給 Agent 一塊獨立螢幕")
            1 -> l.text("准备访问权限","Set up access","準備存取權限")
            else -> l.text("连接你的 Agent","Connect your agent","連線你的 Agent")
        },style=MiuixTheme.textStyles.title2,textAlign=TextAlign.Center)
        Text(when(page) {
            0 -> l.text("Agent 在虚拟屏里操作应用，你照常使用手机。画中画同步显示进度，触摸预览或通知接管即可暂停远程控制。","Your agent works in a virtual display while you use your phone. Follow progress in PiP and take over from the preview or notification.","Agent 在虛擬螢幕裡操作應用程式，你照常使用手機。子母畫面同步顯示進度，觸控預覽或通知接管即可暫停遠端控制。")
            1 -> l.text("选择已安装的 Root 或 Shizuku 并授权。无障碍服务只用于虚拟屏内的节点和文字编辑。","Authorize Root or Shizuku. Accessibility supports virtual-display nodes and text editing.","選擇已安裝的 Root 或 Shizuku 並授權。無障礙服務只用於虛擬螢幕內的節點和文字編輯。")
            else -> l.text("开启 MCP，复制连接配置到 Agent 的 MCP 设置。Agent 创建屏幕后会自动显示画中画；通知可随时接管或停止。","Enable MCP and copy the connection configuration to your agent's MCP settings. Creating a display opens PiP automatically; take over or stop from the notification.","開啟 MCP，複製連線設定到 Agent 的 MCP 設定。Agent 建立螢幕後會自動顯示子母畫面；通知可隨時接管或停止。")
        },style=MiuixTheme.textStyles.body2,color=MiuixTheme.colorScheme.onSurfaceVariantSummary,textAlign=TextAlign.Center)
        if(page==1) Card(Modifier.fillMaxWidth()) {
            WindowDropdownPreference(items=listOf("Root","Shizuku"),selectedIndex=state.backend.ordinal,title=l.text("访问方式","Access method","存取方式"),onSelectedIndexChange={ i -> task { val kind=BackendKind.entries[i]; app.sessions.selectBackend(kind); app.settings.update { it.copy(backend=kind) } } })
            ArrowPreference(title=if(state.capabilities.usable) l.text("已授权","Authorized","已授權") else l.text("授权并检测","Authorize access","授權並檢測"),summary=state.backend.wireName.uppercase(),onClick={ task { app.sessions.requestAuthorization() } })
            ArrowPreference(title=l.text("开启无障碍服务","Enable accessibility","開啟無障礙服務"),summary=l.text("推荐，用于准确定位和文字输入","Recommended for precise targeting and text input","建議，用於準確定位和文字輸入"),onClick={ app.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) })
        }
        if(page==2) Card(Modifier.fillMaxWidth()) {
            ArrowPreference(title=l.text("复制连接配置","Copy connection configuration","複製連線設定"),summary=if(server.running) l.text("粘贴到 Agent 的 MCP 设置","Paste into your agent's MCP settings","貼上至 Agent 的 MCP 設定") else l.text("先开启 MCP 服务","Enable MCP first","先開啟 MCP 服務"),onClick={ if(server.running) copy(app,app.mcp.clientConfiguration(connectionAddress(app))) })
            Column(Modifier.padding(20.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                Text(l.text("从电脑连接","Connect from your computer","從電腦連線"),style=MiuixTheme.textStyles.body1)
                Text(l.text("设置 → MCP 连接 → 局域网访问，复制手机地址","Settings → MCP connection → LAN access; copy the phone address","設定 → MCP 連線 → 區域網路存取，複製手機位址"),style=MiuixTheme.textStyles.footnote1,color=MiuixTheme.colorScheme.onSurfaceVariantSummary)
            }
        }
        error?.let { Text(localizedError(l,it),color=MiuixTheme.colorScheme.error) }
        Button(modifier=Modifier.fillMaxWidth(),colors=ButtonDefaults.buttonColorsPrimary(),enabled=!busy,onClick={
            if(page<2) page++ else if(server.running) { app.settings.completeGuide(); onDone() } else {
                notifications(); foreground(app)
                task { app.mcp.start(app.settings.flow.value.lan).getOrThrow() }
            }
        }) { Text(if(busy) l.text("正在处理…","Working…","正在處理…") else if(page==2) {
            if(server.running) l.text("完成设置","Finish setup","完成設定") else l.text("开启 MCP","Enable MCP","開啟 MCP")
        } else l.text("继续","Continue","繼續")) }
        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceEvenly) {
            if(page>0) Action(l.text("上一步","Previous","上一步")) { page-- }
            Action(if(page==2) l.text("先体验虚拟屏","Try the virtual screen first","先體驗虛擬螢幕") else l.text("稍后设置","Set up later","稍後設定")) { app.settings.completeGuide(); onDone() }
        }
    }
}

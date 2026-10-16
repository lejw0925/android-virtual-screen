package io.github.lejw0925.virtualscreen.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
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

@Composable internal fun SetupGuide(app: VirtualScreenApplication, notifications: () -> Unit = {}, scrollable: Boolean = true, onDone: () -> Unit) {
    val l=LocalLanguage.current
    val state by app.sessions.state.collectAsState()
    val server by app.mcp.flow.collectAsState()
    val toggleMcp=rememberMcpToggle(app,notifications)
    var page by rememberSaveable { mutableIntStateOf(0) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope=rememberCoroutineScope()
    fun task(block: suspend () -> Unit) { scope.launch {
        busy=true; error=null
        try { withContext(Dispatchers.IO) { app.runtimeWork { app.awaitShutdown(); block() } } }
        catch(c: CancellationException) { throw c }
        catch(e: Exception) { error=e.code() }
        finally { busy=false }
    } }
    LaunchedEffect(app) {
        withContext(Dispatchers.IO) { app.runtimeWork {
            app.awaitShutdown()
            app.sessions.initializeBackend(app.settings.flow.value.backend)
        } }
    }
    val scroll=rememberScrollState()
    Column(Modifier.fillMaxWidth().then(if(scrollable) Modifier.verticalScroll(scroll) else Modifier).padding(horizontal=24.dp,vertical=32.dp),
        horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.spacedBy(24.dp)) {
        ScreenMark(Modifier.size(80.dp),enabled=server.running)
        Text(
            text = l.text("${page+1} / 2 · 开始使用","${page+1} / 2 · Getting started","${page+1} / 2 · 開始使用"),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary
        )
        Text(when(page) {
            0 -> l.text("准备访问权限","Set up access","準備存取權限")
            else -> l.text("连接你的 Agent","Connect your agent","連線你的 Agent")
        },style=MaterialTheme.typography.headlineMedium,textAlign=TextAlign.Center,color=MaterialTheme.colorScheme.onSurface)
        if(page==0) Card(Modifier.fillMaxWidth()) {
            DropdownPreference(items=listOf("Root","Shizuku"),selectedIndex=state.backend.ordinal,title=l.text("访问方式","Access method","存取方式"),onSelectedIndexChange={ i -> task { val kind=BackendKind.entries[i]; app.sessions.selectBackend(kind); app.settings.update { it.copy(backend=kind) } } })
            ArrowPreference(title=if(state.capabilities.usable) l.text("已授权","Authorized","已授權") else l.text("授权并检测","Authorize access","授權並檢測"),summary=state.backend.wireName.uppercase(),onClick={ task { app.sessions.requestAuthorization() } })
        }
        if(page==1) Card(Modifier.fillMaxWidth()) {
            ArrowPreference(title=l.text("复制连接配置","Copy connection configuration","複製連線設定"),enabled=server.running,onClick={ if(server.running) copy(app,app.mcp.clientConfiguration(connectionAddress(app))) })
        }
        error?.let { Text(localizedError(l,it),color=MaterialTheme.colorScheme.error) }
        Button(
            onClick={
            if(page<1) page++ else if(server.running) { app.settings.completeGuide(); onDone() } else {
                toggleMcp(true)
            }
        },
            modifier=Modifier.fillMaxWidth(),
            enabled=!busy,
            shape = RoundedCornerShape(16.dp)
        ) { Text(if(busy) l.text("正在处理…","Working…","正在處理…") else if(page==1) {
            if(server.running) l.text("完成设置","Finish setup","完成設定") else l.text("开启 MCP","Enable MCP","開啟 MCP")
        } else l.text("继续","Continue","繼續")) }
        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceEvenly) {
            if(page>0) TextButton(onClick={ page-- }, shape = RoundedCornerShape(12.dp)) { Text(l.text("上一步","Previous","上一步")) }
            TextButton(onClick={ app.settings.completeGuide(); onDone() }, shape = RoundedCornerShape(12.dp)) {
                Text(if(page==1) l.text("先体验虚拟屏","Try the virtual screen first","先體驗虛擬螢幕") else l.text("稍后设置","Set up later","稍後設定"))
            }
        }
    }
}

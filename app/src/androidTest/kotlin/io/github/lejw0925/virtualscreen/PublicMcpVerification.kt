package io.github.lejw0925.virtualscreen

import kotlinx.coroutines.delay
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import org.junit.Assert.*

/** Exercises the real Streamable HTTP session across a tunnel, without device input. */
internal suspend fun verifyPublicMcp(http: OkHttpClient,url: String,token: String) {
    var session: String? = null
    var id = 0
    fun call(method: String,params: JSONObject? = null,notification: Boolean = false,bearer: String = token): Triple<Int,String,String?> {
        val json = JSONObject().put("jsonrpc","2.0").put("method",method)
        if(!notification) json.put("id",++id)
        params?.let { json.put("params",it) }
        val request = Request.Builder().url(url).header("Authorization","Bearer $bearer")
            .header("Accept","application/json, text/event-stream")
            .post(json.toString().toRequestBody("application/json".toMediaType()))
        session?.let { request.header("Mcp-Session-Id",it).header("MCP-Protocol-Version","2025-11-25") }
        return http.newCall(request.build()).execute().use { Triple(it.code,it.body.string(),it.header("Mcp-Session-Id")) }
    }
    val params = JSONObject().put("protocolVersion","2025-11-25").put("capabilities",JSONObject())
        .put("clientInfo",JSONObject().put("name","tunnel-verification").put("version","1"))
    var response = call("initialize",params)
    repeat(3) { if(response.first == 502 || response.first == 530) { delay(1500); response=call("initialize",params) } }
    assertEquals(response.second,200,response.first)
    session = response.third
    assertNotNull("Tunnel dropped the session header",session)
    try {
        assertEquals("2025-11-25",JSONObject(response.second).getJSONObject("result").getString("protocolVersion"))
        assertEquals(202,call("notifications/initialized",notification=true).first)
        assertEquals(401,call("ping",bearer="wrong").first)
        val listed = call("tools/list")
        assertEquals(listed.second,200,listed.first)
        val tools = JSONObject(listed.second).getJSONObject("result").getJSONArray("tools")
        assertEquals(19,tools.length())
        assertTrue((0 until tools.length()).any { tools.getJSONObject(it).getString("name")=="request_control" })
        for(index in 0 until tools.length()) {
            val required=tools.getJSONObject(index).getJSONObject("inputSchema").getJSONArray("required")
            assertTrue((0 until required.length()).any { required.getString(it)=="purpose" })
        }
        val status = call("tools/call",JSONObject().put("name","get_status").put("arguments",JSONObject().put("purpose","验证隧道中的 MCP 连接状态")))
        assertEquals(status.second,200,status.first)
        assertTrue(JSONObject(status.second).getJSONObject("result").getJSONObject("structuredContent").getBoolean("ok"))
    } finally {
        val close = Request.Builder().url(url).header("Authorization","Bearer $token")
            .header("Mcp-Session-Id",session!!).header("MCP-Protocol-Version","2025-11-25").delete().build()
        http.newCall(close).execute().use { assertEquals(204,it.code) }
    }
}

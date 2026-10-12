package io.github.lejw0925.virtualscreen.mcp

/** Small, session-owned metadata cache. Querying a receipt never repeats input. */
internal class RequestReceipts {
    private val entries = linkedMapOf<Pair<String,String>,Map<String,Any?>>()
    @Synchronized fun record(owner: String, requestId: String, values: Map<String,Any?>) {
        val key=owner to requestId
        entries.remove(key)
        entries[key]=values+("request_id" to requestId)
        while(entries.keys.count { it.first==owner }>32) entries.remove(entries.keys.first { it.first==owner })
        while(entries.size>1024) entries.remove(entries.keys.first())
    }
    @Synchronized fun get(owner: String, requestId: String): Map<String,Any?>? = entries[owner to requestId]?.toMap()
    @Synchronized fun forget(owner: String) { entries.keys.removeAll { it.first==owner } }
}

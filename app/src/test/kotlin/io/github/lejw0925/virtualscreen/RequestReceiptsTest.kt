package io.github.lejw0925.virtualscreen

import io.github.lejw0925.virtualscreen.mcp.RequestReceipts
import org.junit.Assert.*
import org.junit.Test

class RequestReceiptsTest {
    @Test fun receiptsAreOwnedBoundedAndRemovedOnDisconnect() {
        val receipts=RequestReceipts()
        receipts.record("first","known",mapOf("execution_status" to "accepted"))
        assertNull(receipts.get("other","known"))
        assertEquals("accepted",receipts.get("first","known")?.get("execution_status"))
        repeat(32) { receipts.record("first","request-$it",mapOf("phase" to "completed")) }
        assertNull(receipts.get("first","known"))
        assertNotNull(receipts.get("first","request-31"))
        receipts.forget("first")
        assertNull(receipts.get("first","request-31"))
    }
}

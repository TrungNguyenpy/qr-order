package com.qrorder.common.web

import com.qrorder.auth.service.UserSpecifications
import kotlin.test.assertEquals
import kotlin.test.assertNull
import org.junit.jupiter.api.Test

class ClientRequestInfoTest {

    @Test
    fun `parses IPv4 and IPv6 literals`() {
        assertEquals("127.0.0.1", ClientRequestInfo.parseIp("127.0.0.1")!!.hostAddress)
        assertEquals("203.0.113.7", ClientRequestInfo.parseIp(" 203.0.113.7 ")!!.hostAddress)
        assertEquals("0:0:0:0:0:0:0:1", ClientRequestInfo.parseIp("::1")!!.hostAddress)
        assertEquals("0:0:0:0:0:0:0:1", ClientRequestInfo.parseIp("[::1]")!!.hostAddress)
    }

    @Test
    fun `never resolves host names (no DNS lookup) and ignores garbage`() {
        listOf(null, "", "localhost", "example.com", "cafe", "12345", "1.2.3", "999.1.1.1", "1.2.3.4; DROP").forEach {
            assertNull(ClientRequestInfo.parseIp(it), "Phải bỏ qua: $it")
        }
    }

    @Test
    fun `LIKE wildcards in search keywords are escaped`() {
        assertEquals("100\\%", UserSpecifications.escapeLike("100%"))
        assertEquals("thu\\_ngan", UserSpecifications.escapeLike("thu_ngan"))
        assertEquals("a\\\\b", UserSpecifications.escapeLike("a\\b"))
    }
}

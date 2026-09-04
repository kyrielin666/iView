package ai.moying.iview.platform

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PlatformCapabilityControllerTest {
    @Test
    fun `reports JVM foundation capabilities`() {
        val response = PlatformCapabilityController().capabilities()
        assertEquals("200", response.code)
        assertEquals("iView", response.data?.product)
        assertEquals("JVM", response.data?.runtime)
        assertTrue(response.data?.capabilities?.any { it.name == "protocol-spi" } == true)
        assertTrue(response.data?.capabilities?.any { it.name == "modbus-tcp" && it.status == "in-progress" } == true)
        assertTrue(response.data?.frontendCapabilities?.any { it.name == "看板文档编辑" && it.scope == "frontend" } == true)
        assertEquals(32, response.data?.ledger?.inProgress)
    }
}

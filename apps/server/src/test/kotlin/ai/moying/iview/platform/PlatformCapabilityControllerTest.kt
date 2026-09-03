package ai.moying.iview.platform

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PlatformCapabilityControllerTest {
    @Test
    fun `reports JVM foundation capabilities`() {
        val response = PlatformCapabilityController().capabilities()
        assertEquals("iView", response.product)
        assertEquals("JVM", response.runtime)
        assertTrue(response.capabilities.any { it.name == "protocol-spi" })
        assertTrue(response.capabilities.any { it.name == "modbus-tcp" && it.status == "in-progress" })
    }
}

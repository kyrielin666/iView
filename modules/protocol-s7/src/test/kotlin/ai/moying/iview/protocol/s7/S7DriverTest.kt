package ai.moying.iview.protocol.s7

import ai.moying.iview.core.device.DeviceDefinition
import ai.moying.iview.core.device.DeviceId
import ai.moying.iview.core.device.PointAccess
import ai.moying.iview.core.device.PointDataType
import ai.moying.iview.core.device.PointDefinition
import ai.moying.iview.core.device.PointId
import kotlinx.coroutines.runBlocking
import java.net.ServerSocket
import java.time.Duration
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class S7DriverTest {
    private val driver = S7Driver()

    @Test
    fun `validates host rack slot and connection type`() {
        val result = driver.validate(device(mapOf("rack" to "8", "slot" to "32", "connectType" to "4")))
        assertFalse(result.valid)
        assertEquals(setOf("host", "rack", "slot", "connect_type"), result.issues.map { it.field }.toSet())
    }

    @Test
    fun `normalizes legacy S7 addresses`() {
        assertEquals("DB1:10:INT", s7Tag(point("DB1.10", PointDataType.INT16)))
        assertEquals("DB7:10:REAL", s7Tag(point("10", PointDataType.FLOAT32, mapOf("areaType" to "DB", "dbNumber" to "7"))))
        assertEquals("%M10.2:BOOL", s7Tag(point("10.2", PointDataType.BOOLEAN, mapOf("areaType" to "M"))))
        assertEquals("%Q4:DINT", s7Tag(point("%Q4:DINT", PointDataType.INT32)))
    }

    @Test
    fun `connection URL retains legacy rack slot and connect type`() {
        assertEquals(
            "s7://192.0.2.10:102?remote-rack=0&remote-slot=2&remote-device-group=OS&read-timeout=3000&tcp.default-timeout=3000&tcp.keep-alive=true",
            s7ConnectionUrl(mapOf("host" to "192.0.2.10", "rack" to "0", "slot" to "2", "connectType" to "2", "timeout" to "3000")),
        )
    }

    @Test
    fun `diagnosis checks configured TCP endpoint`() = runBlocking {
        ServerSocket(0).use { server ->
            thread(isDaemon = true) { server.accept().close() }
            val result = driver.diagnose(device(mapOf("host" to "127.0.0.1", "port" to server.localPort.toString())))
            assertTrue(result.successful)
        }
    }

    private fun device(values: Map<String, String>) = DeviceDefinition(DeviceId(1), "S7-1", "西门子 PLC", "s7comm", true, values)
    private fun point(address: String, type: PointDataType, properties: Map<String, String> = emptyMap()) = PointDefinition(
        PointId(1), DeviceId(1), "p1", "点位", address, type, PointAccess.READ_WRITE, Duration.ofSeconds(1), properties,
    )
}

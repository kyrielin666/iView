package ai.moying.iview.protocol.mitsubishi.mca1e

import ai.moying.iview.core.device.DeviceDefinition
import ai.moying.iview.core.device.DeviceId
import ai.moying.iview.core.device.PointAccess
import ai.moying.iview.core.device.PointDataType
import ai.moying.iview.core.device.PointDefinition
import ai.moying.iview.core.device.PointId
import ai.moying.iview.core.device.ValueQuality
import kotlinx.coroutines.runBlocking
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.ServerSocket
import java.time.Duration
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MitsubishiMcA1eDriverTest {
    private val driver = MitsubishiMcA1eDriver()

    @Test
    fun `validation reports missing host and invalid connection values`() {
        val result = driver.validate(device(mapOf("port" to "70000", "timeout" to "invalid", "monitoring_timer" to "0")))
        assertFalse(result.valid)
        assertEquals(setOf("host", "port", "timeout", "monitoring_timer"), result.issues.map { it.field }.toSet())
    }

    @Test
    fun `address parser follows legacy A series device restrictions`() {
        assertEquals("D", parseA1eAddress("D100").device)
        assertEquals(0x1a, parseA1eAddress("W1A").offset)
        assertTrue(parseA1eAddress("M42").bit)
    }

    @Test
    fun `word read frame is canonical A1E binary request`() {
        val frame = buildA1eFrame(1, parseA1eAddress("D100"), 2, timer = 10)
        assertContentEquals(byteArrayOf(0x01, 0xff.toByte(), 0x0a, 0x00, 0x64, 0x00, 0x00, 0x00, 0x20, 0x44, 0x02, 0x00), frame)
    }

    @Test
    fun `session reads register from exact A1E response`() = runBlocking {
        ServerSocket(0).use { server ->
            thread(isDaemon = true) {
                server.accept().use { socket ->
                    val input = DataInputStream(socket.getInputStream()); val output = DataOutputStream(socket.getOutputStream())
                    val request = ByteArray(12).also(input::readFully)
                    assertEquals(1, request[0].toInt())
                    output.write(byteArrayOf(0x81.toByte(), 0x00, 0x34, 0x12)); output.flush()
                }
            }
            driver.connect(device(mapOf("host" to "127.0.0.1", "port" to server.localPort.toString()))).use { session ->
                val value = session.read(listOf(point())).single()
                assertEquals(0x1234, value.value); assertEquals(ValueQuality.GOOD, value.quality)
            }
        }
    }

    @Test
    fun `PLC end code becomes bad response quality`() = runBlocking {
        ServerSocket(0).use { server ->
            thread(isDaemon = true) { server.accept().use { socket -> ByteArray(12).also(DataInputStream(socket.getInputStream())::readFully); DataOutputStream(socket.getOutputStream()).also { it.write(byteArrayOf(0x81.toByte(), 0x50)); it.flush() } } }
            driver.connect(device(mapOf("host" to "127.0.0.1", "port" to server.localPort.toString()))).use { session ->
                val value = session.read(listOf(point())).single(); assertEquals(ValueQuality.BAD_RESPONSE, value.quality); assertTrue(value.diagnostic.orEmpty().contains("0x50"))
            }
        }
    }

    private fun device(properties: Map<String, String>) = DeviceDefinition(DeviceId(1), "PLC_A1E_001", "三菱 A 系列", "mits_mc_a1e", true, properties)
    private fun point() = PointDefinition(PointId(10), DeviceId(1), "speed", "速度", "D100", PointDataType.UINT16, PointAccess.READ_WRITE, Duration.ofSeconds(1), emptyMap())
}

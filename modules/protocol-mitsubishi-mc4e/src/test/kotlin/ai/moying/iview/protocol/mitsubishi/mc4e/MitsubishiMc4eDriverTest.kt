package ai.moying.iview.protocol.mitsubishi.mc4e

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

class MitsubishiMc4eDriverTest {
    private val driver = MitsubishiMc4eDriver()

    @Test
    fun `validation reports missing host and invalid route values`() {
        val result = driver.validate(device(mapOf("port" to "70000", "timeout" to "invalid", "network_number" to "256", "module_io" to "0x10000")))

        assertFalse(result.valid)
        assertEquals(setOf("host", "port", "timeout", "network_number", "module_io"), result.issues.map { it.field }.toSet())
    }

    @Test
    fun `address parser applies MC 4E hexadecimal and decimal radices`() {
        assertEquals(Mc4eAddress("X", 0x1a, 0x9c, true), parseMc4eAddress("X1A"))
        assertEquals(Mc4eAddress("Y", 0x10, 0x9d, true), parseMc4eAddress("y10"))
        assertEquals(Mc4eAddress("D", 10, 0xa8, false), parseMc4eAddress("D10"))
        assertEquals(Mc4eAddress("W", 16, 0xb4, false), parseMc4eAddress("W10"))
    }

    @Test
    fun `read frame is canonical binary 4E request with serial and route`() {
        val frame = buildMc4eReadFrame(parseMc4eAddress("D100"), count = 2, bit = false, route = Mc4eRoute(1, 0xff, 0x03ff, 2), timer = 10, serial = 0x1234)

        assertContentEquals(
            byteArrayOf(
                0x54, 0x00, 0x34, 0x12, 0x00, 0x00,
                0x01, 0xff.toByte(), 0xff.toByte(), 0x03, 0x02,
                0x0c, 0x00, 0x0a, 0x00, 0x01, 0x04, 0x00, 0x00,
                0x64, 0x00, 0x00, 0xa8.toByte(), 0x02, 0x00,
            ),
            frame,
        )
    }

    @Test
    fun `session reads a register and checks response serial`() = runBlocking {
        ServerSocket(0).use { server ->
            thread(isDaemon = true) {
                server.accept().use { socket ->
                    val input = DataInputStream(socket.getInputStream())
                    val output = DataOutputStream(socket.getOutputStream())
                    val request = readRequest(input)
                    respond(output, serial = littleEndian(request, 2), payload = byteArrayOf(0x34, 0x12))
                }
            }

            driver.connect(device(mapOf("host" to "127.0.0.1", "port" to server.localPort.toString()))).use { session ->
                val value = session.read(listOf(point())).single()
                assertEquals(0x1234, value.value)
                assertEquals(ValueQuality.GOOD, value.quality)
            }
        }
    }

    @Test
    fun `mismatched response serial becomes bad response`() = runBlocking {
        ServerSocket(0).use { server ->
            thread(isDaemon = true) {
                server.accept().use { socket ->
                    readRequest(DataInputStream(socket.getInputStream()))
                    respond(DataOutputStream(socket.getOutputStream()), serial = 0x8888, payload = byteArrayOf(0x34, 0x12))
                }
            }

            driver.connect(device(mapOf("host" to "127.0.0.1", "port" to server.localPort.toString()))).use { session ->
                val value = session.read(listOf(point())).single()
                assertEquals(ValueQuality.BAD_RESPONSE, value.quality)
                assertTrue(value.diagnostic.orEmpty().contains("序列号"))
            }
        }
    }

    private fun readRequest(input: DataInputStream): ByteArray {
        val header = ByteArray(13).also(input::readFully)
        val length = littleEndian(header, 11)
        return header + ByteArray(length).also(input::readFully)
    }

    private fun littleEndian(data: ByteArray, offset: Int) =
        (data[offset].toInt() and 0xff) or ((data[offset + 1].toInt() and 0xff) shl 8)

    private fun respond(output: DataOutputStream, serial: Int, payload: ByteArray) {
        val bodyLength = payload.size + 2
        output.write(byteArrayOf(0xd4.toByte(), 0x00, serial.toByte(), (serial ushr 8).toByte(), 0x00, 0x00, 0x00, 0xff.toByte(), 0xff.toByte(), 0x03, 0x00, bodyLength.toByte(), (bodyLength ushr 8).toByte(), 0x00, 0x00, *payload))
        output.flush()
    }

    private fun device(properties: Map<String, String>) = DeviceDefinition(DeviceId(1), "PLC_MC4E_001", "三菱 PLC", "mits_mc_4e", true, properties)
    private fun point() = PointDefinition(PointId(10), DeviceId(1), "speed", "速度", "D100", PointDataType.UINT16, PointAccess.READ_WRITE, Duration.ofSeconds(1), mapOf("wordOrder" to "ABCD"))
}

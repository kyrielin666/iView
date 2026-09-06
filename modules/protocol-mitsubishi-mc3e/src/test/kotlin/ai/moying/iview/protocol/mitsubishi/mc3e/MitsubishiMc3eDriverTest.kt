package ai.moying.iview.protocol.mitsubishi.mc3e

import ai.moying.iview.core.device.DeviceDefinition
import ai.moying.iview.core.device.DeviceId
import ai.moying.iview.core.device.PointAccess
import ai.moying.iview.core.device.PointDataType
import ai.moying.iview.core.device.PointDefinition
import ai.moying.iview.core.device.PointId
import ai.moying.iview.core.device.ValueQuality
import ai.moying.iview.protocol.PointWrite
import kotlinx.coroutines.runBlocking
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.ServerSocket
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MitsubishiMc3eDriverTest {
    private val driver = MitsubishiMc3eDriver()

    @Test
    fun `validation reports missing host and invalid route values`() {
        val result = driver.validate(
            device(
                mapOf(
                    "port" to "70000",
                    "timeout" to "invalid",
                    "network_number" to "256",
                    "module_io" to "0x10000",
                )
            )
        )

        assertFalse(result.valid)
        assertEquals(setOf("host", "port", "timeout", "network_number", "module_io"), result.issues.map { it.field }.toSet())
    }

    @Test
    fun `address parser applies Mitsubishi address radices and device codes`() {
        assertEquals(Mc3eAddress("X", 8, 0x9c, true), parseMc3eAddress("X10"))
        assertEquals(Mc3eAddress("Y", 8, 0x9d, true), parseMc3eAddress("y10"))
        assertEquals(Mc3eAddress("B", 16, 0xa0, true), parseMc3eAddress("B10"))
        assertEquals(Mc3eAddress("W", 16, 0xb4, false), parseMc3eAddress("W10"))
        assertEquals(Mc3eAddress("D", 10, 0xa8, false), parseMc3eAddress("D10"))
        assertEquals(Mc3eAddress("CN", 10, 0xc5, false), parseMc3eAddress("CN10"))
    }

    @Test
    fun `read frame is a canonical binary 3E request`() {
        val frame = buildMc3eReadFrame(parseMc3eAddress("X10"), count = 3, bitAccess = true)

        assertContentEquals(
            byteArrayOf(
                0x50, 0x00, 0x00, 0xff.toByte(), 0xff.toByte(), 0x03, 0x00,
                0x0c, 0x00, 0x0a, 0x00,
                0x01, 0x04, 0x01, 0x00,
                0x08, 0x00, 0x00, 0x9c.toByte(), 0x03, 0x00,
            ),
            frame,
        )
    }

    @Test
    fun `word and bit write frames carry little endian words and packed nibbles`() {
        val word = buildMc3eWriteFrame(parseMc3eAddress("D100"), 1, false, byteArrayOf(0x34, 0x12))
        val bits = buildMc3eWriteFrame(parseMc3eAddress("M100"), 3, true, byteArrayOf(0x10, 0x10))

        assertEquals(14, word[7].toInt() and 0xff)
        assertContentEquals(byteArrayOf(0x01, 0x14, 0x00, 0x00), word.copyOfRange(11, 15))
        assertContentEquals(byteArrayOf(0x34, 0x12), word.copyOfRange(21, 23))
        assertEquals(14, bits[7].toInt() and 0xff)
        assertContentEquals(byteArrayOf(0x01, 0x14, 0x01, 0x00), bits.copyOfRange(11, 15))
        assertContentEquals(byteArrayOf(0x10, 0x10), bits.copyOfRange(21, 23))
    }

    @Test
    fun `session reads and writes registers over exact TCP frames`() = runBlocking {
        ServerSocket(0).use { server ->
            val requests = mutableListOf<ByteArray>()
            val failure = AtomicReference<Throwable?>()
            val completed = CountDownLatch(1)
            thread(name = "fake-mc3e", isDaemon = true) {
                runCatching {
                    server.accept().use { socket ->
                        val input = DataInputStream(socket.getInputStream())
                        val output = DataOutputStream(socket.getOutputStream())
                        requests += readRequest(input)
                        respond(output, byteArrayOf(0x34, 0x12))
                        requests += readRequest(input)
                        respond(output)
                    }
                }.onFailure(failure::set)
                completed.countDown()
            }

            driver.connect(device(mapOf("host" to "127.0.0.1", "port" to server.localPort.toString()))).use { session ->
                val point = point(
                    dataType = PointDataType.UINT16,
                    address = "100",
                    properties = mapOf("registerType" to "D", "wordOrder" to "ABCD"),
                )
                val value = session.read(listOf(point)).single()
                assertEquals(0x1234, value.value)
                assertEquals(ValueQuality.GOOD, value.quality)
                assertTrue(session.write(listOf(PointWrite(point, 0x4567))).single().successful)
            }

            assertTrue(completed.await(2, TimeUnit.SECONDS))
            failure.get()?.let { throw it }
            assertContentEquals(byteArrayOf(0x64, 0x00, 0x00, 0xa8.toByte()), requests[0].copyOfRange(15, 19))
            assertContentEquals(byteArrayOf(0x67, 0x45), requests[1].copyOfRange(21, 23))
        }
    }

    @Test
    fun `PLC end code becomes bad response quality`() = runBlocking {
        ServerSocket(0).use { server ->
            thread(isDaemon = true) {
                server.accept().use { socket ->
                    readRequest(DataInputStream(socket.getInputStream()))
                    respond(DataOutputStream(socket.getOutputStream()), endCode = 0xc051)
                }
            }

            driver.connect(device(mapOf("host" to "127.0.0.1", "port" to server.localPort.toString()))).use { session ->
                val value = session.read(listOf(point())).single()
                assertEquals(ValueQuality.BAD_RESPONSE, value.quality)
                assertTrue(value.diagnostic.orEmpty().contains("0xC051"))
            }
        }
    }

    @Test
    fun `connection is reopened once after peer disconnects`() = runBlocking {
        ServerSocket(0).use { server ->
            val accepted = CountDownLatch(2)
            thread(name = "fake-mc3e-reconnect", isDaemon = true) {
                server.accept().use { first ->
                    readRequest(DataInputStream(first.getInputStream()))
                    accepted.countDown()
                }
                server.accept().use { second ->
                    readRequest(DataInputStream(second.getInputStream()))
                    respond(DataOutputStream(second.getOutputStream()), byteArrayOf(0x2a, 0x00))
                    accepted.countDown()
                }
            }

            driver.connect(device(mapOf("host" to "127.0.0.1", "port" to server.localPort.toString()))).use { session ->
                val value = session.read(listOf(point())).single()
                assertEquals(42, value.value)
                assertEquals(ValueQuality.GOOD, value.quality)
            }
            assertTrue(accepted.await(2, TimeUnit.SECONDS))
        }
    }

    @Test
    fun `word order converts float32 in both directions`() {
        val lowWordFirst = byteArrayOf(0x00, 0x00, 0x20, 0x41)
        val highWordFirst = byteArrayOf(0x20, 0x41, 0x00, 0x00)

        assertEquals(10.0f, decodeWords(lowWordFirst, PointDataType.FLOAT32, Mc3eWordOrder.ABCD))
        assertEquals(10.0f, decodeWords(highWordFirst, PointDataType.FLOAT32, Mc3eWordOrder.CDAB))
        assertContentEquals(lowWordFirst, encodeWords(10.0, PointDataType.FLOAT32, Mc3eWordOrder.ABCD))
        assertContentEquals(highWordFirst, encodeWords(10.0, PointDataType.FLOAT32, Mc3eWordOrder.CDAB))
    }

    @Test
    fun `diagnosis tests configured TCP endpoint`() = runBlocking {
        ServerSocket(0).use { server ->
            thread(isDaemon = true) { server.accept().close() }
            val result = driver.diagnose(device(mapOf("host" to "127.0.0.1", "port" to server.localPort.toString())))
            assertTrue(result.successful)
            assertEquals(listOf("配置校验", "MC 3E TCP 端口连通"), result.checks.map { it.name })
        }
    }

    private fun readRequest(input: DataInputStream): ByteArray {
        val header = ByteArray(9).also(input::readFully)
        val length = (header[7].toInt() and 0xff) or ((header[8].toInt() and 0xff) shl 8)
        return header + ByteArray(length).also(input::readFully)
    }

    private fun respond(output: DataOutputStream, payload: ByteArray = byteArrayOf(), endCode: Int = 0) {
        val bodyLength = payload.size + 2
        output.write(
            byteArrayOf(
                0xd0.toByte(), 0x00, 0x00, 0xff.toByte(), 0xff.toByte(), 0x03, 0x00,
                bodyLength.toByte(), (bodyLength ushr 8).toByte(),
                endCode.toByte(), (endCode ushr 8).toByte(),
                *payload,
            )
        )
        output.flush()
    }

    private fun device(properties: Map<String, String>) = DeviceDefinition(
        DeviceId(1), "PLC_MC3E_001", "三菱 PLC", "mits_mc_3e", true, properties,
    )

    private fun point(
        dataType: PointDataType = PointDataType.UINT16,
        address: String = "D100",
        properties: Map<String, String> = mapOf("wordOrder" to "ABCD"),
    ) = PointDefinition(
        PointId(10), DeviceId(1), "speed", "速度", address, dataType,
        PointAccess.READ_WRITE, Duration.ofSeconds(1), properties,
    )
}

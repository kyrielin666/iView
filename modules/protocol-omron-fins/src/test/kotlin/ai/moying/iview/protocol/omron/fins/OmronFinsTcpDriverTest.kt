package ai.moying.iview.protocol.omron.fins

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

class OmronFinsTcpDriverTest {
    private val driver = OmronFinsTcpDriver()

    @Test
    fun `validation reports missing host and invalid nodes`() {
        val result = driver.validate(device(mapOf("port" to "0", "timeout" to "invalid", "node_number" to "255", "local_node" to "0")))
        assertFalse(result.valid)
        assertEquals(setOf("host", "port", "timeout", "node_number", "local_node"), result.issues.map { it.field }.toSet())
    }

    @Test
    fun `address parser selects word and bit memory area codes`() {
        assertEquals(FinsAddress(0x82, 100, 0, false, false), parseFinsAddress("D100", "DM"))
        assertEquals(FinsAddress(0x30, 10, 3, true, false), parseFinsAddress("CIO10.03", "CIO"))
        assertEquals(FinsAddress(0x89, 0x8005, 0, false, true), parseFinsAddress("C5", "CNT_PV"))
    }

    @Test
    fun `memory read command contains FINS route and address`() {
        val frame = buildFinsCommand(0x0101, parseFinsAddress("D100", "DM"), 2, byteArrayOf(), targetNode = 10, localNode = 1, network = 0, sid = 7)
        assertContentEquals(byteArrayOf(0x80.toByte(), 0, 2, 0, 10, 0, 0, 1, 0, 7, 1, 1, 0x82.toByte(), 0, 100, 0, 0, 2), frame)
    }

    @Test
    fun `TCP handshake assigns nodes and session reads DM word`() = runBlocking {
        ServerSocket(0).use { server ->
            thread(isDaemon = true) {
                server.accept().use { socket ->
                    val input = DataInputStream(socket.getInputStream()); val output = DataOutputStream(socket.getOutputStream())
                    val handshake = readPacket(input); assertEquals(0, handshake.command); assertEquals(1, int32(handshake.payload, 0))
                    output.write(tcpPacket(1, intBytes(1) + intBytes(10))); output.flush()
                    val command = readPacket(input); assertEquals(2, command.command); assertEquals(10, command.payload[4].toInt() and 0xff)
                    respondFins(output, command.payload, byteArrayOf(0x12, 0x34))
                }
            }
            driver.connect(device(mapOf("host" to "127.0.0.1", "port" to server.localPort.toString(), "node_number" to "10"))).use { session ->
                val value = session.read(listOf(point())).single()
                assertEquals(0x1234, value.value); assertEquals(ValueQuality.GOOD, value.quality)
            }
        }
    }

    @Test
    fun `adjacent FINS word points are merged into one memory area read`() = runBlocking {
        ServerSocket(0).use { server ->
            thread(isDaemon = true) {
                server.accept().use { socket ->
                    val input = DataInputStream(socket.getInputStream()); val output = DataOutputStream(socket.getOutputStream())
                    readPacket(input); output.write(tcpPacket(1, intBytes(1) + intBytes(10))); output.flush()
                    val request = readPacket(input)
                    // command header(10) + 0101 + address(4) + word count(2)
                    assertEquals(0x82, request.payload[12].toInt() and 0xff)
                    assertEquals(100, ((request.payload[13].toInt() and 0xff) shl 8) or (request.payload[14].toInt() and 0xff))
                    assertEquals(3, ((request.payload[16].toInt() and 0xff) shl 8) or (request.payload[17].toInt() and 0xff))
                    respondFins(output, request.payload, byteArrayOf(0, 1, 0, 2, 0, 3))
                }
            }
            driver.connect(device(mapOf("host" to "127.0.0.1", "port" to server.localPort.toString(), "node_number" to "10"))).use { session ->
                val values = session.read(listOf(point(id = 10, address = "D100"), point(id = 11, address = "D101"), point(id = 12, address = "D102")))
                assertEquals(listOf(1, 2, 3), values.map { it.value })
                assertTrue(values.all { it.quality == ValueQuality.GOOD })
            }
        }
    }

    @Test
    fun `PLC end code becomes bad response quality`() = runBlocking {
        ServerSocket(0).use { server ->
            thread(isDaemon = true) {
                server.accept().use { socket ->
                    val input = DataInputStream(socket.getInputStream()); val output = DataOutputStream(socket.getOutputStream())
                    readPacket(input); output.write(tcpPacket(1, intBytes(1) + intBytes(10))); output.flush()
                    val request = readPacket(input); respondFins(output, request.payload, byteArrayOf(), 0x2105)
                }
            }
            driver.connect(device(mapOf("host" to "127.0.0.1", "port" to server.localPort.toString(), "node_number" to "10"))).use { session ->
                val value = session.read(listOf(point())).single()
                assertEquals(ValueQuality.BAD_RESPONSE, value.quality); assertTrue(value.diagnostic.orEmpty().contains("0x2105"))
            }
        }
    }

    private data class Packet(val command: Int, val payload: ByteArray)
    private fun readPacket(input: DataInputStream): Packet { val header = ByteArray(16).also(input::readFully); val length = int32(header, 4); return Packet(int32(header, 8), ByteArray(length - 8).also(input::readFully)) }
    private fun respondFins(output: DataOutputStream, request: ByteArray, data: ByteArray, endCode: Int = 0) { val response = request.copyOfRange(0, 12) + byteArrayOf((endCode ushr 8).toByte(), endCode.toByte()) + data; response[0] = 0xc0.toByte(); output.write(tcpPacket(2, response)); output.flush() }
    private fun intBytes(value: Int) = byteArrayOf((value ushr 24).toByte(), (value ushr 16).toByte(), (value ushr 8).toByte(), value.toByte())
    private fun int32(data: ByteArray, offset: Int) = ((data[offset].toInt() and 0xff) shl 24) or ((data[offset + 1].toInt() and 0xff) shl 16) or ((data[offset + 2].toInt() and 0xff) shl 8) or (data[offset + 3].toInt() and 0xff)
    private fun device(properties: Map<String, String>) = DeviceDefinition(DeviceId(1), "OMRON_001", "欧姆龙 PLC", "omron_fins", true, properties)
    private fun point(id: Long = 10, address: String = "D100") = PointDefinition(PointId(id), DeviceId(1), "speed$id", "速度", address, PointDataType.UINT16, PointAccess.READ_WRITE, Duration.ofSeconds(1), mapOf("memoryArea" to "DM", "byteOrder" to "big"))
}

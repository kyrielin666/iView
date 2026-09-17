package ai.moying.iview.protocol.omron.finsudp

import ai.moying.iview.core.device.DeviceDefinition
import ai.moying.iview.core.device.DeviceId
import ai.moying.iview.core.device.PointAccess
import ai.moying.iview.core.device.PointDataType
import ai.moying.iview.core.device.PointDefinition
import ai.moying.iview.core.device.PointId
import ai.moying.iview.core.device.ValueQuality
import kotlinx.coroutines.runBlocking
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.time.Duration
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class OmronFinsUdpDriverTest {
    private val driver = OmronFinsUdpDriver()

    @Test
    fun `validation rejects invalid UDP settings`() {
        val result = driver.validate(device(mapOf("host" to "", "port" to "70000", "local_node" to "0", "retry_count" to "9")))
        assertFalse(result.valid)
        assertEquals(setOf("host", "port", "local_node", "retry_count"), result.issues.map { it.field }.toSet())
    }

    @Test
    fun `UDP read uses direct FINS frame and validates response`() = runBlocking {
        DatagramSocket(0).use { plc ->
            thread(isDaemon = true) {
                val received = DatagramPacket(ByteArray(1024), 1024); plc.receive(received)
                val request = received.data.copyOf(received.length)
                assertEquals(0x80, request[0].toInt() and 0xff)
                assertEquals(0x0101, ((request[10].toInt() and 0xff) shl 8) or (request[11].toInt() and 0xff))
                assertEquals(0x82, request[12].toInt() and 0xff)
                val response = request.copyOfRange(0, 12) + byteArrayOf(0, 0, 0x12, 0x34)
                response[0] = 0xc0.toByte()
                plc.send(DatagramPacket(response, response.size, received.socketAddress))
            }
            driver.connect(device(mapOf("host" to "127.0.0.1", "port" to plc.localPort.toString(), "node_number" to "10", "local_node" to "1"))).use { session ->
                val result = session.read(listOf(point())).single()
                assertEquals(0x1234, result.value)
                assertEquals(ValueQuality.GOOD, result.quality)
            }
        }
    }

    @Test
    fun `adjacent UDP FINS words are merged`() = runBlocking {
        DatagramSocket(0).use { plc ->
            thread(isDaemon = true) {
                val received = DatagramPacket(ByteArray(1024), 1024); plc.receive(received); val request = received.data.copyOf(received.length)
                assertEquals(3, ((request[16].toInt() and 0xff) shl 8) or (request[17].toInt() and 0xff))
                val response = request.copyOfRange(0, 12) + byteArrayOf(0, 0, 0, 1, 0, 2, 0, 3); response[0] = 0xc0.toByte()
                plc.send(DatagramPacket(response, response.size, received.socketAddress))
            }
            driver.connect(device(mapOf("host" to "127.0.0.1", "port" to plc.localPort.toString(), "node_number" to "10", "local_node" to "1"))).use { session ->
                val values = session.read(listOf(point(1, "D100"), point(2, "D101"), point(3, "D102")))
                assertEquals(listOf(1, 2, 3), values.map { it.value })
            }
        }
    }

    private fun device(properties: Map<String, String>) = DeviceDefinition(DeviceId(1), "OMRON_UDP_001", "欧姆龙 UDP PLC", "omron_fins_udp", true, properties)
    private fun point(id: Long = 1, address: String = "D100") = PointDefinition(PointId(id), DeviceId(1), "speed$id", "速度", address, PointDataType.UINT16, PointAccess.READ_WRITE, Duration.ofSeconds(1), mapOf("memoryArea" to "DM", "byteOrder" to "big"))
}

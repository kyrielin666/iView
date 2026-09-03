package ai.moying.iview.protocol.modbus

import ai.moying.iview.core.device.DeviceDefinition
import ai.moying.iview.core.device.DeviceId
import ai.moying.iview.core.device.PointAccess
import ai.moying.iview.core.device.PointDataType
import ai.moying.iview.core.device.PointDefinition
import ai.moying.iview.core.device.PointId
import ai.moying.iview.protocol.PointWrite
import kotlinx.coroutines.runBlocking
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.ServerSocket
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ModbusTcpDriverTest {
    private val driver = ModbusTcpDriver()

    @Test
    fun `validation reports missing host and invalid values`() {
        val result = driver.validate(device(mapOf("port" to "70000", "slave_id" to "0", "timeout" to "1")))

        assertFalse(result.valid)
        assertEquals(setOf("host", "port", "slave_id", "timeout"), result.issues.map { it.field }.toSet())
    }

    @Test
    fun `session reads and writes holding registers over real TCP frames`() = runBlocking {
        ServerSocket(0).use { server ->
            val completed = CountDownLatch(1)
            var writePayload = byteArrayOf()
            thread(name = "fake-modbus", isDaemon = true) {
                server.accept().use { socket ->
                    val input = DataInputStream(socket.getInputStream())
                    val output = DataOutputStream(socket.getOutputStream())
                    repeat(2) { requestIndex ->
                        val tx = input.readUnsignedShort()
                        input.readUnsignedShort()
                        val length = input.readUnsignedShort()
                        val unit = input.readUnsignedByte()
                        val function = input.readUnsignedByte()
                        val payload = ByteArray(length - 2).also(input::readFully)
                        if (requestIndex == 0) {
                            assertEquals(3, function)
                            assertTrue(payload.contentEquals(byteArrayOf(0, 0, 0, 1)))
                            respond(output, tx, unit, function, byteArrayOf(2, 0x12, 0x34))
                        } else {
                            assertEquals(16, function)
                            writePayload = payload
                            respond(output, tx, unit, function, payload.copyOfRange(0, 4))
                        }
                    }
                }
                completed.countDown()
            }

            val session = driver.connect(device(mapOf("host" to "127.0.0.1", "port" to server.localPort.toString())))
            session.use {
                val point = point()
                val values = it.read(listOf(point))
                assertEquals(0x1234, values.single().value)
                assertTrue(it.write(listOf(PointWrite(point, 0x4567))).single().successful)
            }
            assertTrue(completed.await(2, TimeUnit.SECONDS))
            assertTrue(writePayload.contentEquals(byteArrayOf(0, 0, 0, 1, 2, 0x45, 0x67)))
        }
    }

    @Test
    fun `diagnosis tests a configured TCP endpoint`() = runBlocking {
        ServerSocket(0).use { server ->
            thread(isDaemon = true) { server.accept().close() }
            val result = driver.diagnose(device(mapOf("host" to "127.0.0.1", "port" to server.localPort.toString())))
            assertTrue(result.successful)
            assertEquals(listOf("配置校验", "TCP 端口连通"), result.checks.map { it.name })
        }
    }

    @Test
    fun `batch read merges contiguous registers into one request`() = runBlocking {
        ServerSocket(0).use { server ->
            var requests = 0
            val completed = CountDownLatch(1)
            thread(name = "fake-modbus-batch", isDaemon = true) {
                server.accept().use { socket ->
                    val input = DataInputStream(socket.getInputStream())
                    val output = DataOutputStream(socket.getOutputStream())
                    val tx = input.readUnsignedShort()
                    input.readUnsignedShort()
                    val length = input.readUnsignedShort()
                    val unit = input.readUnsignedByte()
                    val function = input.readUnsignedByte()
                    val payload = ByteArray(length - 2).also(input::readFully)
                    requests++
                    assertEquals(3, function)
                    assertTrue(payload.contentEquals(byteArrayOf(0, 0, 0, 2)))
                    respond(output, tx, unit, function, byteArrayOf(4, 0, 10, 0, 20))
                }
                completed.countDown()
            }

            driver.connect(device(mapOf(
                "host" to "127.0.0.1",
                "port" to server.localPort.toString(),
                "maxBatchSize" to "10",
            ))).use { session ->
                val values = session.read(listOf(point(10, "speed", "40001"), point(11, "count", "40002")))
                assertEquals(listOf(10, 20), values.map { it.value })
            }
            assertTrue(completed.await(2, TimeUnit.SECONDS))
            assertEquals(1, requests)
        }
    }

    private fun respond(output: DataOutputStream, tx: Int, unit: Int, function: Int, payload: ByteArray) {
        output.writeShort(tx)
        output.writeShort(0)
        output.writeShort(payload.size + 2)
        output.writeByte(unit)
        output.writeByte(function)
        output.write(payload)
        output.flush()
    }

    private fun device(properties: Map<String, String>) = DeviceDefinition(
        DeviceId(1), "PLC_001", "测试 PLC", "modbus_tcp", true, properties,
    )

    private fun point(id: Long = 10, code: String = "speed", address: String = "40001") = PointDefinition(
        PointId(id), DeviceId(1), code, code, address, PointDataType.UINT16,
        PointAccess.READ_WRITE, Duration.ofSeconds(1), mapOf("register_type" to "holding", "byte_order" to "big"),
    )
}

package ai.moying.iview.protocol.modbus.rtu

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertTrue
import java.io.IOException

class ModbusRtuFrameTest {
    @Test fun `crc matches canonical read holding registers request`() {
        assertEquals(0xCDC5, crc(byteArrayOf(0x01, 0x03, 0x00, 0x00, 0x00, 0x0A)))
    }

    @Test fun `exception response is surfaced without reconnect`() {
        val wire = FakeTransport(respond = { request -> framed(byteArrayOf(request[0], (request[1].toInt() or 0x80).toByte(), 2)) })
        val client = RtuClient(1) { wire }; client.open()
        val error = assertFails { client.exchange(3, byteArrayOf(0, 0, 0, 1)) }
        assertTrue(error.message!!.contains("异常响应: 2")); assertEquals(1, wire.opens)
    }

    @Test fun `bad crc is rejected`() {
        val wire = FakeTransport(respond = { request -> framed(byteArrayOf(request[0], request[1], 2, 0, 42)).also { it[it.lastIndex] = 0 } })
        val client = RtuClient(1) { wire }; client.open()
        assertTrue(assertFails { client.exchange(3, byteArrayOf(0, 0, 0, 1)) }.message!!.contains("CRC"))
    }

    @Test fun `io failure reopens transport once and succeeds`() {
        var created = 0
        val client = RtuClient(1) { created++; FakeTransport({ request -> framed(byteArrayOf(request[0], request[1], 2, 0, 7)) }, failWrite = created == 1) }
        client.open(); assertEquals(listOf(2, 0, 7), client.exchange(3, byteArrayOf(0, 0, 0, 1)).map { it.toInt() and 255 }); assertEquals(2, created)
    }

    @Test fun `continuous collection keeps one session for ten thousand exchanges`() {
        val wire = FakeTransport(respond = { request -> framed(byteArrayOf(request[0], request[1], 2, 0x12, 0x34)) })
        val client = RtuClient(1) { wire }; client.open()
        repeat(10_000) { assertEquals(0x12, client.exchange(3, byteArrayOf(0, 0, 0, 1))[1].toInt() and 255) }
        assertEquals(1, wire.opens); assertEquals(10_000, wire.writes)
    }

    @Test fun `write response consumes exactly eight byte rtu frame`() {
        val wire = FakeTransport(respond = { request -> framed(request.copyOfRange(0, 6)) })
        val client = RtuClient(1) { wire }; client.open()
        assertEquals(listOf(0, 8, 0, 1), client.exchange(5, byteArrayOf(0, 8, 0, 1)).map { it.toInt() and 255 })
    }

    private fun framed(body: ByteArray): ByteArray { val value = crc(body); return body + byteArrayOf(value.toByte(), (value ushr 8).toByte()) }

    private class FakeTransport(private val respond: (ByteArray) -> ByteArray, private val failWrite: Boolean = false) : RtuTransport {
        override var connected = false; var opens = 0; var writes = 0; private var response = byteArrayOf(); private var offset = 0
        override fun open() { connected = true; opens++ }
        override fun close() { connected = false }
        override fun write(bytes: ByteArray) { writes++; if (failWrite) throw IOException("模拟串口断开"); response = respond(bytes); offset = 0 }
        override fun read(length: Int): ByteArray { if (offset + length > response.size) throw IOException("模拟响应不足"); return response.copyOfRange(offset, offset + length).also { offset += length } }
    }
}

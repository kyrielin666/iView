package ai.moying.iview.protocol.mitsubishi.mca1e

import ai.moying.iview.core.device.*
import ai.moying.iview.protocol.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.time.Duration
import java.time.Instant
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/** Native MELSEC-A MC 1E binary TCP driver. The 1E frame supports D/W/R words and M bits. */
class MitsubishiMcA1eDriver : ProtocolDriver {
    override val protocolType = PROTOCOL_TYPE
    override fun validate(device: DeviceDefinition): ValidationResult {
        val v = device.connectionProperties
        return ValidationResult(buildList {
            if (v.host().isBlank()) add(ValidationIssue("host", "设备地址不能为空"))
            validateInt(v, listOf("port"), 5_001, 1..65_535)?.let { add(ValidationIssue("port", it)) }
            validateInt(v, listOf("timeout"), 5_000, 100..300_000)?.let { add(ValidationIssue("timeout", it)) }
            validateInt(v, listOf("monitoring_timer", "monitoringTimer"), 10, 1..0xffff)?.let { add(ValidationIssue("monitoring_timer", it)) }
        })
    }
    override suspend fun diagnose(device: DeviceDefinition): DiagnosticResult = withContext(Dispatchers.IO) {
        val started = System.nanoTime(); val validation = validate(device)
        val checks = mutableListOf(DiagnosticCheck("配置校验", validation.valid, started.elapsed(), validation.issues.joinToString("；") { "${it.field}: ${it.message}" }.ifBlank { "配置有效" }))
        if (validation.valid) {
            val cfg = A1eConfig.from(device); val tcp = System.nanoTime()
            val result = runCatching { Socket().use { it.connect(InetSocketAddress(cfg.host, cfg.port), cfg.timeoutMs) } }
            checks += DiagnosticCheck("MC A1E TCP 端口连通", result.isSuccess, tcp.elapsed(), result.exceptionOrNull()?.message ?: "${cfg.host}:${cfg.port} 可以建立连接")
        }
        DiagnosticResult(checks)
    }
    override suspend fun connect(device: DeviceDefinition): ProtocolSession = withContext(Dispatchers.IO) {
        val validation = validate(device); require(validation.valid) { validation.issues.joinToString("；") { "${it.field}: ${it.message}" } }
        A1eSession(device, A1eConfig.from(device)).apply { open() }
    }
    companion object { const val PROTOCOL_TYPE = "mits_mc_a1e" }
}

private data class A1eConfig(val host: String, val port: Int, val timeoutMs: Int, val timer: Int) {
    companion object { fun from(device: DeviceDefinition): A1eConfig { val v = device.connectionProperties; return A1eConfig(v.host(), v.int(listOf("port"), 5_001), v.int(listOf("timeout"), 5_000), v.int(listOf("monitoring_timer", "monitoringTimer"), 10)) } }
}
internal data class A1eAddress(val device: String, val offset: Int, val code: ByteArray, val bit: Boolean)
internal enum class A1eWordOrder { ABCD, CDAB, BADC }

private class A1eSession(private val device: DeviceDefinition, private val config: A1eConfig) : ProtocolSession {
    private val lock = ReentrantLock(); private var socket: Socket? = null; private var input: DataInputStream? = null; private var output: DataOutputStream? = null
    override val connected get() = lock.withLock { socket?.let { it.isConnected && !it.isClosed } == true }
    fun open() = lock.withLock { connectSocket() }
    override suspend fun read(points: List<PointDefinition>): List<PointValue> = withContext(Dispatchers.IO) { points.map { point ->
        val observed = Instant.now(); runCatching { readPoint(point) }.fold({ PointValue(device.id, point.id, observed, Instant.now(), it, ValueQuality.GOOD, MitsubishiMcA1eDriver.PROTOCOL_TYPE) }, { error -> PointValue(device.id, point.id, observed, Instant.now(), null, quality(error), MitsubishiMcA1eDriver.PROTOCOL_TYPE, error.message) })
    } }
    override suspend fun write(writes: List<PointWrite>): List<WriteResult> = withContext(Dispatchers.IO) { writes.map { write ->
        if (write.point.access == PointAccess.READ_ONLY) WriteResult(false, "点位 ${write.point.code} 是只读点") else runCatching { writePoint(write.point, write.value) }.fold({ WriteResult(true) }, { WriteResult(false, it.message) })
    } }
    private fun readPoint(point: PointDefinition): Any? {
        val cfg = A1ePoint.from(point); val bit = point.dataType == PointDataType.BOOLEAN && cfg.address.bit; val count = if (bit) 1 else point.dataType.words()
        val data = request(buildA1eFrame(if (bit) 0 else 1, cfg.address, count, timer = config.timer), if (bit) 0x80 else 0x81, if (bit) 1 else count * 2)
        return cfg.scaleRead(if (bit) data[0].toInt() and 0xf0 != 0 else decodeA1e(data, point.dataType, cfg.order))
    }
    private fun writePoint(point: PointDefinition, value: Any?) {
        val cfg = A1ePoint.from(point); val bit = point.dataType == PointDataType.BOOLEAN && cfg.address.bit
        val data = if (bit) byteArrayOf(if (value.bool()) 0x10 else 0) else encodeA1e(cfg.scaleWrite(value), point.dataType, cfg.order)
        request(buildA1eFrame(if (bit) 2 else 3, cfg.address, if (bit) 1 else data.size / 2, data, config.timer), if (bit) 0x82 else 0x83, 0)
    }
    private fun request(frame: ByteArray, expectedHeader: Int, dataLength: Int): ByteArray = lock.withLock { try { exchange(frame, expectedHeader, dataLength) } catch (error: IOException) { closeSocket(); connectSocket(); exchange(frame, expectedHeader, dataLength) } }
    private fun exchange(frame: ByteArray, expectedHeader: Int, dataLength: Int): ByteArray {
        val sink = output ?: throw EOFException("MC A1E 连接未建立"); sink.write(frame); sink.flush()
        val source = input ?: throw EOFException("MC A1E 连接未建立"); val header = ByteArray(2).also(source::readFully)
        if (header[0].u() != expectedHeader) throw A1eException("MC A1E 响应副头部错误: 0x${header[0].u().toString(16)}")
        if (header[1].u() != 0) throw A1eException("MC A1E PLC 异常响应: 0x${header[1].u().toString(16).uppercase().padStart(2, '0')}")
        return ByteArray(dataLength).also(source::readFully)
    }
    private fun connectSocket() { closeSocket(); val next = Socket(); next.soTimeout = config.timeoutMs; next.tcpNoDelay = true; next.keepAlive = true; next.connect(InetSocketAddress(config.host, config.port), config.timeoutMs); socket = next; input = DataInputStream(next.getInputStream()); output = DataOutputStream(next.getOutputStream()) }
    override fun close() = lock.withLock { closeSocket() }; private fun closeSocket() { runCatching { socket?.close() }; socket = null; input = null; output = null }
    private fun quality(error: Throwable) = when (error) { is SocketTimeoutException -> ValueQuality.TIMEOUT; is IOException -> ValueQuality.OFFLINE; is IllegalArgumentException -> ValueQuality.BAD_CONFIGURATION; else -> ValueQuality.BAD_RESPONSE }
}

private data class A1ePoint(val address: A1eAddress, val order: A1eWordOrder, val multiplier: Double, val offset: Double) {
    fun scaleRead(value: Any?) = if (multiplier == 1.0 && offset == 0.0) value else value.number() * multiplier + offset
    fun scaleWrite(value: Any?): Any? { require(multiplier != 0.0) { "value_scale 不能为 0" }; return if (multiplier == 1.0 && offset == 0.0) value else (value.number() - offset) / multiplier }
    companion object { fun from(point: PointDefinition): A1ePoint {
        require(point.dataType !in setOf(PointDataType.STRING, PointDataType.BYTES)) { "MC A1E 暂不支持数据类型: ${point.dataType}" }; val v = point.properties; val prefix = (v["register_type"] ?: v["registerType"]).orEmpty().trim().uppercase(); val raw = point.address.trim(); val address = parseA1eAddress(if (prefix.isNotEmpty() && raw.firstOrNull()?.isDigit() == true) prefix + raw else raw)
        val scaled = address.offset.toLong() * v.int(listOf("address_scale", "addressScale"), 1) + v.int(listOf("address_offset", "addressOffset"), 0); require(scaled in 0..0x7fff_ffff) { "点位地址计算结果超出范围" }
        val order = runCatching { A1eWordOrder.valueOf((v["word_order"] ?: v["wordOrder"] ?: "ABCD").trim().uppercase()) }.getOrElse { throw IllegalArgumentException("不支持的 MC A1E 字序") }; return A1ePoint(address.copy(offset = scaled.toInt()), order, v.double(listOf("value_scale", "valueScale"), 1.0), v.double(listOf("value_offset", "valueOffset"), 0.0))
    } }
}

internal fun parseA1eAddress(raw: String): A1eAddress { val match = Regex("^([A-Z]+)([0-9A-F]+)$").matchEntire(raw.trim().uppercase()) ?: throw IllegalArgumentException("无效的 MC A1E 地址: $raw"); val type = a1eDevices[match.groupValues[1]] ?: throw IllegalArgumentException("不支持的 MC A1E 软元件类型: $raw"); val offset = match.groupValues[2].toIntOrNull(type.radix) ?: throw IllegalArgumentException("无效的 MC A1E 地址: $raw"); return A1eAddress(match.groupValues[1], offset, type.code, type.bit) }
internal fun buildA1eFrame(command: Int, address: A1eAddress, count: Int, data: ByteArray = byteArrayOf(), timer: Int = 10): ByteArray { require(command in 0..3 && count in 1..0xffff && timer in 1..0xffff) { "MC A1E 帧参数无效" }; val expected = when (command) { 2 -> (count + 1) / 2; 3 -> count * 2; else -> 0 }; require(data.size == expected) { "MC A1E 写入数据长度不匹配" }; return byteArrayOf(command.toByte(), 0xff.toByte(), timer.toByte(), (timer ushr 8).toByte(), address.offset.toByte(), (address.offset ushr 8).toByte(), (address.offset ushr 16).toByte(), (address.offset ushr 24).toByte(), *address.code, count.toByte(), (count ushr 8).toByte(), *data) }
private fun decodeA1e(bytes: ByteArray, type: PointDataType, order: A1eWordOrder): Any { val words = (0 until type.words()).map { le(bytes, it * 2) }.let { if (order == A1eWordOrder.ABCD) it else it.reversed() }; var bits = 0L; words.forEachIndexed { i, word -> bits = bits or (word.toLong() shl (16 * i)) }; return when (type) { PointDataType.BOOLEAN -> bits != 0L; PointDataType.INT16 -> bits.toShort(); PointDataType.UINT16 -> bits.toInt() and 0xffff; PointDataType.INT32 -> bits.toInt(); PointDataType.UINT32 -> bits and 0xffff_ffffL; PointDataType.INT64, PointDataType.UINT64 -> bits; PointDataType.FLOAT32 -> Float.fromBits(bits.toInt()); PointDataType.FLOAT64 -> Double.fromBits(bits); else -> throw IllegalArgumentException("MC A1E 暂不支持数据类型: $type") } }
private fun encodeA1e(value: Any?, type: PointDataType, order: A1eWordOrder): ByteArray { val bits = when (type) { PointDataType.BOOLEAN -> if (value.bool()) 1L else 0L; PointDataType.INT16, PointDataType.UINT16 -> value.number().toLong() and 0xffff; PointDataType.INT32, PointDataType.UINT32 -> value.number().toLong() and 0xffff_ffffL; PointDataType.INT64, PointDataType.UINT64 -> value.number().toLong(); PointDataType.FLOAT32 -> value.number().toFloat().toBits().toLong() and 0xffff_ffffL; PointDataType.FLOAT64 -> value.number().toBits(); else -> throw IllegalArgumentException("MC A1E 暂不支持数据类型: $type") }; return (0 until type.words()).map { ((bits ushr (it * 16)) and 0xffff).toInt() }.let { if (order == A1eWordOrder.ABCD) it else it.reversed() }.flatMap { listOf(it.toByte(), (it ushr 8).toByte()) }.toByteArray() }
private fun PointDataType.words() = when (this) { PointDataType.BOOLEAN, PointDataType.INT16, PointDataType.UINT16 -> 1; PointDataType.INT32, PointDataType.UINT32, PointDataType.FLOAT32 -> 2; PointDataType.INT64, PointDataType.UINT64, PointDataType.FLOAT64 -> 4; else -> throw IllegalArgumentException("MC A1E 暂不支持数据类型: $this") }
private fun le(data: ByteArray, offset: Int) = data[offset].u() or (data[offset + 1].u() shl 8); private fun Byte.u() = toInt() and 0xff
private fun Any?.number(): Double = when (this) { is Number -> toDouble(); is String -> trim().toDoubleOrNull() ?: throw IllegalArgumentException("不是数值: $this"); is Boolean -> if (this) 1.0 else 0.0; else -> throw IllegalArgumentException("不是数值") }; private fun Any?.bool(): Boolean = when (this) { is Boolean -> this; is Number -> toDouble() != 0.0; is String -> trim().lowercase() in setOf("true", "1", "on"); else -> throw IllegalArgumentException("不是布尔值") }
private fun Map<String, String>.host() = (this["host"] ?: this["ip_address"] ?: this["ipAddress"] ?: "").trim(); private fun Map<String, String>.int(keys: List<String>, default: Int) = keys.firstNotNullOfOrNull { this[it] }?.trim()?.let(::flexInt) ?: default; private fun Map<String, String>.double(keys: List<String>, default: Double) = keys.firstNotNullOfOrNull { this[it] }?.trim()?.toDoubleOrNull() ?: default
private fun validateInt(values: Map<String, String>, keys: List<String>, default: Int, range: IntRange): String? { val text = keys.firstNotNullOfOrNull { values[it] }?.trim(); val value = text?.let(::flexInt) ?: if (text == null) default else return "必须是整数"; return if (value in range) null else "必须在 ${range.first} 到 ${range.last} 之间" }; private fun flexInt(value: String) = if (value.startsWith("0x", true)) value.drop(2).toIntOrNull(16) else value.toIntOrNull(); private fun Long.elapsed() = Duration.ofNanos(System.nanoTime() - this)
private class A1eException(message: String) : RuntimeException(message); private data class A1eDevice(val code: ByteArray, val radix: Int, val bit: Boolean)
private val a1eDevices = mapOf("D" to A1eDevice(byteArrayOf(0x20, 0x44), 10, false), "W" to A1eDevice(byteArrayOf(0x20, 0x57), 16, false), "R" to A1eDevice(byteArrayOf(0x20, 0x52), 10, false), "M" to A1eDevice(byteArrayOf(0x20, 0x4d), 10, true))

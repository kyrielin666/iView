package ai.moying.iview.protocol.mitsubishi.mc4e

import ai.moying.iview.core.device.DeviceDefinition
import ai.moying.iview.core.device.PointAccess
import ai.moying.iview.core.device.PointDataType
import ai.moying.iview.core.device.PointDefinition
import ai.moying.iview.core.device.PointValue
import ai.moying.iview.core.device.ValueQuality
import ai.moying.iview.protocol.DiagnosticCheck
import ai.moying.iview.protocol.DiagnosticResult
import ai.moying.iview.protocol.PointWrite
import ai.moying.iview.protocol.ProtocolDriver
import ai.moying.iview.protocol.ProtocolSession
import ai.moying.iview.protocol.ValidationIssue
import ai.moying.iview.protocol.ValidationResult
import ai.moying.iview.protocol.WriteResult
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
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/** Mitsubishi MC 4E binary TCP driver for Q, L and iQ-R PLC communication modules. */
class MitsubishiMc4eDriver : ProtocolDriver {
    override val protocolType = PROTOCOL_TYPE
    override fun validate(device: DeviceDefinition): ValidationResult {
        val values = device.connectionProperties
        val issues = buildList {
            if (values.host().isBlank()) add(ValidationIssue("host", "设备地址不能为空"))
            validateInt(values, listOf("port"), 5_000, 1..65_535)?.let { add(ValidationIssue("port", it)) }
            validateInt(values, listOf("timeout"), 5_000, 100..300_000)?.let { add(ValidationIssue("timeout", it)) }
            validateInt(values, listOf("network_number", "networkNumber"), 0, 0..255)?.let { add(ValidationIssue("network_number", it)) }
            validateInt(values, listOf("pc_number", "pcNumber", "plc_number", "plcNumber"), 0xff, 0..255)?.let { add(ValidationIssue("pc_number", it)) }
            validateInt(values, listOf("module_io", "moduleIo"), 0x03ff, 0..0xffff)?.let { add(ValidationIssue("module_io", it)) }
            validateInt(values, listOf("module_station", "moduleStation"), 0, 0..255)?.let { add(ValidationIssue("module_station", it)) }
            validateInt(values, listOf("monitoring_timer", "monitoringTimer"), 10, 1..0xffff)?.let { add(ValidationIssue("monitoring_timer", it)) }
        }
        return ValidationResult(issues)
    }
    override suspend fun diagnose(device: DeviceDefinition): DiagnosticResult = withContext(Dispatchers.IO) {
        val started = System.nanoTime(); val validation = validate(device)
        val checks = mutableListOf(DiagnosticCheck("配置校验", validation.valid, started.elapsed(), validation.issues.joinToString("；") { "${it.field}: ${it.message}" }.ifBlank { "配置有效" }))
        if (validation.valid) {
            val cfg = Mc4eConfig.from(device); val tcp = System.nanoTime()
            val result = runCatching { Socket().use { it.connect(InetSocketAddress(cfg.host, cfg.port), cfg.timeoutMs) } }
            checks += DiagnosticCheck("MC 4E TCP 端口连通", result.isSuccess, tcp.elapsed(), result.exceptionOrNull()?.message ?: "${cfg.host}:${cfg.port} 可以建立连接")
        }
        DiagnosticResult(checks)
    }
    override suspend fun connect(device: DeviceDefinition): ProtocolSession = withContext(Dispatchers.IO) {
        val validation = validate(device); require(validation.valid) { validation.issues.joinToString("；") { "${it.field}: ${it.message}" } }
        Mc4eSession(device, Mc4eConfig.from(device)).apply { open() }
    }
    companion object { const val PROTOCOL_TYPE = "mits_mc_4e" }
}

private data class Mc4eConfig(val host: String, val port: Int, val timeoutMs: Int, val route: Mc4eRoute, val monitoringTimer: Int) {
    companion object { fun from(device: DeviceDefinition): Mc4eConfig {
        val values = device.connectionProperties
        return Mc4eConfig(values.host(), values.int(listOf("port"), 5_000), values.int(listOf("timeout"), 5_000), Mc4eRoute(values.int(listOf("network_number", "networkNumber"), 0), values.int(listOf("pc_number", "pcNumber", "plc_number", "plcNumber"), 0xff), values.int(listOf("module_io", "moduleIo"), 0x03ff), values.int(listOf("module_station", "moduleStation"), 0)), values.int(listOf("monitoring_timer", "monitoringTimer"), 10))
    } }
}
internal data class Mc4eRoute(val networkNumber: Int = 0, val pcNumber: Int = 0xff, val moduleIo: Int = 0x03ff, val moduleStation: Int = 0)
internal data class Mc4eAddress(val device: String, val offset: Int, val code: Int, val bit: Boolean)
internal enum class Mc4eWordOrder { ABCD, CDAB, BADC }

private class Mc4eSession(private val device: DeviceDefinition, private val config: Mc4eConfig) : ProtocolSession {
    private val lock = ReentrantLock(); private val serial = AtomicInteger(0)
    private var socket: Socket? = null; private var input: DataInputStream? = null; private var output: DataOutputStream? = null
    override val connected get() = lock.withLock { socket?.let { it.isConnected && !it.isClosed } == true }
    fun open() = lock.withLock { connectSocket() }
    override suspend fun read(points: List<PointDefinition>): List<PointValue> = withContext(Dispatchers.IO) { points.map { point ->
        val observed = Instant.now(); runCatching { readPoint(point) }.fold({ PointValue(device.id, point.id, observed, Instant.now(), it, ValueQuality.GOOD, MitsubishiMc4eDriver.PROTOCOL_TYPE) }, { error -> PointValue(device.id, point.id, observed, Instant.now(), null, quality(error), MitsubishiMc4eDriver.PROTOCOL_TYPE, error.message) })
    } }
    override suspend fun write(writes: List<PointWrite>): List<WriteResult> = withContext(Dispatchers.IO) { writes.map { write ->
        when { write.point.access == PointAccess.READ_ONLY -> WriteResult(false, "点位 ${write.point.code} 是只读点")
            runCatching { Mc4ePoint.from(write.point).address.device == "X" }.getOrDefault(false) -> WriteResult(false, "三菱 X 输入继电器不允许写入")
            else -> runCatching { writePoint(write.point, write.value) }.fold({ WriteResult(true) }, { WriteResult(false, it.message) }) }
    } }
    private fun readPoint(point: PointDefinition): Any? {
        val cfg = Mc4ePoint.from(point)
        val raw = if (point.dataType == PointDataType.BOOLEAN && cfg.address.bit) { val data = request(buildMc4eReadFrame(cfg.address, 1, true, config.route, config.monitoringTimer, nextSerial())); require(data.isNotEmpty()) { "MC 4E 位读取响应为空" }; data[0].toInt() and 0xf0 != 0 }
        else { val count = point.dataType.words(); val data = request(buildMc4eReadFrame(cfg.address, count, false, config.route, config.monitoringTimer, nextSerial())); require(data.size >= count * 2) { "MC 4E 字读取响应长度不足" }; decode(data, point.dataType, cfg.order) }
        return cfg.scaleRead(raw)
    }
    private fun writePoint(point: PointDefinition, value: Any?) {
        val cfg = Mc4ePoint.from(point)
        if (point.dataType == PointDataType.BOOLEAN && cfg.address.bit) request(buildMc4eWriteFrame(cfg.address, 1, true, byteArrayOf(if (value.bool()) 0x10 else 0), config.route, config.monitoringTimer, nextSerial()))
        else { val data = encode(cfg.scaleWrite(value), point.dataType, cfg.order); request(buildMc4eWriteFrame(cfg.address, data.size / 2, false, data, config.route, config.monitoringTimer, nextSerial())) }
    }
    private fun nextSerial() = (serial.incrementAndGet() and 0xffff).let { if (it == 0) 1 else it }
    private fun request(frame: ByteArray): ByteArray = lock.withLock { try { exchange(frame) } catch (error: IOException) { closeSocket(); connectSocket(); exchange(frame) } }
    private fun exchange(frame: ByteArray): ByteArray {
        val sink = output ?: throw EOFException("MC 4E 连接未建立"); sink.write(frame); sink.flush()
        val source = input ?: throw EOFException("MC 4E 连接未建立"); val header = ByteArray(13).also(source::readFully)
        if (header[0].u() != 0xd4 || header[1].u() != 0) throw Mc4eException("MC 4E 响应副头部错误")
        if (le(header, 2) != le(frame, 2)) throw Mc4eException("MC 4E 响应序列号与请求不匹配")
        if (header[4].u() != 0 || header[5].u() != 0 || !header.matches(config.route)) throw Mc4eException("MC 4E 响应路由错误")
        val length = le(header, 11); if (length !in 2..65_535) throw Mc4eException("MC 4E 响应长度不合法: $length")
        val body = ByteArray(length).also(source::readFully); val endCode = le(body, 0)
        if (endCode != 0) throw Mc4eException("MC 4E PLC 异常响应: 0x${endCode.toString(16).uppercase().padStart(4, '0')}")
        return body.copyOfRange(2, body.size)
    }
    private fun connectSocket() { closeSocket(); val next = Socket(); next.soTimeout = config.timeoutMs; next.tcpNoDelay = true; next.keepAlive = true; next.connect(InetSocketAddress(config.host, config.port), config.timeoutMs); socket = next; input = DataInputStream(next.getInputStream()); output = DataOutputStream(next.getOutputStream()) }
    override fun close() = lock.withLock { closeSocket() }
    private fun closeSocket() { runCatching { socket?.close() }; socket = null; input = null; output = null }
    private fun quality(error: Throwable) = when (error) { is SocketTimeoutException -> ValueQuality.TIMEOUT; is IOException -> ValueQuality.OFFLINE; is IllegalArgumentException -> ValueQuality.BAD_CONFIGURATION; else -> ValueQuality.BAD_RESPONSE }
}

private data class Mc4ePoint(val address: Mc4eAddress, val order: Mc4eWordOrder, val multiplier: Double, val offset: Double) {
    fun scaleRead(value: Any?) = if (multiplier == 1.0 && offset == 0.0) value else value.number() * multiplier + offset
    fun scaleWrite(value: Any?): Any? { require(multiplier != 0.0) { "value_scale 不能为 0" }; return if (multiplier == 1.0 && offset == 0.0) value else (value.number() - offset) / multiplier }
    companion object { fun from(point: PointDefinition): Mc4ePoint {
        require(point.dataType !in setOf(PointDataType.STRING, PointDataType.BYTES)) { "MC 4E 暂不支持数据类型: ${point.dataType}" }
        val values = point.properties; val prefix = (values["register_type"] ?: values["registerType"]).orEmpty().trim().uppercase(); val raw = point.address.trim(); val address = parseMc4eAddress(if (prefix.isNotEmpty() && raw.firstOrNull()?.isDigit() == true) prefix + raw else raw)
        val scale = values.int(listOf("address_scale", "addressScale"), 1); val offset = values.int(listOf("address_offset", "addressOffset"), 0); val calculated = address.offset.toLong() * scale + offset; require(calculated in 0..0xff_ffff) { "点位地址计算结果超出 3 字节范围" }
        val order = runCatching { Mc4eWordOrder.valueOf((values["word_order"] ?: values["wordOrder"] ?: "ABCD").trim().uppercase()) }.getOrElse { throw IllegalArgumentException("不支持的 MC 4E 字序") }
        return Mc4ePoint(address.copy(offset = calculated.toInt()), order, values.double(listOf("value_scale", "valueScale"), 1.0), values.double(listOf("value_offset", "valueOffset"), 0.0))
    } }
}

internal fun parseMc4eAddress(raw: String): Mc4eAddress {
    val match = Regex("^([A-Z]+)([0-9A-F]+)$").matchEntire(raw.trim().uppercase()) ?: throw IllegalArgumentException("无效的 MC 4E 地址: $raw")
    val type = mc4eDevices[match.groupValues[1]] ?: throw IllegalArgumentException("不支持的 MC 4E 软元件类型: $raw")
    val value = match.groupValues[2].toIntOrNull(type.radix) ?: throw IllegalArgumentException("无效的 MC 4E 地址: $raw")
    require(value in 0..0xff_ffff) { "MC 4E 地址超出 3 字节范围" }; return Mc4eAddress(match.groupValues[1], value, type.code, type.bit)
}
internal fun buildMc4eReadFrame(address: Mc4eAddress, count: Int, bit: Boolean, route: Mc4eRoute = Mc4eRoute(), timer: Int = 10, serial: Int = 1): ByteArray = buildMc4e(0x0401, if (bit) 1 else 0, address, count, byteArrayOf(), route, timer, serial)
internal fun buildMc4eWriteFrame(address: Mc4eAddress, count: Int, bit: Boolean, data: ByteArray, route: Mc4eRoute = Mc4eRoute(), timer: Int = 10, serial: Int = 1): ByteArray { val expected = if (bit) (count + 1) / 2 else count * 2; require(count in 1..0xffff && data.size == expected) { "MC 4E 写入数据长度不匹配" }; return buildMc4e(0x1401, if (bit) 1 else 0, address, count, data, route, timer, serial) }
private fun buildMc4e(command: Int, subcommand: Int, address: Mc4eAddress, count: Int, data: ByteArray, route: Mc4eRoute, timer: Int, serial: Int): ByteArray { require(count in 1..0xffff) { "MC 4E 点数必须在 1 到 65535 之间" }; require(serial in 0..0xffff) { "MC 4E 序列号无效" }; val length = 12 + data.size; return byteArrayOf(0x54, 0, serial.toByte(), (serial ushr 8).toByte(), 0, 0, route.networkNumber.toByte(), route.pcNumber.toByte(), route.moduleIo.toByte(), (route.moduleIo ushr 8).toByte(), route.moduleStation.toByte(), length.toByte(), (length ushr 8).toByte(), timer.toByte(), (timer ushr 8).toByte(), command.toByte(), (command ushr 8).toByte(), subcommand.toByte(), (subcommand ushr 8).toByte(), address.offset.toByte(), (address.offset ushr 8).toByte(), (address.offset ushr 16).toByte(), address.code.toByte(), count.toByte(), (count ushr 8).toByte(), *data) }
private fun decode(bytes: ByteArray, type: PointDataType, order: Mc4eWordOrder): Any { val words = (0 until type.words()).map { le(bytes, it * 2) }.let { if (order == Mc4eWordOrder.ABCD) it else it.reversed() }; var bits = 0L; words.forEachIndexed { index, word -> bits = bits or (word.toLong() shl (16 * index)) }; return when (type) { PointDataType.BOOLEAN -> bits != 0L; PointDataType.INT16 -> bits.toShort(); PointDataType.UINT16 -> bits.toInt() and 0xffff; PointDataType.INT32 -> bits.toInt(); PointDataType.UINT32 -> bits and 0xffff_ffffL; PointDataType.INT64, PointDataType.UINT64 -> bits; PointDataType.FLOAT32 -> Float.fromBits(bits.toInt()); PointDataType.FLOAT64 -> Double.fromBits(bits); else -> throw IllegalArgumentException("MC 4E 暂不支持数据类型: $type") } }
private fun encode(value: Any?, type: PointDataType, order: Mc4eWordOrder): ByteArray { val bits = when (type) { PointDataType.BOOLEAN -> if (value.bool()) 1L else 0L; PointDataType.INT16, PointDataType.UINT16 -> value.number().toLong() and 0xffff; PointDataType.INT32, PointDataType.UINT32 -> value.number().toLong() and 0xffff_ffffL; PointDataType.INT64, PointDataType.UINT64 -> value.number().toLong(); PointDataType.FLOAT32 -> value.number().toFloat().toBits().toLong() and 0xffff_ffffL; PointDataType.FLOAT64 -> value.number().toBits(); else -> throw IllegalArgumentException("MC 4E 暂不支持数据类型: $type") }; return (0 until type.words()).map { ((bits ushr (it * 16)) and 0xffff).toInt() }.let { if (order == Mc4eWordOrder.ABCD) it else it.reversed() }.flatMap { listOf(it.toByte(), (it ushr 8).toByte()) }.toByteArray() }
private fun PointDataType.words() = when (this) { PointDataType.BOOLEAN, PointDataType.INT16, PointDataType.UINT16 -> 1; PointDataType.INT32, PointDataType.UINT32, PointDataType.FLOAT32 -> 2; PointDataType.INT64, PointDataType.UINT64, PointDataType.FLOAT64 -> 4; else -> throw IllegalArgumentException("MC 4E 暂不支持数据类型: $this") }
private fun ByteArray.matches(route: Mc4eRoute) = this[6].u() == route.networkNumber && this[7].u() == route.pcNumber && le(this, 8) == route.moduleIo && this[10].u() == route.moduleStation
private fun le(data: ByteArray, offset: Int) = data[offset].u() or (data[offset + 1].u() shl 8)
private fun Byte.u() = toInt() and 0xff
private fun Any?.number(): Double = when (this) { is Number -> toDouble(); is String -> trim().toDoubleOrNull() ?: throw IllegalArgumentException("不是数值: $this"); is Boolean -> if (this) 1.0 else 0.0; else -> throw IllegalArgumentException("不是数值") }
private fun Any?.bool(): Boolean = when (this) { is Boolean -> this; is Number -> toDouble() != 0.0; is String -> trim().lowercase() in setOf("true", "1", "on"); else -> throw IllegalArgumentException("不是布尔值") }
private fun Map<String, String>.host() = (this["host"] ?: this["ip_address"] ?: this["ipAddress"] ?: "").trim()
private fun Map<String, String>.int(keys: List<String>, default: Int) = keys.firstNotNullOfOrNull { this[it] }?.trim()?.let(::flexInt) ?: default
private fun Map<String, String>.double(keys: List<String>, default: Double) = keys.firstNotNullOfOrNull { this[it] }?.trim()?.toDoubleOrNull() ?: default
private fun validateInt(values: Map<String, String>, keys: List<String>, default: Int, range: IntRange): String? { val text = keys.firstNotNullOfOrNull { values[it] }?.trim(); val value = text?.let(::flexInt) ?: if (text == null) default else return "必须是整数"; return if (value in range) null else "必须在 ${range.first} 到 ${range.last} 之间" }
private fun flexInt(value: String) = if (value.startsWith("0x", true)) value.drop(2).toIntOrNull(16) else value.toIntOrNull()
private fun Long.elapsed() = Duration.ofNanos(System.nanoTime() - this)
private class Mc4eException(message: String) : RuntimeException(message)
private data class Mc4eDevice(val code: Int, val radix: Int, val bit: Boolean)
private val mc4eDevices = mapOf("X" to Mc4eDevice(0x9c, 16, true), "Y" to Mc4eDevice(0x9d, 16, true), "M" to Mc4eDevice(0x90, 10, true), "SM" to Mc4eDevice(0x91, 10, true), "L" to Mc4eDevice(0x92, 10, true), "F" to Mc4eDevice(0x93, 10, true), "V" to Mc4eDevice(0x94, 10, true), "B" to Mc4eDevice(0xa0, 16, true), "SB" to Mc4eDevice(0xa1, 16, true), "D" to Mc4eDevice(0xa8, 10, false), "SD" to Mc4eDevice(0xa9, 10, false), "W" to Mc4eDevice(0xb4, 16, false), "SW" to Mc4eDevice(0xb5, 16, false), "R" to Mc4eDevice(0xaf, 10, false), "TC" to Mc4eDevice(0xc0, 10, true), "TS" to Mc4eDevice(0xc1, 10, true), "TN" to Mc4eDevice(0xc2, 10, false), "CC" to Mc4eDevice(0xc3, 10, true), "CS" to Mc4eDevice(0xc4, 10, true), "CN" to Mc4eDevice(0xc5, 10, false), "S" to Mc4eDevice(0x98, 10, true))

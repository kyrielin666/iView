package ai.moying.iview.protocol.omron.finsudp

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
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.SocketTimeoutException
import java.time.Duration
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/** Native OMRON FINS/UDP driver.  UDP has no node-assignment handshake. */
class OmronFinsUdpDriver : ProtocolDriver {
    override val protocolType = PROTOCOL_TYPE

    override fun validate(device: DeviceDefinition): ValidationResult {
        val v = device.connectionProperties
        return ValidationResult(buildList {
            if (v.host().isBlank()) add(ValidationIssue("host", "设备地址不能为空"))
            validInt(v, "port", 9_600, 1..65_535)?.let { add(ValidationIssue("port", it)) }
            validInt(v, "timeout", 5_000, 100..300_000)?.let { add(ValidationIssue("timeout", it)) }
            validInt(v, "node_number", 0, 0..254)?.let { add(ValidationIssue("node_number", it)) }
            validInt(v, "local_node", 1, 1..254)?.let { add(ValidationIssue("local_node", it)) }
            validInt(v, "network_number", 0, 0..127)?.let { add(ValidationIssue("network_number", it)) }
            validInt(v, "retry_count", 1, 0..5)?.let { add(ValidationIssue("retry_count", it)) }
        })
    }

    override suspend fun diagnose(device: DeviceDefinition): DiagnosticResult = withContext(Dispatchers.IO) {
        val start = System.nanoTime(); val validation = validate(device)
        val checks = mutableListOf(DiagnosticCheck("配置校验", validation.valid, start.elapsed(), validation.issues.joinToString("；") { "${it.field}: ${it.message}" }.ifBlank { "配置有效" }))
        if (validation.valid) {
            val cfg = UdpConfig.from(device); val now = System.nanoTime()
            val result = runCatching { DatagramSocket().use { it.connect(InetSocketAddress(cfg.host, cfg.port)) } }
            checks += DiagnosticCheck("FINS/UDP 套接字", result.isSuccess, now.elapsed(), result.exceptionOrNull()?.message ?: "${cfg.host}:${cfg.port} 已配置")
        }
        DiagnosticResult(checks)
    }

    override suspend fun connect(device: DeviceDefinition): ProtocolSession = withContext(Dispatchers.IO) {
        val validation = validate(device); require(validation.valid) { validation.issues.joinToString("；") { "${it.field}: ${it.message}" } }
        FinsUdpSession(device, UdpConfig.from(device)).apply { open() }
    }

    companion object { const val PROTOCOL_TYPE = "omron_fins_udp" }
}

private data class UdpConfig(val host: String, val port: Int, val timeoutMs: Int, val targetNode: Int, val localNode: Int, val network: Int, val retries: Int) {
    companion object {
        fun from(device: DeviceDefinition): UdpConfig {
            val v = device.connectionProperties; val host = v.host()
            val derived = host.substringAfterLast('.', "1").toIntOrNull()?.takeIf { it in 1..254 } ?: 1
            val target = v.int("node_number", 0).takeIf { it != 0 } ?: derived
            return UdpConfig(host, v.int("port", 9_600), v.int("timeout", 5_000), target, v.int("local_node", 1), v.int("network_number", 0), v.int("retry_count", 1))
        }
    }
}

private class FinsUdpSession(private val device: DeviceDefinition, private val config: UdpConfig) : ProtocolSession {
    private val lock = ReentrantLock(); private val sid = AtomicInteger(0); private var socket: DatagramSocket? = null
    override val connected get() = lock.withLock { socket?.let { !it.isClosed && it.isConnected } == true }
    fun open() = lock.withLock { openSocket() }

    override suspend fun read(points: List<PointDefinition>): List<PointValue> = withContext(Dispatchers.IO) {
        val at = Instant.now(); val results = arrayOfNulls<PointValue>(points.size); val mergeable = mutableListOf<UdpReadItem>()
        points.forEachIndexed { index, point -> runCatching { UdpPoint.from(point) }.fold(
            { cfg -> if (cfg.address.bit) results[index] = readValue(point, at) else mergeable += UdpReadItem(index, point, cfg) },
            { error -> results[index] = failedValue(point, at, error) },
        ) }
        val planned = mergeable.sortedWith(compareBy<UdpReadItem> { it.config.address.area }.thenBy { it.config.address.word }); var cursor = 0
        while (cursor < planned.size) {
            val first = planned[cursor]; val group = mutableListOf(first); val start = first.config.address.word; var end = start + first.point.dataType.words(); var next = cursor + 1
            while (next < planned.size) {
                val candidate = planned[next]; if (candidate.config.address.area != first.config.address.area) break
                val candidateEnd = candidate.config.address.word + candidate.point.dataType.words()
                if (candidate.config.address.word > end + 16 || candidateEnd - start > MAX_MERGED_WORDS) break
                group += candidate; end = maxOf(end, candidateEnd); next++
            }
            runCatching {
                val data = request(0x0101, UdpAddress(first.config.address.area, start, 0, false, false), end - start, byteArrayOf())
                group.forEach { item -> val offset = (item.config.address.word - start) * 2; val size = item.point.dataType.words() * 2; val raw = decode(data.copyOfRange(offset, offset + size), item.point.dataType, item.config.order); results[item.index] = PointValue(device.id, item.point.id, at, Instant.now(), item.config.scaleRead(raw), ValueQuality.GOOD, OmronFinsUdpDriver.PROTOCOL_TYPE) }
            }.onFailure { error -> group.forEach { item -> results[item.index] = failedValue(item.point, at, error) } }
            cursor = next
        }
        results.mapIndexed { index, value -> value ?: readValue(points[index], at) }
    }
    override suspend fun write(writes: List<PointWrite>): List<WriteResult> = withContext(Dispatchers.IO) { writes.map { write ->
        if (write.point.access == PointAccess.READ_ONLY) WriteResult(false, "点位 ${write.point.code} 是只读点") else runCatching { writePoint(write.point, write.value) }.fold({ WriteResult(true) }, { WriteResult(false, it.message) })
    } }

    private fun readPoint(point: PointDefinition): Any? {
        val cfg = UdpPoint.from(point); val count = if (cfg.address.bit) 1 else point.dataType.words()
        val data = request(0x0101, cfg.address, count, byteArrayOf())
        val raw = if (cfg.address.bit) { require(data.isNotEmpty()) { "FINS 位读取响应为空" }; data[0].u() != 0 } else decode(data, point.dataType, cfg.order)
        return cfg.scaleRead(raw)
    }
    private fun readValue(point: PointDefinition, at: Instant) = runCatching { readPoint(point) }.fold({ PointValue(device.id, point.id, at, Instant.now(), it, ValueQuality.GOOD, OmronFinsUdpDriver.PROTOCOL_TYPE) }, { error -> failedValue(point, at, error) })
    private fun failedValue(point: PointDefinition, at: Instant, error: Throwable) = PointValue(device.id, point.id, at, Instant.now(), null, quality(error), OmronFinsUdpDriver.PROTOCOL_TYPE, error.message)
    private fun writePoint(point: PointDefinition, value: Any?) {
        val cfg = UdpPoint.from(point); require(!cfg.address.readOnly) { "该 FINS 内存区只读" }
        val data = if (cfg.address.bit) byteArrayOf(if (value.bool()) 1 else 0) else encode(cfg.scaleWrite(value), point.dataType, cfg.order)
        request(0x0102, cfg.address, if (cfg.address.bit) 1 else data.size / 2, data)
    }
    private fun request(command: Int, address: UdpAddress, count: Int, data: ByteArray): ByteArray = lock.withLock {
        var last: IOException? = null
        repeat(config.retries + 1) {
            try { return exchange(command, address, count, data) } catch (error: IOException) { last = error; closeSocket(); openSocket() }
        }
        throw last ?: IOException("FINS/UDP 请求失败")
    }
    private fun exchange(command: Int, address: UdpAddress, count: Int, data: ByteArray): ByteArray {
        val sequence = (sid.incrementAndGet() and 0xff).let { if (it == 0) 1 else it }
        val request = finsFrame(command, address, count, data, config.targetNode, config.localNode, config.network, sequence)
        val current = socket ?: throw IOException("FINS/UDP 未连接")
        current.send(DatagramPacket(request, request.size))
        val received = ByteArray(65_535); val packet = DatagramPacket(received, received.size); current.receive(packet)
        val response = packet.data.copyOfRange(packet.offset, packet.offset + packet.length)
        if (response.size < 14) throw UdpFinsException("FINS/UDP 响应长度不足")
        if (response[9].u() != sequence) throw UdpFinsException("FINS/UDP 响应 SID 与请求不匹配")
        if (be16(response, 10) != command) throw UdpFinsException("FINS/UDP 响应命令与请求不匹配")
        val end = be16(response, 12); if (end != 0) throw UdpFinsException("FINS PLC 异常响应: 0x${end.toString(16).uppercase().padStart(4, '0')}")
        return response.copyOfRange(14, response.size)
    }
    private fun openSocket() { closeSocket(); socket = DatagramSocket().apply { soTimeout = config.timeoutMs; connect(InetAddress.getByName(config.host), config.port) } }
    override fun close() = lock.withLock { closeSocket() }
    private fun closeSocket() { runCatching { socket?.close() }; socket = null }
    private fun quality(error: Throwable) = when (error) { is SocketTimeoutException -> ValueQuality.TIMEOUT; is IOException -> ValueQuality.OFFLINE; is IllegalArgumentException -> ValueQuality.BAD_CONFIGURATION; else -> ValueQuality.BAD_RESPONSE }
    private data class UdpReadItem(val index: Int, val point: PointDefinition, val config: UdpPoint)
    private companion object { const val MAX_MERGED_WORDS = 500 }
}

private data class UdpAddress(val area: Int, val word: Int, val bitIndex: Int, val bit: Boolean, val readOnly: Boolean)
private enum class UdpOrder { BIG, LITTLE, CDAB, BADC }
private data class UdpPoint(val address: UdpAddress, val order: UdpOrder, val multiplier: Double, val offset: Double) {
    fun scaleRead(value: Any?) = if (multiplier == 1.0 && offset == 0.0) value else value.number() * multiplier + offset
    fun scaleWrite(value: Any?): Any? { require(multiplier != 0.0) { "value_scale 不能为 0" }; return if (multiplier == 1.0 && offset == 0.0) value else (value.number() - offset) / multiplier }
    companion object {
        fun from(point: PointDefinition): UdpPoint {
            require(point.dataType !in setOf(PointDataType.STRING, PointDataType.BYTES)) { "FINS 暂不支持数据类型: ${point.dataType}" }
            val p = point.properties; val area = (p["memory_area"] ?: p["memoryArea"] ?: p["register_type"] ?: p["registerType"] ?: areaOf(point.address)).trim().uppercase()
            return UdpPoint(parseAddress(point.address, area), parseOrder(p["byte_order"] ?: p["byteOrder"] ?: "big"), p.double("value_scale", "valueScale", 1.0), p.double("value_offset", "valueOffset", 0.0))
        }
    }
}

private fun parseAddress(raw: String, configuredArea: String): UdpAddress {
    val bit = raw.contains('.'); val match = Regex("^([A-Z]*)([0-9]+)(?:\\.([0-9]{1,2}))?$").matchEntire(raw.trim().uppercase()) ?: throw IllegalArgumentException("无效的 FINS 地址: $raw")
    val word = match.groupValues[2].toInt(); require(word in 0..0xffff) { "FINS 字地址超出范围" }
    val index = match.groupValues[3].ifBlank { "0" }.toInt(); require(!bit || index in 0..15) { "FINS 位地址必须在 0 到 15 之间" }
    val area = normalize(configuredArea.removeSuffix("_WORD").removeSuffix("_BIT").ifBlank { areaOf(raw) }); val readOnly = area in setOf("TIM_PV", "CNT_PV")
    require(!bit || !readOnly) { "$area 不支持位访问" }
    val code = (if (bit) bitAreas[area] else wordAreas[area]) ?: throw IllegalArgumentException("不支持的 FINS 内存区: $configuredArea")
    val adjusted = if (area == "CNT_PV") { require(word <= 0x7fff) { "计数器地址超出范围" }; word + 0x8000 } else word
    return UdpAddress(code, adjusted, index, bit, readOnly)
}
private fun finsFrame(command: Int, address: UdpAddress, count: Int, data: ByteArray, target: Int, local: Int, network: Int, sid: Int): ByteArray {
    require(count in 1..0xffff && target in 1..254 && local in 1..254 && sid in 1..255) { "FINS 帧参数无效" }
    return byteArrayOf(0x80.toByte(), 0, 2, network.toByte(), target.toByte(), 0, 0, local.toByte(), 0, sid.toByte(), (command ushr 8).toByte(), command.toByte(), address.area.toByte(), (address.word ushr 8).toByte(), address.word.toByte(), address.bitIndex.toByte(), (count ushr 8).toByte(), count.toByte(), *data)
}
private fun decode(source: ByteArray, type: PointDataType, order: UdpOrder): Any { val size = type.words() * 2; require(source.size >= size) { "FINS 字读取响应长度不足" }; val data = orderBytes(source.copyOf(size), order); var bits = 0L; data.forEach { bits = (bits shl 8) or it.u().toLong() }; return when (type) { PointDataType.BOOLEAN -> bits != 0L; PointDataType.INT16 -> bits.toShort(); PointDataType.UINT16 -> bits.toInt(); PointDataType.INT32 -> bits.toInt(); PointDataType.UINT32 -> bits; PointDataType.INT64, PointDataType.UINT64 -> bits; PointDataType.FLOAT32 -> Float.fromBits(bits.toInt()); PointDataType.FLOAT64 -> Double.fromBits(bits); else -> error("unreachable") } }
private fun encode(value: Any?, type: PointDataType, order: UdpOrder): ByteArray { val bits = when (type) { PointDataType.BOOLEAN -> if (value.bool()) 1L else 0L; PointDataType.INT16, PointDataType.UINT16 -> value.number().toLong() and 0xffff; PointDataType.INT32, PointDataType.UINT32 -> value.number().toLong() and 0xffff_ffffL; PointDataType.INT64, PointDataType.UINT64 -> value.number().toLong(); PointDataType.FLOAT32 -> value.number().toFloat().toBits().toLong() and 0xffff_ffffL; PointDataType.FLOAT64 -> value.number().toBits(); else -> error("unreachable") }; val size = type.words() * 2; return orderBytes(ByteArray(size) { (bits ushr ((size - it - 1) * 8)).toByte() }, order) }
private fun orderBytes(source: ByteArray, order: UdpOrder) = when (order) { UdpOrder.BIG -> source; UdpOrder.LITTLE -> source.reversedArray(); UdpOrder.CDAB -> source.asList().chunked(2).reversed().flatten().toByteArray(); UdpOrder.BADC -> source.asList().chunked(2).flatMap { it.reversed() }.toByteArray() }
private fun parseOrder(value: String) = when (value.trim().lowercase()) { "", "big", "abcd" -> UdpOrder.BIG; "little", "dcba" -> UdpOrder.LITTLE; "cdab" -> UdpOrder.CDAB; "badc" -> UdpOrder.BADC; else -> throw IllegalArgumentException("不支持的 FINS 字节序: $value") }
private fun PointDataType.words() = when (this) { PointDataType.BOOLEAN, PointDataType.INT16, PointDataType.UINT16 -> 1; PointDataType.INT32, PointDataType.UINT32, PointDataType.FLOAT32 -> 2; PointDataType.INT64, PointDataType.UINT64, PointDataType.FLOAT64 -> 4; else -> throw IllegalArgumentException("FINS 暂不支持数据类型: $this") }
private fun areaOf(address: String) = when { address.trim().uppercase().startsWith("CIO") -> "CIO"; address.trim().uppercase().startsWith("WR") || address.trim().uppercase().startsWith("W") -> "WR"; address.trim().uppercase().startsWith("HR") || address.trim().uppercase().startsWith("H") -> "HR"; address.trim().uppercase().startsWith("AR") || address.trim().uppercase().startsWith("A") -> "AR"; address.trim().uppercase().startsWith("E") -> "EM"; address.trim().uppercase().startsWith("T") -> "TIM_PV"; address.trim().uppercase().startsWith("C") -> "CNT_PV"; else -> "DM" }
private fun normalize(value: String) = mapOf("D" to "DM", "W" to "WR", "H" to "HR", "A" to "AR", "E" to "EM", "T" to "TIM_PV", "TIM" to "TIM_PV", "C" to "CNT_PV", "CNT" to "CNT_PV")[value] ?: value
private val wordAreas = mapOf("CIO" to 0xb0, "WR" to 0xb1, "HR" to 0xb2, "AR" to 0xb3, "DM" to 0x82, "EM" to 0x98, "TIM_PV" to 0x89, "CNT_PV" to 0x89)
private val bitAreas = mapOf("CIO" to 0x30, "WR" to 0x31, "HR" to 0x32, "AR" to 0x33, "DM" to 0x02, "EM" to 0x0a)
private fun be16(data: ByteArray, offset: Int) = (data[offset].u() shl 8) or data[offset + 1].u(); private fun Byte.u() = toInt() and 0xff
private fun Any?.number(): Double = when (this) { is Number -> toDouble(); is String -> trim().toDoubleOrNull() ?: throw IllegalArgumentException("不是数值: $this"); is Boolean -> if (this) 1.0 else 0.0; else -> throw IllegalArgumentException("不是数值") }
private fun Any?.bool(): Boolean = when (this) { is Boolean -> this; is Number -> toDouble() != 0.0; is String -> trim().lowercase() in setOf("true", "1", "on"); else -> throw IllegalArgumentException("不是布尔值") }
private fun Map<String, String>.host() = (this["host"] ?: this["ip_address"] ?: this["ipAddress"] ?: "").trim(); private fun Map<String, String>.int(key: String, default: Int) = (this[key] ?: this[key.replace("_", "")])?.trim()?.toIntOrNull() ?: default; private fun Map<String, String>.double(vararg values: Any): Double { val default = values.last() as Double; return values.dropLast(1).filterIsInstance<String>().firstNotNullOfOrNull { this[it] }?.toDoubleOrNull() ?: default }
private fun validInt(values: Map<String, String>, key: String, default: Int, range: IntRange): String? { val text = values[key]?.trim(); val value = text?.toIntOrNull() ?: if (text == null) default else return "必须是整数"; return if (value in range) null else "必须在 ${range.first} 到 ${range.last} 之间" }
private fun Long.elapsed() = Duration.ofNanos(System.nanoTime() - this); private class UdpFinsException(message: String) : RuntimeException(message)

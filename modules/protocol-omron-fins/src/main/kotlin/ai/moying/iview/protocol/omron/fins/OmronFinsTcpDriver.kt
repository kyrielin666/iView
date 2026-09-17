package ai.moying.iview.protocol.omron.fins

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

/** Native OMRON FINS/TCP driver for CS/CJ/CP controllers. */
class OmronFinsTcpDriver : ProtocolDriver {
    override val protocolType = PROTOCOL_TYPE

    override fun validate(device: DeviceDefinition): ValidationResult {
        val v = device.connectionProperties
        return ValidationResult(buildList {
            if (v.host().isBlank()) add(ValidationIssue("host", "设备地址不能为空"))
            validateInt(v, listOf("port"), 9_600, 1..65_535)?.let { add(ValidationIssue("port", it)) }
            validateInt(v, listOf("timeout"), 5_000, 100..300_000)?.let { add(ValidationIssue("timeout", it)) }
            validateInt(v, listOf("node_number", "nodeNumber"), 0, 0..254)?.let { add(ValidationIssue("node_number", it)) }
            validateInt(v, listOf("local_node", "localNode"), 1, 1..254)?.let { add(ValidationIssue("local_node", it)) }
            validateInt(v, listOf("network_number", "networkNumber"), 0, 0..127)?.let { add(ValidationIssue("network_number", it)) }
        })
    }

    override suspend fun diagnose(device: DeviceDefinition): DiagnosticResult = withContext(Dispatchers.IO) {
        val start = System.nanoTime(); val validation = validate(device)
        val checks = mutableListOf(DiagnosticCheck("配置校验", validation.valid, start.elapsed(), validation.issues.joinToString("；") { "${it.field}: ${it.message}" }.ifBlank { "配置有效" }))
        if (validation.valid) {
            val cfg = FinsConfig.from(device); val tcp = System.nanoTime()
            val result = runCatching { Socket().use { it.connect(InetSocketAddress(cfg.host, cfg.port), cfg.timeoutMs) } }
            checks += DiagnosticCheck("FINS/TCP 端口连通", result.isSuccess, tcp.elapsed(), result.exceptionOrNull()?.message ?: "${cfg.host}:${cfg.port} 可以建立连接")
        }
        DiagnosticResult(checks)
    }

    override suspend fun connect(device: DeviceDefinition): ProtocolSession = withContext(Dispatchers.IO) {
        val validation = validate(device); require(validation.valid) { validation.issues.joinToString("；") { "${it.field}: ${it.message}" } }
        FinsTcpSession(device, FinsConfig.from(device)).apply { open() }
    }

    companion object { const val PROTOCOL_TYPE = "omron_fins" }
}

private data class FinsConfig(val host: String, val port: Int, val timeoutMs: Int, val targetNode: Int, val localNode: Int, val network: Int) {
    companion object {
        fun from(device: DeviceDefinition): FinsConfig {
            val v = device.connectionProperties; val host = v.host(); val configuredNode = v.int(listOf("node_number", "nodeNumber"), 0)
            val derivedNode = host.substringAfterLast('.', "1").toIntOrNull()?.takeIf { it in 1..254 } ?: 1
            return FinsConfig(host, v.int(listOf("port"), 9_600), v.int(listOf("timeout"), 5_000), configuredNode.takeIf { it != 0 } ?: derivedNode, v.int(listOf("local_node", "localNode"), 1), v.int(listOf("network_number", "networkNumber"), 0))
        }
    }
}

internal data class FinsAddress(val area: Int, val word: Int, val bit: Int, val bitAccess: Boolean, val readOnly: Boolean)
internal enum class FinsByteOrder { BIG, LITTLE, CDAB, BADC }

private class FinsTcpSession(private val device: DeviceDefinition, private val config: FinsConfig) : ProtocolSession {
    private val lock = ReentrantLock(); private val sid = AtomicInteger(0)
    private var socket: Socket? = null; private var input: DataInputStream? = null; private var output: DataOutputStream? = null
    private var targetNode = config.targetNode; private var localNode = config.localNode
    override val connected get() = lock.withLock { socket?.let { it.isConnected && !it.isClosed } == true }
    fun open() = lock.withLock { connectSocket() }

    override suspend fun read(points: List<PointDefinition>): List<PointValue> = withContext(Dispatchers.IO) {
        /*
         * FINS memory-area-read accepts a range of words.  Planning compatible word
         * points here cuts request count substantially for normal PLC polling while
         * preserving the public contract: output order, individual scaling and an
         * individual error value for malformed/bit-addressed points are unchanged.
         */
        val observed = Instant.now()
        val results = arrayOfNulls<PointValue>(points.size)
        val mergeable = mutableListOf<FinsReadItem>()
        points.forEachIndexed { index, point ->
            runCatching { FinsPoint.from(point) }.fold({ cfg ->
                if (!cfg.address.bitAccess) mergeable += FinsReadItem(index, point, cfg) else results[index] = readValue(point, observed)
            }, { error -> results[index] = failedValue(point, observed, error) })
        }
        val planned = mergeable.sortedWith(compareBy<FinsReadItem> { it.config.address.area }.thenBy { it.config.address.word })
        var cursor = 0
        while (cursor < planned.size) {
            val item = planned[cursor]
            val group = mutableListOf(item)
            val start = item.config.address.word
            var endExclusive = start + item.point.dataType.words()
            var next = cursor + 1
            while (next < planned.size) {
                val candidate = planned[next]
                if (candidate.config.address.area != item.config.address.area) break
                val candidateStart = candidate.config.address.word
                val candidateEnd = candidateStart + candidate.point.dataType.words()
                // A small hole is intentionally allowed: it still replaces many PLC round trips.
                if (candidateStart > endExclusive + 16 || candidateEnd - start > MAX_MERGED_WORDS) break
                group += candidate; endExclusive = maxOf(endExclusive, candidateEnd); next++
            }
            runCatching {
                val data = request(0x0101, FinsAddress(item.config.address.area, start, 0, false, false), endExclusive - start, byteArrayOf())
                group.forEach { grouped ->
                    val offset = (grouped.config.address.word - start) * 2
                    val length = grouped.point.dataType.words() * 2
                    val raw = decodeFins(data.copyOfRange(offset, offset + length), grouped.point.dataType, grouped.config.order)
                    results[grouped.index] = PointValue(device.id, grouped.point.id, observed, Instant.now(), grouped.config.scaleRead(raw), ValueQuality.GOOD, OmronFinsTcpDriver.PROTOCOL_TYPE)
                }
            }.onFailure { error -> group.forEach { grouped -> results[grouped.index] = failedValue(grouped.point, observed, error) } }
            cursor = next
        }
        results.mapIndexed { index, value -> value ?: readValue(points[index], observed) }
    }
    override suspend fun write(writes: List<PointWrite>): List<WriteResult> = withContext(Dispatchers.IO) { writes.map { write ->
        if (write.point.access == PointAccess.READ_ONLY) WriteResult(false, "点位 ${write.point.code} 是只读点") else runCatching { writePoint(write.point, write.value) }.fold({ WriteResult(true) }, { WriteResult(false, it.message) })
    } }

    private fun readPoint(point: PointDefinition): Any? {
        val cfg = FinsPoint.from(point); val count = if (cfg.address.bitAccess) 1 else point.dataType.words()
        val data = request(0x0101, cfg.address, count, byteArrayOf())
        val raw = if (cfg.address.bitAccess) { require(data.isNotEmpty()) { "FINS 位读取响应为空" }; data[0].toInt() != 0 } else decodeFins(data, point.dataType, cfg.order)
        return cfg.scaleRead(raw)
    }
    private fun readValue(point: PointDefinition, observed: Instant) = runCatching { readPoint(point) }.fold(
        { PointValue(device.id, point.id, observed, Instant.now(), it, ValueQuality.GOOD, OmronFinsTcpDriver.PROTOCOL_TYPE) },
        { error -> failedValue(point, observed, error) },
    )
    private fun failedValue(point: PointDefinition, observed: Instant, error: Throwable) = PointValue(device.id, point.id, observed, Instant.now(), null, quality(error), OmronFinsTcpDriver.PROTOCOL_TYPE, error.message)
    private fun writePoint(point: PointDefinition, value: Any?) {
        val cfg = FinsPoint.from(point); require(!cfg.address.readOnly) { "该 FINS 内存区只读" }
        val data = if (cfg.address.bitAccess) byteArrayOf(if (value.bool()) 1 else 0) else encodeFins(cfg.scaleWrite(value), point.dataType, cfg.order)
        request(0x0102, cfg.address, if (cfg.address.bitAccess) 1 else data.size / 2, data)
    }
    private fun request(command: Int, address: FinsAddress, count: Int, data: ByteArray): ByteArray = lock.withLock {
        try { exchange(command, address, count, data) } catch (error: IOException) { closeSocket(); connectSocket(); exchange(command, address, count, data) }
    }
    private fun exchange(command: Int, address: FinsAddress, count: Int, data: ByteArray): ByteArray {
        val sequence = (sid.incrementAndGet() and 0xff).let { if (it == 0) 1 else it }
        val fins = buildFinsCommand(command, address, count, data, targetNode, localNode, config.network, sequence)
        val packet = tcpPacket(2, fins); val sink = output ?: throw EOFException("FINS/TCP 连接未建立"); sink.write(packet); sink.flush()
        val payload = readTcpPacket(input ?: throw EOFException("FINS/TCP 连接未建立"), 2)
        if (payload.size < 14) throw FinsException("FINS/TCP 响应长度不足")
        if (payload[9].u() != sequence) throw FinsException("FINS 响应 SID 与请求不匹配")
        if (be16(payload, 10) != command) throw FinsException("FINS 响应命令与请求不匹配")
        val endCode = be16(payload, 12); if (endCode != 0) throw FinsException("FINS PLC 异常响应: 0x${endCode.toString(16).uppercase().padStart(4, '0')}")
        return payload.copyOfRange(14, payload.size)
    }
    private fun connectSocket() {
        closeSocket(); val next = Socket(); next.soTimeout = config.timeoutMs; next.tcpNoDelay = true; next.keepAlive = true; next.connect(InetSocketAddress(config.host, config.port), config.timeoutMs)
        socket = next; input = DataInputStream(next.getInputStream()); output = DataOutputStream(next.getOutputStream())
        val sink = output!!; sink.write(tcpPacket(0, be32(config.localNode))); sink.flush(); val handshake = readTcpPacket(input!!, 1)
        require(handshake.size >= 8) { "FINS/TCP 握手响应长度不足" }; localNode = be32Value(handshake, 0) and 0xff; targetNode = be32Value(handshake, 4) and 0xff
        require(localNode in 1..254 && targetNode in 1..254) { "FINS/TCP 握手返回的节点号无效" }
    }
    override fun close() = lock.withLock { closeSocket() }
    private fun closeSocket() { runCatching { socket?.close() }; socket = null; input = null; output = null }
    private fun quality(error: Throwable) = when (error) { is SocketTimeoutException -> ValueQuality.TIMEOUT; is IOException -> ValueQuality.OFFLINE; is IllegalArgumentException -> ValueQuality.BAD_CONFIGURATION; else -> ValueQuality.BAD_RESPONSE }
    private data class FinsReadItem(val index: Int, val point: PointDefinition, val config: FinsPoint)
    private companion object { const val MAX_MERGED_WORDS = 500 }
}

private data class FinsPoint(val address: FinsAddress, val order: FinsByteOrder, val multiplier: Double, val offset: Double) {
    fun scaleRead(value: Any?) = if (multiplier == 1.0 && offset == 0.0) value else value.number() * multiplier + offset
    fun scaleWrite(value: Any?): Any? { require(multiplier != 0.0) { "value_scale 不能为 0" }; return if (multiplier == 1.0 && offset == 0.0) value else (value.number() - offset) / multiplier }
    companion object {
        fun from(point: PointDefinition): FinsPoint {
            require(point.dataType !in setOf(PointDataType.STRING, PointDataType.BYTES)) { "FINS 暂不支持数据类型: ${point.dataType}" }
            val v = point.properties; val area = (v["memory_area"] ?: v["memoryArea"] ?: v["register_type"] ?: v["registerType"] ?: inferArea(point.address)).trim().uppercase()
            val address = parseFinsAddress(point.address, area); val order = parseOrder(v["byte_order"] ?: v["byteOrder"] ?: "big")
            return FinsPoint(address, order, v.double(listOf("value_scale", "valueScale"), 1.0), v.double(listOf("value_offset", "valueOffset"), 0.0))
        }
    }
}

internal fun parseFinsAddress(raw: String, configuredArea: String): FinsAddress {
    val bit = raw.contains('.'); val match = Regex("^([A-Z]*)([0-9]+)(?:\\.([0-9]{1,2}))?$").matchEntire(raw.trim().uppercase()) ?: throw IllegalArgumentException("无效的 FINS 地址: $raw")
    val word = match.groupValues[2].toIntOrNull() ?: throw IllegalArgumentException("无效的 FINS 地址: $raw"); require(word in 0..0xffff) { "FINS 字地址超出范围" }
    val bitIndex = match.groupValues[3].ifBlank { "0" }.toInt(); require(!bit || bitIndex in 0..15) { "FINS 位地址必须在 0 到 15 之间" }
    val areaName = configuredArea.removeSuffix("_WORD").removeSuffix("_BIT").let { normalizeArea(it.ifBlank { inferArea(raw) }) }
    val readOnly = areaName in setOf("TIM_PV", "CNT_PV"); require(!bit || !readOnly) { "$areaName 不支持位访问" }
    val code = (if (bit) finsBitAreas[areaName] else finsWordAreas[areaName])
        ?: throw IllegalArgumentException("不支持的 FINS 内存区: $configuredArea")
    val adjusted = if (areaName == "CNT_PV") { require(word <= 0x7fff) { "计数器地址超出范围" }; word + 0x8000 } else word
    return FinsAddress(code, adjusted, bitIndex, bit, readOnly)
}

internal fun buildFinsCommand(command: Int, address: FinsAddress, count: Int, data: ByteArray, targetNode: Int, localNode: Int, network: Int, sid: Int): ByteArray {
    require(count in 1..0xffff && targetNode in 1..254 && localNode in 1..254 && sid in 1..255) { "FINS 帧参数无效" }
    return byteArrayOf(0x80.toByte(), 0, 2, network.toByte(), targetNode.toByte(), 0, 0, localNode.toByte(), 0, sid.toByte(), (command ushr 8).toByte(), command.toByte(), address.area.toByte(), (address.word ushr 8).toByte(), address.word.toByte(), address.bit.toByte(), (count ushr 8).toByte(), count.toByte(), *data)
}
internal fun tcpPacket(command: Int, payload: ByteArray): ByteArray = byteArrayOf(0x46, 0x49, 0x4e, 0x53, *be32(payload.size + 8), *be32(command), 0, 0, 0, 0, *payload)
private fun readTcpPacket(input: DataInputStream, expectedCommand: Int): ByteArray { val header = ByteArray(16).also(input::readFully); if (!header.copyOfRange(0, 4).contentEquals(byteArrayOf(0x46, 0x49, 0x4e, 0x53))) throw FinsException("FINS/TCP 魔数错误"); val length = be32Value(header, 4); if (length !in 8..1_048_576) throw FinsException("FINS/TCP 长度不合法: $length"); val command = be32Value(header, 8); if (command != expectedCommand) throw FinsException("FINS/TCP 命令错误: $command"); val error = be32Value(header, 12); if (error != 0) throw FinsException("FINS/TCP 错误: 0x${error.toString(16).uppercase()}"); return ByteArray(length - 8).also(input::readFully) }

private fun decodeFins(source: ByteArray, type: PointDataType, order: FinsByteOrder): Any { val size = type.words() * 2; require(source.size >= size) { "FINS 字读取响应长度不足" }; val data = reorder(source.copyOf(size), order); var bits = 0L; data.forEach { bits = (bits shl 8) or it.u().toLong() }; return when (type) { PointDataType.BOOLEAN -> bits != 0L; PointDataType.INT16 -> bits.toShort(); PointDataType.UINT16 -> bits.toInt(); PointDataType.INT32 -> bits.toInt(); PointDataType.UINT32 -> bits; PointDataType.INT64, PointDataType.UINT64 -> bits; PointDataType.FLOAT32 -> Float.fromBits(bits.toInt()); PointDataType.FLOAT64 -> Double.fromBits(bits); else -> throw IllegalArgumentException("FINS 暂不支持数据类型: $type") } }
private fun encodeFins(value: Any?, type: PointDataType, order: FinsByteOrder): ByteArray { val bits = when (type) { PointDataType.BOOLEAN -> if (value.bool()) 1L else 0L; PointDataType.INT16, PointDataType.UINT16 -> value.number().toLong() and 0xffff; PointDataType.INT32, PointDataType.UINT32 -> value.number().toLong() and 0xffff_ffffL; PointDataType.INT64, PointDataType.UINT64 -> value.number().toLong(); PointDataType.FLOAT32 -> value.number().toFloat().toBits().toLong() and 0xffff_ffffL; PointDataType.FLOAT64 -> value.number().toBits(); else -> throw IllegalArgumentException("FINS 暂不支持数据类型: $type") }; val size = type.words() * 2; val canonical = ByteArray(size) { index -> (bits ushr ((size - index - 1) * 8)).toByte() }; return reorder(canonical, order) }
private fun reorder(source: ByteArray, order: FinsByteOrder): ByteArray = when (order) { FinsByteOrder.BIG -> source; FinsByteOrder.LITTLE -> source.reversedArray(); FinsByteOrder.CDAB -> source.asList().chunked(2).reversed().flatten().toByteArray(); FinsByteOrder.BADC -> source.asList().chunked(2).flatMap { it.reversed() }.toByteArray() }
private fun parseOrder(value: String) = when (value.trim().lowercase()) { "", "big", "abcd" -> FinsByteOrder.BIG; "little", "dcba" -> FinsByteOrder.LITTLE; "cdab" -> FinsByteOrder.CDAB; "badc" -> FinsByteOrder.BADC; else -> throw IllegalArgumentException("不支持的 FINS 字节序: $value") }
private fun PointDataType.words() = when (this) { PointDataType.BOOLEAN, PointDataType.INT16, PointDataType.UINT16 -> 1; PointDataType.INT32, PointDataType.UINT32, PointDataType.FLOAT32 -> 2; PointDataType.INT64, PointDataType.UINT64, PointDataType.FLOAT64 -> 4; else -> throw IllegalArgumentException("FINS 暂不支持数据类型: $this") }
private fun inferArea(address: String) = when { address.trim().uppercase().startsWith("CIO") -> "CIO"; address.trim().uppercase().startsWith("WR") || address.trim().uppercase().startsWith("W") -> "WR"; address.trim().uppercase().startsWith("HR") || address.trim().uppercase().startsWith("H") -> "HR"; address.trim().uppercase().startsWith("AR") || address.trim().uppercase().startsWith("A") -> "AR"; address.trim().uppercase().startsWith("E") -> "EM"; address.trim().uppercase().startsWith("T") -> "TIM_PV"; address.trim().uppercase().startsWith("C") -> "CNT_PV"; else -> "DM" }
private fun normalizeArea(value: String) = when (value) { "D" -> "DM"; "W" -> "WR"; "H" -> "HR"; "A" -> "AR"; "E" -> "EM"; "T", "TIM" -> "TIM_PV"; "C", "CNT" -> "CNT_PV"; else -> value }
private val finsWordAreas = mapOf("CIO" to 0xb0, "WR" to 0xb1, "HR" to 0xb2, "AR" to 0xb3, "DM" to 0x82, "EM" to 0x98, "TIM_PV" to 0x89, "CNT_PV" to 0x89)
private val finsBitAreas = mapOf("CIO" to 0x30, "WR" to 0x31, "HR" to 0x32, "AR" to 0x33, "DM" to 0x02, "EM" to 0x0a)
private fun be16(data: ByteArray, offset: Int) = (data[offset].u() shl 8) or data[offset + 1].u(); private fun be32(value: Int) = byteArrayOf((value ushr 24).toByte(), (value ushr 16).toByte(), (value ushr 8).toByte(), value.toByte()); private fun be32Value(data: ByteArray, offset: Int) = (data[offset].u() shl 24) or (data[offset + 1].u() shl 16) or (data[offset + 2].u() shl 8) or data[offset + 3].u(); private fun Byte.u() = toInt() and 0xff
private fun Any?.number(): Double = when (this) { is Number -> toDouble(); is String -> trim().toDoubleOrNull() ?: throw IllegalArgumentException("不是数值: $this"); is Boolean -> if (this) 1.0 else 0.0; else -> throw IllegalArgumentException("不是数值") }; private fun Any?.bool(): Boolean = when (this) { is Boolean -> this; is Number -> toDouble() != 0.0; is String -> trim().lowercase() in setOf("true", "1", "on"); else -> throw IllegalArgumentException("不是布尔值") }
private fun Map<String, String>.host() = (this["host"] ?: this["ip_address"] ?: this["ipAddress"] ?: "").trim(); private fun Map<String, String>.int(keys: List<String>, default: Int) = keys.firstNotNullOfOrNull { this[it] }?.trim()?.toIntOrNull() ?: default; private fun Map<String, String>.double(keys: List<String>, default: Double) = keys.firstNotNullOfOrNull { this[it] }?.trim()?.toDoubleOrNull() ?: default
private fun validateInt(values: Map<String, String>, keys: List<String>, default: Int, range: IntRange): String? { val text = keys.firstNotNullOfOrNull { values[it] }?.trim(); val value = text?.toIntOrNull() ?: if (text == null) default else return "必须是整数"; return if (value in range) null else "必须在 ${range.first} 到 ${range.last} 之间" }; private fun Long.elapsed() = Duration.ofNanos(System.nanoTime() - this)
private class FinsException(message: String) : RuntimeException(message)

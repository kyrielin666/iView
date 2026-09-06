package ai.moying.iview.protocol.mitsubishi.mc3e

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
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

class MitsubishiMc3eDriver : ProtocolDriver {
    override val protocolType: String = PROTOCOL_TYPE

    override fun validate(device: DeviceDefinition): ValidationResult {
        val values = device.connectionProperties
        val issues = buildList {
            if (values.host().isBlank()) add(ValidationIssue("host", "设备地址不能为空"))
            validateInteger(values, listOf("port"), 5_000, 1..65_535)?.let { add(ValidationIssue("port", it)) }
            validateInteger(values, listOf("timeout"), 5_000, 100..300_000)?.let { add(ValidationIssue("timeout", it)) }
            validateInteger(values, listOf("network_number", "networkNumber"), 0, 0..255)
                ?.let { add(ValidationIssue("network_number", it)) }
            validateInteger(values, listOf("pc_number", "pcNumber", "plc_number", "plcNumber"), 0xff, 0..255)
                ?.let { add(ValidationIssue("pc_number", it)) }
            validateInteger(values, listOf("module_io", "moduleIo"), 0x03ff, 0..0xffff)
                ?.let { add(ValidationIssue("module_io", it)) }
            validateInteger(values, listOf("module_station", "moduleStation"), 0, 0..255)
                ?.let { add(ValidationIssue("module_station", it)) }
            validateInteger(values, listOf("monitoring_timer", "monitoringTimer"), 10, 1..0xffff)
                ?.let { add(ValidationIssue("monitoring_timer", it)) }
        }
        return ValidationResult(issues)
    }

    override suspend fun diagnose(device: DeviceDefinition): DiagnosticResult = withContext(Dispatchers.IO) {
        val validationStarted = System.nanoTime()
        val validation = validate(device)
        val checks = mutableListOf(
            DiagnosticCheck(
                name = "配置校验",
                successful = validation.valid,
                elapsed = validationStarted.elapsed(),
                detail = validation.issues.joinToString("；") { "${it.field}: ${it.message}" }.ifBlank { "配置有效" },
            )
        )
        if (!validation.valid) return@withContext DiagnosticResult(checks)

        val config = Mc3eConnectionConfig.from(device)
        val tcpStarted = System.nanoTime()
        val outcome = runCatching {
            Socket().use { socket -> socket.connect(InetSocketAddress(config.host, config.port), config.timeoutMs) }
        }
        checks += DiagnosticCheck(
            name = "MC 3E TCP 端口连通",
            successful = outcome.isSuccess,
            elapsed = tcpStarted.elapsed(),
            detail = outcome.exceptionOrNull()?.message ?: "${config.host}:${config.port} 可以建立连接",
        )
        DiagnosticResult(checks)
    }

    override suspend fun connect(device: DeviceDefinition): ProtocolSession = withContext(Dispatchers.IO) {
        val validation = validate(device)
        require(validation.valid) { validation.issues.joinToString("；") { "${it.field}: ${it.message}" } }
        MitsubishiMc3eSession(device, Mc3eConnectionConfig.from(device)).apply { open() }
    }

    private companion object {
        const val PROTOCOL_TYPE = "mits_mc_3e"
    }
}

private data class Mc3eConnectionConfig(
    val host: String,
    val port: Int,
    val timeoutMs: Int,
    val route: Mc3eRoute,
    val monitoringTimer: Int,
) {
    companion object {
        fun from(device: DeviceDefinition): Mc3eConnectionConfig {
            val values = device.connectionProperties
            return Mc3eConnectionConfig(
                host = values.host(),
                port = values.configInt(listOf("port"), 5_000),
                timeoutMs = values.configInt(listOf("timeout"), 5_000),
                route = Mc3eRoute(
                    networkNumber = values.configInt(listOf("network_number", "networkNumber"), 0),
                    pcNumber = values.configInt(listOf("pc_number", "pcNumber", "plc_number", "plcNumber"), 0xff),
                    moduleIo = values.configInt(listOf("module_io", "moduleIo"), 0x03ff),
                    moduleStation = values.configInt(listOf("module_station", "moduleStation"), 0),
                ),
                monitoringTimer = values.configInt(listOf("monitoring_timer", "monitoringTimer"), 10),
            )
        }
    }
}

internal data class Mc3eRoute(
    val networkNumber: Int = 0,
    val pcNumber: Int = 0xff,
    val moduleIo: Int = 0x03ff,
    val moduleStation: Int = 0,
)

private class MitsubishiMc3eSession(
    private val device: DeviceDefinition,
    private val config: Mc3eConnectionConfig,
) : ProtocolSession {
    private val lock = ReentrantLock()
    private var socket: Socket? = null
    private var input: DataInputStream? = null
    private var output: DataOutputStream? = null

    override val connected: Boolean
        get() = lock.withLock { socket?.let { it.isConnected && !it.isClosed } == true }

    fun open() = lock.withLock { connectSocket() }

    override suspend fun read(points: List<PointDefinition>): List<PointValue> = withContext(Dispatchers.IO) {
        points.map { point ->
            val observedAt = Instant.now()
            runCatching { readPoint(point) }.fold(
                onSuccess = { PointValue(device.id, point.id, observedAt, Instant.now(), it, ValueQuality.GOOD, PROTOCOL_TYPE) },
                onFailure = {
                    PointValue(device.id, point.id, observedAt, Instant.now(), null, quality(it), PROTOCOL_TYPE, it.message)
                },
            )
        }
    }

    override suspend fun write(writes: List<PointWrite>): List<WriteResult> = withContext(Dispatchers.IO) {
        writes.map { write ->
            when {
                write.point.access == PointAccess.READ_ONLY -> WriteResult(false, "点位 ${write.point.code} 是只读点")
                runCatching { Mc3ePointConfig.from(write.point).address.device == "X" }.getOrDefault(false) ->
                    WriteResult(false, "三菱 X 输入继电器不允许写入")
                else -> runCatching { writePoint(write.point, write.value) }
                    .fold({ WriteResult(true) }, { WriteResult(false, it.message) })
            }
        }
    }

    private fun readPoint(point: PointDefinition): Any? {
        val pointConfig = Mc3ePointConfig.from(point)
        val raw = if (point.dataType == PointDataType.BOOLEAN && pointConfig.address.bitDevice) {
            val response = request(buildMc3eReadFrame(pointConfig.address, 1, true, config.route, config.monitoringTimer))
            requirePayload(response, 1, "位读取")
            response[0].toInt() and 0xf0 != 0
        } else {
            val count = point.dataType.wordCount()
            val response = request(buildMc3eReadFrame(pointConfig.address, count, false, config.route, config.monitoringTimer))
            requirePayload(response, count * 2, "字读取")
            decodeWords(response.copyOfRange(0, count * 2), point.dataType, pointConfig.wordOrder)
        }
        return pointConfig.applyReadScale(raw)
    }

    private fun writePoint(point: PointDefinition, value: Any?) {
        val pointConfig = Mc3ePointConfig.from(point)
        if (point.dataType == PointDataType.BOOLEAN && pointConfig.address.bitDevice) {
            request(
                buildMc3eWriteFrame(
                    pointConfig.address,
                    1,
                    true,
                    byteArrayOf(if (value.asBoolean()) 0x10 else 0x00),
                    config.route,
                    config.monitoringTimer,
                )
            )
        } else {
            val data = encodeWords(pointConfig.removeWriteScale(value), point.dataType, pointConfig.wordOrder)
            request(
                buildMc3eWriteFrame(
                    pointConfig.address,
                    data.size / 2,
                    false,
                    data,
                    config.route,
                    config.monitoringTimer,
                )
            )
        }
    }

    private fun request(frame: ByteArray): ByteArray = lock.withLock {
        try {
            exchange(frame)
        } catch (first: IOException) {
            closeSocket()
            connectSocket()
            exchange(frame)
        }
    }

    private fun exchange(frame: ByteArray): ByteArray {
        val sink = output ?: throw EOFException("MC 3E 连接未建立")
        sink.write(frame)
        sink.flush()

        val source = input ?: throw EOFException("MC 3E 连接未建立")
        val header = ByteArray(9).also(source::readFully)
        if (header[0].unsigned() != 0xd0 || header[1].unsigned() != 0x00) {
            throw Mc3eProtocolException("MC 3E 响应副头部错误")
        }
        if (!header.matches(config.route)) throw Mc3eProtocolException("MC 3E 响应路由与请求不匹配")
        val responseLength = littleEndianUnsignedShort(header, 7)
        if (responseLength !in 2..65_535) throw Mc3eProtocolException("MC 3E 响应长度不合法: $responseLength")
        val body = ByteArray(responseLength).also(source::readFully)
        val endCode = littleEndianUnsignedShort(body, 0)
        if (endCode != 0) throw Mc3eProtocolException("MC 3E PLC 异常响应: 0x${endCode.toString(16).uppercase().padStart(4, '0')}")
        return body.copyOfRange(2, body.size)
    }

    private fun connectSocket() {
        closeSocket()
        val next = Socket()
        next.soTimeout = config.timeoutMs
        next.tcpNoDelay = true
        next.keepAlive = true
        next.connect(InetSocketAddress(config.host, config.port), config.timeoutMs)
        socket = next
        input = DataInputStream(next.getInputStream())
        output = DataOutputStream(next.getOutputStream())
    }

    override fun close() = lock.withLock { closeSocket() }

    private fun closeSocket() {
        runCatching { socket?.close() }
        socket = null
        input = null
        output = null
    }

    private fun quality(error: Throwable): ValueQuality = when (error) {
        is SocketTimeoutException -> ValueQuality.TIMEOUT
        is IOException -> ValueQuality.OFFLINE
        is IllegalArgumentException -> ValueQuality.BAD_CONFIGURATION
        else -> ValueQuality.BAD_RESPONSE
    }

    private companion object {
        const val PROTOCOL_TYPE = "mits_mc_3e"
    }
}

internal data class Mc3eAddress(
    val device: String,
    val offset: Int,
    val deviceCode: Int,
    val bitDevice: Boolean,
)

private data class Mc3ePointConfig(
    val address: Mc3eAddress,
    val wordOrder: Mc3eWordOrder,
    val valueScale: Double,
    val valueOffset: Double,
) {
    fun applyReadScale(value: Any?): Any? =
        if (valueScale == 1.0 && valueOffset == 0.0) value else value.asNumber() * valueScale + valueOffset

    fun removeWriteScale(value: Any?): Any? {
        require(valueScale != 0.0) { "value_scale 不能为 0" }
        return if (valueScale == 1.0 && valueOffset == 0.0) value else (value.asNumber() - valueOffset) / valueScale
    }

    companion object {
        fun from(point: PointDefinition): Mc3ePointConfig {
            require(point.dataType !in setOf(PointDataType.STRING, PointDataType.BYTES)) {
                "MC 3E 暂不支持数据类型: ${point.dataType}"
            }
            val values = point.properties
            val prefix = (values["register_type"] ?: values["registerType"]).orEmpty().trim().uppercase()
            val resolvedAddress = point.address.trim().let { raw ->
                if (prefix.isNotEmpty() && raw.firstOrNull()?.isDigit() == true) prefix + raw else raw
            }
            val parsed = parseMc3eAddress(resolvedAddress)
            val scale = values.configInt(listOf("address_scale", "addressScale"), 1)
            val offset = values.configInt(listOf("address_offset", "addressOffset"), 0)
            val calculated = parsed.offset.toLong() * scale + offset
            require(calculated in 0..0xff_ffff) { "点位地址计算结果超出 3 字节范围: $calculated" }
            val orderName = (values["word_order"] ?: values["wordOrder"] ?: "ABCD").trim().uppercase()
            val order = runCatching { Mc3eWordOrder.valueOf(orderName) }
                .getOrElse { throw IllegalArgumentException("不支持的 MC 3E 字序: $orderName") }
            return Mc3ePointConfig(
                address = parsed.copy(offset = calculated.toInt()),
                wordOrder = order,
                valueScale = values.configDouble(listOf("value_scale", "valueScale"), 1.0),
                valueOffset = values.configDouble(listOf("value_offset", "valueOffset"), 0.0),
            )
        }
    }
}

internal enum class Mc3eWordOrder { ABCD, CDAB, BADC }

internal fun parseMc3eAddress(raw: String): Mc3eAddress {
    val match = ADDRESS_PATTERN.matchEntire(raw.trim().uppercase())
        ?: throw IllegalArgumentException("无效的 MC 3E 地址: $raw")
    val device = match.groupValues[1]
    val metadata = DEVICE_TYPES[device] ?: throw IllegalArgumentException("不支持的 MC 3E 软元件类型: $device")
    val offset = match.groupValues[2].toIntOrNull(metadata.radix)
        ?: throw IllegalArgumentException("无效的 $device 地址: $raw（${metadata.radix} 进制）")
    require(offset in 0..0xff_ffff) { "MC 3E 地址超出 3 字节范围: $raw" }
    return Mc3eAddress(device, offset, metadata.code, metadata.bitDevice)
}

internal fun buildMc3eReadFrame(
    address: Mc3eAddress,
    count: Int,
    bitAccess: Boolean,
    route: Mc3eRoute = Mc3eRoute(),
    monitoringTimer: Int = 10,
): ByteArray {
    require(count in 1..0xffff) { "MC 3E 读取点数必须在 1 到 65535 之间" }
    return buildMc3eRequest(
        command = 0x0401,
        subcommand = if (bitAccess) 1 else 0,
        address = address,
        count = count,
        data = byteArrayOf(),
        route = route,
        monitoringTimer = monitoringTimer,
    )
}

internal fun buildMc3eWriteFrame(
    address: Mc3eAddress,
    count: Int,
    bitAccess: Boolean,
    data: ByteArray,
    route: Mc3eRoute = Mc3eRoute(),
    monitoringTimer: Int = 10,
): ByteArray {
    require(count in 1..0xffff) { "MC 3E 写入点数必须在 1 到 65535 之间" }
    val expected = if (bitAccess) (count + 1) / 2 else count * 2
    require(data.size == expected) { "MC 3E 写入数据长度不匹配: ${data.size} != $expected" }
    return buildMc3eRequest(0x1401, if (bitAccess) 1 else 0, address, count, data, route, monitoringTimer)
}

private fun buildMc3eRequest(
    command: Int,
    subcommand: Int,
    address: Mc3eAddress,
    count: Int,
    data: ByteArray,
    route: Mc3eRoute,
    monitoringTimer: Int,
): ByteArray {
    val requestLength = 12 + data.size
    return byteArrayOf(
        0x50, 0x00,
        route.networkNumber.toByte(), route.pcNumber.toByte(),
        route.moduleIo.toByte(), (route.moduleIo ushr 8).toByte(), route.moduleStation.toByte(),
        requestLength.toByte(), (requestLength ushr 8).toByte(),
        monitoringTimer.toByte(), (monitoringTimer ushr 8).toByte(),
        command.toByte(), (command ushr 8).toByte(),
        subcommand.toByte(), (subcommand ushr 8).toByte(),
        address.offset.toByte(), (address.offset ushr 8).toByte(), (address.offset ushr 16).toByte(),
        address.deviceCode.toByte(), count.toByte(), (count ushr 8).toByte(),
        *data,
    )
}

internal fun decodeWords(source: ByteArray, type: PointDataType, order: Mc3eWordOrder): Any {
    val wordCount = type.wordCount()
    require(source.size >= wordCount * 2) { "MC 3E 字响应长度不足" }
    val words = (0 until wordCount).map { littleEndianUnsignedShort(source, it * 2) }
        .let { if (order == Mc3eWordOrder.ABCD) it else it.reversed() }
    var bits = 0L
    words.forEachIndexed { index, word -> bits = bits or (word.toLong() shl (index * 16)) }
    return when (type) {
        PointDataType.BOOLEAN -> bits != 0L
        PointDataType.INT16 -> bits.toShort()
        PointDataType.UINT16 -> bits.toInt() and 0xffff
        PointDataType.INT32 -> bits.toInt()
        PointDataType.UINT32 -> bits and 0xffff_ffffL
        PointDataType.INT64, PointDataType.UINT64 -> bits
        PointDataType.FLOAT32 -> Float.fromBits(bits.toInt())
        PointDataType.FLOAT64 -> Double.fromBits(bits)
        else -> throw IllegalArgumentException("MC 3E 暂不支持数据类型: $type")
    }
}

internal fun encodeWords(value: Any?, type: PointDataType, order: Mc3eWordOrder): ByteArray {
    val bits = when (type) {
        PointDataType.BOOLEAN -> if (value.asBoolean()) 1L else 0L
        PointDataType.INT16, PointDataType.UINT16 -> value.asNumber().toLong() and 0xffff
        PointDataType.INT32, PointDataType.UINT32 -> value.asNumber().toLong() and 0xffff_ffffL
        PointDataType.INT64, PointDataType.UINT64 -> value.asNumber().toLong()
        PointDataType.FLOAT32 -> value.asNumber().toFloat().toBits().toLong() and 0xffff_ffffL
        PointDataType.FLOAT64 -> value.asNumber().toBits()
        else -> throw IllegalArgumentException("MC 3E 暂不支持数据类型: $type")
    }
    val words = (0 until type.wordCount()).map { ((bits ushr (it * 16)) and 0xffff).toInt() }
        .let { if (order == Mc3eWordOrder.ABCD) it else it.reversed() }
    return words.flatMap { listOf(it.toByte(), (it ushr 8).toByte()) }.toByteArray()
}

private fun PointDataType.wordCount(): Int = when (this) {
    PointDataType.BOOLEAN, PointDataType.INT16, PointDataType.UINT16 -> 1
    PointDataType.INT32, PointDataType.UINT32, PointDataType.FLOAT32 -> 2
    PointDataType.INT64, PointDataType.UINT64, PointDataType.FLOAT64 -> 4
    else -> throw IllegalArgumentException("MC 3E 暂不支持数据类型: $this")
}

private fun ByteArray.matches(route: Mc3eRoute): Boolean =
    this[2].unsigned() == route.networkNumber &&
        this[3].unsigned() == route.pcNumber &&
        littleEndianUnsignedShort(this, 4) == route.moduleIo &&
        this[6].unsigned() == route.moduleStation

private fun requirePayload(payload: ByteArray, minimum: Int, operation: String) {
    if (payload.size < minimum) throw Mc3eProtocolException("MC 3E $operation 响应长度不足: ${payload.size} < $minimum")
}

private class Mc3eProtocolException(message: String) : RuntimeException(message)

private data class DeviceType(val code: Int, val radix: Int, val bitDevice: Boolean)

private val DEVICE_TYPES = mapOf(
    "X" to DeviceType(0x9c, 8, true), "Y" to DeviceType(0x9d, 8, true),
    "M" to DeviceType(0x90, 10, true), "SM" to DeviceType(0x91, 10, true),
    "L" to DeviceType(0x92, 10, true), "F" to DeviceType(0x93, 10, true),
    "V" to DeviceType(0x94, 10, true), "B" to DeviceType(0xa0, 16, true),
    "SB" to DeviceType(0xa1, 16, true), "D" to DeviceType(0xa8, 10, false),
    "SD" to DeviceType(0xa9, 10, false), "W" to DeviceType(0xb4, 16, false),
    "SW" to DeviceType(0xb5, 16, false), "R" to DeviceType(0xaf, 10, false),
    "TC" to DeviceType(0xc0, 10, true), "TS" to DeviceType(0xc1, 10, true),
    "TN" to DeviceType(0xc2, 10, false), "CC" to DeviceType(0xc3, 10, true),
    "CS" to DeviceType(0xc4, 10, true), "CN" to DeviceType(0xc5, 10, false),
    "S" to DeviceType(0x98, 10, true),
)

private val ADDRESS_PATTERN = Regex("^([A-Z]+)([0-9A-F]+)$")

private fun Map<String, String>.host(): String = (this["host"] ?: this["ip_address"] ?: this["ipAddress"] ?: "").trim()

private fun Map<String, String>.configInt(keys: List<String>, default: Int): Int {
    val raw = keys.firstNotNullOfOrNull { this[it] }?.trim() ?: return default
    return parseFlexibleInt(raw) ?: default
}

private fun Map<String, String>.configDouble(keys: List<String>, default: Double): Double =
    keys.firstNotNullOfOrNull { this[it] }?.trim()?.toDoubleOrNull() ?: default

private fun validateInteger(values: Map<String, String>, keys: List<String>, default: Int, range: IntRange): String? {
    val raw = keys.firstNotNullOfOrNull { values[it] }?.trim()
    val parsed = raw?.let(::parseFlexibleInt) ?: if (raw == null) default else return "必须是整数"
    return if (parsed in range) null else "必须在 ${range.first} 到 ${range.last} 之间"
}

private fun parseFlexibleInt(raw: String): Int? =
    if (raw.startsWith("0x", ignoreCase = true)) raw.drop(2).toIntOrNull(16) else raw.toIntOrNull()

private fun Any?.asNumber(): Double = when (this) {
    is Number -> toDouble()
    is String -> trim().toDoubleOrNull() ?: throw IllegalArgumentException("不是数值: $this")
    is Boolean -> if (this) 1.0 else 0.0
    else -> throw IllegalArgumentException("不是数值: $this")
}

private fun Any?.asBoolean(): Boolean = when (this) {
    is Boolean -> this
    is Number -> toDouble() != 0.0
    is String -> when (trim().lowercase()) {
        "true", "1", "on" -> true
        "false", "0", "off" -> false
        else -> throw IllegalArgumentException("不是布尔值: $this")
    }
    else -> throw IllegalArgumentException("不是布尔值: $this")
}

private fun littleEndianUnsignedShort(source: ByteArray, offset: Int): Int =
    source[offset].unsigned() or (source[offset + 1].unsigned() shl 8)

private fun Byte.unsigned(): Int = toInt() and 0xff
private fun Long.elapsed(): Duration = Duration.ofNanos(System.nanoTime() - this)

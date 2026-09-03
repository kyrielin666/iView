package ai.moying.iview.protocol.modbus

import ai.moying.iview.core.device.DeviceDefinition
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
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.time.Duration
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.withLock
import java.util.concurrent.locks.ReentrantLock

class ModbusTcpDriver : ProtocolDriver {
    override val protocolType = "modbus_tcp"

    override fun validate(device: DeviceDefinition): ValidationResult {
        val config = device.connectionProperties
        val issues = buildList {
            if (config.host().isBlank()) add(ValidationIssue("host", "设备地址不能为空"))
            config.intValue("port", 502).takeUnless { it in 1..65_535 }
                ?.let { add(ValidationIssue("port", "端口必须在 1 到 65535 之间")) }
            config.intValue("slave_id", config.intValue("slaveId", 1)).takeUnless { it in 1..255 }
                ?.let { add(ValidationIssue("slave_id", "从站 ID 必须在 1 到 255 之间")) }
            config.intValue("timeout", 5_000).takeUnless { it in 100..300_000 }
                ?.let { add(ValidationIssue("timeout", "超时必须在 100 到 300000 毫秒之间")) }
            config.intValue("max_batch_size", config.intValue("maxBatchSize", 1)).takeUnless { it in 1..125 }
                ?.let { add(ValidationIssue("max_batch_size", "最大批量读取长度必须在 1 到 125 之间")) }
        }
        return ValidationResult(issues)
    }

    override suspend fun diagnose(device: DeviceDefinition): DiagnosticResult = withContext(Dispatchers.IO) {
        val validationStart = System.nanoTime()
        val validation = validate(device)
        val checks = mutableListOf(
            DiagnosticCheck(
                name = "配置校验",
                successful = validation.valid,
                elapsed = validationStart.elapsed(),
                detail = validation.issues.joinToString("；") { "${it.field}: ${it.message}" }.ifBlank { "配置有效" },
            )
        )
        if (!validation.valid) return@withContext DiagnosticResult(checks)

        val config = ModbusConnectionConfig.from(device)
        val tcpStart = System.nanoTime()
        val outcome = runCatching {
            Socket().use { socket -> socket.connect(InetSocketAddress(config.host, config.port), config.timeoutMs) }
        }
        checks += DiagnosticCheck(
            name = "TCP 端口连通",
            successful = outcome.isSuccess,
            elapsed = tcpStart.elapsed(),
            detail = outcome.exceptionOrNull()?.message ?: "${config.host}:${config.port} 可以建立连接",
        )
        DiagnosticResult(checks)
    }

    override suspend fun connect(device: DeviceDefinition): ProtocolSession = withContext(Dispatchers.IO) {
        val validation = validate(device)
        require(validation.valid) { validation.issues.joinToString("；") { "${it.field}: ${it.message}" } }
        ModbusTcpSession(device, ModbusConnectionConfig.from(device)).apply { open() }
    }
}

private data class ModbusConnectionConfig(
    val host: String,
    val port: Int,
    val slaveId: Int,
    val timeoutMs: Int,
    val maxBatchSize: Int,
) {
    companion object {
        fun from(device: DeviceDefinition): ModbusConnectionConfig {
            val values = device.connectionProperties
            return ModbusConnectionConfig(
                host = values.host(),
                port = values.intValue("port", 502),
                slaveId = values.intValue("slave_id", values.intValue("slaveId", 1)),
                timeoutMs = values.intValue("timeout", 5_000),
                maxBatchSize = values.intValue("max_batch_size", values.intValue("maxBatchSize", 1)),
            )
        }
    }
}

private class ModbusTcpSession(
    private val device: DeviceDefinition,
    private val config: ModbusConnectionConfig,
) : ProtocolSession {
    private val lock = ReentrantLock()
    private val transactionId = AtomicInteger(0)
    private var socket: Socket? = null
    private var input: DataInputStream? = null
    private var output: DataOutputStream? = null

    override val connected: Boolean
        get() = lock.withLock { socket?.let { it.isConnected && !it.isClosed } == true }

    fun open() = lock.withLock { connectSocket() }

    override suspend fun read(points: List<PointDefinition>): List<PointValue> = withContext(Dispatchers.IO) {
        if (config.maxBatchSize > 1) return@withContext readBatched(points)
        points.map { point ->
            val now = Instant.now()
            runCatching { readPoint(point) }.fold(
                onSuccess = { value -> PointValue(device.id, point.id, now, Instant.now(), value, ValueQuality.GOOD, protocolType()) },
                onFailure = { error -> PointValue(device.id, point.id, now, Instant.now(), null, quality(error), protocolType(), error.message) },
            )
        }
    }

    private fun readBatched(points: List<PointDefinition>): List<PointValue> {
        val results = arrayOfNulls<PointValue>(points.size)
        val configured = buildList {
            points.forEachIndexed { index, point ->
                runCatching { ModbusPointConfig.from(point) }.fold(
                    onSuccess = { add(BatchPoint(index, point, it)) },
                    onFailure = { results[index] = failedValue(point, it) },
                )
            }
        }
        configured.groupBy { it.config.registerType }.values.forEach { group ->
            buildRanges(group.sortedBy { it.config.address }).forEach { range ->
                runCatching { readRange(range) }.fold(
                    onSuccess = { values -> values.forEach { (index, value) -> results[index] = value } },
                    onFailure = { error -> range.points.forEach { results[it.index] = failedValue(it.point, error) } },
                )
            }
        }
        return points.mapIndexed { index, point -> results[index] ?: failedValue(point, IllegalStateException("点位未被采集")) }
    }

    private fun buildRanges(points: List<BatchPoint>): List<BatchRange> {
        if (points.isEmpty()) return emptyList()
        val ranges = mutableListOf<BatchRange>()
        var current = BatchRange(points.first().config.address, points.first().width, mutableListOf(points.first()))
        points.drop(1).forEach { point ->
            val newQuantity = point.config.address + point.width - current.start
            if (newQuantity <= config.maxBatchSize) {
                current.quantity = maxOf(current.quantity, newQuantity)
                current.points += point
            } else {
                ranges += current
                current = BatchRange(point.config.address, point.width, mutableListOf(point))
            }
        }
        ranges += current
        return ranges
    }

    private fun readRange(range: BatchRange): Map<Int, PointValue> {
        val first = range.points.first()
        val function = first.config.function
        val response = request(function, byteArrayOf(
            (range.start ushr 8).toByte(), range.start.toByte(),
            (range.quantity ushr 8).toByte(), range.quantity.toByte(),
        ))
        require(response.isNotEmpty()) { "Modbus 响应缺少字节计数" }
        val byteCount = response[0].toInt() and 0xff
        require(response.size == byteCount + 1) { "Modbus 响应长度不匹配" }
        val data = response.copyOfRange(1, response.size)
        return range.points.associate { item ->
            val raw = if (item.config.bitRegister) {
                val bitOffset = item.config.address - range.start
                val byteIndex = bitOffset / 8
                require(byteIndex < data.size) { "Modbus 位响应长度不足" }
                data[byteIndex].toInt() ushr (bitOffset % 8) and 1 == 1
            } else {
                val byteOffset = (item.config.address - range.start) * 2
                val byteLength = item.width * 2
                require(byteOffset + byteLength <= data.size) { "Modbus 寄存器响应长度不足" }
                decodeRegisters(data.copyOfRange(byteOffset, byteOffset + byteLength), item.point.dataType, item.config.byteOrder)
            }
            val now = Instant.now()
            item.index to PointValue(device.id, item.point.id, now, now, item.config.applyReadScale(raw), ValueQuality.GOOD, protocolType())
        }
    }

    override suspend fun write(writes: List<PointWrite>): List<WriteResult> = withContext(Dispatchers.IO) {
        writes.map { write ->
            if (write.point.access == ai.moying.iview.core.device.PointAccess.READ_ONLY) {
                WriteResult(false, "点位 ${write.point.code} 是只读点")
            } else {
                runCatching { writePoint(write.point, write.value) }
                    .fold({ WriteResult(true) }, { WriteResult(false, it.message) })
            }
        }
    }

    private fun readPoint(point: PointDefinition): Any? {
        val pointConfig = ModbusPointConfig.from(point)
        val quantity = if (pointConfig.bitRegister) 1 else point.dataType.registerCount()
        val function = pointConfig.function
        val payload = byteArrayOf(
            (pointConfig.address ushr 8).toByte(), pointConfig.address.toByte(),
            (quantity ushr 8).toByte(), quantity.toByte(),
        )
        val response = request(function, payload)
        require(response.isNotEmpty()) { "Modbus 响应缺少字节计数" }
        val byteCount = response[0].toInt() and 0xff
        require(response.size == byteCount + 1) { "Modbus 响应长度不匹配" }
        val bytes = response.copyOfRange(1, response.size)
        val raw = if (pointConfig.bitRegister) {
            require(bytes.isNotEmpty()) { "Modbus 位响应为空" }
            bytes[0].toInt() and 1 == 1
        } else {
            decodeRegisters(bytes, point.dataType, pointConfig.byteOrder)
        }
        return pointConfig.applyReadScale(raw)
    }

    private fun writePoint(point: PointDefinition, value: Any?) {
        val pointConfig = ModbusPointConfig.from(point)
        when (pointConfig.registerType) {
            "coil" -> {
                val enabled = value.asBoolean()
                request(5, byteArrayOf(
                    (pointConfig.address ushr 8).toByte(), pointConfig.address.toByte(),
                    if (enabled) 0xff.toByte() else 0, 0,
                ))
            }
            "holding" -> {
                val data = encodeRegisters(pointConfig.removeWriteScale(value), point.dataType, pointConfig.byteOrder)
                val registers = data.size / 2
                request(16, byteArrayOf(
                    (pointConfig.address ushr 8).toByte(), pointConfig.address.toByte(),
                    (registers ushr 8).toByte(), registers.toByte(), data.size.toByte(), *data,
                ))
            }
            else -> throw IllegalArgumentException("${pointConfig.registerType} 寄存器不支持写入")
        }
    }

    private fun request(function: Int, payload: ByteArray): ByteArray = lock.withLock {
        try {
            exchange(function, payload)
        } catch (first: IOException) {
            closeSocket()
            connectSocket()
            exchange(function, payload)
        }
    }

    private fun exchange(function: Int, payload: ByteArray): ByteArray {
        val tx = transactionId.updateAndGet { (it + 1) and 0xffff }
        val bodyLength = 2 + payload.size
        val out = output ?: throw EOFException("Modbus 连接未建立")
        out.writeShort(tx)
        out.writeShort(0)
        out.writeShort(bodyLength)
        out.writeByte(config.slaveId)
        out.writeByte(function)
        out.write(payload)
        out.flush()

        val source = input ?: throw EOFException("Modbus 连接未建立")
        val responseTx = source.readUnsignedShort()
        val protocol = source.readUnsignedShort()
        val length = source.readUnsignedShort()
        source.readUnsignedByte()
        require(responseTx == tx) { "Modbus 事务号不匹配: $responseTx != $tx" }
        require(protocol == 0) { "Modbus 协议标识不正确: $protocol" }
        require(length in 2..260) { "Modbus 响应长度不合法: $length" }
        val responseFunction = source.readUnsignedByte()
        val response = ByteArray(length - 2)
        source.readFully(response)
        if (responseFunction == (function or 0x80)) {
            throw ModbusException(response.firstOrNull()?.toInt()?.and(0xff) ?: 0)
        }
        require(responseFunction == function) { "Modbus 功能码不匹配: $responseFunction != $function" }
        return response
    }

    private fun connectSocket() {
        closeSocket()
        val next = Socket()
        next.soTimeout = config.timeoutMs
        next.tcpNoDelay = true
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

    private fun protocolType() = "modbus_tcp"
    private fun failedValue(point: PointDefinition, error: Throwable): PointValue {
        val now = Instant.now()
        return PointValue(device.id, point.id, now, now, null, quality(error), protocolType(), error.message)
    }
    private fun quality(error: Throwable) = when (error) {
        is java.net.SocketTimeoutException -> ValueQuality.TIMEOUT
        is IOException -> ValueQuality.OFFLINE
        is IllegalArgumentException -> ValueQuality.BAD_CONFIGURATION
        else -> ValueQuality.BAD_RESPONSE
    }
}

private data class BatchPoint(
    val index: Int,
    val point: PointDefinition,
    val config: ModbusPointConfig,
) {
    val width: Int = if (config.bitRegister) 1 else point.dataType.registerCount()
}

private data class BatchRange(
    val start: Int,
    var quantity: Int,
    val points: MutableList<BatchPoint>,
)

private class ModbusException(code: Int) : IOException("Modbus 异常响应: ${exceptionText(code)} ($code)")

private data class ModbusPointConfig(
    val registerType: String,
    val byteOrder: String,
    val address: Int,
    val valueScale: Double,
    val valueOffset: Double,
) {
    val bitRegister get() = registerType == "coil" || registerType == "discrete"
    val function get() = when (registerType) {
        "coil" -> 1
        "discrete" -> 2
        "input" -> 4
        else -> 3
    }

    fun applyReadScale(value: Any?): Any? = if (valueScale == 1.0 && valueOffset == 0.0) value else value.asNumber() * valueScale + valueOffset
    fun removeWriteScale(value: Any?): Any? {
        require(valueScale != 0.0) { "value_scale 不能为 0" }
        return if (valueScale == 1.0 && valueOffset == 0.0) value else (value.asNumber() - valueOffset) / valueScale
    }

    companion object {
        fun from(point: PointDefinition): ModbusPointConfig {
            val values = point.properties
            val register = (values["register_type"] ?: values["registerType"] ?: inferRegister(point.address)).lowercase()
            require(register in setOf("coil", "discrete", "input", "holding")) { "不支持的寄存器类型: $register" }
            val base = parseAddress(point.address)
            val scale = values.intValue("address_scale", values.intValue("addressScale", 1))
            val offset = values.intValue("address_offset", values.intValue("addressOffset", 0))
            val address = base * scale + offset
            require(address in 0..65_535) { "点位地址计算结果超出范围: $address" }
            val order = (values["byte_order"] ?: values["byteOrder"] ?: "big").lowercase()
            require(order in setOf("big", "little", "cdab", "badc")) { "不支持的字节序: $order" }
            return ModbusPointConfig(
                register,
                order,
                address,
                values.doubleValue("value_scale", values.doubleValue("valueScale", 1.0)),
                values.doubleValue("value_offset", values.doubleValue("valueOffset", 0.0)),
            )
        }
    }
}

private fun parseAddress(raw: String): Int {
    val address = raw.trim().toIntOrNull() ?: throw IllegalArgumentException("无效的 Modbus 地址: $raw")
    return when {
        address >= 40_001 -> address - 40_001
        address >= 30_001 -> address - 30_001
        address >= 10_001 -> address - 10_001
        else -> address
    }
}

private fun inferRegister(raw: String): String = raw.trim().toIntOrNull()?.let {
    when {
        it >= 40_001 -> "holding"
        it >= 30_001 -> "input"
        it >= 10_001 -> "discrete"
        else -> "holding"
    }
} ?: "holding"

private fun PointDataType.registerCount(): Int = when (this) {
    PointDataType.BOOLEAN, PointDataType.INT16, PointDataType.UINT16 -> 1
    PointDataType.INT32, PointDataType.UINT32, PointDataType.FLOAT32 -> 2
    PointDataType.INT64, PointDataType.UINT64, PointDataType.FLOAT64 -> 4
    else -> throw IllegalArgumentException("Modbus 暂不支持数据类型: $this")
}

private fun decodeRegisters(source: ByteArray, type: PointDataType, order: String): Any {
    val bytes = applyByteOrder(source, order)
    val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
    return when (type) {
        PointDataType.BOOLEAN -> buffer.short.toInt() != 0
        PointDataType.INT16 -> buffer.short
        PointDataType.UINT16 -> buffer.short.toInt() and 0xffff
        PointDataType.INT32 -> buffer.int
        PointDataType.UINT32 -> buffer.int.toLong() and 0xffff_ffffL
        PointDataType.INT64, PointDataType.UINT64 -> buffer.long
        PointDataType.FLOAT32 -> buffer.float
        PointDataType.FLOAT64 -> buffer.double
        else -> throw IllegalArgumentException("Modbus 暂不支持数据类型: $type")
    }
}

private fun encodeRegisters(value: Any?, type: PointDataType, order: String): ByteArray {
    val bytes = ByteBuffer.allocate(type.registerCount() * 2).order(ByteOrder.BIG_ENDIAN).apply {
        when (type) {
            PointDataType.BOOLEAN -> putShort(if (value.asBoolean()) 1 else 0)
            PointDataType.INT16, PointDataType.UINT16 -> putShort(value.asNumber().toInt().toShort())
            PointDataType.INT32, PointDataType.UINT32 -> putInt(value.asNumber().toLong().toInt())
            PointDataType.INT64, PointDataType.UINT64 -> putLong(value.asNumber().toLong())
            PointDataType.FLOAT32 -> putFloat(value.asNumber().toFloat())
            PointDataType.FLOAT64 -> putDouble(value.asNumber())
            else -> throw IllegalArgumentException("Modbus 暂不支持数据类型: $type")
        }
    }.array()
    return applyByteOrder(bytes, order)
}

private fun applyByteOrder(source: ByteArray, order: String): ByteArray = when (order) {
    "big" -> source.copyOf()
    "little" -> source.reversedArray()
    "cdab" -> if (source.size == 4) byteArrayOf(source[2], source[3], source[0], source[1]) else source.copyOf()
    "badc" -> if (source.size == 4) byteArrayOf(source[1], source[0], source[3], source[2]) else source.copyOf()
    else -> throw IllegalArgumentException("不支持的字节序: $order")
}

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

private fun Map<String, String>.host() = (this["host"] ?: this["ip_address"] ?: "").trim()
private fun Map<String, String>.intValue(key: String, default: Int) = this[key]?.trim()?.toIntOrNull() ?: default
private fun Map<String, String>.doubleValue(key: String, default: Double) = this[key]?.trim()?.toDoubleOrNull() ?: default
private fun Long.elapsed() = Duration.ofNanos(System.nanoTime() - this)
private fun exceptionText(code: Int) = when (code) {
    1 -> "非法功能"
    2 -> "非法数据地址"
    3 -> "非法数据值"
    4 -> "从站设备故障"
    5 -> "确认"
    6 -> "从站设备忙"
    else -> "未知异常"
}

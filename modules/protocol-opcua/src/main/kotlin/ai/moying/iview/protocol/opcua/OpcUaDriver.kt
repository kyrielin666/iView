package ai.moying.iview.protocol.opcua

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
import org.eclipse.milo.opcua.sdk.client.OpcUaClient
import org.eclipse.milo.opcua.sdk.client.identity.UsernameProvider
import org.eclipse.milo.opcua.stack.core.UaException
import org.eclipse.milo.opcua.stack.core.security.SecurityPolicy
import org.eclipse.milo.opcua.stack.core.types.builtin.ByteString
import org.eclipse.milo.opcua.stack.core.types.builtin.DataValue
import org.eclipse.milo.opcua.stack.core.types.builtin.NodeId
import org.eclipse.milo.opcua.stack.core.types.builtin.StatusCode
import org.eclipse.milo.opcua.stack.core.types.builtin.Variant
import org.eclipse.milo.opcua.stack.core.types.builtin.unsigned.UInteger
import org.eclipse.milo.opcua.stack.core.types.builtin.unsigned.ULong
import org.eclipse.milo.opcua.stack.core.types.enumerated.MessageSecurityMode
import org.eclipse.milo.opcua.stack.core.types.enumerated.TimestampsToReturn
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URI
import java.net.SocketTimeoutException
import java.time.Duration
import java.time.Instant
import java.util.Optional
import java.util.concurrent.TimeoutException

class OpcUaDriver : ProtocolDriver {
    override val protocolType: String = PROTOCOL_TYPE

    override fun validate(device: DeviceDefinition): ValidationResult {
        val values = device.connectionProperties
        val endpoint = values.endpointUrl()
        val issues = buildList {
            val uri = runCatching { URI(endpoint) }.getOrNull()
            if (endpoint.isBlank()) {
                add(ValidationIssue("endpoint_url", "OPC UA 端点不能为空"))
            } else if (uri == null || !uri.scheme.equals("opc.tcp", true) || uri.host.isNullOrBlank()) {
                add(ValidationIssue("endpoint_url", "端点必须是有效的 opc.tcp://host:port 地址"))
            } else if (uri.port !in listOf(-1) && uri.port !in 1..65_535) {
                add(ValidationIssue("endpoint_url", "端点端口必须在 1 到 65535 之间"))
            }
            validateInteger(values, "timeout", 5_000, 100..300_000)?.let {
                add(ValidationIssue("timeout", it))
            }
            val policy = values.securityPolicy()
            val mode = values.securityMode()
            if (policy !in SUPPORTED_SECURITY_POLICIES) {
                add(ValidationIssue("security_policy", "不支持的安全策略: $policy"))
            }
            if (mode !in SUPPORTED_SECURITY_MODES) {
                add(ValidationIssue("security_mode", "不支持的消息安全模式: $mode"))
            }
            if (!policy.equals("None", true) || !mode.equals("None", true)) {
                add(ValidationIssue("security_policy", "当前版本仅允许 None；证书安全连接必须在配置受信证书后启用"))
            }
            if (values.password().isNotEmpty() && values.username().isBlank()) {
                add(ValidationIssue("username", "配置密码时必须同时填写用户名"))
            }
        }
        return ValidationResult(issues)
    }

    override suspend fun diagnose(device: DeviceDefinition): DiagnosticResult = withContext(Dispatchers.IO) {
        val validationStarted = System.nanoTime()
        val validation = validate(device)
        val checks = mutableListOf(
            DiagnosticCheck(
                "配置校验",
                validation.valid,
                validationStarted.elapsed(),
                validation.issues.joinToString("；") { "${it.field}: ${it.message}" }.ifBlank { "配置有效" },
            )
        )
        if (!validation.valid) return@withContext DiagnosticResult(checks)

        val config = OpcUaConfig.from(device)
        val tcpStarted = System.nanoTime()
        val outcome = runCatching {
            Socket().use { socket -> socket.connect(InetSocketAddress(config.host, config.port), config.timeoutMs) }
        }
        checks += DiagnosticCheck(
            "OPC UA TCP 端口连通",
            outcome.isSuccess,
            tcpStarted.elapsed(),
            outcome.exceptionOrNull()?.message ?: "${config.host}:${config.port} 可以建立连接",
        )
        DiagnosticResult(checks)
    }

    override suspend fun connect(device: DeviceDefinition): ProtocolSession = withContext(Dispatchers.IO) {
        val validation = validate(device)
        require(validation.valid) {
            validation.issues.joinToString("；") { "${it.field}: ${it.message}" }
        }
        OpcUaSession(device, OpcUaConfig.from(device)).apply { open() }
    }

    private companion object {
        const val PROTOCOL_TYPE = "opcua"
        val SUPPORTED_SECURITY_POLICIES = SecurityPolicy.entries.map { it.name }.toSet()
        val SUPPORTED_SECURITY_MODES = MessageSecurityMode.entries.map { it.name }.toSet()
    }
}

private data class OpcUaConfig(
    val endpointUrl: String,
    val host: String,
    val port: Int,
    val timeoutMs: Int,
    val username: String,
    val password: String,
) {
    companion object {
        fun from(device: DeviceDefinition): OpcUaConfig {
            val values = device.connectionProperties
            val endpoint = values.endpointUrl()
            val uri = URI(endpoint)
            return OpcUaConfig(
                endpointUrl = endpoint,
                host = uri.host,
                port = if (uri.port == -1) 4_840 else uri.port,
                timeoutMs = values["timeout"]?.toIntOrNull() ?: 5_000,
                username = values.username(),
                password = values.password(),
            )
        }
    }
}

private class OpcUaSession(
    private val device: DeviceDefinition,
    private val config: OpcUaConfig,
) : ProtocolSession {
    private val lock = Any()
    private var client: OpcUaClient? = null

    override val connected: Boolean
        get() = synchronized(lock) { client != null }

    fun open() = synchronized(lock) {
        closeClient()
        client = createClient().also { it.connect() }
    }

    override suspend fun read(points: List<PointDefinition>): List<PointValue> = withContext(Dispatchers.IO) {
        points.map { point ->
            val received = Instant.now()
            runCatching { readPoint(point) }.fold(
                onSuccess = { result ->
                    PointValue(
                        device.id,
                        point.id,
                        received,
                        Instant.now(),
                        result.value,
                        result.quality,
                        PROTOCOL_TYPE,
                        result.diagnostic,
                    )
                },
                onFailure = { error ->
                    PointValue(
                        device.id,
                        point.id,
                        received,
                        Instant.now(),
                        null,
                        quality(error),
                        PROTOCOL_TYPE,
                        rootCause(error).message,
                    )
                },
            )
        }
    }

    override suspend fun write(writes: List<PointWrite>): List<WriteResult> = withContext(Dispatchers.IO) {
        writes.map { write ->
            if (write.point.access == PointAccess.READ_ONLY) {
                WriteResult(false, "点位 ${write.point.code} 是只读点")
            } else {
                runCatching { writePoint(write.point, write.value) }
                    .fold({ WriteResult(true) }, { WriteResult(false, rootCause(it).message) })
            }
        }
    }

    private fun readPoint(point: PointDefinition): OpcReadResult = synchronized(lock) {
        val nodeId = opcNodeId(point)
        val dataValue = retryOnce {
            requireClient().readValue(0.0, TimestampsToReturn.Both, nodeId)
        }
        val status = dataValue.statusCode()
        val quality = when {
            status.isGood -> ValueQuality.GOOD
            status.isUncertain -> ValueQuality.UNCERTAIN
            else -> ValueQuality.BAD_RESPONSE
        }
        OpcReadResult(
            normalizeOpcValue(dataValue.value()?.value()),
            quality,
            status.takeUnless(StatusCode::isGood)?.toString(),
        )
    }

    private fun writePoint(point: PointDefinition, value: Any?) = synchronized(lock) {
        val status = retryOnce {
            requireClient().writeValues(
                listOf(opcNodeId(point)),
                listOf(DataValue.valueOnly(opcVariant(point.dataType, value))),
            ).single()
        }
        if (!status.isGood) throw OpcUaStatusException("OPC UA 写入失败: $status")
    }

    private fun createClient(): OpcUaClient = OpcUaClient.create(
        config.endpointUrl,
        { endpoints ->
            endpoints.firstOrNull {
                it.securityPolicyUri == SecurityPolicy.None.uri && it.securityMode == MessageSecurityMode.None
            }.let { Optional.ofNullable(it) }
        },
        {},
        { builder ->
            builder.setRequestTimeout(UInteger.valueOf(config.timeoutMs))
                .setSessionTimeout(UInteger.valueOf(config.timeoutMs.coerceAtLeast(10_000)))
                .setKeepAliveInterval(UInteger.valueOf(config.timeoutMs.coerceAtLeast(1_000)))
                .setKeepAliveTimeout(UInteger.valueOf(config.timeoutMs))
            if (config.username.isNotBlank()) {
                builder.setIdentityProvider(UsernameProvider(config.username, config.password))
            }
        },
    )

    private fun requireClient(): OpcUaClient = client ?: throw IOException("OPC UA 连接未建立")

    private fun <T> retryOnce(block: () -> T): T = try {
        block()
    } catch (first: Exception) {
        val cause = rootCause(first)
        if (cause is OpcUaStatusException || cause is IllegalArgumentException) throw first
        closeClient()
        client = createClient().also { it.connect() }
        block()
    }

    override fun close() = synchronized(lock) { closeClient() }

    private fun closeClient() {
        runCatching { client?.disconnect() }
        client = null
    }

    private fun quality(error: Throwable): ValueQuality = when (val cause = rootCause(error)) {
        is IllegalArgumentException -> ValueQuality.BAD_CONFIGURATION
        is TimeoutException, is SocketTimeoutException -> ValueQuality.TIMEOUT
        is OpcUaStatusException -> ValueQuality.BAD_RESPONSE
        is UaException, is IOException -> ValueQuality.OFFLINE
        else -> ValueQuality.BAD_RESPONSE
    }

    private companion object {
        const val PROTOCOL_TYPE = "opcua"
    }
}

private data class OpcReadResult(val value: Any?, val quality: ValueQuality, val diagnostic: String?)
private class OpcUaStatusException(message: String) : RuntimeException(message)

internal fun opcNodeId(point: PointDefinition): NodeId {
    val raw = (point.properties["node_id"] ?: point.properties["nodeId"] ?: point.address).trim()
    require(raw.isNotBlank()) { "OPC UA NodeId 不能为空" }
    return NodeId.parse(raw)
}

internal fun opcVariant(type: PointDataType, value: Any?): Variant = when (type) {
    PointDataType.BOOLEAN -> Variant(value.asBoolean())
    PointDataType.INT16 -> Variant(value.asLong().toShort())
    PointDataType.UINT16 -> Variant.ofUInt16(org.eclipse.milo.opcua.stack.core.types.builtin.unsigned.UShort.valueOf(value.asLong().toInt()))
    PointDataType.INT32 -> Variant(value.asLong().toInt())
    PointDataType.UINT32 -> Variant.ofUInt32(UInteger.valueOf(value.asLong()))
    PointDataType.INT64 -> Variant(value.asLong())
    PointDataType.UINT64 -> Variant.ofUInt64(ULong.valueOf(value.asString()))
    PointDataType.FLOAT32 -> Variant(value.asDouble().toFloat())
    PointDataType.FLOAT64 -> Variant(value.asDouble())
    PointDataType.STRING -> Variant.ofString(value.asString())
    PointDataType.BYTES -> Variant.ofByteString(ByteString.of(value.asBytes()))
}

internal fun normalizeOpcValue(value: Any?): Any? = when (value) {
    is org.eclipse.milo.opcua.stack.core.types.builtin.unsigned.UByte -> value.toShort()
    is org.eclipse.milo.opcua.stack.core.types.builtin.unsigned.UShort -> value.toInt()
    is UInteger -> value.toLong()
    is ULong -> value.toString()
    is ByteString -> value.bytesOrEmpty()
    else -> value
}

private fun Map<String, String>.endpointUrl(): String =
    (this["endpoint_url"] ?: this["endpointUrl"] ?: this["url"]).orEmpty().trim()

private fun Map<String, String>.securityPolicy(): String =
    (this["security_policy"] ?: this["securityPolicy"] ?: "None").trim()

private fun Map<String, String>.securityMode(): String =
    (this["security_mode"] ?: this["securityMode"] ?: "None").trim()

private fun Map<String, String>.username(): String = (this["username"] ?: "").trim()
private fun Map<String, String>.password(): String = this["password"].orEmpty()

private fun validateInteger(values: Map<String, String>, key: String, default: Int, range: IntRange): String? {
    val raw = values[key]?.trim()
    val value = raw?.toIntOrNull() ?: if (raw == null) default else return "必须是整数"
    return if (value in range) null else "必须在 ${range.first} 到 ${range.last} 之间"
}

private fun Any?.asString(): String = this?.toString() ?: throw IllegalArgumentException("写入值不能为空")
private fun Any?.asLong(): Long = when (this) {
    is Number -> toLong()
    is String -> trim().toLongOrNull() ?: throw IllegalArgumentException("不是整数: $this")
    is Boolean -> if (this) 1L else 0L
    else -> throw IllegalArgumentException("不是整数: $this")
}
private fun Any?.asDouble(): Double = when (this) {
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
private fun Any?.asBytes(): ByteArray = when (this) {
    is ByteArray -> this
    is String -> trim().split(Regex("[\\s,]+"))
        .filter(String::isNotBlank)
        .map { token -> token.removePrefix("0x").toInt(16).also { require(it in 0..255) }.toByte() }
        .toByteArray()
    else -> throw IllegalArgumentException("字节写入值必须是 ByteArray 或十六进制字符串")
}

private fun rootCause(error: Throwable): Throwable {
    var current = error
    while (current.cause != null && current.cause !== current) current = current.cause!!
    return current
}

private fun Long.elapsed(): Duration = Duration.ofNanos(System.nanoTime() - this)

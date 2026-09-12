package ai.moying.iview.protocol.s7

import ai.moying.iview.core.device.DeviceDefinition
import ai.moying.iview.core.device.PointDefinition
import ai.moying.iview.core.device.PointValue
import ai.moying.iview.core.device.ValueQuality
import ai.moying.iview.protocol.DiagnosticCheck
import ai.moying.iview.protocol.DiagnosticResult
import ai.moying.iview.protocol.ProtocolDriver
import ai.moying.iview.protocol.ProtocolSession
import ai.moying.iview.protocol.PointWrite
import ai.moying.iview.protocol.WriteResult
import ai.moying.iview.protocol.ValidationIssue
import ai.moying.iview.protocol.ValidationResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.InetSocketAddress
import java.net.Socket
import java.time.Duration
import java.time.Instant
import org.apache.plc4x.java.DefaultPlcDriverManager
import org.apache.plc4x.java.api.PlcConnection
import org.apache.plc4x.java.api.types.PlcResponseCode
import java.net.SocketTimeoutException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/** Siemens S7 TCP configuration and legacy-address normalizer. */
class S7Driver : ProtocolDriver {
    override val protocolType = "s7comm"

    override fun validate(device: DeviceDefinition): ValidationResult {
        val values = device.connectionProperties
        val issues = buildList {
            if ((values["host"] ?: values["ip_address"]).orEmpty().isBlank()) add(ValidationIssue("host", "设备地址不能为空"))
            check(values, "port", 102, 1..65535)?.let { add(ValidationIssue("port", it)) }
            check(values, "rack", 0, 0..7)?.let { add(ValidationIssue("rack", it)) }
            check(values, "slot", 1, 0..31)?.let { add(ValidationIssue("slot", it)) }
            check(values, "connect_type", values["connectType"]?.toIntOrNull() ?: 1, 1..3)?.let { add(ValidationIssue("connect_type", it)) }
            check(values, "timeout", 5000, 100..300000)?.let { add(ValidationIssue("timeout", it)) }
        }
        return ValidationResult(issues)
    }

    override suspend fun diagnose(device: DeviceDefinition): DiagnosticResult = withContext(Dispatchers.IO) {
        val started = System.nanoTime(); val validation = validate(device)
        val checks = mutableListOf(DiagnosticCheck("配置校验", validation.valid, Duration.ofNanos(System.nanoTime() - started), validation.issues.joinToString("；") { it.message }.ifBlank { "配置有效" }))
        if (!validation.valid) return@withContext DiagnosticResult(checks)
        val host = (device.connectionProperties["host"] ?: device.connectionProperties["ip_address"]).orEmpty()
        val port = device.connectionProperties["port"]?.toIntOrNull() ?: 102
        val tcpStarted = System.nanoTime(); val outcome = runCatching { Socket().use { it.connect(InetSocketAddress(host, port), device.connectionProperties["timeout"]?.toIntOrNull() ?: 5000) } }
        checks += DiagnosticCheck("S7 TCP 端口连通", outcome.isSuccess, Duration.ofNanos(System.nanoTime() - tcpStarted), outcome.exceptionOrNull()?.message ?: "$host:$port 可以建立连接")
        DiagnosticResult(checks)
    }

    override suspend fun connect(device: DeviceDefinition): ProtocolSession {
        val validation = validate(device)
        require(validation.valid) { validation.issues.joinToString("；") { it.message } }
        return S7Session(device).also { it.open() }
    }
}

private class S7Session(private val device: DeviceDefinition) : ProtocolSession {
    private val lock = Any(); private var connection: PlcConnection? = null
    private val host get() = (device.connectionProperties["host"] ?: device.connectionProperties["ip_address"]).orEmpty()
    private val timeout get() = device.connectionProperties["timeout"]?.toLongOrNull() ?: 5000L
    private fun url() = s7ConnectionUrl(device.connectionProperties)
    fun open() = synchronized(lock) { close(); connection = DefaultPlcDriverManager().getConnection(url()).also { it.connect() } }
    override val connected get() = synchronized(lock) { connection?.isConnected == true }
    override suspend fun read(points: List<PointDefinition>): List<PointValue> = withContext(Dispatchers.IO) {
        points.map { point -> val start = Instant.now(); runCatching { readOne(point) }.fold(
            { PointValue(device.id, point.id, start, Instant.now(), it, ValueQuality.GOOD, "s7comm") },
            { PointValue(device.id, point.id, start, Instant.now(), null, quality(it), "s7comm", it.message) }) }
    }
    private fun readOne(point: PointDefinition): Any? = synchronized(lock) {
        val tag = s7Tag(point)
        retryOnce {
            val response = requireConnection().readRequestBuilder().addTagAddress("v", tag).build().execute().get(timeout, TimeUnit.MILLISECONDS)
            if (response.getResponseCode("v") != PlcResponseCode.OK) throw S7ProtocolException("S7 读取失败: ${response.getResponseCode("v")}")
            response.getObject("v")
        }
    }
    override suspend fun write(writes: List<PointWrite>): List<WriteResult> = withContext(Dispatchers.IO) {
        writes.map { write -> if (write.point.access == ai.moying.iview.core.device.PointAccess.READ_ONLY) WriteResult(false, "点位只读") else runCatching { synchronized(lock) { retryOnce { val response = requireConnection().writeRequestBuilder().addTagAddress("v", s7Tag(write.point), write.value).build().execute().get(timeout, TimeUnit.MILLISECONDS); if (response.getResponseCode("v") != PlcResponseCode.OK) throw S7ProtocolException("S7 写入失败: ${response.getResponseCode("v")}") } } }.fold({ WriteResult(true) }, { WriteResult(false, it.message) }) }
    }
    private fun requireConnection() = connection ?: throw IllegalStateException("S7 连接未建立")
    private fun <T> retryOnce(block: () -> T): T = try { block() } catch (first: S7ProtocolException) { throw first } catch (first: Exception) { close(); open(); block() }
    private fun quality(error: Throwable) = when (error) { is IllegalArgumentException -> ValueQuality.BAD_CONFIGURATION; is TimeoutException, is SocketTimeoutException -> ValueQuality.TIMEOUT; is S7ProtocolException -> ValueQuality.BAD_RESPONSE; else -> ValueQuality.OFFLINE }
    override fun close() = synchronized(lock) { runCatching { connection?.close() }; connection=null }
}

private class S7ProtocolException(message: String) : RuntimeException(message)

internal fun s7ConnectionUrl(values: Map<String, String>): String {
    val host = (values["host"] ?: values["ip_address"]).orEmpty().trim()
    val port = values["port"]?.toIntOrNull() ?: 102
    val rack = values["rack"]?.toIntOrNull() ?: 0
    val slot = values["slot"]?.toIntOrNull() ?: 1
    val timeout = values["timeout"]?.toIntOrNull() ?: 5000
    val connectionType = values["connect_type"]?.toIntOrNull() ?: values["connectType"]?.toIntOrNull() ?: 1
    val group = when (connectionType) { 2 -> "OS"; 3 -> "OTHERS"; else -> "PG_OR_PC" }
    return "s7://$host:$port?remote-rack=$rack&remote-slot=$slot&remote-device-group=$group&read-timeout=$timeout&tcp.default-timeout=$timeout&tcp.keep-alive=true"
}

internal fun s7Tag(point: PointDefinition, defaultArea: String = "DB", defaultDb: Int = 1): String {
    point.properties["plc_tag"]?.takeIf { it.isNotBlank() }?.let { return it }
    if (point.address.trim().startsWith('%')) return point.address.trim()
    val type = when (point.dataType.name) { "BOOLEAN" -> "BOOL"; "INT16" -> "INT"; "UINT16" -> "UINT"; "INT32" -> "DINT"; "UINT32" -> "UDINT"; "FLOAT32" -> "REAL"; "FLOAT64" -> "LREAL"; "INT64" -> "LINT"; "UINT64" -> "ULINT"; else -> throw IllegalArgumentException("S7 不支持该点位类型") }
    val address = point.address.trim().uppercase()
    if (address.startsWith("DB")) {
        val match = Regex("^DB(\\d+)\\.(\\d+)(?:\\.(\\d+))?$").matchEntire(address) ?: throw IllegalArgumentException("无效 S7 DB 地址: $address")
        return "DB${match.groupValues[1]}:${match.groupValues[2]}${match.groupValues[3].takeIf { it.isNotBlank() }?.let { ".$it" } ?: ""}:$type"
    }
    val area = point.properties["area_type"] ?: point.properties["areaType"] ?: defaultArea
    val db = point.properties["db_number"]?.toIntOrNull() ?: point.properties["dbNumber"]?.toIntOrNull() ?: defaultDb
    return if (area.equals("DB", true)) "DB$db:$address:$type" else "%${area.uppercase()}$address:$type"
}

private fun check(values: Map<String, String>, key: String, default: Int, range: IntRange): String? {
    val raw = values[key] ?: if (key == "connect_type") values["connectType"] else null
    val value = raw?.toIntOrNull() ?: if (raw == null) default else return "必须是整数"
    return if (value in range) null else "必须在 ${range.first} 到 ${range.last} 之间"
}

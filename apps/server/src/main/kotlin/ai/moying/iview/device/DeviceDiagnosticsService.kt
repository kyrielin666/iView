package ai.moying.iview.device

import ai.moying.iview.collector.DriverRegistry
import java.time.Instant

data class ConnectionTestView(
    val connected: Boolean,
    val error: String? = null,
)

data class DiagnosticStepView(
    val key: String,
    val title: String,
    val status: String,
    val message: String,
    val durationMs: Long,
)

data class DeviceDiagnosticView(
    val deviceId: Long,
    val deviceSn: String,
    val deviceName: String,
    val protocolType: String,
    val online: Boolean,
    val overallStatus: String,
    val summary: String,
    val suggestions: List<String>,
    val diagnosedAt: Instant,
    val steps: List<DiagnosticStepView>,
)

class DeviceDiagnosticsService(
    private val catalog: DeviceCatalogService,
    private val drivers: DriverRegistry,
    private val runtimeMapper: DeviceRuntimeMapper,
) {
    suspend fun test(deviceId: Long): ConnectionTestView {
        val definition = runtimeMapper.map(deviceId).definition
        return runCatching {
            drivers.require(definition.protocolType).connect(definition).use { ConnectionTestView(it.connected) }
        }.getOrElse { ConnectionTestView(false, it.message ?: it::class.simpleName) }
    }

    suspend fun diagnose(deviceId: Long): DeviceDiagnosticView {
        val device = catalog.getDevice(deviceId)
        val definition = runtimeMapper.map(deviceId).definition
        val result = drivers.require(definition.protocolType).diagnose(definition)
        val steps = result.checks.mapIndexed { index, check ->
            DiagnosticStepView(
                key = "step_${index + 1}",
                title = check.name,
                status = if (check.successful) "success" else "error",
                message = check.detail.orEmpty(),
                durationMs = check.elapsed.toMillis(),
            )
        }
        return DeviceDiagnosticView(
            device.id,
            device.deviceSn,
            device.deviceName,
            definition.protocolType,
            online = false,
            overallStatus = if (result.successful) "success" else "error",
            summary = if (result.successful) "设备连接诊断通过" else "设备连接诊断未通过",
            suggestions = if (result.successful) emptyList() else listOf("检查设备地址、端口、从站号以及现场网络连通性"),
            diagnosedAt = Instant.now(),
            steps = steps,
        )
    }

}

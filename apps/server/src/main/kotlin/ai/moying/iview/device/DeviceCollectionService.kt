package ai.moying.iview.device

import ai.moying.iview.collector.CollectionEngine
import ai.moying.iview.collector.CollectionResult
import ai.moying.iview.core.device.DeviceId
import ai.moying.iview.core.device.PointValue
import ai.moying.iview.core.device.ValueQuality
import ai.moying.iview.telemetry.TelemetryRepository
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

data class CollectedPointView(
    val pointId: Long,
    val pointName: String,
    val pointCode: String,
    val value: Any?,
    val rawValue: Any?,
    val timestamp: Instant,
    val quality: Int,
    val error: String?,
)

data class CollectResultView(
    val deviceSn: String,
    val success: Boolean,
    val values: List<CollectedPointView>,
    val error: String?,
    val duration: Long,
    val attempts: Int,
    val recovered: Boolean,
)

data class RealtimePointView(
    val pointId: Long,
    val pointName: String,
    val pointCode: String,
    val value: Any?,
    val quality: String,
    val timestamp: Instant?,
)

data class DeviceRealtimeView(
    val id: Long,
    val deviceSn: String,
    val deviceName: String,
    val templateId: Long,
    val isOnline: Boolean,
    val points: List<RealtimePointView>,
)

data class DeviceStatisticsView(
    val total: Long,
    val enabled: Long,
    val online: Long,
    val offline: Long,
    val sessionCount: Int,
)

class DeviceCollectionService(
    private val catalog: DeviceCatalogService,
    private val runtimeMapper: DeviceRuntimeMapper,
    private val engine: CollectionEngine,
    private val telemetry: TelemetryRepository,
) {
    private val lastScheduledAt = ConcurrentHashMap<Long, Instant>()

    suspend fun collect(deviceId: Long, pointId: Long? = null): CollectResultView {
        val runtime = runtimeMapper.map(deviceId)
        if (!runtime.device.enabled) throw CatalogValidationException("设备已停用，不能采集")
        val points = pointId?.let { id ->
            runtime.points.filter { it.id.value == id }.ifEmpty { throw CatalogNotFoundException("采集点不存在: $id") }
        } ?: runtime.points
        return view(engine.collect(runtime.definition, points), runtime)
    }

    fun realtime(deviceId: Long): DeviceRealtimeView {
        val runtime = runtimeMapper.map(deviceId)
        val latest = telemetry.latest(DeviceId(deviceId)).associateBy { it.pointId.value }
        val onlineThreshold = Instant.now().minusMillis(maxOf(runtime.template.collectIntervalMs.toLong() * 3, 5_000))
        val online = latest.values.any { it.quality == ValueQuality.GOOD && it.receivedAt >= onlineThreshold }
        return DeviceRealtimeView(
            runtime.device.id, runtime.device.deviceSn, runtime.device.deviceName, runtime.template.id, online,
            runtime.catalogPoints.map { point ->
                val value = latest[point.id]
                RealtimePointView(
                    point.id, point.pointName, point.pointCode, value?.value,
                    value?.quality?.name?.lowercase() ?: "offline", value?.observedAt,
                )
            },
        )
    }

    fun history(deviceId: Long, from: Instant, to: Instant, limit: Int): List<CollectedPointView> {
        require(from < to) { "from 必须早于 to" }
        val runtime = runtimeMapper.map(deviceId)
        val pointById = runtime.catalogPoints.associateBy(TemplatePoint::id)
        return telemetry.history(DeviceId(deviceId), from, to, limit).map { value ->
            val point = pointById[value.pointId.value]
            pointView(value, point?.pointName ?: value.pointId.value.toString(), point?.pointCode ?: "")
        }
    }

    fun statistics(sessionCount: Int): DeviceStatisticsView {
        var page = 1
        var total = 0L
        var enabled = 0L
        var online = 0L
        do {
            val result = catalog.listDevices(DeviceFilter(page, 100))
            total = result.total
            result.list.forEach { device ->
                if (device.enabled) enabled++
                if (realtime(device.id).isOnline) online++
            }
            page++
        } while ((page - 1) * 100 < result.total)
        return DeviceStatisticsView(total, enabled, online, total - online, sessionCount)
    }

    suspend fun collectDueDevices(now: Instant = Instant.now()): Int {
        var page = 1
        var collected = 0
        do {
            val devices = catalog.listDevices(DeviceFilter(page, 100, enabled = true))
            devices.list.forEach { device ->
                val template = catalog.getTemplate(device.templateId)
                val previous = lastScheduledAt[device.id]
                if (previous == null || previous.plusMillis(template.collectIntervalMs.toLong()) <= now) {
                    runCatching { collect(device.id) }
                    lastScheduledAt[device.id] = now
                    collected++
                }
            }
            page++
        } while ((page - 1) * 100 < devices.total)
        return collected
    }

    private fun view(result: CollectionResult, runtime: DeviceRuntimeDefinition) = CollectResultView(
        result.deviceSerialNumber,
        result.successful,
        result.values.map { value ->
            val point = runtime.catalogPoints.firstOrNull { it.id == value.pointId.value }
            pointView(value, point?.pointName ?: value.pointId.value.toString(), point?.pointCode ?: "")
        },
        result.error,
        result.duration.toMillis(),
        result.attempts,
        result.recovered,
    )

    private fun pointView(value: PointValue, name: String, code: String) = CollectedPointView(
        value.pointId.value, name, code, value.value, value.value, value.observedAt,
        when (value.quality) { ValueQuality.GOOD -> 1; ValueQuality.UNCERTAIN -> 2; else -> 0 },
        value.diagnostic,
    )
}

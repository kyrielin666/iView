package ai.moying.iview.telemetry

import ai.moying.iview.core.device.DeviceId
import ai.moying.iview.core.device.PointValue
import java.time.Instant

interface TelemetryRepository {
    fun append(values: List<PointValue>)

    fun latest(deviceId: DeviceId): List<PointValue>

    fun history(deviceId: DeviceId, from: Instant, to: Instant, limit: Int = 1_000): List<PointValue>
}

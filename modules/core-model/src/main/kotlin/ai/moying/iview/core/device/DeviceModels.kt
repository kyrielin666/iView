package ai.moying.iview.core.device

import java.time.Duration
import java.time.Instant
@JvmInline
value class DeviceId(val value: Long)

@JvmInline
value class PointId(val value: Long)

enum class DeviceLifecycle {
    DISABLED,
    OFFLINE,
    CONNECTING,
    ONLINE,
    DEGRADED,
}

enum class PointAccess {
    READ_ONLY,
    WRITE_ONLY,
    READ_WRITE,
}

enum class PointDataType {
    BOOLEAN,
    INT16,
    UINT16,
    INT32,
    UINT32,
    INT64,
    UINT64,
    FLOAT32,
    FLOAT64,
    STRING,
    BYTES,
}

enum class ValueQuality {
    GOOD,
    UNCERTAIN,
    STALE,
    TIMEOUT,
    OFFLINE,
    BAD_CONFIGURATION,
    BAD_RESPONSE,
    OUT_OF_RANGE,
}

data class DeviceDefinition(
    val id: DeviceId,
    val serialNumber: String,
    val name: String,
    val protocolType: String,
    val enabled: Boolean,
    val connectionProperties: Map<String, String>,
)

data class PointDefinition(
    val id: PointId,
    val deviceId: DeviceId,
    val code: String,
    val name: String,
    val address: String,
    val dataType: PointDataType,
    val access: PointAccess,
    val collectionInterval: Duration,
    val properties: Map<String, String> = emptyMap(),
)

data class PointValue(
    val deviceId: DeviceId,
    val pointId: PointId,
    val observedAt: Instant,
    val receivedAt: Instant,
    val value: Any?,
    val quality: ValueQuality,
    val source: String,
    val diagnostic: String? = null,
)

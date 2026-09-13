package ai.moying.iview.collector

import ai.moying.iview.core.device.DeviceDefinition
import ai.moying.iview.core.device.PointDefinition
import ai.moying.iview.core.device.PointValue
import ai.moying.iview.core.device.ValueQuality
import ai.moying.iview.protocol.ProtocolSession
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.delay
import java.io.Closeable
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

fun interface PointValueSink {
    fun append(values: List<PointValue>)
}

data class CollectionResult(
    val deviceSerialNumber: String,
    val successful: Boolean,
    val values: List<PointValue>,
    val error: String?,
    val duration: Duration,
    val attempts: Int = 1,
    val recovered: Boolean = false,
)

data class CollectionRetryPolicy(
    val maxReconnectAttempts: Int = 1,
    val baseDelayMs: Long = 100,
    val maxDelayMs: Long = 2_000,
) {
    init {
        require(maxReconnectAttempts in 0..10) { "重连次数必须在 0 到 10 之间" }
        require(baseDelayMs in 0..60_000 && maxDelayMs in baseDelayMs..300_000) { "重连退避时间不合法" }
    }
    fun delayMs(attempt: Int): Long = (baseDelayMs * (1L shl (attempt - 1).coerceIn(0, 20))).coerceAtMost(maxDelayMs)
}

data class SessionStatus(
    val deviceId: Long,
    val protocolType: String,
    val connected: Boolean,
)

class DeviceSessionPool(private val drivers: DriverRegistry) : Closeable {
    private data class Entry(val definition: DeviceDefinition, val session: ProtocolSession)

    private val mutex = Mutex()
    private val entries = ConcurrentHashMap<Long, Entry>()

    suspend fun session(device: DeviceDefinition): ProtocolSession = mutex.withLock {
        val current = entries[device.id.value]
        if (current != null && current.definition == device && current.session.connected) return@withLock current.session
        current?.session?.close()
        drivers.require(device.protocolType).connect(device).also { entries[device.id.value] = Entry(device, it) }
    }

    suspend fun invalidate(deviceId: Long) = mutex.withLock {
        entries.remove(deviceId)?.session?.close()
    }

    fun statuses(): List<SessionStatus> = entries.values.map {
        SessionStatus(it.definition.id.value, it.definition.protocolType, it.session.connected)
    }.sortedBy(SessionStatus::deviceId)

    override fun close() {
        entries.values.forEach { runCatching { it.session.close() } }
        entries.clear()
    }
}

class CollectionEngine(
    private val sessions: DeviceSessionPool,
    private val sink: PointValueSink,
    private val retryPolicy: CollectionRetryPolicy,
) {
    constructor(sessions: DeviceSessionPool, sink: PointValueSink) : this(sessions, sink, CollectionRetryPolicy())

    suspend fun collect(device: DeviceDefinition, points: List<PointDefinition>): CollectionResult {
        val started = System.nanoTime()
        if (points.isEmpty()) return CollectionResult(
            device.serialNumber, true, emptyList(), null, started.elapsed(),
        )
        var attempts = 0
        var outcome: Result<List<PointValue>>
        do {
            attempts++
            outcome = runCatching { sessions.session(device).read(points) }
            val transient = outcome.isFailure || outcome.getOrNull().orEmpty().any { it.quality == ValueQuality.OFFLINE || it.quality == ValueQuality.TIMEOUT }
            if (!transient || attempts > retryPolicy.maxReconnectAttempts) break
            sessions.invalidate(device.id.value)
            delay(retryPolicy.delayMs(attempts))
        } while (true)
        val values = outcome.getOrElse { error ->
            val now = Instant.now()
            points.map { point ->
                PointValue(device.id, point.id, now, now, null, ValueQuality.OFFLINE, device.protocolType, error.message)
            }
        }
        sink.append(values)
        if (outcome.isFailure || values.any { it.quality == ValueQuality.OFFLINE || it.quality == ValueQuality.TIMEOUT }) {
            sessions.invalidate(device.id.value)
        }
        val successful = outcome.isSuccess && values.all { it.quality == ValueQuality.GOOD }
        val error = outcome.exceptionOrNull()?.message
            ?: values.firstOrNull { it.quality != ValueQuality.GOOD }?.diagnostic
        return CollectionResult(device.serialNumber, successful, values, error, started.elapsed(), attempts, attempts > 1 && successful)
    }
}

private fun Long.elapsed() = Duration.ofNanos(System.nanoTime() - this)

package ai.moying.iview.outbound

import ai.moying.iview.core.device.PointValue
import ai.moying.iview.core.device.ValueQuality
import ai.moying.iview.device.DeviceCatalogService
import java.io.Closeable
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

data class OutboundStatus(val queued: Int, val active: Int, val dropped: Long, val lastError: String?)

class OutboundPublisher(
    private val catalog: DeviceCatalogService,
    private val dispatcher: PushDispatcher,
) : Closeable {
    private val dropped = AtomicLong()
    private val lastError = AtomicReference<String?>()
    private val executor = ThreadPoolExecutor(
        2, 2, 0, TimeUnit.MILLISECONDS, ArrayBlockingQueue(1_000),
        { task -> Thread(task, "iview-outbound").apply { isDaemon = true } },
        { _, _ -> dropped.incrementAndGet() },
    )

    fun publish(values: List<PointValue>) {
        values.groupBy { it.deviceId.value }.forEach { (deviceId, group) ->
            executor.execute {
                runCatching { dispatcher.dispatch(payload(deviceId, group)) }
                    .onFailure { lastError.set(it.message ?: it.javaClass.simpleName) }
            }
        }
    }

    fun dispatchNow(payload: PushPayload, configId: Long? = null) = dispatcher.dispatch(payload, configId)
    fun status() = OutboundStatus(executor.queue.size, executor.activeCount, dropped.get(), lastError.get())

    private fun payload(deviceId: Long, values: List<PointValue>): PushPayload {
        val device = catalog.getDevice(deviceId)
        val template = catalog.getTemplate(device.templateId)
        val points = catalog.listPoints(template.id).associateBy { it.id }
        val collectTime = values.maxOf(PointValue::receivedAt)
        val mapped = values.map { value ->
            val point = points[value.pointId.value]
            PushPoint(
                value.pointId.value, point?.pointName ?: value.pointId.value.toString(), point?.pointCode ?: "",
                value.value, value.value, if (value.quality == ValueQuality.GOOD) 1 else 0, value.observedAt,
            )
        }
        return PushPayload(
            data = mapped.associate { it.pointCode to it.value }, timestamp = collectTime.toEpochMilli(),
            deviceSn = device.deviceSn, deviceName = device.deviceName, templateId = template.id,
            templateCode = template.templateCode, collectTime = collectTime, points = mapped,
        )
    }

    override fun close() { executor.shutdownNow() }
}

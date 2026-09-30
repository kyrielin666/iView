package ai.moying.iview.device

import ai.moying.iview.identity.AuthorizationException
import org.springframework.http.MediaType
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArraySet

/** Per-device telemetry stream. Grants are checked at connect, on publish, and during idle periods. */
@Component
class DeviceRealtimeHub {
    private data class Subscriber(val emitter: SseEmitter, val authorized: () -> Boolean)
    private val subscribers = ConcurrentHashMap<Long, CopyOnWriteArraySet<Subscriber>>()

    fun subscribe(deviceId: Long, authorized: () -> Boolean): SseEmitter {
        if (!runCatching(authorized).getOrDefault(false)) throw AuthorizationException("当前用户无权订阅该设备遥测")
        val emitter = SseEmitter(0L)
        val subscriber = Subscriber(emitter, authorized)
        subscribers.compute(deviceId) { _, current -> (current ?: CopyOnWriteArraySet()).also { it += subscriber } }
        val remove = { removeSubscriber(deviceId, subscriber) }
        emitter.onCompletion(remove)
        emitter.onTimeout { remove(); emitter.complete() }
        runCatching { emitter.send(SseEmitter.event().name("ready").data(mapOf("device_id" to deviceId), MediaType.APPLICATION_JSON)) }
            .onFailure { remove(); emitter.complete() }
        return emitter
    }

    fun publish(view: DeviceRealtimeView) {
        subscribers[view.id]?.forEach { subscriber ->
            if (!runCatching(subscriber.authorized).getOrDefault(false)) { revoke(view.id, subscriber); return@forEach }
            runCatching { subscriber.emitter.send(SseEmitter.event().name("telemetry").data(view, MediaType.APPLICATION_JSON)) }
                .onFailure { removeSubscriber(view.id, subscriber); subscriber.emitter.complete() }
        }
    }

    @Scheduled(fixedDelayString = "\${iview.device.sse.authorization-check-ms:10000}")
    fun revokeUnauthorized() {
        subscribers.forEach { (deviceId, current) ->
            current.forEach { subscriber -> if (!runCatching(subscriber.authorized).getOrDefault(false)) revoke(deviceId, subscriber) }
        }
    }

    internal fun subscriberCount(deviceId: Long): Int = subscribers[deviceId]?.size ?: 0

    private fun revoke(deviceId: Long, subscriber: Subscriber) {
        removeSubscriber(deviceId, subscriber)
        subscriber.emitter.complete()
    }

    private fun removeSubscriber(deviceId: Long, subscriber: Subscriber) {
        subscribers.computeIfPresent(deviceId) { _, current -> current.remove(subscriber); current.takeUnless { it.isEmpty() } }
    }
}

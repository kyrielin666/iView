package ai.moying.iview.device

import org.springframework.http.MediaType
import org.springframework.stereotype.Component
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArraySet

/** Authenticated, per-device telemetry stream used by the device detail and dashboard bindings. */
@Component
class DeviceRealtimeHub {
    private val subscribers = ConcurrentHashMap<Long, CopyOnWriteArraySet<SseEmitter>>()

    fun subscribe(deviceId: Long): SseEmitter {
        val emitter = SseEmitter(0L)
        val emitters = subscribers.computeIfAbsent(deviceId) { CopyOnWriteArraySet() }
        emitters += emitter
        val remove = { emitters.remove(emitter); if (emitters.isEmpty()) subscribers.remove(deviceId, emitters) }
        emitter.onCompletion(remove)
        emitter.onTimeout { remove(); emitter.complete() }
        runCatching { emitter.send(SseEmitter.event().name("ready").data(mapOf("device_id" to deviceId), MediaType.APPLICATION_JSON)) }
            .onFailure { remove() }
        return emitter
    }

    fun publish(view: DeviceRealtimeView) {
        val emitters = subscribers[view.id] ?: return
        emitters.forEach { emitter -> runCatching {
            emitter.send(SseEmitter.event().name("telemetry").data(view, MediaType.APPLICATION_JSON))
        }.onFailure { emitters.remove(emitter) } }
        if (emitters.isEmpty()) subscribers.remove(view.id, emitters)
    }
}

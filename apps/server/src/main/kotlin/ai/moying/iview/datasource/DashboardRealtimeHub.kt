package ai.moying.iview.datasource

import org.springframework.http.MediaType
import org.springframework.stereotype.Component
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArraySet

/**
 * Small, authenticated dashboard-control subscription channel. It broadcasts document and
 * publication changes only; business values remain bound through published data models.
 */
@Component
class DashboardRealtimeHub {
    private val subscribers = ConcurrentHashMap<Long, CopyOnWriteArraySet<SseEmitter>>()

    fun subscribe(dashboardId: Long): SseEmitter {
        val emitter = SseEmitter(0L)
        val set = subscribers.computeIfAbsent(dashboardId) { CopyOnWriteArraySet() }
        set += emitter
        emitter.onCompletion { remove(dashboardId, emitter) }
        emitter.onTimeout { remove(dashboardId, emitter) }
        emitter.onError { remove(dashboardId, emitter) }
        send(emitter, "ready", dashboardId)
        return emitter
    }

    fun notifyChanged(dashboardId: Long, change: String) {
        subscribers[dashboardId]?.forEach { emitter -> if (!send(emitter, change, dashboardId)) remove(dashboardId, emitter) }
    }

    private fun send(emitter: SseEmitter, event: String, dashboardId: Long): Boolean = runCatching {
        emitter.send(SseEmitter.event().name(event).data(mapOf("dashboard_id" to dashboardId, "at" to Instant.now().toString()), MediaType.APPLICATION_JSON))
    }.isSuccess

    private fun remove(dashboardId: Long, emitter: SseEmitter) {
        subscribers[dashboardId]?.let { set ->
            set -= emitter
            if (set.isEmpty()) subscribers.remove(dashboardId, set)
        }
    }
}

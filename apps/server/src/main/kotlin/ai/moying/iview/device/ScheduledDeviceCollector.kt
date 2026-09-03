package ai.moying.iview.device

import kotlinx.coroutines.runBlocking
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.util.concurrent.atomic.AtomicBoolean

@Component
@ConditionalOnProperty(prefix = "iview.collector", name = ["scheduling-enabled"], havingValue = "true")
class ScheduledDeviceCollector(private val collection: DeviceCollectionService) {
    private val running = AtomicBoolean(false)

    @Scheduled(fixedDelayString = "\${iview.collector.scan-delay-ms:1000}")
    fun scan() {
        if (!running.compareAndSet(false, true)) return
        try {
            runBlocking { collection.collectDueDevices() }
        } finally {
            running.set(false)
        }
    }
}

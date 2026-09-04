package ai.moying.iview.oee

import ai.moying.iview.common.ApiResponse
import org.springframework.web.bind.annotation.*
import java.time.Duration
import java.time.Instant

@RestController
@RequestMapping("/api/v1/devices/{deviceId}/oee")
class OeeController(private val service: DeviceOeeService) {
    @GetMapping fun calculate(@PathVariable deviceId: Long, @RequestParam start: Instant, @RequestParam end: Instant,
        @RequestParam(name = "planned_production_ms", required = false) plannedMs: Long?, @RequestParam(name = "ideal_cycle_ms", required = false) idealMs: Long?) =
        ApiResponse.success(service.calculate(deviceId, start, end, plannedMs?.let(Duration::ofMillis), idealMs?.let(Duration::ofMillis)))
}

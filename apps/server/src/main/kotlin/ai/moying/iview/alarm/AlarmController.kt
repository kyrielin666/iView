package ai.moying.iview.alarm

import ai.moying.iview.common.ApiResponse
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.time.Instant

class AlarmRuleRequest {
    var pattern: String = ""
    var category: Int? = null
    var priority: Int? = null
    var enabled: Int? = null
}

@RestController
@RequestMapping("/api/v1/device-alarm-classification-rules")
class AlarmRuleController(private val service: AlarmService) {
    @GetMapping fun list() = ApiResponse.success(service.listRules())
    @GetMapping("/{id}") fun get(@PathVariable id: Long) = ApiResponse.success(service.getRule(id))
    @PostMapping @ResponseStatus(HttpStatus.CREATED)
    fun create(@RequestBody request: AlarmRuleRequest) = ApiResponse.success(service.createRule(request.command()))
    @PutMapping("/{id}")
    fun update(@PathVariable id: Long, @RequestBody request: AlarmRuleRequest): ApiResponse<AlarmClassificationRule> {
        val previous = service.getRule(id)
        return ApiResponse.success(service.updateRule(id, request.command(previous)))
    }
    @DeleteMapping("/{id}")
    fun delete(@PathVariable id: Long): ApiResponse<Nothing> { service.deleteRule(id); return ApiResponse.success() }
    @PostMapping("/{id}/enable") fun enable(@PathVariable id: Long) = ApiResponse.success(service.setRuleEnabled(id, true))
    @PostMapping("/{id}/disable") fun disable(@PathVariable id: Long) = ApiResponse.success(service.setRuleEnabled(id, false))

    private fun AlarmRuleRequest.command(previous: AlarmClassificationRule? = null) = AlarmClassificationRuleCommand(
        pattern.takeIf(String::isNotBlank) ?: previous?.pattern ?: "",
        category ?: previous?.category ?: -1,
        priority ?: previous?.priority ?: 0,
        enabled?.let { it == 1 } ?: previous?.enabled ?: true,
    )
}

@RestController
@RequestMapping("/api/v1/device-alarms")
class DeviceAlarmController(private val service: AlarmService) {
    @GetMapping
    fun list(
        @RequestParam(defaultValue = "1") page: Int,
        @RequestParam(name = "page_size", defaultValue = "20") pageSize: Int,
        @RequestParam(name = "device_id", required = false) deviceId: Long?,
        @RequestParam(required = false) status: AlarmStatus?,
    ) = ApiResponse.success(service.listAlarms(DeviceAlarmFilter(page, pageSize, deviceId, status)))

    /** Removes historical alarm rows before the supplied UTC instant. The cutoff is mandatory. */
    @DeleteMapping("/cleanup")
    fun cleanup(@RequestParam before: Instant): ApiResponse<Map<String, Int>> =
        ApiResponse.success(mapOf("deleted" to service.cleanupBefore(before)))
}

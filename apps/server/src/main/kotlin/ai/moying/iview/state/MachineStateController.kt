package ai.moying.iview.state

import ai.moying.iview.common.ApiResponse
import ai.moying.iview.oee.MachineState
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.*
import java.time.Instant

class MachineStateRuleRequest {
    var templateId: Long? = null
    var pointId: Long? = null
    var standardMapping: Map<String, String>? = null
    var runtimeMapping: Map<String, String>? = null
    var defaultStatus: String? = null
    var defaultRuntimeState: String? = null
    var enabled: Int? = null
}

@RestController
@RequestMapping("/api/v1/machine-state-rules")
class MachineStateRuleController(private val service: MachineStateService) {
    @GetMapping fun list() = ApiResponse.success(service.listRules())
    @GetMapping("/{id}") fun get(@PathVariable id: Long) = ApiResponse.success(service.getRule(id))
    @PostMapping @ResponseStatus(HttpStatus.CREATED) fun create(@RequestBody body: MachineStateRuleRequest) = ApiResponse.success(service.createRule(body.command()))
    @PutMapping("/{id}") fun update(@PathVariable id: Long, @RequestBody body: MachineStateRuleRequest): ApiResponse<MachineStateRule> {
        val old = service.getRule(id); return ApiResponse.success(service.updateRule(id, body.command(old)))
    }
    @DeleteMapping("/{id}") fun delete(@PathVariable id: Long): ApiResponse<Nothing> { service.deleteRule(id); return ApiResponse.success() }
    @PostMapping("/{id}/enable") fun enable(@PathVariable id: Long) = ApiResponse.success(service.setEnabled(id, true))
    @PostMapping("/{id}/disable") fun disable(@PathVariable id: Long) = ApiResponse.success(service.setEnabled(id, false))
    private fun MachineStateRuleRequest.command(old: MachineStateRule? = null) = MachineStateRuleCommand(
        templateId ?: old?.templateId ?: 0, pointId ?: old?.pointId ?: 0,
        standardMapping ?: old?.standardMapping ?: emptyMap(),
        (runtimeMapping ?: old?.runtimeMapping?.mapValues { it.value.name } ?: emptyMap()).mapValues { MachineState.valueOf(it.value.trim().uppercase()) },
        defaultStatus ?: old?.defaultStatus ?: "", MachineState.valueOf((defaultRuntimeState ?: old?.defaultRuntimeState?.name ?: "UNKNOWN").trim().uppercase()),
        enabled?.let { it == 1 } ?: old?.enabled ?: true,
    )
}

@RestController
@RequestMapping("/api/v1/devices/{deviceId}/machine-state")
class DeviceMachineStateController(private val service: MachineStateService) {
    @GetMapping("/current") fun current(@PathVariable deviceId: Long) = ApiResponse.success(service.current(deviceId))
    @GetMapping("/intervals") fun intervals(@PathVariable deviceId: Long, @RequestParam start: Instant, @RequestParam end: Instant) = ApiResponse.success(service.intervals(deviceId, start, end))
}

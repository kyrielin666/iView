package ai.moying.iview.outbound

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

class PushConfigRequest {
    var name: String = ""
    var pushType: String = ""
    var enabled: Int? = null
    var priority: Int? = null
    var timeout: Int? = null
    var retryCount: Int? = null
    var retryDelay: Int? = null
    var config: Map<String, Any?>? = null
    var description: String? = null
}

data class PushTestView(val connected: Boolean, val error: String? = null)
data class PushConfigView(
    val id: Long,
    val name: String,
    val pushType: String,
    val enabled: Int,
    val priority: Int,
    val timeout: Int,
    val retryCount: Int,
    val retryDelay: Int,
    val config: Map<String, Any?>,
    val description: String,
    val createdAt: java.time.Instant,
    val updatedAt: java.time.Instant,
)

@RestController
@RequestMapping("/api/v1/push-configs")
class PushConfigController(
    private val service: PushConfigService,
    private val dispatcher: PushDispatcher,
    private val adapters: OutboundAdapterRegistry,
) {
    @GetMapping
    fun list(
        @RequestParam(defaultValue = "1") page: Int,
        @RequestParam(name = "page_size", defaultValue = "20") pageSize: Int,
        @RequestParam(required = false) keyword: String?,
        @RequestParam(name = "push_type", required = false) pushType: String?,
    ): ApiResponse<PushPage<PushConfigView>> {
        val result = service.list(PushConfigFilter(page, pageSize, keyword, pushType))
        return ApiResponse.success(PushPage(result.list.map(::view), result.total, result.page, result.pageSize))
    }

    @GetMapping("/types") fun types() = ApiResponse.success(listOf(
        mapOf("value" to "mqtt", "label" to "MQTT"), mapOf("value" to "http", "label" to "HTTP"),
    ))
    @GetMapping("/{id}") fun get(@PathVariable id: Long) = ApiResponse.success(view(service.get(id)))

    @PostMapping @ResponseStatus(HttpStatus.CREATED)
    fun create(@RequestBody request: PushConfigRequest) = ApiResponse.success(view(service.create(request.createCommand())))

    @PutMapping("/{id}")
    fun update(@PathVariable id: Long, @RequestBody request: PushConfigRequest): ApiResponse<PushConfigView> {
        val current = service.get(id)
        val updated = service.update(id, request.updateCommand(current))
        adapters.invalidate(id)
        return ApiResponse.success(view(updated))
    }

    @DeleteMapping("/{id}")
    fun delete(@PathVariable id: Long): ApiResponse<Nothing> { service.delete(id); adapters.invalidate(id); return ApiResponse.success() }

    @DeleteMapping("/batch/{ids}")
    fun batchDelete(@PathVariable ids: String): ApiResponse<Nothing> {
        ids.split(',').map(String::trim).filter(String::isNotEmpty).map(String::toLong).forEach { id -> service.delete(id); adapters.invalidate(id) }
        return ApiResponse.success()
    }

    @PostMapping("/{id}/enable") fun enable(@PathVariable id: Long) = ApiResponse.success(view(service.setEnabled(id, true)))
    @PostMapping("/{id}/disable") fun disable(@PathVariable id: Long): ApiResponse<PushConfigView> {
        val config = service.setEnabled(id, false)
        adapters.invalidate(id)
        return ApiResponse.success(view(config))
    }

    @PostMapping("/{id}/test")
    fun test(@PathVariable id: Long): ApiResponse<PushTestView> {
        val result = runCatching { dispatcher.test(service.get(id)) }
        return ApiResponse.success(PushTestView(result.isSuccess, result.exceptionOrNull()?.message))
    }

    private fun PushConfigRequest.createCommand() = PushConfigCommand(
        name, pushType, enabled == 1, priority ?: 0, timeout ?: 5_000,
        retryCount ?: 3, retryDelay ?: 1_000, config ?: emptyMap(), description ?: "",
    )
    private fun PushConfigRequest.updateCommand(current: PushConfig) = PushConfigCommand(
        name.takeIf(String::isNotBlank) ?: current.name,
        pushType.takeIf(String::isNotBlank) ?: current.pushType,
        enabled?.let { it == 1 } ?: current.enabled,
        priority ?: current.priority, timeout ?: current.timeoutMs, retryCount ?: current.retryCount,
        retryDelay ?: current.retryDelayMs, config ?: current.config, description ?: current.description,
    )
    private fun view(config: PushConfig) = PushConfigView(
        config.id, config.name, config.pushType, if (config.enabled) 1 else 0, config.priority,
        config.timeoutMs, config.retryCount, config.retryDelayMs, config.config, config.description,
        config.createdAt, config.updatedAt,
    )
}

@RestController
@RequestMapping("/api/v1/push-logs")
class PushLogController(private val service: PushConfigService) {
    @GetMapping
    fun list(
        @RequestParam(defaultValue = "1") page: Int,
        @RequestParam(name = "page_size", defaultValue = "20") pageSize: Int,
        @RequestParam(name = "config_id", required = false) configId: Long?,
        @RequestParam(name = "device_sn", required = false) deviceSn: String?,
        @RequestParam(required = false) status: Int?,
    ) = ApiResponse.success(service.logs(PushLogFilter(page, pageSize, configId, deviceSn, status)))
}

@RestController
@RequestMapping("/api/v1/push")
class PushMonitorController(private val publisher: OutboundPublisher) {
    @GetMapping("/status") fun status() = ApiResponse.success(publisher.status())
}

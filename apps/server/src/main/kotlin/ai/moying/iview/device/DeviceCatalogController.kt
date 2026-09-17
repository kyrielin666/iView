package ai.moying.iview.device

import ai.moying.iview.common.ApiResponse
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
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
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter
import java.time.Instant
import jakarta.servlet.http.HttpServletRequest
import kotlinx.coroutines.runBlocking
import ai.moying.iview.collector.DeviceSessionPool

class GroupRequest {
    var groupCode: String = ""
    var groupName: String = ""
    var description: String = ""
}

class TemplateRequest {
    var templateCode: String = ""
    var templateName: String = ""
    var protocolType: String = ""
    var protocolCfg: Map<String, Any?> = emptyMap()
    var collectInterval: Int = 1_000
    var timeout: Int = 5_000
    var description: String = ""
}

class PointRequest {
    var pointName: String = ""
    var pointCode: String = ""
    var dataType: String = "int16"
    var address: String = ""
    var unit: String = ""
    var enabled: Int = 1
    var valueEnum: List<Map<String, Any?>> = emptyList()
    var pointConfig: Map<String, Any?> = emptyMap()
    var description: String = ""
}

class ControlPointRequest {
    var templateId: Long = 0
    var pointName: String = ""
    var pointCode: String = ""
    var dataType: String = "int16"
    var address: String = ""
    var defaultValue: String = ""
    var valueRange: String = ""
    var enabled: Int = 1
    var valueEnum: List<Map<String, Any?>> = emptyList()
    var pointConfig: Map<String, Any?> = emptyMap()
    var description: String = ""
}

class ExecuteControlRequest {
    var deviceId: Long = 0
    var value: Any? = null
}

class DeviceRequest {
    var deviceSn: String? = null
    var deviceName: String = ""
    var templateId: Long = 0
    var groupId: Long? = null
    var lineId: Any? = null
    var vendorId: Any? = null
    var deviceTypeId: Any? = null
    var configJson: Map<String, Any?> = emptyMap()
    var ipAddress: String? = null
    var port: Int? = null
    var slaveId: Int? = null
    var endpointUrl: String? = null
    var username: String? = null
    var password: String? = null
    var enabled: Int = 1
    var description: String = ""

    fun runtimeConfig(): Map<String, Any?> = buildMap {
        putAll(configJson.filterValues { it != null })
        ipAddress?.takeIf(String::isNotBlank)?.let { put("host", it) }
        port?.let { put("port", it) }
        slaveId?.let { put("slave_id", it) }
        endpointUrl?.takeIf(String::isNotBlank)?.let { put("endpoint_url", it) }
        username?.takeIf(String::isNotBlank)?.let { put("username", it) }
        password?.takeIf(String::isNotBlank)?.let { put("password", it) }
    }
}

data class TemplateView(
    val id: Long,
    val templateCode: String,
    val templateName: String,
    val protocolType: String,
    val protocolCfg: Map<String, Any?>,
    val collectInterval: Int,
    val timeout: Int,
    val description: String,
    val points: List<PointView>,
    val controlPoints: List<ControlPointView>,
    val createdAt: Instant,
    val updatedAt: Instant,
)

data class ControlPointView(
    val id: Long,
    val templateId: Long,
    val pointName: String,
    val pointCode: String,
    val dataType: String,
    val address: String,
    val defaultValue: String,
    val valueRange: String,
    val enabled: Int,
    val valueEnum: List<Map<String, Any?>>,
    val pointConfig: Map<String, Any?>,
    val description: String,
    val createdAt: Instant,
    val updatedAt: Instant,
)

data class PointView(
    val id: Long,
    val templateId: Long,
    val pointName: String,
    val pointCode: String,
    val dataType: String,
    val address: String,
    val unit: String,
    val enabled: Int,
    val valueEnum: List<Map<String, Any?>>,
    val pointConfig: Map<String, Any?>,
    val description: String,
    val createdAt: Instant,
    val updatedAt: Instant,
)

data class DeviceView(
    val id: Long,
    val deviceSn: String,
    val deviceName: String,
    val templateId: Long,
    val template: TemplateView,
    val groupId: Long?,
    val lineId: String?,
    val deviceVendor: String?,
    val deviceType: String?,
    val configJson: Map<String, Any?>,
    val enabled: Int,
    val isOnline: Boolean,
    val description: String,
    val createdAt: Instant,
    val updatedAt: Instant,
)

@RestController
@RequestMapping("/api/v1/device-groups")
class DeviceGroupController(private val service: DeviceCatalogService) {
    @GetMapping fun list() = ApiResponse.success(service.listGroups())
    @GetMapping("/{id}") fun get(@PathVariable id: Long) = ApiResponse.success(service.getGroup(id))
    @GetMapping("/options") fun options() = ApiResponse.success(service.listGroups().map { mapOf("value" to it.id, "label" to it.groupName) })
    @PostMapping @ResponseStatus(HttpStatus.CREATED)
    fun create(@RequestBody request: GroupRequest) = ApiResponse.success(service.createGroup(request.command()))
    @PutMapping("/{id}") fun update(@PathVariable id: Long, @RequestBody request: GroupRequest) = ApiResponse.success(service.updateGroup(id, request.command()))
    @DeleteMapping("/{id}") fun delete(@PathVariable id: Long): ApiResponse<Nothing> { service.deleteGroup(id); return ApiResponse.success() }
    private fun GroupRequest.command() = GroupCommand(groupCode, groupName, description)
}

@RestController
@RequestMapping("/api/v1/templates")
class DeviceTemplateController(private val service: DeviceCatalogService) {
    @GetMapping
    fun list(@RequestParam(defaultValue = "1") page: Int, @RequestParam(name = "page_size", defaultValue = "20") pageSize: Int, @RequestParam(required = false) keyword: String?): ApiResponse<Page<TemplateView>> {
        val result = service.listTemplates(page, pageSize, keyword)
        return ApiResponse.success(Page(result.list.map(::view), result.total, result.page, result.pageSize))
    }
    @GetMapping("/options") fun options() = ApiResponse.success(service.listTemplates(1, 100, null).list.map { mapOf("value" to it.id, "label" to it.templateName, "protocol_type" to it.protocolType) })
    @GetMapping("/{id}") fun get(@PathVariable id: Long) = ApiResponse.success(view(service.getTemplate(id)))
    @PostMapping @ResponseStatus(HttpStatus.CREATED)
    fun create(@RequestBody request: TemplateRequest) = ApiResponse.success(view(service.createTemplate(request.command())))
    @PutMapping("/{id}") fun update(@PathVariable id: Long, @RequestBody request: TemplateRequest) = ApiResponse.success(view(service.updateTemplate(id, request.command())))
    @DeleteMapping("/{id}") fun delete(@PathVariable id: Long): ApiResponse<Nothing> { service.deleteTemplate(id); return ApiResponse.success() }
    @GetMapping("/{id}/points") fun points(@PathVariable id: Long) = ApiResponse.success(service.listPoints(id).map(::pointView))
    @GetMapping("/{id}/control-points") fun controlPoints(@PathVariable id: Long) =
        ApiResponse.success(service.listControlPoints(id).map(::controlPointView))
    @PostMapping("/{id}/points") @ResponseStatus(HttpStatus.CREATED)
    fun createPoint(@PathVariable id: Long, @RequestBody request: PointRequest) = ApiResponse.success(pointView(service.createPoint(id, request.command())))

    private fun view(template: DeviceTemplate) = TemplateView(
        template.id, template.templateCode, template.templateName, template.protocolType, template.protocolConfig,
        template.collectIntervalMs, template.timeoutMs, template.description, service.listPoints(template.id).map(::pointView),
        service.listControlPoints(template.id).map(::controlPointView),
        template.createdAt, template.updatedAt,
    )
    private fun TemplateRequest.command() = TemplateCommand(templateCode, templateName, protocolType, protocolCfg, collectInterval, timeout, description)
}

@RestController
@RequestMapping("/api/v1/points")
class TemplatePointController(private val service: DeviceCatalogService) {
    @PutMapping("/{id}") fun update(@PathVariable id: Long, @RequestBody request: PointRequest) = ApiResponse.success(pointView(service.updatePoint(id, request.command())))
    @DeleteMapping("/{id}") fun delete(@PathVariable id: Long): ApiResponse<Nothing> { service.deletePoint(id); return ApiResponse.success() }
}

@RestController
@RequestMapping("/api/v1/control-points")
class TemplateControlPointController(
    private val service: DeviceCatalogService,
    private val control: DeviceControlService,
    private val scope: DeviceScopeGuard,
) {
    @GetMapping("/{id}") fun get(@PathVariable id: Long) = ApiResponse.success(controlPointView(service.getControlPoint(id)))

    @PostMapping @ResponseStatus(HttpStatus.CREATED)
    fun create(@RequestBody request: ControlPointRequest): ApiResponse<ControlPointView> {
        if (request.templateId <= 0) throw CatalogValidationException("template_id 必须大于0")
        return ApiResponse.success(controlPointView(service.createControlPoint(request.templateId, request.command())))
    }

    @PutMapping("/{id}")
    fun update(@PathVariable id: Long, @RequestBody request: ControlPointRequest) =
        ApiResponse.success(controlPointView(service.updateControlPoint(id, request.command())))

    @DeleteMapping("/{id}")
    fun delete(@PathVariable id: Long): ApiResponse<Nothing> { service.deleteControlPoint(id); return ApiResponse.success() }

    @DeleteMapping("/batch/{ids}")
    fun batchDelete(@PathVariable ids: String): ApiResponse<Nothing> {
        ids.split(',').map(String::trim).filter(String::isNotEmpty).map(String::toLong).forEach(service::deleteControlPoint)
        return ApiResponse.success()
    }

    @PostMapping("/batch") @ResponseStatus(HttpStatus.CREATED)
    fun batchCreate(@RequestBody requests: List<ControlPointRequest>): ApiResponse<List<ControlPointView>> {
        if (requests.isEmpty()) throw CatalogValidationException("控制点不能为空")
        return ApiResponse.success(requests.map { request ->
            if (request.templateId <= 0) throw CatalogValidationException("template_id 必须大于0")
            controlPointView(service.createControlPoint(request.templateId, request.command()))
        })
    }

    @PostMapping("/{id}/execute")
    fun execute(@PathVariable id: Long, @RequestBody request: ExecuteControlRequest, servletRequest: HttpServletRequest): ApiResponse<ControlExecutionView> {
        if (request.deviceId <= 0) throw CatalogValidationException("device_id 必须大于0")
        if (request.value == null) throw CatalogValidationException("value 不能为空")
        scope.require(servletRequest, request.deviceId)
        return ApiResponse.success(runBlocking { control.execute(id, request.deviceId, request.value) })
    }
}

@RestController
@RequestMapping("/api/v1/control-logs")
class ControlLogController(private val service: DeviceCatalogService, private val scope: DeviceScopeGuard) {
    @GetMapping
    fun list(
        @RequestParam(defaultValue = "1") page: Int,
        @RequestParam(name = "page_size", defaultValue = "20") pageSize: Int,
        @RequestParam(name = "device_id", required = false) deviceId: Long?,
        @RequestParam(name = "control_point_id", required = false) controlPointId: Long?, request: HttpServletRequest,
    ) = ApiResponse.success(service.listControlLogs(ControlLogFilter(page, pageSize, deviceId, controlPointId, scope.allowed(request))))
}

@RestController
@RequestMapping("/api/v1/devices")
class DeviceController(private val service: DeviceCatalogService, private val scope: DeviceScopeGuard) {
    @GetMapping
    fun list(
        @RequestParam(defaultValue = "1") page: Int,
        @RequestParam(name = "page_size", defaultValue = "20") pageSize: Int,
        @RequestParam(required = false) keyword: String?,
        @RequestParam(name = "template_id", required = false) templateId: Long?,
        @RequestParam(required = false) status: Int?, request: HttpServletRequest,
    ): ApiResponse<Page<DeviceView>> {
        val result = service.listDevices(DeviceFilter(page, pageSize, keyword, templateId, status?.let { it != 0 }, scope.allowed(request)))
        return ApiResponse.success(Page(result.list.map(::view), result.total, result.page, result.pageSize))
    }
    @GetMapping("/options") fun options() = ApiResponse.success(mapOf(
        "templates" to service.listTemplates(1, 100, null).list.map { mapOf("value" to it.id, "label" to it.templateName) },
        "groups" to service.listGroups().map { mapOf("value" to it.id, "label" to it.groupName) },
    ))
    @GetMapping("/check-sn") fun checkSn(@RequestParam(name = "device_sn") sn: String, @RequestParam(name = "exclude_id", required = false) excludeId: Long?) = ApiResponse.success(mapOf("exists" to service.deviceSnExists(sn, excludeId)))
    @GetMapping("/{id}") fun get(@PathVariable id: Long, request: HttpServletRequest) = ApiResponse.success(scope.require(request, id).let { view(service.getDevice(id)) })
    @PostMapping @ResponseStatus(HttpStatus.CREATED)
    fun create(@RequestBody request: DeviceRequest) = ApiResponse.success(view(service.createDevice(request.command(request.deviceSn ?: ""))))
    @PutMapping("/{id}") fun update(@PathVariable id: Long, @RequestBody request: DeviceRequest, servletRequest: HttpServletRequest): ApiResponse<DeviceView> {
        scope.require(servletRequest, id)
        val sn = request.deviceSn ?: service.getDevice(id).deviceSn
        return ApiResponse.success(view(service.updateDevice(id, request.command(sn))))
    }
    @DeleteMapping("/{id}") fun delete(@PathVariable id: Long, request: HttpServletRequest): ApiResponse<Nothing> { scope.require(request, id); service.deleteDevice(id); return ApiResponse.success() }
    @DeleteMapping("/batch/{ids}") fun batchDelete(@PathVariable ids: String, request: HttpServletRequest): ApiResponse<Nothing> { ids.split(',').map(String::trim).filter(String::isNotEmpty).map(String::toLong).onEach { scope.require(request, it) }.forEach(service::deleteDevice); return ApiResponse.success() }
    @PutMapping("/{id}/enable") fun enable(@PathVariable id: Long, request: HttpServletRequest) = ApiResponse.success(scope.require(request, id).let { view(service.setEnabled(id, true)) })
    @PutMapping("/{id}/disable") fun disable(@PathVariable id: Long, request: HttpServletRequest) = ApiResponse.success(scope.require(request, id).let { view(service.setEnabled(id, false)) })

    private fun view(device: Device): DeviceView {
        val template = service.getTemplate(device.templateId)
        return DeviceView(
            device.id, device.deviceSn, device.deviceName, device.templateId,
            TemplateView(template.id, template.templateCode, template.templateName, template.protocolType, template.protocolConfig, template.collectIntervalMs, template.timeoutMs, template.description, service.listPoints(template.id).map(::pointView), service.listControlPoints(template.id).map(::controlPointView), template.createdAt, template.updatedAt),
            device.groupId, device.lineId, device.deviceVendor, device.deviceType, device.configJson,
            if (device.enabled) 1 else 0, false, device.description, device.createdAt, device.updatedAt,
        )
    }
    private fun DeviceRequest.command(sn: String) = DeviceCommand(
        sn, deviceName, templateId, groupId, lineId?.toString(), vendorId?.toString(), deviceTypeId?.toString(),
        runtimeConfig(), enabled != 0, description,
    )
}

@RestController
@RequestMapping("/api/v1/devices")
class DeviceProtocolController(
    private val diagnostics: DeviceDiagnosticsService,
    private val collection: DeviceCollectionService,
    private val sessions: DeviceSessionPool,
    private val scope: DeviceScopeGuard,
    private val realtimeHub: DeviceRealtimeHub,
) {
    @GetMapping("/statistics")
    fun statistics(request: HttpServletRequest): ApiResponse<DeviceStatisticsView> {
        val allowed = scope.allowed(request)
        val count = sessions.statuses().count { allowed == null || it.deviceId in allowed }
        return ApiResponse.success(collection.statistics(count, allowed))
    }

    @PostMapping("/{id}/test")
    fun test(@PathVariable id: Long, request: HttpServletRequest) = ApiResponse.success(scope.require(request, id).let { runBlocking { diagnostics.test(id) } })

    @PostMapping("/{id}/diagnose")
    fun diagnose(@PathVariable id: Long, request: HttpServletRequest) = ApiResponse.success(scope.require(request, id).let { runBlocking { diagnostics.diagnose(id) } })

    @PostMapping("/{id}/collect")
    fun collect(@PathVariable id: Long, request: HttpServletRequest) = ApiResponse.success(scope.require(request, id).let { runBlocking { collection.collect(id) } })

    @PostMapping("/{id}/collect/{pointId}")
    fun collectPoint(@PathVariable id: Long, @PathVariable pointId: Long, request: HttpServletRequest) =
        ApiResponse.success(scope.require(request, id).let { runBlocking { collection.collect(id, pointId) } })

    @GetMapping("/{id}/realtime")
    fun realtime(@PathVariable id: Long, request: HttpServletRequest) = ApiResponse.success(scope.require(request, id).let { collection.realtime(id) })

    @GetMapping("/{id}/subscription", produces = [MediaType.TEXT_EVENT_STREAM_VALUE])
    fun subscription(@PathVariable id: Long, request: HttpServletRequest): SseEmitter {
        scope.require(request, id)
        return realtimeHub.subscribe(id)
    }

    @GetMapping("/{id}/history")
    fun history(
        @PathVariable id: Long,
        @RequestParam(required = false) from: Instant?,
        @RequestParam(required = false) to: Instant?,
        @RequestParam(defaultValue = "1000") limit: Int, request: HttpServletRequest,
    ): ApiResponse<List<CollectedPointView>> {
        scope.require(request, id); val end = to ?: Instant.now()
        val start = from ?: end.minusSeconds(3600)
        return ApiResponse.success(collection.history(id, start, end, limit))
    }
}

@RestController
@RequestMapping("/api/v1/collector")
class CollectorMonitorController(private val sessions: DeviceSessionPool) {
    @GetMapping("/sessions")
    fun sessions() = ApiResponse.success(sessions.statuses())
}

private fun PointRequest.command() = TemplatePointCommand(pointName, pointCode, dataType, address, unit, enabled != 0, valueEnum, pointConfig, description)
private fun ControlPointRequest.command() = TemplateControlPointCommand(
    pointName, pointCode, dataType, address, defaultValue, valueRange, enabled != 0, valueEnum, pointConfig, description,
)
private fun pointView(point: TemplatePoint) = PointView(
    point.id, point.templateId, point.pointName, point.pointCode, point.dataType, point.address, point.unit,
    if (point.enabled) 1 else 0, point.valueEnum, point.pointConfig, point.description, point.createdAt, point.updatedAt,
)
private fun controlPointView(point: TemplateControlPoint) = ControlPointView(
    point.id, point.templateId, point.pointName, point.pointCode, point.dataType, point.address,
    point.defaultValue, point.valueRange, if (point.enabled) 1 else 0, point.valueEnum,
    point.pointConfig, point.description, point.createdAt, point.updatedAt,
)

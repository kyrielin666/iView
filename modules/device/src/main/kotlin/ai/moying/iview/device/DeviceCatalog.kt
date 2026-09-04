package ai.moying.iview.device

import java.time.Instant

data class Page<T>(
    val list: List<T>,
    val total: Long,
    val page: Int,
    val pageSize: Int,
)

data class DeviceGroup(
    val id: Long,
    val groupCode: String,
    val groupName: String,
    val description: String,
    val createdAt: Instant,
    val updatedAt: Instant,
)

data class DeviceTemplate(
    val id: Long,
    val templateCode: String,
    val templateName: String,
    val protocolType: String,
    val protocolConfig: Map<String, Any?>,
    val collectIntervalMs: Int,
    val timeoutMs: Int,
    val description: String,
    val createdAt: Instant,
    val updatedAt: Instant,
)

data class TemplatePoint(
    val id: Long,
    val templateId: Long,
    val pointName: String,
    val pointCode: String,
    val dataType: String,
    val address: String,
    val unit: String,
    val enabled: Boolean,
    val valueEnum: List<Map<String, Any?>>,
    val pointConfig: Map<String, Any?>,
    val description: String,
    val createdAt: Instant,
    val updatedAt: Instant,
)

data class TemplateControlPoint(
    val id: Long,
    val templateId: Long,
    val pointName: String,
    val pointCode: String,
    val dataType: String,
    val address: String,
    val defaultValue: String,
    val valueRange: String,
    val enabled: Boolean,
    val valueEnum: List<Map<String, Any?>>,
    val pointConfig: Map<String, Any?>,
    val description: String,
    val createdAt: Instant,
    val updatedAt: Instant,
)

data class ControlLog(
    val id: Long,
    val deviceId: Long,
    val deviceName: String,
    val controlPointId: Long,
    val pointName: String,
    val value: String,
    val status: Int,
    val message: String,
    val operatorId: Long,
    val operatorName: String,
    val source: String,
    val createdAt: Instant,
)

data class Device(
    val id: Long,
    val deviceSn: String,
    val deviceName: String,
    val templateId: Long,
    val groupId: Long?,
    val lineId: String?,
    val deviceVendor: String?,
    val deviceType: String?,
    val configJson: Map<String, Any?>,
    val enabled: Boolean,
    val description: String,
    val createdAt: Instant,
    val updatedAt: Instant,
)

data class GroupCommand(val code: String, val name: String, val description: String = "")

data class TemplateCommand(
    val code: String,
    val name: String,
    val protocolType: String,
    val protocolConfig: Map<String, Any?> = emptyMap(),
    val collectIntervalMs: Int = 1_000,
    val timeoutMs: Int = 5_000,
    val description: String = "",
)

data class TemplatePointCommand(
    val name: String,
    val code: String,
    val dataType: String,
    val address: String,
    val unit: String = "",
    val enabled: Boolean = true,
    val valueEnum: List<Map<String, Any?>> = emptyList(),
    val pointConfig: Map<String, Any?> = emptyMap(),
    val description: String = "",
)

data class TemplateControlPointCommand(
    val name: String,
    val code: String,
    val dataType: String,
    val address: String,
    val defaultValue: String = "",
    val valueRange: String = "",
    val enabled: Boolean = true,
    val valueEnum: List<Map<String, Any?>> = emptyList(),
    val pointConfig: Map<String, Any?> = emptyMap(),
    val description: String = "",
)

data class ControlLogCommand(
    val deviceId: Long,
    val deviceName: String,
    val controlPointId: Long,
    val pointName: String,
    val value: String,
    val status: Int,
    val message: String,
    val operatorId: Long = 0,
    val operatorName: String = "system",
    val source: String = "web",
)

data class ControlLogFilter(
    val page: Int = 1,
    val pageSize: Int = 20,
    val deviceId: Long? = null,
    val controlPointId: Long? = null,
)

data class DeviceCommand(
    val sn: String,
    val name: String,
    val templateId: Long,
    val groupId: Long? = null,
    val lineId: String? = null,
    val vendor: String? = null,
    val deviceType: String? = null,
    val config: Map<String, Any?> = emptyMap(),
    val enabled: Boolean = true,
    val description: String = "",
)

data class DeviceFilter(
    val page: Int = 1,
    val pageSize: Int = 20,
    val keyword: String? = null,
    val templateId: Long? = null,
    val enabled: Boolean? = null,
)

interface DeviceCatalogRepository {
    fun listGroups(): List<DeviceGroup>
    fun findGroup(id: Long): DeviceGroup?
    fun groupCodeExists(code: String, excludingId: Long? = null): Boolean
    fun createGroup(command: GroupCommand): DeviceGroup
    fun updateGroup(id: Long, command: GroupCommand): DeviceGroup?
    fun deleteGroup(id: Long): Boolean
    fun groupInUse(id: Long): Boolean

    fun listTemplates(page: Int, pageSize: Int, keyword: String?): Page<DeviceTemplate>
    fun findTemplate(id: Long): DeviceTemplate?
    fun templateCodeExists(code: String, excludingId: Long? = null): Boolean
    fun createTemplate(command: TemplateCommand): DeviceTemplate
    fun updateTemplate(id: Long, command: TemplateCommand): DeviceTemplate?
    fun deleteTemplate(id: Long): Boolean
    fun templateInUse(id: Long): Boolean

    fun listPoints(templateId: Long): List<TemplatePoint>
    fun findPoint(id: Long): TemplatePoint?
    fun pointCodeExists(templateId: Long, code: String, excludingId: Long? = null): Boolean
    fun createPoint(templateId: Long, command: TemplatePointCommand): TemplatePoint
    fun updatePoint(id: Long, command: TemplatePointCommand): TemplatePoint?
    fun deletePoint(id: Long): Boolean

    fun listControlPoints(templateId: Long): List<TemplateControlPoint>
    fun findControlPoint(id: Long): TemplateControlPoint?
    fun controlPointCodeExists(templateId: Long, code: String, excludingId: Long? = null): Boolean
    fun createControlPoint(templateId: Long, command: TemplateControlPointCommand): TemplateControlPoint
    fun updateControlPoint(id: Long, command: TemplateControlPointCommand): TemplateControlPoint?
    fun deleteControlPoint(id: Long): Boolean

    fun appendControlLog(command: ControlLogCommand): ControlLog
    fun listControlLogs(filter: ControlLogFilter): Page<ControlLog>

    fun listDevices(filter: DeviceFilter): Page<Device>
    fun findDevice(id: Long): Device?
    fun deviceSnExists(sn: String, excludingId: Long? = null): Boolean
    fun createDevice(command: DeviceCommand): Device
    fun updateDevice(id: Long, command: DeviceCommand): Device?
    fun deleteDevice(id: Long): Boolean
}

open class DeviceCatalogException(message: String) : RuntimeException(message)
class CatalogNotFoundException(message: String) : DeviceCatalogException(message)
class CatalogConflictException(message: String) : DeviceCatalogException(message)
class CatalogValidationException(message: String) : DeviceCatalogException(message)

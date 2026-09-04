package ai.moying.iview.device

class DeviceCatalogService(private val repository: DeviceCatalogRepository) {
    fun listGroups() = repository.listGroups()
    fun getGroup(id: Long) = repository.findGroup(id) ?: throw CatalogNotFoundException("设备组不存在: $id")
    fun createGroup(command: GroupCommand): DeviceGroup {
        validateGroup(command)
        if (repository.groupCodeExists(command.code)) throw CatalogConflictException("设备组编码已存在")
        return repository.createGroup(command.normalized())
    }
    fun updateGroup(id: Long, command: GroupCommand): DeviceGroup {
        validateGroup(command)
        if (repository.groupCodeExists(command.code, id)) throw CatalogConflictException("设备组编码已存在")
        return repository.updateGroup(id, command.normalized()) ?: throw CatalogNotFoundException("设备组不存在: $id")
    }
    fun deleteGroup(id: Long) {
        if (repository.groupInUse(id)) throw CatalogConflictException("设备组仍被设备使用")
        if (!repository.deleteGroup(id)) throw CatalogNotFoundException("设备组不存在: $id")
    }

    fun listTemplates(page: Int, pageSize: Int, keyword: String?) =
        repository.listTemplates(validPage(page), validPageSize(pageSize), keyword?.trim()?.takeIf(String::isNotEmpty))
    fun getTemplate(id: Long) = repository.findTemplate(id) ?: throw CatalogNotFoundException("设备模板不存在: $id")
    fun createTemplate(command: TemplateCommand): DeviceTemplate {
        validateTemplate(command)
        if (repository.templateCodeExists(command.code)) throw CatalogConflictException("模板标识已存在")
        return repository.createTemplate(command.normalized())
    }
    fun updateTemplate(id: Long, command: TemplateCommand): DeviceTemplate {
        validateTemplate(command)
        if (repository.templateCodeExists(command.code, id)) throw CatalogConflictException("模板标识已存在")
        return repository.updateTemplate(id, command.normalized()) ?: throw CatalogNotFoundException("设备模板不存在: $id")
    }
    fun deleteTemplate(id: Long) {
        if (repository.templateInUse(id)) throw CatalogConflictException("设备模板仍被设备使用")
        if (!repository.deleteTemplate(id)) throw CatalogNotFoundException("设备模板不存在: $id")
    }

    fun listPoints(templateId: Long): List<TemplatePoint> {
        getTemplate(templateId)
        return repository.listPoints(templateId)
    }
    fun createPoint(templateId: Long, command: TemplatePointCommand): TemplatePoint {
        getTemplate(templateId)
        validatePoint(command)
        if (repository.pointCodeExists(templateId, command.code)) throw CatalogConflictException("采集点编码已存在")
        return repository.createPoint(templateId, command.normalized())
    }
    fun updatePoint(id: Long, command: TemplatePointCommand): TemplatePoint {
        val current = repository.findPoint(id) ?: throw CatalogNotFoundException("采集点不存在: $id")
        validatePoint(command)
        if (repository.pointCodeExists(current.templateId, command.code, id)) throw CatalogConflictException("采集点编码已存在")
        return repository.updatePoint(id, command.normalized()) ?: throw CatalogNotFoundException("采集点不存在: $id")
    }
    fun deletePoint(id: Long) {
        if (!repository.deletePoint(id)) throw CatalogNotFoundException("采集点不存在: $id")
    }

    fun listControlPoints(templateId: Long): List<TemplateControlPoint> {
        getTemplate(templateId)
        return repository.listControlPoints(templateId)
    }
    fun getControlPoint(id: Long) = repository.findControlPoint(id)
        ?: throw CatalogNotFoundException("控制点不存在: $id")
    fun createControlPoint(templateId: Long, command: TemplateControlPointCommand): TemplateControlPoint {
        getTemplate(templateId)
        validateControlPoint(command)
        if (repository.controlPointCodeExists(templateId, command.code)) throw CatalogConflictException("控制点编码已存在")
        return repository.createControlPoint(templateId, command.normalized())
    }
    fun updateControlPoint(id: Long, command: TemplateControlPointCommand): TemplateControlPoint {
        val current = repository.findControlPoint(id) ?: throw CatalogNotFoundException("控制点不存在: $id")
        validateControlPoint(command)
        if (repository.controlPointCodeExists(current.templateId, command.code, id)) throw CatalogConflictException("控制点编码已存在")
        return repository.updateControlPoint(id, command.normalized()) ?: throw CatalogNotFoundException("控制点不存在: $id")
    }
    fun deleteControlPoint(id: Long) {
        if (!repository.deleteControlPoint(id)) throw CatalogNotFoundException("控制点不存在: $id")
    }
    fun appendControlLog(command: ControlLogCommand) = repository.appendControlLog(command)
    fun listControlLogs(filter: ControlLogFilter) = repository.listControlLogs(
        filter.copy(page = validPage(filter.page), pageSize = validPageSize(filter.pageSize))
    )

    fun listDevices(filter: DeviceFilter) = repository.listDevices(
        filter.copy(page = validPage(filter.page), pageSize = validPageSize(filter.pageSize), keyword = filter.keyword?.trim())
    )
    fun getDevice(id: Long) = repository.findDevice(id) ?: throw CatalogNotFoundException("设备不存在: $id")
    fun createDevice(command: DeviceCommand): Device {
        validateDevice(command)
        validateReferences(command)
        if (repository.deviceSnExists(command.sn)) throw CatalogConflictException("设备SN已存在")
        return repository.createDevice(command.normalized())
    }
    fun updateDevice(id: Long, command: DeviceCommand): Device {
        validateDevice(command)
        validateReferences(command)
        if (repository.deviceSnExists(command.sn, id)) throw CatalogConflictException("设备SN已存在")
        return repository.updateDevice(id, command.normalized()) ?: throw CatalogNotFoundException("设备不存在: $id")
    }
    fun deleteDevice(id: Long) {
        if (!repository.deleteDevice(id)) throw CatalogNotFoundException("设备不存在: $id")
    }
    fun setEnabled(id: Long, enabled: Boolean): Device {
        val current = getDevice(id)
        return updateDevice(id, current.toCommand().copy(enabled = enabled))
    }
    fun deviceSnExists(sn: String, excludingId: Long?) = repository.deviceSnExists(sn.trim(), excludingId)

    private fun validateGroup(command: GroupCommand) {
        requireMatch(command.code, GROUP_CODE, "设备组编码只能包含字母、数字、下划线和横杆，且不超过50位")
        requireText(command.name, 100, "设备组名称")
    }
    private fun validateTemplate(command: TemplateCommand) {
        requireMatch(command.code, TEMPLATE_CODE, "模板标识只能包含字母、数字和下划线，且不超过32位")
        requireText(command.name, 100, "模板名称")
        requireMatch(command.protocolType, PROTOCOL_TYPE, "协议类型格式不正确")
        if (command.collectIntervalMs !in 10..86_400_000) throw CatalogValidationException("采集间隔必须在10毫秒到24小时之间")
        if (command.timeoutMs !in 100..300_000) throw CatalogValidationException("超时时间必须在100毫秒到5分钟之间")
    }
    private fun validatePoint(command: TemplatePointCommand) {
        requireText(command.name, 100, "采集点名称")
        requireMatch(command.code, POINT_CODE, "采集点编码只能包含字母、数字、下划线、点和横杆")
        requireText(command.dataType, 30, "数据类型")
        if (command.address.isBlank() && command.pointConfig.isEmpty()) throw CatalogValidationException("采集点地址和协议配置不能同时为空")
    }
    private fun validateControlPoint(command: TemplateControlPointCommand) {
        requireText(command.name, 100, "控制点名称")
        requireMatch(command.code, POINT_CODE, "控制点编码只能包含字母、数字、下划线、点和横杆")
        requireText(command.dataType, 30, "数据类型")
        if (command.address.isBlank() && command.pointConfig.isEmpty()) throw CatalogValidationException("控制点地址和协议配置不能同时为空")
        if (command.valueRange.length > 200) throw CatalogValidationException("控制点值范围不能超过200位")
    }
    private fun validateDevice(command: DeviceCommand) {
        requireMatch(command.sn, DEVICE_SN, "设备SN只能包含字母、数字、下划线和横杆，且不超过32位")
        requireText(command.name, 100, "设备名称")
    }
    private fun validateReferences(command: DeviceCommand) {
        if (repository.findTemplate(command.templateId) == null) throw CatalogValidationException("设备模板不存在")
        if (command.groupId != null && repository.findGroup(command.groupId) == null) throw CatalogValidationException("设备组不存在")
    }
    private fun requireText(value: String, max: Int, label: String) {
        if (value.isBlank() || value.length > max) throw CatalogValidationException("${label}不能为空且不能超过${max}位")
    }
    private fun requireMatch(value: String, regex: Regex, message: String) {
        if (!regex.matches(value.trim())) throw CatalogValidationException(message)
    }
    private fun validPage(value: Int) = value.coerceAtLeast(1)
    private fun validPageSize(value: Int) = value.coerceIn(1, 100)

    private fun GroupCommand.normalized() = copy(code = code.trim(), name = name.trim(), description = description.trim())
    private fun TemplateCommand.normalized() = copy(code = code.trim(), name = name.trim(), protocolType = protocolType.trim().lowercase(), description = description.trim())
    private fun TemplatePointCommand.normalized() = copy(name = name.trim(), code = code.trim(), dataType = dataType.trim().lowercase(), address = address.trim(), unit = unit.trim(), description = description.trim())
    private fun TemplateControlPointCommand.normalized() = copy(name = name.trim(), code = code.trim(), dataType = dataType.trim().lowercase(), address = address.trim(), defaultValue = defaultValue.trim(), valueRange = valueRange.trim(), description = description.trim())
    private fun DeviceCommand.normalized() = copy(sn = sn.trim(), name = name.trim(), lineId = lineId?.trim(), vendor = vendor?.trim(), deviceType = deviceType?.trim(), description = description.trim())
    private fun Device.toCommand() = DeviceCommand(deviceSn, deviceName, templateId, groupId, lineId, deviceVendor, deviceType, configJson, enabled, description)

    companion object {
        private val GROUP_CODE = Regex("^[A-Za-z0-9_-]{1,50}$")
        private val TEMPLATE_CODE = Regex("^[A-Za-z0-9_]{1,32}$")
        private val POINT_CODE = Regex("^[A-Za-z0-9_.-]{1,50}$")
        private val DEVICE_SN = Regex("^[A-Za-z0-9_-]{1,32}$")
        private val PROTOCOL_TYPE = Regex("^[a-zA-Z0-9_-]{2,50}$")
    }
}

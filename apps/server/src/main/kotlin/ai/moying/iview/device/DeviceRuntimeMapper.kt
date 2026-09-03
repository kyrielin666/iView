package ai.moying.iview.device

import ai.moying.iview.core.device.DeviceDefinition
import ai.moying.iview.core.device.DeviceId
import ai.moying.iview.core.device.PointAccess
import ai.moying.iview.core.device.PointDataType
import ai.moying.iview.core.device.PointDefinition
import ai.moying.iview.core.device.PointId
import java.time.Duration

data class DeviceRuntimeDefinition(
    val device: Device,
    val template: DeviceTemplate,
    val definition: DeviceDefinition,
    val points: List<PointDefinition>,
    val catalogPoints: List<TemplatePoint>,
)

class DeviceRuntimeMapper(private val catalog: DeviceCatalogService) {
    fun map(deviceId: Long): DeviceRuntimeDefinition {
        val device = catalog.getDevice(deviceId)
        val template = catalog.getTemplate(device.templateId)
        val connectionProperties = buildMap {
            template.protocolConfig.forEach { (key, value) -> value?.let { put(key, it.toString()) } }
            device.configJson.forEach { (key, value) -> value?.let { put(key, it.toString()) } }
            putIfAbsent("timeout", template.timeoutMs.toString())
        }
        val catalogPoints = catalog.listPoints(template.id).filter(TemplatePoint::enabled)
        val points = catalogPoints.map { point ->
            PointDefinition(
                PointId(point.id), DeviceId(device.id), point.pointCode, point.pointName, point.address,
                dataType(point.dataType), PointAccess.READ_ONLY, Duration.ofMillis(template.collectIntervalMs.toLong()),
                point.pointConfig.mapValues { it.value?.toString().orEmpty() },
            )
        }
        return DeviceRuntimeDefinition(
            device,
            template,
            DeviceDefinition(
                DeviceId(device.id), device.deviceSn, device.deviceName, template.protocolType,
                device.enabled, connectionProperties,
            ),
            points,
            catalogPoints,
        )
    }

    private fun dataType(value: String): PointDataType = when (value.trim().lowercase()) {
        "bool", "boolean" -> PointDataType.BOOLEAN
        "int16", "short" -> PointDataType.INT16
        "uint16", "ushort" -> PointDataType.UINT16
        "int32", "int" -> PointDataType.INT32
        "uint32", "uint" -> PointDataType.UINT32
        "int64", "long" -> PointDataType.INT64
        "uint64", "ulong" -> PointDataType.UINT64
        "float32", "float" -> PointDataType.FLOAT32
        "float64", "double" -> PointDataType.FLOAT64
        "string" -> PointDataType.STRING
        "bytes", "bytearray" -> PointDataType.BYTES
        else -> throw CatalogValidationException("不支持的采集点数据类型: $value")
    }
}

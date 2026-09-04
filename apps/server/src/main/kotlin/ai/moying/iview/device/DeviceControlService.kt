package ai.moying.iview.device

import ai.moying.iview.collector.DeviceSessionPool
import ai.moying.iview.core.device.DeviceId
import ai.moying.iview.core.device.PointAccess
import ai.moying.iview.core.device.PointDefinition
import ai.moying.iview.core.device.PointId
import ai.moying.iview.protocol.PointWrite
import com.fasterxml.jackson.databind.ObjectMapper
import java.time.Duration

data class ControlExecutionView(
    val deviceId: Long,
    val controlPointId: Long,
    val writeCount: Int,
    val successful: Boolean,
)

class ControlExecutionException(message: String) : RuntimeException(message)

class DeviceControlService(
    private val catalog: DeviceCatalogService,
    private val runtimeMapper: DeviceRuntimeMapper,
    private val sessions: DeviceSessionPool,
    private val objectMapper: ObjectMapper,
) {
    suspend fun execute(
        controlPointId: Long,
        deviceId: Long,
        value: Any?,
        operatorId: Long = 0,
        operatorName: String = "system",
        source: String = "web",
    ): ControlExecutionView {
        val point = catalog.getControlPoint(controlPointId)
        if (!point.enabled) throw CatalogValidationException("控制点已停用")
        val runtime = runtimeMapper.map(deviceId)
        if (!runtime.device.enabled) throw CatalogValidationException("设备已停用，不能执行控制")
        if (runtime.device.templateId != point.templateId) throw CatalogValidationException("控制点不属于该设备模板")
        val writes = buildWrites(point, runtime.device.id, runtime.template.collectIntervalMs, value)
        val storedValue = valueString(value)
        val outcome = runCatching {
            val results = sessions.session(runtime.definition).write(writes)
            val failure = results.firstOrNull { !it.successful }
            if (results.size != writes.size) throw ControlExecutionException("协议驱动返回的写入结果数量不一致")
            if (failure != null) throw ControlExecutionException(failure.diagnostic ?: "协议写入失败")
        }
        if (outcome.isFailure) sessions.invalidate(deviceId)
        val error = outcome.exceptionOrNull()
        catalog.appendControlLog(
            ControlLogCommand(
                deviceId, runtime.device.deviceName, point.id, point.pointName, storedValue,
                if (error == null) 1 else 0, error?.message ?: "success", operatorId, operatorName, source,
            )
        )
        if (error != null) throw if (error is ControlExecutionException) error
            else ControlExecutionException(error.message ?: "控制写入失败")
        return ControlExecutionView(deviceId, controlPointId, writes.size, true)
    }

    private fun buildWrites(point: TemplateControlPoint, deviceId: Long, intervalMs: Int, value: Any?): List<PointWrite> {
        val targets = targets(point)
        val values = values(targets, value)
        return targets.mapIndexed { index, target ->
            val definition = PointDefinition(
                PointId(point.id * 1_000 + index), DeviceId(deviceId), target.key, target.name, target.address,
                runtimeMapper.mapDataType(target.dataType), PointAccess.READ_WRITE, Duration.ofMillis(intervalMs.toLong()),
                target.config.mapValues { it.value?.toString().orEmpty() },
            )
            PointWrite(definition, values[index])
        }
    }

    private data class WriteTarget(
        val key: String,
        val name: String,
        val address: String,
        val dataType: String,
        val config: Map<String, Any?>,
    )

    private fun targets(point: TemplateControlPoint): List<WriteTarget> {
        val baseConfig = point.pointConfig.filterKeys { it != "writeTargets" && it != "write_targets" }
        val raw = point.pointConfig["writeTargets"] ?: point.pointConfig["write_targets"]
        if (raw == null) {
            val address = point.address.ifBlank { baseConfig["address"]?.toString().orEmpty() }
            return listOf(WriteTarget("value", point.pointName, address, point.dataType, baseConfig + ("address" to address)))
        }
        val entries = raw as? List<*> ?: throw CatalogValidationException("writeTargets 必须是数组")
        if (entries.isEmpty()) throw CatalogValidationException("writeTargets 不能为空")
        return entries.mapIndexed { index, item ->
            val target = item as? Map<*, *> ?: throw CatalogValidationException("writeTargets[$index] 必须是对象")
            val nested = (target["point_config"] ?: target["pointConfig"]) as? Map<*, *> ?: emptyMap<Any?, Any?>()
            val targetConfig = nested.entries.associate { it.key.toString() to it.value }
            val address = target["address"]?.toString()?.takeIf(String::isNotBlank) ?: point.address
            WriteTarget(
                target["key"]?.toString()?.takeIf(String::isNotBlank) ?: "value${index + 1}",
                target["name"]?.toString()?.takeIf(String::isNotBlank) ?: "${point.pointName}-${index + 1}",
                address,
                (target["data_type"] ?: target["dataType"])?.toString()?.takeIf(String::isNotBlank) ?: point.dataType,
                baseConfig + targetConfig + ("address" to address),
            )
        }
    }

    private fun values(targets: List<WriteTarget>, value: Any?): List<Any?> {
        if (targets.size == 1) {
            val mapped = value as? Map<*, *>
            return listOf(if (mapped != null && mapped.containsKey(targets[0].key)) mapped[targets[0].key] else value)
        }
        if (value is Map<*, *>) return targets.map { target ->
            when {
                value.containsKey(target.key) -> value[target.key]
                value.containsKey(target.name) -> value[target.name]
                else -> throw CatalogValidationException("缺少控制值: ${target.key}")
            }
        }
        if (value is List<*>) {
            if (value.size != targets.size) throw CatalogValidationException("控制值数量不匹配，需要 ${targets.size} 个")
            return value
        }
        throw CatalogValidationException("多地址控制点的值必须是对象或数组")
    }

    private fun valueString(value: Any?): String = when (value) {
        null -> "null"
        is String, is Number, is Boolean -> value.toString()
        else -> objectMapper.writeValueAsString(value)
    }
}

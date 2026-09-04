package ai.moying.iview.state

import ai.moying.iview.core.device.PointValue
import ai.moying.iview.core.device.ValueQuality
import ai.moying.iview.oee.MachineState
import java.time.Instant

data class MachineStateRule(
    val id: Long,
    val templateId: Long,
    val pointId: Long,
    val standardMapping: Map<String, String>,
    val runtimeMapping: Map<String, MachineState>,
    val defaultStatus: String,
    val defaultRuntimeState: MachineState,
    val enabled: Boolean,
    val version: Long,
    val createdAt: Instant,
    val updatedAt: Instant,
)

data class MachineStateRuleCommand(
    val templateId: Long,
    val pointId: Long,
    val standardMapping: Map<String, String>,
    val runtimeMapping: Map<String, MachineState>,
    val defaultStatus: String = "unknown",
    val defaultRuntimeState: MachineState = MachineState.UNKNOWN,
    val enabled: Boolean = true,
)

data class CurrentMachineState(
    val deviceId: Long,
    val rawStatus: String?,
    val standardStatus: String,
    val runtimeState: MachineState,
    val source: String,
    val ruleVersion: String,
    val reportedAt: Instant,
    val updatedAt: Instant,
)

data class StoredMachineStateInterval(
    val id: Long,
    val deviceId: Long,
    val state: MachineState,
    val startedAt: Instant,
    val endedAt: Instant?,
    val ruleVersion: String,
)

interface MachineStateRepository {
    fun listRules(): List<MachineStateRule>
    fun findRule(id: Long): MachineStateRule?
    fun createRule(command: MachineStateRuleCommand): MachineStateRule
    fun updateRule(id: Long, command: MachineStateRuleCommand): MachineStateRule?
    fun deleteRule(id: Long): Boolean
    fun current(deviceId: Long): CurrentMachineState?
    fun record(next: CurrentMachineState): Boolean
    fun listIntervals(deviceId: Long, start: Instant, endExclusive: Instant): List<StoredMachineStateInterval>
}

open class MachineStateException(message: String) : RuntimeException(message)
class MachineStateNotFoundException(message: String) : MachineStateException(message)
class MachineStateValidationException(message: String) : MachineStateException(message)

class MachineStateService(private val repository: MachineStateRepository) {
    fun listRules() = repository.listRules()
    fun getRule(id: Long) = repository.findRule(id) ?: throw MachineStateNotFoundException("设备状态规则不存在: $id")
    fun createRule(command: MachineStateRuleCommand): MachineStateRule {
        val normalized = command.normalized(); validate(normalized); return repository.createRule(normalized)
    }
    fun updateRule(id: Long, command: MachineStateRuleCommand): MachineStateRule {
        getRule(id); val normalized = command.normalized(); validate(normalized)
        return repository.updateRule(id, normalized) ?: throw MachineStateNotFoundException("设备状态规则不存在: $id")
    }
    fun deleteRule(id: Long) { if (!repository.deleteRule(id)) throw MachineStateNotFoundException("设备状态规则不存在: $id") }
    fun setEnabled(id: Long, enabled: Boolean): MachineStateRule {
        val rule = getRule(id)
        return updateRule(id, rule.toCommand().copy(enabled = enabled))
    }
    fun current(deviceId: Long) = repository.current(deviceId)
    fun intervals(deviceId: Long, start: Instant, endExclusive: Instant): List<StoredMachineStateInterval> {
        if (!endExclusive.isAfter(start)) throw MachineStateValidationException("结束时间必须晚于开始时间")
        return repository.listIntervals(deviceId, start, endExclusive)
    }

    fun inspect(values: List<PointValue>) {
        val rules = repository.listRules().asSequence().filter { it.enabled }.associateBy { it.pointId }
        values.filter { it.quality == ValueQuality.GOOD }.forEach { value ->
            val rule = rules[value.pointId.value] ?: return@forEach
            val previous = repository.current(value.deviceId.value)
            val raw = value.value?.toString()?.trim()?.takeIf(String::isNotBlank)
            val offlineRecovery = raw == null && previous?.runtimeState == MachineState.OFFLINE
            val standard = if (offlineRecovery) "normal" else raw?.let { rule.standardMapping[normalize(it)] } ?: rule.defaultStatus
            val runtime = raw?.let { rule.runtimeMapping[normalize(it)] } ?: rule.defaultRuntimeState
            repository.record(CurrentMachineState(
                value.deviceId.value, raw, standard, runtime, value.source, "state-rule-${rule.id}-v${rule.version}",
                value.observedAt, value.receivedAt,
            ))
        }
    }

    private fun validate(command: MachineStateRuleCommand) {
        if (command.templateId <= 0 || command.pointId <= 0) throw MachineStateValidationException("模板和采集点必须有效")
        if (command.standardMapping.isEmpty() || command.standardMapping.any { it.key.isBlank() || it.value.isBlank() }) throw MachineStateValidationException("状态映射不能为空")
        if (command.runtimeMapping.isEmpty() || command.runtimeMapping.any { it.key.isBlank() }) throw MachineStateValidationException("运行状态映射不能为空")
        if (command.defaultStatus.isBlank()) throw MachineStateValidationException("默认状态不能为空")
    }
    private fun MachineStateRuleCommand.normalized() = copy(
        standardMapping = standardMapping.mapKeys { normalize(it.key) }.mapValues { it.value.trim() },
        runtimeMapping = runtimeMapping.mapKeys { normalize(it.key) }, defaultStatus = defaultStatus.trim(),
    )
    private fun MachineStateRule.toCommand() = MachineStateRuleCommand(templateId, pointId, standardMapping, runtimeMapping, defaultStatus, defaultRuntimeState, enabled)
    private fun normalize(value: String) = value.trim().lowercase()
}

package ai.moying.iview.alarm

import ai.moying.iview.core.device.PointValue
import ai.moying.iview.core.device.ValueQuality
import java.time.Instant

enum class AlarmStatus { ACTIVE, RECOVERED }

data class AlarmClassificationRule(
    val id: Long,
    val pattern: String,
    val category: Int,
    val priority: Int,
    val enabled: Boolean,
    val createdAt: Instant,
    val updatedAt: Instant,
)

data class AlarmClassificationRuleCommand(
    val pattern: String,
    val category: Int,
    val priority: Int = 0,
    val enabled: Boolean = true,
)

data class DeviceAlarm(
    val id: Long,
    val deviceId: Long,
    val abnormalAt: Instant,
    val recoveredAt: Instant?,
    val alarmContent: String?,
    val alarmCategory: Int?,
    val status: AlarmStatus,
    val createdAt: Instant,
    val updatedAt: Instant,
)

data class DeviceAlarmFilter(
    val page: Int = 1,
    val pageSize: Int = 20,
    val deviceId: Long? = null,
    val status: AlarmStatus? = null,
)

data class AlarmPage<T>(val list: List<T>, val total: Long, val page: Int, val pageSize: Int)

interface AlarmRepository {
    fun listRules(): List<AlarmClassificationRule>
    fun findRule(id: Long): AlarmClassificationRule?
    fun createRule(command: AlarmClassificationRuleCommand): AlarmClassificationRule
    fun updateRule(id: Long, command: AlarmClassificationRuleCommand): AlarmClassificationRule?
    fun deleteRule(id: Long): Boolean

    fun findActive(deviceId: Long): DeviceAlarm?
    fun createAlarm(deviceId: Long, eventAt: Instant, content: String?, category: Int?): DeviceAlarm
    fun recover(id: Long, eventAt: Instant): DeviceAlarm?
    fun listAlarms(filter: DeviceAlarmFilter): AlarmPage<DeviceAlarm>
    fun deleteBefore(cutoff: Instant): Int
}

open class AlarmException(message: String) : RuntimeException(message)
class AlarmNotFoundException(message: String) : AlarmException(message)
class AlarmValidationException(message: String) : AlarmException(message)

class AlarmService(private val repository: AlarmRepository) {
    fun listRules() = repository.listRules()
    fun getRule(id: Long) = repository.findRule(id) ?: throw AlarmNotFoundException("告警分类规则不存在: $id")
    fun createRule(command: AlarmClassificationRuleCommand): AlarmClassificationRule {
        val normalized = command.normalized()
        validate(normalized)
        return repository.createRule(normalized)
    }
    fun updateRule(id: Long, command: AlarmClassificationRuleCommand): AlarmClassificationRule {
        getRule(id)
        val normalized = command.normalized()
        validate(normalized)
        return repository.updateRule(id, normalized) ?: throw AlarmNotFoundException("告警分类规则不存在: $id")
    }
    fun deleteRule(id: Long) {
        if (!repository.deleteRule(id)) throw AlarmNotFoundException("告警分类规则不存在: $id")
    }
    fun setRuleEnabled(id: Long, enabled: Boolean): AlarmClassificationRule {
        val current = getRule(id)
        return updateRule(id, current.toCommand().copy(enabled = enabled))
    }

    fun listAlarms(filter: DeviceAlarmFilter) = repository.listAlarms(filter.normalized())
    fun cleanupBefore(cutoff: Instant): Int = repository.deleteBefore(cutoff)

    fun markAbnormal(deviceId: Long, eventAt: Instant, content: String?): DeviceAlarm? {
        requireDeviceId(deviceId)
        if (repository.findActive(deviceId) != null) return null
        val normalized = content?.trim()?.ifBlank { null }
        return repository.createAlarm(deviceId, eventAt, normalized, classify(normalized))
    }

    fun markNormal(deviceId: Long, eventAt: Instant): DeviceAlarm? {
        requireDeviceId(deviceId)
        val active = repository.findActive(deviceId) ?: return null
        return repository.recover(active.id, eventAt)
    }

    private fun classify(content: String?): Int? = content?.let { message ->
        repository.listRules().asSequence().filter { it.enabled }.sortedWith(compareBy(AlarmClassificationRule::priority, AlarmClassificationRule::id))
            .firstOrNull { Regex(it.pattern).containsMatchIn(message) }?.category
    }
    private fun validate(command: AlarmClassificationRuleCommand) {
        if (command.pattern.isBlank() || command.pattern.length > 500) throw AlarmValidationException("匹配规则不能为空且不能超过500位")
        if (command.category < 0) throw AlarmValidationException("告警分类必须大于或等于0")
        if (command.priority < 0) throw AlarmValidationException("优先级必须大于或等于0")
        try { Regex(command.pattern) } catch (error: IllegalArgumentException) { throw AlarmValidationException(error.message ?: "匹配规则格式不正确") }
    }
    private fun requireDeviceId(deviceId: Long) {
        if (deviceId <= 0) throw AlarmValidationException("设备标识必须大于0")
    }
    private fun AlarmClassificationRuleCommand.normalized() = copy(pattern = pattern.trim())
    private fun AlarmClassificationRule.toCommand() = AlarmClassificationRuleCommand(pattern, category, priority, enabled)
    private fun DeviceAlarmFilter.normalized() = copy(page = page.coerceAtLeast(1), pageSize = pageSize.coerceIn(1, 100))
}

/** Converts one collection batch into one device availability state transition. */
class CollectionAlarmMonitor(private val alarms: AlarmService) {
    fun inspect(values: List<PointValue>) {
        values.groupBy { it.deviceId.value }.forEach { (deviceId, samples) ->
            val abnormal = samples.firstOrNull { it.quality in ABNORMAL_QUALITIES }
            if (abnormal == null) alarms.markNormal(deviceId, samples.maxOfOrNull { it.observedAt } ?: Instant.now())
            else alarms.markAbnormal(deviceId, abnormal.observedAt, abnormal.message())
        }
    }

    private fun PointValue.message() = diagnostic?.trim()?.takeIf(String::isNotBlank)
        ?: "采集异常: ${quality.name.lowercase()}"

    private companion object {
        val ABNORMAL_QUALITIES = setOf(
            ValueQuality.TIMEOUT, ValueQuality.OFFLINE, ValueQuality.BAD_CONFIGURATION,
            ValueQuality.BAD_RESPONSE, ValueQuality.OUT_OF_RANGE,
        )
    }
}

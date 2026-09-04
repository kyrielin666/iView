package ai.moying.iview.alarm

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import java.time.Instant

class AlarmServiceTest {
    @Test fun `deduplicates active alarms then recovers and classifies by priority`() {
        val repository = MemoryAlarms()
        val service = AlarmService(repository)
        service.createRule(AlarmClassificationRuleCommand(".*离线.*", 2, 10))
        service.createRule(AlarmClassificationRuleCommand("设备离线", 1, 0))
        val now = Instant.parse("2026-01-01T00:00:00Z")

        val created = service.markAbnormal(7, now, "设备离线")
        assertEquals(1, created?.alarmCategory)
        assertNull(service.markAbnormal(7, now.plusSeconds(1), "重复异常"))
        val recovered = service.markNormal(7, now.plusSeconds(5))
        assertEquals(AlarmStatus.RECOVERED, recovered?.status)
        assertEquals(now.plusSeconds(5), recovered?.recoveredAt)
    }
}

private class MemoryAlarms : AlarmRepository {
    private var sequence = 0L
    private val rules = mutableListOf<AlarmClassificationRule>()
    private val alarms = mutableListOf<DeviceAlarm>()
    override fun listRules() = rules.toList()
    override fun findRule(id: Long) = rules.find { it.id == id }
    override fun createRule(command: AlarmClassificationRuleCommand): AlarmClassificationRule = AlarmClassificationRule(++sequence, command.pattern, command.category, command.priority, command.enabled, Instant.EPOCH, Instant.EPOCH).also(rules::add)
    override fun updateRule(id: Long, command: AlarmClassificationRuleCommand) = findRule(id)?.let { old -> old.copy(pattern = command.pattern, category = command.category, priority = command.priority, enabled = command.enabled).also { next -> rules[rules.indexOf(old)] = next } }
    override fun deleteRule(id: Long) = rules.removeIf { it.id == id }
    override fun findActive(deviceId: Long) = alarms.lastOrNull { it.deviceId == deviceId && it.status == AlarmStatus.ACTIVE }
    override fun createAlarm(deviceId: Long, eventAt: Instant, content: String?, category: Int?) = DeviceAlarm(++sequence, deviceId, eventAt, null, content, category, AlarmStatus.ACTIVE, eventAt, eventAt).also(alarms::add)
    override fun recover(id: Long, eventAt: Instant) = alarms.find { it.id == id }?.let { old -> old.copy(recoveredAt = eventAt, status = AlarmStatus.RECOVERED, updatedAt = eventAt).also { next -> alarms[alarms.indexOf(old)] = next } }
    override fun listAlarms(filter: DeviceAlarmFilter) = AlarmPage(alarms.toList(), alarms.size.toLong(), filter.page, filter.pageSize)
    override fun deleteBefore(cutoff: Instant) = 0
}

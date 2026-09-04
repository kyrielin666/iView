package ai.moying.iview.alarm.jdbc

import ai.moying.iview.alarm.AlarmClassificationRule
import ai.moying.iview.alarm.AlarmClassificationRuleCommand
import ai.moying.iview.alarm.AlarmPage
import ai.moying.iview.alarm.AlarmRepository
import ai.moying.iview.alarm.AlarmStatus
import ai.moying.iview.alarm.DeviceAlarm
import ai.moying.iview.alarm.DeviceAlarmFilter
import org.springframework.jdbc.core.RowMapper
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.jdbc.core.simple.SimpleJdbcInsert
import org.springframework.stereotype.Repository
import java.sql.ResultSet
import java.time.Instant

@Repository
class JdbcAlarmRepository(private val jdbc: NamedParameterJdbcTemplate) : AlarmRepository {
    private val ruleInsert by lazy { insert("iview_alarm_classification_rule", "pattern", "category", "priority", "enabled") }
    private val alarmInsert by lazy { insert("iview_device_alarm", "device_id", "abnormal_at", "alarm_content", "alarm_category", "status") }

    override fun listRules(): List<AlarmClassificationRule> = jdbc.query(
        "SELECT * FROM iview_alarm_classification_rule ORDER BY priority, id", emptyMap<String, Any>(), ruleMapper,
    )
    override fun findRule(id: Long): AlarmClassificationRule? = single(
        "SELECT * FROM iview_alarm_classification_rule WHERE id=:id", mapOf("id" to id), ruleMapper,
    )
    override fun createRule(command: AlarmClassificationRuleCommand): AlarmClassificationRule {
        val id = ruleInsert.executeAndReturnKey(values(command)).toLong()
        return requireNotNull(findRule(id))
    }
    override fun updateRule(id: Long, command: AlarmClassificationRuleCommand): AlarmClassificationRule? {
        val updated = jdbc.update(
            "UPDATE iview_alarm_classification_rule SET pattern=:pattern, category=:category, priority=:priority, enabled=:enabled, updated_at=CURRENT_TIMESTAMP WHERE id=:id",
            values(command) + ("id" to id),
        )
        return if (updated == 0) null else findRule(id)
    }
    override fun deleteRule(id: Long) = jdbc.update("DELETE FROM iview_alarm_classification_rule WHERE id=:id", mapOf("id" to id)) > 0

    override fun findActive(deviceId: Long): DeviceAlarm? = single(
        "SELECT * FROM iview_device_alarm WHERE device_id=:deviceId AND status='ACTIVE' ORDER BY id DESC LIMIT 1",
        mapOf("deviceId" to deviceId), alarmMapper,
    )
    override fun createAlarm(deviceId: Long, eventAt: Instant, content: String?, category: Int?): DeviceAlarm {
        val id = alarmInsert.executeAndReturnKey(mapOf(
            "device_id" to deviceId, "abnormal_at" to eventAt, "alarm_content" to content,
            "alarm_category" to category, "status" to AlarmStatus.ACTIVE.name,
        )).toLong()
        return requireNotNull(single("SELECT * FROM iview_device_alarm WHERE id=:id", mapOf("id" to id), alarmMapper))
    }
    override fun recover(id: Long, eventAt: Instant): DeviceAlarm? {
        val updated = jdbc.update(
            "UPDATE iview_device_alarm SET status='RECOVERED', recovered_at=:eventAt, updated_at=CURRENT_TIMESTAMP WHERE id=:id AND status='ACTIVE'",
            mapOf("id" to id, "eventAt" to eventAt),
        )
        return if (updated == 0) null else single("SELECT * FROM iview_device_alarm WHERE id=:id", mapOf("id" to id), alarmMapper)
    }
    override fun listAlarms(filter: DeviceAlarmFilter): AlarmPage<DeviceAlarm> {
        val clauses = mutableListOf<String>()
        val params = mutableMapOf<String, Any>("limit" to filter.pageSize, "offset" to (filter.page - 1) * filter.pageSize)
        filter.deviceId?.let { clauses += "device_id=:deviceId"; params["deviceId"] = it }
        filter.status?.let { clauses += "status=:status"; params["status"] = it.name }
        val where = if (clauses.isEmpty()) "" else "WHERE ${clauses.joinToString(" AND ")}"
        return AlarmPage(
            jdbc.query("SELECT * FROM iview_device_alarm $where ORDER BY id DESC LIMIT :limit OFFSET :offset", params, alarmMapper),
            jdbc.queryForObject("SELECT COUNT(*) FROM iview_device_alarm $where", params, Long::class.java) ?: 0,
            filter.page, filter.pageSize,
        )
    }
    override fun deleteBefore(cutoff: Instant) = jdbc.update(
        "DELETE FROM iview_device_alarm WHERE abnormal_at < :cutoff", mapOf("cutoff" to cutoff),
    )

    private fun values(command: AlarmClassificationRuleCommand) = mapOf(
        "pattern" to command.pattern, "category" to command.category, "priority" to command.priority, "enabled" to command.enabled,
    )
    private fun insert(table: String, vararg columns: String) = SimpleJdbcInsert(jdbc.jdbcTemplate)
        .withTableName(table).usingGeneratedKeyColumns("id").usingColumns(*columns)
    private fun <T> single(sql: String, params: Map<String, Any?>, mapper: RowMapper<T>) = jdbc.query(sql, params, mapper).firstOrNull()
    private fun instant(rs: ResultSet, name: String) = rs.getTimestamp(name).toInstant()
    private val ruleMapper = RowMapper { rs, _ -> AlarmClassificationRule(
        rs.getLong("id"), rs.getString("pattern"), rs.getInt("category"), rs.getInt("priority"), rs.getBoolean("enabled"),
        instant(rs, "created_at"), instant(rs, "updated_at"),
    ) }
    private val alarmMapper = RowMapper { rs, _ -> DeviceAlarm(
        rs.getLong("id"), rs.getLong("device_id"), instant(rs, "abnormal_at"),
        rs.getTimestamp("recovered_at")?.toInstant(), rs.getString("alarm_content"), (rs.getObject("alarm_category") as? Number)?.toInt(),
        AlarmStatus.valueOf(rs.getString("status")), instant(rs, "created_at"), instant(rs, "updated_at"),
    ) }
}

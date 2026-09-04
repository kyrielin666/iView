package ai.moying.iview.state.jdbc

import ai.moying.iview.oee.MachineState
import ai.moying.iview.state.CurrentMachineState
import ai.moying.iview.state.MachineStateRepository
import ai.moying.iview.state.MachineStateRule
import ai.moying.iview.state.MachineStateRuleCommand
import ai.moying.iview.state.StoredMachineStateInterval
import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.jdbc.core.RowMapper
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.jdbc.core.simple.SimpleJdbcInsert
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Transactional
import java.sql.ResultSet
import java.time.Instant

@Repository
class JdbcMachineStateRepository(private val jdbc: NamedParameterJdbcTemplate, private val mapper: ObjectMapper) : MachineStateRepository {
    private val ruleInsert by lazy { insert("iview_machine_state_rule", "template_id", "point_id", "standard_mapping_json", "runtime_mapping_json", "default_status", "default_runtime_state", "enabled", "rule_version") }
    private val currentInsert by lazy { insert("iview_device_current_state", "device_id", "raw_status", "standard_status", "runtime_state", "source", "rule_version", "reported_at") }
    private val intervalInsert by lazy { insert("iview_machine_state_interval", "device_id", "state", "started_at", "rule_version") }
    override fun listRules() = jdbc.query("SELECT * FROM iview_machine_state_rule ORDER BY id", emptyMap<String, Any>(), ruleMapper)
    override fun findRule(id: Long) = one("SELECT * FROM iview_machine_state_rule WHERE id=:id", mapOf("id" to id), ruleMapper)
    override fun createRule(command: MachineStateRuleCommand): MachineStateRule {
        val id = ruleInsert.executeAndReturnKey(values(command, 1)).toLong(); return requireNotNull(findRule(id))
    }
    override fun updateRule(id: Long, command: MachineStateRuleCommand): MachineStateRule? {
        val count = jdbc.update("UPDATE iview_machine_state_rule SET template_id=:template_id, point_id=:point_id, standard_mapping_json=:standard_mapping_json, runtime_mapping_json=:runtime_mapping_json, default_status=:default_status, default_runtime_state=:default_runtime_state, enabled=:enabled, rule_version=rule_version+1, updated_at=CURRENT_TIMESTAMP WHERE id=:id", values(command, 0) + ("id" to id))
        return if (count == 0) null else findRule(id)
    }
    override fun deleteRule(id: Long) = jdbc.update("DELETE FROM iview_machine_state_rule WHERE id=:id", mapOf("id" to id)) > 0
    override fun current(deviceId: Long) = one("SELECT * FROM iview_device_current_state WHERE device_id=:deviceId", mapOf("deviceId" to deviceId), currentMapper)

    @Transactional
    override fun record(next: CurrentMachineState): Boolean {
        val previous = current(next.deviceId)
        if (previous?.runtimeState != next.runtimeState) {
            if (previous != null) jdbc.update("UPDATE iview_machine_state_interval SET ended_at=:endedAt WHERE device_id=:deviceId AND ended_at IS NULL", mapOf("deviceId" to next.deviceId, "endedAt" to next.reportedAt))
            intervalInsert.execute(mapOf("device_id" to next.deviceId, "state" to next.runtimeState.name, "started_at" to next.reportedAt, "rule_version" to next.ruleVersion))
        }
        if (previous == null) currentInsert.execute(currentValues(next))
        else jdbc.update("UPDATE iview_device_current_state SET raw_status=:raw_status, standard_status=:standard_status, runtime_state=:runtime_state, source=:source, rule_version=:rule_version, reported_at=:reported_at, updated_at=CURRENT_TIMESTAMP WHERE device_id=:device_id", currentValues(next))
        return previous?.runtimeState != next.runtimeState
    }
    override fun listIntervals(deviceId: Long, start: Instant, endExclusive: Instant) = jdbc.query(
        "SELECT * FROM iview_machine_state_interval WHERE device_id=:deviceId AND started_at < :endExclusive AND (ended_at IS NULL OR ended_at > :start) ORDER BY started_at", mapOf("deviceId" to deviceId, "start" to start, "endExclusive" to endExclusive), intervalMapper)
    private fun values(c: MachineStateRuleCommand, version: Int) = mapOf("template_id" to c.templateId, "point_id" to c.pointId, "standard_mapping_json" to mapper.writeValueAsString(c.standardMapping), "runtime_mapping_json" to mapper.writeValueAsString(c.runtimeMapping.mapValues { it.value.name }), "default_status" to c.defaultStatus, "default_runtime_state" to c.defaultRuntimeState.name, "enabled" to c.enabled, "rule_version" to version)
    private fun currentValues(c: CurrentMachineState) = mapOf("device_id" to c.deviceId, "raw_status" to c.rawStatus, "standard_status" to c.standardStatus, "runtime_state" to c.runtimeState.name, "source" to c.source, "rule_version" to c.ruleVersion, "reported_at" to c.reportedAt)
    private fun insert(t: String, vararg c: String) = SimpleJdbcInsert(jdbc.jdbcTemplate).withTableName(t).usingGeneratedKeyColumns("id").usingColumns(*c)
    private fun <T> one(s: String, p: Map<String, Any?>, r: RowMapper<T>) = jdbc.query(s, p, r).firstOrNull()
    private fun instant(rs: ResultSet, n: String) = rs.getTimestamp(n).toInstant()
    private fun stringMap(json: String) = mapper.readValue(json, object : TypeReference<Map<String, String>>() {})
    private val ruleMapper = RowMapper { rs, _ -> MachineStateRule(rs.getLong("id"), rs.getLong("template_id"), rs.getLong("point_id"), stringMap(rs.getString("standard_mapping_json")), stringMap(rs.getString("runtime_mapping_json")).mapValues { MachineState.valueOf(it.value) }, rs.getString("default_status"), MachineState.valueOf(rs.getString("default_runtime_state")), rs.getBoolean("enabled"), rs.getLong("rule_version"), instant(rs,"created_at"), instant(rs,"updated_at")) }
    private val currentMapper = RowMapper { rs, _ -> CurrentMachineState(rs.getLong("device_id"), rs.getString("raw_status"), rs.getString("standard_status"), MachineState.valueOf(rs.getString("runtime_state")), rs.getString("source"), rs.getString("rule_version"), instant(rs,"reported_at"), instant(rs,"updated_at")) }
    private val intervalMapper = RowMapper { rs, _ -> StoredMachineStateInterval(rs.getLong("id"), rs.getLong("device_id"), MachineState.valueOf(rs.getString("state")), instant(rs,"started_at"), rs.getTimestamp("ended_at")?.toInstant(), rs.getString("rule_version")) }
}

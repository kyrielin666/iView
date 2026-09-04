package ai.moying.iview.outbound.jdbc

import ai.moying.iview.outbound.PushConfig
import ai.moying.iview.outbound.PushConfigCommand
import ai.moying.iview.outbound.PushConfigFilter
import ai.moying.iview.outbound.PushLog
import ai.moying.iview.outbound.PushLogCommand
import ai.moying.iview.outbound.PushLogFilter
import ai.moying.iview.outbound.PushPage
import ai.moying.iview.outbound.PushRepository
import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.jdbc.core.RowMapper
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.jdbc.core.simple.SimpleJdbcInsert
import org.springframework.stereotype.Repository
import java.sql.ResultSet
import java.time.Instant

@Repository
class JdbcPushRepository(
    private val jdbc: NamedParameterJdbcTemplate,
    private val objectMapper: ObjectMapper,
) : PushRepository {
    private val configInsert by lazy {
        insert("iview_push_config", "config_name", "push_type", "enabled", "priority", "timeout_ms", "retry_count", "retry_delay_ms", "settings_json", "description")
    }
    private val logInsert by lazy {
        insert("iview_push_log", "config_id", "config_name", "device_sn", "status", "retry_count", "message", "payload_size", "duration_ms")
    }

    override fun listConfigs(filter: PushConfigFilter): PushPage<PushConfig> {
        val clauses = mutableListOf<String>()
        val params = mutableMapOf<String, Any>("limit" to filter.pageSize, "offset" to (filter.page - 1) * filter.pageSize)
        filter.keyword?.let { clauses += "config_name ILIKE :keyword"; params["keyword"] = "%$it%" }
        filter.pushType?.let { clauses += "push_type=:pushType"; params["pushType"] = it }
        val where = where(clauses)
        return PushPage(
            jdbc.query("SELECT * FROM iview_push_config $where ORDER BY id DESC LIMIT :limit OFFSET :offset", params, configMapper),
            count("SELECT COUNT(*) FROM iview_push_config $where", params), filter.page, filter.pageSize,
        )
    }

    override fun listEnabledConfigs(): List<PushConfig> = jdbc.query(
        "SELECT * FROM iview_push_config WHERE enabled=TRUE ORDER BY priority, id", emptyMap<String, Any>(), configMapper,
    )

    override fun findConfig(id: Long): PushConfig? = single(
        "SELECT * FROM iview_push_config WHERE id=:id", mapOf("id" to id), configMapper,
    )

    override fun createConfig(command: PushConfigCommand): PushConfig {
        val id = configInsert.executeAndReturnKey(values(command)).toLong()
        return requireNotNull(findConfig(id))
    }

    override fun updateConfig(id: Long, command: PushConfigCommand): PushConfig? {
        val count = jdbc.update(
            """UPDATE iview_push_config SET config_name=:config_name, push_type=:push_type,
               enabled=:enabled, priority=:priority, timeout_ms=:timeout_ms, retry_count=:retry_count,
               retry_delay_ms=:retry_delay_ms, settings_json=:settings_json, description=:description,
               updated_at=CURRENT_TIMESTAMP WHERE id=:id""",
            values(command) + ("id" to id),
        )
        return if (count == 0) null else findConfig(id)
    }

    override fun deleteConfig(id: Long) = jdbc.update("DELETE FROM iview_push_config WHERE id=:id", mapOf("id" to id)) > 0

    override fun setEnabled(id: Long, enabled: Boolean): PushConfig? {
        val count = jdbc.update(
            "UPDATE iview_push_config SET enabled=:enabled, updated_at=CURRENT_TIMESTAMP WHERE id=:id",
            mapOf("id" to id, "enabled" to enabled),
        )
        return if (count == 0) null else findConfig(id)
    }

    override fun appendLog(command: PushLogCommand): PushLog {
        val id = logInsert.executeAndReturnKey(
            mapOf(
                "config_id" to command.configId, "config_name" to command.configName,
                "device_sn" to command.deviceSn, "status" to command.status.code,
                "retry_count" to command.retryCount, "message" to command.message.take(500),
                "payload_size" to command.payloadSize, "duration_ms" to command.duration,
            )
        ).toLong()
        return requireNotNull(single("SELECT * FROM iview_push_log WHERE id=:id", mapOf("id" to id), logMapper))
    }

    override fun listLogs(filter: PushLogFilter): PushPage<PushLog> {
        val clauses = mutableListOf<String>()
        val params = mutableMapOf<String, Any>("limit" to filter.pageSize, "offset" to (filter.page - 1) * filter.pageSize)
        filter.configId?.let { clauses += "config_id=:configId"; params["configId"] = it }
        filter.deviceSn?.let { clauses += "device_sn ILIKE :deviceSn"; params["deviceSn"] = "%$it%" }
        filter.status?.let { clauses += "status=:status"; params["status"] = it }
        val where = where(clauses)
        return PushPage(
            jdbc.query("SELECT * FROM iview_push_log $where ORDER BY id DESC LIMIT :limit OFFSET :offset", params, logMapper),
            count("SELECT COUNT(*) FROM iview_push_log $where", params), filter.page, filter.pageSize,
        )
    }

    private fun values(command: PushConfigCommand): Map<String, Any?> = mapOf(
        "config_name" to command.name, "push_type" to command.pushType, "enabled" to command.enabled,
        "priority" to command.priority, "timeout_ms" to command.timeoutMs, "retry_count" to command.retryCount,
        "retry_delay_ms" to command.retryDelayMs, "settings_json" to objectMapper.writeValueAsString(command.config),
        "description" to command.description,
    )

    private fun where(clauses: List<String>) = if (clauses.isEmpty()) "" else "WHERE ${clauses.joinToString(" AND ")}"
    private fun insert(table: String, vararg columns: String) = SimpleJdbcInsert(jdbc.jdbcTemplate)
        .withTableName(table).usingGeneratedKeyColumns("id").usingColumns(*columns)
    private fun count(sql: String, params: Map<String, Any?>) = jdbc.queryForObject(sql, params, Long::class.java) ?: 0
    private fun <T> single(sql: String, params: Map<String, Any?>, mapper: RowMapper<T>) = jdbc.query(sql, params, mapper).firstOrNull()
    private fun mapJson(value: String?): Map<String, Any?> = if (value.isNullOrBlank()) emptyMap()
        else objectMapper.readValue(value, object : TypeReference<Map<String, Any?>>() {})
    private fun instant(rs: ResultSet, name: String): Instant = rs.getTimestamp(name).toInstant()

    private val configMapper = RowMapper { rs, _ ->
        PushConfig(
            rs.getLong("id"), rs.getString("config_name"), rs.getString("push_type"), rs.getBoolean("enabled"),
            rs.getInt("priority"), rs.getInt("timeout_ms"), rs.getInt("retry_count"), rs.getInt("retry_delay_ms"),
            mapJson(rs.getString("settings_json")), rs.getString("description"),
            instant(rs, "created_at"), instant(rs, "updated_at"),
        )
    }
    private val logMapper = RowMapper { rs, _ ->
        PushLog(
            rs.getLong("id"), rs.getLong("config_id"), rs.getString("config_name"), rs.getString("device_sn"),
            rs.getInt("status"), rs.getInt("retry_count"), rs.getString("message"), rs.getInt("payload_size"),
            rs.getLong("duration_ms"), instant(rs, "created_at"),
        )
    }
}

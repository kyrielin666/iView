package ai.moying.iview.device.jdbc

import ai.moying.iview.device.Device
import ai.moying.iview.device.DeviceCatalogRepository
import ai.moying.iview.device.DeviceCommand
import ai.moying.iview.device.DeviceFilter
import ai.moying.iview.device.DeviceGroup
import ai.moying.iview.device.DeviceTemplate
import ai.moying.iview.device.GroupCommand
import ai.moying.iview.device.Page
import ai.moying.iview.device.TemplateCommand
import ai.moying.iview.device.TemplatePoint
import ai.moying.iview.device.TemplatePointCommand
import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.jdbc.core.RowMapper
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.jdbc.core.simple.SimpleJdbcInsert
import org.springframework.stereotype.Repository
import java.sql.ResultSet
import java.time.Instant

@Repository
class JdbcDeviceCatalogRepository(
    private val jdbc: NamedParameterJdbcTemplate,
    private val objectMapper: ObjectMapper,
) : DeviceCatalogRepository {
    private val groupInsert by lazy { insert("iview_device_group", "group_code", "group_name", "description") }
    private val templateInsert by lazy { insert("iview_device_template", "template_code", "template_name", "protocol_type", "protocol_config", "collect_interval_ms", "timeout_ms", "description") }
    private val pointInsert by lazy { insert("iview_template_point", "template_id", "point_name", "point_code", "data_type", "address", "unit", "enabled", "value_enum", "point_config", "description") }
    private val deviceInsert by lazy { insert("iview_device", "device_sn", "device_name", "template_id", "group_id", "line_id", "device_vendor", "device_type", "config_json", "enabled", "description") }

    override fun listGroups(): List<DeviceGroup> = jdbc.query(
        "SELECT * FROM iview_device_group ORDER BY group_name, id", emptyMap<String, Any>(), groupMapper
    )

    override fun findGroup(id: Long): DeviceGroup? = single(
        "SELECT * FROM iview_device_group WHERE id=:id", mapOf("id" to id), groupMapper
    )

    override fun groupCodeExists(code: String, excludingId: Long?): Boolean = exists(
        "iview_device_group", "group_code", code, excludingId
    )

    override fun createGroup(command: GroupCommand): DeviceGroup {
        val id = groupInsert.executeAndReturnKey(
            mapOf("group_code" to command.code, "group_name" to command.name, "description" to command.description)
        ).toLong()
        return requireNotNull(findGroup(id))
    }

    override fun updateGroup(id: Long, command: GroupCommand): DeviceGroup? {
        val count = jdbc.update(
            """UPDATE iview_device_group SET group_code=:code, group_name=:name,
               description=:description, updated_at=CURRENT_TIMESTAMP WHERE id=:id""",
            mapOf("id" to id, "code" to command.code, "name" to command.name, "description" to command.description),
        )
        return if (count == 0) null else findGroup(id)
    }

    override fun deleteGroup(id: Long) = jdbc.update(
        "DELETE FROM iview_device_group WHERE id=:id", mapOf("id" to id)
    ) > 0

    override fun groupInUse(id: Long) = count(
        "SELECT COUNT(*) FROM iview_device WHERE group_id=:id", mapOf("id" to id)
    ) > 0

    override fun listTemplates(page: Int, pageSize: Int, keyword: String?): Page<DeviceTemplate> {
        val where = if (keyword.isNullOrBlank()) "" else "WHERE template_name ILIKE :keyword OR template_code ILIKE :keyword"
        val params = mutableMapOf<String, Any>("limit" to pageSize, "offset" to (page - 1) * pageSize)
        if (!keyword.isNullOrBlank()) params["keyword"] = "%$keyword%"
        val total = count("SELECT COUNT(*) FROM iview_device_template $where", params)
        val list = jdbc.query(
            "SELECT * FROM iview_device_template $where ORDER BY id DESC LIMIT :limit OFFSET :offset",
            params,
            templateMapper,
        )
        return Page(list, total, page, pageSize)
    }

    override fun findTemplate(id: Long): DeviceTemplate? = single(
        "SELECT * FROM iview_device_template WHERE id=:id", mapOf("id" to id), templateMapper
    )

    override fun templateCodeExists(code: String, excludingId: Long?) = exists(
        "iview_device_template", "template_code", code, excludingId
    )

    override fun createTemplate(command: TemplateCommand): DeviceTemplate {
        val id = templateInsert.executeAndReturnKey(
            mapOf(
                "template_code" to command.code,
                "template_name" to command.name,
                "protocol_type" to command.protocolType,
                "protocol_config" to json(command.protocolConfig),
                "collect_interval_ms" to command.collectIntervalMs,
                "timeout_ms" to command.timeoutMs,
                "description" to command.description,
            )
        ).toLong()
        return requireNotNull(findTemplate(id))
    }

    override fun updateTemplate(id: Long, command: TemplateCommand): DeviceTemplate? {
        val count = jdbc.update(
            """UPDATE iview_device_template SET template_code=:code, template_name=:name,
               protocol_type=:protocol, protocol_config=:config, collect_interval_ms=:interval,
               timeout_ms=:timeout, description=:description, updated_at=CURRENT_TIMESTAMP WHERE id=:id""",
            mapOf(
                "id" to id, "code" to command.code, "name" to command.name,
                "protocol" to command.protocolType, "config" to json(command.protocolConfig),
                "interval" to command.collectIntervalMs, "timeout" to command.timeoutMs,
                "description" to command.description,
            ),
        )
        return if (count == 0) null else findTemplate(id)
    }

    override fun deleteTemplate(id: Long) = jdbc.update(
        "DELETE FROM iview_device_template WHERE id=:id", mapOf("id" to id)
    ) > 0

    override fun templateInUse(id: Long) = count(
        "SELECT COUNT(*) FROM iview_device WHERE template_id=:id", mapOf("id" to id)
    ) > 0

    override fun listPoints(templateId: Long): List<TemplatePoint> = jdbc.query(
        "SELECT * FROM iview_template_point WHERE template_id=:templateId ORDER BY id",
        mapOf("templateId" to templateId),
        pointMapper,
    )

    override fun findPoint(id: Long): TemplatePoint? = single(
        "SELECT * FROM iview_template_point WHERE id=:id", mapOf("id" to id), pointMapper
    )

    override fun pointCodeExists(templateId: Long, code: String, excludingId: Long?): Boolean {
        val sql = buildString {
            append("SELECT COUNT(*) FROM iview_template_point WHERE template_id=:templateId AND point_code=:code")
            if (excludingId != null) append(" AND id<>:excludingId")
        }
        return count(sql, mapOf("templateId" to templateId, "code" to code, "excludingId" to excludingId)) > 0
    }

    override fun createPoint(templateId: Long, command: TemplatePointCommand): TemplatePoint {
        val id = pointInsert.executeAndReturnKey(
            mapOf(
                "template_id" to templateId, "point_name" to command.name, "point_code" to command.code,
                "data_type" to command.dataType, "address" to command.address, "unit" to command.unit,
                "enabled" to command.enabled, "value_enum" to json(command.valueEnum),
                "point_config" to json(command.pointConfig), "description" to command.description,
            )
        ).toLong()
        return requireNotNull(findPoint(id))
    }

    override fun updatePoint(id: Long, command: TemplatePointCommand): TemplatePoint? {
        val count = jdbc.update(
            """UPDATE iview_template_point SET point_name=:name, point_code=:code, data_type=:dataType,
               address=:address, unit=:unit, enabled=:enabled, value_enum=:valueEnum,
               point_config=:pointConfig, description=:description, updated_at=CURRENT_TIMESTAMP WHERE id=:id""",
            mapOf(
                "id" to id, "name" to command.name, "code" to command.code, "dataType" to command.dataType,
                "address" to command.address, "unit" to command.unit, "enabled" to command.enabled,
                "valueEnum" to json(command.valueEnum), "pointConfig" to json(command.pointConfig),
                "description" to command.description,
            ),
        )
        return if (count == 0) null else findPoint(id)
    }

    override fun deletePoint(id: Long) = jdbc.update(
        "DELETE FROM iview_template_point WHERE id=:id", mapOf("id" to id)
    ) > 0

    override fun listDevices(filter: DeviceFilter): Page<Device> {
        val clauses = mutableListOf<String>()
        val params = mutableMapOf<String, Any>("limit" to filter.pageSize, "offset" to (filter.page - 1) * filter.pageSize)
        filter.keyword?.takeIf(String::isNotBlank)?.let {
            clauses += "(device_sn ILIKE :keyword OR device_name ILIKE :keyword)"
            params["keyword"] = "%$it%"
        }
        filter.templateId?.let { clauses += "template_id=:templateId"; params["templateId"] = it }
        filter.enabled?.let { clauses += "enabled=:enabled"; params["enabled"] = it }
        val where = if (clauses.isEmpty()) "" else "WHERE ${clauses.joinToString(" AND ")}"
        val total = count("SELECT COUNT(*) FROM iview_device $where", params)
        val list = jdbc.query(
            "SELECT * FROM iview_device $where ORDER BY id DESC LIMIT :limit OFFSET :offset", params, deviceMapper
        )
        return Page(list, total, filter.page, filter.pageSize)
    }

    override fun findDevice(id: Long): Device? = single(
        "SELECT * FROM iview_device WHERE id=:id", mapOf("id" to id), deviceMapper
    )

    override fun deviceSnExists(sn: String, excludingId: Long?) = exists("iview_device", "device_sn", sn, excludingId)

    override fun createDevice(command: DeviceCommand): Device {
        val id = deviceInsert.executeAndReturnKey(
            mapOf(
                "device_sn" to command.sn, "device_name" to command.name, "template_id" to command.templateId,
                "group_id" to command.groupId, "line_id" to command.lineId, "device_vendor" to command.vendor,
                "device_type" to command.deviceType, "config_json" to json(command.config),
                "enabled" to command.enabled, "description" to command.description,
            )
        ).toLong()
        return requireNotNull(findDevice(id))
    }

    override fun updateDevice(id: Long, command: DeviceCommand): Device? {
        val count = jdbc.update(
            """UPDATE iview_device SET device_sn=:sn, device_name=:name, template_id=:templateId,
               group_id=:groupId, line_id=:lineId, device_vendor=:vendor, device_type=:deviceType,
               config_json=:config, enabled=:enabled, description=:description,
               updated_at=CURRENT_TIMESTAMP WHERE id=:id""",
            MapSqlParameterSource()
                .addValue("id", id).addValue("sn", command.sn).addValue("name", command.name)
                .addValue("templateId", command.templateId).addValue("groupId", command.groupId)
                .addValue("lineId", command.lineId).addValue("vendor", command.vendor)
                .addValue("deviceType", command.deviceType).addValue("config", json(command.config))
                .addValue("enabled", command.enabled).addValue("description", command.description),
        )
        return if (count == 0) null else findDevice(id)
    }

    override fun deleteDevice(id: Long) = jdbc.update("DELETE FROM iview_device WHERE id=:id", mapOf("id" to id)) > 0

    private fun insert(table: String, vararg columns: String) = SimpleJdbcInsert(jdbc.jdbcTemplate)
        .withTableName(table)
        .usingGeneratedKeyColumns("id")
        .usingColumns(*columns)

    private fun exists(table: String, column: String, value: String, excludingId: Long?): Boolean {
        val sql = buildString {
            append("SELECT COUNT(*) FROM $table WHERE $column=:value")
            if (excludingId != null) append(" AND id<>:excludingId")
        }
        return count(sql, mapOf("value" to value, "excludingId" to excludingId)) > 0
    }

    private fun count(sql: String, params: Map<String, Any?>): Long =
        jdbc.queryForObject(sql, params, Long::class.java) ?: 0

    private fun <T> single(sql: String, params: Map<String, Any?>, mapper: RowMapper<T>): T? =
        jdbc.query(sql, params, mapper).firstOrNull()

    private fun json(value: Any): String = objectMapper.writeValueAsString(value)
    private fun mapJson(value: String?): Map<String, Any?> = if (value.isNullOrBlank()) emptyMap()
        else objectMapper.readValue(value, object : TypeReference<Map<String, Any?>>() {})
    private fun listJson(value: String?): List<Map<String, Any?>> = if (value.isNullOrBlank()) emptyList()
        else objectMapper.readValue(value, object : TypeReference<List<Map<String, Any?>>>() {})
    private fun instant(rs: ResultSet, column: String): Instant = rs.getTimestamp(column).toInstant()

    private val groupMapper = RowMapper { rs, _ ->
        DeviceGroup(rs.getLong("id"), rs.getString("group_code"), rs.getString("group_name"), rs.getString("description"), instant(rs, "created_at"), instant(rs, "updated_at"))
    }
    private val templateMapper = RowMapper { rs, _ ->
        DeviceTemplate(
            rs.getLong("id"), rs.getString("template_code"), rs.getString("template_name"), rs.getString("protocol_type"),
            mapJson(rs.getString("protocol_config")), rs.getInt("collect_interval_ms"), rs.getInt("timeout_ms"),
            rs.getString("description"), instant(rs, "created_at"), instant(rs, "updated_at"),
        )
    }
    private val pointMapper = RowMapper { rs, _ ->
        TemplatePoint(
            rs.getLong("id"), rs.getLong("template_id"), rs.getString("point_name"), rs.getString("point_code"),
            rs.getString("data_type"), rs.getString("address"), rs.getString("unit"), rs.getBoolean("enabled"),
            listJson(rs.getString("value_enum")), mapJson(rs.getString("point_config")), rs.getString("description"),
            instant(rs, "created_at"), instant(rs, "updated_at"),
        )
    }
    private val deviceMapper = RowMapper { rs, _ ->
        Device(
            rs.getLong("id"), rs.getString("device_sn"), rs.getString("device_name"), rs.getLong("template_id"),
            rs.getLong("group_id").let { if (rs.wasNull()) null else it }, rs.getString("line_id"),
            rs.getString("device_vendor"), rs.getString("device_type"), mapJson(rs.getString("config_json")),
            rs.getBoolean("enabled"), rs.getString("description"), instant(rs, "created_at"), instant(rs, "updated_at"),
        )
    }
}

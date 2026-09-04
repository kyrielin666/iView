package ai.moying.iview.query.jdbc

import ai.moying.iview.query.DataModel
import ai.moying.iview.query.DataModelDraft
import ai.moying.iview.query.DataModelField
import ai.moying.iview.query.DataModelRepository
import ai.moying.iview.query.DataModelSchema
import ai.moying.iview.query.DataModelStatus
import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.jdbc.core.RowMapper
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.jdbc.core.simple.SimpleJdbcInsert
import org.springframework.stereotype.Repository
import java.sql.ResultSet
import java.time.Instant

@Repository
class JdbcDataModelRepository(private val jdbc: NamedParameterJdbcTemplate, private val objectMapper: ObjectMapper) : DataModelRepository {
    private val modelInsert by lazy { SimpleJdbcInsert(jdbc.jdbcTemplate).withTableName("iview_data_model").usingGeneratedKeyColumns("id").usingColumns("dataset_id", "model_name", "description") }
    private val schemaInsert by lazy { SimpleJdbcInsert(jdbc.jdbcTemplate).withTableName("iview_data_model_schema").usingGeneratedKeyColumns("id").usingColumns("model_id", "schema_version", "fields_json", "fingerprint") }
    override fun list(): List<DataModel> = jdbc.query("SELECT * FROM iview_data_model ORDER BY id DESC", emptyMap<String, Any>(), modelMapper)
    override fun find(id: Long): DataModel? = jdbc.query("SELECT * FROM iview_data_model WHERE id=:id", mapOf("id" to id), modelMapper).firstOrNull()
    override fun create(draft: DataModelDraft): DataModel { val id = modelInsert.executeAndReturnKey(mapOf("dataset_id" to draft.datasetId, "model_name" to draft.name, "description" to draft.description)).toLong(); return requireNotNull(find(id)) }
    override fun update(id: Long, draft: DataModelDraft): DataModel? {
        val count = jdbc.update("UPDATE iview_data_model SET dataset_id=:datasetId, model_name=:name, description=:description, updated_at=CURRENT_TIMESTAMP WHERE id=:id", mapOf("id" to id, "datasetId" to draft.datasetId, "name" to draft.name, "description" to draft.description))
        return if (count == 0) null else find(id)
    }
    override fun delete(id: Long) = jdbc.update("DELETE FROM iview_data_model WHERE id=:id", mapOf("id" to id)) > 0
    override fun latestSchema(modelId: Long): DataModelSchema? = jdbc.query("SELECT * FROM iview_data_model_schema WHERE model_id=:id ORDER BY schema_version DESC", mapOf("id" to modelId), schemaMapper).firstOrNull()
    override fun listSchemas(modelId: Long): List<DataModelSchema> = jdbc.query("SELECT * FROM iview_data_model_schema WHERE model_id=:id ORDER BY schema_version DESC", mapOf("id" to modelId), schemaMapper)
    override fun appendSchema(modelId: Long, fields: List<DataModelField>, fingerprint: String): DataModelSchema {
        val version = (latestSchema(modelId)?.version ?: 0) + 1
        val id = schemaInsert.executeAndReturnKey(mapOf("model_id" to modelId, "schema_version" to version, "fields_json" to objectMapper.writeValueAsString(fields), "fingerprint" to fingerprint)).toLong()
        jdbc.update("UPDATE iview_data_model SET latest_schema_version=:version, updated_at=CURRENT_TIMESTAMP WHERE id=:id", mapOf("id" to modelId, "version" to version))
        return jdbc.query("SELECT * FROM iview_data_model_schema WHERE id=:id", mapOf("id" to id), schemaMapper).first()
    }
    override fun markDrifted(id: Long, latestSchemaVersion: Int): DataModel? { jdbc.update("UPDATE iview_data_model SET model_status='DRIFTED', latest_schema_version=:version, updated_at=CURRENT_TIMESTAMP WHERE id=:id", mapOf("id" to id, "version" to latestSchemaVersion)); return find(id) }
    override fun publish(id: Long, schemaVersion: Int): DataModel? { jdbc.update("UPDATE iview_data_model SET model_status='PUBLISHED', published_schema_version=:version, updated_at=CURRENT_TIMESTAMP WHERE id=:id", mapOf("id" to id, "version" to schemaVersion)); return find(id) }
    private val modelMapper = RowMapper { rs, _ -> DataModel(rs.getLong("id"), rs.getLong("dataset_id"), rs.getString("model_name"), rs.getString("description"), DataModelStatus.valueOf(rs.getString("model_status")), rs.getInt("latest_schema_version"), rs.nullableInt("published_schema_version"), instant(rs, "created_at"), instant(rs, "updated_at")) }
    private val schemaMapper = RowMapper { rs, _ -> DataModelSchema(rs.getLong("id"), rs.getLong("model_id"), rs.getInt("schema_version"), fields(rs.getString("fields_json")), rs.getString("fingerprint"), instant(rs, "created_at")) }
    private fun fields(value: String) = objectMapper.readValue(value, object : TypeReference<List<DataModelField>>() {})
    private fun ResultSet.nullableInt(column: String) = getInt(column).let { if (wasNull()) null else it }
    private fun instant(rs: ResultSet, column: String): Instant = rs.getTimestamp(column).toInstant()
}

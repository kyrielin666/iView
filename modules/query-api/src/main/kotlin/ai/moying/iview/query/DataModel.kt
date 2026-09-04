package ai.moying.iview.query

import java.time.Instant

enum class DataModelStatus { DRAFT, PUBLISHED, DRIFTED }

data class DataModel(
    val id: Long,
    val datasetId: Long,
    val name: String,
    val description: String,
    val status: DataModelStatus,
    val latestSchemaVersion: Int,
    val publishedSchemaVersion: Int?,
    val createdAt: Instant,
    val updatedAt: Instant,
)

data class DataModelDraft(val datasetId: Long, val name: String, val description: String = "")
data class DataModelField(val name: String, val type: String, val nullable: Boolean)
data class DataModelSchema(
    val id: Long,
    val modelId: Long,
    val version: Int,
    val fields: List<DataModelField>,
    val fingerprint: String,
    val createdAt: Instant,
)
data class DataModelSyncResult(val model: DataModel, val schema: DataModelSchema, val changed: Boolean)

interface DataModelRepository {
    fun list(): List<DataModel>
    fun find(id: Long): DataModel?
    fun create(draft: DataModelDraft): DataModel
    fun update(id: Long, draft: DataModelDraft): DataModel?
    fun delete(id: Long): Boolean
    fun latestSchema(modelId: Long): DataModelSchema?
    fun listSchemas(modelId: Long): List<DataModelSchema>
    fun appendSchema(modelId: Long, fields: List<DataModelField>, fingerprint: String): DataModelSchema
    fun markDrifted(id: Long, latestSchemaVersion: Int): DataModel?
    fun publish(id: Long, schemaVersion: Int): DataModel?
}

class DataModelNotFoundException(message: String) : DataSourceException(message)
class DataModelValidationException(message: String) : DataSourceException(message)

class DataModelService(private val repository: DataModelRepository, private val datasets: DatasetService) {
    fun list() = repository.list()
    fun get(id: Long) = repository.find(id) ?: throw DataModelNotFoundException("数据模型不存在: $id")
    fun schemas(id: Long): List<DataModelSchema> { get(id); return repository.listSchemas(id) }
    fun create(draft: DataModelDraft): DataModel {
        val normalized = normalize(draft); datasets.get(normalized.datasetId); return repository.create(normalized)
    }
    fun update(id: Long, draft: DataModelDraft): DataModel {
        get(id); val normalized = normalize(draft); datasets.get(normalized.datasetId)
        return repository.update(id, normalized) ?: throw DataModelNotFoundException("数据模型不存在: $id")
    }
    fun delete(id: Long) { if (!repository.delete(id)) throw DataModelNotFoundException("数据模型不存在: $id") }

    fun sync(id: Long, fields: List<DataModelField>): DataModelSyncResult {
        val model = get(id); val normalized = normalizeFields(fields); val fingerprint = fingerprint(normalized)
        val current = repository.latestSchema(id)
        if (current?.fingerprint == fingerprint) return DataModelSyncResult(model, current, false)
        val schema = repository.appendSchema(id, normalized, fingerprint)
        val updated = if (model.status == DataModelStatus.PUBLISHED) repository.markDrifted(id, schema.version) else repository.find(id)
        return DataModelSyncResult(requireNotNull(updated), schema, true)
    }

    fun publish(id: Long): DataModel {
        val schema = repository.latestSchema(id) ?: throw DataModelValidationException("数据模型至少需要一次字段同步才能发布")
        return repository.publish(id, schema.version) ?: throw DataModelNotFoundException("数据模型不存在: $id")
    }

    private fun normalize(draft: DataModelDraft) = draft.copy(name = draft.name.trim(), description = draft.description.trim()).also {
        if (it.datasetId <= 0) throw DataModelValidationException("数据模型必须选择一个数据集")
        if (it.name.isBlank() || it.name.length > 100) throw DataModelValidationException("数据模型名称不能为空且不能超过100位")
    }
    private fun normalizeFields(fields: List<DataModelField>): List<DataModelField> {
        if (fields.isEmpty()) throw DataModelValidationException("数据集查询未返回字段，不能同步模型")
        return fields.map { it.copy(name = it.name.trim(), type = it.type.trim().uppercase()) }.also { normalized ->
            if (normalized.any { it.name.isBlank() || it.type.isBlank() }) throw DataModelValidationException("字段名称和类型不能为空")
            if (normalized.map { it.name.lowercase() }.toSet().size != normalized.size) throw DataModelValidationException("字段名称不能重复")
        }
    }
    private fun fingerprint(fields: List<DataModelField>) = fields.joinToString("|") { "${it.name.lowercase()}:${it.type}:${it.nullable}" }
}

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
enum class DataModelFieldChangeKind { ADDED, REMOVED, TYPE_CHANGED, NULLABILITY_CHANGED }
data class DataModelFieldChange(
    val field: String,
    val kind: DataModelFieldChangeKind,
    val previousType: String? = null,
    val currentType: String? = null,
    val previousNullable: Boolean? = null,
    val currentNullable: Boolean? = null,
    val breaking: Boolean = false,
)
data class DataModelPublishPlan(
    val model: DataModel,
    val latestSchema: DataModelSchema,
    val publishedSchema: DataModelSchema?,
    val changes: List<DataModelFieldChange>,
    val breakingChanges: Int,
    val requiresPublish: Boolean,
)

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

    fun publishPlan(id: Long): DataModelPublishPlan {
        val model = get(id)
        val schema = repository.latestSchema(id) ?: throw DataModelValidationException("数据模型至少需要一次字段同步才能发布")
        val published = model.publishedSchemaVersion?.let { version -> repository.listSchemas(id).firstOrNull { it.version == version } }
        val changes = schemaChanges(published?.fields.orEmpty(), schema.fields, published == null)
        return DataModelPublishPlan(model, schema, published, changes, changes.count { it.breaking }, model.publishedSchemaVersion != schema.version)
    }

    fun publish(id: Long, expectedSchemaVersion: Int? = null): DataModel {
        val plan = publishPlan(id)
        if (expectedSchemaVersion != null && plan.latestSchema.version != expectedSchemaVersion) {
            throw DataModelValidationException("字段版本已从 v$expectedSchemaVersion 更新为 v${plan.latestSchema.version}，请重新确认发布计划")
        }
        val schema = plan.latestSchema
        return repository.publish(id, schema.version) ?: throw DataModelNotFoundException("数据模型不存在: $id")
    }

    private fun schemaChanges(previous: List<DataModelField>, current: List<DataModelField>, firstPublish: Boolean): List<DataModelFieldChange> {
        val before = previous.associateBy { it.name.lowercase() }
        val after = current.associateBy { it.name.lowercase() }
        return buildList {
            current.forEach { field ->
                val old = before[field.name.lowercase()]
                if (old == null) add(DataModelFieldChange(field.name, DataModelFieldChangeKind.ADDED, currentType = field.type, currentNullable = field.nullable, breaking = false))
                else {
                    if (old.type != field.type) add(DataModelFieldChange(field.name, DataModelFieldChangeKind.TYPE_CHANGED, old.type, field.type, old.nullable, field.nullable, true))
                    if (old.nullable != field.nullable) add(DataModelFieldChange(field.name, DataModelFieldChangeKind.NULLABILITY_CHANGED, old.type, field.type, old.nullable, field.nullable, old.nullable && !field.nullable))
                }
            }
            if (!firstPublish) previous.filter { it.name.lowercase() !in after }.forEach { field ->
                add(DataModelFieldChange(field.name, DataModelFieldChangeKind.REMOVED, previousType = field.type, previousNullable = field.nullable, breaking = true))
            }
        }
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

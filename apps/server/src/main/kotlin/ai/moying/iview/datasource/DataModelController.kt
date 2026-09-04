package ai.moying.iview.datasource

import ai.moying.iview.common.ApiResponse
import ai.moying.iview.query.DataModel
import ai.moying.iview.query.DataModelDraft
import ai.moying.iview.query.DataModelField
import ai.moying.iview.query.DataModelSchema
import ai.moying.iview.query.DataModelService
import ai.moying.iview.query.DataModelSyncResult
import ai.moying.iview.query.DataSourceService
import ai.moying.iview.query.DatasetService
import ai.moying.iview.query.DatasetSql
import ai.moying.iview.query.SafeSql
import ai.moying.iview.query.DataModelStatus
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.sql.DriverManager

class DataModelRequest { var datasetId: Long? = null; var name: String = ""; var description: String? = null }
class DataModelPreviewRequest { var variables: Map<String, String> = emptyMap() }

@RestController
@RequestMapping("/api/v1/data-models")
class DataModelController(
    private val models: DataModelService,
    private val datasets: DatasetService,
    private val sources: DataSourceService,
) {
    @GetMapping fun list() = ApiResponse.success(models.list().map(::view))
    @GetMapping("/{id}") fun get(@PathVariable id: Long) = ApiResponse.success(view(models.get(id)))
    @PostMapping @ResponseStatus(HttpStatus.CREATED) fun create(@RequestBody request: DataModelRequest) = ApiResponse.success(view(models.create(request.draft())))
    @PutMapping("/{id}") fun update(@PathVariable id: Long, @RequestBody request: DataModelRequest) = ApiResponse.success(view(models.update(id, request.draft())))
    @DeleteMapping("/{id}") fun delete(@PathVariable id: Long): ApiResponse<Nothing> { models.delete(id); return ApiResponse.success() }
    @GetMapping("/{id}/schemas") fun schemas(@PathVariable id: Long) = ApiResponse.success(models.schemas(id).map(::schemaView))
    @PostMapping("/{id}/sync") fun sync(@PathVariable id: Long) = ApiResponse.success(syncView(models.sync(id, readFields(id))))
    @PostMapping("/{id}/publish") fun publish(@PathVariable id: Long) = ApiResponse.success(view(models.publish(id)))
    @PostMapping("/{id}/preview") fun preview(@PathVariable id: Long, @RequestBody request: DataModelPreviewRequest): ApiResponse<SqlResult> {
        val model = models.get(id)
        if (model.status != DataModelStatus.PUBLISHED) throw IllegalArgumentException("只有已发布数据模型可以提供看板预览数据")
        val dataset = datasets.get(model.datasetId); val credential = sources.credential(dataset.sourceId)
        val bound = DatasetSql.bindVariables(SafeSql.limit(dataset.sql, 100), request.variables)
        return ApiResponse.success(DriverManager.getConnection(credential.jdbcUrl, credential.username, credential.password).use { execute(it, bound.sql, bound.values) })
    }

    private fun readFields(id: Long): List<DataModelField> {
        val model = models.get(id); val dataset = datasets.get(model.datasetId); val credential = sources.credential(dataset.sourceId)
        return DriverManager.getConnection(credential.jdbcUrl, credential.username, credential.password).use { connection ->
            connection.prepareStatement(DatasetSql.schemaQuery(dataset.sql)).use { statement -> statement.executeQuery().use { rs ->
                val metadata = rs.metaData; (1..metadata.columnCount).map { DataModelField(metadata.getColumnLabel(it), metadata.getColumnTypeName(it), metadata.isNullable(it) != 0) }
            } }
        }
    }
    private fun DataModelRequest.draft() = DataModelDraft(datasetId ?: 0, name, description ?: "")
    private fun view(model: DataModel) = mapOf("id" to model.id, "dataset_id" to model.datasetId, "name" to model.name, "description" to model.description, "status" to model.status.name.lowercase(), "latest_schema_version" to model.latestSchemaVersion, "published_schema_version" to model.publishedSchemaVersion, "created_at" to model.createdAt, "updated_at" to model.updatedAt)
    private fun schemaView(schema: DataModelSchema) = mapOf("id" to schema.id, "version" to schema.version, "fields" to schema.fields, "fingerprint" to schema.fingerprint, "created_at" to schema.createdAt)
    private fun syncView(result: DataModelSyncResult) = mapOf("model" to view(result.model), "schema" to schemaView(result.schema), "changed" to result.changed)
}

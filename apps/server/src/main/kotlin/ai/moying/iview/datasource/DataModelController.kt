package ai.moying.iview.datasource

import ai.moying.iview.common.ApiResponse
import ai.moying.iview.query.DataModel
import ai.moying.iview.query.DataModelDraft
import ai.moying.iview.query.DataModelField
import ai.moying.iview.query.DataModelSchema
import ai.moying.iview.query.DataModelService
import ai.moying.iview.query.DataModelSyncResult
import ai.moying.iview.query.DataModelPublishPlan
import ai.moying.iview.query.DatasetService
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

class DataModelRequest { var datasetId: Long? = null; var name: String = ""; var description: String? = null }
class DataModelPreviewRequest { var variables: Map<String, String> = emptyMap() }
class DataModelSyncRequest { var variables: Map<String, String> = emptyMap() }
class DataModelPublishRequest { var expectedSchemaVersion: Int? = null }

@RestController
@RequestMapping("/api/v1/data-models")
class DataModelController(
    private val models: DataModelService,
    private val datasets: DatasetService,
    private val execution: DataSourceExecutionService,
) {
    @GetMapping fun list() = ApiResponse.success(models.list().map(::view))
    @GetMapping("/{id}") fun get(@PathVariable id: Long) = ApiResponse.success(view(models.get(id)))
    @PostMapping @ResponseStatus(HttpStatus.CREATED) fun create(@RequestBody request: DataModelRequest) = ApiResponse.success(view(models.create(request.draft())))
    @PutMapping("/{id}") fun update(@PathVariable id: Long, @RequestBody request: DataModelRequest) = ApiResponse.success(view(models.update(id, request.draft())))
    @DeleteMapping("/{id}") fun delete(@PathVariable id: Long): ApiResponse<Nothing> { models.delete(id); return ApiResponse.success() }
    @GetMapping("/{id}/schemas") fun schemas(@PathVariable id: Long) = ApiResponse.success(models.schemas(id).map(::schemaView))
    @PostMapping("/{id}/sync") fun sync(@PathVariable id: Long, @RequestBody(required = false) request: DataModelSyncRequest?) = ApiResponse.success(syncView(models.sync(id, readFields(id, request?.variables ?: emptyMap()))))
    @GetMapping("/{id}/publish-plan") fun publishPlan(@PathVariable id: Long) = ApiResponse.success(publishPlanView(models.publishPlan(id)))
    @PostMapping("/{id}/publish") fun publish(@PathVariable id: Long, @RequestBody(required = false) request: DataModelPublishRequest?) = ApiResponse.success(view(models.publish(id, request?.expectedSchemaVersion)))
    @PostMapping("/{id}/preview") fun preview(@PathVariable id: Long, @RequestBody request: DataModelPreviewRequest): ApiResponse<SqlResult> {
        val model = models.get(id)
        if (model.status != DataModelStatus.PUBLISHED) throw IllegalArgumentException("只有已发布数据模型可以提供看板预览数据")
        val dataset = datasets.get(model.datasetId)
        return ApiResponse.success(execution.queryDataset(dataset, 100, request.variables))
    }

    private fun readFields(id: Long, variables: Map<String, String>): List<DataModelField> {
        val model = models.get(id); val dataset = datasets.get(model.datasetId)
        return execution.schema(dataset, variables).map { DataModelField(it.name, it.type, it.nullable) }
    }
    private fun DataModelRequest.draft() = DataModelDraft(datasetId ?: 0, name, description ?: "")
    private fun view(model: DataModel) = mapOf("id" to model.id, "dataset_id" to model.datasetId, "name" to model.name, "description" to model.description, "status" to model.status.name.lowercase(), "latest_schema_version" to model.latestSchemaVersion, "published_schema_version" to model.publishedSchemaVersion, "created_at" to model.createdAt, "updated_at" to model.updatedAt)
    private fun schemaView(schema: DataModelSchema) = mapOf("id" to schema.id, "version" to schema.version, "fields" to schema.fields, "fingerprint" to schema.fingerprint, "created_at" to schema.createdAt)
    private fun syncView(result: DataModelSyncResult) = mapOf("model" to view(result.model), "schema" to schemaView(result.schema), "changed" to result.changed)
    private fun publishPlanView(plan: DataModelPublishPlan) = mapOf(
        "model" to view(plan.model), "latest_schema" to schemaView(plan.latestSchema), "published_schema" to plan.publishedSchema?.let(::schemaView),
        "changes" to plan.changes.map { change -> mapOf(
            "field" to change.field, "kind" to change.kind.name.lowercase(), "previous_type" to change.previousType, "current_type" to change.currentType,
            "previous_nullable" to change.previousNullable, "current_nullable" to change.currentNullable, "breaking" to change.breaking,
        ) },
        "breaking_changes" to plan.breakingChanges, "requires_publish" to plan.requiresPublish,
    )
}

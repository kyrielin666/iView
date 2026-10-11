package ai.moying.iview.datasource

import ai.moying.iview.common.ApiResponse
import ai.moying.iview.query.DataModel
import ai.moying.iview.query.DataModelDraft
import ai.moying.iview.query.DataModelField
import ai.moying.iview.query.DataModelSchema
import ai.moying.iview.query.DataModelService
import ai.moying.iview.query.DataModelSyncResult
import ai.moying.iview.query.DataModelPublishPlan
import ai.moying.iview.query.DataModelRollbackPlan
import ai.moying.iview.query.DatasetService
import ai.moying.iview.query.DataModelStatus
import ai.moying.iview.query.DataModelValidationException
import ai.moying.iview.query.DataSourceService
import ai.moying.iview.query.DataSourceType
import ai.moying.iview.query.DashboardDocument
import ai.moying.iview.query.DashboardService
import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController

class DataModelRequest { var datasetId: Long? = null; var name: String = ""; var description: String? = null }
class DataModelPreviewRequest { var variables: Map<String, String> = emptyMap() }
class DataModelHeatmapRequest {
    var xField: String = ""
    var yField: String = ""
    var valueField: String = ""
    var variables: Map<String, String> = emptyMap()
}
class DataModelSyncRequest { var variables: Map<String, String> = emptyMap() }
class DataModelPublishRequest { var expectedSchemaVersion: Int? = null; var confirmBreaking = false }
class DataModelRollbackRequest { var targetSchemaVersion: Int? = null; var expectedPublishedSchemaVersion: Int? = null; var confirmImpact = false }
data class DataModelDashboardImpact(
    val dashboardId: Long,
    val dashboardName: String,
    val dashboardStatus: String,
    val componentIds: List<String>,
    val affectedFields: List<String>,
    val sources: List<String>,
)

@RestController
@RequestMapping("/api/v1/data-models")
class DataModelController(
    private val models: DataModelService,
    private val datasets: DatasetService,
    private val execution: DataSourceExecutionService,
    private val sources: DataSourceService,
    private val dashboards: DashboardService,
    private val objectMapper: ObjectMapper,
) {
    @GetMapping fun list() = ApiResponse.success(models.list().map(::view))
    @GetMapping("/{id}") fun get(@PathVariable id: Long) = ApiResponse.success(view(models.get(id)))
    @PostMapping @ResponseStatus(HttpStatus.CREATED) fun create(@RequestBody request: DataModelRequest) = ApiResponse.success(view(models.create(request.draft())))
    @PutMapping("/{id}") fun update(@PathVariable id: Long, @RequestBody request: DataModelRequest) = ApiResponse.success(view(models.update(id, request.draft())))
    @DeleteMapping("/{id}") fun delete(@PathVariable id: Long): ApiResponse<Nothing> {
        models.get(id)
        val impacts = dependencies(id, emptySet())
        if (impacts.isNotEmpty()) throw DataModelValidationException("数据模型仍被 ${impacts.size} 个看板草稿或发布快照使用，不能删除")
        models.delete(id); return ApiResponse.success()
    }
    @GetMapping("/{id}/schemas") fun schemas(@PathVariable id: Long) = ApiResponse.success(models.schemas(id).map(::schemaView))
    @PostMapping("/{id}/sync") fun sync(@PathVariable id: Long, @RequestBody(required = false) request: DataModelSyncRequest?) = ApiResponse.success(syncView(models.sync(id, readFields(id, request?.variables ?: emptyMap()))))
    @GetMapping("/{id}/publish-plan") fun publishPlan(@PathVariable id: Long): ApiResponse<Map<String, Any?>> {
        val plan = models.publishPlan(id)
        return ApiResponse.success(publishPlanView(plan, dependencies(id, plan.changes.filter { it.breaking }.map { it.field }.toSet())))
    }
    @PostMapping("/{id}/publish") fun publish(@PathVariable id: Long, @RequestBody(required = false) request: DataModelPublishRequest?): ApiResponse<Map<String, Any?>> {
        val plan = models.publishPlan(id)
        if (plan.breakingChanges > 0 && request?.confirmBreaking != true) throw ai.moying.iview.query.DataModelValidationException("发布计划包含 ${plan.breakingChanges} 项破坏性字段变更，必须明确确认后才能发布")
        if (plan.breakingChanges > 0) dependencies(id, plan.changes.filter { it.breaking }.map { it.field }.toSet())
        return ApiResponse.success(view(models.publish(id, request?.expectedSchemaVersion)))
    }
    @PostMapping("/{id}/rollback") fun rollback(@PathVariable id: Long, @RequestBody request: DataModelRollbackRequest): ApiResponse<Map<String, Any?>> {
        val target = request.targetSchemaVersion ?: throw ai.moying.iview.query.DataModelValidationException("必须指定回滚目标字段版本")
        val plan = models.rollbackPlan(id, target)
        val impacts = dependencies(id, plan.changes.filter { it.breaking }.map { it.field }.toSet())
        if (impacts.isNotEmpty() && !request.confirmImpact) throw ai.moying.iview.query.DataModelValidationException("回滚会影响 ${impacts.size} 个依赖看板，必须查看影响并明确确认")
        return ApiResponse.success(view(models.rollback(id, target, request.expectedPublishedSchemaVersion)))
    }
    @GetMapping("/{id}/rollback-plan") fun rollbackPlan(@PathVariable id: Long, @RequestParam(name = "target_schema_version") targetSchemaVersion: Int): ApiResponse<Map<String, Any?>> {
        val plan = models.rollbackPlan(id, targetSchemaVersion)
        return ApiResponse.success(rollbackPlanView(plan, dependencies(id, plan.changes.filter { it.breaking }.map { it.field }.toSet())))
    }
    @PostMapping("/{id}/preview") fun preview(@PathVariable id: Long, @RequestBody request: DataModelPreviewRequest): ApiResponse<SqlResult> {
        val model = models.get(id)
        if (model.status != DataModelStatus.PUBLISHED) throw IllegalArgumentException("只有已发布数据模型可以提供看板预览数据")
        val dataset = datasets.get(model.datasetId)
        val result = execution.queryDataset(dataset, 100, request.variables)
        val schema = models.schema(id, requireNotNull(model.publishedSchemaVersion))
        val indexes = result.columns.mapIndexed { index, column -> column.name to index }.toMap()
        val missing = schema.fields.map { it.name }.filterNot(indexes::containsKey)
        if (missing.isNotEmpty()) throw ai.moying.iview.query.DataModelValidationException("当前数据集缺少已发布模型字段: ${missing.joinToString()}")
        val selected = schema.fields.map { indexes.getValue(it.name) }
        return ApiResponse.success(SqlResult(schema.fields.map { SchemaColumn(it.name, it.type, it.nullable) }, result.rows.map { row -> selected.map(row::getOrNull) }))
    }

    @PostMapping("/{id}/heatmap") fun heatmap(@PathVariable id: Long, @RequestBody request: DataModelHeatmapRequest): ApiResponse<HeatmapAggregateResult> {
        val model = models.get(id)
        if (model.status != DataModelStatus.PUBLISHED) throw DataModelValidationException("只有已发布数据模型可以提供热力图数据")
        val schema = models.schema(id, requireNotNull(model.publishedSchemaVersion))
        val dataset = datasets.get(model.datasetId)
        val isPostgres = sources.credential(dataset.sourceId).type == DataSourceType.POSTGRESQL
        HeatmapAggregation.validate(schema.fields, request.xField, request.yField, request.valueField, allowTextualMeasure = !isPostgres)
        val result = if (isPostgres) {
            val structured = execution.queryStructuredDataset(dataset,
                HeatmapAggregation.postgresRequest(request.xField, request.yField, request.valueField, request.variables),
                StructuredDatasetQueryService())
            HeatmapAggregation.fromStructured(structured, request.valueField)
        } else {
            HeatmapAggregation.fromPortable(
                execution.queryDataset(dataset, StructuredDatasetQueryService.SOURCE_ROW_LIMIT, request.variables),
                request.xField, request.yField, request.valueField)
        }
        return ApiResponse.success(result)
    }

    private fun readFields(id: Long, variables: Map<String, String>): List<DataModelField> {
        val model = models.get(id); val dataset = datasets.get(model.datasetId)
        return execution.schema(dataset, variables).map { DataModelField(it.name, it.type, it.nullable) }
    }
    private fun DataModelRequest.draft() = DataModelDraft(datasetId ?: 0, name, description ?: "")
    private fun view(model: DataModel) = mapOf("id" to model.id, "dataset_id" to model.datasetId, "name" to model.name, "description" to model.description, "status" to model.status.name.lowercase(), "latest_schema_version" to model.latestSchemaVersion, "published_schema_version" to model.publishedSchemaVersion, "created_at" to model.createdAt, "updated_at" to model.updatedAt)
    private fun schemaView(schema: DataModelSchema) = mapOf("id" to schema.id, "version" to schema.version, "fields" to schema.fields, "fingerprint" to schema.fingerprint, "created_at" to schema.createdAt)
    private fun syncView(result: DataModelSyncResult) = mapOf("model" to view(result.model), "schema" to schemaView(result.schema), "changed" to result.changed)
    private fun publishPlanView(plan: DataModelPublishPlan, dependencies: List<DataModelDashboardImpact>) = mapOf(
        "model" to view(plan.model), "latest_schema" to schemaView(plan.latestSchema), "published_schema" to plan.publishedSchema?.let(::schemaView),
        "changes" to plan.changes.map { change -> mapOf(
            "field" to change.field, "kind" to change.kind.name.lowercase(), "previous_type" to change.previousType, "current_type" to change.currentType,
            "previous_nullable" to change.previousNullable, "current_nullable" to change.currentNullable, "breaking" to change.breaking,
        ) },
        "breaking_changes" to plan.breakingChanges, "requires_publish" to plan.requiresPublish,
        "dependencies" to dependencies, "dependency_count" to dependencies.size,
    )
    private fun rollbackPlanView(plan: DataModelRollbackPlan, dependencies: List<DataModelDashboardImpact>) = mapOf(
        "model" to view(plan.model), "published_schema" to schemaView(plan.publishedSchema), "target_schema" to schemaView(plan.targetSchema),
        "changes" to plan.changes.map(::changeView), "breaking_changes" to plan.breakingChanges,
        "dependencies" to dependencies, "dependency_count" to dependencies.size,
    )
    private fun changeView(change: ai.moying.iview.query.DataModelFieldChange) = mapOf(
        "field" to change.field, "kind" to change.kind.name.lowercase(), "previous_type" to change.previousType, "current_type" to change.currentType,
        "previous_nullable" to change.previousNullable, "current_nullable" to change.currentNullable, "breaking" to change.breaking,
    )

    private fun dependencies(modelId: Long, changedFields: Set<String>): List<DataModelDashboardImpact> = dashboards.list().mapNotNull { dashboard ->
        fun read(source: String, content: String) = try {
            objectMapper.readValue(content, DashboardDocument::class.java)
        } catch (_: Exception) {
            throw DataModelValidationException("看板 ${dashboard.id} 的${if (source == "published") "发布快照" else "草稿"}文档无法解析，不能安全计算模型影响范围")
        }
        val versions = mutableListOf(DashboardDependencyVersion("draft", dashboard.modelId, read("draft", dashboard.contentJson)))
        if (dashboard.publishedSnapshotId != null) {
            val snapshot = dashboards.published(dashboard.id)
            versions += DashboardDependencyVersion("published", snapshot.modelId, read("published", snapshot.contentJson))
        }
        DashboardModelDependencyAnalyzer.analyze(modelId, changedFields, dashboard, versions)
    }
}

package ai.moying.iview.datasource

import ai.moying.iview.common.ApiResponse
import ai.moying.iview.query.Dataset
import ai.moying.iview.query.DatasetDraft
import ai.moying.iview.query.DatasetFolder
import ai.moying.iview.query.DatasetFolderDraft
import ai.moying.iview.query.DatasetService
import org.springframework.http.HttpStatus
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
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

class DatasetRequest {
    var sourceId: Long? = null
    var folderId: Long? = null
    var name: String = ""
    var sql: String = ""
    var description: String? = null
}
class DatasetPreviewRequest { var maxRows: Int? = null; var variables: Map<String, String> = emptyMap() }
class DatasetCopyRequest { var name: String = "" }
class DatasetMoveRequest { var folderId: Long? = null }
class DatasetFolderRequest { var name: String = ""; var parentId: Long? = null; var description: String? = null }
class DatasetVariablesRequest { var variables: Map<String, String> = emptyMap() }
data class DatasetLineageField(
    val outputField: String, val sourceCatalog: String?, val sourceSchema: String?, val sourceTable: String?, val sourceField: String?, val confidence: String,
)
data class DatasetLineageNode(val id: String, val kind: String, val label: String)
data class DatasetLineageEdge(val source: String, val target: String, val confidence: String)
data class DatasetLineageSummary(val outputFields: Int, val sourceFields: Int, val sourceTables: Int, val derivedFields: Int)
data class DatasetLineage(
    val datasetId: Long, val fields: List<DatasetLineageField>, val nodes: List<DatasetLineageNode>, val edges: List<DatasetLineageEdge>,
    val summary: DatasetLineageSummary, val note: String,
)

@RestController
@RequestMapping("/api/v1/datasets")
class DatasetController(
    private val datasets: DatasetService,
    private val execution: DataSourceExecutionService,
    private val structuredQueries: StructuredDatasetQueryService = StructuredDatasetQueryService(),
) {
    @GetMapping fun list(@RequestParam(name = "source_id", required = false) sourceId: Long?, @RequestParam(name = "folder_id", required = false) folderId: Long?) = ApiResponse.success(datasets.list(sourceId, folderId).map(::view))
    @GetMapping("/{id}") fun get(@PathVariable id: Long) = ApiResponse.success(view(datasets.get(id)))
    @PostMapping @ResponseStatus(HttpStatus.CREATED) fun create(@RequestBody request: DatasetRequest) = ApiResponse.success(view(datasets.create(request.draft())))
    @PutMapping("/{id}") fun update(@PathVariable id: Long, @RequestBody request: DatasetRequest) = ApiResponse.success(view(datasets.update(id, request.draft())))
    @DeleteMapping("/{id}") fun delete(@PathVariable id: Long): ApiResponse<Nothing> { datasets.delete(id); return ApiResponse.success() }
    @PostMapping("/{id}/copy") fun copy(@PathVariable id: Long, @RequestBody request: DatasetCopyRequest) = ApiResponse.success(view(datasets.copy(id, request.name)))
    @PutMapping("/{id}/folder") fun move(@PathVariable id: Long, @RequestBody request: DatasetMoveRequest) = ApiResponse.success(view(datasets.move(id, request.folderId)))
    @PostMapping("/{id}/preview") fun preview(@PathVariable id: Long, @RequestBody request: DatasetPreviewRequest): ApiResponse<SqlResult> {
        val dataset = datasets.get(id)
        return ApiResponse.success(execution.queryDataset(dataset, request.maxRows ?: 1_000, request.variables))
    }
    @PostMapping("/{id}/query")
    fun query(@PathVariable id: Long, @RequestBody request: DatasetStructuredQueryRequest): ApiResponse<StructuredDatasetQueryResult> {
        val dataset = datasets.get(id)
        val sourceResult = execution.queryDataset(dataset, StructuredDatasetQueryService.SOURCE_ROW_LIMIT, request.variables)
        return ApiResponse.success(structuredQueries.execute(sourceResult, request))
    }
    @PostMapping("/{id}/export") fun export(@PathVariable id: Long, @RequestBody(required = false) request: DatasetVariablesRequest?): ResponseEntity<ByteArray> {
        val dataset = datasets.get(id)
        val result = execution.queryDataset(dataset, 10_000, request?.variables ?: emptyMap())
        val csv = ai.moying.iview.query.CsvExport.render(result.columns.map { it.name }, result.rows).toByteArray(Charsets.UTF_8)
        return ResponseEntity.ok().header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=dataset-${dataset.id}.csv").contentType(MediaType.parseMediaType("text/csv;charset=UTF-8")).body(csv)
    }
    @PostMapping("/{id}/schema") fun schema(@PathVariable id: Long, @RequestBody(required = false) request: DatasetVariablesRequest?): ApiResponse<List<SchemaColumn>> {
        val dataset = datasets.get(id)
        return ApiResponse.success(execution.schema(dataset, request?.variables ?: emptyMap()))
    }
    @PostMapping("/{id}/lineage") fun lineage(@PathVariable id: Long, @RequestBody(required = false) request: DatasetVariablesRequest?): ApiResponse<DatasetLineage> {
        val dataset = datasets.get(id)
        val fields = execution.lineage(dataset, request?.variables ?: emptyMap()).map { field ->
            DatasetLineageField(field.outputField, field.sourceCatalog, field.sourceSchema, field.sourceTable, field.sourceField, field.confidence)
        }
        val outputNodes = fields.map { DatasetLineageNode("output:${it.outputField}", "output", it.outputField) }
        val sourceNodes = fields.mapNotNull { field -> field.sourceField?.let { source ->
            val path = listOfNotNull(field.sourceCatalog, field.sourceSchema, field.sourceTable, source).joinToString(".")
            DatasetLineageNode("source:$path", "source", path)
        } }.distinctBy { it.id }
        val edges = fields.mapNotNull { field -> field.sourceField?.let { source ->
            val path = listOfNotNull(field.sourceCatalog, field.sourceSchema, field.sourceTable, source).joinToString(".")
            DatasetLineageEdge("source:$path", "output:${field.outputField}", field.confidence)
        } }
        val tables = fields.mapNotNull { field -> field.sourceTable?.let { listOfNotNull(field.sourceCatalog, field.sourceSchema, it).joinToString(".") } }.toSet()
        val summary = DatasetLineageSummary(fields.size, sourceNodes.size, tables.size, fields.count { it.sourceField == null })
        return ApiResponse.success(DatasetLineage(dataset.id, fields, sourceNodes + outputNodes, edges, summary, "PostgreSQL 优先使用 JDBC 字段元数据；非关系型数据源按实际响应字段建立来源关系，复杂嵌套字段保留为 JSON。"))
    }
    @PostMapping("/{id}/explain") fun explain(@PathVariable id: Long, @RequestBody(required = false) request: DatasetVariablesRequest?): ApiResponse<List<String>> {
        val dataset = datasets.get(id)
        return ApiResponse.success(execution.explain(dataset, request?.variables ?: emptyMap()))
    }
    private fun DatasetRequest.draft() = DatasetDraft(sourceId ?: 0, folderId, name, sql, description ?: "")
    private fun view(dataset: Dataset) = mapOf(
        "id" to dataset.id, "source_id" to dataset.sourceId, "folder_id" to dataset.folderId, "name" to dataset.name, "sql" to dataset.sql,
        "description" to dataset.description, "created_at" to dataset.createdAt, "updated_at" to dataset.updatedAt,
    )
}

@RestController
@RequestMapping("/api/v1/dataset-folders")
class DatasetFolderController(private val datasets: DatasetService) {
    @GetMapping fun list() = ApiResponse.success(datasets.listFolders().map(::view))
    @PostMapping @ResponseStatus(HttpStatus.CREATED) fun create(@RequestBody request: DatasetFolderRequest) = ApiResponse.success(view(datasets.createFolder(request.draft())))
    @PutMapping("/{id}") fun update(@PathVariable id: Long, @RequestBody request: DatasetFolderRequest) = ApiResponse.success(view(datasets.updateFolder(id, request.draft())))
    @DeleteMapping("/{id}") fun delete(@PathVariable id: Long): ApiResponse<Nothing> { datasets.deleteFolder(id); return ApiResponse.success() }
    private fun DatasetFolderRequest.draft() = DatasetFolderDraft(name, parentId, description ?: "")
    private fun view(folder: DatasetFolder) = mapOf("id" to folder.id, "name" to folder.name, "parent_id" to folder.parentId, "description" to folder.description, "created_at" to folder.createdAt, "updated_at" to folder.updatedAt)
}

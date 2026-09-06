package ai.moying.iview.datasource

import ai.moying.iview.common.ApiResponse
import ai.moying.iview.query.DataSourceService
import ai.moying.iview.query.Dataset
import ai.moying.iview.query.DatasetDraft
import ai.moying.iview.query.DatasetFolder
import ai.moying.iview.query.DatasetFolderDraft
import ai.moying.iview.query.DatasetService
import ai.moying.iview.query.DatasetSql
import ai.moying.iview.query.SafeSql
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
import java.sql.DriverManager

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

@RestController
@RequestMapping("/api/v1/datasets")
class DatasetController(private val datasets: DatasetService, private val sources: DataSourceService) {
    @GetMapping fun list(@RequestParam(name = "source_id", required = false) sourceId: Long?, @RequestParam(name = "folder_id", required = false) folderId: Long?) = ApiResponse.success(datasets.list(sourceId, folderId).map(::view))
    @GetMapping("/{id}") fun get(@PathVariable id: Long) = ApiResponse.success(view(datasets.get(id)))
    @PostMapping @ResponseStatus(HttpStatus.CREATED) fun create(@RequestBody request: DatasetRequest) = ApiResponse.success(view(datasets.create(request.draft())))
    @PutMapping("/{id}") fun update(@PathVariable id: Long, @RequestBody request: DatasetRequest) = ApiResponse.success(view(datasets.update(id, request.draft())))
    @DeleteMapping("/{id}") fun delete(@PathVariable id: Long): ApiResponse<Nothing> { datasets.delete(id); return ApiResponse.success() }
    @PostMapping("/{id}/copy") fun copy(@PathVariable id: Long, @RequestBody request: DatasetCopyRequest) = ApiResponse.success(view(datasets.copy(id, request.name)))
    @PutMapping("/{id}/folder") fun move(@PathVariable id: Long, @RequestBody request: DatasetMoveRequest) = ApiResponse.success(view(datasets.move(id, request.folderId)))
    @PostMapping("/{id}/preview") fun preview(@PathVariable id: Long, @RequestBody request: DatasetPreviewRequest): ApiResponse<SqlResult> {
        val dataset = datasets.get(id)
        val credential = sources.credential(dataset.sourceId)
        val bound = DatasetSql.bindVariables(SafeSql.limit(dataset.sql, request.maxRows ?: 1_000), request.variables)
        return ApiResponse.success(DriverManager.getConnection(credential.jdbcUrl, credential.username, credential.password).use { execute(it, bound.sql, bound.values) })
    }
    @PostMapping("/{id}/export") fun export(@PathVariable id: Long, @RequestBody(required = false) request: DatasetVariablesRequest?): ResponseEntity<ByteArray> {
        val dataset = datasets.get(id); val credential = sources.credential(dataset.sourceId)
        val bound = DatasetSql.bindVariables(SafeSql.limit(dataset.sql, 10_000), request?.variables ?: emptyMap())
        val result = DriverManager.getConnection(credential.jdbcUrl, credential.username, credential.password).use { execute(it, bound.sql, bound.values) }
        val csv = ai.moying.iview.query.CsvExport.render(result.columns.map { it.name }, result.rows).toByteArray(Charsets.UTF_8)
        return ResponseEntity.ok().header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=dataset-${dataset.id}.csv").contentType(MediaType.parseMediaType("text/csv;charset=UTF-8")).body(csv)
    }
    @PostMapping("/{id}/schema") fun schema(@PathVariable id: Long, @RequestBody(required = false) request: DatasetVariablesRequest?): ApiResponse<List<SchemaColumn>> {
        val dataset = datasets.get(id); val credential = sources.credential(dataset.sourceId)
        return ApiResponse.success(DriverManager.getConnection(credential.jdbcUrl, credential.username, credential.password).use { connection ->
            val bound = DatasetSql.bindVariables(DatasetSql.schemaQuery(dataset.sql), request?.variables ?: emptyMap())
            connection.prepareStatement(bound.sql).use { statement ->
                bound.values.forEachIndexed { index, value -> statement.setString(index + 1, value) }
                statement.executeQuery().use { rs ->
                val meta = rs.metaData; (1..meta.columnCount).map { SchemaColumn(meta.getColumnLabel(it), meta.getColumnTypeName(it), meta.isNullable(it) != 0) }
            } }
        })
    }
    @PostMapping("/{id}/explain") fun explain(@PathVariable id: Long, @RequestBody(required = false) request: DatasetVariablesRequest?): ApiResponse<List<String>> {
        val dataset = datasets.get(id); val credential = sources.credential(dataset.sourceId)
        return ApiResponse.success(DriverManager.getConnection(credential.jdbcUrl, credential.username, credential.password).use { connection ->
            val bound = DatasetSql.bindVariables(DatasetSql.explainQuery(dataset.sql), request?.variables ?: emptyMap())
            connection.prepareStatement(bound.sql).use { statement ->
                bound.values.forEachIndexed { index, value -> statement.setString(index + 1, value) }
                statement.executeQuery().use { rs -> generateSequence { if (rs.next()) rs.getString(1) else null }.toList() }
            }
        })
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

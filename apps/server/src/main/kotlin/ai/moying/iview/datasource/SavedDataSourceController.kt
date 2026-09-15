package ai.moying.iview.datasource

import ai.moying.iview.common.ApiResponse
import ai.moying.iview.query.DataSource
import ai.moying.iview.query.DataSourceDraft
import ai.moying.iview.query.DataSourceService
import ai.moying.iview.query.DataSourceType
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
import org.springframework.web.bind.annotation.RequestPart
import org.springframework.web.multipart.MultipartFile
import org.springframework.beans.factory.annotation.Value
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID

class DataSourceRequest {
    var name: String = ""
    var type: String = "postgresql"
    var jdbcUrl: String = ""
    var username: String = ""
    var password: String? = null
    var description: String? = null
}
class SavedQueryRequest { var sql: String = ""; var query: String? = null; var maxRows: Int? = null; var variables: Map<String, String> = emptyMap() }

@RestController
@RequestMapping("/api/v1/data-sources")
class SavedDataSourceController(private val service: DataSourceService, private val execution: DataSourceExecutionService) {
    @GetMapping fun list() = ApiResponse.success(service.list().map(::view))
    @GetMapping("/{id}") fun get(@PathVariable id: Long) = ApiResponse.success(view(service.get(id)))

    @PostMapping @ResponseStatus(HttpStatus.CREATED)
    fun create(@RequestBody request: DataSourceRequest) = ApiResponse.success(view(service.create(request.draft())))

    @PutMapping("/{id}")
    fun update(@PathVariable id: Long, @RequestBody request: DataSourceRequest) = ApiResponse.success(view(service.update(id, request.draft())))

    @DeleteMapping("/{id}") fun delete(@PathVariable id: Long): ApiResponse<Nothing> { service.delete(id); return ApiResponse.success() }

    @PostMapping("/{id}/test") fun test(@PathVariable id: Long) = ApiResponse.success(execution.test(id))

    @GetMapping("/{id}/schemas") fun schemas(@PathVariable id: Long) = ApiResponse.success(execution.schemas(id))

    @GetMapping("/{id}/columns") fun columns(@PathVariable id: Long, @RequestParam schema: String, @RequestParam table: String) = ApiResponse.success(execution.columns(id, schema, table))

    @PostMapping("/{id}/query") fun query(@PathVariable id: Long, @RequestBody request: SavedQueryRequest): ApiResponse<SqlResult> {
        return ApiResponse.success(execution.querySource(id, request.query ?: request.sql, request.maxRows ?: 1_000, request.variables))
    }
    private fun DataSourceRequest.draft() = DataSourceDraft(
        name, runCatching { DataSourceType.valueOf(type.trim().uppercase()) }.getOrElse { throw IllegalArgumentException("暂不支持的数据源类型: $type") },
        jdbcUrl, username, password, description ?: "",
    )
    private fun view(source: DataSource) = mapOf(
        "id" to source.id, "name" to source.name, "type" to source.type.name.lowercase(), "endpoint" to source.jdbcUrl, "jdbc_url" to source.jdbcUrl,
        "username" to source.username, "description" to source.description, "created_at" to source.createdAt, "updated_at" to source.updatedAt,
        "password_configured" to true,
    )
}

@RestController
@RequestMapping("/api/v1/data-sources/files")
class FileDataSourceController(@Value("\${iview.data-sources.file-root:./data/file-sources}") fileRoot: String) {
    private val root = Path.of(fileRoot).toAbsolutePath().normalize()

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    fun upload(@RequestPart("file") file: MultipartFile): ApiResponse<Map<String, Any>> {
        if (file.isEmpty) throw IllegalArgumentException("请选择需要上传的文件")
        if (file.size > 10 * 1024 * 1024) throw IllegalArgumentException("数据文件不能超过10MB")
        val original = file.originalFilename?.substringAfterLast('/')?.substringAfterLast('\\')?.take(180) ?: "data.csv"
        val extension = original.substringAfterLast('.', "").lowercase()
        if (extension !in setOf("csv", "json")) throw IllegalArgumentException("仅支持 CSV 或 JSON 文件")
        val safeName = original.substringBeforeLast('.', "data").replace(Regex("[^A-Za-z0-9._-]"), "_").ifBlank { "data" }
        val relative = "${UUID.randomUUID()}-$safeName.$extension"
        Files.createDirectories(root)
        file.inputStream.use { input -> Files.newOutputStream(root.resolve(relative)).use(input::copyTo) }
        return ApiResponse.success(mapOf("path" to relative, "name" to original, "size" to file.size))
    }
}

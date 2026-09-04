package ai.moying.iview.datasource

import ai.moying.iview.common.ApiResponse
import ai.moying.iview.query.DataSource
import ai.moying.iview.query.DataSourceDraft
import ai.moying.iview.query.DataSourceService
import ai.moying.iview.query.DataSourceType
import ai.moying.iview.query.SafeSql
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
import java.sql.Connection
import java.sql.DriverManager

class DataSourceRequest {
    var name: String = ""
    var type: String = "postgresql"
    var jdbcUrl: String = ""
    var username: String = ""
    var password: String? = null
    var description: String? = null
}
class SavedQueryRequest { var sql: String = ""; var maxRows: Int? = null }

@RestController
@RequestMapping("/api/v1/data-sources")
class SavedDataSourceController(private val service: DataSourceService) {
    @GetMapping fun list() = ApiResponse.success(service.list().map(::view))
    @GetMapping("/{id}") fun get(@PathVariable id: Long) = ApiResponse.success(view(service.get(id)))

    @PostMapping @ResponseStatus(HttpStatus.CREATED)
    fun create(@RequestBody request: DataSourceRequest) = ApiResponse.success(view(service.create(request.draft())))

    @PutMapping("/{id}")
    fun update(@PathVariable id: Long, @RequestBody request: DataSourceRequest) = ApiResponse.success(view(service.update(id, request.draft())))

    @DeleteMapping("/{id}") fun delete(@PathVariable id: Long): ApiResponse<Nothing> { service.delete(id); return ApiResponse.success() }

    @PostMapping("/{id}/test") fun test(@PathVariable id: Long) = ApiResponse.success(withConnection(id) { connection ->
        mapOf("connected" to true, "database" to connection.metaData.databaseProductName, "version" to connection.metaData.databaseProductVersion)
    })

    @GetMapping("/{id}/schemas") fun schemas(@PathVariable id: Long) = ApiResponse.success(withConnection(id) { connection ->
        connection.metaData.getTables(null, null, "%", arrayOf("TABLE", "VIEW")).use { rs ->
            generateSequence { if (rs.next()) SchemaTable(rs.getString("TABLE_SCHEM"), rs.getString("TABLE_NAME")) else null }
                .filter { it.schema !in setOf("pg_catalog", "information_schema") }.toList()
        }
    })

    @GetMapping("/{id}/columns") fun columns(@PathVariable id: Long, @RequestParam schema: String, @RequestParam table: String) = ApiResponse.success(withConnection(id) { connection ->
        connection.metaData.getColumns(null, schema, table, "%").use { rs ->
            generateSequence { if (rs.next()) SchemaColumn(rs.getString("COLUMN_NAME"), rs.getString("TYPE_NAME"), rs.getInt("NULLABLE") != 0) else null }.toList()
        }
    })

    @PostMapping("/{id}/query") fun query(@PathVariable id: Long, @RequestBody request: SavedQueryRequest): ApiResponse<SqlResult> {
        val sql = SafeSql.limit(request.sql, request.maxRows ?: 1_000)
        return ApiResponse.success(withConnection(id) { execute(it, sql) })
    }

    private fun <T> withConnection(id: Long, block: (Connection) -> T): T {
        val credential = service.credential(id)
        return DriverManager.getConnection(credential.jdbcUrl, credential.username, credential.password).use(block)
    }
    private fun DataSourceRequest.draft() = DataSourceDraft(
        name, runCatching { DataSourceType.valueOf(type.trim().uppercase()) }.getOrElse { throw IllegalArgumentException("暂不支持的数据源类型: $type") },
        jdbcUrl, username, password, description ?: "",
    )
    private fun view(source: DataSource) = mapOf(
        "id" to source.id, "name" to source.name, "type" to source.type.name.lowercase(), "jdbc_url" to source.jdbcUrl,
        "username" to source.username, "description" to source.description, "created_at" to source.createdAt, "updated_at" to source.updatedAt,
        "password_configured" to true,
    )
}

package ai.moying.iview.datasource

import ai.moying.iview.common.ApiResponse
import ai.moying.iview.query.SafeSql
import org.springframework.web.bind.annotation.*
import java.sql.DriverManager

open class PostgresConnectionRequest { var jdbcUrl:String=""; var username:String=""; var password:String=""; var connectTimeoutMs:Int?=null }
class PostgresQueryRequest:PostgresConnectionRequest(){var sql:String="";var maxRows:Int?=null}
data class SchemaTable(val schema:String,val table:String)
data class SchemaColumn(val name:String,val type:String,val nullable:Boolean)
data class SqlResult(val columns:List<SchemaColumn>,val rows:List<List<Any?>>)

@RestController
@RequestMapping("/api/v1/data-sources/postgres")
class PostgresController {
 @PostMapping("/test") fun test(@RequestBody body:PostgresConnectionRequest)=ApiResponse.success(connect(body){ c -> mapOf("connected" to true,"database" to c.metaData.databaseProductName,"version" to c.metaData.databaseProductVersion) })
 @PostMapping("/schemas") fun schemas(@RequestBody body:PostgresConnectionRequest)=ApiResponse.success(connect(body){c->c.metaData.getTables(null,null,"%",arrayOf("TABLE","VIEW")).use{rs->generateSequence{if(rs.next())SchemaTable(rs.getString("TABLE_SCHEM"),rs.getString("TABLE_NAME"))else null}.filter{it.schema !in setOf("pg_catalog","information_schema")}.toList()}})
 @PostMapping("/columns") fun columns(@RequestBody body:PostgresConnectionRequest,@RequestParam schema:String,@RequestParam table:String)=ApiResponse.success(connect(body){c->c.metaData.getColumns(null,schema,table,"%").use{rs->generateSequence{if(rs.next())SchemaColumn(rs.getString("COLUMN_NAME"),rs.getString("TYPE_NAME"),rs.getInt("NULLABLE")!=0)else null}.toList()}})
 @PostMapping("/query") fun query(@RequestBody body:PostgresQueryRequest): ApiResponse<SqlResult> {
  val sql = SafeSql.limit(body.sql, body.maxRows ?: 1_000)
  return ApiResponse.success(connect(body) { c -> execute(c, sql) })
 }
 private fun <T> connect(body:PostgresConnectionRequest,block:(java.sql.Connection)->T):T{if(!body.jdbcUrl.startsWith("jdbc:postgresql://"))throw IllegalArgumentException("仅支持 PostgreSQL JDBC URL");return DriverManager.getConnection(body.jdbcUrl,body.username,body.password).use(block)}
}

internal fun execute(connection: java.sql.Connection, sql: String, values: List<String> = emptyList()): SqlResult = connection.prepareStatement(sql).use { statement ->
 values.forEachIndexed { index, value -> statement.setString(index + 1, value) }
 statement.queryTimeout = 30
 statement.executeQuery(sql).use { rs ->
  val meta = rs.metaData
  val columns = (1..meta.columnCount).map { SchemaColumn(meta.getColumnLabel(it), meta.getColumnTypeName(it), meta.isNullable(it) != 0) }
  val rows = generateSequence { if (rs.next()) (1..meta.columnCount).map(rs::getObject) else null }.toList()
  SqlResult(columns, rows)
 }
}

package ai.moying.iview.datasource

import ai.moying.iview.query.DataSourceCredential
import ai.moying.iview.query.DataSourceService
import ai.moying.iview.query.DataSourceType
import ai.moying.iview.query.Dataset
import ai.moying.iview.query.DatasetSql
import ai.moying.iview.query.DatasetValidationException
import ai.moying.iview.query.SafeSql
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.mongodb.ConnectionString
import com.mongodb.MongoClientSettings
import com.mongodb.MongoCredential
import com.mongodb.client.MongoClient
import com.mongodb.client.MongoClients
import org.bson.Document
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import java.net.InetAddress
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.sql.DriverManager
import java.time.Duration
import java.util.Base64
import java.util.concurrent.TimeUnit

@Service
class DataSourceExecutionService(
    private val sources: DataSourceService,
    private val mapper: ObjectMapper,
    @Value("\${iview.data-sources.file-root:./data/file-sources}") fileRoot: String,
    @Value("\${iview.data-sources.http.allow-private:false}") private val allowPrivateHttp: Boolean,
) {
    private val fileRoot: Path = Path.of(fileRoot).toAbsolutePath().normalize()
    private val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).followRedirects(HttpClient.Redirect.NEVER).build()

    fun test(id: Long): Map<String, Any?> = withCredential(id) { credential ->
        when (credential.type) {
            DataSourceType.POSTGRESQL -> postgres(credential) { connection -> mapOf("connected" to true, "database" to connection.metaData.databaseProductName, "version" to connection.metaData.databaseProductVersion) }
            DataSourceType.MONGODB -> mongo(credential, emptyObject()) { client, connection ->
                val database = connection.database ?: "admin"
                client.getDatabase(database).runCommand(Document("ping", 1))
                mapOf("connected" to true, "database" to "MongoDB", "version" to "server", "default_database" to database)
            }
            DataSourceType.HTTP -> {
                val result = httpQuery(credential, emptyObject(), 1)
                mapOf("connected" to true, "database" to "HTTP JSON", "version" to "HTTP/1.1+", "columns" to result.columns.size)
            }
            DataSourceType.FILE -> {
                val path = resolveFile(credential.endpoint)
                val size = Files.size(path)
                readFile(credential, emptyObject(), 1)
                mapOf("connected" to true, "database" to "File", "version" to path.fileName.toString(), "size" to size)
            }
        }
    }

    fun schemas(id: Long): List<SchemaTable> = withCredential(id) { credential ->
        when (credential.type) {
            DataSourceType.POSTGRESQL -> postgres(credential) { connection -> connection.metaData.getTables(null, null, "%", arrayOf("TABLE", "VIEW")).use { rs ->
                generateSequence { if (rs.next()) SchemaTable(rs.getString("TABLE_SCHEM"), rs.getString("TABLE_NAME")) else null }
                    .filter { it.schema !in setOf("pg_catalog", "information_schema") }.toList()
            } }
            DataSourceType.MONGODB -> mongo(credential, emptyObject()) { client, connection ->
                val database = connection.database ?: throw DatasetValidationException("MongoDB 地址或查询定义必须指定 database")
                client.getDatabase(database).listCollectionNames().map { SchemaTable(database, it) }.toList()
            }
            DataSourceType.HTTP -> listOf(SchemaTable("http", URI(credential.endpoint).host ?: "endpoint"))
            DataSourceType.FILE -> listOf(SchemaTable("file", resolveFile(credential.endpoint).fileName.toString()))
        }
    }

    fun columns(id: Long, schema: String, table: String): List<SchemaColumn> = withCredential(id) { credential ->
        when (credential.type) {
            DataSourceType.POSTGRESQL -> postgres(credential) { connection -> connection.metaData.getColumns(null, schema, table, "%").use { rs ->
                generateSequence { if (rs.next()) SchemaColumn(rs.getString("COLUMN_NAME"), rs.getString("TYPE_NAME"), rs.getInt("NULLABLE") != 0) else null }.toList()
            } }
            DataSourceType.MONGODB -> mongo(credential, objectNode("database" to schema, "collection" to table)) { client, _ ->
                val document = client.getDatabase(schema).getCollection(table).find().limit(1).first()
                document?.let { rowsToResult(listOf(documentToMap(it))).columns } ?: emptyList()
            }
            DataSourceType.HTTP -> httpQuery(credential, emptyObject(), 1).columns
            DataSourceType.FILE -> readFile(credential, emptyObject(), 1).columns
        }
    }

    fun querySource(id: Long, definition: String, maxRows: Int, variables: Map<String, String> = emptyMap()): SqlResult =
        execute(sources.credential(id), definition, maxRows, variables)

    fun queryDataset(dataset: Dataset, maxRows: Int, variables: Map<String, String> = emptyMap()): SqlResult =
        execute(sources.credential(dataset.sourceId), dataset.sql, maxRows, variables)

    fun schema(dataset: Dataset, variables: Map<String, String>): List<SchemaColumn> {
        val credential = sources.credential(dataset.sourceId)
        return if (credential.type == DataSourceType.POSTGRESQL) postgres(credential) { connection ->
            val bound = DatasetSql.bindVariables(DatasetSql.schemaQuery(dataset.sql), variables)
            connection.prepareStatement(bound.sql).use { statement ->
                bind(statement, bound.values)
                statement.executeQuery().use { rs -> val meta = rs.metaData; (1..meta.columnCount).map { SchemaColumn(meta.getColumnLabel(it), meta.getColumnTypeName(it), meta.isNullable(it) != 0) } }
            }
        } else queryDataset(dataset, 1, variables).columns
    }

    fun explain(dataset: Dataset, variables: Map<String, String>): List<String> {
        val credential = sources.credential(dataset.sourceId)
        if (credential.type != DataSourceType.POSTGRESQL) return listOf("${credential.type.name} 数据源不提供 PostgreSQL 执行计划；查询定义已通过类型校验。")
        return postgres(credential) { connection ->
            val bound = DatasetSql.bindVariables(DatasetSql.explainQuery(dataset.sql), variables)
            connection.prepareStatement(bound.sql).use { statement -> bind(statement, bound.values); statement.executeQuery().use { rs -> generateSequence { if (rs.next()) rs.getString(1) else null }.toList() } }
        }
    }

    private fun execute(credential: DataSourceCredential, definition: String, requestedRows: Int, variables: Map<String, String>): SqlResult {
        val maxRows = requestedRows.coerceIn(1, 10_000)
        return when (credential.type) {
            DataSourceType.POSTGRESQL -> {
                val bound = DatasetSql.bindVariables(SafeSql.limit(definition, maxRows), variables)
                postgres(credential) { execute(it, bound.sql, bound.values) }
            }
            DataSourceType.MONGODB -> mongoQuery(credential, parseDefinition(definition, variables), maxRows)
            DataSourceType.HTTP -> httpQuery(credential, parseDefinition(definition, variables), maxRows)
            DataSourceType.FILE -> readFile(credential, parseDefinition(definition, variables), maxRows)
        }
    }

    fun lineage(dataset: Dataset, variables: Map<String, String>): List<ExecutedLineageField> {
        val credential = sources.credential(dataset.sourceId)
        if (credential.type != DataSourceType.POSTGRESQL) {
            val label = when (credential.type) {
                DataSourceType.MONGODB -> runCatching { text(parseDefinition(dataset.sql, variables), "collection") }.getOrNull() ?: "collection"
                DataSourceType.HTTP -> URI(credential.endpoint).host ?: "endpoint"
                DataSourceType.FILE -> Path.of(credential.endpoint).fileName.toString()
                else -> "source"
            }
            return schema(dataset, variables).map { ExecutedLineageField(it.name, null, credential.type.name.lowercase(), label, it.name, "SOURCE_SCHEMA") }
        }
        return postgres(credential) { connection ->
            val bound = DatasetSql.bindVariables(DatasetSql.schemaQuery(dataset.sql), variables)
            connection.prepareStatement(bound.sql).use { statement ->
                bind(statement, bound.values)
                statement.executeQuery().use { rs ->
                    val meta = rs.metaData
                    (1..meta.columnCount).map { index ->
                        val table = meta.getTableName(index).takeIf { !it.isNullOrBlank() }
                        val field = meta.getColumnName(index).takeIf { !it.isNullOrBlank() }
                        ExecutedLineageField(meta.getColumnLabel(index), meta.getCatalogName(index).takeIf { !it.isNullOrBlank() }, meta.getSchemaName(index).takeIf { !it.isNullOrBlank() }, table, field, if (table != null && field != null) "JDBC_METADATA" else "DERIVED_OR_DRIVER_UNAVAILABLE")
                    }
                }
            }
        }
    }

    private fun mongoQuery(credential: DataSourceCredential, config: JsonNode, maxRows: Int): SqlResult = mongo(credential, config) { client, connection ->
        val database = text(config, "database") ?: connection.database ?: throw DatasetValidationException("MongoDB 查询定义缺少 database")
        val collection = text(config, "collection") ?: throw DatasetValidationException("MongoDB 查询定义缺少 collection")
        var query = client.getDatabase(database).getCollection(collection).find(document(config["filter"]))
        config["projection"]?.takeIf(JsonNode::isObject)?.let { query = query.projection(document(it)) }
        config["sort"]?.takeIf(JsonNode::isObject)?.let { query = query.sort(document(it)) }
        val limit = (config["limit"]?.asInt(maxRows) ?: maxRows).coerceIn(1, maxRows)
        rowsToResult(query.limit(limit).map(::documentToMap).toList())
    }

    private fun httpQuery(credential: DataSourceCredential, config: JsonNode, maxRows: Int): SqlResult {
        val base = URI(credential.endpoint)
        val path = text(config, "path") ?: ""
        if (runCatching { URI(path).isAbsolute }.getOrDefault(false)) throw DatasetValidationException("HTTP 查询 path 不能是绝对地址")
        var uri = base.resolve(path)
        if (!sameOrigin(base, uri)) throw DatasetValidationException("HTTP 查询不能跳转到其他主机")
        val query = config["query"]?.takeIf(JsonNode::isObject)?.fields()?.asSequence()?.map { (key, value) ->
            encode(key) + "=" + encode(if (value.isValueNode) value.asText() else value.toString())
        }?.joinToString("&").orEmpty()
        if (query.isNotEmpty()) uri = URI(uri.toString() + (if (uri.rawQuery == null) "?" else "&") + query)
        validateHttpTarget(uri)
        val method = (text(config, "method") ?: "GET").uppercase()
        if (method !in setOf("GET", "POST")) throw DatasetValidationException("HTTP 数据源仅允许 GET 或 POST")
        val builder = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(30)).header("Accept", "application/json")
        config["headers"]?.takeIf(JsonNode::isObject)?.fields()?.forEach { (name, value) ->
            if (name.lowercase() in setOf("host", "content-length", "authorization")) throw DatasetValidationException("HTTP 请求头不允许设置 $name")
            builder.header(name, value.asText())
        }
        if (credential.username.isNotBlank()) builder.header("Authorization", "Basic " + Base64.getEncoder().encodeToString("${credential.username}:${credential.password}".toByteArray(StandardCharsets.UTF_8)))
        val body = config["body"]?.let(mapper::writeValueAsString).orEmpty()
        builder.method(method, if (method == "POST") HttpRequest.BodyPublishers.ofString(body.ifBlank { "{}" }) else HttpRequest.BodyPublishers.noBody())
        if (method == "POST") builder.header("Content-Type", "application/json")
        val response = http.send(builder.build(), HttpResponse.BodyHandlers.ofInputStream())
        response.body().use { stream ->
            val bytes = stream.readNBytes(MAX_RESPONSE_BYTES + 1)
            if (bytes.size > MAX_RESPONSE_BYTES) throw DatasetValidationException("HTTP 响应超过10MB限制")
            if (response.statusCode() !in 200..299) throw DatasetValidationException("HTTP 数据源返回状态码 ${response.statusCode()}")
            var node = runCatching { mapper.readTree(bytes) }.getOrElse { throw DatasetValidationException("HTTP 响应不是有效 JSON") }
            text(config, "data_path")?.split('.')?.filter(String::isNotBlank)?.forEach { part -> node = node[part] ?: throw DatasetValidationException("HTTP 响应不存在 data_path: ${text(config, "data_path")}") }
            return jsonToResult(node, maxRows)
        }
    }

    private fun readFile(credential: DataSourceCredential, config: JsonNode, maxRows: Int): SqlResult {
        val path = resolveFile(credential.endpoint)
        if (!Files.isRegularFile(path)) throw DatasetValidationException("文件数据源不存在: ${credential.endpoint}")
        if (Files.size(path) > MAX_RESPONSE_BYTES) throw DatasetValidationException("文件数据源超过10MB限制")
        return when (path.fileName.toString().substringAfterLast('.', "").lowercase()) {
            "json" -> Files.newInputStream(path).use { input -> runCatching { jsonToResult(mapper.readTree(input), maxRows) }.getOrElse { throw DatasetValidationException("文件内容不是有效 JSON") } }
            "csv" -> {
                val delimiter = text(config, "delimiter")?.singleOrNull() ?: ','
                val records = parseCsv(Files.readString(path), delimiter)
                if (records.isEmpty()) SqlResult(emptyList(), emptyList()) else {
                    val headers = records.first().mapIndexed { index, value -> value.ifBlank { "column_${index + 1}" } }
                    val rows = records.drop(1).take(maxRows).map { record -> headers.indices.associate { headers[it] to record.getOrNull(it) } }
                    rowsToResult(rows, headers)
                }
            }
            else -> throw DatasetValidationException("文件数据源仅支持 CSV 或 JSON")
        }
    }

    private fun jsonToResult(node: JsonNode, maxRows: Int): SqlResult {
        val nodes = if (node.isArray) node.take(maxRows) else listOf(node)
        val rows = nodes.map { item ->
            if (item.isObject) item.fields().asSequence().associate { (key, value) -> key to jsonValue(value) }
            else mapOf("value" to jsonValue(item))
        }
        return rowsToResult(rows)
    }

    private fun rowsToResult(rows: List<Map<String, Any?>>, preferred: List<String> = emptyList()): SqlResult {
        val names = (preferred + rows.flatMap(Map<String, Any?>::keys)).distinct()
        val columns = names.map { name -> SchemaColumn(name, inferType(rows.asSequence().map { it[name] }.firstOrNull { value -> value != null }), true) }
        return SqlResult(columns, rows.map { row -> names.map(row::get) })
    }

    private fun parseDefinition(definition: String, variables: Map<String, String>): JsonNode {
        if (variables.size > 50 || variables.any { (key, value) -> !Regex("[A-Za-z_][A-Za-z0-9_]{0,63}").matches(key) || value.length > 500 }) throw DatasetValidationException("查询变量无效")
        val root = runCatching { mapper.readTree(definition) }.getOrElse { throw DatasetValidationException("查询定义不是有效 JSON") }
        if (!root.isObject) throw DatasetValidationException("查询定义必须是 JSON 对象")
        return bindJson(root.deepCopy(), variables)
    }

    private fun bindJson(node: JsonNode, variables: Map<String, String>): JsonNode {
        if (node.isObject) node.fields().forEach { (key, value) -> if (value.isTextual) (node as com.fasterxml.jackson.databind.node.ObjectNode).put(key, bindText(value.asText(), variables)) else bindJson(value, variables) }
        else if (node.isArray) node.forEachIndexed { index, value -> if (value.isTextual) (node as com.fasterxml.jackson.databind.node.ArrayNode).set(index, mapper.nodeFactory.textNode(bindText(value.asText(), variables))) else bindJson(value, variables) }
        return node
    }

    private fun bindText(value: String, variables: Map<String, String>) = VARIABLE.replace(value) { match -> variables[match.groupValues[1]] ?: throw DatasetValidationException("缺少查询变量: ${match.groupValues[1]}") }
    private fun parseCsv(content: String, delimiter: Char): List<List<String>> {
        val rows = mutableListOf<List<String>>(); val row = mutableListOf<String>(); val field = StringBuilder(); var quoted = false; var index = 0
        while (index < content.length) {
            val char = content[index]
            when {
                char == '"' && quoted && index + 1 < content.length && content[index + 1] == '"' -> { field.append('"'); index++ }
                char == '"' -> quoted = !quoted
                char == delimiter && !quoted -> { row += field.toString(); field.setLength(0) }
                (char == '\n' || char == '\r') && !quoted -> { if (char == '\r' && index + 1 < content.length && content[index + 1] == '\n') index++; row += field.toString(); field.setLength(0); rows += row.toList(); row.clear() }
                else -> field.append(char)
            }
            index++
        }
        if (quoted) throw DatasetValidationException("CSV 文件存在未闭合的引号")
        if (field.isNotEmpty() || row.isNotEmpty()) { row += field.toString(); rows += row.toList() }
        return rows.filterNot { it.size == 1 && it[0].isBlank() }
    }

    private fun resolveFile(relative: String): Path {
        val path = fileRoot.resolve(relative).normalize()
        if (!path.startsWith(fileRoot)) throw DatasetValidationException("文件路径超出允许的数据目录")
        return path
    }
    private fun validateHttpTarget(uri: URI) {
        if (uri.scheme !in setOf("http", "https") || uri.host.isNullOrBlank()) throw DatasetValidationException("HTTP 数据源地址无效")
        if (!allowPrivateHttp && InetAddress.getAllByName(uri.host).any { it.isAnyLocalAddress || it.isLoopbackAddress || it.isLinkLocalAddress || it.isSiteLocalAddress || it.isMulticastAddress }) {
            throw DatasetValidationException("HTTP 数据源默认禁止访问本机或内网地址；可信部署可设置 IVIEW_HTTP_ALLOW_PRIVATE=true")
        }
    }
    private fun sameOrigin(left: URI, right: URI) = left.scheme.equals(right.scheme, true) && left.host.equals(right.host, true) && effectivePort(left) == effectivePort(right)
    private fun effectivePort(uri: URI) = if (uri.port >= 0) uri.port else if (uri.scheme.equals("https", true)) 443 else 80
    private fun encode(value: String) = URLEncoder.encode(value, StandardCharsets.UTF_8)
    private fun emptyObject() = mapper.createObjectNode()
    private fun objectNode(vararg pairs: Pair<String, String>) = mapper.createObjectNode().also { node -> pairs.forEach { node.put(it.first, it.second) } }
    private fun text(node: JsonNode, name: String) = node[name]?.takeUnless(JsonNode::isNull)?.asText()?.takeIf(String::isNotBlank)
    private fun document(node: JsonNode?) = if (node == null || !node.isObject) Document() else Document.parse(mapper.writeValueAsString(node))
    private fun documentToMap(document: Document): Map<String, Any?> = mapper.convertValue(mapper.readTree(document.toJson()), Map::class.java).entries.associate { it.key.toString() to it.value }
    private fun jsonValue(node: JsonNode): Any? = when {
        node.isNull -> null
        node.isBoolean -> node.booleanValue()
        node.isIntegralNumber -> node.longValue()
        node.isFloatingPointNumber -> node.doubleValue()
        node.isTextual -> node.textValue()
        else -> mapper.writeValueAsString(node)
    }
    private fun inferType(value: Any?) = when (value) { is Boolean -> "BOOLEAN"; is Byte, is Short, is Int, is Long -> "BIGINT"; is Float, is Double -> "DOUBLE"; null -> "UNKNOWN"; else -> "VARCHAR" }
    private fun bind(statement: java.sql.PreparedStatement, values: List<String>) = values.forEachIndexed { index, value -> statement.setString(index + 1, value) }
    private fun <T> withCredential(id: Long, block: (DataSourceCredential) -> T) = block(sources.credential(id))
    private fun <T> postgres(credential: DataSourceCredential, block: (java.sql.Connection) -> T): T = DriverManager.getConnection(credential.endpoint, credential.username, credential.password).use(block)
    private fun <T> mongo(credential: DataSourceCredential, config: JsonNode, block: (MongoClient, ConnectionString) -> T): T {
        val connection = ConnectionString(credential.endpoint)
        val settings = MongoClientSettings.builder().applyConnectionString(connection).applyToClusterSettings { it.serverSelectionTimeout(5, TimeUnit.SECONDS) }
        if (credential.username.isNotBlank()) settings.credential(MongoCredential.createCredential(credential.username, text(config, "auth_database") ?: connection.database ?: "admin", credential.password.toCharArray()))
        return MongoClients.create(settings.build()).use { block(it, connection) }
    }

    companion object {
        private const val MAX_RESPONSE_BYTES = 10 * 1024 * 1024
        private val VARIABLE = Regex("\\{\\{([A-Za-z_][A-Za-z0-9_]{0,63})}}")
    }
}

data class ExecutedLineageField(
    val outputField: String,
    val sourceCatalog: String?,
    val sourceSchema: String?,
    val sourceTable: String?,
    val sourceField: String?,
    val confidence: String,
)

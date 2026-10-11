package ai.moying.iview.datasource

import ai.moying.iview.query.DatasetValidationException
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.OffsetDateTime
import java.util.UUID

/** Builds a PostgreSQL query around an already validated read-only dataset SQL. */
class PostgresStructuredQueryBuilder {
    fun build(baseSql: String, baseValues: List<String>, schema: List<SchemaColumn>, request: DatasetStructuredQueryRequest): PostgresStructuredQuery {
        requireRequest(request.limit in 1..StructuredDatasetQueryService.PAGE_LIMIT, "limit 必须在 1 到 ${StructuredDatasetQueryService.PAGE_LIMIT} 之间")
        requireRequest(request.offset in 0..999_999, "offset 必须在 0 到 999999 之间")
        requireRequest(request.fields.size <= 100, "查询字段最多 100 个")
        requireRequest(request.filters.size <= 20, "筛选条件最多 20 个")
        requireRequest(request.groupBy.size <= 20, "分组字段最多 20 个")
        requireRequest(request.aggregations.size <= 20, "聚合项最多 20 个")
        requireRequest(request.having.size <= 20, "HAVING 条件最多 20 个")
        requireRequest(request.orderBy.size <= 10, "排序字段最多 10 个")

        val columns = schema.associateBy { it.name }
        fun source(name: String): SchemaColumn = columns[name] ?: invalid("字段不存在: $name")
        val grouped = request.groupBy.isNotEmpty() || request.aggregations.isNotEmpty() || request.having.isNotEmpty()
        if (grouped) requireRequest(request.aggregations.isNotEmpty() || request.groupBy.isNotEmpty(), "分组查询必须包含 group_by 或 aggregations")

        val outputExpressions = linkedMapOf<String, String>()
        val outputTypes = linkedMapOf<String, String?>()
        val select = if (grouped) {
            request.groupBy.distinct().map { name ->
                val column = source(name)
                quote(name).also { outputExpressions[name] = it; outputTypes[name] = column.type }
            } +
                request.aggregations.map { aggregation ->
                    val function = aggregation.function.uppercase()
                    requireRequest(function in AGGREGATIONS, "不支持的聚合函数: ${aggregation.function}")
                    requireRequest(ALIAS.matches(aggregation.alias), "聚合别名无效: ${aggregation.alias}")
                    requireRequest(aggregation.alias !in outputExpressions, "输出字段重复: ${aggregation.alias}")
                    var resultType: String? = "int8"
                    val expression = if (function == "COUNT" && aggregation.field.isNullOrBlank()) "COUNT(*)" else {
                        val field = aggregation.field?.takeIf(String::isNotBlank) ?: invalid("${aggregation.function} 必须指定字段")
                        val column = source(field)
                        resultType = if (function == "COUNT") "int8" else column.type
                        "$function(${quote(field)})"
                    }
                    outputExpressions[aggregation.alias] = expression
                    outputTypes[aggregation.alias] = resultType
                    "$expression AS ${quote(aggregation.alias)}"
                }
        } else {
            val fields = request.fields.ifEmpty { schema.map(SchemaColumn::name) }
            requireRequest(fields.isNotEmpty(), "查询字段不能为空")
            fields.map { name -> source(name); quote(name).also { outputExpressions[name] = it } }
        }

        val values = baseValues.map { it as Any? }.toMutableList()
        val where = filters(request.filters, columns, values, emptyMap())
        val groupBy = request.groupBy.distinct().map { source(it); quote(it) }
        val having = filters(request.having, columns, values, outputExpressions, true, outputTypes)
        request.orderBy.forEach { sort ->
            requireRequest(sort.field in outputExpressions, "排序字段不在查询输出中: ${sort.field}")
            requireRequest(sort.direction.uppercase() in setOf("ASC", "DESC"), "排序方向只能是 ASC 或 DESC")
        }

        val shapedSql = buildString {
            append("SELECT ").append(select.joinToString(", ")).append(" FROM (").append(baseSql).append(") iview_dataset")
            if (where.isNotEmpty()) append(" WHERE ").append(where.joinToString(" AND "))
            if (groupBy.isNotEmpty()) append(" GROUP BY ").append(groupBy.joinToString(", "))
            if (having.isNotEmpty()) append(" HAVING ").append(having.joinToString(" AND "))
        }
        val orderedSql = buildString {
            append(shapedSql)
            if (request.orderBy.isNotEmpty()) append(" ORDER BY ").append(request.orderBy.joinToString(", ") { "${quote(it.field)} ${it.direction.uppercase()}" })
            append(" LIMIT ").append(request.limit).append(" OFFSET ").append(request.offset)
        }
        return PostgresStructuredQuery(orderedSql, "SELECT COUNT(*) FROM ($shapedSql) iview_count", values, baseValues.size)
    }

    private fun filters(
        filters: List<DatasetFilterRequest>,
        columns: Map<String, SchemaColumn>,
        values: MutableList<Any?>,
        expressions: Map<String, String>,
        having: Boolean = false,
        expressionTypes: Map<String, String?> = emptyMap(),
    ): List<String> = filters.map { filter ->
        val expression = expressions[filter.field] ?: if (!having) quote(columns[filter.field]?.name ?: invalid("字段不存在: ${filter.field}")) else invalid("HAVING 字段不在分组输出中: ${filter.field}")
        val sourceType = expressionTypes[filter.field] ?: columns[filter.field]?.type
        val operator = runCatching { DatasetFilterOperator.from(filter.operator) }.getOrElse { invalid("不支持的筛选操作: ${filter.operator}") }
        when (operator) {
            DatasetFilterOperator.IS_NULL -> "$expression IS NULL"
            DatasetFilterOperator.IS_NOT_NULL -> "$expression IS NOT NULL"
            DatasetFilterOperator.IS_NOT_BLANK -> "NULLIF(BTRIM(CAST($expression AS TEXT)), '') IS NOT NULL"
            DatasetFilterOperator.IN, DatasetFilterOperator.NOT_IN -> {
                val targets = filter.values.ifEmpty { listOfNotNull(filter.value) }
                requireRequest(targets.isNotEmpty(), "${filter.operator} 需要 values")
                requireRequest(targets.size <= 100, "IN 条件最多 100 个值")
                targets.forEach { values += typed(it, sourceType) }
                "$expression ${if (operator == DatasetFilterOperator.IN) "IN" else "NOT IN"} (${targets.joinToString(",") { "?" }})"
            }
            DatasetFilterOperator.LIKE -> {
                values += filter.value.orEmpty()
                "CAST($expression AS TEXT) LIKE ?"
            }
            else -> {
                val value = filter.value ?: invalid("${filter.operator} 缺少比较值")
                values += typed(value, sourceType)
                "$expression ${SQL_OPERATORS.getValue(operator)} ?"
            }
        }
    }

    private fun typed(value: String, type: String?): Any = when {
        type == null -> value
        type.lowercase().let { it.contains("int") || it.contains("numeric") || it.contains("decimal") || it.contains("real") || it.contains("double") || it.contains("float") } ->
            runCatching { BigDecimal(value) }.getOrElse { invalid("数值字段的筛选值无效: $value") }
        type.lowercase().contains("bool") -> value.toBooleanStrictOrNull() ?: invalid("布尔字段的筛选值只能是 true 或 false")
        type.lowercase().contains("timestamp") && (type.lowercase().contains("tz") || type.lowercase().contains("with time zone")) -> runCatching { OffsetDateTime.parse(value) }.getOrElse { invalid("带时区时间字段的筛选值必须是 ISO-8601 格式") }
        type.lowercase().contains("timestamp") -> runCatching { LocalDateTime.parse(value) }.getOrElse { invalid("时间字段的筛选值必须是 ISO-8601 格式") }
        type.lowercase() == "date" -> runCatching { LocalDate.parse(value) }.getOrElse { invalid("日期字段的筛选值必须是 yyyy-MM-dd") }
        type.lowercase().startsWith("time") -> runCatching { LocalTime.parse(value) }.getOrElse { invalid("时间字段的筛选值格式无效") }
        type.lowercase() == "uuid" -> runCatching { UUID.fromString(value) }.getOrElse { invalid("UUID 字段的筛选值格式无效") }
        else -> value
    }

    private fun quote(value: String) = "\"${value.replace("\"", "\"\"")}\""
    private fun requireRequest(condition: Boolean, message: String) { if (!condition) invalid(message) }
    private fun invalid(message: String): Nothing = throw DatasetValidationException(message)

    private companion object {
        val ALIAS = Regex("[A-Za-z_][A-Za-z0-9_]{0,63}")
        val AGGREGATIONS = setOf("COUNT", "SUM", "AVG", "MIN", "MAX")
        val SQL_OPERATORS = mapOf(
            DatasetFilterOperator.EQUALS to "=", DatasetFilterOperator.NOT_EQUALS to "<>",
            DatasetFilterOperator.GREATER_THAN to ">", DatasetFilterOperator.GREATER_THAN_OR_EQUAL to ">=",
            DatasetFilterOperator.LESS_THAN to "<", DatasetFilterOperator.LESS_THAN_OR_EQUAL to "<=",
        )
    }
}

data class PostgresStructuredQuery(val sql: String, val countSql: String, val values: List<Any?>, val baseValueCount: Int)

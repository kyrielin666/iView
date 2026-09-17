package ai.moying.iview.datasource

import ai.moying.iview.query.DatasetValidationException
import java.math.BigDecimal

/**
 * Applies the dashboard query builder's projection, filter, ordering and paging
 * rules to a dataset result. Column identifiers are always checked against the
 * dataset schema; this layer never interpolates request values into source SQL.
 *
 * The source execution is intentionally capped at [SOURCE_ROW_LIMIT]. This is a
 * portable fallback for HTTP, file and Mongo sources. PostgreSQL push-down for
 * joins/grouping/having remains a separate, planned capability.
 */
class StructuredDatasetQueryService {
    fun execute(result: SqlResult, request: DatasetStructuredQueryRequest): StructuredDatasetQueryResult {
        val allFields = result.columns.map(SchemaColumn::name)
        val fields = request.fields.ifEmpty { allFields }
        requireRequest(fields.isNotEmpty() || allFields.isEmpty(), "查询字段不能为空")
        requireRequest(fields.size <= 100, "查询字段最多 100 个")
        requireRequest(request.filters.size <= 20, "筛选条件最多 20 个")
        requireRequest(request.orderBy.size <= 10, "排序字段最多 10 个")
        requireRequest(request.limit in 1..PAGE_LIMIT, "limit 必须在 1 到 $PAGE_LIMIT 之间")
        requireRequest(request.offset in 0..(SOURCE_ROW_LIMIT - 1), "offset 超出可查询范围")
        requireRequest(request.offset + request.limit <= SOURCE_ROW_LIMIT, "offset + limit 不能超过 $SOURCE_ROW_LIMIT")

        val indexes = allFields.withIndex().associate { it.value to it.index }
        fields.forEach { requireField(it, indexes) }
        request.filters.forEach { filter ->
            requireField(filter.field, indexes)
            requireRequest(filter.operator in DatasetFilterOperator.entries.map { it.wireName }.toSet(), "不支持的筛选操作: ${filter.operator}")
            if (filter.operator in setOf(DatasetFilterOperator.IN.wireName, DatasetFilterOperator.NOT_IN.wireName)) {
                requireRequest(filter.values.isNotEmpty() || !filter.value.isNullOrBlank(), "${filter.operator} 需要 values")
            }
        }
        request.orderBy.forEach { sort ->
            requireField(sort.field, indexes)
            requireRequest(sort.direction.uppercase() in setOf("ASC", "DESC"), "排序方向只能是 ASC 或 DESC")
        }

        val projected = result.rows.mapIndexed { index, row -> QueryRow(index, row) }
            .filter { queryRow -> request.filters.all { matches(queryRow.values[indexes.getValue(it.field)], it) } }
            .let { rows -> sort(rows, request.orderBy, indexes) }
        val total = projected.size
        val selectedIndexes = fields.map(indexes::getValue)
        val page = projected.drop(request.offset).take(request.limit).map { queryRow -> selectedIndexes.map(queryRow.values::getOrNull) }
        val columns = fields.map { field -> result.columns[indexes.getValue(field)] }
        return StructuredDatasetQueryResult(columns, page, total, request.offset, request.limit, result.rows.size >= SOURCE_ROW_LIMIT)
    }

    private fun sort(rows: List<QueryRow>, orderBy: List<DatasetSortRequest>, indexes: Map<String, Int>): List<QueryRow> {
        if (orderBy.isEmpty()) return rows
        return rows.sortedWith { left, right ->
            for (sort in orderBy) {
                val comparison = compareValues(left.values[indexes.getValue(sort.field)], right.values[indexes.getValue(sort.field)])
                if (comparison != 0) return@sortedWith if (sort.direction.equals("DESC", true)) -comparison else comparison
            }
            left.index.compareTo(right.index)
        }
    }

    private fun matches(value: Any?, filter: DatasetFilterRequest): Boolean {
        val operator = DatasetFilterOperator.from(filter.operator)
        return when (operator) {
            DatasetFilterOperator.IS_NULL -> value == null
            DatasetFilterOperator.IS_NOT_NULL -> value != null
            DatasetFilterOperator.IN, DatasetFilterOperator.NOT_IN -> {
                val targets = filter.values.ifEmpty { listOfNotNull(filter.value) }
                val present = targets.any { equalsValue(value, it) }
                if (operator == DatasetFilterOperator.IN) present else !present
            }
            DatasetFilterOperator.LIKE -> wildcardMatches(value?.toString().orEmpty(), filter.value.orEmpty())
            DatasetFilterOperator.EQUALS -> equalsValue(value, filter.value)
            DatasetFilterOperator.NOT_EQUALS -> !equalsValue(value, filter.value)
            DatasetFilterOperator.GREATER_THAN -> value != null && compareValueToText(value, filter.value) > 0
            DatasetFilterOperator.GREATER_THAN_OR_EQUAL -> value != null && compareValueToText(value, filter.value) >= 0
            DatasetFilterOperator.LESS_THAN -> value != null && compareValueToText(value, filter.value) < 0
            DatasetFilterOperator.LESS_THAN_OR_EQUAL -> value != null && compareValueToText(value, filter.value) <= 0
        }
    }

    private fun equalsValue(value: Any?, expected: String?): Boolean = when {
        value == null -> expected == null
        expected == null -> false
        decimal(value)?.let { actual -> decimal(expected)?.let { actual.compareTo(it) == 0 } } != null -> true
        else -> value.toString() == expected
    }

    private fun compareValueToText(value: Any?, expected: String?): Int {
        requireRequest(value != null && expected != null, "比较筛选不支持空值")
        val left = decimal(value)
        val right = decimal(expected)
        return if (left != null && right != null) left.compareTo(right) else value.toString().compareTo(expected!!)
    }

    private fun compareValues(left: Any?, right: Any?): Int = when {
        left == null && right == null -> 0
        left == null -> 1
        right == null -> -1
        decimal(left) != null && decimal(right) != null -> decimal(left)!!.compareTo(decimal(right)!!)
        else -> left.toString().compareTo(right.toString())
    }

    private fun wildcardMatches(value: String, pattern: String): Boolean {
        val expression = buildString {
            append('^')
            pattern.forEach { character -> when (character) {
                '%' -> append(".*")
                '_' -> append('.')
                else -> append(Regex.escape(character.toString()))
            } }
            append('$')
        }
        return Regex(expression, setOf(RegexOption.DOT_MATCHES_ALL)).matches(value)
    }

    private fun decimal(value: Any?): BigDecimal? = runCatching { BigDecimal(value?.toString()) }.getOrNull()
    private fun requireField(field: String, indexes: Map<String, Int>) = requireRequest(field in indexes, "字段不存在: $field")
    private fun requireRequest(condition: Boolean, message: String) { if (!condition) throw DatasetValidationException(message) }
    private data class QueryRow(val index: Int, val values: List<Any?>)

    companion object { const val SOURCE_ROW_LIMIT = 10_000; const val PAGE_LIMIT = 1_000 }
}

class DatasetStructuredQueryRequest {
    var fields: List<String> = emptyList()
    var filters: List<DatasetFilterRequest> = emptyList()
    var orderBy: List<DatasetSortRequest> = emptyList()
    var limit: Int = 100
    var offset: Int = 0
    var variables: Map<String, String> = emptyMap()
}

class DatasetFilterRequest {
    var field: String = ""
    var operator: String = DatasetFilterOperator.EQUALS.wireName
    var value: String? = null
    var values: List<String> = emptyList()
}

class DatasetSortRequest { var field: String = ""; var direction: String = "ASC" }

data class StructuredDatasetQueryResult(
    val columns: List<SchemaColumn>, val rows: List<List<Any?>>, val total: Int, val offset: Int, val limit: Int,
    /** true means the portable source fetch reached its 10k protection limit. */
    val sourceLimitReached: Boolean,
)

enum class DatasetFilterOperator(val wireName: String) {
    EQUALS("equals"), NOT_EQUALS("not_equals"), GREATER_THAN("greater_than"), GREATER_THAN_OR_EQUAL("greater_than_or_equal"),
    LESS_THAN("less_than"), LESS_THAN_OR_EQUAL("less_than_or_equal"), IN("in"), NOT_IN("not_in"), LIKE("like"), IS_NULL("is_null"), IS_NOT_NULL("is_not_null");
    companion object { fun from(value: String) = entries.first { it.wireName == value } }
}

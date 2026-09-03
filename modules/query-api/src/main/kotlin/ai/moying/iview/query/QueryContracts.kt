package ai.moying.iview.query

enum class ComparisonOperator {
    EQUALS, NOT_EQUALS, GREATER_THAN, GREATER_THAN_OR_EQUAL,
    LESS_THAN, LESS_THAN_OR_EQUAL, IN, NOT_IN, LIKE, IS_NULL, IS_NOT_NULL,
}

enum class SortDirection { ASC, DESC }

data class QueryFilter(
    val field: String,
    val operator: ComparisonOperator,
    val value: Any? = null,
)

data class QuerySort(val field: String, val direction: SortDirection)

data class DatasetQuery(
    val datasetId: String,
    val fields: List<String>,
    val filters: List<QueryFilter> = emptyList(),
    val groupBy: List<String> = emptyList(),
    val orderBy: List<QuerySort> = emptyList(),
    val limit: Int? = null,
    val offset: Int = 0,
) {
    init {
        require(fields.isNotEmpty()) { "At least one field must be selected" }
        require(limit == null || limit > 0) { "Limit must be positive" }
        require(offset >= 0) { "Offset cannot be negative" }
    }
}

data class QueryColumn(val name: String, val logicalType: String, val nullable: Boolean)

data class QueryResult(
    val columns: List<QueryColumn>,
    val rows: List<List<Any?>>,
    val total: Long? = null,
)

interface DatasetQueryService {
    fun query(request: DatasetQuery): QueryResult
}

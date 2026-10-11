package ai.moying.iview.datasource

import ai.moying.iview.query.DataModelField
import ai.moying.iview.query.DatasetValidationException
import java.math.BigDecimal

data class HeatmapAggregateResult(
    val columns: List<SchemaColumn>,
    val rows: List<List<Any?>>,
    val totalGroups: Int,
    val truncated: Boolean,
)

/** Aggregates actual source rows. A bounded fallback refuses to claim complete sums at its source cap. */
object HeatmapAggregation {
    private const val MAX_GROUPS = 400
    private const val SOURCE_LIMIT = StructuredDatasetQueryService.SOURCE_ROW_LIMIT
    private const val SUM_ALIAS = "iview_heatmap_sum"

    fun validate(published: List<DataModelField>, x: String, y: String, value: String, allowTextualMeasure: Boolean = false) {
        val names = listOf(x, y, value)
        if (names.any(String::isBlank) || names.distinct().size != 3) throw DatasetValidationException("热力图必须指定三个不同的 X、Y、数值字段")
        if (names.any { name -> published.none { it.name == name } }) throw DatasetValidationException("热力图字段不在已发布模型结构中")
        val type = published.first { it.name == value }.type.lowercase()
        val numeric = Regex("^(int|smallint|bigint|tinyint|numeric|decimal|number|float|double|real|money|serial|long|short).*", RegexOption.IGNORE_CASE).matches(type)
        val textual = type.startsWith("varchar") || type.startsWith("text") || type.startsWith("char") || type == "unknown"
        if (!numeric && !(allowTextualMeasure && textual))
            throw DatasetValidationException("热力图数值字段必须是数值类型")
    }

    fun postgresRequest(x: String, y: String, value: String, variables: Map<String, String>) = DatasetStructuredQueryRequest().also { request ->
        request.filters = listOf(x, y).map { name -> DatasetFilterRequest().also { filter -> filter.field = name; filter.operator = DatasetFilterOperator.IS_NOT_BLANK.wireName } } +
            DatasetFilterRequest().also { filter -> filter.field = value; filter.operator = DatasetFilterOperator.IS_NOT_NULL.wireName }
        request.groupBy = listOf(x, y)
        request.aggregations = listOf(DatasetAggregationRequest().also { aggregation ->
            aggregation.field = value; aggregation.function = "SUM"; aggregation.alias = SUM_ALIAS
        })
        request.orderBy = listOf(x, y).map { name -> DatasetSortRequest().also { sort -> sort.field = name; sort.direction = "ASC" } }
        request.limit = MAX_GROUPS
        request.variables = variables
    }

    fun fromStructured(result: StructuredDatasetQueryResult, valueField: String): HeatmapAggregateResult {
        if (result.sourceLimitReached) throw DatasetValidationException("数据源达到安全行数上限，不能保证热力图汇总完整")
        val columns = result.columns.mapIndexed { index, column -> if (index == 2) column.copy(name = valueField) else column }
        return HeatmapAggregateResult(columns, result.rows, result.total, result.total > result.rows.size)
    }

    fun fromPortable(source: SqlResult, x: String, y: String, value: String): HeatmapAggregateResult {
        if (source.rows.size >= SOURCE_LIMIT) throw DatasetValidationException("数据源达到 $SOURCE_LIMIT 行上限，不能保证热力图汇总完整；请改用 PostgreSQL 聚合或缩小数据集")
        val names = source.columns.map { it.name }
        val xIndex = names.indexOf(x); val yIndex = names.indexOf(y); val valueIndex = names.indexOf(value)
        if (listOf(xIndex, yIndex, valueIndex).any { it < 0 }) throw DatasetValidationException("当前数据集缺少热力图绑定字段")
        val groups = linkedMapOf<Pair<String, String>, BigDecimal>()
        for (row in source.rows) {
            val rawX = row.getOrNull(xIndex)?.toString()?.takeIf(String::isNotBlank) ?: continue
            val rawY = row.getOrNull(yIndex)?.toString()?.takeIf(String::isNotBlank) ?: continue
            val amount = row.getOrNull(valueIndex)?.toString()?.toBigDecimalOrNull() ?: continue
            val key = rawX to rawY
            groups[key] = (groups[key] ?: BigDecimal.ZERO) + amount
        }
        val columns = listOf(source.columns[xIndex], source.columns[yIndex], SchemaColumn(value, "NUMERIC", true))
        return HeatmapAggregateResult(columns, groups.entries.take(MAX_GROUPS).map { (key, total) -> listOf(key.first, key.second, total) }, groups.size, groups.size > MAX_GROUPS)
    }
}

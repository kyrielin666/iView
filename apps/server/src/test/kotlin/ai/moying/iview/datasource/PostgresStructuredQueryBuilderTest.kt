package ai.moying.iview.datasource

import ai.moying.iview.query.DatasetValidationException
import java.math.BigDecimal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class PostgresStructuredQueryBuilderTest {
    private val schema = listOf(
        SchemaColumn("line", "varchar", false),
        SchemaColumn("quantity", "int4", false),
        SchemaColumn("state", "varchar", true),
    )

    @Test
    fun `pushes filters grouping having ordering and paging into postgres`() {
        val request = DatasetStructuredQueryRequest().also {
            it.filters = listOf(filter("state", "not_equals", "offline"))
            it.groupBy = listOf("line")
            it.aggregations = listOf(DatasetAggregationRequest().also { aggregation ->
                aggregation.field = "quantity"; aggregation.function = "sum"; aggregation.alias = "total_quantity"
            })
            it.having = listOf(filter("total_quantity", "greater_than", "10"))
            it.orderBy = listOf(DatasetSortRequest().also { sort -> sort.field = "total_quantity"; sort.direction = "DESC" })
            it.limit = 25; it.offset = 50
        }

        val query = PostgresStructuredQueryBuilder().build("select line, quantity, state from production where plant = ?", listOf("north"), schema, request)

        assertTrue(query.sql.contains("SUM(\"quantity\") AS \"total_quantity\""))
        assertTrue(query.sql.contains("GROUP BY \"line\" HAVING SUM(\"quantity\") > ?"))
        assertTrue(query.sql.endsWith("ORDER BY \"total_quantity\" DESC LIMIT 25 OFFSET 50"))
        assertTrue(query.countSql.startsWith("SELECT COUNT(*) FROM (SELECT"))
        assertEquals("north", query.values[0])
        assertEquals("offline", query.values[1])
        assertEquals(BigDecimal("10"), query.values[2])
    }

    @Test
    fun `rejects untrusted output aliases and unknown fields`() {
        val aliasInjection = DatasetStructuredQueryRequest().also {
            it.aggregations = listOf(DatasetAggregationRequest().also { aggregation -> aggregation.alias = "x; drop table users" })
        }
        assertFailsWith<DatasetValidationException> { PostgresStructuredQueryBuilder().build("select 1", emptyList(), schema, aliasInjection) }
        val unknown = DatasetStructuredQueryRequest().also { it.fields = listOf("missing") }
        assertFailsWith<DatasetValidationException> { PostgresStructuredQueryBuilder().build("select 1", emptyList(), schema, unknown) }
    }

    private fun filter(field: String, operator: String, value: String) = DatasetFilterRequest().also {
        it.field = field; it.operator = operator; it.value = value
    }
}

package ai.moying.iview.datasource

import ai.moying.iview.query.DataModelField
import ai.moying.iview.query.DatasetValidationException
import java.math.BigDecimal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class HeatmapAggregationTest {
    private val published = listOf(
        DataModelField("shift", "VARCHAR", false),
        DataModelField("machine", "VARCHAR", false),
        DataModelField("quantity", "INTEGER", true),
    )

    @Test fun `published schema gates all three fields before aggregation`() {
        HeatmapAggregation.validate(published, "shift", "machine", "quantity")
        assertFailsWith<DatasetValidationException> { HeatmapAggregation.validate(published, "shift", "machine", "secret") }
        assertFailsWith<DatasetValidationException> { HeatmapAggregation.validate(published, "shift", "shift", "quantity") }
        assertFailsWith<DatasetValidationException> { HeatmapAggregation.validate(published, "shift", "quantity", "machine") }
        HeatmapAggregation.validate(published.map { if (it.name == "quantity") it.copy(type = "VARCHAR") else it }, "shift", "machine", "quantity", true)
    }

    @Test fun `postgres request pushes SUM into grouped query instead of sampling first 100 rows`() {
        val request = HeatmapAggregation.postgresRequest("shift", "machine", "quantity", mapOf("plant" to "A"))
        assertEquals(listOf("shift", "machine"), request.groupBy)
        assertEquals("SUM", request.aggregations.single().function)
        assertEquals("quantity", request.aggregations.single().field)
        assertEquals(400, request.limit)
        assertEquals("A", request.variables["plant"])
        val sql = PostgresStructuredQueryBuilder().build("SELECT shift,machine,quantity FROM t", emptyList(),
            published.map { SchemaColumn(it.name, it.type, it.nullable) }, request)
        assertTrue(sql.sql.contains("SUM(\"quantity\")"))
        assertTrue(sql.sql.contains("GROUP BY \"shift\", \"machine\""))
        assertTrue(sql.sql.contains("BTRIM(CAST(\"shift\" AS TEXT))"))
        assertTrue(sql.sql.contains("\"quantity\" IS NOT NULL"))
    }

    @Test fun `portable aggregation includes rows beyond 100 and never converts null into zero`() {
        val rows = List(101) { listOf("早班", "一号机", 1) } + listOf(
            listOf("晚班", "一号机", 0), listOf("早班", "二号机", null), listOf("晚班", "二号机", -2),
        )
        val result = HeatmapAggregation.fromPortable(SqlResult(published.map { SchemaColumn(it.name, it.type, it.nullable) }, rows), "shift", "machine", "quantity")
        assertEquals(3, result.totalGroups)
        assertEquals(BigDecimal("101"), result.rows[0][2])
        assertEquals(BigDecimal.ZERO, result.rows[1][2])
        assertEquals(BigDecimal("-2"), result.rows[2][2])
        assertEquals(false, result.truncated)
    }

    @Test fun `portable source limit fails closed and grouped result marks truncation`() {
        val columns = published.map { SchemaColumn(it.name, it.type, it.nullable) }
        assertFailsWith<DatasetValidationException> {
            HeatmapAggregation.fromPortable(SqlResult(columns, List(10_000) { listOf("A", "M", 1) }), "shift", "machine", "quantity")
        }
        val grouped = HeatmapAggregation.fromStructured(StructuredDatasetQueryResult(columns, listOf(listOf("A", "M", 1)), 401, 0, 400, false), "quantity")
        assertTrue(grouped.truncated)
        assertEquals(401, grouped.totalGroups)
        assertEquals("quantity", grouped.columns[2].name)
    }
}

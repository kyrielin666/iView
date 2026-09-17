package ai.moying.iview.datasource

import ai.moying.iview.query.DatasetValidationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class StructuredDatasetQueryServiceTest {
    private val service = StructuredDatasetQueryService()
    private val source = SqlResult(
        listOf(SchemaColumn("line", "varchar", true), SchemaColumn("count", "int4", true), SchemaColumn("state", "varchar", true)),
        listOf(listOf("A01", 12, "running"), listOf("A02", 3, "idle"), listOf("A03", 20, "running"), listOf("A04", null, "offline")),
    )

    @Test
    fun `filters sorts projects and pages without changing source sql`() {
        val request = DatasetStructuredQueryRequest().also {
            it.fields = listOf("line", "count")
            it.filters = listOf(DatasetFilterRequest().also { filter -> filter.field = "state"; filter.operator = "equals"; filter.value = "running" })
            it.orderBy = listOf(DatasetSortRequest().also { sort -> sort.field = "count"; sort.direction = "DESC" })
            it.limit = 1
        }
        val result = service.execute(source, request)
        assertEquals(listOf("line", "count"), result.columns.map { it.name })
        assertEquals(listOf(listOf("A03", 20)), result.rows)
        assertEquals(2, result.total)
    }

    @Test
    fun `supports numeric comparisons lists and sql-like wildcards`() {
        val request = DatasetStructuredQueryRequest().also {
            it.filters = listOf(
                DatasetFilterRequest().also { filter -> filter.field = "count"; filter.operator = "greater_than"; filter.value = "10" },
                DatasetFilterRequest().also { filter -> filter.field = "line"; filter.operator = "like"; filter.value = "A0_" },
                DatasetFilterRequest().also { filter -> filter.field = "state"; filter.operator = "in"; filter.values = listOf("running") },
            )
        }
        assertEquals(listOf(listOf("A01", 12, "running"), listOf("A03", 20, "running")), service.execute(source, request).rows)
    }

    @Test
    fun `rejects unknown requested field`() {
        val request = DatasetStructuredQueryRequest().also { it.fields = listOf("line", "drop table") }
        assertFailsWith<DatasetValidationException> { service.execute(source, request) }
    }
}

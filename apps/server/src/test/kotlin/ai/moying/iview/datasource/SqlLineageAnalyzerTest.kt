package ai.moying.iview.datasource

import kotlin.test.Test
import kotlin.test.assertEquals

class SqlLineageAnalyzerTest {
    @Test fun `extracts base tables and named ctes without treating literals as relations`() {
        val relations = SqlLineageAnalyzer.relations("""
            WITH recent AS (SELECT * FROM factory.orders WHERE note = 'join not a relation')
            SELECT r.id, p.name FROM recent r JOIN "factory"."products" p ON p.id = r.product_id
        """.trimIndent())
        assertEquals(listOf(
            SqlLineageRelation("factory.orders", "BASE_TABLE"),
            SqlLineageRelation("recent", "CTE"),
            SqlLineageRelation("factory.products", "BASE_TABLE"),
        ), relations)
    }

    @Test fun `ignores relation keywords inside sql comments`() {
        assertEquals(listOf(SqlLineageRelation("actual_data", "BASE_TABLE")), SqlLineageAnalyzer.relations("""
            -- FROM phantom_table
            SELECT * FROM actual_data /* JOIN also_phantom */
        """.trimIndent()))
    }
}

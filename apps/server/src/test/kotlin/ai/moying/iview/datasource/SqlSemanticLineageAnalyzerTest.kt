package ai.moying.iview.datasource

import kotlin.test.*

class SqlSemanticLineageAnalyzerTest {
    private val tables = listOf(
        SqlCatalogTable("mes", "public", "orders", listOf("id", "product_id", "quantity", "price")),
        SqlCatalogTable("mes", "public", "products", listOf("id", "name")),
    )
    private fun analyze(sql: String) = SqlSemanticLineageAnalyzer.analyze(sql) { name ->
        tables.find { name == it.name || name == "${it.schema}.${it.name}" }
    }
    private fun refs(column: SqlLineageOutput) = column.sources.map { "${it.table}.${it.column}" }.toSet()

    @Test fun `expressions resolve multiple inputs through ctes and aliases`() {
        val result = analyze("WITH sales AS (SELECT product_id, quantity * price AS amount FROM orders) SELECT p.name, sum(s.amount) AS revenue FROM sales s JOIN products p ON p.id=s.product_id GROUP BY p.name")
        assertEquals(setOf("products.name"), refs(result.outputs[0]))
        assertEquals(setOf("orders.quantity", "orders.price"), refs(result.outputs[1]))
        assertTrue(result.outputs.all { it.status == "RESOLVED" })
        assertTrue(result.controlSources.any { it.table == "products" && it.column == "id" })
    }

    @Test fun `derived tables and union branches resolve by output ordinal`() {
        val result = analyze("SELECT x.value FROM (SELECT quantity AS value FROM orders UNION ALL SELECT price AS different_name FROM orders) x")
        assertEquals(setOf("orders.quantity", "orders.price"), refs(result.outputs.single()))
    }

    @Test fun `wildcards expand against ordered catalog columns`() {
        val result = analyze("SELECT p.*, o.quantity FROM products p JOIN orders o ON p.id=o.product_id")
        assertEquals(listOf("id", "name", "quantity"), result.outputs.map { it.name })
        assertEquals(setOf("products.name"), refs(result.outputs[1]))
    }

    @Test fun `ambiguous columns and unavailable catalogs are not claimed resolved`() {
        assertEquals("UNRESOLVED", analyze("SELECT id FROM orders o JOIN products p ON o.product_id=p.id").outputs.single().status)
        assertEquals("UNRESOLVED", analyze("SELECT x FROM unavailable").outputs.single().status)
    }

    @Test fun `constants have no fabricated sources and identifiers retain case`() {
        assertEquals("CONSTANT", analyze("SELECT 1 AS one").outputs.single().status)
        val result = SqlSemanticLineageAnalyzer.analyze("SELECT \"T\".\"Value\" FROM \"CaseTable\" \"T\"") { name ->
            if (name == "\"CaseTable\"") SqlCatalogTable("mes", "public", "CaseTable", listOf("Value")) else null
        }
        assertEquals("Value", result.outputs.single().sources.single().column)
    }

    @Test fun `recursive ctes report unsupported semantics instead of fabricated completeness`() {
        val result = analyze("WITH RECURSIVE n(x) AS (SELECT 1 UNION ALL SELECT x+1 FROM n WHERE x<3) SELECT x FROM n")
        assertTrue(result.warnings.isNotEmpty())
        assertFalse(result.outputs.any { it.status == "RESOLVED" })
    }

    @Test fun `case dependencies include predicate and branch inputs`() {
        val result = analyze("SELECT CASE WHEN quantity>0 THEN price ELSE 0 END AS effective_price FROM orders")
        assertEquals(setOf("orders.quantity", "orders.price"), refs(result.outputs.single()))
    }

    @Test fun `syntax errors return explicit unsupported result`() {
        assertTrue(analyze("SELECT FROM (").warnings.isNotEmpty())
    }

    @Test fun `count star through a derived query retains row dependencies`() {
        val field = analyze("WITH data AS (SELECT id FROM orders) SELECT count(*) AS total FROM data").outputs.single()
        assertEquals("RESOLVED", field.status)
        assertEquals(listOf("mes.public.orders"), field.rowSources)
        assertTrue(field.sources.isEmpty())
    }

    @Test fun `nested aliases shadow outer aliases and correlated predicates resolve outer fields`() {
        val result = analyze("SELECT (SELECT max(p.price) FROM orders p WHERE p.product_id=o.id) AS amount FROM products o")
        assertEquals(setOf("orders.price"), refs(result.outputs.single()))
        assertTrue(result.controlSources.any { it.table == "products" && it.column == "id" })
        assertTrue(result.warnings.isEmpty())
    }

    @Test fun `explicit cte and derived aliases preserve underlying field references`() {
        val cte = analyze("WITH x(q,p) AS (SELECT quantity,price FROM orders) SELECT q*p AS total FROM x")
        assertEquals(setOf("orders.quantity", "orders.price"), refs(cte.outputs.single()))
        val derived = analyze("SELECT d.q FROM (SELECT quantity FROM orders) d(q)")
        assertEquals(setOf("orders.quantity"), refs(derived.outputs.single()))
    }

    @Test fun `unknown functions and windows advertise incomplete semantics`() {
        assertEquals("PARTIAL", analyze("SELECT custom_func(price) AS result FROM orders").outputs.single().status)
        assertEquals("PARTIAL", analyze("SELECT sum(price) OVER (PARTITION BY product_id) FROM orders").outputs.single().status)
    }

    @Test fun `distinct on and grouping sets retain control dependencies`() {
        val distinct = analyze("SELECT DISTINCT ON (product_id) price FROM orders ORDER BY product_id, price")
        assertTrue(distinct.controlSources.any { it.column == "product_id" })
        val grouped = analyze("SELECT sum(price) FROM orders GROUP BY GROUPING SETS ((product_id), ())")
        assertTrue(grouped.controlSources.any { it.column == "product_id" })
    }

    @Test fun `postgres function labels resolve through derived tables without explicit aliases`() {
        val result = analyze("SELECT totals.sum FROM (SELECT sum(price) FROM orders) totals")
        assertEquals(setOf("orders.price"), refs(result.outputs.single()))
    }

    @Test fun `unquoted non ascii identifiers do not collapse distinct catalog columns`() {
        val result = SqlSemanticLineageAnalyzer.analyze("SELECT Ä FROM case_table") { name ->
            if (name == "case_table") SqlCatalogTable("mes", "public", "case_table", listOf("Ä", "ä")) else null
        }
        assertEquals("Ä", result.outputs.single().sources.single().column)
    }
}

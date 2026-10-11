package ai.moying.iview.datasource

import kotlin.test.*

class DatasetLineageAssemblerTest {
    @Test fun `multiple inputs and duplicate output aliases retain distinct graph nodes`() {
        val fields = List(2) { DatasetLineageField("value", null, null, null, null, "DERIVED_OR_DRIVER_UNAVAILABLE") }
        val semantic = SqlSemanticLineageAnalyzer.analyze("SELECT quantity*price AS value, id AS value FROM orders") {
            SqlCatalogTable("mes", "public", "orders", listOf("id", "quantity", "price"))
        }
        val result = DatasetLineageAssembler.assemble(1, fields, semantic)
        assertEquals(3, result.edges.size)
        assertEquals(2, result.nodes.filter { it.kind == "output" }.distinctBy { it.id }.size)
        assertEquals(2, result.edges.count { it.target.startsWith("output:0:") })
    }

    @Test fun `mismatched projections do not attach semantic references to unrelated output`() {
        val fields = listOf(DatasetLineageField("actual", null, null, null, null, "DERIVED_OR_DRIVER_UNAVAILABLE"))
        val result = DatasetLineageAssembler.assemble(1, fields, SqlSemanticLineage(emptyList(), emptyList(), emptyList(), emptyList()))
        assertTrue(result.edges.isEmpty())
        assertTrue("OUTPUT_SCHEMA_MISMATCH" in result.semantic!!.warnings)
    }
}

package ai.moying.iview.query

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class DatasetSqlTest {
    @Test fun `schema and explain wrap only verified select SQL`() {
        assertEquals("EXPLAIN (FORMAT JSON) SELECT id FROM orders", DatasetSql.explainQuery("SELECT id FROM orders"))
        assertEquals("SELECT * FROM (SELECT id FROM orders) iview_dataset_schema WHERE 1=0", DatasetSql.schemaQuery("SELECT id FROM orders"))
        assertFailsWith<QueryValidationException> { DatasetSql.explainQuery("DELETE FROM orders") }
    }
}

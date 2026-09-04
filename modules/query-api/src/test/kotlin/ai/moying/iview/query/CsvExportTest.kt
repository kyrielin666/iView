package ai.moying.iview.query

import kotlin.test.Test
import kotlin.test.assertEquals

class CsvExportTest {
    @Test fun `escapes csv delimiters quotes and line breaks`() {
        assertEquals("name,note\nA,\"x,\"\"y\"\"\"\n", CsvExport.render(listOf("name", "note"), listOf(listOf("A", "x,\"y\""))))
    }
}

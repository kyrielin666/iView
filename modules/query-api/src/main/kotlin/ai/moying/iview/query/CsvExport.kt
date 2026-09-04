package ai.moying.iview.query

object CsvExport {
    fun render(headers: List<String>, rows: List<List<Any?>>): String = buildString {
        append(headers.joinToString(",", transform = ::escape)).append('\n')
        rows.forEach { row -> append(row.joinToString(",") { escape(it?.toString().orEmpty()) }).append('\n') }
    }
    private fun escape(value: String): String = if (value.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) "\"${value.replace("\"", "\"\"")}\"" else value
}

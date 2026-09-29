package ai.moying.iview.datasource

/**
 * Conservative relation extractor for PostgreSQL read-only dataset SQL.
 *
 * It deliberately reports relations, not fabricated field-level mappings: JDBC metadata remains
 * the authoritative source for fields, while this parser makes CTE and join inputs visible when
 * a driver cannot attribute a derived output column.
 */
data class SqlLineageRelation(val name: String, val kind: String)

object SqlLineageAnalyzer {
    private val cte = Regex("""(?is)(?:\bwith|,)\s*((?:\"[^\"]+\")|(?:[a-z_][a-z0-9_$]*))\s+as\s*\(""")
    private val relation = Regex("""(?is)\b(from|join)\s+((?:\"[^\"]+\"|[a-z_][a-z0-9_$]*)(?:\s*\.\s*(?:\"[^\"]+\"|[a-z_][a-z0-9_$]*)){0,2})""")
    private val strings = Regex("""'(?:''|[^'])*'|\$\$.*?\$\$""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
    private val comments = Regex("""--[^\r\n]*|/\*.*?\*/""", setOf(RegexOption.DOT_MATCHES_ALL))

    fun relations(sql: String): List<SqlLineageRelation> {
        val statement = comments.replace(strings.replace(sql) { "''" }) { " " }
        val ctes = cte.findAll(statement).map { identifier(it.groupValues[1]) }.toSet()
        return relation.findAll(statement).map { identifier(it.groupValues[2]) }.filter { it.isNotBlank() }
            .map { name -> SqlLineageRelation(name, if (name in ctes) "CTE" else "BASE_TABLE") }
            .distinctBy { "${it.kind}:${it.name.lowercase()}" }.toList()
    }

    private fun identifier(value: String) = value.split('.').joinToString(".") { part -> part.trim().removeSurrounding("\"") }
}

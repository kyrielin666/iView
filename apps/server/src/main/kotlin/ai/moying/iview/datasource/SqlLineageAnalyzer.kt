package ai.moying.iview.datasource

/**
 * Relation-only compatibility entry point. Field dependencies use the semantic analyzer with
 * a real catalog resolver; unavailable catalogs here never produce fabricated field mappings.
 */
data class SqlLineageRelation(val name: String, val kind: String)

object SqlLineageAnalyzer {
    fun relations(sql: String): List<SqlLineageRelation> = SqlSemanticLineageAnalyzer.analyze(sql) { null }.relations
}

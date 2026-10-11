package ai.moying.iview.datasource

import net.sf.jsqlparser.expression.*
import net.sf.jsqlparser.expression.Function
import net.sf.jsqlparser.parser.CCJSqlParserUtil
import net.sf.jsqlparser.schema.Column
import net.sf.jsqlparser.schema.Table
import net.sf.jsqlparser.statement.select.*
import java.util.Locale

data class SqlCatalogTable(val catalog: String?, val schema: String?, val name: String, val columns: List<String>)
data class SqlSourceColumn(val catalog: String?, val schema: String?, val table: String, val column: String)
data class SqlLineageOutput(
    val name: String, val expression: String, val sources: List<SqlSourceColumn>, val status: String,
    val warnings: List<String> = emptyList(), val rowSources: List<String> = emptyList(),
)
data class SqlSemanticLineage(
    val outputs: List<SqlLineageOutput>, val relations: List<SqlLineageRelation>,
    val controlSources: List<SqlSourceColumn>, val warnings: List<String>,
)

/** Static dependencies of supported SELECT syntax. Function bodies and view definitions are opaque.
 * Resolution is per query scope; missing catalogs and ambiguous columns never become exact edges.
 */
object SqlSemanticLineageAnalyzer {
    fun analyze(sql: String, catalog: (String) -> SqlCatalogTable?): SqlSemanticLineage {
        if (sql.length > 100_000) return empty("SQL_TOO_LARGE")
        val statement = try { CCJSqlParserUtil.parse(sql) { it.withTimeOut(3_000) } }
        catch (_: Exception) { return empty("SQL_PARSE_UNSUPPORTED") }
        if (statement !is Select) return empty("ONLY_SELECT_SUPPORTED")
        return Analysis(catalog).run(statement)
    }

    private fun empty(warning: String) = SqlSemanticLineage(emptyList(), emptyList(), emptyList(), listOf(warning))
    private fun identifier(value: String): String = if (value.startsWith('"') && value.endsWith('"'))
        value.substring(1, value.length - 1).replace("\"\"", "\"")
    else value.map { if (it in 'A'..'Z') it.lowercaseChar() else it }.joinToString("")
    private fun qualified(table: Table) = listOfNotNull(table.database?.databaseName?.takeIf(String::isNotBlank), table.schemaName, table.name)
        .joinToString(".", transform = ::identifier)

    private data class Relation(val qualifiers: Set<String>, val columns: List<SqlLineageOutput>, val rows: Set<String>, val known: Boolean = true)
    private class Scope(val relations: MutableList<Relation> = mutableListOf(), val parent: Scope? = null) {
        fun resolve(column: Column): SqlLineageOutput {
            val name = identifier(column.columnName)
            val qualifier = column.table?.name?.takeIf(String::isNotBlank)?.let { qualified(column.table) }
            val candidates = relations.filter { qualifier == null || qualifier in it.qualifiers }
            val matches = candidates.flatMap { it.columns }.filter { it.name == name }
            if (candidates.none { !it.known } && matches.size == 1) return matches.single()
            if (matches.isEmpty() && candidates.none { !it.known } && (qualifier == null || candidates.isEmpty())) {
                parent?.let { return it.resolve(column) }
            }
            return SqlLineageOutput(name, column.toString(), emptyList(), "UNRESOLVED", listOf("UNRESOLVED_COLUMN: $column"))
        }
    }

    private class Analysis(private val catalog: (String) -> SqlCatalogTable?) {
        private val relations = linkedSetOf<SqlLineageRelation>()
        private val controls = linkedSetOf<SqlSourceColumn>()
        private val warnings = linkedSetOf<String>()
        private var depth = 0
        fun run(select: Select): SqlSemanticLineage {
            val outputs = select(select, emptyMap(), null)
            return SqlSemanticLineage(outputs, relations.toList(), controls.toList(), (warnings + outputs.flatMap { it.warnings }).toList())
        }

        private fun select(select: Select, inherited: Map<String, Relation>, parent: Scope?): List<SqlLineageOutput> {
            if (++depth > 32) { depth--; warnings += "QUERY_NESTING_LIMIT"; return emptyList() }
            try {
                val ctes = inherited.toMutableMap()
                for (with in select.withItemsList.orEmpty()) {
                    val name = identifier(with.aliasName)
                    if (with.isRecursive || with.select == null) {
                        warnings += "UNSUPPORTED_CTE: $name"
                        ctes[name] = Relation(setOf(name), emptyList(), emptySet(), false)
                    } else {
                        var outputs = select(with.select, ctes, parent)
                        val aliases = with.withItemList.orEmpty().map { identifier(it.expression.toString()) }
                        if (aliases.isNotEmpty()) {
                            if (aliases.size != outputs.size) warnings += "CTE_COLUMN_COUNT: $name"
                            outputs = outputs.mapIndexed { index, field -> field.copy(name = aliases.getOrElse(index) { field.name }) }
                        }
                        ctes[name] = Relation(setOf(name), outputs, outputs.flatMap { it.rowSources }.toSet())
                    }
                }
                return when (select) {
                    is ParenthesedSelect -> select(select.select, ctes, parent)
                    is PlainSelect -> plain(select, ctes, parent)
                    is SetOperationList -> {
                        if (select.operations.any { it !is UnionOp }) { warnings += "UNSUPPORTED_SET_OPERATION"; emptyList() }
                        else {
                            val branches = select.selects.map { select(it, ctes, parent) }
                            val first = branches.firstOrNull().orEmpty()
                            if (branches.any { it.size != first.size }) { warnings += "SET_COLUMN_COUNT"; emptyList() }
                            else first.mapIndexed { index, output ->
                                val fields = branches.map { it[index] }
                                output.copy(sources = fields.flatMap { it.sources }.distinct(), rowSources = fields.flatMap { it.rowSources }.distinct(),
                                    warnings = fields.flatMap { it.warnings }.distinct(), status = status(fields.flatMap { it.sources }, fields.flatMap { it.warnings }, fields.flatMap { it.rowSources }))
                            }
                        }
                    }
                    else -> { warnings += "UNSUPPORTED_SELECT: ${select.javaClass.simpleName}"; emptyList() }
                }
            } finally { depth-- }
        }

        private fun from(item: FromItem?, scope: Scope, ctes: Map<String, Relation>): Relation? {
            if (item == null) return null
            val alias = item.alias?.name?.let(::identifier)
            val aliases = item.alias?.aliasColumns.orEmpty().map { identifier(it.name) }
            val result = when (item) {
                is Table -> {
                    val name = identifier(item.name)
                    val cte = if (item.schemaName == null) ctes[name] else null
                    if (cte != null) {
                        relations += SqlLineageRelation(name, "CTE")
                        cte.copy(qualifiers = setOf(alias ?: name))
                    } else {
                        relations += SqlLineageRelation(qualified(item), "BASE_TABLE")
                        val table = catalog(item.fullyQualifiedName)
                        val qualifiers = if (alias != null) setOf(alias) else setOf(name, qualified(item))
                        if (table == null) Relation(qualifiers, emptyList(), setOf(qualified(item)), false)
                        else {
                            val row = listOfNotNull(table.catalog, table.schema, table.name).joinToString(".")
                            Relation(qualifiers, table.columns.map { field -> SqlLineageOutput(field, field,
                                listOf(SqlSourceColumn(table.catalog, table.schema, table.name, field)), "RESOLVED") }, setOf(row))
                        }
                    }
                }
                is ParenthesedSelect -> {
                    val outputs = select(item.select, ctes, if (item is LateralSubSelect) scope else scope.parent)
                    Relation(setOfNotNull(alias), outputs, outputs.flatMap { it.rowSources }.toSet(), outputs.isNotEmpty())
                }
                else -> { warnings += "UNSUPPORTED_FROM: ${item.javaClass.simpleName}"; Relation(setOfNotNull(alias), emptyList(), emptySet(), false) }
            }
            return if (aliases.isEmpty()) result else {
                if (aliases.size != result.columns.size) warnings += "ALIAS_COLUMN_COUNT"
                result.copy(columns = result.columns.mapIndexed { index, field -> field.copy(name = aliases.getOrElse(index) { field.name }) })
            }
        }

        private fun plain(select: PlainSelect, ctes: Map<String, Relation>, parent: Scope?): List<SqlLineageOutput> {
            val scope = Scope(parent = parent)
            from(select.fromItem, scope, ctes)?.let(scope.relations::add)
            val localWarnings = mutableListOf<String>()
            for (join in select.joins.orEmpty()) {
                from(join.rightItem, scope, ctes)?.let(scope.relations::add)
                if (join.isNatural || !join.usingColumns.isNullOrEmpty()) localWarnings += "JOIN_USING_OR_NATURAL_REQUIRES_MERGED_COLUMNS"
                join.onExpressions.orEmpty().forEach { control(it, scope, ctes) }
            }
            control(select.where, scope, ctes)
            control(select.having, scope, ctes)
            select.groupBy?.groupByExpressionList?.forEach { control(it, scope, ctes) }
            select.groupBy?.groupingSets.orEmpty().forEach { control(it, scope, ctes) }
            select.distinct?.onSelectItems.orEmpty().forEach { control(it.expression, scope, ctes) }
            control(select.limit?.rowCount, scope, ctes)
            control(select.limit?.offset, scope, ctes)
            control(select.offset?.offset, scope, ctes)
            val outputs = select.selectItems.flatMap { item ->
                val expression = item.expression
                when (expression) {
                    is AllColumns -> {
                        val candidates = if (expression is AllTableColumns) scope.relations.filter { qualified(expression.table) in it.qualifiers } else scope.relations
                        if (candidates.isEmpty() || candidates.any { !it.known }) {
                            listOf(SqlLineageOutput("*", expression.toString(), emptyList(), "UNRESOLVED", listOf("WILDCARD_CATALOG_UNAVAILABLE")))
                        } else candidates.flatMap { it.columns }
                    }
                    else -> expression(expression, scope, ctes).copy(name = item.alias?.name?.let(::identifier) ?: when (expression) {
                        is Column -> identifier(expression.columnName)
                        is Function -> identifier(expression.name.substringAfterLast('.'))
                        is CaseExpression -> "case"
                        else -> expression.toString()
                    }).let(::listOf)
                }
            }.map { output ->
                output.copy(rowSources = (output.rowSources + scope.relations.flatMap { it.rows }).distinct(),
                    status = if (localWarnings.isEmpty()) output.status else "PARTIAL", warnings = output.warnings + localWarnings)
            }
            // ORDER BY may refer to projection aliases, unlike WHERE and JOIN ON.
            val outputScope = Scope(mutableListOf(Relation(emptySet(), outputs, emptySet())), scope)
            select.orderByElements.orEmpty().forEach { control(it.expression, outputScope, ctes) }
            return outputs
        }

        private fun control(expression: Expression?, scope: Scope, ctes: Map<String, Relation>) {
            if (expression == null) return
            val output = expression(expression, scope, ctes)
            controls += output.sources
            warnings += output.warnings
        }

        private fun expression(expression: Expression, scope: Scope, ctes: Map<String, Relation>): SqlLineageOutput {
            val sources = linkedSetOf<SqlSourceColumn>()
            val problems = linkedSetOf<String>()
            val rows = linkedSetOf<String>()
            val visitor = object : ExpressionVisitorAdapter<Void>() {
                override fun <S : Any?> visit(column: Column, context: S): Void? {
                    val field = scope.resolve(column)
                    sources += field.sources; problems += field.warnings; rows += field.rowSources
                    return null
                }
                override fun <S : Any?> visit(select: ParenthesedSelect, context: S): Void? {
                    return visit(select as Select, context)
                }
                override fun <S : Any?> visit(select: Select, context: S): Void? {
                    val before = warnings.toSet()
                    val outputs = select(select, ctes, scope)
                    if (outputs.isEmpty()) problems += "SUBQUERY_UNRESOLVED"
                    outputs.forEach { sources += it.sources; problems += it.warnings; rows += it.rowSources }
                    problems += warnings - before
                    return null
                }
                override fun <S : Any?> visit(all: AllColumns, context: S): Void? {
                    rows += scope.relations.flatMap { it.rows }
                    if (scope.relations.any { !it.known }) problems += "ROW_SOURCE_UNRESOLVED"
                    return null
                }
                override fun <S : Any?> visit(function: Function, context: S): Void? {
                    if (function.name.lowercase(Locale.ROOT) !in SAFE_FUNCTIONS) problems += "FUNCTION_BODY_OPAQUE: ${function.name}"
                    return super.visit(function, context)
                }
                override fun <S : Any?> visit(analytic: AnalyticExpression, context: S): Void? {
                    // Named windows and frame clauses need separate binding; preserve visible dependencies.
                    problems += "WINDOW_SEMANTICS_PARTIAL"
                    return super.visit(analytic, context)
                }
            }
            expression.accept(visitor, null)
            val literal = expression is LongValue || expression is DoubleValue || expression is StringValue || expression is NullValue || expression is BooleanValue
            val resultStatus = if (literal) "CONSTANT" else status(sources.toList(), problems.toList(), rows.toList())
            return SqlLineageOutput("", expression.toString(), sources.toList(), resultStatus, problems.toList(), rows.toList())
        }

        private fun status(sources: List<SqlSourceColumn>, problems: List<String>, rows: List<String>) = when {
            problems.isNotEmpty() -> if (sources.isEmpty() && rows.isEmpty()) "UNRESOLVED" else "PARTIAL"
            sources.isNotEmpty() || rows.isNotEmpty() -> "RESOLVED"
            else -> "DERIVED"
        }
    }
    private val SAFE_FUNCTIONS = setOf("sum", "count", "avg", "min", "max", "coalesce", "nullif", "abs", "round", "floor", "ceil", "ceiling", "lower", "upper", "length", "substring", "concat", "trim", "date_trunc", "date_part", "greatest", "least", "string_agg", "array_agg", "bool_and", "bool_or")
}

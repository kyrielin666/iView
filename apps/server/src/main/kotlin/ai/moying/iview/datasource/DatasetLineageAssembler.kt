package ai.moying.iview.datasource

object DatasetLineageAssembler {
    fun assemble(datasetId: Long, fields: List<DatasetLineageField>, semantic: SqlSemanticLineage?): DatasetLineage {
        val aligned = semantic != null && semantic.outputs.size == fields.size
        val analysis = if (semantic != null && !aligned) semantic.copy(warnings = (semantic.warnings + "OUTPUT_SCHEMA_MISMATCH").distinct())
            else semantic?.copy(outputs = semantic.outputs.mapIndexed { index, output -> output.copy(name = fields[index].outputField) })
        val sources = linkedMapOf<String, DatasetLineageNode>()
        val edges = mutableListOf<DatasetLineageEdge>()
        val outputs = fields.mapIndexed { index, field ->
            val id = "output:$index:${field.outputField}"
            val references = if (aligned) semantic!!.outputs[index].sources else
                if (field.sourceTable != null && field.sourceField != null) listOf(SqlSourceColumn(field.sourceCatalog, field.sourceSchema, field.sourceTable, field.sourceField)) else emptyList()
            references.forEach { source ->
                val parts = listOf(source.catalog, source.schema, source.table, source.column)
                // Length-prefixed identifiers avoid collisions between quoted names containing dots.
                val sourceId = "source:" + parts.joinToString(":") { it?.let { value -> "${value.length}:$value" } ?: "-" }
                sources[sourceId] = DatasetLineageNode(sourceId, "source", parts.filterNotNull().joinToString("."))
                edges += DatasetLineageEdge(sourceId, id, if (aligned) "AST_${semantic!!.outputs[index].status}" else field.confidence)
            }
            DatasetLineageNode(id, "output", field.outputField)
        }
        val relations = semantic?.relations.orEmpty()
        val tableCount = if (semantic != null) relations.filter { it.kind == "BASE_TABLE" }.distinct().size else fields.mapNotNull { it.sourceTable }.distinct().size
        return DatasetLineage(datasetId, fields, sources.values.toList() + outputs, edges, relations,
            DatasetLineageSummary(fields.size, sources.size, tableCount, fields.count { it.sourceTable == null }),
            if (semantic == null) "当前数据源提供来源结构信息，不代表 SQL 或跨数据源语义血缘。" else
                "静态 SQL 血缘按查询作用域解析多来源表达式；筛选/关联依赖单列于 control_sources。请同时查看解析状态与警告；视图内部、窗口细节、递归及跨数据源不保证完整。", analysis)
    }
}

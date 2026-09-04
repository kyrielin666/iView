package ai.moying.iview.query

import java.time.Instant

data class Dataset(
    val id: Long,
    val sourceId: Long,
    val folderId: Long?,
    val name: String,
    val sql: String,
    val description: String,
    val createdAt: Instant,
    val updatedAt: Instant,
)

data class DatasetDraft(
    val sourceId: Long,
    val folderId: Long? = null,
    val name: String,
    val sql: String,
    val description: String = "",
)

data class DatasetFolder(
    val id: Long,
    val name: String,
    val parentId: Long?,
    val description: String,
    val createdAt: Instant,
    val updatedAt: Instant,
)

data class DatasetFolderDraft(val name: String, val parentId: Long? = null, val description: String = "")

interface DatasetRepository {
    fun list(sourceId: Long? = null, folderId: Long? = null): List<Dataset>
    fun find(id: Long): Dataset?
    fun create(draft: DatasetDraft): Dataset
    fun update(id: Long, draft: DatasetDraft): Dataset?
    fun delete(id: Long): Boolean
    fun move(id: Long, folderId: Long?): Dataset?
    fun listFolders(): List<DatasetFolder>
    fun findFolder(id: Long): DatasetFolder?
    fun createFolder(draft: DatasetFolderDraft): DatasetFolder
    fun updateFolder(id: Long, draft: DatasetFolderDraft): DatasetFolder?
    fun deleteFolder(id: Long): Boolean
    fun countDatasetsInFolder(id: Long): Long
    fun countChildFolders(id: Long): Long
}

class DatasetNotFoundException(message: String) : DataSourceException(message)
class DatasetValidationException(message: String) : DataSourceException(message)

class DatasetService(private val repository: DatasetRepository, private val sources: DataSourceService) {
    fun list(sourceId: Long? = null, folderId: Long? = null) = repository.list(sourceId, folderId)
    fun get(id: Long) = repository.find(id) ?: throw DatasetNotFoundException("数据集不存在: $id")

    fun create(draft: DatasetDraft): Dataset {
        val normalized = normalize(draft)
        sources.get(normalized.sourceId)
        return repository.create(normalized)
    }

    fun update(id: Long, draft: DatasetDraft): Dataset {
        get(id)
        val normalized = normalize(draft)
        sources.get(normalized.sourceId)
        return repository.update(id, normalized) ?: throw DatasetNotFoundException("数据集不存在: $id")
    }

    fun delete(id: Long) {
        if (!repository.delete(id)) throw DatasetNotFoundException("数据集不存在: $id")
    }

    fun copy(id: Long, name: String): Dataset {
        val current = get(id)
        return create(DatasetDraft(current.sourceId, current.folderId, name, current.sql, current.description))
    }

    fun move(id: Long, folderId: Long?): Dataset {
        get(id)
        folderId?.let(::getFolder)
        return repository.move(id, folderId) ?: throw DatasetNotFoundException("数据集不存在: $id")
    }

    fun listFolders() = repository.listFolders()
    fun getFolder(id: Long) = repository.findFolder(id) ?: throw DatasetNotFoundException("数据集文件夹不存在: $id")
    fun createFolder(draft: DatasetFolderDraft): DatasetFolder {
        val normalized = normalizeFolder(draft)
        normalized.parentId?.let(::getFolder)
        return repository.createFolder(normalized)
    }
    fun updateFolder(id: Long, draft: DatasetFolderDraft): DatasetFolder {
        getFolder(id)
        val normalized = normalizeFolder(draft)
        validateFolderParent(id, normalized.parentId)
        return repository.updateFolder(id, normalized) ?: throw DatasetNotFoundException("数据集文件夹不存在: $id")
    }
    fun deleteFolder(id: Long) {
        getFolder(id)
        if (repository.countDatasetsInFolder(id) > 0 || repository.countChildFolders(id) > 0) throw DatasetValidationException("文件夹非空，不能删除")
        if (!repository.deleteFolder(id)) throw DatasetNotFoundException("数据集文件夹不存在: $id")
    }

    private fun normalize(draft: DatasetDraft): DatasetDraft {
        val normalized = draft.copy(name = draft.name.trim(), sql = draft.sql.trim(), description = draft.description.trim())
        if (normalized.sourceId <= 0) throw DatasetValidationException("数据集必须选择一个数据源")
        if (normalized.name.isBlank() || normalized.name.length > 100) throw DatasetValidationException("数据集名称不能为空且不能超过100位")
        try { SafeSql.selectOnly(normalized.sql) } catch (error: QueryValidationException) { throw DatasetValidationException(error.message ?: "数据集 SQL 无效") }
        normalized.folderId?.let(::getFolder)
        return normalized
    }
    private fun normalizeFolder(draft: DatasetFolderDraft) = draft.copy(name = draft.name.trim(), description = draft.description.trim()).also {
        if (it.name.isBlank() || it.name.length > 100) throw DatasetValidationException("文件夹名称不能为空且不能超过100位")
    }
    private fun validateFolderParent(id: Long, parentId: Long?) {
        var cursor = parentId
        var depth = 0
        while (cursor != null) {
            if (cursor == id) throw DatasetValidationException("文件夹不能移动到自身或子文件夹中")
            if (++depth > 100) throw DatasetValidationException("文件夹层级超过100级")
            cursor = getFolder(cursor).parentId
        }
    }
}

object DatasetSql {
    fun schemaQuery(sql: String) = "SELECT * FROM (${SafeSql.selectOnly(sql)}) iview_dataset_schema WHERE 1=0"
    fun explainQuery(sql: String) = "EXPLAIN (FORMAT JSON) ${SafeSql.selectOnly(sql)}"
    fun bindVariables(sql: String, variables: Map<String, String>): BoundSql {
        if (variables.size > 50 || variables.any { (key, value) -> !variableName.matches(key) || value.length > 500 }) throw DatasetValidationException("查询变量无效")
        val values = mutableListOf<String>()
        val bound = placeholder.replace(sql) { match ->
            val name = match.groupValues[1]
            values += variables[name] ?: throw DatasetValidationException("缺少查询变量: $name")
            "?"
        }
        return BoundSql(bound, values)
    }
    private val placeholder = Regex("\\{\\{([A-Za-z_][A-Za-z0-9_]{0,63})\\}\\}")
    private val variableName = Regex("[A-Za-z_][A-Za-z0-9_]{0,63}")
}

data class BoundSql(val sql: String, val values: List<String>)

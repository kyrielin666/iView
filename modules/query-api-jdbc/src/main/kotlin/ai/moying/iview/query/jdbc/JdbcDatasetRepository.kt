package ai.moying.iview.query.jdbc

import ai.moying.iview.query.Dataset
import ai.moying.iview.query.DatasetDraft
import ai.moying.iview.query.DatasetFolder
import ai.moying.iview.query.DatasetFolderDraft
import ai.moying.iview.query.DatasetRepository
import org.springframework.jdbc.core.RowMapper
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.jdbc.core.simple.SimpleJdbcInsert
import org.springframework.stereotype.Repository
import java.sql.ResultSet
import java.time.Instant

@Repository
class JdbcDatasetRepository(private val jdbc: NamedParameterJdbcTemplate) : DatasetRepository {
    private val datasetInsert by lazy { SimpleJdbcInsert(jdbc.jdbcTemplate).withTableName("iview_dataset").usingGeneratedKeyColumns("id").usingColumns("source_id", "folder_id", "dataset_name", "query_sql", "description") }
    private val folderInsert by lazy { SimpleJdbcInsert(jdbc.jdbcTemplate).withTableName("iview_dataset_folder").usingGeneratedKeyColumns("id").usingColumns("folder_name", "parent_id", "description") }
    override fun list(sourceId: Long?, folderId: Long?): List<Dataset> {
        val clauses = mutableListOf<String>(); val params = mutableMapOf<String, Any>()
        sourceId?.let { clauses += "source_id=:sourceId"; params["sourceId"] = it }
        folderId?.let { clauses += "folder_id=:folderId"; params["folderId"] = it }
        val where = if (clauses.isEmpty()) "" else " WHERE ${clauses.joinToString(" AND ")}"
        return jdbc.query("SELECT * FROM iview_dataset$where ORDER BY id DESC", params, datasetMapper)
    }
    override fun find(id: Long): Dataset? = jdbc.query("SELECT * FROM iview_dataset WHERE id=:id", mapOf("id" to id), datasetMapper).firstOrNull()
    override fun create(draft: DatasetDraft): Dataset { val id = datasetInsert.executeAndReturnKey(datasetValues(draft)).toLong(); return requireNotNull(find(id)) }
    override fun update(id: Long, draft: DatasetDraft): Dataset? {
        val count = jdbc.update("""UPDATE iview_dataset SET source_id=:source_id, folder_id=:folder_id, dataset_name=:dataset_name,
            query_sql=:query_sql, description=:description, updated_at=CURRENT_TIMESTAMP WHERE id=:id""", datasetValues(draft) + ("id" to id))
        return if (count == 0) null else find(id)
    }
    override fun delete(id: Long) = jdbc.update("DELETE FROM iview_dataset WHERE id=:id", mapOf("id" to id)) > 0
    override fun move(id: Long, folderId: Long?): Dataset? {
        val count = jdbc.update("UPDATE iview_dataset SET folder_id=:folderId, updated_at=CURRENT_TIMESTAMP WHERE id=:id", mapOf("id" to id, "folderId" to folderId))
        return if (count == 0) null else find(id)
    }
    override fun listFolders(): List<DatasetFolder> = jdbc.query("SELECT * FROM iview_dataset_folder ORDER BY id", emptyMap<String, Any>(), folderMapper)
    override fun findFolder(id: Long): DatasetFolder? = jdbc.query("SELECT * FROM iview_dataset_folder WHERE id=:id", mapOf("id" to id), folderMapper).firstOrNull()
    override fun createFolder(draft: DatasetFolderDraft): DatasetFolder { val id = folderInsert.executeAndReturnKey(folderValues(draft)).toLong(); return requireNotNull(findFolder(id)) }
    override fun updateFolder(id: Long, draft: DatasetFolderDraft): DatasetFolder? {
        val count = jdbc.update("UPDATE iview_dataset_folder SET folder_name=:folder_name, parent_id=:parent_id, description=:description, updated_at=CURRENT_TIMESTAMP WHERE id=:id", folderValues(draft) + ("id" to id))
        return if (count == 0) null else findFolder(id)
    }
    override fun deleteFolder(id: Long) = jdbc.update("DELETE FROM iview_dataset_folder WHERE id=:id", mapOf("id" to id)) > 0
    override fun countDatasetsInFolder(id: Long) = jdbc.queryForObject("SELECT COUNT(*) FROM iview_dataset WHERE folder_id=:id", mapOf("id" to id), Long::class.java) ?: 0
    override fun countChildFolders(id: Long) = jdbc.queryForObject("SELECT COUNT(*) FROM iview_dataset_folder WHERE parent_id=:id", mapOf("id" to id), Long::class.java) ?: 0
    private fun datasetValues(draft: DatasetDraft) = mapOf("source_id" to draft.sourceId, "folder_id" to draft.folderId, "dataset_name" to draft.name, "query_sql" to draft.sql, "description" to draft.description)
    private fun folderValues(draft: DatasetFolderDraft) = mapOf("folder_name" to draft.name, "parent_id" to draft.parentId, "description" to draft.description)
    private val datasetMapper = RowMapper { rs, _ -> Dataset(rs.getLong("id"), rs.getLong("source_id"), rs.nullableLong("folder_id"), rs.getString("dataset_name"), rs.getString("query_sql"), rs.getString("description"), instant(rs, "created_at"), instant(rs, "updated_at")) }
    private val folderMapper = RowMapper { rs, _ -> DatasetFolder(rs.getLong("id"), rs.getString("folder_name"), rs.nullableLong("parent_id"), rs.getString("description"), instant(rs, "created_at"), instant(rs, "updated_at")) }
    private fun ResultSet.nullableLong(column: String) = getLong(column).let { if (wasNull()) null else it }
    private fun instant(rs: ResultSet, column: String): Instant = rs.getTimestamp(column).toInstant()
}

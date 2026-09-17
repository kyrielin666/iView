package ai.moying.iview.query.jdbc

import ai.moying.iview.query.*
import org.springframework.jdbc.core.RowMapper
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.jdbc.core.simple.SimpleJdbcInsert
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Transactional
import java.sql.ResultSet
import java.time.Instant

@Repository
class JdbcDashboardRepository(private val jdbc: NamedParameterJdbcTemplate) : DashboardRepository {
    private val dashboardInsert by lazy { insert("iview_dashboard", "folder_id", "model_id", "dashboard_name", "description", "content_json") }
    private val folderInsert by lazy { insert("iview_dashboard_folder", "folder_name", "parent_id") }
    private val snapshotInsert by lazy { insert("iview_dashboard_snapshot", "dashboard_id", "snapshot_version", "content_json", "model_id") }
    override fun list(folderId: Long?) = jdbc.query(if (folderId == null) "SELECT * FROM iview_dashboard ORDER BY id DESC" else "SELECT * FROM iview_dashboard WHERE folder_id=:id ORDER BY id DESC", if (folderId == null) emptyMap<String, Any>() else mapOf("id" to folderId), dashboardMapper)
    override fun find(id: Long) = jdbc.query("SELECT * FROM iview_dashboard WHERE id=:id", mapOf("id" to id), dashboardMapper).firstOrNull()
    override fun create(draft: DashboardDraft): Dashboard { val id = dashboardInsert.executeAndReturnKey(values(draft)).toLong(); return requireNotNull(find(id)) }
    override fun update(id: Long, draft: DashboardDraft): Dashboard? { val n = jdbc.update("UPDATE iview_dashboard SET folder_id=:folder_id, model_id=:model_id, dashboard_name=:dashboard_name, description=:description, content_json=:content_json, updated_at=CURRENT_TIMESTAMP WHERE id=:id", values(draft) + ("id" to id)); return if(n==0)null else find(id) }
    @Transactional
    override fun delete(id: Long): Boolean {
        jdbc.update("UPDATE iview_dashboard SET published_snapshot_id=NULL WHERE id=:id", mapOf("id" to id))
        return jdbc.update("DELETE FROM iview_dashboard WHERE id=:id", mapOf("id" to id)) > 0
    }
    override fun listFolders() = jdbc.query("SELECT * FROM iview_dashboard_folder ORDER BY id", emptyMap<String, Any>(), folderMapper)
    override fun findFolder(id: Long) = jdbc.query("SELECT * FROM iview_dashboard_folder WHERE id=:id", mapOf("id" to id), folderMapper).firstOrNull()
    override fun createFolder(draft: DashboardFolderDraft): DashboardFolder { val id = folderInsert.executeAndReturnKey(mapOf("folder_name" to draft.name, "parent_id" to draft.parentId)).toLong(); return requireNotNull(findFolder(id)) }
    override fun updateFolder(id: Long, draft: DashboardFolderDraft): DashboardFolder? { val n = jdbc.update("UPDATE iview_dashboard_folder SET folder_name=:name,parent_id=:parent,updated_at=CURRENT_TIMESTAMP WHERE id=:id", mapOf("id" to id, "name" to draft.name, "parent" to draft.parentId)); return if (n == 0) null else findFolder(id) }
    override fun deleteFolder(id: Long) = jdbc.update("DELETE FROM iview_dashboard_folder WHERE id=:id", mapOf("id" to id)) > 0
    override fun countDashboardsInFolder(id: Long) = jdbc.queryForObject("SELECT COUNT(*) FROM iview_dashboard WHERE folder_id=:id", mapOf("id" to id), Long::class.java) ?: 0
    override fun countChildFolders(id: Long) = jdbc.queryForObject("SELECT COUNT(*) FROM iview_dashboard_folder WHERE parent_id=:id", mapOf("id" to id), Long::class.java) ?: 0
    override fun listSnapshots(dashboardId: Long) = jdbc.query("SELECT * FROM iview_dashboard_snapshot WHERE dashboard_id=:id ORDER BY snapshot_version DESC", mapOf("id" to dashboardId), snapshotMapper)
    override fun createSnapshot(dashboard: Dashboard): DashboardSnapshot { val version = (listSnapshots(dashboard.id).firstOrNull()?.version ?: 0) + 1; val id = snapshotInsert.executeAndReturnKey(mapOf("dashboard_id" to dashboard.id, "snapshot_version" to version, "content_json" to dashboard.contentJson, "model_id" to dashboard.modelId)).toLong(); return jdbc.query("SELECT * FROM iview_dashboard_snapshot WHERE id=:id", mapOf("id" to id), snapshotMapper).first() }
    override fun markPublished(id: Long, snapshotId: Long): Dashboard? { jdbc.update("UPDATE iview_dashboard SET dashboard_status='PUBLISHED',published_snapshot_id=:snapshot,updated_at=CURRENT_TIMESTAMP WHERE id=:id", mapOf("id" to id, "snapshot" to snapshotId)); return find(id) }
    private fun values(d: DashboardDraft) = mapOf("folder_id" to d.folderId, "model_id" to d.modelId, "dashboard_name" to d.name, "description" to d.description, "content_json" to d.contentJson)
    private fun insert(table:String,vararg cols:String)=SimpleJdbcInsert(jdbc.jdbcTemplate).withTableName(table).usingGeneratedKeyColumns("id").usingColumns(*cols)
    private val dashboardMapper=RowMapper{rs,_->Dashboard(rs.getLong("id"),rs.longOrNull("folder_id"),rs.longOrNull("model_id"),rs.getString("dashboard_name"),rs.getString("description"),rs.getString("content_json"),DashboardStatus.valueOf(rs.getString("dashboard_status")),rs.longOrNull("published_snapshot_id"),time(rs,"created_at"),time(rs,"updated_at"))}
    private val folderMapper=RowMapper{rs,_->DashboardFolder(rs.getLong("id"),rs.getString("folder_name"),rs.longOrNull("parent_id"),time(rs,"created_at"),time(rs,"updated_at"))}
    private val snapshotMapper=RowMapper{rs,_->DashboardSnapshot(rs.getLong("id"),rs.getLong("dashboard_id"),rs.getInt("snapshot_version"),rs.getString("content_json"),rs.longOrNull("model_id"),time(rs,"created_at"))}
    private fun ResultSet.longOrNull(c:String)=getLong(c).let{if(wasNull())null else it}; private fun time(rs:ResultSet,c:String):Instant=rs.getTimestamp(c).toInstant()
}

package ai.moying.iview.query

import java.time.Instant

enum class DashboardStatus { DRAFT, PUBLISHED }
data class DashboardFolder(val id: Long, val name: String, val parentId: Long?, val createdAt: Instant, val updatedAt: Instant)
data class DashboardFolderDraft(val name: String, val parentId: Long? = null)
data class Dashboard(
    val id: Long, val folderId: Long?, val modelId: Long?, val name: String, val description: String,
    val contentJson: String, val status: DashboardStatus, val publishedSnapshotId: Long?, val createdAt: Instant, val updatedAt: Instant,
)
data class DashboardDraft(val folderId: Long? = null, val modelId: Long? = null, val name: String, val description: String = "", val contentJson: String = "{}")
data class DashboardSnapshot(val id: Long, val dashboardId: Long, val version: Int, val contentJson: String, val modelId: Long?, val createdAt: Instant)

interface DashboardRepository {
    fun list(folderId: Long? = null): List<Dashboard>; fun find(id: Long): Dashboard?; fun create(draft: DashboardDraft): Dashboard; fun update(id: Long, draft: DashboardDraft): Dashboard?; fun delete(id: Long): Boolean
    fun listFolders(): List<DashboardFolder>; fun findFolder(id: Long): DashboardFolder?; fun createFolder(draft: DashboardFolderDraft): DashboardFolder; fun updateFolder(id: Long, draft: DashboardFolderDraft): DashboardFolder?; fun deleteFolder(id: Long): Boolean; fun countDashboardsInFolder(id: Long): Long; fun countChildFolders(id: Long): Long
    fun listSnapshots(dashboardId: Long): List<DashboardSnapshot>; fun createSnapshot(dashboard: Dashboard): DashboardSnapshot; fun markPublished(id: Long, snapshotId: Long): Dashboard?
}
class DashboardNotFoundException(message: String) : DataSourceException(message)
class DashboardValidationException(message: String) : DataSourceException(message)

class DashboardService(private val repository: DashboardRepository, private val modelExists: (Long) -> Unit) {
    fun list(folderId: Long? = null) = repository.list(folderId); fun get(id: Long) = repository.find(id) ?: throw DashboardNotFoundException("看板不存在: $id")
    fun create(draft: DashboardDraft): Dashboard { val normalized = normalize(draft); return repository.create(normalized) }
    fun update(id: Long, draft: DashboardDraft): Dashboard { get(id); val normalized = normalize(draft); return repository.update(id, normalized) ?: throw DashboardNotFoundException("看板不存在: $id") }
    fun delete(id: Long) { if (!repository.delete(id)) throw DashboardNotFoundException("看板不存在: $id") }
    fun replaceContent(id: Long, contentJson: String): Dashboard {
        val current = get(id)
        return update(id, DashboardDraft(current.folderId, current.modelId, current.name, current.description, contentJson))
    }
    fun copy(id: Long, name: String): Dashboard {
        val current = get(id)
        return create(DashboardDraft(current.folderId, current.modelId, name, current.description, current.contentJson))
    }
    fun move(id: Long, folderId: Long?): Dashboard {
        val current = get(id)
        folderId?.let(::getFolder)
        return update(id, DashboardDraft(folderId, current.modelId, current.name, current.description, current.contentJson))
    }
    fun publish(id: Long): Dashboard { val snapshot = repository.createSnapshot(get(id)); return repository.markPublished(id, snapshot.id) ?: throw DashboardNotFoundException("看板不存在: $id") }
    fun snapshots(id: Long) = repository.listSnapshots(get(id).id)
    fun published(id: Long): DashboardSnapshot {
        val dashboard = get(id); val snapshotId = dashboard.publishedSnapshotId ?: throw DashboardValidationException("看板尚未发布")
        return snapshots(id).firstOrNull { it.id == snapshotId } ?: throw DashboardNotFoundException("发布快照不存在: $snapshotId")
    }
    fun listFolders() = repository.listFolders(); fun getFolder(id: Long) = repository.findFolder(id) ?: throw DashboardNotFoundException("看板文件夹不存在: $id")
    fun createFolder(draft: DashboardFolderDraft): DashboardFolder { val normalized = normalizeFolder(draft); normalized.parentId?.let(::getFolder); return repository.createFolder(normalized) }
    fun updateFolder(id: Long, draft: DashboardFolderDraft): DashboardFolder { getFolder(id); val normalized = normalizeFolder(draft); validateParent(id, normalized.parentId); return repository.updateFolder(id, normalized) ?: throw DashboardNotFoundException("看板文件夹不存在: $id") }
    fun deleteFolder(id: Long) { getFolder(id); if (repository.countDashboardsInFolder(id) > 0 || repository.countChildFolders(id) > 0) throw DashboardValidationException("文件夹非空，不能删除"); if (!repository.deleteFolder(id)) throw DashboardNotFoundException("看板文件夹不存在: $id") }
    private fun normalize(draft: DashboardDraft) = draft.copy(name = draft.name.trim(), description = draft.description.trim(), contentJson = draft.contentJson.trim()).also {
        if (it.name.isBlank() || it.name.length > 100) throw DashboardValidationException("看板名称不能为空且不能超过100位")
        if (it.contentJson.isBlank() || it.contentJson.length > 2_000_000) throw DashboardValidationException("看板内容不能为空且不能超过2MB")
        it.folderId?.let(::getFolder); it.modelId?.let(modelExists)
    }
    private fun normalizeFolder(draft: DashboardFolderDraft) = draft.copy(name = draft.name.trim()).also { if (it.name.isBlank() || it.name.length > 100) throw DashboardValidationException("文件夹名称不能为空且不能超过100位") }
    private fun validateParent(id: Long, parentId: Long?) { var cursor = parentId; var depth = 0; while (cursor != null) { if (cursor == id) throw DashboardValidationException("文件夹不能移动到自身或子文件夹中"); if (++depth > 100) throw DashboardValidationException("文件夹层级超过100级"); cursor = getFolder(cursor).parentId } }
}

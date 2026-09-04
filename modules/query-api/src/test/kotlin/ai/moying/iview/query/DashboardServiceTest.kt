package ai.moying.iview.query

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals

class DashboardServiceTest {
    @Test fun `published snapshots remain immutable when draft changes`() {
        val service = DashboardService(Memory(), {})
        val dashboard = service.create(DashboardDraft(name = "生产概览", contentJson = "{\"title\":\"v1\"}"))
        service.publish(dashboard.id)
        service.update(dashboard.id, DashboardDraft(name = "生产概览", contentJson = "{\"title\":\"v2\"}"))
        service.publish(dashboard.id)
        val snapshots = service.snapshots(dashboard.id)
        assertEquals(listOf("{\"title\":\"v2\"}", "{\"title\":\"v1\"}"), snapshots.map { it.contentJson })
    }
    private class Memory : DashboardRepository {
        private var dashboard: Dashboard? = null; private val snapshots = mutableListOf<DashboardSnapshot>()
        override fun list(folderId: Long?) = listOfNotNull(dashboard); override fun find(id: Long) = dashboard?.takeIf { it.id == id }
        override fun create(d: DashboardDraft) = Dashboard(1,d.folderId,d.modelId,d.name,d.description,d.contentJson,DashboardStatus.DRAFT,null,Instant.EPOCH,Instant.EPOCH).also { dashboard=it }
        override fun update(id:Long,d:DashboardDraft)=dashboard?.copy(folderId=d.folderId,modelId=d.modelId,name=d.name,description=d.description,contentJson=d.contentJson)?.also{dashboard=it}; override fun delete(id:Long)=false
        override fun listFolders()=emptyList<DashboardFolder>();override fun findFolder(id:Long)=null;override fun createFolder(d:DashboardFolderDraft)=throw UnsupportedOperationException();override fun updateFolder(id:Long,d:DashboardFolderDraft)=null;override fun deleteFolder(id:Long)=false;override fun countDashboardsInFolder(id:Long)=0L;override fun countChildFolders(id:Long)=0L
        override fun listSnapshots(dashboardId:Long)=snapshots.sortedByDescending{it.version}; override fun createSnapshot(d:Dashboard)=DashboardSnapshot(snapshots.size+1L,d.id,snapshots.size+1,d.contentJson,d.modelId,Instant.EPOCH).also{snapshots+=it}; override fun markPublished(id:Long,snapshotId:Long)=dashboard?.copy(status=DashboardStatus.PUBLISHED,publishedSnapshotId=snapshotId)?.also{dashboard=it}
    }
}

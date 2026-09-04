package ai.moying.iview.query

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertEquals

class DatasetServiceTest {
    @Test fun `dataset refuses write SQL before it is stored`() {
        val sourceRepository = SourceRepository()
        val sources = DataSourceService(sourceRepository, PlainCipher())
        sources.create(DataSourceDraft("source", DataSourceType.POSTGRESQL, "jdbc:postgresql://db/a", "reader", "password"))
        val datasets = DatasetService(DatasetRepositoryFake(), sources)
        assertFailsWith<DatasetValidationException> { datasets.create(DatasetDraft(sourceId = 1, name = "订单", sql = "UPDATE orders SET status='x'")) }
    }

    @Test fun `copy and move preserve query while non empty folders cannot be deleted`() {
        val sources = DataSourceService(SourceRepository(), PlainCipher())
        sources.create(DataSourceDraft("source", DataSourceType.POSTGRESQL, "jdbc:postgresql://db/a", "reader", "password"))
        val datasets = DatasetService(MemoryDatasets(), sources)
        val folder = datasets.createFolder(DatasetFolderDraft("生产"))
        val original = datasets.create(DatasetDraft(1, folder.id, "订单", "SELECT id FROM orders"))
        val copied = datasets.copy(original.id, "订单副本")
        val moved = datasets.move(copied.id, null)
        assertEquals(original.sql, copied.sql)
        assertEquals(null, moved.folderId)
        assertFailsWith<DatasetValidationException> { datasets.deleteFolder(folder.id) }
    }

    private class PlainCipher : DataSourceSecretCipher { override fun encrypt(plainText: String) = plainText; override fun decrypt(cipherText: String) = cipherText }
    private class SourceRepository : DataSourceRepository {
        private var item: StoredDataSource? = null
        override fun list() = item?.let { listOf(it.source) } ?: emptyList()
        override fun find(id: Long) = item?.takeIf { it.source.id == id }
        override fun create(draft: DataSourceDraft, passwordCipher: String): DataSource {
            val source = DataSource(1, draft.name, draft.type, draft.jdbcUrl, draft.username, draft.description, Instant.EPOCH, Instant.EPOCH)
            item = StoredDataSource(source, passwordCipher); return source
        }
        override fun update(id: Long, draft: DataSourceDraft, passwordCipher: String?) = null
        override fun delete(id: Long) = false
    }
    private class DatasetRepositoryFake : DatasetRepository {
        override fun list(sourceId: Long?, folderId: Long?) = emptyList<Dataset>()
        override fun find(id: Long) = null
        override fun create(draft: DatasetDraft) = throw UnsupportedOperationException()
        override fun update(id: Long, draft: DatasetDraft) = null
        override fun delete(id: Long) = false
        override fun move(id: Long, folderId: Long?) = null
        override fun listFolders() = emptyList<DatasetFolder>()
        override fun findFolder(id: Long) = null
        override fun createFolder(draft: DatasetFolderDraft) = throw UnsupportedOperationException()
        override fun updateFolder(id: Long, draft: DatasetFolderDraft) = null
        override fun deleteFolder(id: Long) = false
        override fun countDatasetsInFolder(id: Long) = 0L
        override fun countChildFolders(id: Long) = 0L
    }
    private class MemoryDatasets : DatasetRepository {
        private var datasetId = 1L; private var folderId = 1L
        private val datasets = linkedMapOf<Long, Dataset>(); private val folders = linkedMapOf<Long, DatasetFolder>()
        override fun list(sourceId: Long?, folderId: Long?) = datasets.values.filter { (sourceId == null || it.sourceId == sourceId) && (folderId == null || it.folderId == folderId) }
        override fun find(id: Long) = datasets[id]
        override fun create(draft: DatasetDraft): Dataset = Dataset(datasetId++, draft.sourceId, draft.folderId, draft.name, draft.sql, draft.description, Instant.EPOCH, Instant.EPOCH).also { datasets[it.id] = it }
        override fun update(id: Long, draft: DatasetDraft) = datasets[id]?.copy(sourceId = draft.sourceId, folderId = draft.folderId, name = draft.name, sql = draft.sql, description = draft.description)?.also { datasets[id] = it }
        override fun delete(id: Long) = datasets.remove(id) != null
        override fun move(id: Long, folderId: Long?) = datasets[id]?.copy(folderId = folderId)?.also { datasets[id] = it }
        override fun listFolders() = folders.values.toList()
        override fun findFolder(id: Long) = folders[id]
        override fun createFolder(draft: DatasetFolderDraft): DatasetFolder = DatasetFolder(folderId++, draft.name, draft.parentId, draft.description, Instant.EPOCH, Instant.EPOCH).also { folders[it.id] = it }
        override fun updateFolder(id: Long, draft: DatasetFolderDraft) = folders[id]?.copy(name = draft.name, parentId = draft.parentId, description = draft.description)?.also { folders[id] = it }
        override fun deleteFolder(id: Long) = folders.remove(id) != null
        override fun countDatasetsInFolder(id: Long) = datasets.values.count { it.folderId == id }.toLong()
        override fun countChildFolders(id: Long) = folders.values.count { it.parentId == id }.toLong()
    }
}

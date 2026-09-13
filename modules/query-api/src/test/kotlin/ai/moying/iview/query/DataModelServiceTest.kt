package ai.moying.iview.query

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class DataModelServiceTest {
    @Test fun `published model is marked drifted only when its schema changes`() {
        val sources = DataSourceService(SourceMemory(), PlainCipher())
        sources.create(DataSourceDraft("s", DataSourceType.POSTGRESQL, "jdbc:postgresql://db/x", "u", "p"))
        val datasets = DatasetService(DatasetMemory(), sources)
        val repository = ModelMemory()
        val service = DataModelService(repository, datasets)
        val model = service.create(DataModelDraft(1, "订单模型"))
        service.sync(model.id, listOf(DataModelField("id", "bigint", false)))
        assertEquals(DataModelStatus.PUBLISHED, service.publish(model.id).status)
        assertEquals(false, service.sync(model.id, listOf(DataModelField("id", "BIGINT", false))).changed)
        val changed = service.sync(model.id, listOf(DataModelField("id", "BIGINT", false), DataModelField("created_at", "timestamp", true)))
        assertEquals(true, changed.changed)
        assertEquals(DataModelStatus.DRIFTED, changed.model.status)
        assertEquals(2, changed.schema.version)
    }

    @Test fun `publish plan reports breaking drift and rejects stale confirmation`() {
        val sources = DataSourceService(SourceMemory(), PlainCipher())
        sources.create(DataSourceDraft("s", DataSourceType.POSTGRESQL, "jdbc:postgresql://db/x", "u", "p"))
        val service = DataModelService(ModelMemory(), DatasetService(DatasetMemory(), sources))
        val model = service.create(DataModelDraft(1, "订单模型"))
        service.sync(model.id, listOf(DataModelField("id", "BIGINT", false), DataModelField("remark", "VARCHAR", true)))
        service.publish(model.id, 1)
        service.sync(model.id, listOf(DataModelField("id", "VARCHAR", false), DataModelField("created_at", "TIMESTAMP", true)))

        val plan = service.publishPlan(model.id)
        assertEquals(2, plan.latestSchema.version)
        assertEquals(2, plan.breakingChanges)
        assertEquals(true, plan.requiresPublish)
        assertFailsWith<DataModelValidationException> { service.publish(model.id, 1) }
        assertEquals(DataModelStatus.PUBLISHED, service.publish(model.id, 2).status)
    }

    private class PlainCipher : DataSourceSecretCipher { override fun encrypt(plainText: String) = plainText; override fun decrypt(cipherText: String) = cipherText }
    private class SourceMemory : DataSourceRepository {
        private var source: StoredDataSource? = null
        override fun list() = source?.let { listOf(it.source) } ?: emptyList(); override fun find(id: Long) = source?.takeIf { it.source.id == id }
        override fun create(draft: DataSourceDraft, passwordCipher: String): DataSource = DataSource(1, draft.name, draft.type, draft.jdbcUrl, draft.username, draft.description, Instant.EPOCH, Instant.EPOCH).also { source = StoredDataSource(it, passwordCipher) }
        override fun update(id: Long, draft: DataSourceDraft, passwordCipher: String?) = null; override fun delete(id: Long) = false
    }
    private class DatasetMemory : DatasetRepository {
        override fun list(sourceId: Long?, folderId: Long?) = listOf(find(1)!!); override fun find(id: Long) = if (id == 1L) Dataset(1, 1, null, "orders", "SELECT id FROM orders", "", Instant.EPOCH, Instant.EPOCH) else null
        override fun create(draft: DatasetDraft) = throw UnsupportedOperationException(); override fun update(id: Long, draft: DatasetDraft) = null; override fun delete(id: Long) = false; override fun move(id: Long, folderId: Long?) = null
        override fun listFolders() = emptyList<DatasetFolder>(); override fun findFolder(id: Long) = null; override fun createFolder(draft: DatasetFolderDraft) = throw UnsupportedOperationException(); override fun updateFolder(id: Long, draft: DatasetFolderDraft) = null; override fun deleteFolder(id: Long) = false; override fun countDatasetsInFolder(id: Long) = 0L; override fun countChildFolders(id: Long) = 0L
    }
    private class ModelMemory : DataModelRepository {
        private var item: DataModel? = null; private val schemas = mutableListOf<DataModelSchema>()
        override fun list() = listOfNotNull(item); override fun find(id: Long) = item?.takeIf { it.id == id }
        override fun create(draft: DataModelDraft) = DataModel(1, draft.datasetId, draft.name, draft.description, DataModelStatus.DRAFT, 0, null, Instant.EPOCH, Instant.EPOCH).also { item = it }
        override fun update(id: Long, draft: DataModelDraft) = item?.copy(datasetId = draft.datasetId, name = draft.name, description = draft.description)?.also { item = it }
        override fun delete(id: Long) = false; override fun latestSchema(modelId: Long) = schemas.lastOrNull(); override fun listSchemas(modelId: Long) = schemas.toList()
        override fun appendSchema(modelId: Long, fields: List<DataModelField>, fingerprint: String) = DataModelSchema(schemas.size + 1L, modelId, schemas.size + 1, fields, fingerprint, Instant.EPOCH).also { schemas += it; item = item!!.copy(latestSchemaVersion = it.version) }
        override fun markDrifted(id: Long, latestSchemaVersion: Int) = item?.copy(status = DataModelStatus.DRIFTED, latestSchemaVersion = latestSchemaVersion)?.also { item = it }
        override fun publish(id: Long, schemaVersion: Int) = item?.copy(status = DataModelStatus.PUBLISHED, publishedSchemaVersion = schemaVersion)?.also { item = it }
    }
}

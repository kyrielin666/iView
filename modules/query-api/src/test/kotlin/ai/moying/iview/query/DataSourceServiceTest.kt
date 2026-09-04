package ai.moying.iview.query

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import java.time.Instant

class DataSourceServiceTest {
    @Test fun `persists only encrypted password and retains it when update omits password`() {
        val repository = FakeRepository()
        val service = DataSourceService(repository, ReverseCipher())
        val created = service.create(DataSourceDraft("生产库", DataSourceType.POSTGRESQL, "jdbc:postgresql://db/production", "reader", "secret"))
        service.update(created.id, DataSourceDraft("生产库 2", DataSourceType.POSTGRESQL, "jdbc:postgresql://db/production", "reader"))
        assertEquals("terces", repository.record!!.passwordCipher)
        assertEquals("secret", service.credential(created.id).password)
    }

    @Test fun `new source requires password and postgres url`() {
        val service = DataSourceService(FakeRepository(), ReverseCipher())
        assertFailsWith<DataSourceValidationException> { service.create(DataSourceDraft("x", DataSourceType.POSTGRESQL, "jdbc:h2:mem:x", "sa", "")) }
    }

    private class ReverseCipher : DataSourceSecretCipher {
        override fun encrypt(plainText: String) = plainText.reversed()
        override fun decrypt(cipherText: String) = cipherText.reversed()
    }
    private class FakeRepository : DataSourceRepository {
        var record: StoredDataSource? = null
        override fun list() = record?.let { listOf(it.source) } ?: emptyList()
        override fun find(id: Long) = record?.takeIf { it.source.id == id }
        override fun create(draft: DataSourceDraft, passwordCipher: String): DataSource {
            val source = DataSource(1, draft.name, draft.type, draft.jdbcUrl, draft.username, draft.description, Instant.EPOCH, Instant.EPOCH)
            record = StoredDataSource(source, passwordCipher); return source
        }
        override fun update(id: Long, draft: DataSourceDraft, passwordCipher: String?): DataSource? {
            val current = record ?: return null
            val source = current.source.copy(name = draft.name, jdbcUrl = draft.jdbcUrl, username = draft.username, description = draft.description)
            record = StoredDataSource(source, passwordCipher ?: current.passwordCipher); return source
        }
        override fun delete(id: Long) = false
    }
}

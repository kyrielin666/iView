package ai.moying.iview.datasource

import ai.moying.iview.query.DataSource
import ai.moying.iview.query.DataSourceDraft
import ai.moying.iview.query.DataSourceRepository
import ai.moying.iview.query.DataSourceSecretCipher
import ai.moying.iview.query.DataSourceService
import ai.moying.iview.query.DataSourceType
import ai.moying.iview.query.DatasetValidationException
import ai.moying.iview.query.StoredDataSource
import com.fasterxml.jackson.databind.ObjectMapper
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class DataSourceExecutionServiceTest {
    @TempDir lateinit var directory: Path

    @Test
    fun `reads quoted csv inside configured file root`() {
        Files.writeString(directory.resolve("production.csv"), "line,quantity,note\nA01,12,\"good, stable\"\nA02,9,waiting\n")
        val execution = service(DataSourceType.FILE, "production.csv")

        val result = execution.querySource(1, "{}", 100)

        assertEquals(listOf("line", "quantity", "note"), result.columns.map { it.name })
        assertEquals(listOf("A01", "12", "good, stable"), result.rows.first())
        assertEquals(2, result.rows.size)
    }

    @Test
    fun `blocks private HTTP targets by default`() {
        val execution = service(DataSourceType.HTTP, "http://127.0.0.1:65530/data")
        assertFailsWith<DatasetValidationException> { execution.querySource(1, "{}", 10) }
    }

    private fun service(type: DataSourceType, endpoint: String): DataSourceExecutionService {
        val source = DataSource(1, "test", type, endpoint, "", "", Instant.EPOCH, Instant.EPOCH)
        val repository = object : DataSourceRepository {
            override fun list() = listOf(source)
            override fun find(id: Long) = StoredDataSource(source, "")
            override fun create(draft: DataSourceDraft, passwordCipher: String) = source
            override fun update(id: Long, draft: DataSourceDraft, passwordCipher: String?) = source
            override fun delete(id: Long) = false
        }
        val cipher = object : DataSourceSecretCipher { override fun encrypt(plainText: String) = plainText; override fun decrypt(cipherText: String) = cipherText }
        return DataSourceExecutionService(DataSourceService(repository, cipher), ObjectMapper().findAndRegisterModules(), directory.toString(), false)
    }
}

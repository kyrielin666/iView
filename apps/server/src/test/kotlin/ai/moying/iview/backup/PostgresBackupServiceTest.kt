package ai.moying.iview.backup

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.springframework.jdbc.core.JdbcTemplate
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*

/** Command/guard unit tests; these do not assert that a real database was restored. */
class PostgresBackupServiceTest {
    @TempDir lateinit var directory: Path
    private val commands = mutableListOf<List<String>>()
    private val fake = PostgresCommandExecutor { command, _, _, _ ->
        commands += command
        command.find { it.startsWith("--file=") }?.let { Files.writeString(Path.of(it.removePrefix("--file=")), "unit-test-archive") }
    }
    private fun service(url: String = "jdbc:postgresql://127.0.0.1:5432/iview_test", enabled: Boolean = true) = BackupService(
        JdbcTemplate(), url, "test", "test-secret", directory.toString(), 7, true, enabled, "pg_dump", "pg_restore", 10, fake,
    )

    @Test fun `postgres restore validates digest then uses one transaction and stops on error`() {
        val service = service(); val backup = service.create()
        service.restore(backup.name, "RESTORE ${backup.name}")
        assertTrue(commands[1].contains("--list"))
        assertTrue(commands.last().containsAll(listOf("--single-transaction", "--exit-on-error", "--clean")))
        assertFalse(commands.flatten().any { it.contains("test-secret") })
    }

    @Test fun `wrong confirmation or engine never launches restore or validation commands`() {
        val backup = service().create(); commands.clear()
        assertFailsWith<BackupException> { service().restore(backup.name, "wrong") }
        assertFailsWith<BackupException> { service("jdbc:h2:mem:test").restore(backup.name, "RESTORE ${backup.name}") }
        assertFailsWith<BackupException> { service(enabled = false).restore(backup.name, "RESTORE ${backup.name}") }
        assertTrue(commands.isEmpty())
    }

    @Test fun `corrupted archive is rejected before invoking postgres tools`() {
        val service = service(); val backup = service.create(); commands.clear()
        Files.writeString(directory.resolve(backup.name), "tampered")
        assertFailsWith<BackupException> { service.restore(backup.name, "RESTORE ${backup.name}") }
        assertTrue(commands.isEmpty())
    }

    @Test fun `unsupported transport parameters and libpq connection string names are rejected`() {
        assertFailsWith<BackupException> { service("jdbc:postgresql://127.0.0.1/test?sslmode=verify-full").create() }
        assertFailsWith<BackupException> { service("jdbc:postgresql://127.0.0.1/dbname=other").create() }
        assertTrue(commands.isEmpty())
    }
}

package ai.moying.iview.backup

import ai.moying.iview.common.ApiResponse
import ai.moying.iview.identity.AdminGuard
import jakarta.servlet.http.HttpServletRequest
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.HttpStatus
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import org.springframework.web.bind.annotation.*
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.security.MessageDigest
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.net.URI
import java.util.concurrent.TimeUnit

data class BackupFile(val name: String, val size: Long, val sha256: String, val createdAt: Instant, val valid: Boolean, val engine: String)
class RestoreRequest { var name = ""; var confirmation = "" }
class BackupException(message: String) : RuntimeException(message)

@RestController
@RequestMapping("/api/v1/backups")
class BackupController(private val backups: BackupService, private val guard: AdminGuard) {
    @GetMapping fun list(request: HttpServletRequest) = guard.require(request).let { ApiResponse.success(backups.list()) }
    @PostMapping @ResponseStatus(HttpStatus.CREATED) fun create(request: HttpServletRequest) = guard.require(request).let { ApiResponse.success(backups.create()) }
    @PostMapping("/{name}/validate") fun validate(@PathVariable name: String, request: HttpServletRequest) = guard.require(request).let { ApiResponse.success(backups.validate(name)) }
    @PostMapping("/restore") fun restore(@RequestBody body: RestoreRequest, request: HttpServletRequest): ApiResponse<Nothing> { guard.require(request); backups.restore(body.name, body.confirmation); return ApiResponse.success() }
}

@Service
class BackupService(
    private val jdbc: JdbcTemplate,
    @Value("\${spring.datasource.url}") private val jdbcUrl: String,
    @Value("\${spring.datasource.username}") private val jdbcUsername: String,
    @Value("\${spring.datasource.password:}") private val jdbcPassword: String,
    @Value("\${iview.backup.directory:./data/backups}") configuredDirectory: String,
    @Value("\${iview.backup.retention-count:7}") private val retentionCount: Int,
    @Value("\${iview.backup.restore-enabled:false}") private val restoreEnabled: Boolean,
    @Value("\${iview.backup.postgres.enabled:false}") private val postgresEnabled: Boolean,
    @Value("\${iview.backup.postgres.pg-dump-command:pg_dump}") private val pgDumpCommand: String,
    @Value("\${iview.backup.postgres.pg-restore-command:pg_restore}") private val pgRestoreCommand: String,
    @Value("\${iview.backup.postgres.command-timeout-seconds:1800}") private val postgresTimeoutSeconds: Long,
) {
    private val directory: Path = Paths.get(configuredDirectory).toAbsolutePath().normalize()
    private val namePattern = Regex("iview-\\d{8}-\\d{6}-\\d{3}\\.(?:sql|dump)")
    fun list(): List<BackupFile> = ensureDirectory().let { Files.list(it).use { paths -> paths.filter { Files.isRegularFile(it) && namePattern.matches(it.fileName.toString()) }.map(::metadata).sorted(compareByDescending<BackupFile> { it.createdAt }).toList() } }
    fun create(): BackupFile {
        require(retentionCount in 1..100) { "备份保留数量必须在 1 到 100 之间" }; val dir = ensureDirectory()
        val suffix = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS").withZone(ZoneOffset.UTC).format(Instant.now())
        val target = safeName("iview-$suffix.${if (isPostgres()) "dump" else "sql"}")
        if (isPostgres()) createPostgres(target) else { requireH2(); jdbc.execute("SCRIPT TO '${target.toString().replace("'", "''")}'") }
        val result = metadata(target); trimRetention(dir); return result
    }
    fun validate(name: String): BackupFile {
        val source = existing(name); val info = metadata(source); if (!info.valid) throw BackupException("备份文件为空或校验失败")
        if (source.fileName.toString().endsWith(".dump")) runPostgres(listOf(pgRestoreCommand, "--list", source.toString()), "校验 PostgreSQL 备份")
        return info
    }
    fun restore(name: String, confirmation: String) {
        if (!restoreEnabled) throw BackupException("恢复操作默认关闭；请在受控维护窗口设置 IVIEW_BACKUP_RESTORE_ENABLED=true 后重启服务")
        val source = existing(name); validate(name); if (confirmation != "RESTORE $name") throw BackupException("恢复确认文本不正确")
        if (source.fileName.toString().endsWith(".dump")) restorePostgres(source) else { requireH2(); jdbc.execute("DROP ALL OBJECTS"); jdbc.execute("RUNSCRIPT FROM '${source.toString().replace("'", "''")}'") }
    }
    private fun createPostgres(target: Path) {
        if (!postgresEnabled) throw BackupException("PostgreSQL 备份默认关闭；请在受控环境设置 IVIEW_BACKUP_POSTGRES_ENABLED=true，并配置 pg_dump/pg_restore")
        val database = postgresConnection()
        runPostgres(listOf(pgDumpCommand, "--format=custom", "--file=$target", "--no-owner", "--no-privileges", "--host=${database.host}", "--port=${database.port}", "--username=${database.username}", "--dbname=${database.database}"), "创建 PostgreSQL 备份", database.password)
    }
    private fun restorePostgres(source: Path) {
        if (!postgresEnabled) throw BackupException("PostgreSQL 恢复需要 IVIEW_BACKUP_POSTGRES_ENABLED=true")
        val database = postgresConnection()
        runPostgres(listOf(pgRestoreCommand, "--clean", "--if-exists", "--no-owner", "--no-privileges", "--host=${database.host}", "--port=${database.port}", "--username=${database.username}", "--dbname=${database.database}", source.toString()), "恢复 PostgreSQL 备份", database.password)
    }
    private fun isPostgres() = jdbcUrl.startsWith("jdbc:postgresql:")
    private fun requireH2() { if (!jdbcUrl.startsWith("jdbc:h2:")) throw BackupException("当前数据库不支持内置 H2 备份；PostgreSQL 请启用受控 pg_dump/pg_restore 作业") }
    private fun ensureDirectory(): Path = directory.also { Files.createDirectories(it) }
    private fun safeName(name: String): Path { if (!namePattern.matches(name)) throw BackupException("备份文件名不合法"); val result = directory.resolve(name).normalize(); if (result.parent != directory) throw BackupException("备份文件路径不合法"); return result }
    private fun existing(name: String): Path = safeName(name).also { if (!Files.isRegularFile(it)) throw BackupException("备份文件不存在") }
    private fun metadata(path: Path): BackupFile { val size = Files.size(path); return BackupFile(path.fileName.toString(), size, digest(path), Files.getLastModifiedTime(path).toInstant(), size > 0, if (path.fileName.toString().endsWith(".dump")) "POSTGRESQL" else "H2") }
    private fun digest(path: Path): String = Files.newInputStream(path).use { input -> MessageDigest.getInstance("SHA-256").let { digest -> ByteArray(8192).let { buffer -> generateSequence { input.read(buffer).takeIf { it > 0 } }.forEach { digest.update(buffer, 0, it) }; digest.digest().joinToString("") { byte -> "%02x".format(byte) } } } }
    private fun trimRetention(dir: Path) { val stale = Files.list(dir).use { it.filter { Files.isRegularFile(it) && namePattern.matches(it.fileName.toString()) }.sorted().toList().dropLast(retentionCount) }; stale.forEach(Files::deleteIfExists) }
    private data class PostgresConnection(val host: String, val port: Int, val database: String, val username: String, val password: String)
    private fun postgresConnection(): PostgresConnection {
        if (!isPostgres()) throw BackupException("当前数据库不是 PostgreSQL")
        val uri = runCatching { URI(jdbcUrl.removePrefix("jdbc:")) }.getOrElse { throw BackupException("PostgreSQL JDBC 地址无效") }
        val host = uri.host?.takeIf { it.isNotBlank() } ?: throw BackupException("PostgreSQL JDBC 地址缺少主机")
        val database = uri.path.trim('/').takeIf { it.isNotBlank() } ?: throw BackupException("PostgreSQL JDBC 地址缺少数据库名")
        return PostgresConnection(host, if (uri.port > 0) uri.port else 5432, database, jdbcUsername, jdbcPassword)
    }
    private fun runPostgres(command: List<String>, action: String, password: String = "") {
        if (postgresTimeoutSeconds !in 1..86_400) throw BackupException("PostgreSQL 备份命令超时必须在 1 到 86400 秒之间")
        val process = try { ProcessBuilder(command).redirectErrorStream(true).apply { if (password.isNotBlank()) environment()["PGPASSWORD"] = password }.start() } catch (error: Exception) { throw BackupException("$action 无法启动：${error.message}") }
        val output = StringBuilder(); val reader = Thread { var outputLines = 0; process.inputStream.bufferedReader().useLines { lines -> lines.forEach { line -> if (outputLines++ < 100) output.appendLine(line) } } }.apply { isDaemon = true; start() }
        if (!process.waitFor(postgresTimeoutSeconds, TimeUnit.SECONDS)) { process.destroyForcibly(); reader.join(1_000); throw BackupException("$action 超时") }
        reader.join(1_000)
        if (process.exitValue() != 0) throw BackupException("$action 失败：${output.toString().ifBlank { "命令退出码 ${process.exitValue()}" }.trim()}")
    }
}

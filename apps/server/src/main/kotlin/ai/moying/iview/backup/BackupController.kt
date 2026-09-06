package ai.moying.iview.backup

import ai.moying.iview.common.ApiResponse
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

data class BackupFile(val name: String, val size: Long, val sha256: String, val createdAt: Instant, val valid: Boolean)
class RestoreRequest { var name = ""; var confirmation = "" }
class BackupException(message: String) : RuntimeException(message)

@RestController
@RequestMapping("/api/v1/backups")
class BackupController(private val backups: BackupService) {
    @GetMapping fun list() = ApiResponse.success(backups.list())
    @PostMapping @ResponseStatus(HttpStatus.CREATED) fun create() = ApiResponse.success(backups.create())
    @PostMapping("/{name}/validate") fun validate(@PathVariable name: String) = ApiResponse.success(backups.validate(name))
    @PostMapping("/restore") fun restore(@RequestBody request: RestoreRequest): ApiResponse<Nothing> { backups.restore(request.name, request.confirmation); return ApiResponse.success() }
}

@Service
class BackupService(
    private val jdbc: JdbcTemplate,
    @Value("\${spring.datasource.url}") private val jdbcUrl: String,
    @Value("\${iview.backup.directory:./data/backups}") configuredDirectory: String,
    @Value("\${iview.backup.retention-count:7}") private val retentionCount: Int,
    @Value("\${iview.backup.restore-enabled:false}") private val restoreEnabled: Boolean,
) {
    private val directory: Path = Paths.get(configuredDirectory).toAbsolutePath().normalize()
    private val namePattern = Regex("iview-\\d{8}-\\d{6}-\\d{3}\\.sql")
    fun list(): List<BackupFile> = ensureDirectory().let { Files.list(it).use { paths -> paths.filter { Files.isRegularFile(it) && namePattern.matches(it.fileName.toString()) }.map(::metadata).sorted(compareByDescending<BackupFile> { it.createdAt }).toList() } }
    fun create(): BackupFile {
        requireH2(); require(retentionCount in 1..100) { "备份保留数量必须在 1 到 100 之间" }; val dir = ensureDirectory()
        val name = "iview-${DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS").withZone(ZoneOffset.UTC).format(Instant.now())}.sql"; val target = safeName(name)
        jdbc.execute("SCRIPT TO '${target.toString().replace("'", "''")}'")
        val result = metadata(target); trimRetention(dir); return result
    }
    fun validate(name: String): BackupFile { val info = metadata(existing(name)); if (!info.valid) throw BackupException("备份文件为空或校验失败"); return info }
    fun restore(name: String, confirmation: String) {
        requireH2(); if (!restoreEnabled) throw BackupException("恢复操作默认关闭；请在受控维护窗口设置 IVIEW_BACKUP_RESTORE_ENABLED=true 后重启服务")
        val source = existing(name); validate(name); if (confirmation != "RESTORE $name") throw BackupException("恢复确认文本不正确")
        jdbc.execute("DROP ALL OBJECTS"); jdbc.execute("RUNSCRIPT FROM '${source.toString().replace("'", "''")}'")
    }
    private fun requireH2() { if (!jdbcUrl.startsWith("jdbc:h2:")) throw BackupException("当前仅内置 H2 逻辑备份；PostgreSQL 生产环境请配置受控的 pg_dump/pg_restore 作业") }
    private fun ensureDirectory(): Path = directory.also { Files.createDirectories(it) }
    private fun safeName(name: String): Path { if (!namePattern.matches(name)) throw BackupException("备份文件名不合法"); val result = directory.resolve(name).normalize(); if (result.parent != directory) throw BackupException("备份文件路径不合法"); return result }
    private fun existing(name: String): Path = safeName(name).also { if (!Files.isRegularFile(it)) throw BackupException("备份文件不存在") }
    private fun metadata(path: Path): BackupFile { val size = Files.size(path); return BackupFile(path.fileName.toString(), size, digest(path), Files.getLastModifiedTime(path).toInstant(), size > 0) }
    private fun digest(path: Path): String = Files.newInputStream(path).use { input -> MessageDigest.getInstance("SHA-256").let { digest -> ByteArray(8192).let { buffer -> generateSequence { input.read(buffer).takeIf { it > 0 } }.forEach { digest.update(buffer, 0, it) }; digest.digest().joinToString("") { byte -> "%02x".format(byte) } } } }
    private fun trimRetention(dir: Path) { val stale = Files.list(dir).use { it.filter { Files.isRegularFile(it) && namePattern.matches(it.fileName.toString()) }.sorted().toList().dropLast(retentionCount) }; stale.forEach(Files::deleteIfExists) }
}

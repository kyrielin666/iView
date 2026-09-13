package ai.moying.iview.device

import ai.moying.iview.common.ApiResponse
import ai.moying.iview.identity.AdminGuard
import jakarta.annotation.PostConstruct
import jakarta.annotation.PreDestroy
import jakarta.servlet.http.HttpServletRequest
import kotlinx.coroutines.*
import org.springframework.http.HttpStatus
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import org.springframework.web.bind.annotation.*
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class DeviceAcceptanceRequest {
    var durationMinutes: Int = 60
    var intervalMs: Int = 1_000
    var minimumSuccessRate: Double = 0.99
    var failureLimit: Int = 5
}

data class DeviceAcceptanceRun(
    val id: String, val deviceId: Long, val deviceName: String, val protocolType: String, val status: String,
    val startedAt: Instant, val plannedEndAt: Instant, val endedAt: Instant?, val intervalMs: Int,
    val minimumSuccessRate: Double, val failureLimit: Int, val attempts: Long, val successes: Long,
    val failures: Long, val recoveries: Long, val averageDurationMs: Long, val maxDurationMs: Long,
    val maxConsecutiveFailures: Int, val successRate: Double, val passed: Boolean?, val lastError: String,
)

@RestController
class DeviceAcceptanceController(private val acceptance: DeviceAcceptanceService, private val guard: AdminGuard) {
    @PostMapping("/api/v1/devices/{deviceId}/acceptance-runs") @ResponseStatus(HttpStatus.ACCEPTED)
    fun start(@PathVariable deviceId: Long, @RequestBody body: DeviceAcceptanceRequest, request: HttpServletRequest) = guard.require(request).let { ApiResponse.success(acceptance.start(deviceId, body)) }
    @GetMapping("/api/v1/device-acceptance-runs")
    fun list(@RequestParam(name = "device_id", required = false) deviceId: Long?, @RequestParam(defaultValue = "100") limit: Int, request: HttpServletRequest) = guard.require(request).let { ApiResponse.success(acceptance.list(deviceId, limit)) }
    @GetMapping("/api/v1/device-acceptance-runs/{id}") fun get(@PathVariable id: String, request: HttpServletRequest) = guard.require(request).let { ApiResponse.success(acceptance.get(id)) }
    @DeleteMapping("/api/v1/device-acceptance-runs/{id}") fun stop(@PathVariable id: String, request: HttpServletRequest) = guard.require(request).let { ApiResponse.success(acceptance.stop(id)) }
}

@Service
class DeviceAcceptanceService(private val jdbc: JdbcTemplate, private val catalog: DeviceCatalogService, private val collection: DeviceCollectionService) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val jobs = ConcurrentHashMap<String, Job>()

    @PostConstruct fun recoverInterruptedRuns() { jdbc.update("UPDATE iview_device_acceptance_run SET status='INTERRUPTED',ended_at=CURRENT_TIMESTAMP,last_error='服务重启导致验收中断' WHERE status='RUNNING'") }
    @PreDestroy fun shutdown() { scope.cancel() }

    @Synchronized fun start(deviceId: Long, body: DeviceAcceptanceRequest): DeviceAcceptanceRun {
        val device = catalog.getDevice(deviceId); if (!device.enabled) throw CatalogValidationException("设备已停用，不能开始连续运行验收")
        if (body.durationMinutes !in 1..10_080) throw CatalogValidationException("验收时长必须为 1 分钟到 7 天")
        if (body.intervalMs !in 100..60_000) throw CatalogValidationException("采集间隔必须为 100 到 60000 毫秒")
        if (body.minimumSuccessRate !in 0.5..1.0) throw CatalogValidationException("最低成功率必须为 0.5 到 1.0")
        if (body.failureLimit !in 1..1_000) throw CatalogValidationException("连续失败阈值必须为 1 到 1000")
        val active = jdbc.queryForObject("SELECT COUNT(*) FROM iview_device_acceptance_run WHERE device_id=? AND status='RUNNING'", Long::class.java, deviceId) ?: 0
        if (active > 0) throw CatalogConflictException("该设备已有进行中的连续运行验收")
        val id = UUID.randomUUID().toString(); val started = Instant.now(); val plannedEnd = started.plusSeconds(body.durationMinutes.toLong() * 60)
        jdbc.update("INSERT INTO iview_device_acceptance_run(id,device_id,status,started_at,planned_end_at,interval_ms,minimum_success_rate,failure_limit) VALUES(?,?,'RUNNING',?,?,?,?,?)", id, deviceId, started, plannedEnd, body.intervalMs, body.minimumSuccessRate, body.failureLimit)
        val job = scope.launch(start = CoroutineStart.LAZY) { execute(id, deviceId, plannedEnd, body.intervalMs, body.failureLimit) }
        jobs[id] = job; job.invokeOnCompletion { jobs.remove(id, job) }; job.start()
        return get(id)
    }

    fun list(deviceId: Long?, limit: Int): List<DeviceAcceptanceRun> {
        val where = if (deviceId == null) "" else "WHERE r.device_id=?"; val sql = "$selectSql $where ORDER BY r.started_at DESC LIMIT ?"
        return if (deviceId == null) jdbc.query(sql, mapper, limit.coerceIn(1, 500)) else jdbc.query(sql, mapper, deviceId, limit.coerceIn(1, 500))
    }
    fun get(id: String): DeviceAcceptanceRun = jdbc.query("$selectSql WHERE r.id=?", mapper, id).firstOrNull() ?: throw CatalogNotFoundException("连续运行验收不存在: $id")
    fun stop(id: String): DeviceAcceptanceRun {
        val current = get(id); if (current.status == "RUNNING") { jdbc.update("UPDATE iview_device_acceptance_run SET status='STOPPED',ended_at=CURRENT_TIMESTAMP,last_error='由管理员手工停止' WHERE id=? AND status='RUNNING'", id); jobs[id]?.cancel() }
        return get(id)
    }

    private suspend fun execute(id: String, deviceId: Long, plannedEnd: Instant, intervalMs: Int, failureLimit: Int) {
        var attempts = 0L; var successes = 0L; var failures = 0L; var recoveries = 0L; var totalDuration = 0L; var maxDuration = 0L; var consecutive = 0; var maxConsecutive = 0; var lastError = ""
        try {
            while (currentCoroutineContext().isActive && Instant.now() < plannedEnd) {
                val started = System.nanoTime(); val outcome = runCatching { collection.collect(deviceId) }; val duration = ((System.nanoTime() - started) / 1_000_000).coerceAtLeast(0)
                attempts++; totalDuration += duration; maxDuration = maxOf(maxDuration, duration)
                val result = outcome.getOrNull(); val successful = result?.success == true
                if (successful) { successes++; consecutive = 0; if (result.recovered) recoveries++; lastError = "" } else { failures++; consecutive++; maxConsecutive = maxOf(maxConsecutive, consecutive); lastError = (result?.error ?: outcome.exceptionOrNull()?.message ?: "采集失败").take(1000) }
                jdbc.update("UPDATE iview_device_acceptance_run SET attempts=?,successes=?,failures=?,recoveries=?,total_duration_ms=?,max_duration_ms=?,max_consecutive_failures=?,last_error=? WHERE id=? AND status='RUNNING'", attempts, successes, failures, recoveries, totalDuration, maxDuration, maxConsecutive, lastError, id)
                if (consecutive >= failureLimit) { jdbc.update("UPDATE iview_device_acceptance_run SET status='FAILED',ended_at=CURRENT_TIMESTAMP WHERE id=? AND status='RUNNING'", id); return }
                delay((intervalMs - duration).coerceAtLeast(1))
            }
            jdbc.update("UPDATE iview_device_acceptance_run SET status='COMPLETED',ended_at=CURRENT_TIMESTAMP WHERE id=? AND status='RUNNING'", id)
        } catch (_: CancellationException) { /* stop() records the terminal state before cancellation */ }
        catch (error: Exception) { jdbc.update("UPDATE iview_device_acceptance_run SET status='FAILED',ended_at=CURRENT_TIMESTAMP,last_error=? WHERE id=? AND status='RUNNING'", (error.message ?: error::class.simpleName ?: "验收任务异常").take(1000), id) }
    }

    private val mapper = org.springframework.jdbc.core.RowMapper { rs, _ ->
        val attempts = rs.getLong("attempts"); val successes = rs.getLong("successes"); val status = rs.getString("status"); val rate = if (attempts == 0L) 0.0 else successes.toDouble() / attempts
        DeviceAcceptanceRun(rs.getString("id"), rs.getLong("device_id"), rs.getString("device_name"), rs.getString("protocol_type"), status, rs.getTimestamp("started_at").toInstant(), rs.getTimestamp("planned_end_at").toInstant(), rs.getTimestamp("ended_at")?.toInstant(), rs.getInt("interval_ms"), rs.getDouble("minimum_success_rate"), rs.getInt("failure_limit"), attempts, successes, rs.getLong("failures"), rs.getLong("recoveries"), if (attempts == 0L) 0 else rs.getLong("total_duration_ms") / attempts, rs.getLong("max_duration_ms"), rs.getInt("max_consecutive_failures"), rate, if (status == "RUNNING") null else status == "COMPLETED" && rate >= rs.getDouble("minimum_success_rate") && rs.getInt("max_consecutive_failures") < rs.getInt("failure_limit"), rs.getString("last_error"))
    }
    private val selectSql = "SELECT r.*,d.device_name,t.protocol_type FROM iview_device_acceptance_run r JOIN iview_device d ON d.id=r.device_id JOIN iview_device_template t ON t.id=d.template_id"
}

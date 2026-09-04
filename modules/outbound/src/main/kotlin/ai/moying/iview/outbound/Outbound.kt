package ai.moying.iview.outbound

import java.io.Closeable
import java.time.Instant
import java.util.UUID

enum class PushStatus(val code: Int) { FAILED(0), SUCCESS(1), RETRYING(2) }

data class PushConfig(
    val id: Long,
    val name: String,
    val pushType: String,
    val enabled: Boolean,
    val priority: Int,
    val timeoutMs: Int,
    val retryCount: Int,
    val retryDelayMs: Int,
    val config: Map<String, Any?>,
    val description: String,
    val createdAt: Instant,
    val updatedAt: Instant,
)

data class PushConfigCommand(
    val name: String,
    val pushType: String,
    val enabled: Boolean = false,
    val priority: Int = 0,
    val timeoutMs: Int = 5_000,
    val retryCount: Int = 3,
    val retryDelayMs: Int = 1_000,
    val config: Map<String, Any?> = emptyMap(),
    val description: String = "",
)

data class PushConfigFilter(
    val page: Int = 1,
    val pageSize: Int = 20,
    val keyword: String? = null,
    val pushType: String? = null,
)

data class PushPage<T>(val list: List<T>, val total: Long, val page: Int, val pageSize: Int)

data class PushPoint(
    val pointId: Long,
    val pointName: String,
    val pointCode: String,
    val value: Any?,
    val rawValue: Any?,
    val quality: Int,
    val timestamp: Instant,
)

data class PushPayload(
    val msgId: String = UUID.randomUUID().toString(),
    val data: Map<String, Any?>,
    val timestamp: Long,
    val deviceSn: String,
    val deviceName: String,
    val templateId: Long,
    val templateCode: String,
    val collectTime: Instant,
    val points: List<PushPoint>,
)

data class PushLog(
    val id: Long,
    val configId: Long,
    val configName: String,
    val deviceSn: String,
    val status: Int,
    val retryCount: Int,
    val message: String,
    val payloadSize: Int,
    val duration: Long,
    val createdAt: Instant,
)

data class PushLogCommand(
    val configId: Long,
    val configName: String,
    val deviceSn: String,
    val status: PushStatus,
    val retryCount: Int,
    val message: String,
    val payloadSize: Int,
    val duration: Long,
)

data class PushLogFilter(
    val page: Int = 1,
    val pageSize: Int = 20,
    val configId: Long? = null,
    val deviceSn: String? = null,
    val status: Int? = null,
)

interface PushRepository {
    fun listConfigs(filter: PushConfigFilter): PushPage<PushConfig>
    fun listEnabledConfigs(): List<PushConfig>
    fun findConfig(id: Long): PushConfig?
    fun createConfig(command: PushConfigCommand): PushConfig
    fun updateConfig(id: Long, command: PushConfigCommand): PushConfig?
    fun deleteConfig(id: Long): Boolean
    fun setEnabled(id: Long, enabled: Boolean): PushConfig?
    fun appendLog(command: PushLogCommand): PushLog
    fun listLogs(filter: PushLogFilter): PushPage<PushLog>
}

interface PushAdapter : Closeable {
    fun healthCheck(timeoutMs: Int)
    fun push(payload: PushPayload, timeoutMs: Int)
}

fun interface PushAdapterFactory {
    fun create(config: PushConfig): PushAdapter
}

open class PushException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)
class PushNotFoundException(message: String) : PushException(message)
class PushValidationException(message: String) : PushException(message)

class PushConfigService(private val repository: PushRepository) {
    fun list(filter: PushConfigFilter) = repository.listConfigs(filter.normalized())
    fun get(id: Long) = repository.findConfig(id) ?: throw PushNotFoundException("推送配置不存在: $id")
    fun create(command: PushConfigCommand): PushConfig {
        val normalized = command.normalized()
        validate(normalized)
        return repository.createConfig(normalized)
    }
    fun update(id: Long, command: PushConfigCommand): PushConfig {
        get(id)
        val normalized = command.normalized()
        validate(normalized)
        return repository.updateConfig(id, normalized) ?: throw PushNotFoundException("推送配置不存在: $id")
    }
    fun delete(id: Long) {
        if (!repository.deleteConfig(id)) throw PushNotFoundException("推送配置不存在: $id")
    }
    fun setEnabled(id: Long, enabled: Boolean) = repository.setEnabled(id, enabled)
        ?: throw PushNotFoundException("推送配置不存在: $id")
    fun logs(filter: PushLogFilter) = repository.listLogs(filter.normalized())

    private fun validate(command: PushConfigCommand) {
        if (command.name.isBlank() || command.name.length > 100) throw PushValidationException("配置名称不能为空且不能超过100位")
        if (command.pushType !in setOf("http", "mqtt")) throw PushValidationException("暂不支持的推送类型: ${command.pushType}")
        if (command.timeoutMs !in 100..300_000) throw PushValidationException("超时时间必须在100毫秒到5分钟之间")
        if (command.retryCount !in 0..20) throw PushValidationException("重试次数必须在0到20之间")
        if (command.retryDelayMs !in 0..300_000) throw PushValidationException("重试延迟必须在0到5分钟之间")
        when (command.pushType) {
            "http" -> {
                val url = command.config["url"]?.toString().orEmpty()
                if (!(url.startsWith("http://") || url.startsWith("https://"))) throw PushValidationException("HTTP URL 必须以 http:// 或 https:// 开头")
                val method = command.config["method"]?.toString()?.uppercase() ?: "POST"
                if (method !in setOf("POST", "PUT", "PATCH")) throw PushValidationException("HTTP 方法仅支持 POST、PUT 或 PATCH")
            }
            "mqtt" -> {
                val broker = command.config["broker"]?.toString().orEmpty()
                if (broker.isBlank()) throw PushValidationException("MQTT Broker 不能为空")
                if (command.config["topic"]?.toString().isNullOrBlank()) throw PushValidationException("MQTT Topic 不能为空")
                val qos = (command.config["qos"] as? Number)?.toInt() ?: command.config["qos"]?.toString()?.toIntOrNull() ?: 0
                if (qos !in 0..2) throw PushValidationException("MQTT QoS 必须为0、1或2")
            }
        }
    }

    private fun PushConfigCommand.normalized() = copy(name = name.trim(), pushType = pushType.trim().lowercase(), description = description.trim())
    private fun PushConfigFilter.normalized() = copy(page = page.coerceAtLeast(1), pageSize = pageSize.coerceIn(1, 100), keyword = keyword?.trim()?.takeIf(String::isNotEmpty), pushType = pushType?.trim()?.lowercase()?.takeIf(String::isNotEmpty))
    private fun PushLogFilter.normalized() = copy(page = page.coerceAtLeast(1), pageSize = pageSize.coerceIn(1, 100), deviceSn = deviceSn?.trim()?.takeIf(String::isNotEmpty))
}

class PushDispatcher(
    private val repository: PushRepository,
    private val adapters: PushAdapterFactory,
    private val payloadSize: (PushPayload) -> Int,
    private val sleeper: (Long) -> Unit = Thread::sleep,
) {
    fun dispatch(payload: PushPayload, onlyConfigId: Long? = null) {
        repository.listEnabledConfigs()
            .filter { onlyConfigId == null || it.id == onlyConfigId }
            .sortedWith(compareBy(PushConfig::priority, PushConfig::id))
            .forEach { config -> dispatch(config, payload) }
    }

    fun test(config: PushConfig) = adapters.create(config).healthCheck(config.timeoutMs)

    private fun dispatch(config: PushConfig, payload: PushPayload) {
        val size = payloadSize(payload)
        val started = System.nanoTime()
        val adapter = runCatching { adapters.create(config) }.getOrElse { error ->
            repository.appendLog(PushLogCommand(config.id, config.name, payload.deviceSn, PushStatus.FAILED, 0, error.message ?: "创建推送适配器失败", size, started.elapsedMs()))
            return
        }
        var lastError: Throwable? = null
        for (attempt in 0..config.retryCount) {
            val result = runCatching { adapter.push(payload, config.timeoutMs) }
            if (result.isSuccess) {
                repository.appendLog(PushLogCommand(config.id, config.name, payload.deviceSn, PushStatus.SUCCESS, attempt, "", size, started.elapsedMs()))
                return
            }
            lastError = result.exceptionOrNull()
            if (attempt < config.retryCount) {
                repository.appendLog(PushLogCommand(config.id, config.name, payload.deviceSn, PushStatus.RETRYING, attempt, lastError?.message ?: "推送失败", size, 0))
                sleeper(config.retryDelayMs.toLong())
            }
        }
        repository.appendLog(PushLogCommand(config.id, config.name, payload.deviceSn, PushStatus.FAILED, config.retryCount, lastError?.message ?: "推送失败", size, started.elapsedMs()))
    }
}

private fun Long.elapsedMs() = (System.nanoTime() - this) / 1_000_000

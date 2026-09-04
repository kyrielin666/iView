package ai.moying.iview.outbound

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import java.time.Instant

class PushDispatcherTest {
    @Test
    fun `dispatcher retries then stores success audit`() {
        val repository = MemoryRepository(listOf(config(retryCount = 3)))
        var attempts = 0
        val adapter = object : PushAdapter {
            override fun healthCheck(timeoutMs: Int) = Unit
            override fun push(payload: PushPayload, timeoutMs: Int) {
                attempts++
                if (attempts < 3) error("temporary-$attempts")
            }
            override fun close() = Unit
        }
        PushDispatcher(repository, PushAdapterFactory { adapter }, { 128 }, sleeper = {}).dispatch(payload())

        assertEquals(3, attempts)
        assertEquals(listOf(2, 2, 1), repository.logs.map { it.status.code })
        assertEquals(listOf(0, 1, 2), repository.logs.map(PushLogCommand::retryCount))
        assertEquals(128, repository.logs.last().payloadSize)
    }

    @Test
    fun `dispatcher stores final failure after configured retries`() {
        val repository = MemoryRepository(listOf(config(retryCount = 1)))
        val adapter = object : PushAdapter {
            override fun healthCheck(timeoutMs: Int) = Unit
            override fun push(payload: PushPayload, timeoutMs: Int) = error("destination unavailable")
            override fun close() = Unit
        }
        PushDispatcher(repository, PushAdapterFactory { adapter }, { 64 }, sleeper = {}).dispatch(payload())

        assertEquals(listOf(2, 0), repository.logs.map { it.status.code })
        assertEquals("destination unavailable", repository.logs.last().message)
    }

    @Test
    fun `config validation rejects unsupported target and malformed http url`() {
        val service = PushConfigService(MemoryRepository(emptyList()))
        assertFailsWith<PushValidationException> {
            service.create(PushConfigCommand("Kafka", "kafka", config = emptyMap()))
        }
        assertFailsWith<PushValidationException> {
            service.create(PushConfigCommand("HTTP", "http", config = mapOf("url" to "ftp://example")))
        }
    }

    private fun config(retryCount: Int) = PushConfig(
        1, "MES HTTP", "http", true, 0, 1_000, retryCount, 1,
        mapOf("url" to "http://localhost"), "", Instant.EPOCH, Instant.EPOCH,
    )

    private fun payload() = PushPayload(
        data = mapOf("speed" to 1200), timestamp = 1, deviceSn = "PLC_01", deviceName = "PLC",
        templateId = 2, templateCode = "plc", collectTime = Instant.EPOCH,
        points = listOf(PushPoint(3, "速度", "speed", 1200, 1200, 1, Instant.EPOCH)),
    )
}

private class MemoryRepository(initial: List<PushConfig>) : PushRepository {
    private val configs = initial.toMutableList()
    val logs = mutableListOf<PushLogCommand>()
    override fun listConfigs(filter: PushConfigFilter) = PushPage(configs, configs.size.toLong(), filter.page, filter.pageSize)
    override fun listEnabledConfigs() = configs.filter(PushConfig::enabled)
    override fun findConfig(id: Long) = configs.firstOrNull { it.id == id }
    override fun createConfig(command: PushConfigCommand): PushConfig = error("not needed")
    override fun updateConfig(id: Long, command: PushConfigCommand): PushConfig? = error("not needed")
    override fun deleteConfig(id: Long) = false
    override fun setEnabled(id: Long, enabled: Boolean): PushConfig? = null
    override fun appendLog(command: PushLogCommand): PushLog {
        logs += command
        return PushLog(
            logs.size.toLong(), command.configId, command.configName, command.deviceSn, command.status.code,
            command.retryCount, command.message, command.payloadSize, command.duration, Instant.EPOCH,
        )
    }
    override fun listLogs(filter: PushLogFilter) = PushPage(emptyList<PushLog>(), 0, filter.page, filter.pageSize)
}

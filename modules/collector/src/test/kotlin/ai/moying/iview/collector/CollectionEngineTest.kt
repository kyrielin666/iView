package ai.moying.iview.collector

import ai.moying.iview.core.device.DeviceDefinition
import ai.moying.iview.core.device.DeviceId
import ai.moying.iview.core.device.PointAccess
import ai.moying.iview.core.device.PointDataType
import ai.moying.iview.core.device.PointDefinition
import ai.moying.iview.core.device.PointId
import ai.moying.iview.core.device.PointValue
import ai.moying.iview.core.device.ValueQuality
import ai.moying.iview.protocol.DiagnosticResult
import ai.moying.iview.protocol.PointWrite
import ai.moying.iview.protocol.ProtocolDriver
import ai.moying.iview.protocol.ProtocolSession
import ai.moying.iview.protocol.ValidationResult
import ai.moying.iview.protocol.WriteResult
import kotlinx.coroutines.runBlocking
import java.time.Duration
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CollectionEngineTest {
    @Test
    fun `pool reuses session and collection appends values`() = runBlocking {
        val driver = StubDriver()
        val stored = mutableListOf<PointValue>()
        val pool = DeviceSessionPool(DriverRegistry(listOf(driver)))
        val engine = CollectionEngine(pool) { stored += it }
        val device = device()
        val point = point()

        assertTrue(engine.collect(device, listOf(point)).successful)
        assertTrue(engine.collect(device, listOf(point)).successful)

        assertEquals(1, driver.connectionCount)
        assertEquals(2, stored.size)
        assertEquals(1, pool.statuses().size)
        pool.close()
    }

    @Test
    fun `connection failure stores offline values`() = runBlocking {
        val driver = StubDriver(failConnection = true)
        val stored = mutableListOf<PointValue>()
        val engine = CollectionEngine(DeviceSessionPool(DriverRegistry(listOf(driver)))) { stored += it }

        val result = engine.collect(device(), listOf(point()))

        assertFalse(result.successful)
        assertEquals(ValueQuality.OFFLINE, result.values.single().quality)
        assertEquals(ValueQuality.OFFLINE, stored.single().quality)
    }

    private fun device() = DeviceDefinition(DeviceId(1), "PLC_1", "PLC", "stub", true, mapOf("host" to "test"))
    private fun point() = PointDefinition(
        PointId(2), DeviceId(1), "speed", "速度", "0", PointDataType.UINT16,
        PointAccess.READ_ONLY, Duration.ofSeconds(1),
    )

    private class StubDriver(private val failConnection: Boolean = false) : ProtocolDriver {
        var connectionCount = 0
        override val protocolType = "stub"
        override fun validate(device: DeviceDefinition) = ValidationResult.valid()
        override suspend fun diagnose(device: DeviceDefinition) = DiagnosticResult(emptyList())
        override suspend fun connect(device: DeviceDefinition): ProtocolSession {
            connectionCount++
            if (failConnection) error("connection failed")
            return object : ProtocolSession {
                override var connected = true
                override suspend fun read(points: List<PointDefinition>) = points.map {
                    val now = Instant.now()
                    PointValue(device.id, it.id, now, now, 42, ValueQuality.GOOD, "stub")
                }
                override suspend fun write(writes: List<PointWrite>) = writes.map { WriteResult(true) }
                override fun close() { connected = false }
            }
        }
    }
}

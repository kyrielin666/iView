package ai.moying.iview.collector

import ai.moying.iview.core.device.DeviceDefinition
import ai.moying.iview.protocol.DiagnosticResult
import ai.moying.iview.protocol.ProtocolDriver
import ai.moying.iview.protocol.ProtocolSession
import ai.moying.iview.protocol.ValidationResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class DriverRegistryTest {
    @Test
    fun `protocol lookup is case insensitive and whitespace tolerant`() {
        val driver = StubDriver("modbus-tcp")
        val registry = DriverRegistry(listOf(driver))
        assertEquals(driver, registry.require(" MODBUS-TCP "))
    }

    @Test
    fun `duplicate protocol types fail at startup`() {
        assertFailsWith<DuplicateProtocolDriverException> {
            DriverRegistry(listOf(StubDriver("s7"), StubDriver("S7")))
        }
    }

    private class StubDriver(override val protocolType: String) : ProtocolDriver {
        override fun validate(device: DeviceDefinition) = ValidationResult.valid()
        override suspend fun diagnose(device: DeviceDefinition) = DiagnosticResult(emptyList())
        override suspend fun connect(device: DeviceDefinition): ProtocolSession = error("Not needed")
    }
}

package ai.moying.iview.protocol.opcua

import ai.moying.iview.core.device.DeviceDefinition
import ai.moying.iview.core.device.DeviceId
import ai.moying.iview.core.device.PointAccess
import ai.moying.iview.core.device.PointDataType
import ai.moying.iview.core.device.PointDefinition
import ai.moying.iview.core.device.PointId
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class OpcUaDriverTest {
    private val driver = OpcUaDriver()

    @Test
    fun `validates endpoint timeout credentials and security mode`() {
        assertTrue(driver.validate(device(mapOf("endpoint_url" to "opc.tcp://127.0.0.1:4840/iview"))).valid)

        val invalid = driver.validate(
            device(
                mapOf(
                    "endpoint_url" to "http://127.0.0.1",
                    "timeout" to "99",
                    "password" to "secret",
                    "security_mode" to "Sign",
                    "security_policy" to "Basic256Sha256",
                )
            )
        )
        assertFalse(invalid.valid)
        assertEquals(setOf("endpoint_url", "timeout", "username", "security_policy"), invalid.issues.map { it.field }.toSet())
    }

    @Test
    fun `parses numeric and string node ids`() {
        assertEquals("ns=2;i=10853", opcNodeId(point("ns=2;i=10853", PointDataType.INT32)).toParseableString())
        assertEquals(
            "ns=3;s=Machine/Speed",
            opcNodeId(point("ignored", PointDataType.FLOAT32, mapOf("nodeId" to "ns=3;s=Machine/Speed"))).toParseableString(),
        )
    }

    @Test
    fun `maps writable scalar and byte values to OPC variants`() {
        assertEquals(true, opcVariant(PointDataType.BOOLEAN, "1").value())
        assertEquals(42, opcVariant(PointDataType.INT32, "42").value())
        assertEquals("hello", opcVariant(PointDataType.STRING, "hello").value())
        assertContentEquals(
            byteArrayOf(0x01, 0x0a, 0xff.toByte()),
            (opcVariant(PointDataType.BYTES, "01 0A FF").value() as org.eclipse.milo.opcua.stack.core.types.builtin.ByteString).bytesOrEmpty(),
        )
    }

    private fun device(properties: Map<String, String>) = DeviceDefinition(
        id = DeviceId(1),
        serialNumber = "opc-1",
        name = "OPC UA",
        protocolType = "opcua",
        enabled = true,
        connectionProperties = properties,
    )

    private fun point(address: String, type: PointDataType, properties: Map<String, String> = emptyMap()) = PointDefinition(
        id = PointId(1),
        deviceId = DeviceId(1),
        code = "speed",
        name = "速度",
        address = address,
        dataType = type,
        access = PointAccess.READ_WRITE,
        collectionInterval = Duration.ofSeconds(1),
        properties = properties,
    )
}

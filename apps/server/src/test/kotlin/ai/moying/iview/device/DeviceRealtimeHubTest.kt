package ai.moying.iview.device

import ai.moying.iview.identity.AuthorizationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class DeviceRealtimeHubTest {
    @Test fun `idle subscribers are closed after access is revoked`() {
        val hub = DeviceRealtimeHub()
        var allowed = true
        hub.subscribe(9) { allowed }
        assertEquals(1, hub.subscriberCount(9))
        allowed = false
        hub.revokeUnauthorized()
        assertEquals(0, hub.subscriberCount(9))
        assertFailsWith<AuthorizationException> { hub.subscribe(9) { false } }
    }

    @Test fun `a failing authorization check never retains the subscriber`() {
        val hub = DeviceRealtimeHub()
        var allowed = true
        hub.subscribe(8) { if (allowed) true else error("user lookup failed") }
        allowed = false
        hub.revokeUnauthorized()
        assertEquals(0, hub.subscriberCount(8))
    }

    @Test fun `a telemetry publish cannot reach a subscriber whose grant was removed`() {
        val hub = DeviceRealtimeHub()
        var allowed = true
        hub.subscribe(7) { allowed }
        allowed = false
        hub.publish(DeviceRealtimeView(7, "SN", "test", 1, false, emptyList()))
        assertEquals(0, hub.subscriberCount(7))
    }
}

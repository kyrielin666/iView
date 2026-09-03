package ai.moying.iview.platform

import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

data class CapabilityStatus(val name: String, val status: String)

data class PlatformCapabilities(
    val product: String,
    val runtime: String,
    val capabilities: List<CapabilityStatus>,
)

@RestController
@RequestMapping("/api/v1/platform")
class PlatformCapabilityController {
    @GetMapping("/capabilities")
    fun capabilities() = PlatformCapabilities(
        product = "iView",
        runtime = "JVM",
        capabilities = listOf(
            CapabilityStatus("device-model", "foundation"),
            CapabilityStatus("device-catalog", "implemented"),
            CapabilityStatus("protocol-spi", "foundation"),
            CapabilityStatus("modbus-tcp", "in-progress"),
            CapabilityStatus("collector", "foundation"),
            CapabilityStatus("oee", "foundation"),
            CapabilityStatus("dataset-query", "foundation"),
        ),
    )
}

package ai.moying.iview.platform

import ai.moying.iview.common.ApiResponse
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

data class CapabilityStatus(val name: String, val status: String, val scope: String = "backend")
data class MigrationLedgerSummary(val total: Int, val inProgress: Int, val notStarted: Int, val verified: Int)

data class PlatformCapabilities(
    val product: String,
    val runtime: String,
    val capabilities: List<CapabilityStatus>,
    val frontendCapabilities: List<CapabilityStatus>,
    val ledger: MigrationLedgerSummary,
)

@RestController
@RequestMapping("/api/v1/platform")
class PlatformCapabilityController {
    @GetMapping("/capabilities")
    fun capabilities() = ApiResponse.success(PlatformCapabilities(
        product = "iView",
        runtime = "JVM",
        capabilities = listOf(
            CapabilityStatus("device-model", "foundation"),
            CapabilityStatus("device-catalog", "implemented"),
            CapabilityStatus("protocol-spi", "foundation"),
            CapabilityStatus("modbus-tcp", "in-progress"),
            CapabilityStatus("collector", "in-progress"),
            CapabilityStatus("telemetry-history", "in-progress"),
            CapabilityStatus("device-control", "in-progress"),
            CapabilityStatus("outbound-push", "in-progress"),
            CapabilityStatus("device-alarms", "in-progress"),
            CapabilityStatus("machine-state-rules", "in-progress"),
            CapabilityStatus("production-records", "in-progress"),
            CapabilityStatus("oee", "foundation"),
            CapabilityStatus("dataset-query", "foundation"),
            CapabilityStatus("postgres-data-source", "in-progress"),
            CapabilityStatus("datasets", "in-progress"),
            CapabilityStatus("data-models", "in-progress"),
            CapabilityStatus("dashboards", "in-progress"),
        ),
        frontendCapabilities = listOf(
            CapabilityStatus("设备目录与控制台", "in-progress", "frontend"),
            CapabilityStatus("数据资产工作台", "in-progress", "frontend"),
            CapabilityStatus("看板文档编辑", "in-progress", "frontend"),
            CapabilityStatus("组件快捷添加", "in-progress", "frontend"),
            CapabilityStatus("草稿与发布快照预览", "in-progress", "frontend"),
            CapabilityStatus("组件数据绑定运行时", "in-progress", "frontend"),
            CapabilityStatus("图层拖拽与组件渲染", "in-progress", "frontend"),
        ),
        ledger = MigrationLedgerSummary(total = 58, inProgress = 33, notStarted = 25, verified = 0),
    ))
}

package ai.moying.iview.protocol

import ai.moying.iview.core.device.DeviceDefinition
import ai.moying.iview.core.device.PointDefinition
import ai.moying.iview.core.device.PointValue
import java.io.Closeable
import java.time.Duration

data class ValidationIssue(
    val field: String,
    val message: String,
)

data class ValidationResult(
    val issues: List<ValidationIssue>,
) {
    val valid: Boolean = issues.isEmpty()

    companion object {
        fun valid() = ValidationResult(emptyList())
    }
}

data class DiagnosticCheck(
    val name: String,
    val successful: Boolean,
    val elapsed: Duration,
    val detail: String? = null,
)

data class DiagnosticResult(
    val checks: List<DiagnosticCheck>,
) {
    val successful: Boolean = checks.all(DiagnosticCheck::successful)
}

data class PointWrite(
    val point: PointDefinition,
    val value: Any?,
)

data class WriteResult(
    val successful: Boolean,
    val diagnostic: String? = null,
)

interface ProtocolSession : Closeable {
    val connected: Boolean

    suspend fun read(points: List<PointDefinition>): List<PointValue>

    suspend fun write(writes: List<PointWrite>): List<WriteResult>
}

interface ProtocolDriver {
    val protocolType: String

    fun validate(device: DeviceDefinition): ValidationResult

    suspend fun diagnose(device: DeviceDefinition): DiagnosticResult

    suspend fun connect(device: DeviceDefinition): ProtocolSession
}

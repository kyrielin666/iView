package ai.moying.iview.datasource

import ai.moying.iview.query.Dashboard
import ai.moying.iview.query.DashboardDocument

/** Analyze what a dashboard actually renders, not merely its configured default model ID. */
internal data class DashboardDependencyVersion(
    val source: String,
    val defaultModelId: Long?,
    val document: DashboardDocument,
)

internal object DashboardModelDependencyAnalyzer {
    fun analyze(
        modelId: Long,
        changedFields: Set<String>,
        dashboard: Dashboard,
        versions: List<DashboardDependencyVersion>,
    ): DataModelDashboardImpact? {
        val matches = versions.mapNotNull { version ->
            val components = version.document.components.filter { component ->
                component.realtime == null && (component.modelId ?: version.defaultModelId) == modelId
            }
            if (components.isEmpty()) null else version.source to components
        }
        if (matches.isEmpty()) return null
        return DataModelDashboardImpact(
            dashboard.id,
            dashboard.name,
            dashboard.status.name.lowercase(),
            matches.flatMap { it.second.map { component -> component.id } }.distinct(),
            matches.flatMap { it.second.flatMap { component -> component.fields } }.filter { it in changedFields }.distinct(),
            matches.map { it.first },
        )
    }
}

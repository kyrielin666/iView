package ai.moying.iview.datasource

import ai.moying.iview.query.Dashboard
import ai.moying.iview.query.DashboardComponent
import ai.moying.iview.query.DashboardDocument
import ai.moying.iview.query.DashboardRealtimeBinding
import ai.moying.iview.query.DashboardStatus
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class DashboardModelDependencyAnalyzerTest {
    private val dashboard = Dashboard(7, null, 3, "产线", "", "{}", DashboardStatus.PUBLISHED, 11, Instant.EPOCH, Instant.EPOCH)
    private fun component(id: String, modelId: Long? = null, realtime: DashboardRealtimeBinding? = null) =
        DashboardComponent(id, "metric", 0, 0, 100, 50, modelId, listOf("rate"), realtime = realtime)

    @Test fun `published snapshot remains an impact when draft binding has been removed`() {
        val impact = DashboardModelDependencyAnalyzer.analyze(3, setOf("rate"), dashboard, listOf(
            DashboardDependencyVersion("draft", 3, DashboardDocument(components = listOf(component("new", modelId = 4)))),
            DashboardDependencyVersion("published", 3, DashboardDocument(components = listOf(component("old")))),
        ))!!
        assertEquals(listOf("published"), impact.sources)
        assertEquals(listOf("old"), impact.componentIds)
        assertEquals(listOf("rate"), impact.affectedFields)
    }

    @Test fun `snapshot uses its own default model and duplicate components are counted once`() {
        val impact = DashboardModelDependencyAnalyzer.analyze(3, setOf("rate"), dashboard, listOf(
            DashboardDependencyVersion("draft", 3, DashboardDocument(components = listOf(component("shared")))),
            DashboardDependencyVersion("published", 3, DashboardDocument(components = listOf(component("shared")))),
        ))!!
        assertEquals(listOf("draft", "published"), impact.sources)
        assertEquals(listOf("shared"), impact.componentIds)
        assertEquals(listOf("rate"), impact.affectedFields)

        assertNull(DashboardModelDependencyAnalyzer.analyze(3, setOf("rate"), dashboard, listOf(
            DashboardDependencyVersion("draft", 4, DashboardDocument(components = listOf(component("other")))),
            DashboardDependencyVersion("published", 4, DashboardDocument(components = listOf(component("other")))),
        )))
    }

    @Test fun `telemetry components do not create false data model dependencies`() {
        assertNull(DashboardModelDependencyAnalyzer.analyze(3, setOf("rate"), dashboard, listOf(
            DashboardDependencyVersion("draft", 3, DashboardDocument(components = listOf(component("pressure", realtime = DashboardRealtimeBinding(9))))),
        )))
    }
}

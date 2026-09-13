package ai.moying.iview.query

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertEquals

class DashboardDocumentValidatorTest {
    @Test fun `components can only bind published data models`() {
        val draft = DataModel(1, 1, "m", "", DataModelStatus.DRAFT, 1, null, Instant.EPOCH, Instant.EPOCH)
        val validator = DashboardDocumentValidator { draft }
        val document = DashboardDocument(components = listOf(DashboardComponent("c1", "chart", 0, 0, 4, 3, modelId = 1, fields = listOf("count"))))
        assertFailsWith<DashboardValidationException> { validator.validate(document) }
    }

    @Test fun `variables styles and interactions are retained in a valid document`() {
        val published = DataModel(1, 1, "m", "", DataModelStatus.PUBLISHED, 1, 1, Instant.EPOCH, Instant.EPOCH)
        val document = DashboardDocument(
            variables = listOf(DashboardVariable("line_id", "A01")),
            components = listOf(DashboardComponent(
                "c1", "chart", 0, 0, 4, 3, modelId = 1, fields = listOf("rate"),
                conditionalStyles = listOf(DashboardConditionalStyle("rate", "lt", "80", mapOf("color" to "#f00"))),
                interactions = listOf(DashboardInteraction("click", "set_variable", parameters = mapOf("line_id" to "\${selectedLine}"))),
            )),
        )
        assertEquals(document, DashboardDocumentValidator { published }.validate(document))
    }

    @Test fun `invalid conditional styles and navigation are rejected`() {
        val published = DataModel(1, 1, "m", "", DataModelStatus.PUBLISHED, 1, 1, Instant.EPOCH, Instant.EPOCH)
        val validator = DashboardDocumentValidator { published }
        assertFailsWith<DashboardValidationException> {
            validator.validate(DashboardDocument(components = listOf(
                DashboardComponent("c1", "metric", 0, 0, 1, 1, conditionalStyles = listOf(
                    DashboardConditionalStyle("value", "gt", style = mapOf("color" to "red")),
                )),
            )))
        }
        assertFailsWith<DashboardValidationException> {
            validator.validate(DashboardDocument(components = listOf(
                DashboardComponent("c1", "metric", 0, 0, 1, 1, interactions = listOf(DashboardInteraction("click", "navigate"))),
            )))
        }
    }

    @Test fun `tuple component can bind fields from a published model`() {
        val published = DataModel(1, 1, "m", "", DataModelStatus.PUBLISHED, 1, 1, Instant.EPOCH, Instant.EPOCH)
        val document = DashboardDocument(components = listOf(DashboardComponent("tuple", "tuple", 0, 0, 4, 3, modelId = 1, fields = listOf("line", "count"))))
        assertEquals(document, DashboardDocumentValidator { published }.validate(document))
    }

    @Test fun `only containers can declare bounded repeat configuration`() {
        val published = DataModel(1, 1, "m", "", DataModelStatus.PUBLISHED, 1, 1, Instant.EPOCH, Instant.EPOCH)
        val validator = DashboardDocumentValidator { published }
        val child = DashboardComponent("child", "tuple", 0, 0, 2, 1, modelId = 1, fields = listOf("line"))
        val repeated = DashboardDocument(components = listOf(DashboardComponent("container", "container", 0, 0, 4, 3, modelId = 1, fields = listOf("line"), repeat = DashboardRepeatConfig(5, "horizontal", listOf("child"))), child))
        assertEquals(repeated, validator.validate(repeated))
        assertFailsWith<DashboardValidationException> { validator.validate(DashboardDocument(components = listOf(DashboardComponent("metric", "metric", 0, 0, 1, 1, repeat = DashboardRepeatConfig(childIds = listOf("missing")))))) }
    }

    @Test fun `nested repeat containers are bounded and cannot form cycles`() {
        val published = DataModel(1, 1, "m", "", DataModelStatus.PUBLISHED, 1, 1, Instant.EPOCH, Instant.EPOCH)
        val validator = DashboardDocumentValidator { published }
        val leaf = DashboardComponent("leaf", "metric", 0, 0, 1, 1, modelId = 1, fields = listOf("value"))
        val inner = DashboardComponent("inner", "container", 0, 0, 2, 2, modelId = 1, repeat = DashboardRepeatConfig(childIds = listOf("leaf")))
        val outer = DashboardComponent("outer", "container", 0, 0, 4, 3, modelId = 1, repeat = DashboardRepeatConfig(childIds = listOf("inner")))
        assertEquals(listOf(outer, inner, leaf), validator.validate(DashboardDocument(components = listOf(outer, inner, leaf))).components)

        val cyclicA = outer.copy(id = "a", repeat = DashboardRepeatConfig(childIds = listOf("b")))
        val cyclicB = inner.copy(id = "b", repeat = DashboardRepeatConfig(childIds = listOf("a")))
        assertFailsWith<DashboardValidationException> { validator.validate(DashboardDocument(components = listOf(cyclicA, cyclicB))) }

        val deep = (1..7).map { index ->
            DashboardComponent("c$index", "container", 0, 0, 1, 1, modelId = 1, repeat = DashboardRepeatConfig(childIds = listOf(if (index == 7) "leaf" else "c${index + 1}")))
        } + leaf
        assertFailsWith<DashboardValidationException> { validator.validate(DashboardDocument(components = deep)) }
    }

    @Test fun `industrial indicator components are supported`() {
        val validator = DashboardDocumentValidator { DataModel(it, 1, "m", "", DataModelStatus.PUBLISHED, 1, 1, Instant.EPOCH, Instant.EPOCH) }
        val components = listOf("gauge", "progress", "status", "clock").mapIndexed { index, type -> DashboardComponent(type, type, index, 0, 100, 80, modelId = if (type == "clock") null else 1, fields = if (type == "clock") emptyList() else listOf("value")) }
        assertEquals(components, validator.validate(DashboardDocument(components = components)).components)
    }

    @Test fun `managed dashboard refresh interval is bounded`() {
        val validator = DashboardDocumentValidator { DataModel(it, 1, "m", "", DataModelStatus.PUBLISHED, 1, 1, Instant.EPOCH, Instant.EPOCH) }
        assertEquals(5_000, validator.validate(DashboardDocument()).refreshIntervalMs)
        assertEquals(0, validator.validate(DashboardDocument(refreshIntervalMs = 0)).refreshIntervalMs)
        assertFailsWith<DashboardValidationException> { validator.validate(DashboardDocument(refreshIntervalMs = 500)) }
        assertFailsWith<DashboardValidationException> { validator.validate(DashboardDocument(refreshIntervalMs = 3_600_001)) }
    }
}

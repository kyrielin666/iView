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
}

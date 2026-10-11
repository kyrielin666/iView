package ai.moying.iview.datasource

import ai.moying.iview.query.DataModelDraft
import ai.moying.iview.query.DataModelField
import ai.moying.iview.query.DataModelService
import ai.moying.iview.query.DataSourceDraft
import ai.moying.iview.query.DataSourceService
import ai.moying.iview.query.DataSourceType
import ai.moying.iview.query.DatasetDraft
import ai.moying.iview.query.DatasetService
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class DataModelHeatmapApiTest {
    @Autowired private lateinit var mvc: MockMvc
    @Autowired private lateinit var sources: DataSourceService
    @Autowired private lateinit var datasets: DatasetService
    @Autowired private lateinit var models: DataModelService
    @Autowired private lateinit var execution: DataSourceExecutionService
    @Value("\${iview.data-sources.file-root:./data/file-sources}") private lateinit var configuredFileRoot: String

    @Test fun `published model aggregates more than 100 source rows and rejects unpublished fields`() {
        val name = "heatmap-${UUID.randomUUID()}.csv"
        val path = Path.of(configuredFileRoot).toAbsolutePath().normalize().resolve(name)
        Files.createDirectories(path.parent)
        Files.writeString(path, "shift,machine,quantity\n" + "早班,一号机,1\n".repeat(101))
        try {
            val source = sources.create(DataSourceDraft(name, DataSourceType.FILE, name, ""))
            val dataset = datasets.create(DatasetDraft(source.id, name = name, sql = "{}"))
            val model = models.create(DataModelDraft(dataset.id, name))
            val actualFields = execution.schema(dataset, emptyMap()).map { DataModelField(it.name, it.type, it.nullable) }
            org.junit.jupiter.api.Assertions.assertEquals("VARCHAR", actualFields.single { it.name == "quantity" }.type)
            models.sync(model.id, actualFields)
            val body = """{"x_field":"shift","y_field":"machine","value_field":"quantity"}"""
            mvc.perform(post("/api/v1/data-models/${model.id}/heatmap").contentType("application/json").content(body))
                .andExpect(status().isBadRequest)
            models.publish(model.id)
            mvc.perform(post("/api/v1/data-models/${model.id}/heatmap").contentType("application/json").content(body))
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.data.rows[0][2]").value(101))
                .andExpect(jsonPath("$.data.columns[2].name").value("quantity"))
                .andExpect(jsonPath("$.data.total_groups").value(1))
                .andExpect(jsonPath("$.data.truncated").value(false))
            mvc.perform(post("/api/v1/data-models/${model.id}/heatmap").contentType("application/json")
                .content("""{"x_field":"shift","y_field":"machine","value_field":"secret"}"""))
                .andExpect(status().isBadRequest)
        } finally { Files.deleteIfExists(path) }
    }
}

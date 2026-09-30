package ai.moying.iview.datasource

import ai.moying.iview.query.DataModelDraft
import ai.moying.iview.query.DataModelField
import ai.moying.iview.query.DataModelService
import ai.moying.iview.query.DataSourceDraft
import ai.moying.iview.query.DataSourceService
import ai.moying.iview.query.DataSourceType
import ai.moying.iview.query.DashboardDraft
import ai.moying.iview.query.DashboardService
import ai.moying.iview.query.DatasetDraft
import ai.moying.iview.query.DatasetService
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class DataModelDependencyApiTest {
    @Autowired private lateinit var mockMvc: MockMvc
    @Autowired private lateinit var sources: DataSourceService
    @Autowired private lateinit var datasets: DatasetService
    @Autowired private lateinit var models: DataModelService
    @Autowired private lateinit var dashboards: DashboardService

    @Test fun `publish plan reports a published dashboard after its draft no longer uses the model`() {
        val source = sources.create(DataSourceDraft("依赖分析文件", DataSourceType.FILE, "dependency.csv", ""))
        val dataset = datasets.create(DatasetDraft(source.id, name = "依赖分析数据集", sql = "{}"))
        val model = models.create(DataModelDraft(dataset.id, "依赖分析模型"))
        models.sync(model.id, listOf(DataModelField("rate", "INTEGER", false)))
        models.publish(model.id)
        val dashboard = dashboards.create(DashboardDraft(modelId = model.id, name = "已发布依赖", contentJson = """
            {"components":[{"id":"old","type":"metric","x":0,"y":0,"width":100,"height":50,"fields":["rate"]}]}
        """.trimIndent()))
        dashboards.publish(dashboard.id)
        dashboards.replaceContent(dashboard.id, """{"components":[]}""")
        models.sync(model.id, listOf(DataModelField("new_rate", "INTEGER", false)))

        mockMvc.perform(get("/api/v1/data-models/${model.id}/publish-plan"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.breaking_changes").value(1))
            .andExpect(jsonPath("$.data.dependency_count").value(1))
            .andExpect(jsonPath("$.data.dependencies[0].dashboard_id").value(dashboard.id))
            .andExpect(jsonPath("$.data.dependencies[0].sources[0]").value("published"))
            .andExpect(jsonPath("$.data.dependencies[0].component_ids[0]").value("old"))
            .andExpect(jsonPath("$.data.dependencies[0].affected_fields[0]").value("rate"))
        mockMvc.perform(delete("/api/v1/data-models/${model.id}"))
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.msg").value(org.hamcrest.Matchers.containsString("不能删除")))
    }
}

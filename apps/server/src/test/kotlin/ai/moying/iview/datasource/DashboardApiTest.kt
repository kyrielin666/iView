package ai.moying.iview.datasource

import com.fasterxml.jackson.databind.ObjectMapper
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.http.MediaType
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class DashboardApiTest {
    @Autowired private lateinit var mockMvc: MockMvc
    @Autowired private lateinit var objectMapper: ObjectMapper

    @Test fun `media map and carousel survive save publish and export`() {
        val response = mockMvc.perform(post("/api/v1/dashboards").contentType(MediaType.APPLICATION_JSON)
            .content("""{"name":"组件发布回归"}""")).andExpect(status().isCreated).andReturn().response.contentAsString
        val id = objectMapper.readTree(response).path("data").path("id").asLong()
        val document = """{"components":[
          {"id":"v","type":"video","x":0,"y":0,"width":300,"height":200,"props":{"url":"https://example.com/video.mp4","muted":true}},
          {"id":"m","type":"map","x":300,"y":0,"width":300,"height":200,"props":{"locations":[{"name":"A","x":20,"y":40}]}},
          {"id":"t","type":"carousel_table","x":600,"y":0,"width":300,"height":200,"props":{"interval_ms":2500}}
        ]}"""
        mockMvc.perform(put("/api/v1/dashboards/$id/document").contentType(MediaType.APPLICATION_JSON).content(document))
            .andExpect(status().isOk).andExpect(jsonPath("$.data.components[2].type").value("carousel_table"))
        mockMvc.perform(post("/api/v1/dashboards/$id/publish")).andExpect(status().isOk)
        mockMvc.perform(get("/api/v1/dashboards/$id/published")).andExpect(status().isOk)
            .andExpect(jsonPath("$.data.document.components[0].props.muted").value(true))
            .andExpect(jsonPath("$.data.document.components[1].props.locations[0].x").value(20))
        mockMvc.perform(get("/api/v1/dashboards/$id/export")).andExpect(status().isOk)
            .andExpect(jsonPath("$.data.document.components[2].props.interval_ms").value(2500))
    }

    @Test fun `dashboard can be copied and moved between folders`() {
        val folder = mockMvc.perform(post("/api/v1/dashboard-folders").contentType(MediaType.APPLICATION_JSON)
            .content("""{"name":"产线目录"}""")).andExpect(status().isCreated).andReturn().response.contentAsString
        val folderId = objectMapper.readTree(folder).path("data").path("id").asLong()
        val created = mockMvc.perform(post("/api/v1/dashboards").contentType(MediaType.APPLICATION_JSON)
            .content("""{"name":"原始看板","description":"复制保留","content_json":"{\"version\":1,\"components\":[]}"}"""))
            .andExpect(status().isCreated).andReturn().response.contentAsString
        val id = objectMapper.readTree(created).path("data").path("id").asLong()

        mockMvc.perform(put("/api/v1/dashboards/$id/folder").contentType(MediaType.APPLICATION_JSON)
            .content("""{"folder_id":$folderId}""")).andExpect(status().isOk)
            .andExpect(jsonPath("$.data.folder_id").value(folderId))
        val copied = mockMvc.perform(post("/api/v1/dashboards/$id/copy").contentType(MediaType.APPLICATION_JSON)
            .content("""{"name":"看板副本"}""")).andExpect(status().isOk)
            .andExpect(jsonPath("$.data.folder_id").value(folderId))
            .andExpect(jsonPath("$.data.description").value("复制保留"))
            .andReturn().response.contentAsString
        val copiedId = objectMapper.readTree(copied).path("data").path("id").asLong()
        mockMvc.perform(get("/api/v1/dashboards/$copiedId/preview")).andExpect(status().isOk)
            .andExpect(jsonPath("$.data.version").value(1))
        mockMvc.perform(post("/api/v1/dashboards/$id/publish")).andExpect(status().isOk)
        mockMvc.perform(delete("/api/v1/dashboards/$id")).andExpect(status().isOk)
        mockMvc.perform(get("/api/v1/dashboards/$id")).andExpect(status().isNotFound)
    }

    @Test fun `dashboard can be exported and imported as a validated portable document`() {
        val created = mockMvc.perform(post("/api/v1/dashboards").contentType(MediaType.APPLICATION_JSON).content("""
            {"name":"导出源看板","description":"可移植","content_json":"{}"}
        """.trimIndent()))
            .andExpect(status().isCreated).andExpect(jsonPath("$.code").value("200"))
            .andReturn().response.contentAsString
        val id = objectMapper.readTree(created).path("data").path("id").asLong()
        val exported = mockMvc.perform(get("/api/v1/dashboards/$id/export"))
            .andExpect(status().isOk).andExpect(jsonPath("$.data.format").value("iview-dashboard"))
            .andExpect(jsonPath("$.data.document.layout_mode").value("GRID"))
            .andReturn().response.contentAsString
        val exportPayload = objectMapper.readTree(exported).path("data")
        val request = objectMapper.createObjectNode().put("name", "导入副本").set<com.fasterxml.jackson.databind.JsonNode>("export", exportPayload)
        mockMvc.perform(post("/api/v1/dashboards/import").contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(request)))
            .andExpect(status().isCreated).andExpect(jsonPath("$.code").value("200"))
            .andExpect(jsonPath("$.data.name").value("导入副本"))
    }
}

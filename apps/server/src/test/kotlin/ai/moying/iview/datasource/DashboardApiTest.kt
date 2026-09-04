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
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class DashboardApiTest {
    @Autowired private lateinit var mockMvc: MockMvc
    @Autowired private lateinit var objectMapper: ObjectMapper

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

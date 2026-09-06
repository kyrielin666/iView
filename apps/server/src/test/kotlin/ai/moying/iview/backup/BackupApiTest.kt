package ai.moying.iview.backup

import com.fasterxml.jackson.databind.ObjectMapper
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

@SpringBootTest(properties = ["iview.backup.directory=./build/test-backups", "iview.auth.enforced=false"])
@AutoConfigureMockMvc
@ActiveProfiles("test")
class BackupApiTest {
    @Autowired private lateinit var mockMvc: MockMvc
    @Autowired private lateinit var objectMapper: ObjectMapper

    @Test fun `h2 backup is listed and checksum validated`() {
        val body = mockMvc.perform(post("/api/v1/backups")).andExpect(status().isCreated).andExpect(jsonPath("$.data.valid").value(true)).andReturn().response.contentAsString
        val name = objectMapper.readTree(body).path("data").path("name").asText()
        mockMvc.perform(post("/api/v1/backups/$name/validate")).andExpect(status().isOk).andExpect(jsonPath("$.data.name").value(name)).andExpect(jsonPath("$.data.sha256").isNotEmpty)
        mockMvc.perform(get("/api/v1/backups")).andExpect(status().isOk).andExpect(jsonPath("$.data[0].name").value(name))
    }
}

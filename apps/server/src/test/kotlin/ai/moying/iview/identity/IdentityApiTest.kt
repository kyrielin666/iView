package ai.moying.iview.identity

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
@org.springframework.test.context.TestPropertySource(properties = ["iview.auth.enforced=true"])
class IdentityApiTest {
    @Autowired private lateinit var mockMvc: MockMvc
    @Autowired private lateinit var objectMapper: ObjectMapper

    @Test fun `bootstrap administrator can login refresh logout and read current user`() {
        val login = mockMvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON).content("""{"username":"admin","password":"ChangeMe123!"}"""))
            .andExpect(status().isOk).andExpect(jsonPath("$.data.user.roles[0]").value("ADMIN")).andReturn().response.contentAsString
        val tokens = objectMapper.readTree(login).path("data")
        val access = tokens.path("access_token").asText(); val refresh = tokens.path("refresh_token").asText()
        mockMvc.perform(get("/api/v1/devices/statistics")).andExpect(status().isUnauthorized)
        mockMvc.perform(get("/api/v1/auth/me").header("Authorization", "Bearer $access")).andExpect(status().isOk).andExpect(jsonPath("$.data.username").value("admin"))
        val renewed = mockMvc.perform(post("/api/v1/auth/refresh").contentType(MediaType.APPLICATION_JSON).content("""{"refresh_token":"$refresh"}"""))
            .andExpect(status().isOk).andReturn().response.contentAsString
        val renewedToken = objectMapper.readTree(renewed).path("data").path("refresh_token").asText()
        mockMvc.perform(post("/api/v1/auth/logout").contentType(MediaType.APPLICATION_JSON).content("""{"refresh_token":"$renewedToken"}""")).andExpect(status().isOk)
        mockMvc.perform(post("/api/v1/auth/refresh").contentType(MediaType.APPLICATION_JSON).content("""{"refresh_token":"$renewedToken"}""")).andExpect(status().isUnauthorized)
    }
}

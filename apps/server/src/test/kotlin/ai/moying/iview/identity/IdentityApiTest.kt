package ai.moying.iview.identity

import com.fasterxml.jackson.databind.ObjectMapper
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
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
@org.springframework.test.context.TestPropertySource(properties = ["iview.auth.enforced=true"])
class IdentityApiTest {
    @Autowired private lateinit var mockMvc: MockMvc
    @Autowired private lateinit var objectMapper: ObjectMapper
    @Autowired private lateinit var jdbc: JdbcTemplate

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

    @Test fun `administrator manages roles users permissions and audit`() {
        val login = mockMvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON).content("""{"username":"admin","password":"ChangeMe123!"}""")).andExpect(status().isOk).andReturn().response.contentAsString
        val access = objectMapper.readTree(login).path("data").path("access_token").asText(); val authorization = "Bearer $access"
        mockMvc.perform(get("/api/v1/device-acceptance-runs").header("Authorization", authorization)).andExpect(status().isOk)
        val permission = mockMvc.perform(post("/api/v1/admin/permissions").header("Authorization", authorization).contentType(MediaType.APPLICATION_JSON).content("""{"code":"GET:/api/v1/devices/**","name":"读取设备"}"""))
            .andExpect(status().isCreated).andReturn().response.contentAsString
        val permissionId = objectMapper.readTree(permission).path("data").path("id").asLong()
        val role = mockMvc.perform(post("/api/v1/admin/roles").header("Authorization", authorization).contentType(MediaType.APPLICATION_JSON).content("""{"code":"VIEWER","name":"只读用户","permission_codes":["GET:/api/v1/devices/**"]}"""))
            .andExpect(status().isCreated).andExpect(jsonPath("$.data.permissions[0]").value("GET:/api/v1/devices/**")).andReturn().response.contentAsString
        val roleId = objectMapper.readTree(role).path("data").path("id").asLong()
        val suffix = System.nanoTime().toString().takeLast(8)
        jdbc.update("INSERT INTO iview_device_template(template_code,template_name,protocol_type,protocol_config,collect_interval_ms,timeout_ms,description) VALUES(?,?,?,?,?,?,?)", "scope$suffix", "范围模板", "MODBUS_TCP", "{}", 1000, 5000, "")
        val templateId = jdbc.queryForObject("SELECT id FROM iview_device_template WHERE template_code=?", Long::class.java, "scope$suffix")!!
        jdbc.update("INSERT INTO iview_device(device_sn,device_name,template_id,config_json,enabled,description) VALUES(?,?,?,?,?,?)", "scope-a$suffix", "范围设备 A", templateId, "{}", true, "")
        jdbc.update("INSERT INTO iview_device(device_sn,device_name,template_id,config_json,enabled,description) VALUES(?,?,?,?,?,?)", "scope-b$suffix", "范围设备 B", templateId, "{}", true, "")
        val scopedId = jdbc.queryForObject("SELECT id FROM iview_device WHERE device_sn=?", Long::class.java, "scope-a$suffix")!!
        val blockedId = jdbc.queryForObject("SELECT id FROM iview_device WHERE device_sn=?", Long::class.java, "scope-b$suffix")!!
        val user = mockMvc.perform(post("/api/v1/admin/users").header("Authorization", authorization).contentType(MediaType.APPLICATION_JSON).content("""{"username":"viewer","display_name":"查看人员","password":"ViewerPass123!","enabled":true,"role_codes":["VIEWER"],"device_ids":[$scopedId]}"""))
            .andExpect(status().isCreated).andExpect(jsonPath("$.data.roles[0]").value("VIEWER")).andReturn().response.contentAsString
        val userId = objectMapper.readTree(user).path("data").path("id").asLong()
        val viewerLogin = mockMvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON).content("""{"username":"viewer","password":"ViewerPass123!"}""")).andExpect(status().isOk).andReturn().response.contentAsString
        val viewerAccess = objectMapper.readTree(viewerLogin).path("data").path("access_token").asText()
        mockMvc.perform(get("/api/v1/devices").header("Authorization", "Bearer $viewerAccess")).andExpect(status().isOk).andExpect(jsonPath("$.data.total").value(1))
        mockMvc.perform(get("/api/v1/devices/$scopedId").header("Authorization", "Bearer $viewerAccess")).andExpect(status().isOk)
        mockMvc.perform(get("/api/v1/devices/$blockedId").header("Authorization", "Bearer $viewerAccess")).andExpect(status().isForbidden)
        mockMvc.perform(get("/api/v1/devices/statistics").header("Authorization", "Bearer $viewerAccess")).andExpect(status().isOk)
        mockMvc.perform(get("/api/v1/admin/users").header("Authorization", "Bearer $viewerAccess")).andExpect(status().isForbidden)
        mockMvc.perform(put("/api/v1/admin/users/$userId").header("Authorization", authorization).contentType(MediaType.APPLICATION_JSON).content("""{"display_name":"查看人员 2","enabled":false,"role_codes":["VIEWER"]}""")).andExpect(status().isOk)
        mockMvc.perform(get("/api/v1/auth/me").header("Authorization", "Bearer $viewerAccess")).andExpect(status().isUnauthorized)
        mockMvc.perform(delete("/api/v1/admin/users/$userId").header("Authorization", authorization)).andExpect(status().isOk)
        mockMvc.perform(delete("/api/v1/admin/roles/$roleId").header("Authorization", authorization)).andExpect(status().isOk)
        mockMvc.perform(delete("/api/v1/admin/permissions/$permissionId").header("Authorization", authorization)).andExpect(status().isOk)
        mockMvc.perform(get("/api/v1/admin/audit").header("Authorization", authorization)).andExpect(status().isOk).andExpect(jsonPath("$.data[0].actor").value("admin"))
    }
}

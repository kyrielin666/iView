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
import org.hamcrest.Matchers.hasItems
import ai.moying.iview.device.DeviceRealtimeHub
import ai.moying.iview.device.DeviceScopeGuard
import org.springframework.mock.web.MockHttpServletRequest
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@org.springframework.test.context.TestPropertySource(properties = ["iview.auth.enforced=true"])
class IdentityApiTest {
    @Autowired private lateinit var mockMvc: MockMvc
    @Autowired private lateinit var objectMapper: ObjectMapper
    @Autowired private lateinit var jdbc: JdbcTemplate
    @Autowired private lateinit var identities: IdentityService
    @Autowired private lateinit var deviceScope: DeviceScopeGuard
    @Autowired private lateinit var realtimeHub: DeviceRealtimeHub

    @Test fun `model preview permission also authorizes heatmap aggregation without broader write access`() {
        val login = mockMvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON).content("""{"username":"admin","password":"ChangeMe123!"}"""))
            .andExpect(status().isOk).andReturn().response.contentAsString
        val admin = "Bearer ${objectMapper.readTree(login).path("data").path("access_token").asText()}"
        val suffix = java.util.UUID.randomUUID().toString().take(8)
        val roleCode = "HEAT${suffix.uppercase()}"
        val permission = "POST:/api/v1/data-models/*/preview"
        if (jdbc.queryForObject("SELECT COUNT(*) FROM iview_permission WHERE permission_code=?", Int::class.java, permission) == 0) {
            mockMvc.perform(post("/api/v1/admin/permissions").header("Authorization", admin).contentType(MediaType.APPLICATION_JSON)
                .content("""{"code":"$permission","name":"模型预览"}""")).andExpect(status().isCreated)
        }
        mockMvc.perform(post("/api/v1/admin/roles").header("Authorization", admin).contentType(MediaType.APPLICATION_JSON)
            .content("""{"code":"$roleCode","name":"热力图查看","permission_codes":["$permission"]}""")).andExpect(status().isCreated)
        mockMvc.perform(post("/api/v1/admin/users").header("Authorization", admin).contentType(MediaType.APPLICATION_JSON)
            .content("""{"username":"heat$suffix","display_name":"热力图查看","password":"HeatViewer123!","role_codes":["$roleCode"]}""")).andExpect(status().isCreated)
        val viewerLogin = mockMvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
            .content("""{"username":"heat$suffix","password":"HeatViewer123!"}""")).andExpect(status().isOk).andReturn().response.contentAsString
        val viewer = "Bearer ${objectMapper.readTree(viewerLogin).path("data").path("access_token").asText()}"
        assertTrue(identities.permitted(identities.current(viewer), "POST", "/api/v1/data-models/99999999/preview"))
        mockMvc.perform(post("/api/v1/data-models/99999999/heatmap").header("Authorization", viewer).contentType(MediaType.APPLICATION_JSON)
            .content("""{"x_field":"x","y_field":"y","value_field":"v"}""")).andExpect(status().isNotFound)
        mockMvc.perform(post("/api/v1/data-models/99999999/publish").header("Authorization", viewer).contentType(MediaType.APPLICATION_JSON)
            .content("{}")) .andExpect(status().isForbidden)
    }

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

    @Test fun `repeated password failures lock the account and are audited`() {
        val login = mockMvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON).content("""{"username":"admin","password":"ChangeMe123!"}"""))
            .andExpect(status().isOk).andReturn().response.contentAsString
        val authorization = "Bearer ${objectMapper.readTree(login).path("data").path("access_token").asText()}"
        val username = "locked${System.nanoTime().toString().takeLast(8)}"
        val created = mockMvc.perform(post("/api/v1/admin/users").header("Authorization", authorization).contentType(MediaType.APPLICATION_JSON)
            .content("""{"username":"$username","display_name":"锁定测试","password":"LockedPass123!","enabled":true,"role_codes":["ADMIN"]}"""))
            .andExpect(status().isCreated).andReturn().response.contentAsString
        val userId = objectMapper.readTree(created).path("data").path("id").asLong()
        repeat(5) {
            mockMvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON).content("""{"username":"$username","password":"wrong-password"}"""))
                .andExpect(status().isUnauthorized)
        }
        mockMvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON).content("""{"username":"$username","password":"LockedPass123!"}"""))
            .andExpect(status().isUnauthorized).andExpect(jsonPath("$.msg").value("登录失败次数过多，账号暂时锁定"))
        mockMvc.perform(get("/api/v1/admin/auth-audit").header("Authorization", authorization)).andExpect(status().isOk)
            .andExpect(jsonPath("$.data[0].username").value(username)).andExpect(jsonPath("$.data[0].reason").value("LOCKED"))
        mockMvc.perform(post("/api/v1/admin/users/$userId/unlock").header("Authorization", authorization)).andExpect(status().isOk)
            .andExpect(jsonPath("$.data.failed_login_count").value(0)).andExpect(jsonPath("$.data.locked_until").doesNotExist())
        mockMvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON).content("""{"username":"$username","password":"LockedPass123!"}"""))
            .andExpect(status().isOk)
        mockMvc.perform(delete("/api/v1/admin/users/$userId").header("Authorization", authorization)).andExpect(status().isOk)
    }

    @Test fun `administrator manages roles users permissions and audit`() {
        val login = mockMvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON).content("""{"username":"admin","password":"ChangeMe123!"}""")).andExpect(status().isOk).andReturn().response.contentAsString
        val access = objectMapper.readTree(login).path("data").path("access_token").asText(); val authorization = "Bearer $access"
        mockMvc.perform(get("/api/v1/device-acceptance-runs").header("Authorization", authorization)).andExpect(status().isOk)
        val permission = mockMvc.perform(post("/api/v1/admin/permissions").header("Authorization", authorization).contentType(MediaType.APPLICATION_JSON).content("""{"code":"GET:/api/v1/devices/**","name":"读取设备"}"""))
            .andExpect(status().isCreated).andReturn().response.contentAsString
        val permissionId = objectMapper.readTree(permission).path("data").path("id").asLong()
        val alarmPermission = mockMvc.perform(post("/api/v1/admin/permissions").header("Authorization", authorization).contentType(MediaType.APPLICATION_JSON).content("""{"code":"GET:/api/v1/device-alarms","name":"读取告警"}"""))
            .andExpect(status().isCreated).andReturn().response.contentAsString
        val alarmPermissionId = objectMapper.readTree(alarmPermission).path("data").path("id").asLong()
        val acknowledgePermission = mockMvc.perform(post("/api/v1/admin/permissions").header("Authorization", authorization).contentType(MediaType.APPLICATION_JSON).content("""{"code":"POST:/api/v1/device-alarms/*/acknowledge","name":"确认告警"}"""))
            .andExpect(status().isCreated).andReturn().response.contentAsString
        val acknowledgePermissionId = objectMapper.readTree(acknowledgePermission).path("data").path("id").asLong()
        val role = mockMvc.perform(post("/api/v1/admin/roles").header("Authorization", authorization).contentType(MediaType.APPLICATION_JSON).content("""{"code":"VIEWER","name":"只读用户","permission_codes":["GET:/api/v1/devices/**","GET:/api/v1/device-alarms","POST:/api/v1/device-alarms/*/acknowledge"]}"""))
            .andExpect(status().isCreated).andExpect(jsonPath("$.data.permissions", hasItems("GET:/api/v1/devices/**", "GET:/api/v1/device-alarms", "POST:/api/v1/device-alarms/*/acknowledge"))).andReturn().response.contentAsString
        val roleId = objectMapper.readTree(role).path("data").path("id").asLong()
        val suffix = System.nanoTime().toString().takeLast(8)
        jdbc.update("INSERT INTO iview_device_template(template_code,template_name,protocol_type,protocol_config,collect_interval_ms,timeout_ms,description) VALUES(?,?,?,?,?,?,?)", "scope$suffix", "范围模板", "MODBUS_TCP", "{}", 1000, 5000, "")
        val templateId = jdbc.queryForObject("SELECT id FROM iview_device_template WHERE template_code=?", Long::class.java, "scope$suffix")!!
        jdbc.update("INSERT INTO iview_device(device_sn,device_name,template_id,config_json,enabled,description) VALUES(?,?,?,?,?,?)", "scope-a$suffix", "范围设备 A", templateId, "{}", true, "")
        jdbc.update("INSERT INTO iview_device(device_sn,device_name,template_id,config_json,enabled,description) VALUES(?,?,?,?,?,?)", "scope-b$suffix", "范围设备 B", templateId, "{}", true, "")
        val scopedId = jdbc.queryForObject("SELECT id FROM iview_device WHERE device_sn=?", Long::class.java, "scope-a$suffix")!!
        val blockedId = jdbc.queryForObject("SELECT id FROM iview_device WHERE device_sn=?", Long::class.java, "scope-b$suffix")!!
        jdbc.update("INSERT INTO iview_device_alarm(device_id,abnormal_at,alarm_content,status) VALUES(?,?,?,'ACTIVE')", scopedId, java.time.Instant.now(), "范围内告警")
        jdbc.update("INSERT INTO iview_device_alarm(device_id,abnormal_at,alarm_content,status) VALUES(?,?,?,'ACTIVE')", blockedId, java.time.Instant.now(), "范围外告警")
        val scopedAlarmId = jdbc.queryForObject("SELECT id FROM iview_device_alarm WHERE device_id=? ORDER BY id DESC LIMIT 1", Long::class.java, scopedId)!!
        val blockedAlarmId = jdbc.queryForObject("SELECT id FROM iview_device_alarm WHERE device_id=? ORDER BY id DESC LIMIT 1", Long::class.java, blockedId)!!
        val user = mockMvc.perform(post("/api/v1/admin/users").header("Authorization", authorization).contentType(MediaType.APPLICATION_JSON).content("""{"username":"viewer","display_name":"查看人员","password":"ViewerPass123!","enabled":true,"role_codes":["VIEWER"],"device_ids":[$scopedId]}"""))
            .andExpect(status().isCreated).andExpect(jsonPath("$.data.roles[0]").value("VIEWER")).andReturn().response.contentAsString
        val userId = objectMapper.readTree(user).path("data").path("id").asLong()
        val viewerLogin = mockMvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON).content("""{"username":"viewer","password":"ViewerPass123!"}""")).andExpect(status().isOk).andReturn().response.contentAsString
        val viewerAccess = objectMapper.readTree(viewerLogin).path("data").path("access_token").asText()
        val subscriptionRequest = MockHttpServletRequest().apply {
            addHeader("Authorization", "Bearer $viewerAccess")
            setAttribute("iview.user", identities.current("Bearer $viewerAccess"))
        }
        val stillAuthorized = deviceScope.subscriptionAuthorization(subscriptionRequest, scopedId)
        assertTrue(stillAuthorized())
        realtimeHub.subscribe(scopedId, stillAuthorized)
        assertEquals(1, realtimeHub.subscriberCount(scopedId))
        mockMvc.perform(get("/api/v1/auth/me").header("Authorization", "Bearer $viewerAccess")).andExpect(status().isOk).andExpect(jsonPath("$.data.username").value("viewer"))
        mockMvc.perform(get("/api/v1/navigation").header("Authorization", "Bearer $viewerAccess")).andExpect(status().isOk)
            .andExpect(jsonPath("$.data[*].route", hasItems("devices", "alarms")))
        mockMvc.perform(get("/api/v1/devices").header("Authorization", "Bearer $viewerAccess")).andExpect(status().isOk).andExpect(jsonPath("$.data.total").value(1))
        mockMvc.perform(get("/api/v1/devices/$scopedId").header("Authorization", "Bearer $viewerAccess")).andExpect(status().isOk)
        mockMvc.perform(get("/api/v1/devices/$blockedId").header("Authorization", "Bearer $viewerAccess")).andExpect(status().isForbidden)
        mockMvc.perform(get("/api/v1/device-alarms").header("Authorization", "Bearer $viewerAccess")).andExpect(status().isOk)
            .andExpect(jsonPath("$.data.total").value(1)).andExpect(jsonPath("$.data.list[0].device_id").value(scopedId))
        mockMvc.perform(post("/api/v1/device-alarms/$blockedAlarmId/acknowledge").header("Authorization", "Bearer $viewerAccess").contentType(MediaType.APPLICATION_JSON).content("""{"note":"越权确认"}"""))
            .andExpect(status().isForbidden)
        mockMvc.perform(post("/api/v1/device-alarms/$scopedAlarmId/acknowledge").header("Authorization", "Bearer $viewerAccess").contentType(MediaType.APPLICATION_JSON).content("""{"note":"已现场检查"}"""))
            .andExpect(status().isOk).andExpect(jsonPath("$.data.acknowledged_by").value("viewer")).andExpect(jsonPath("$.data.acknowledgement").value("已现场检查"))
        mockMvc.perform(get("/api/v1/devices/statistics").header("Authorization", "Bearer $viewerAccess")).andExpect(status().isOk)
            .andExpect(jsonPath("$.data.total").value(1)).andExpect(jsonPath("$.data.enabled").value(1))
        mockMvc.perform(put("/api/v1/admin/users/$userId").header("Authorization", authorization).contentType(MediaType.APPLICATION_JSON)
            .content("""{"display_name":"无设备权限","enabled":true,"role_codes":["VIEWER"],"device_ids":[]}""")).andExpect(status().isOk)
        assertFalse(stillAuthorized())
        realtimeHub.revokeUnauthorized()
        assertEquals(0, realtimeHub.subscriberCount(scopedId))
        mockMvc.perform(get("/api/v1/devices/statistics").header("Authorization", "Bearer $viewerAccess")).andExpect(status().isOk)
            .andExpect(jsonPath("$.data.total").value(0)).andExpect(jsonPath("$.data.enabled").value(0))
        mockMvc.perform(get("/api/v1/admin/users").header("Authorization", "Bearer $viewerAccess")).andExpect(status().isForbidden)
        mockMvc.perform(put("/api/v1/admin/users/$userId").header("Authorization", authorization).contentType(MediaType.APPLICATION_JSON).content("""{"display_name":"查看人员 2","enabled":false,"role_codes":["VIEWER"]}""")).andExpect(status().isOk)
        mockMvc.perform(get("/api/v1/auth/me").header("Authorization", "Bearer $viewerAccess")).andExpect(status().isUnauthorized)
        mockMvc.perform(delete("/api/v1/admin/users/$userId").header("Authorization", authorization)).andExpect(status().isOk)
        mockMvc.perform(delete("/api/v1/admin/roles/$roleId").header("Authorization", authorization)).andExpect(status().isOk)
        mockMvc.perform(delete("/api/v1/admin/permissions/$permissionId").header("Authorization", authorization)).andExpect(status().isOk)
        mockMvc.perform(delete("/api/v1/admin/permissions/$alarmPermissionId").header("Authorization", authorization)).andExpect(status().isOk)
        mockMvc.perform(delete("/api/v1/admin/permissions/$acknowledgePermissionId").header("Authorization", authorization)).andExpect(status().isOk)
        mockMvc.perform(get("/api/v1/admin/audit").header("Authorization", authorization)).andExpect(status().isOk).andExpect(jsonPath("$.data[0].actor").value("admin"))
    }
}

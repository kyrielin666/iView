package ai.moying.iview.device

import com.fasterxml.jackson.databind.ObjectMapper
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.http.MediaType
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.net.ServerSocket
import java.io.DataInputStream
import java.io.DataOutputStream
import kotlin.concurrent.thread

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class DeviceCatalogApiTest {
    @Autowired private lateinit var mockMvc: MockMvc
    @Autowired private lateinit var objectMapper: ObjectMapper

    @Test
    fun `device catalog supports complete create list protect disable and delete flow`() {
        val groupId = postAndId("/api/v1/device-groups", """
            {"group_code":"line-a","group_name":"一车间A线","description":"主生产线"}
        """)
        mockMvc.perform(
            post("/api/v1/device-groups").contentType(MediaType.APPLICATION_JSON)
                .content("""{"group_code":"line-a","group_name":"重复编码"}""")
        ).andExpect(status().isConflict)
            .andExpect(jsonPath("$.code").value("409"))
        val templateId = postAndId("/api/v1/templates", """
            {
              "template_code":"modbus_cnc",
              "template_name":"Modbus CNC",
              "protocol_type":"modbus_tcp",
              "protocol_cfg":{"port":502,"slave_id":1},
              "collect_interval":1000,
              "timeout":5000
            }
        """)
        val pointId = postAndId("/api/v1/templates/$templateId/points", """
            {
              "point_name":"运行状态",
              "point_code":"run_state",
              "data_type":"uint16",
              "address":"40001",
              "enabled":1,
              "point_config":{"register_type":"holding","byte_order":"big"}
            }
        """)
        mockMvc.perform(
            post("/api/v1/devices").contentType(MediaType.APPLICATION_JSON).content("""
                {"device_sn":"bad sn!","device_name":"错误设备","template_id":$templateId}
            """.trimIndent())
        ).andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.code").value("400"))
        val deviceId = postAndId("/api/v1/devices", """
            {
              "device_sn":"CNC_001",
              "device_name":"一号加工中心",
              "template_id":$templateId,
              "group_id":$groupId,
              "line_id":"A",
              "vendor_id":"Mitsubishi",
              "device_type_id":"CNC",
              "config_json":{"host":"192.168.1.20","port":502},
              "enabled":1
            }
        """)

        mockMvc.perform(get("/api/v1/devices").param("page", "1").param("page_size", "20").param("keyword", "CNC_001"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.code").value("200"))
            .andExpect(jsonPath("$.data.total").value(1))
            .andExpect(jsonPath("$.data.list[0].device_sn").value("CNC_001"))
            .andExpect(jsonPath("$.data.list[0].template.points[0].point_code").value("run_state"))

        mockMvc.perform(put("/api/v1/devices/$deviceId/disable"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.enabled").value(0))

        mockMvc.perform(delete("/api/v1/templates/$templateId"))
            .andExpect(status().isConflict)
            .andExpect(jsonPath("$.code").value("409"))

        mockMvc.perform(delete("/api/v1/devices/$deviceId")).andExpect(status().isOk)
        mockMvc.perform(delete("/api/v1/points/$pointId")).andExpect(status().isOk)
        mockMvc.perform(delete("/api/v1/templates/$templateId")).andExpect(status().isOk)
        mockMvc.perform(delete("/api/v1/device-groups/$groupId")).andExpect(status().isOk)
    }

    @Test
    fun `modbus device supports connection test and diagnosis endpoints`() {
        ServerSocket(0).use { server ->
            thread(name = "diagnostic-target", isDaemon = true) {
                repeat(2) { server.accept().close() }
            }
            val templateId = postAndId("/api/v1/templates", """
                {
                  "template_code":"modbus_diagnostic",
                  "template_name":"Modbus 诊断模板",
                  "protocol_type":"modbus_tcp",
                  "protocol_cfg":{"slave_id":1},
                  "collect_interval":1000,
                  "timeout":1000
                }
            """)
            val deviceId = postAndId("/api/v1/devices", """
                {
                  "device_sn":"PLC_DIAGNOSTIC",
                  "device_name":"诊断测试设备",
                  "template_id":$templateId,
                  "config_json":{"host":"127.0.0.1","port":${server.localPort}},
                  "enabled":1
                }
            """)

            mockMvc.perform(post("/api/v1/devices/$deviceId/test"))
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.data.connected").value(true))
            mockMvc.perform(post("/api/v1/devices/$deviceId/diagnose"))
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.data.overall_status").value("success"))
                .andExpect(jsonPath("$.data.steps[1].title").value("TCP 端口连通"))

            mockMvc.perform(delete("/api/v1/devices/$deviceId")).andExpect(status().isOk)
            mockMvc.perform(delete("/api/v1/templates/$templateId")).andExpect(status().isOk)
        }
    }

    @Test
    fun `manual collection persists samples and exposes realtime and history`() {
        ServerSocket(0).use { server ->
            thread(name = "collection-target", isDaemon = true) {
                server.accept().use { socket ->
                    val input = DataInputStream(socket.getInputStream())
                    val output = DataOutputStream(socket.getOutputStream())
                    val tx = input.readUnsignedShort()
                    input.readUnsignedShort()
                    val length = input.readUnsignedShort()
                    val unit = input.readUnsignedByte()
                    val function = input.readUnsignedByte()
                    ByteArray(length - 2).also(input::readFully)
                    output.writeShort(tx)
                    output.writeShort(0)
                    output.writeShort(5)
                    output.writeByte(unit)
                    output.writeByte(function)
                    output.write(byteArrayOf(2, 0, 99))
                    output.flush()
                }
            }
            val templateId = postAndId("/api/v1/templates", """
                {
                  "template_code":"modbus_collection",
                  "template_name":"Modbus 采集模板",
                  "protocol_type":"modbus_tcp",
                  "protocol_cfg":{"slave_id":1},
                  "collect_interval":1000,
                  "timeout":1000
                }
            """)
            val pointId = postAndId("/api/v1/templates/$templateId/points", """
                {
                  "point_name":"产量",
                  "point_code":"quantity",
                  "data_type":"uint16",
                  "address":"40001",
                  "enabled":1,
                  "point_config":{"register_type":"holding"}
                }
            """)
            val deviceId = postAndId("/api/v1/devices", """
                {
                  "device_sn":"PLC_COLLECTION",
                  "device_name":"采集测试设备",
                  "template_id":$templateId,
                  "config_json":{"host":"127.0.0.1","port":${server.localPort}},
                  "enabled":1
                }
            """)

            mockMvc.perform(post("/api/v1/devices/$deviceId/collect"))
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.data.success").value(true))
                .andExpect(jsonPath("$.data.values[0].value").value(99))
            mockMvc.perform(get("/api/v1/devices/$deviceId/realtime"))
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.data.online").value(true))
                .andExpect(jsonPath("$.data.points[0].quality").value("good"))
                .andExpect(jsonPath("$.data.points[0].value").value(99))
            mockMvc.perform(get("/api/v1/devices/$deviceId/history"))
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.data[0].point_code").value("quantity"))

            mockMvc.perform(delete("/api/v1/devices/$deviceId")).andExpect(status().isOk)
            mockMvc.perform(delete("/api/v1/points/$pointId")).andExpect(status().isOk)
            mockMvc.perform(delete("/api/v1/templates/$templateId")).andExpect(status().isOk)
        }
    }

    private fun postAndId(path: String, json: String): Long {
        val response = mockMvc.perform(
            post(path).contentType(MediaType.APPLICATION_JSON).content(json.trimIndent())
        ).andExpect(status().isCreated)
            .andExpect(jsonPath("$.code").value("200"))
            .andReturn().response.contentAsString
        return objectMapper.readTree(response).path("data").path("id").asLong()
    }
}

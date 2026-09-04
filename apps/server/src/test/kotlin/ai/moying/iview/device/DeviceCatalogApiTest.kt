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
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import ai.moying.iview.outbound.OutboundPublisher

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class DeviceCatalogApiTest {
    @Autowired private lateinit var mockMvc: MockMvc
    @Autowired private lateinit var objectMapper: ObjectMapper
    @Autowired private lateinit var outboundPublisher: OutboundPublisher

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
            mockMvc.perform(get("/api/v1/devices/statistics"))
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.data.online").isNumber)
                .andExpect(jsonPath("$.data.session_count").isNumber)
            mockMvc.perform(get("/api/v1/collector/sessions"))
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.data").isArray)

            mockMvc.perform(delete("/api/v1/devices/$deviceId")).andExpect(status().isOk)
            mockMvc.perform(delete("/api/v1/points/$pointId")).andExpect(status().isOk)
            mockMvc.perform(delete("/api/v1/templates/$templateId")).andExpect(status().isOk)
        }
    }

    @Test
    fun `control point executes multi target writes and records success log`() {
        ServerSocket(0).use { server ->
            thread(name = "control-write-target", isDaemon = true) {
                server.accept().use { socket ->
                    val input = DataInputStream(socket.getInputStream())
                    val output = DataOutputStream(socket.getOutputStream())
                    repeat(2) {
                        val tx = input.readUnsignedShort()
                        input.readUnsignedShort()
                        val length = input.readUnsignedShort()
                        val unit = input.readUnsignedByte()
                        val function = input.readUnsignedByte()
                        val payload = ByteArray(length - 2).also(input::readFully)
                        output.writeShort(tx)
                        output.writeShort(0)
                        output.writeShort(6)
                        output.writeByte(unit)
                        output.writeByte(function)
                        output.write(payload.copyOfRange(0, 4))
                        output.flush()
                    }
                }
            }
            val templateId = postAndId("/api/v1/templates", """
                {
                  "template_code":"modbus_control_multi",
                  "template_name":"Modbus 多目标控制模板",
                  "protocol_type":"modbus_tcp",
                  "protocol_cfg":{"slave_id":1},
                  "collect_interval":1000,
                  "timeout":1000
                }
            """)
            val controlPointId = postAndId("/api/v1/control-points", """
                {
                  "template_id":$templateId,
                  "point_name":"启停与速度",
                  "point_code":"start_speed",
                  "data_type":"uint16",
                  "address":"40001",
                  "point_config":{"register_type":"holding","writeTargets":[
                    {"key":"start","name":"启动","address":"40001","data_type":"uint16"},
                    {"key":"speed","name":"速度","address":"40002","data_type":"uint16"}
                  ]}
                }
            """)
            val deviceId = postAndId("/api/v1/devices", """
                {
                  "device_sn":"PLC_CONTROL_MULTI",
                  "device_name":"多目标控制测试设备",
                  "template_id":$templateId,
                  "config_json":{"host":"127.0.0.1","port":${server.localPort}},
                  "enabled":1
                }
            """)

            mockMvc.perform(
                post("/api/v1/control-points/$controlPointId/execute")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"device_id":$deviceId,"value":{"start":1,"speed":1500}}""")
            ).andExpect(status().isOk)
                .andExpect(jsonPath("$.data.successful").value(true))
                .andExpect(jsonPath("$.data.write_count").value(2))
            mockMvc.perform(get("/api/v1/control-logs").param("device_id", deviceId.toString()))
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.list[0].status").value(1))
                .andExpect(jsonPath("$.data.list[0].device_name").value("多目标控制测试设备"))

            mockMvc.perform(delete("/api/v1/devices/$deviceId")).andExpect(status().isOk)
            mockMvc.perform(delete("/api/v1/control-points/$controlPointId")).andExpect(status().isOk)
            mockMvc.perform(delete("/api/v1/templates/$templateId")).andExpect(status().isOk)
        }
    }

    @Test
    fun `readonly control failure returns error and records failed audit log`() {
        ServerSocket(0).use { server ->
            thread(name = "readonly-control-target", isDaemon = true) { server.accept().close() }
            val templateId = postAndId("/api/v1/templates", """
                {"template_code":"readonly_control","template_name":"只读控制模板",
                 "protocol_type":"modbus_tcp","protocol_cfg":{"slave_id":1},
                 "collect_interval":1000,"timeout":1000}
            """)
            val controlPointId = postAndId("/api/v1/control-points", """
                {"template_id":$templateId,"point_name":"错误只读点","point_code":"readonly",
                 "data_type":"uint16","address":"30001","point_config":{"register_type":"input"}}
            """)
            val deviceId = postAndId("/api/v1/devices", """
                {"device_sn":"PLC_READONLY_CONTROL","device_name":"只读控制测试设备",
                 "template_id":$templateId,"config_json":{"host":"127.0.0.1","port":${server.localPort}},"enabled":1}
            """)

            mockMvc.perform(
                post("/api/v1/control-points/$controlPointId/execute")
                    .contentType(MediaType.APPLICATION_JSON).content("""{"device_id":$deviceId,"value":1}""")
            ).andExpect(status().isInternalServerError)
                .andExpect(jsonPath("$.code").value("500"))
            mockMvc.perform(get("/api/v1/control-logs").param("control_point_id", controlPointId.toString()))
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.data.list[0].status").value(0))
                .andExpect(jsonPath("$.data.list[0].message").value(org.hamcrest.Matchers.containsString("不支持写入")))

            mockMvc.perform(delete("/api/v1/devices/$deviceId")).andExpect(status().isOk)
            mockMvc.perform(delete("/api/v1/control-points/$controlPointId")).andExpect(status().isOk)
            mockMvc.perform(delete("/api/v1/templates/$templateId")).andExpect(status().isOk)
        }
    }

    @Test
    fun `manual collection asynchronously pushes http payload and stores audit log`() {
        val received = AtomicReference<String>()
        val pushed = CountDownLatch(1)
        val http = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/telemetry") { exchange ->
                received.set(exchange.requestBody.bufferedReader().readText())
                exchange.sendResponseHeaders(204, -1)
                exchange.close()
                pushed.countDown()
            }
            start()
        }
        try {
            ServerSocket(0).use { modbus ->
                thread(name = "push-collection-target", isDaemon = true) {
                    modbus.accept().use { socket ->
                        val input = DataInputStream(socket.getInputStream())
                        val output = DataOutputStream(socket.getOutputStream())
                        val tx = input.readUnsignedShort()
                        input.readUnsignedShort()
                        val length = input.readUnsignedShort()
                        val unit = input.readUnsignedByte()
                        val function = input.readUnsignedByte()
                        ByteArray(length - 2).also(input::readFully)
                        output.writeShort(tx); output.writeShort(0); output.writeShort(5)
                        output.writeByte(unit); output.writeByte(function); output.write(byteArrayOf(2, 0, 42)); output.flush()
                    }
                }
                val templateId = postAndId("/api/v1/templates", """
                    {"template_code":"push_collection","template_name":"推送采集模板","protocol_type":"modbus_tcp",
                     "protocol_cfg":{"slave_id":1},"collect_interval":1000,"timeout":1000}
                """)
                val pointId = postAndId("/api/v1/templates/$templateId/points", """
                    {"point_name":"产量","point_code":"quantity","data_type":"uint16","address":"40001",
                     "enabled":1,"point_config":{"register_type":"holding"}}
                """)
                val deviceId = postAndId("/api/v1/devices", """
                    {"device_sn":"PLC_PUSH_COLLECTION","device_name":"推送测试设备","template_id":$templateId,
                     "config_json":{"host":"127.0.0.1","port":${modbus.localPort}},"enabled":1}
                """)
                val pushConfigId = postAndId("/api/v1/push-configs", """
                    {"name":"本地MES","push_type":"http","enabled":1,"timeout":1000,"retry_count":0,
                     "retry_delay":0,"config":{"url":"http://127.0.0.1:${http.address.port}/telemetry","method":"POST",
                     "headers":{"X-IView-Test":"true"}}}
                """)

                mockMvc.perform(post("/api/v1/devices/$deviceId/collect"))
                    .andExpect(status().isOk).andExpect(jsonPath("$.data.success").value(true))
                check(pushed.await(3, TimeUnit.SECONDS)) { "HTTP push was not received: ${outboundPublisher.status()}" }
                val payload = objectMapper.readTree(received.get())
                kotlin.test.assertEquals("PLC_PUSH_COLLECTION", payload.path("device_sn").asText())
                kotlin.test.assertEquals(42, payload.path("data").path("quantity").asInt())

                var logTotal = 0
                var checks = 0
                while (logTotal == 0 && checks++ < 20) {
                    val body = mockMvc.perform(get("/api/v1/push-logs").param("config_id", pushConfigId.toString()))
                        .andExpect(status().isOk).andReturn().response.contentAsString
                    logTotal = objectMapper.readTree(body).path("data").path("total").asInt()
                    if (logTotal == 0) Thread.sleep(25)
                }
                kotlin.test.assertEquals(1, logTotal)
                mockMvc.perform(get("/api/v1/push-logs").param("config_id", pushConfigId.toString()))
                    .andExpect(jsonPath("$.data.list[0].status").value(1))
                    .andExpect(jsonPath("$.data.list[0].device_sn").value("PLC_PUSH_COLLECTION"))
                mockMvc.perform(post("/api/v1/push-configs/$pushConfigId/disable"))
                    .andExpect(status().isOk).andExpect(jsonPath("$.data.enabled").value(0))

                mockMvc.perform(delete("/api/v1/push-configs/$pushConfigId")).andExpect(status().isOk)
                mockMvc.perform(delete("/api/v1/devices/$deviceId")).andExpect(status().isOk)
                mockMvc.perform(delete("/api/v1/points/$pointId")).andExpect(status().isOk)
                mockMvc.perform(delete("/api/v1/templates/$templateId")).andExpect(status().isOk)
            }
        } finally {
            http.stop(0)
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

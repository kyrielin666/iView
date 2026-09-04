package ai.moying.iview.outbound

import com.fasterxml.jackson.databind.ObjectMapper
import org.eclipse.paho.client.mqttv3.MqttClient
import org.eclipse.paho.client.mqttv3.MqttConnectOptions
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap

class OutboundAdapterRegistry(private val objectMapper: ObjectMapper) : PushAdapterFactory, AutoCloseable {
    private data class Entry(val version: java.time.Instant, val adapter: PushAdapter)
    private val adapters = ConcurrentHashMap<Long, Entry>()

    override fun create(config: PushConfig): PushAdapter = synchronized(adapters) {
        val current = adapters[config.id]
        if (current != null && current.version == config.updatedAt) return@synchronized current.adapter
        current?.adapter?.close()
        val adapter = when (config.pushType) {
            "http" -> HttpPushAdapter(config.config, objectMapper)
            "mqtt" -> MqttPushAdapter(config.config, objectMapper)
            else -> throw PushValidationException("暂不支持的推送类型: ${config.pushType}")
        }
        adapters[config.id] = Entry(config.updatedAt, adapter)
        adapter
    }

    fun invalidate(configId: Long) = synchronized(adapters) { adapters.remove(configId)?.adapter?.close() }
    override fun close() { adapters.values.forEach { runCatching { it.adapter.close() } }; adapters.clear() }
}

private class HttpPushAdapter(
    private val config: Map<String, Any?>,
    private val objectMapper: ObjectMapper,
) : PushAdapter {
    private val client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).build()

    override fun healthCheck(timeoutMs: Int) {
        val request = HttpRequest.newBuilder(uri()).timeout(Duration.ofMillis(timeoutMs.toLong())).GET().build()
        val response = client.send(request, HttpResponse.BodyHandlers.discarding())
        if (response.statusCode() >= 400) throw PushException("HTTP 健康检查失败: ${response.statusCode()}")
    }

    override fun push(payload: PushPayload, timeoutMs: Int) {
        val body = objectMapper.writeValueAsString(payload.wirePayload())
        val method = config["method"]?.toString()?.uppercase() ?: "POST"
        val builder = HttpRequest.newBuilder(uri()).timeout(Duration.ofMillis(timeoutMs.toLong()))
            .header("Content-Type", config["content_type"]?.toString() ?: config["contentType"]?.toString() ?: "application/json")
        val headers = config["headers"] as? Map<*, *>
        headers?.forEach { (key, value) -> if (key != null && value != null) builder.header(key.toString(), value.toString()) }
        val response = client.send(builder.method(method, HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.discarding())
        if (response.statusCode() >= 400) throw PushException("HTTP 推送失败: ${response.statusCode()}")
    }

    private fun uri() = URI.create(config["url"]?.toString() ?: throw PushValidationException("HTTP URL 不能为空"))
    override fun close() = Unit
}

private class MqttPushAdapter(
    private val config: Map<String, Any?>,
    private val objectMapper: ObjectMapper,
) : PushAdapter {
    @Volatile private var client: MqttClient? = null

    override fun healthCheck(timeoutMs: Int) { ensureConnected(timeoutMs) }

    override fun push(payload: PushPayload, timeoutMs: Int) {
        val mqtt = ensureConnected(timeoutMs)
        val topic = topic(payload)
        val qos = number("qos", 0).coerceIn(0, 2)
        mqtt.publish(topic, objectMapper.writeValueAsBytes(payload.wirePayload()), qos, false)
    }

    @Synchronized
    private fun ensureConnected(timeoutMs: Int): MqttClient {
        client?.takeIf(MqttClient::isConnected)?.let { return it }
        client?.let { runCatching { it.close() } }
        val broker = config["broker"]?.toString() ?: throw PushValidationException("MQTT Broker 不能为空")
        val clientId = config["client_id"]?.toString()?.takeIf(String::isNotBlank)
            ?: config["clientId"]?.toString()?.takeIf(String::isNotBlank)
            ?: "iview-push-${java.util.UUID.randomUUID()}"
        return MqttClient(broker, clientId, MemoryPersistence()).also { mqtt ->
            val options = MqttConnectOptions().apply {
                isCleanSession = boolean("clean_session", boolean("cleanSession", true))
                connectionTimeout = (timeoutMs / 1000).coerceAtLeast(1)
                isAutomaticReconnect = true
                config["username"]?.toString()?.takeIf(String::isNotBlank)?.let { userName = it }
                config["password"]?.toString()?.takeIf(String::isNotBlank)?.let { password = it.toCharArray() }
            }
            mqtt.connect(options)
            client = mqtt
        }
    }

    private fun topic(payload: PushPayload): String = (config["topic"]?.toString() ?: throw PushValidationException("MQTT Topic 不能为空"))
        .replace("{device_sn}", payload.deviceSn)
        .replace("{device_name}", payload.deviceName)
        .replace("{template_id}", payload.templateId.toString())
        .replace("{template_code}", payload.templateCode)
    private fun number(key: String, fallback: Int) = (config[key] as? Number)?.toInt() ?: config[key]?.toString()?.toIntOrNull() ?: fallback
    private fun boolean(key: String, fallback: Boolean) = (config[key] as? Boolean) ?: config[key]?.toString()?.toBooleanStrictOrNull() ?: fallback

    @Synchronized
    override fun close() {
        client?.let { mqtt -> runCatching { if (mqtt.isConnected) mqtt.disconnect(250) }; runCatching { mqtt.close() } }
        client = null
    }
}

internal fun PushPayload.wirePayload(): Map<String, Any?> = linkedMapOf(
    "msgId" to msgId,
    "data" to data,
    "timestamp" to timestamp,
    "device_sn" to deviceSn,
    "device_name" to deviceName,
    "template_id" to templateId,
    "template_code" to templateCode,
    "collect_time" to collectTime,
    "points" to points.map { point -> linkedMapOf(
        "point_id" to point.pointId,
        "point_name" to point.pointName,
        "point_code" to point.pointCode,
        "value" to point.value,
        "raw_value" to point.rawValue,
        "quality" to point.quality,
        "timestamp" to point.timestamp,
    ) },
)

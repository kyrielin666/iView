package ai.moying.iview.telemetry.jdbc

import ai.moying.iview.core.device.DeviceId
import ai.moying.iview.core.device.PointId
import ai.moying.iview.core.device.PointValue
import ai.moying.iview.core.device.ValueQuality
import ai.moying.iview.telemetry.TelemetryRepository
import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.jdbc.core.RowMapper
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Repository
import java.sql.ResultSet
import java.sql.Types
import java.time.Instant

@Repository
class JdbcTelemetryRepository(
    private val jdbc: NamedParameterJdbcTemplate,
    private val objectMapper: ObjectMapper,
) : TelemetryRepository {
    override fun append(values: List<PointValue>) {
        if (values.isEmpty()) return
        val params = values.map { value ->
            MapSqlParameterSource()
                .addValue("deviceId", value.deviceId.value)
                .addValue("pointId", value.pointId.value)
                .addValue("observedAt", value.observedAt)
                .addValue("receivedAt", value.receivedAt)
                .addValue("valueJson", value.value?.let(objectMapper::writeValueAsString), Types.VARCHAR)
                .addValue("quality", value.quality.name)
                .addValue("source", value.source)
                .addValue("diagnostic", value.diagnostic, Types.VARCHAR)
        }.toTypedArray()
        jdbc.batchUpdate(
            """INSERT INTO iview_point_sample
               (device_id, point_id, observed_at, received_at, value_json, quality, source, diagnostic)
               VALUES (:deviceId, :pointId, :observedAt, :receivedAt, :valueJson, :quality, :source, :diagnostic)""",
            params,
        )
    }

    override fun latest(deviceId: DeviceId): List<PointValue> = jdbc.query(
        """SELECT device_id, point_id, observed_at, received_at, value_json, quality, source, diagnostic
           FROM (
             SELECT s.*, ROW_NUMBER() OVER (PARTITION BY point_id ORDER BY received_at DESC, id DESC) AS row_num
             FROM iview_point_sample s WHERE device_id=:deviceId
           ) latest WHERE row_num=1 ORDER BY point_id""",
        mapOf("deviceId" to deviceId.value),
        mapper,
    )

    override fun history(deviceId: DeviceId, from: Instant, to: Instant, limit: Int): List<PointValue> = jdbc.query(
        """SELECT device_id, point_id, observed_at, received_at, value_json, quality, source, diagnostic
           FROM iview_point_sample
           WHERE device_id=:deviceId AND observed_at>=:from AND observed_at<:to
           ORDER BY observed_at DESC, id DESC LIMIT :limit""",
        mapOf("deviceId" to deviceId.value, "from" to from, "to" to to, "limit" to limit.coerceIn(1, 10_000)),
        mapper,
    )

    private val mapper = RowMapper { rs, _ ->
        PointValue(
            DeviceId(rs.getLong("device_id")),
            PointId(rs.getLong("point_id")),
            instant(rs, "observed_at"),
            instant(rs, "received_at"),
            rs.getString("value_json")?.let { objectMapper.readValue(it, Any::class.java) },
            ValueQuality.valueOf(rs.getString("quality")),
            rs.getString("source"),
            rs.getString("diagnostic"),
        )
    }

    private fun instant(rs: ResultSet, column: String): Instant = rs.getTimestamp(column).toInstant()
}

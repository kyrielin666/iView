package ai.moying.iview.query.jdbc

import ai.moying.iview.query.DataSource
import ai.moying.iview.query.DataSourceDraft
import ai.moying.iview.query.DataSourceRepository
import ai.moying.iview.query.DataSourceType
import ai.moying.iview.query.StoredDataSource
import org.springframework.jdbc.core.RowMapper
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.jdbc.core.simple.SimpleJdbcInsert
import org.springframework.stereotype.Repository
import java.sql.ResultSet
import java.time.Instant

@Repository
class JdbcDataSourceRepository(private val jdbc: NamedParameterJdbcTemplate) : DataSourceRepository {
    private val insert by lazy {
        SimpleJdbcInsert(jdbc.jdbcTemplate).withTableName("iview_data_source")
            .usingGeneratedKeyColumns("id")
            .usingColumns("source_name", "source_type", "jdbc_url", "username", "password_cipher", "description")
    }

    override fun list(): List<DataSource> = jdbc.query(
        "SELECT * FROM iview_data_source ORDER BY id DESC", emptyMap<String, Any>(), publicMapper,
    )

    override fun find(id: Long): StoredDataSource? = jdbc.query(
        "SELECT * FROM iview_data_source WHERE id=:id", mapOf("id" to id), storedMapper,
    ).firstOrNull()

    override fun create(draft: DataSourceDraft, passwordCipher: String): DataSource {
        val id = insert.executeAndReturnKey(values(draft, passwordCipher)).toLong()
        return requireNotNull(find(id)).source
    }

    override fun update(id: Long, draft: DataSourceDraft, passwordCipher: String?): DataSource? {
        val assignments = mutableListOf(
            "source_name=:source_name", "source_type=:source_type", "jdbc_url=:jdbc_url",
            "username=:username", "description=:description", "updated_at=CURRENT_TIMESTAMP",
        )
        val params = values(draft, passwordCipher) + ("id" to id)
        if (passwordCipher != null) assignments += "password_cipher=:password_cipher"
        val count = jdbc.update("UPDATE iview_data_source SET ${assignments.joinToString(", ")} WHERE id=:id", params)
        return if (count == 0) null else requireNotNull(find(id)).source
    }

    override fun delete(id: Long): Boolean = jdbc.update(
        "DELETE FROM iview_data_source WHERE id=:id", mapOf("id" to id),
    ) > 0

    private fun values(draft: DataSourceDraft, passwordCipher: String?) = mapOf(
        "source_name" to draft.name, "source_type" to draft.type.name,
        "jdbc_url" to draft.jdbcUrl, "username" to draft.username,
        "password_cipher" to passwordCipher, "description" to draft.description,
    )

    private val publicMapper = RowMapper { rs, _ -> source(rs) }
    private val storedMapper = RowMapper { rs, _ -> StoredDataSource(source(rs), rs.getString("password_cipher")) }
    private fun source(rs: ResultSet) = DataSource(
        rs.getLong("id"), rs.getString("source_name"), DataSourceType.valueOf(rs.getString("source_type")),
        rs.getString("jdbc_url"), rs.getString("username"), rs.getString("description"),
        instant(rs, "created_at"), instant(rs, "updated_at"),
    )
    private fun instant(rs: ResultSet, column: String): Instant = rs.getTimestamp(column).toInstant()
}

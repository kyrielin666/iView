package ai.moying.iview.datasource

import java.sql.Connection

/** Uses the connection's actual search_path and quoted identifier rules, without interpolating SQL. */
class PostgresLineageCatalog(private val connection: Connection) {
    private val cache = mutableMapOf<String, SqlCatalogTable?>()
    val warnings = linkedSetOf<String>()

    fun resolve(name: String): SqlCatalogTable? = cache.getOrPut(name) {
        connection.prepareStatement("""
            SELECT current_database(), n.nspname, c.relname, a.attname, c.relkind
            FROM pg_catalog.pg_class c JOIN pg_catalog.pg_namespace n ON n.oid=c.relnamespace
            JOIN pg_catalog.pg_attribute a ON a.attrelid=c.oid
            WHERE c.oid=pg_catalog.to_regclass(?) AND a.attnum>0 AND NOT a.attisdropped
            ORDER BY a.attnum
        """.trimIndent()).use { statement ->
            statement.queryTimeout = 10
            statement.setString(1, name)
            statement.executeQuery().use { rs ->
                var table: SqlCatalogTable? = null
                val columns = mutableListOf<String>()
                while (rs.next()) {
                    table = SqlCatalogTable(rs.getString(1), rs.getString(2), rs.getString(3), columns)
                    columns += rs.getString(4)
                    if (rs.getString(5) in setOf("v", "m")) warnings += "VIEW_DEFINITION_OPAQUE: $name"
                    if (rs.getString(5) == "f") warnings += "FOREIGN_TABLE_ORIGIN_OPAQUE: $name"
                }
                table
            }
        }
    }
}

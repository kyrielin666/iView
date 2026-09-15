package ai.moying.iview.query

import java.time.Instant

enum class DataSourceType { POSTGRESQL, MONGODB, HTTP, FILE }

data class DataSource(
    val id: Long,
    val name: String,
    val type: DataSourceType,
    val jdbcUrl: String,
    val username: String,
    val description: String,
    val createdAt: Instant,
    val updatedAt: Instant,
)

data class DataSourceDraft(
    val name: String,
    val type: DataSourceType,
    val jdbcUrl: String,
    val username: String,
    /** Null retains the existing secret on update; creation requires a password. */
    val password: String? = null,
    val description: String = "",
)

data class DataSourceCredential(
    val type: DataSourceType,
    val endpoint: String,
    val username: String,
    val password: String,
)

data class StoredDataSource(val source: DataSource, val passwordCipher: String)

interface DataSourceRepository {
    fun list(): List<DataSource>
    fun find(id: Long): StoredDataSource?
    fun create(draft: DataSourceDraft, passwordCipher: String): DataSource
    fun update(id: Long, draft: DataSourceDraft, passwordCipher: String?): DataSource?
    fun delete(id: Long): Boolean
}

interface DataSourceSecretCipher {
    fun encrypt(plainText: String): String
    fun decrypt(cipherText: String): String
}

open class DataSourceException(message: String) : RuntimeException(message)
class DataSourceNotFoundException(message: String) : DataSourceException(message)
class DataSourceValidationException(message: String) : DataSourceException(message)

class DataSourceService(
    private val repository: DataSourceRepository,
    private val cipher: DataSourceSecretCipher,
) {
    fun list() = repository.list()

    fun get(id: Long) = repository.find(id)?.source ?: throw DataSourceNotFoundException("数据源不存在: $id")

    fun create(draft: DataSourceDraft): DataSource {
        val normalized = normalize(draft, requirePassword = true)
        return repository.create(normalized, cipher.encrypt(normalized.password ?: ""))
    }

    fun update(id: Long, draft: DataSourceDraft): DataSource {
        get(id)
        val normalized = normalize(draft, requirePassword = false)
        val passwordCipher = normalized.password?.let(cipher::encrypt)
        return repository.update(id, normalized, passwordCipher) ?: throw DataSourceNotFoundException("数据源不存在: $id")
    }

    fun delete(id: Long) {
        if (!repository.delete(id)) throw DataSourceNotFoundException("数据源不存在: $id")
    }

    fun credential(id: Long): DataSourceCredential {
        val record = repository.find(id) ?: throw DataSourceNotFoundException("数据源不存在: $id")
        return DataSourceCredential(record.source.type, record.source.jdbcUrl, record.source.username, cipher.decrypt(record.passwordCipher))
    }

    private fun normalize(draft: DataSourceDraft, requirePassword: Boolean): DataSourceDraft {
        val normalized = draft.copy(
            name = draft.name.trim(), jdbcUrl = draft.jdbcUrl.trim(), username = draft.username.trim(),
            password = draft.password?.takeIf(String::isNotBlank), description = draft.description.trim(),
        )
        if (normalized.name.isBlank() || normalized.name.length > 100) throw DataSourceValidationException("数据源名称不能为空且不能超过100位")
        if (normalized.jdbcUrl.isBlank() || normalized.jdbcUrl.length > 1_000) throw DataSourceValidationException("数据源地址不能为空且不能超过1000位")
        when (normalized.type) {
            DataSourceType.POSTGRESQL -> {
                if (!normalized.jdbcUrl.startsWith("jdbc:postgresql://")) throw DataSourceValidationException("PostgreSQL JDBC URL 必须以 jdbc:postgresql:// 开头")
                if (normalized.username.isBlank()) throw DataSourceValidationException("数据库用户名不能为空")
                if (requirePassword && normalized.password == null) throw DataSourceValidationException("新建 PostgreSQL 数据源必须提供数据库密码")
            }
            DataSourceType.MONGODB -> if (!normalized.jdbcUrl.startsWith("mongodb://") && !normalized.jdbcUrl.startsWith("mongodb+srv://")) {
                throw DataSourceValidationException("MongoDB 地址必须以 mongodb:// 或 mongodb+srv:// 开头")
            }
            DataSourceType.HTTP -> if (!normalized.jdbcUrl.startsWith("http://") && !normalized.jdbcUrl.startsWith("https://")) {
                throw DataSourceValidationException("HTTP 地址必须以 http:// 或 https:// 开头")
            }
            DataSourceType.FILE -> {
                val value = normalized.jdbcUrl.replace('\\', '/')
                if (value.startsWith("/") || Regex("^[A-Za-z]:").containsMatchIn(value) || value.split('/').any { it == ".." }) {
                    throw DataSourceValidationException("文件数据源必须填写数据目录内的相对路径")
                }
                if (!value.lowercase().endsWith(".csv") && !value.lowercase().endsWith(".json")) {
                    throw DataSourceValidationException("文件数据源仅支持 CSV 或 JSON 文件")
                }
            }
        }
        return normalized
    }
}

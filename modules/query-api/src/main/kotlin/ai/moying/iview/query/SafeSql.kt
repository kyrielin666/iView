package ai.moying.iview.query

class QueryValidationException(message: String) : RuntimeException(message)

object SafeSql {
    fun selectOnly(sql: String): String {
        val normalized = sql.trim()
        if (normalized.isBlank()) throw QueryValidationException("SQL 不能为空")
        if (normalized.contains(";")) throw QueryValidationException("只允许执行单条 SQL")
        if (Regex("(?s)/\\*|--").containsMatchIn(normalized)) throw QueryValidationException("SQL 不允许包含注释")
        if (!Regex("(?is)^\\s*(select|with)\\b").containsMatchIn(normalized)) throw QueryValidationException("仅允许只读 SELECT 查询")
        val forbidden = Regex("(?i)\\b(insert|update|delete|merge|drop|alter|create|grant|revoke|copy|call|do)\\b")
        if (forbidden.containsMatchIn(normalized)) throw QueryValidationException("查询包含不允许的写入或管理关键字")
        return normalized
    }

    fun limit(sql: String, maxRows: Int): String {
        require(maxRows in 1..10_000) { "最大行数必须在1到10000之间" }
        val checked = selectOnly(sql)
        return if (Regex("(?i)\\blimit\\s+\\d+").containsMatchIn(checked)) checked else "$checked LIMIT $maxRows"
    }
}

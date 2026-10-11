package ai.moying.iview.backup

import org.springframework.stereotype.Component
import java.util.concurrent.TimeUnit

fun interface PostgresCommandExecutor {
    fun execute(command: List<String>, action: String, password: String, timeoutSeconds: Long)
}

@Component
class NativePostgresCommandExecutor : PostgresCommandExecutor {
    override fun execute(command: List<String>, action: String, password: String, timeoutSeconds: Long) {
        val process = try {
            ProcessBuilder(command).redirectErrorStream(true).apply {
                // Never inherit an unrelated PGPASSWORD when this connection intentionally uses none.
                environment().remove("PGPASSWORD")
                if (password.isNotBlank()) environment()["PGPASSWORD"] = password
            }.start()
        } catch (error: Exception) { throw BackupException("$action 无法启动：${error.message}") }
        val output = StringBuilder()
        val reader = Thread {
            process.inputStream.bufferedReader().useLines { lines -> lines.forEach { line ->
                synchronized(output) { if (output.length < 16_384) output.appendLine(line.take(1_024)) }
            } }
        }.apply { isDaemon = true; start() }
        try {
            if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
                process.destroyForcibly(); process.waitFor(5, TimeUnit.SECONDS)
                throw BackupException("$action 超时")
            }
            reader.join(1_000)
            if (process.exitValue() != 0) {
                val detail = synchronized(output) { output.toString() }.ifBlank { "命令退出码 ${process.exitValue()}" }.trim()
                throw BackupException("$action 失败：${if (password.isBlank()) detail else detail.replace(password, "[redacted]")}")
            }
        } finally {
            if (process.isAlive) process.destroyForcibly()
        }
    }
}

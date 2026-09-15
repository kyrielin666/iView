package ai.moying.iview.identity

import ai.moying.iview.common.ApiResponse
import jakarta.annotation.PostConstruct
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.HttpStatus
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.web.bind.annotation.*
import org.springframework.util.AntPathMatcher
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Instant
import java.util.*
import javax.crypto.Mac
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

class AuthRequest { var username = ""; var password = "" }
class RefreshRequest { var refreshToken = "" }
data class AuthUser(val id: Long, val username: String, val displayName: String, val roles: List<String>)
data class TokenPair(val accessToken: String, val refreshToken: String, val expiresIn: Long, val user: AuthUser)
class AuthenticationException(message: String) : RuntimeException(message)
class AuthorizationException(message: String) : RuntimeException(message)

@RestController
@RequestMapping("/api/v1/auth")
class IdentityController(private val identities: IdentityService) {
    @PostMapping("/login") fun login(@RequestBody request: AuthRequest) = ApiResponse.success(identities.login(request.username, request.password))
    @PostMapping("/refresh") fun refresh(@RequestBody request: RefreshRequest) = ApiResponse.success(identities.refresh(request.refreshToken))
    @PostMapping("/logout") fun logout(@RequestBody request: RefreshRequest): ApiResponse<Nothing> { identities.logout(request.refreshToken); return ApiResponse.success() }
    @GetMapping("/me") fun me(@RequestHeader("Authorization") authorization: String?) = ApiResponse.success(identities.current(authorization))
}

@org.springframework.stereotype.Service
class IdentityService(
    private val jdbc: JdbcTemplate,
    @Value("\${iview.auth.token-secret}") private val tokenSecret: String,
    @Value("\${iview.auth.bootstrap-admin-password:}") private val bootstrapPassword: String,
) {
    private val random = SecureRandom()
    private val pathMatcher = AntPathMatcher()
    @PostConstruct fun bootstrap() {
        if ((jdbc.queryForObject("SELECT COUNT(*) FROM iview_user", Long::class.java) ?: 0) != 0L) return
        require(bootstrapPassword.length >= 12) { "首次启动必须设置至少 12 位的 IVIEW_BOOTSTRAP_ADMIN_PASSWORD" }
        jdbc.update("INSERT INTO iview_user(username,password_hash,display_name,enabled) VALUES(?,?,?,TRUE)", "admin", passwordHash(bootstrapPassword), "系统管理员")
        jdbc.update("INSERT INTO iview_role(role_code,role_name,description) VALUES('ADMIN','系统管理员','拥有全部 iView 权限')")
        jdbc.update("INSERT INTO iview_permission(permission_code,permission_name) VALUES('*','全部权限')")
        jdbc.update("INSERT INTO iview_user_role(user_id,role_id) SELECT u.id,r.id FROM iview_user u CROSS JOIN iview_role r WHERE u.username='admin' AND r.role_code='ADMIN'")
        jdbc.update("INSERT INTO iview_role_permission(role_id,permission_id) SELECT r.id,p.id FROM iview_role r CROSS JOIN iview_permission p WHERE r.role_code='ADMIN' AND p.permission_code='*'")
    }
    fun login(username: String, password: String): TokenPair {
        val user = findByUsername(username.trim()) ?: throw AuthenticationException("用户名或密码不正确")
        if (!user.enabled || !verify(password, user.passwordHash)) throw AuthenticationException("用户名或密码不正确")
        return issue(user.view())
    }
    fun refresh(raw: String): TokenPair {
        val hash = sha256(raw); val row = jdbc.query("SELECT user_id FROM iview_refresh_token WHERE token_hash=? AND revoked_at IS NULL AND expires_at>?", { rs, _ -> rs.getLong(1) }, hash, Instant.now()).firstOrNull() ?: throw AuthenticationException("刷新令牌无效或已过期")
        jdbc.update("UPDATE iview_refresh_token SET revoked_at=? WHERE token_hash=?", Instant.now(), hash)
        val user = findById(row)?.takeIf { it.enabled } ?: throw AuthenticationException("用户不存在或已停用")
        return issue(user.view())
    }
    fun logout(raw: String) { if (raw.isNotBlank()) jdbc.update("UPDATE iview_refresh_token SET revoked_at=? WHERE token_hash=?", Instant.now(), sha256(raw)) }
    fun current(header: String?): AuthUser { val token = header?.removePrefix("Bearer ")?.trim().orEmpty(); val fields = decode(token); return findById(fields[0].toLongOrNull() ?: throw AuthenticationException("访问令牌无效"))?.takeIf { it.enabled }?.view() ?: throw AuthenticationException("用户不存在或已停用") }
    fun permitted(user: AuthUser, method: String, path: String): Boolean {
        val permissions = jdbc.query("SELECT p.permission_code FROM iview_permission p JOIN iview_role_permission rp ON rp.permission_id=p.id JOIN iview_user_role ur ON ur.role_id=rp.role_id WHERE ur.user_id=?", { rs, _ -> rs.getString(1) }, user.id)
        val normalizedMethod = method.uppercase()
        return permissions.any { permission ->
            if (permission == "*") true else {
                val separator = permission.indexOf(':')
                separator > 0 && permission.substring(0, separator).uppercase() == normalizedMethod &&
                    pathMatcher.match(permission.substring(separator + 1), path)
            }
        }
    }
    private fun issue(user: AuthUser): TokenPair { val expires = Instant.now().plusSeconds(900); val access = encode(listOf(user.id.toString(), user.username, expires.epochSecond.toString())); val refresh = randomToken(); jdbc.update("INSERT INTO iview_refresh_token(user_id,token_hash,expires_at) VALUES(?,?,?)", user.id, sha256(refresh), Instant.now().plusSeconds(604800)); return TokenPair(access, refresh, 900, user) }
    private fun findByUsername(username: String) = jdbc.query("SELECT id,username,password_hash,display_name,enabled FROM iview_user WHERE username=?", { rs, _ -> StoredUser(rs.getLong("id"),rs.getString("username"),rs.getString("password_hash"),rs.getString("display_name"),rs.getBoolean("enabled")) }, username).firstOrNull()
    private fun findById(id: Long) = jdbc.query("SELECT id,username,password_hash,display_name,enabled FROM iview_user WHERE id=?", { rs, _ -> StoredUser(rs.getLong("id"),rs.getString("username"),rs.getString("password_hash"),rs.getString("display_name"),rs.getBoolean("enabled")) }, id).firstOrNull()
    private fun StoredUser.view() = AuthUser(id, username, displayName, jdbc.query("SELECT r.role_code FROM iview_role r JOIN iview_user_role ur ON ur.role_id=r.id WHERE ur.user_id=? ORDER BY r.role_code", { rs, _ -> rs.getString(1) }, id))
    private fun passwordHash(password: String): String { val salt = ByteArray(16).also(random::nextBytes); val derived = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(PBEKeySpec(password.toCharArray(), salt, 210_000, 256)).encoded; return "${Base64.getEncoder().encodeToString(salt)}:${Base64.getEncoder().encodeToString(derived)}" }
    private fun verify(password: String, stored: String): Boolean { val pieces = stored.split(':'); if (pieces.size != 2) return false; val derived = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(PBEKeySpec(password.toCharArray(), Base64.getDecoder().decode(pieces[0]), 210_000, 256)).encoded; return MessageDigest.isEqual(derived, Base64.getDecoder().decode(pieces[1])) }
    private fun encode(fields: List<String>): String { val payload = Base64.getUrlEncoder().withoutPadding().encodeToString(fields.joinToString(":").toByteArray(StandardCharsets.UTF_8)); return "$payload.${signature(payload)}" }
    private fun decode(token: String): List<String> { val pair = token.split('.'); if (pair.size != 2 || !MessageDigest.isEqual(signature(pair[0]).toByteArray(), pair[1].toByteArray())) throw AuthenticationException("访问令牌无效"); val fields = String(Base64.getUrlDecoder().decode(pair[0]), StandardCharsets.UTF_8).split(':'); if (fields.size != 3 || fields[2].toLongOrNull()?.let { Instant.now().epochSecond >= it } != false) throw AuthenticationException("访问令牌已过期"); return fields }
    private fun signature(payload: String): String = Base64.getUrlEncoder().withoutPadding().encodeToString(Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(tokenSecret.toByteArray(StandardCharsets.UTF_8), "HmacSHA256")) }.doFinal(payload.toByteArray(StandardCharsets.UTF_8)))
    private fun randomToken(): String = ByteArray(48).also(random::nextBytes).let { Base64.getUrlEncoder().withoutPadding().encodeToString(it) }
    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(StandardCharsets.UTF_8)).joinToString("") { "%02x".format(it) }
    private data class StoredUser(val id: Long, val username: String, val passwordHash: String, val displayName: String, val enabled: Boolean)
}

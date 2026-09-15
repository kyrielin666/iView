package ai.moying.iview.identity

import ai.moying.iview.common.ApiResponse
import jakarta.servlet.http.HttpServletRequest
import org.springframework.http.HttpStatus
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.*
import java.security.SecureRandom
import java.time.Instant
import java.util.Base64
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

data class ManagedUser(val id: Long, val username: String, val displayName: String, val enabled: Boolean, val roles: List<String>, val deviceIds: List<Long>, val departmentId: Long?)
data class ManagedRole(val id: Long, val code: String, val name: String, val description: String, val permissions: List<String>)
data class ManagedPermission(val id: Long, val code: String, val name: String)
data class ManagedDepartment(val id: Long, val code: String, val name: String, val parentId: Long?, val sortOrder: Int, val enabled: Boolean)
data class OperationAudit(val id: Long, val actor: String?, val action: String, val targetType: String, val targetId: String, val detail: String, val createdAt: Instant)
class ManagedUserRequest { var username = ""; var displayName = ""; var password = ""; var enabled = true; var roleCodes: List<String> = emptyList(); var deviceIds: List<Long> = emptyList(); var departmentId: Long? = null }
class ManagedRoleRequest { var code = ""; var name = ""; var description = ""; var permissionCodes: List<String> = emptyList() }
class ManagedPermissionRequest { var code = ""; var name = "" }
class ManagedDepartmentRequest { var code = ""; var name = ""; var parentId: Long? = null; var sortOrder = 0; var enabled = true }

@Component
class AdminGuard {
    fun require(request: HttpServletRequest): AuthUser {
        val user = request.getAttribute("iview.user") as? AuthUser ?: throw AuthenticationException("请先登录")
        if ("ADMIN" !in user.roles) throw AuthorizationException("仅系统管理员可执行此操作")
        return user
    }
}

@RestController
@RequestMapping("/api/v1/admin")
class IdentityAdministrationController(private val administration: IdentityAdministrationService, private val guard: AdminGuard) {
    @GetMapping("/users") fun users(request: HttpServletRequest) = guard.require(request).let { ApiResponse.success(administration.users()) }
    @PostMapping("/users") @ResponseStatus(HttpStatus.CREATED) fun createUser(@RequestBody body: ManagedUserRequest, request: HttpServletRequest) = guard.require(request).let { ApiResponse.success(administration.createUser(body, it)) }
    @PutMapping("/users/{id}") fun updateUser(@PathVariable id: Long, @RequestBody body: ManagedUserRequest, request: HttpServletRequest) = guard.require(request).let { ApiResponse.success(administration.updateUser(id, body, it)) }
    @DeleteMapping("/users/{id}") fun deleteUser(@PathVariable id: Long, request: HttpServletRequest): ApiResponse<Nothing> { administration.deleteUser(id, guard.require(request)); return ApiResponse.success() }

    @GetMapping("/roles") fun roles(request: HttpServletRequest) = guard.require(request).let { ApiResponse.success(administration.roles()) }
    @PostMapping("/roles") @ResponseStatus(HttpStatus.CREATED) fun createRole(@RequestBody body: ManagedRoleRequest, request: HttpServletRequest) = guard.require(request).let { ApiResponse.success(administration.createRole(body, it)) }
    @PutMapping("/roles/{id}") fun updateRole(@PathVariable id: Long, @RequestBody body: ManagedRoleRequest, request: HttpServletRequest) = guard.require(request).let { ApiResponse.success(administration.updateRole(id, body, it)) }
    @DeleteMapping("/roles/{id}") fun deleteRole(@PathVariable id: Long, request: HttpServletRequest): ApiResponse<Nothing> { administration.deleteRole(id, guard.require(request)); return ApiResponse.success() }

    @GetMapping("/permissions") fun permissions(request: HttpServletRequest) = guard.require(request).let { ApiResponse.success(administration.permissions()) }
    @PostMapping("/permissions") @ResponseStatus(HttpStatus.CREATED) fun createPermission(@RequestBody body: ManagedPermissionRequest, request: HttpServletRequest) = guard.require(request).let { ApiResponse.success(administration.createPermission(body, it)) }
    @PutMapping("/permissions/{id}") fun updatePermission(@PathVariable id: Long, @RequestBody body: ManagedPermissionRequest, request: HttpServletRequest) = guard.require(request).let { ApiResponse.success(administration.updatePermission(id, body, it)) }
    @DeleteMapping("/permissions/{id}") fun deletePermission(@PathVariable id: Long, request: HttpServletRequest): ApiResponse<Nothing> { administration.deletePermission(id, guard.require(request)); return ApiResponse.success() }
    @GetMapping("/departments") fun departments(request: HttpServletRequest) = guard.require(request).let { ApiResponse.success(administration.departments()) }
    @PostMapping("/departments") @ResponseStatus(HttpStatus.CREATED) fun createDepartment(@RequestBody body: ManagedDepartmentRequest, request: HttpServletRequest) = guard.require(request).let { ApiResponse.success(administration.createDepartment(body, it)) }
    @PutMapping("/departments/{id}") fun updateDepartment(@PathVariable id: Long, @RequestBody body: ManagedDepartmentRequest, request: HttpServletRequest) = guard.require(request).let { ApiResponse.success(administration.updateDepartment(id, body, it)) }
    @DeleteMapping("/departments/{id}") fun deleteDepartment(@PathVariable id: Long, request: HttpServletRequest): ApiResponse<Nothing> { administration.deleteDepartment(id, guard.require(request)); return ApiResponse.success() }
    @GetMapping("/audit") fun audit(@RequestParam(defaultValue = "100") limit: Int, request: HttpServletRequest) = guard.require(request).let { ApiResponse.success(administration.audit(limit)) }
}

@Service
class IdentityAdministrationService(private val jdbc: JdbcTemplate) {
    private val random = SecureRandom()

    fun users(): List<ManagedUser> = jdbc.query("SELECT id,username,display_name,enabled FROM iview_user ORDER BY id") { rs, _ ->
        val id = rs.getLong("id"); ManagedUser(id, rs.getString("username"), rs.getString("display_name"), rs.getBoolean("enabled"), userRoles(id), userDeviceIds(id), userDepartmentId(id))
    }
    fun roles(): List<ManagedRole> = jdbc.query("SELECT id,role_code,role_name,description FROM iview_role ORDER BY role_code") { rs, _ ->
        val id = rs.getLong("id"); ManagedRole(id, rs.getString("role_code"), rs.getString("role_name"), rs.getString("description"), rolePermissions(id))
    }
    fun permissions(): List<ManagedPermission> = jdbc.query("SELECT id,permission_code,permission_name FROM iview_permission ORDER BY permission_code") { rs, _ -> ManagedPermission(rs.getLong("id"), rs.getString("permission_code"), rs.getString("permission_name")) }
    fun departments(): List<ManagedDepartment> = jdbc.query("SELECT id,department_code,department_name,parent_id,sort_order,enabled FROM iview_department ORDER BY sort_order,id") { rs, _ -> ManagedDepartment(rs.getLong("id"), rs.getString("department_code"), rs.getString("department_name"), rs.getObject("parent_id", Long::class.java), rs.getInt("sort_order"), rs.getBoolean("enabled")) }
    fun audit(limit: Int): List<OperationAudit> = jdbc.query("SELECT a.id,u.username,a.action,a.target_type,a.target_id,a.detail,a.created_at FROM iview_operation_audit a LEFT JOIN iview_user u ON u.id=a.actor_user_id ORDER BY a.id DESC LIMIT ?", { rs, _ -> OperationAudit(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5), rs.getString(6), rs.getTimestamp(7).toInstant()) }, limit.coerceIn(1, 500))

    @Transactional fun createUser(body: ManagedUserRequest, actor: AuthUser): ManagedUser {
        val username = validateCode(body.username, "用户名").lowercase(); validatePassword(body.password)
        if (count("SELECT COUNT(*) FROM iview_user WHERE username=?", username) > 0) throw IllegalArgumentException("用户名已存在")
        validateRoles(body.roleCodes); validateDeviceIds(body.deviceIds); validateDepartmentId(body.departmentId)
        jdbc.update("INSERT INTO iview_user(username,password_hash,display_name,enabled) VALUES(?,?,?,?)", username, passwordHash(body.password), required(body.displayName, "显示名称", 100), body.enabled)
        val id = jdbc.queryForObject("SELECT id FROM iview_user WHERE username=?", Long::class.java, username)!!
        replaceUserRoles(id, body.roleCodes); replaceUserDeviceScope(id, body.deviceIds); replaceUserDepartment(id, body.departmentId); record(actor, "CREATE", "USER", id, username); return users().first { it.id == id }
    }
    @Transactional fun updateUser(id: Long, body: ManagedUserRequest, actor: AuthUser): ManagedUser {
        val current = users().firstOrNull { it.id == id } ?: throw IllegalArgumentException("用户不存在")
        if (id == actor.id && !body.enabled) throw IllegalArgumentException("不能停用当前登录用户")
        if (current.username == "admin" && !body.enabled) throw IllegalArgumentException("不能停用引导管理员")
        validateRoles(body.roleCodes); validateDeviceIds(body.deviceIds); validateDepartmentId(body.departmentId); if (body.password.isNotBlank()) validatePassword(body.password)
        jdbc.update("UPDATE iview_user SET display_name=?,enabled=?,updated_at=CURRENT_TIMESTAMP WHERE id=?", required(body.displayName, "显示名称", 100), body.enabled, id)
        if (body.password.isNotBlank()) jdbc.update("UPDATE iview_user SET password_hash=? WHERE id=?", passwordHash(body.password), id)
        replaceUserRoles(id, body.roleCodes); replaceUserDeviceScope(id, body.deviceIds); replaceUserDepartment(id, body.departmentId)
        if (!body.enabled || body.password.isNotBlank()) jdbc.update("UPDATE iview_refresh_token SET revoked_at=CURRENT_TIMESTAMP WHERE user_id=? AND revoked_at IS NULL", id)
        record(actor, "UPDATE", "USER", id, current.username); return users().first { it.id == id }
    }
    @Transactional fun deleteUser(id: Long, actor: AuthUser) {
        val current = users().firstOrNull { it.id == id } ?: throw IllegalArgumentException("用户不存在")
        if (id == actor.id || current.username == "admin") throw IllegalArgumentException("不能删除当前用户或引导管理员")
        record(actor, "DELETE", "USER", id, current.username); jdbc.update("DELETE FROM iview_user WHERE id=?", id)
    }

    @Transactional fun createRole(body: ManagedRoleRequest, actor: AuthUser): ManagedRole {
        val code = validateCode(body.code, "角色编码").uppercase(); if (count("SELECT COUNT(*) FROM iview_role WHERE role_code=?", code) > 0) throw IllegalArgumentException("角色编码已存在")
        validatePermissions(body.permissionCodes); jdbc.update("INSERT INTO iview_role(role_code,role_name,description) VALUES(?,?,?)", code, required(body.name, "角色名称", 100), body.description.trim().take(500))
        val id = jdbc.queryForObject("SELECT id FROM iview_role WHERE role_code=?", Long::class.java, code)!!; replaceRolePermissions(id, body.permissionCodes); record(actor, "CREATE", "ROLE", id, code); return roles().first { it.id == id }
    }
    @Transactional fun updateRole(id: Long, body: ManagedRoleRequest, actor: AuthUser): ManagedRole {
        val current = roles().firstOrNull { it.id == id } ?: throw IllegalArgumentException("角色不存在"); validatePermissions(body.permissionCodes)
        if (current.code == "ADMIN" && "*" !in body.permissionCodes) throw IllegalArgumentException("管理员角色必须保留全部权限")
        jdbc.update("UPDATE iview_role SET role_name=?,description=? WHERE id=?", required(body.name, "角色名称", 100), body.description.trim().take(500), id); replaceRolePermissions(id, body.permissionCodes); record(actor, "UPDATE", "ROLE", id, current.code); return roles().first { it.id == id }
    }
    @Transactional fun deleteRole(id: Long, actor: AuthUser) {
        val current = roles().firstOrNull { it.id == id } ?: throw IllegalArgumentException("角色不存在"); if (current.code == "ADMIN") throw IllegalArgumentException("不能删除管理员角色")
        record(actor, "DELETE", "ROLE", id, current.code); jdbc.update("DELETE FROM iview_role WHERE id=?", id)
    }

    @Transactional fun createPermission(body: ManagedPermissionRequest, actor: AuthUser): ManagedPermission {
        val code = required(body.code, "权限编码", 128); validatePermissionCode(code); if (count("SELECT COUNT(*) FROM iview_permission WHERE permission_code=?", code) > 0) throw IllegalArgumentException("权限编码已存在")
        jdbc.update("INSERT INTO iview_permission(permission_code,permission_name) VALUES(?,?)", code, required(body.name, "权限名称", 100)); val id = jdbc.queryForObject("SELECT id FROM iview_permission WHERE permission_code=?", Long::class.java, code)!!; record(actor, "CREATE", "PERMISSION", id, code); return permissions().first { it.id == id }
    }
    @Transactional fun updatePermission(id: Long, body: ManagedPermissionRequest, actor: AuthUser): ManagedPermission {
        val current = permissions().firstOrNull { it.id == id } ?: throw IllegalArgumentException("权限不存在"); if (current.code == "*") throw IllegalArgumentException("不能修改全部权限")
        jdbc.update("UPDATE iview_permission SET permission_name=? WHERE id=?", required(body.name, "权限名称", 100), id); record(actor, "UPDATE", "PERMISSION", id, current.code); return permissions().first { it.id == id }
    }
    @Transactional fun deletePermission(id: Long, actor: AuthUser) {
        val current = permissions().firstOrNull { it.id == id } ?: throw IllegalArgumentException("权限不存在"); if (current.code == "*") throw IllegalArgumentException("不能删除全部权限")
        record(actor, "DELETE", "PERMISSION", id, current.code); jdbc.update("DELETE FROM iview_permission WHERE id=?", id)
    }

    @Transactional fun createDepartment(body: ManagedDepartmentRequest, actor: AuthUser): ManagedDepartment {
        val code = validateCode(body.code, "部门编码").uppercase()
        if (count("SELECT COUNT(*) FROM iview_department WHERE department_code=?", code) > 0) throw IllegalArgumentException("部门编码已存在")
        validateDepartmentParent(body.parentId, null)
        jdbc.update("INSERT INTO iview_department(department_code,department_name,parent_id,sort_order,enabled) VALUES(?,?,?,?,?)", code, required(body.name, "部门名称", 100), body.parentId, body.sortOrder.coerceIn(-100000, 100000), body.enabled)
        val id = jdbc.queryForObject("SELECT id FROM iview_department WHERE department_code=?", Long::class.java, code)!!
        record(actor, "CREATE", "DEPARTMENT", id, code); return departments().first { it.id == id }
    }
    @Transactional fun updateDepartment(id: Long, body: ManagedDepartmentRequest, actor: AuthUser): ManagedDepartment {
        val current = departments().firstOrNull { it.id == id } ?: throw IllegalArgumentException("部门不存在")
        validateDepartmentParent(body.parentId, id)
        jdbc.update("UPDATE iview_department SET department_name=?,parent_id=?,sort_order=?,enabled=?,updated_at=CURRENT_TIMESTAMP WHERE id=?", required(body.name, "部门名称", 100), body.parentId, body.sortOrder.coerceIn(-100000, 100000), body.enabled, id)
        record(actor, "UPDATE", "DEPARTMENT", id, current.code); return departments().first { it.id == id }
    }
    @Transactional fun deleteDepartment(id: Long, actor: AuthUser) {
        val current = departments().firstOrNull { it.id == id } ?: throw IllegalArgumentException("部门不存在")
        if (count("SELECT COUNT(*) FROM iview_department WHERE parent_id=?", id) > 0) throw IllegalArgumentException("请先处理该部门的下级部门")
        if (count("SELECT COUNT(*) FROM iview_user_department WHERE department_id=?", id) > 0) throw IllegalArgumentException("请先将用户移出该部门")
        record(actor, "DELETE", "DEPARTMENT", id, current.code); jdbc.update("DELETE FROM iview_department WHERE id=?", id)
    }

    private fun replaceUserRoles(userId: Long, codes: List<String>) { jdbc.update("DELETE FROM iview_user_role WHERE user_id=?", userId); codes.distinct().forEach { jdbc.update("INSERT INTO iview_user_role(user_id,role_id) SELECT ?,id FROM iview_role WHERE role_code=?", userId, it.uppercase()) } }
    private fun replaceUserDeviceScope(userId: Long, deviceIds: List<Long>) { jdbc.update("DELETE FROM iview_user_device_scope WHERE user_id=?", userId); deviceIds.distinct().forEach { jdbc.update("INSERT INTO iview_user_device_scope(user_id,device_id) VALUES(?,?)", userId, it) } }
    private fun replaceUserDepartment(userId: Long, departmentId: Long?) { jdbc.update("DELETE FROM iview_user_department WHERE user_id=?", userId); if (departmentId != null) jdbc.update("INSERT INTO iview_user_department(user_id,department_id) VALUES(?,?)", userId, departmentId) }
    private fun replaceRolePermissions(roleId: Long, codes: List<String>) { jdbc.update("DELETE FROM iview_role_permission WHERE role_id=?", roleId); codes.distinct().forEach { jdbc.update("INSERT INTO iview_role_permission(role_id,permission_id) SELECT ?,id FROM iview_permission WHERE permission_code=?", roleId, it) } }
    private fun validateRoles(codes: List<String>) { val normalized = codes.map(String::uppercase).distinct(); if (normalized.isEmpty()) throw IllegalArgumentException("用户至少需要一个角色"); if (normalized.any { count("SELECT COUNT(*) FROM iview_role WHERE role_code=?", it) == 0L }) throw IllegalArgumentException("包含不存在的角色") }
    private fun validateDeviceIds(ids: List<Long>) { if (ids.any { it <= 0 } || ids.distinct().size != ids.size) throw IllegalArgumentException("设备数据范围无效"); if (ids.isNotEmpty() && count("SELECT COUNT(*) FROM iview_device WHERE id IN (${ids.joinToString(",") { "?" }})", *ids.toTypedArray()) != ids.size.toLong()) throw IllegalArgumentException("设备数据范围包含不存在的设备") }
    private fun validateDepartmentId(id: Long?) { if (id != null && count("SELECT COUNT(*) FROM iview_department WHERE id=?", id) == 0L) throw IllegalArgumentException("所属部门不存在") }
    private fun validateDepartmentParent(parentId: Long?, currentId: Long?) {
        if (parentId == null) return
        if (parentId == currentId) throw IllegalArgumentException("部门不能以自身为上级")
        var candidate = parentId
        while (true) {
            val parent = jdbc.query("SELECT parent_id FROM iview_department WHERE id=?", { rs, _ -> rs.getObject(1, Long::class.java) }, candidate).firstOrNull() ?: throw IllegalArgumentException("上级部门不存在")
            if (parent == currentId) throw IllegalArgumentException("部门层级不能形成循环")
            candidate = parent ?: return
        }
    }
    private fun validatePermissions(codes: List<String>) { if (codes.distinct().any { count("SELECT COUNT(*) FROM iview_permission WHERE permission_code=?", it) == 0L }) throw IllegalArgumentException("包含不存在的权限") }
    private fun validatePermissionCode(code: String) {
        if (code == "*") throw IllegalArgumentException("全部权限为系统保留权限")
        if (!Regex("^(GET|POST|PUT|PATCH|DELETE):/api/v1/[A-Za-z0-9_.*{}/-]+$").matches(code)) throw IllegalArgumentException("权限编码格式应为 METHOD:/api/v1/path，可使用 * 或 ** 通配路径")
    }
    private fun userRoles(id: Long) = jdbc.query("SELECT r.role_code FROM iview_role r JOIN iview_user_role ur ON ur.role_id=r.id WHERE ur.user_id=? ORDER BY r.role_code", { rs, _ -> rs.getString(1) }, id)
    private fun userDeviceIds(id: Long) = jdbc.query("SELECT device_id FROM iview_user_device_scope WHERE user_id=? ORDER BY device_id", { rs, _ -> rs.getLong(1) }, id)
    private fun userDepartmentId(id: Long): Long? = jdbc.query("SELECT department_id FROM iview_user_department WHERE user_id=?", { rs, _ -> rs.getLong(1) }, id).firstOrNull()
    private fun rolePermissions(id: Long) = jdbc.query("SELECT p.permission_code FROM iview_permission p JOIN iview_role_permission rp ON rp.permission_id=p.id WHERE rp.role_id=? ORDER BY p.permission_code", { rs, _ -> rs.getString(1) }, id)
    private fun count(sql: String, value: Any) = jdbc.queryForObject(sql, Long::class.java, value) ?: 0
    private fun count(sql: String, vararg values: Any) = jdbc.queryForObject(sql, Long::class.java, *values) ?: 0
    private fun required(value: String, label: String, max: Int) = value.trim().also { if (it.isBlank() || it.length > max) throw IllegalArgumentException("$label 不能为空且不能超过 $max 个字符") }
    private fun validateCode(value: String, label: String) = value.trim().also { if (!Regex("[A-Za-z][A-Za-z0-9_.-]{2,63}").matches(it)) throw IllegalArgumentException("$label 必须为 3-64 位字母、数字、点、横线或下划线") }
    private fun validatePassword(value: String) { if (value.length !in 12..128) throw IllegalArgumentException("密码长度必须为 12-128 位") }
    private fun passwordHash(password: String): String { val salt = ByteArray(16).also(random::nextBytes); val derived = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(PBEKeySpec(password.toCharArray(), salt, 210_000, 256)).encoded; return "${Base64.getEncoder().encodeToString(salt)}:${Base64.getEncoder().encodeToString(derived)}" }
    private fun record(actor: AuthUser, action: String, type: String, id: Any, detail: String) { jdbc.update("INSERT INTO iview_operation_audit(actor_user_id,action,target_type,target_id,detail) VALUES(?,?,?,?,?)", actor.id, action, type, id.toString(), detail.take(1000)) }
}

package ai.moying.iview.device

import ai.moying.iview.identity.AuthUser
import ai.moying.iview.identity.AuthorizationException
import ai.moying.iview.identity.IdentityService
import jakarta.servlet.http.HttpServletRequest
import org.springframework.stereotype.Component

@Component
class DeviceScopeGuard(private val identities: IdentityService) {
    /** Test and maintenance profiles can disable the global authorization interceptor. */
    fun allowed(request: HttpServletRequest): Set<Long>? = (request.getAttribute("iview.user") as? AuthUser)?.let(identities::allowedDeviceIds)
    fun require(request: HttpServletRequest, deviceId: Long) {
        val allowed = allowed(request)
        if (allowed != null && deviceId !in allowed) throw AuthorizationException("当前用户无权访问该设备的数据范围")
    }

    /** Re-resolve the user and role/scope grants for a long-lived SSE connection. */
    fun subscriptionAuthorization(request: HttpServletRequest, deviceId: Long): () -> Boolean {
        if (request.getAttribute("iview.user") !is AuthUser) return { true } // auth-disabled test/maintenance profile
        val authorization = request.getHeader("Authorization")
        return {
            runCatching {
                val user = identities.current(authorization)
                identities.permitted(user, "GET", "/api/v1/devices/$deviceId/subscription") &&
                    (identities.allowedDeviceIds(user)?.contains(deviceId) != false)
            }.getOrDefault(false)
        }
    }
}

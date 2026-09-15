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
}

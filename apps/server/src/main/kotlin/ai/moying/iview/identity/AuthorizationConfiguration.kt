package ai.moying.iview.identity

import ai.moying.iview.common.ApiResponse
import com.fasterxml.jackson.databind.ObjectMapper
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Configuration
import org.springframework.http.HttpStatus
import org.springframework.web.servlet.HandlerInterceptor
import org.springframework.web.servlet.config.annotation.InterceptorRegistry
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer

@Configuration
class AuthorizationConfiguration(
    private val identities: IdentityService,
    private val mapper: ObjectMapper,
    @Value("\${iview.auth.enforced:true}") private val enforced: Boolean,
) : WebMvcConfigurer {
    override fun addInterceptors(registry: InterceptorRegistry) {
        if (enforced) registry.addInterceptor(AuthorizationInterceptor(identities, mapper)).addPathPatterns("/api/**")
    }
}

private class AuthorizationInterceptor(private val identities: IdentityService, private val mapper: ObjectMapper) : HandlerInterceptor {
    override fun preHandle(request: HttpServletRequest, response: HttpServletResponse, handler: Any): Boolean {
        if (request.method == "OPTIONS" || request.requestURI in setOf("/api/v1/auth/login", "/api/v1/auth/refresh", "/api/v1/auth/logout")) return true
        return try {
            val user = identities.current(request.getHeader("Authorization"))
            if (!identities.permitted(user, request.method, request.requestURI)) throw AuthorizationException("当前用户没有执行此操作的权限")
            request.setAttribute("iview.user", user); true
        } catch (error: AuthenticationException) { fail(response, HttpStatus.UNAUTHORIZED, error.message ?: "未登录") }
        catch (error: AuthorizationException) { fail(response, HttpStatus.FORBIDDEN, error.message ?: "无权限") }
    }
    private fun fail(response: HttpServletResponse, status: HttpStatus, message: String): Boolean { response.status = status.value(); response.contentType = "application/json;charset=UTF-8"; mapper.writeValue(response.writer, ApiResponse<Nothing>(status.value().toString(), message)); return false }
}

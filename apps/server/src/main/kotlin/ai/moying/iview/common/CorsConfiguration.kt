package ai.moying.iview.common

import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Configuration
import org.springframework.web.servlet.config.annotation.CorsRegistry
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer

/** CORS stays disabled until a deployment explicitly supplies its frontend origins. */
@Configuration
class CorsConfiguration(
    @Value("\${iview.cors.allowed-origins:}") allowedOrigins: String,
) : WebMvcConfigurer {
    private val origins = allowedOrigins.split(',').map(String::trim).filter(String::isNotEmpty)

    override fun addCorsMappings(registry: CorsRegistry) {
        if (origins.isEmpty()) return
        registry.addMapping("/api/**")
            .allowedOrigins(*origins.toTypedArray())
            .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS")
            .allowedHeaders("Content-Type", "Authorization")
            .maxAge(3600)
    }
}

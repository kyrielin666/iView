package ai.moying.iview.outbound

import ai.moying.iview.device.DeviceCatalogService
import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration
class OutboundConfiguration {
    @Bean fun pushConfigService(repository: PushRepository) = PushConfigService(repository)

    @Bean(destroyMethod = "close")
    fun outboundAdapterRegistry(objectMapper: ObjectMapper) = OutboundAdapterRegistry(objectMapper)

    @Bean
    fun pushDispatcher(repository: PushRepository, adapters: OutboundAdapterRegistry, objectMapper: ObjectMapper) =
        PushDispatcher(repository, adapters, payloadSize = { objectMapper.writeValueAsBytes(it.wirePayload()).size })

    @Bean(destroyMethod = "close")
    fun outboundPublisher(catalog: DeviceCatalogService, dispatcher: PushDispatcher) = OutboundPublisher(catalog, dispatcher)
}

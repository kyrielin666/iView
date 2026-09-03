package ai.moying.iview.device

import ai.moying.iview.collector.DriverRegistry
import ai.moying.iview.collector.CollectionEngine
import ai.moying.iview.collector.DeviceSessionPool
import ai.moying.iview.telemetry.TelemetryRepository
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration
class DeviceModuleConfiguration {
    @Bean
    fun deviceCatalogObjectMapper() = jacksonObjectMapper()

    @Bean
    fun deviceCatalogService(repository: DeviceCatalogRepository) = DeviceCatalogService(repository)

    @Bean
    fun deviceRuntimeMapper(catalog: DeviceCatalogService) = DeviceRuntimeMapper(catalog)

    @Bean(destroyMethod = "close")
    fun deviceSessionPool(drivers: DriverRegistry) = DeviceSessionPool(drivers)

    @Bean
    fun collectionEngine(pool: DeviceSessionPool, telemetry: TelemetryRepository) =
        CollectionEngine(pool, telemetry::append)

    @Bean
    fun deviceCollectionService(catalog: DeviceCatalogService, runtimeMapper: DeviceRuntimeMapper,
        engine: CollectionEngine, telemetry: TelemetryRepository) =
        DeviceCollectionService(catalog, runtimeMapper, engine, telemetry)

    @Bean
    fun deviceDiagnosticsService(catalog: DeviceCatalogService, drivers: DriverRegistry, runtimeMapper: DeviceRuntimeMapper) =
        DeviceDiagnosticsService(catalog, drivers, runtimeMapper)
}

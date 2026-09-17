package ai.moying.iview.device

import ai.moying.iview.collector.DriverRegistry
import ai.moying.iview.collector.CollectionEngine
import ai.moying.iview.collector.DeviceSessionPool
import ai.moying.iview.telemetry.TelemetryRepository
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.databind.SerializationFeature
import ai.moying.iview.outbound.OutboundPublisher
import ai.moying.iview.alarm.CollectionAlarmMonitor
import ai.moying.iview.state.MachineStateService
import ai.moying.iview.production.ProductionService
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration
class DeviceModuleConfiguration {
    @Bean
    fun deviceCatalogObjectMapper() = jacksonObjectMapper()
        .registerModule(JavaTimeModule())
        .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)

    @Bean
    fun deviceCatalogService(repository: DeviceCatalogRepository) = DeviceCatalogService(repository)

    @Bean
    fun deviceRuntimeMapper(catalog: DeviceCatalogService) = DeviceRuntimeMapper(catalog)

    @Bean(destroyMethod = "close")
    fun deviceSessionPool(drivers: DriverRegistry) = DeviceSessionPool(drivers)

    @Bean
    fun collectionEngine(pool: DeviceSessionPool, telemetry: TelemetryRepository, outbound: OutboundPublisher,
        alarms: CollectionAlarmMonitor, states: MachineStateService, production: ProductionService) =
        CollectionEngine(pool) { values -> telemetry.append(values); alarms.inspect(values); states.inspect(values); production.inspect(values); outbound.publish(values) }

    @Bean
    fun deviceCollectionService(catalog: DeviceCatalogService, runtimeMapper: DeviceRuntimeMapper,
        engine: CollectionEngine, telemetry: TelemetryRepository, realtimeHub: DeviceRealtimeHub) =
        DeviceCollectionService(catalog, runtimeMapper, engine, telemetry, realtimeHub)

    @Bean
    fun deviceDiagnosticsService(catalog: DeviceCatalogService, drivers: DriverRegistry, runtimeMapper: DeviceRuntimeMapper) =
        DeviceDiagnosticsService(catalog, drivers, runtimeMapper)

    @Bean
    fun deviceControlService(catalog: DeviceCatalogService, runtimeMapper: DeviceRuntimeMapper,
        sessions: DeviceSessionPool, objectMapper: com.fasterxml.jackson.databind.ObjectMapper) =
        DeviceControlService(catalog, runtimeMapper, sessions, objectMapper)
}

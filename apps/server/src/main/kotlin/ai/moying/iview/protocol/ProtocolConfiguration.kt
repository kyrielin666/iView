package ai.moying.iview.protocol

import ai.moying.iview.collector.DriverRegistry
import ai.moying.iview.protocol.modbus.ModbusTcpDriver
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration
class ProtocolConfiguration {
    @Bean
    fun modbusTcpDriver(): ProtocolDriver = ModbusTcpDriver()

    @Bean
    fun driverRegistry(drivers: List<ProtocolDriver>) = DriverRegistry(drivers)
}

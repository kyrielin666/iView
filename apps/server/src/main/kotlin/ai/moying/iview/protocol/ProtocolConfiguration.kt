package ai.moying.iview.protocol

import ai.moying.iview.collector.DriverRegistry
import ai.moying.iview.protocol.modbus.ModbusTcpDriver
import ai.moying.iview.protocol.modbus.rtu.ModbusRtuDriver
import ai.moying.iview.protocol.mitsubishi.mc3e.MitsubishiMc3eDriver
import ai.moying.iview.protocol.opcua.OpcUaDriver
import ai.moying.iview.protocol.s7.S7Driver
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration
class ProtocolConfiguration {
    @Bean
    fun modbusTcpDriver(): ProtocolDriver = ModbusTcpDriver()

    @Bean
    fun modbusRtuDriver(): ProtocolDriver = ModbusRtuDriver()

    @Bean
    fun mitsubishiMc3eDriver(): ProtocolDriver = MitsubishiMc3eDriver()

    @Bean
    fun s7Driver(): ProtocolDriver = S7Driver()

    @Bean
    fun opcUaDriver(): ProtocolDriver = OpcUaDriver()

    @Bean
    fun driverRegistry(drivers: List<ProtocolDriver>) = DriverRegistry(drivers)
}

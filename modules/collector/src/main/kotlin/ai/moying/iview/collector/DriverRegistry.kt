package ai.moying.iview.collector

import ai.moying.iview.protocol.ProtocolDriver

class DuplicateProtocolDriverException(protocolType: String) :
    IllegalArgumentException("A driver is already registered for protocol '$protocolType'")

class UnknownProtocolDriverException(protocolType: String) :
    NoSuchElementException("No driver is registered for protocol '$protocolType'")

class DriverRegistry(drivers: Iterable<ProtocolDriver>) {
    private val driversByType: Map<String, ProtocolDriver> = buildMap {
        drivers.forEach { driver ->
            val key = normalize(driver.protocolType)
            if (put(key, driver) != null) {
                throw DuplicateProtocolDriverException(driver.protocolType)
            }
        }
    }

    fun require(protocolType: String): ProtocolDriver =
        driversByType[normalize(protocolType)] ?: throw UnknownProtocolDriverException(protocolType)

    fun supportedProtocols(): Set<String> = driversByType.keys

    private fun normalize(protocolType: String): String = protocolType.trim().lowercase()
}

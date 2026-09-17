pluginManagement {
    repositories {
        maven { url = uri("https://repo.huaweicloud.com/repository/maven/") }
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        maven { url = uri("https://repo.huaweicloud.com/repository/maven/") }
        mavenCentral()
    }
}

rootProject.name = "iview"

include(
    ":apps:server",
    ":modules:core-model",
    ":modules:protocol-spi",
    ":modules:protocol-modbus-tcp",
    ":modules:protocol-modbus-rtu",
    ":modules:protocol-mitsubishi-mc3e",
    ":modules:protocol-mitsubishi-mc4e",
    ":modules:protocol-mitsubishi-mca1e",
    ":modules:protocol-omron-fins",
    ":modules:protocol-omron-fins-udp",
    ":modules:protocol-s7",
    ":modules:protocol-opcua",
    ":modules:collector",
    ":modules:device",
    ":modules:device-jdbc",
    ":modules:oee",
    ":modules:machine-state",
    ":modules:machine-state-jdbc",
    ":modules:production",
    ":modules:production-jdbc",
    ":modules:production-plan",
    ":modules:production-plan-jdbc",
    ":modules:alarm",
    ":modules:alarm-jdbc",
    ":modules:outbound",
    ":modules:outbound-jdbc",
    ":modules:query-api",
    ":modules:query-api-jdbc",
    ":modules:telemetry",
    ":modules:telemetry-jdbc",
)

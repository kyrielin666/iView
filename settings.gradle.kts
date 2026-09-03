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
    ":modules:collector",
    ":modules:device",
    ":modules:device-jdbc",
    ":modules:oee",
    ":modules:query-api",
    ":modules:telemetry",
    ":modules:telemetry-jdbc",
)

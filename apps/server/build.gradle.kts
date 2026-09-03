plugins {
    kotlin("jvm")
    kotlin("plugin.spring")
    id("org.springframework.boot")
    id("io.spring.dependency-management")
}

kotlin { jvmToolchain(21) }

dependencies {
    implementation(project(":modules:core-model"))
    implementation(project(":modules:protocol-spi"))
    implementation(project(":modules:protocol-modbus-tcp"))
    implementation(project(":modules:collector"))
    implementation(project(":modules:device"))
    implementation(project(":modules:device-jdbc"))
    implementation(project(":modules:oee"))
    implementation(project(":modules:query-api"))
    implementation(project(":modules:telemetry"))
    implementation(project(":modules:telemetry-jdbc"))
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.springframework.boot:spring-boot-starter-jdbc")
    implementation("org.springframework.boot:spring-boot-flyway")
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin")
    implementation("org.jetbrains.kotlin:kotlin-reflect")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
    runtimeOnly("com.h2database:h2")
    runtimeOnly("org.postgresql:postgresql")
    runtimeOnly("org.flywaydb:flyway-database-postgresql")
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.boot:spring-boot-webmvc-test")
    testImplementation(kotlin("test"))
}

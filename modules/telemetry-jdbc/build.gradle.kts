plugins {
    kotlin("jvm")
    kotlin("plugin.spring")
}

kotlin { jvmToolchain(21) }

dependencies {
    api(project(":modules:telemetry"))
    implementation(platform("org.springframework.boot:spring-boot-dependencies:4.0.2"))
    implementation("org.springframework:spring-context")
    implementation("org.springframework:spring-jdbc")
    implementation("com.fasterxml.jackson.core:jackson-databind")
    testImplementation(kotlin("test"))
}

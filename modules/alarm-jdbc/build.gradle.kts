plugins {
    kotlin("jvm")
    kotlin("plugin.spring")
}

kotlin { jvmToolchain(21) }

dependencies {
    api(project(":modules:alarm"))
    implementation(platform("org.springframework.boot:spring-boot-dependencies:4.0.2"))
    implementation("org.springframework:spring-context")
    implementation("org.springframework:spring-jdbc")
    testImplementation(kotlin("test"))
}

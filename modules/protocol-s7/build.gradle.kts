plugins {
    kotlin("jvm")
}

kotlin { jvmToolchain(21) }

dependencies {
    implementation(project(":modules:protocol-spi"))
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
    implementation("org.apache.plc4x:plc4j-driver-s7:0.13.1")
    testImplementation(kotlin("test"))
}

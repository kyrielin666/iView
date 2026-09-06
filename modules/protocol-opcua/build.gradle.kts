plugins {
    kotlin("jvm")
}

kotlin { jvmToolchain(21) }

dependencies {
    implementation(project(":modules:protocol-spi"))
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
    implementation("org.eclipse.milo:milo-sdk-client:1.1.6")
    testImplementation(kotlin("test"))
}

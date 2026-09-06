plugins { kotlin("jvm") }
kotlin { jvmToolchain(21) }
dependencies { implementation(project(":modules:protocol-spi")); implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2"); implementation("com.fazecast:jSerialComm:2.9.2"); testImplementation(kotlin("test")) }

plugins { kotlin("jvm") }
kotlin { jvmToolchain(21) }
dependencies { api(project(":modules:core-model")); testImplementation(kotlin("test")) }

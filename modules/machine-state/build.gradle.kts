plugins { kotlin("jvm") }
kotlin { jvmToolchain(21) }
dependencies {
    api(project(":modules:core-model"))
    api(project(":modules:oee"))
    testImplementation(kotlin("test"))
}

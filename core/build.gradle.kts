plugins { kotlin("jvm"); kotlin("plugin.serialization") }
kotlin { jvmToolchain(17) }
dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.8.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
    testImplementation(kotlin("test-junit"))
}
val nativeConfigure by tasks.registering(Exec::class) {
    commandLine("cmake", "-S", "${rootDir}/native", "-B", "${layout.buildDirectory.get()}/native", "-DCMAKE_BUILD_TYPE=Debug")
    environment("JAVA_HOME", System.getProperty("java.home"))
}
val nativeBuild by tasks.registering(Exec::class) {
    dependsOn(nativeConfigure)
    commandLine("cmake", "--build", "${layout.buildDirectory.get()}/native", "-j", "4")
}
tasks.test {
    dependsOn(nativeBuild)
    systemProperty("java.library.path", "${layout.buildDirectory.get()}/native")
}

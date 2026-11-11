plugins { kotlin("jvm") }
kotlin { jvmToolchain(21) }
dependencies {
    implementation("org.jetbrains.jediterm:jediterm-core:3.69")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0")
    testImplementation(kotlin("test"))
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.11.0")
}
tasks.test { useJUnitPlatform() }

tasks.register<JavaExec>("releaseKey") {
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("local.ringleader.core.tools.ReleaseToolKt")
    // One property per argument: gradlew.bat runs through cmd.exe, where a separator such as | would be a pipe.
    //   -Prelease=generate -PreleaseKey=<private key path>
    //   -Prelease=sign     -PreleaseKey=<private key path> -PreleaseManifest=<manifest path>
    //   -Prelease=verify   -PreleasePublic=<public key>    -PreleaseManifest=<manifest path>
    fun property(name: String) = providers.gradleProperty(name).orNull
    args(listOfNotNull(property("release"), property("releaseKey") ?: property("releasePublic"), property("releaseManifest")))
}

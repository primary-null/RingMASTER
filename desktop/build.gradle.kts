import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    kotlin("jvm")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.compose")
}
val generatedVersionDir = layout.buildDirectory.dir("generated/sources/appVersion/kotlin")
val generateAppVersion by tasks.registering {
    val appVersion = project.version.toString()
    inputs.property("appVersion", appVersion)
    outputs.dir(generatedVersionDir)
    doLast {
        val file = generatedVersionDir.get().file("local/ringleader/desktop/AppVersion.kt").asFile
        file.parentFile.mkdirs()
        file.writeText("package local.ringleader.desktop\n\ninternal object AppVersion { const val value = \"$appVersion\" }\n")
    }
}
kotlin {
    jvmToolchain(21)
    sourceSets.main { kotlin.srcDir(generateAppVersion) }
}
dependencies {
    implementation("org.jetbrains.jediterm:jediterm-core:3.69")
    testImplementation(kotlin("test"))
    implementation(project(":core"))
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")
    implementation("io.ktor:ktor-server-cio:3.6.0")
    implementation("io.ktor:ktor-server-netty:3.6.0")
    implementation("io.ktor:ktor-server-websockets:3.6.0")
    implementation(compose.desktop.currentOs)
    implementation(compose.material3)
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:1.11.0")
    implementation("me.friwi:jcefmaven:152.0.6")
}
// Windows Bluetooth helper (Rust, native/ringmaster-bt). Built when cargo is available on a Windows build host and
// shipped through the app resources; without it the app falls back to the bundled PowerShell scanner.
val bluetoothHelper = layout.projectDirectory.file("resources/windows/ringmaster-bt.exe")
val buildBluetoothHelper by tasks.registering {
    val crate = rootProject.layout.projectDirectory.dir("native/ringmaster-bt").asFile
    val home = System.getProperty("user.home")
    val cargo = File(home, ".cargo/bin/cargo.exe").takeIf { it.isFile }
        ?: System.getenv("PATH").orEmpty().split(File.pathSeparator).map { File(it, "cargo.exe") }.firstOrNull { it.isFile }
    val windowsHost = System.getProperty("os.name").startsWith("Windows")
    val out = bluetoothHelper.asFile
    inputs.dir(File(crate, "src")); inputs.file(File(crate, "Cargo.toml"))
    outputs.file(out)
    onlyIf("Windows host with cargo") { windowsHost && cargo != null }
    doLast {
        // The GNU toolchain's self-contained tools (dlltool, linker) live next to the target's rustlib.
        val selfContained = File(home, ".rustup/toolchains").listFiles().orEmpty()
            .map { File(it, "lib/rustlib/x86_64-pc-windows-gnu/bin/self-contained") }.filter { it.isDirectory }
        val pb = ProcessBuilder(cargo!!.path, "build", "--release", "--locked", "--manifest-path", File(crate, "Cargo.toml").path).inheritIO()
        pb.environment()["PATH"] = (listOf(cargo.parentFile.path) + selfContained.map { it.path } + System.getenv("PATH")).joinToString(File.pathSeparator)
        // Panic locations would otherwise carry this computer's paths (and so the account name) into the shipped exe.
        // The later, more specific prefix wins. The \u001f-separated form keeps paths with spaces intact.
        pb.environment()["CARGO_ENCODED_RUSTFLAGS"] = listOf("--remap-path-prefix=$home=~", "--remap-path-prefix=${crate.path}=ringmaster-bt").joinToString("\u001f")
        check(pb.start().waitFor() == 0) { "cargo build failed for native/ringmaster-bt" }
        out.parentFile.mkdirs()
        File(crate, "target/release/ringmaster-bt.exe").copyTo(out, overwrite = true)
    }
}
tasks.matching { it.name == "prepareAppResources" }.configureEach { dependsOn(buildBluetoothHelper) }

compose.desktop {
    application {
        mainClass = "local.ringleader.desktop.MainKt"
        nativeDistributions {
            targetFormats(TargetFormat.Deb, TargetFormat.Msi)
            appResourcesRootDir.set(project.layout.projectDirectory.dir("resources"))
            packageName = "ringmaster"
            packageVersion = project.version.toString()
            description = "RingMASTER desktop infrastructure client"
            vendor = "RingMASTER"
            modules("java.net.http", "jdk.crypto.ec", "jdk.unsupported", "java.management", "java.naming")
            linux {
                shortcut = true
                menuGroup = "System"
                appCategory = "System"
                iconFile.set(project.file("icons/ringmaster.png"))
            }
            windows {
                // Per-user install: no administrator prompt; Start menu and desktop shortcuts.
                perUserInstall = true
                iconFile.set(project.file("icons/ringmaster.ico"))
                menu = true
                menuGroup = "RingMASTER"
                shortcut = true
                dirChooser = false
                // Fixed so each newer MSI upgrades the installed one in place.
                upgradeUuid = "3f6c2a7e-8f4b-4d1e-9b2a-52c0d7e1a9b4"
            }
        }
    }
}

tasks.test {
    useJUnitPlatform()
    systemProperty("ringleader.buildVersion", project.version.toString())
}

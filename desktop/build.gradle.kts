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
// The app's own licence travels inside the app jar, so About can show it on every platform and in browser mode.
// Its own folder and name: other jars also carry a LICENSE at their root.
tasks.processResources {
    from(rootProject.file("LICENSE")) { into("legal"); rename { "RingMASTER-LICENSE.txt" } }
}
dependencies {
    implementation("org.jetbrains.jediterm:jediterm-core:3.69")
    testImplementation(kotlin("test"))
    implementation(project(":core"))
    testImplementation(testFixtures(project(":core")))
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")
    implementation("io.ktor:ktor-server-cio:3.6.0")
    implementation("io.ktor:ktor-server-netty:3.6.0") {
        // HTTP/3 is not used. Its native libraries come as one jar per operating system, and every package carried all of
        // them (about 11 MB). The classes stay; WebTlsEngineTest proves the TLS engine starts without the natives.
        exclude(group = "io.netty", module = "netty-codec-native-quic")
    }
    implementation("io.ktor:ktor-server-websockets:3.6.0")
    implementation(compose.desktop.currentOs)
    implementation(compose.material3)
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:1.11.0")
    implementation("me.friwi:jcefmaven:152.0.6")
    implementation("net.java.dev.jna:jna:5.19.1")
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
    inputs.dir(File(crate, "src")); inputs.file(File(crate, "Cargo.toml")); inputs.file(File(crate, "Cargo.lock"))
    outputs.file(out)
    onlyIf("Windows host with cargo") { windowsHost && cargo != null }
    doLast {
        // The GNU toolchain's self-contained tools (dlltool, linker) live next to the target's rustlib.
        val selfContained = File(home, ".rustup/toolchains").listFiles().orEmpty()
            .map { File(it, "lib/rustlib/x86_64-pc-windows-gnu/bin/self-contained") }.filter { it.isDirectory }
        val pb = ProcessBuilder(cargo!!.path, "build", "--release", "--locked", "--manifest-path", File(crate, "Cargo.toml").path).inheritIO()
        pb.environment()["PATH"] = (listOf(cargo.parentFile.path) + selfContained.map { it.path } + System.getenv("PATH")).joinToString(File.pathSeparator)
        // A forced progress bar writes only carriage returns. The build queue captures that pipe and the compile stalls.
        pb.environment()["CARGO_TERM_PROGRESS_WHEN"] = "never"
        // Panic locations would otherwise carry this computer's paths (and so the account name) into the shipped exe.
        // The later, more specific prefix wins. The \u001f-separated form keeps paths with spaces intact.
        // +crt-static has to live here. Cargo uses only one rustflags source, and this variable wins over .cargo/config.toml.
        pb.environment()["CARGO_ENCODED_RUSTFLAGS"] = listOf(
            "-Ctarget-feature=+crt-static",
            "--remap-path-prefix=$home=~",
            "--remap-path-prefix=${crate.path}=ringmaster-bt",
        ).joinToString("\u001f")
        check(pb.start().waitFor() == 0) { "cargo build failed for native/ringmaster-bt" }
        out.parentFile.mkdirs()
        File(crate, "target/release/ringmaster-bt.exe").copyTo(out, overwrite = true)
    }
}
tasks.matching { it.name == "prepareAppResources" }.configureEach { dependsOn(buildBluetoothHelper) }

// Windows notification helper (Rust, native/ringmaster-notify): WinRT app notifications under RingMASTER's own
// AppUserModelID. Built the same way as the Bluetooth helper and shipped beside it. Without it the app reports
// Windows notifications as unsupported; the in-app notification center is unaffected.
val notifyHelper = layout.projectDirectory.file("resources/windows/ringmaster-notify.exe")
val buildNotifyHelper by tasks.registering {
    val crate = rootProject.layout.projectDirectory.dir("native/ringmaster-notify").asFile
    val home = System.getProperty("user.home")
    val cargo = File(home, ".cargo/bin/cargo.exe").takeIf { it.isFile }
        ?: System.getenv("PATH").orEmpty().split(File.pathSeparator).map { File(it, "cargo.exe") }.firstOrNull { it.isFile }
    val windowsHost = System.getProperty("os.name").startsWith("Windows")
    val out = notifyHelper.asFile
    inputs.dir(File(crate, "src")); inputs.file(File(crate, "Cargo.toml")); inputs.file(File(crate, "Cargo.lock"))
    outputs.file(out)
    onlyIf("Windows host with cargo") { windowsHost && cargo != null }
    doLast {
        // Unlike the Bluetooth task, the GNU "self-contained" folders are not put on PATH: a linker found on PATH
        // makes rustc assume an external MinGW and drop its own CRT objects, which fails to link ("cannot find
        // crt2.o") when more than one toolchain is installed. rustc finds its self-contained linker by itself.
        // Output is drained into the Gradle log; inheriting the daemon's stdio stalled on a full pipe.
        val pb = ProcessBuilder(cargo!!.path, "build", "--release", "--locked", "--manifest-path", File(crate, "Cargo.toml").path)
            .redirectErrorStream(true)
            .redirectInput(ProcessBuilder.Redirect.from(File(if (windowsHost) "NUL" else "/dev/null")))
        pb.environment()["PATH"] = (listOf(cargo.parentFile.path) + System.getenv("PATH")).joinToString(File.pathSeparator)
        pb.environment()["CARGO_TERM_PROGRESS_WHEN"] = "never"
        pb.environment()["CARGO_TERM_COLOR"] = "never"
        // Same reasons as the Bluetooth helper: static CRT, and no build-host paths (account name) in the shipped exe.
        pb.environment()["CARGO_ENCODED_RUSTFLAGS"] = listOf(
            "-Ctarget-feature=+crt-static",
            "--remap-path-prefix=$home=~",
            "--remap-path-prefix=${crate.path}=ringmaster-notify",
        ).joinToString("\u001f")
        val process = pb.start()
        process.inputStream.bufferedReader().useLines { lines -> lines.forEach { logger.lifecycle("cargo: {}", it.take(400)) } }
        check(process.waitFor() == 0) { "cargo build failed for native/ringmaster-notify" }
        out.parentFile.mkdirs()
        File(crate, "target/release/ringmaster-notify.exe").copyTo(out, overwrite = true)
    }
}
tasks.matching { it.name == "prepareAppResources" }.configureEach { dependsOn(buildNotifyHelper) }

// Class-data archive. The bundled runtime starts without one, so every start parsed and verified some 7,000 classes
// again (Java's, Compose's and the app's). The archive holds them ready-made: on the test systems the window appears in
// about half the time and start-up uses about 40% less processor time.
//
// It is built at packaging time from the list of classes the app loads while starting (cds/<os>.classlist; class names
// only). A static dump needs no display and runs no application code. Java accepts the archive only for the same class
// path, in the same order, so it is dumped against the app image's own launcher configuration; the jar names carry a
// content hash, which ties the archive to exactly these jars. The file goes into the runtime folder of the app image
// (the Arch package is built from that image) and of the jlink image (MSI, DEB and RPM are built from that one).
// If anything does not match on a user's system, Java ignores the archive and starts as it did before.
val classArchiveName = "ringmaster.jsa"
val appClassArchive by tasks.registering {
    val windows = System.getProperty("os.name").startsWith("Windows")
    val list = layout.projectDirectory.file("cds/${if (windows) "windows" else "linux"}.classlist")
    val jlinkImage = layout.buildDirectory.dir("compose/tmp/main/runtime")
    val appImage = layout.buildDirectory.dir("compose/binaries/main/app/ringmaster")
    val jdk = File(System.getProperty("java.home"))
    dependsOn("createDistributable")
    inputs.file(list)
    onlyIf("a class list for this operating system") { list.asFile.isFile }
    doLast {
        val root = appImage.get().asFile
        val appDir = File(root, if (windows) "app" else "lib/app")
        val runtime = File(root, if (windows) "runtime" else "lib/runtime")
        fun version(home: File) = File(home, "release").takeIf { it.isFile }?.readLines()?.firstOrNull { it.startsWith("JAVA_VERSION=") }
        // Only the JDK that built the runtime can write an archive for it. With any other, the package is built without
        // one and the app starts as it did before.
        if (version(runtime) == null || version(runtime) != version(jdk)) {
            logger.warn("Class-data archive skipped: runtime image {} and build JDK {} differ", version(runtime), version(jdk)); return@doLast
        }
        val launcherPath = File(appDir, "ringmaster.cfg").readLines().filter { it.startsWith("app.classpath=") }
            .map { File(appDir, it.substringAfter('=').removePrefix("\$APPDIR").trimStart('\\', '/')) }
        check(launcherPath.isNotEmpty() && launcherPath.all { it.isFile }) { "Could not read the class path from the app image" }
        // The packaging plugin strips Skia's native library out of its jar and writes the empty rest anew in every
        // packaging task, stamped with the current time, so that one jar's hashed name differs between the app image
        // and each installer. Java accepts an archive whose class path is a leading part of the real one, so the
        // archive covers the entries before that jar (alphabetical order puts about 83% of the start-up classes there).
        val classPath = launcherPath.takeWhile { !it.name.startsWith("skiko-awt-runtime-") }
        check(classPath.size > launcherPath.size / 2) { "The class path order changed; the archive would cover too little" }
        // jlink strips the `java` launcher from the runtime; lend the build JDK's own for the dump.
        val name = if (windows) "java.exe" else "java"
        val lent = File(runtime, "bin/$name")
        val own = lent.isFile
        if (!own) { lent.parentFile.mkdirs(); File(jdk, "bin/$name").copyTo(lent); lent.setExecutable(true) }
        val archive = File(runtime, classArchiveName)
        val arguments = File(temporaryDir, "dump.args")
        fun quoted(file: File) = "\"" + file.path.replace("\\", "\\\\") + "\""
        arguments.writeText(listOf("-Xshare:dump", "-XX:SharedClassListFile=" + quoted(list.asFile), "-XX:SharedArchiveFile=" + quoted(archive),
            "-cp", "\"" + classPath.joinToString(File.pathSeparator) { it.path.replace("\\", "\\\\") } + "\"").joinToString("\n"))
        try {
            archive.delete()
            val process = ProcessBuilder(lent.path, "@" + arguments.path).redirectErrorStream(true).start()
            val output = process.inputStream.bufferedReader().readText()
            check(process.waitFor() == 0) { "Class-data archive dump failed: ${output.takeLast(800)}" }
        } finally { if (!own) lent.delete() }
        check(archive.isFile && archive.length() > 10_000_000) { "Class-data archive was not written at $archive" }
        archive.copyTo(File(jlinkImage.get().asFile, classArchiveName), overwrite = true)
        logger.lifecycle("Class-data archive: {} MB for the first {} of {} class path entries", archive.length() / 1_048_576, classPath.size, launcherPath.size)
    }
}
tasks.matching { it.name == "createDistributable" }.configureEach { finalizedBy(appClassArchive) }
tasks.matching { it.name in setOf("packageMsi", "packageDeb", "packageRpm") }.configureEach { dependsOn(appClassArchive) }

compose.desktop {
    application {
        mainClass = "local.ringleader.desktop.MainKt"
        // $APPDIR is the launcher's own token; app and runtime folders are neighbours on every system. A developer run
        // has no such file, and Java then starts without the archive.
        jvmArgs += "-XX:SharedArchiveFile=\$APPDIR/../runtime/$classArchiveName"
        nativeDistributions {
            targetFormats(TargetFormat.Deb, TargetFormat.Rpm, TargetFormat.Msi)
            appResourcesRootDir.set(project.layout.projectDirectory.dir("resources"))
            packageName = "ringmaster"
            packageVersion = project.version.toString()
            description = "RingMASTER desktop infrastructure client"
            vendor = "RingMASTER"
            // Without this the packages say only "Copyright (C) <year>", with no holder.
            copyright = "Copyright (C) 2026 primary-null"
            licenseFile.set(rootProject.file("LICENSE"))
            modules("java.net.http", "jdk.crypto.ec", "jdk.unsupported", "java.management", "java.naming", "java.logging")
            linux {
                shortcut = true
                menuGroup = "System;"
                appCategory = "System"
                debMaintainer = "RingMASTER <285952344+primary-null@users.noreply.github.com>"
                rpmLicenseType = "AGPL-3.0-only"
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
    // Skia unpacks its native library under user.home/.skiko by default. Keep it in the build folder so tests never
    // write to the real home and still run for accounts whose home is not writable (sandboxed CI and build runners).
    systemProperty("skiko.data.path", layout.buildDirectory.dir("skiko").get().asFile.absolutePath)
}

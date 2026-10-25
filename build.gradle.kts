import java.net.URI
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

plugins {
    java
    application
    id("org.openjfx.javafxplugin") version "0.1.0"
    id("com.gradleup.shadow") version "8.3.6"
    id("edu.sc.seis.launch4j") version "4.0.0"
}

group = "io.selene"
version = "0.2.0-beta.9"

repositories {
    mavenCentral()
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

javafx {
    version = "21.0.6"
    modules = listOf("javafx.controls", "javafx.graphics")
}

dependencies {
    implementation("com.fasterxml.jackson.core:jackson-databind:2.18.3")
    implementation("com.fasterxml.jackson.datatype:jackson-datatype-jdk8:2.18.3")
    implementation("com.fasterxml.jackson.datatype:jackson-datatype-jsr310:2.18.3")

    // The build runs on Linux but produces a Windows application, so package Windows JavaFX natives too.
    runtimeOnly("org.openjfx:javafx-base:21.0.6:win")
    runtimeOnly("org.openjfx:javafx-graphics:21.0.6:win")
    runtimeOnly("org.openjfx:javafx-controls:21.0.6:win")

    testImplementation(platform("org.junit:junit-bom:5.12.1"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testImplementation("org.assertj:assertj-core:3.27.3")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

application {
    mainClass = "io.selene.launcher.LauncherMain"
    applicationDefaultJvmArgs = listOf(
        "-Dfile.encoding=UTF-8",
        "-Dprism.lcdtext=false"
    )
}

tasks.test {
    useJUnitPlatform()
    testLogging {
        events("failed")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}

tasks.jar {
    manifest {
        attributes["Main-Class"] = "io.selene.launcher.LauncherMain"
        attributes["Implementation-Title"] = "Selene Launcher"
        attributes["Implementation-Version"] = project.version
    }
}

tasks.shadowJar {
    archiveClassifier.set("all")
    mergeServiceFiles()
    manifest.inheritFrom(tasks.jar.get().manifest)
}

launch4j {
    outfile = "SeleneLauncher.exe"
    mainClassName = "io.selene.launcher.LauncherMain"
    headerType = "gui"
    chdir = "."
    icon = project.file("src/main/resources/io/selene/launcher/selene.ico").absolutePath
    bundledJrePath = "runtime"
    requires64Bit = true
    jreMinVersion = "21"
    version = "0.2.0.9"
    textVersion = project.version.toString()
    companyName = "Selene Launcher Project"
    productName = "Selene Launcher"
    internalName = "SeleneLauncher"
    fileDescription = "Selene Minecraft Launcher"
    copyright = "Copyright © Selene Launcher Project"
    supportUrl = "https://github.com/"
    messagesJreNotFoundError = "Selene Launcher 携带的 Java 运行时缺失，请重新解压完整安装包。"
}

val windowsRuntimeVersion = "21.0.12.1+1"
val windowsRuntimeUrl = "https://github.com/adoptium/temurin21-binaries/releases/download/jdk-21.0.12.1%2B1/OpenJDK21U-jre_x64_windows_hotspot_21.0.12.1_1.zip"
val windowsRuntimeSha256 = "d35f31e712f0fcf6ac5a093edc90204fbff22f720ba3950bd09d331d5e621636"
val windowsRuntimeArchive = layout.buildDirectory.file("packaging/cache/temurin-jre-$windowsRuntimeVersion-windows-x64.zip")
val bootstrapPortable = layout.projectDirectory.file("dist/Luna-Launcher-0.1.0-alpha.4-windows-x64.zip")
val windowsRuntimeDirectory = layout.buildDirectory.dir("packaging/runtime")
val windowsPortableDirectory = layout.buildDirectory.dir("packaging/windows-portable")

val downloadWindowsRuntime by tasks.registering {
    group = "distribution"
    description = "Downloads the pinned Windows Java 21 runtime."
    outputs.file(windowsRuntimeArchive)

    doLast {
        val target = windowsRuntimeArchive.get().asFile.toPath()
        target.parent.toFile().mkdirs()
        val bootstrap = bootstrapPortable.asFile
        if (bootstrap.isFile) {
            logger.lifecycle("Recovering the previously verified Windows runtime from ${bootstrap.name}")
            target.toFile().delete()
            ZipFile(bootstrap).use { source ->
                ZipOutputStream(target.toFile().outputStream()).use { output ->
                    val entries = source.entries()
                    var copied = 0
                    while (entries.hasMoreElements()) {
                        val entry = entries.nextElement()
                        val marker = "/runtime/"
                        val index = entry.name.indexOf(marker)
                        if (index < 0 || entry.isDirectory) continue
                        val relative = entry.name.substring(index + marker.length)
                        if (relative.isBlank()) continue
                        output.putNextEntry(ZipEntry("jdk-21.0.12.1+1-jre/" + relative))
                        source.getInputStream(entry).use { it.copyTo(output) }
                        output.closeEntry()
                        copied++
                    }
                    check(copied > 100) { "Bootstrap package did not contain a complete Windows runtime" }
                }
            }
            logger.lifecycle("Recovered $windowsRuntimeVersion from the verified alpha.4 portable package")
            return@doLast
        }

        logger.lifecycle("Downloading Eclipse Temurin JRE $windowsRuntimeVersion for Windows x64...")
        target.toFile().delete()
        URI(windowsRuntimeUrl).toURL().openStream().use { input ->
            target.toFile().outputStream().use { output -> input.copyTo(output) }
        }
        val digest = MessageDigest.getInstance("SHA-256")
        val actual = target.toFile().inputStream().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
            digest.digest().joinToString("") { "%02x".format(it) }
        }
        check(actual == windowsRuntimeSha256) {
            target.toFile().delete()
            "Windows Java runtime checksum mismatch: expected $windowsRuntimeSha256, got $actual"
        }
    }
}

val unpackWindowsRuntime by tasks.registering(Sync::class) {
    group = "distribution"
    description = "Unpacks the pinned Windows Java runtime."
    dependsOn(downloadWindowsRuntime)
    from(provider { zipTree(windowsRuntimeArchive.get().asFile) })
    into(windowsRuntimeDirectory)
    includeEmptyDirs = false
    eachFile {
        val flattened = relativePath.segments.drop(1).toTypedArray()
        relativePath = RelativePath(true, *flattened)
    }
}

val stageWindowsPortable by tasks.registering(Sync::class) {
    group = "distribution"
    description = "Stages the self-contained Windows application."
    dependsOn(tasks.named("createExe"), unpackWindowsRuntime)
    doFirst {
        val target = windowsPortableDirectory.get().asFile
        if (target.exists()) {
            target.walkTopDown().forEach { it.setWritable(true, false) }
            project.delete(target)
        }
    }
    into(windowsPortableDirectory)
    from(layout.buildDirectory.dir("launch4j")) {
        exclude("*.xml", "lib/*-linux.jar")
    }
    from(windowsRuntimeDirectory) {
        into("runtime")
    }
    from(tasks.shadowJar) {
        rename { "SeleneLauncher.jar" }
    }
    from("SeleneLauncher-Diagnostic.cmd")
    from("README.md") {
        rename { "README.txt" }
    }
    from("LICENSE")
}

val verifyWindowsPortable by tasks.registering {
    group = "verification"
    description = "Verifies every file required by the portable Windows launcher."
    dependsOn(stageWindowsPortable)
    inputs.dir(windowsPortableDirectory)

    doLast {
        val root = windowsPortableDirectory.get().asFile
        val required = listOf(
            "SeleneLauncher.exe",
            "SeleneLauncher.jar",
            "SeleneLauncher-Diagnostic.cmd",
            "runtime/bin/java.exe",
            "runtime/bin/javaw.exe",
            "runtime/bin/server/jvm.dll",
            "lib/javafx-controls-21.0.6-win.jar",
            "lib/javafx-graphics-21.0.6-win.jar"
        )
        val missing = required.filterNot { root.resolve(it).isFile }
        check(missing.isEmpty()) {
            "Portable Windows package is incomplete; missing: ${missing.joinToString(", ")}"
        }
    }
}

val windowsPortableZip by tasks.registering(Zip::class) {
    group = "distribution"
    description = "Builds the portable Windows x64 application archive."
    dependsOn(verifyWindowsPortable)
    destinationDirectory.set(layout.projectDirectory.dir("dist"))
    archiveFileName.set("Selene-Launcher-${project.version}-windows-x64.zip")
    from(windowsPortableDirectory) {
        into("Selene Launcher")
    }
}

tasks.build {
    dependsOn(tasks.shadowJar, windowsPortableZip)
}

tasks.register("phaseOneCheck") {
    group = "verification"
    description = "Runs all verification required by Selene phase one."
    dependsOn(tasks.test)
}

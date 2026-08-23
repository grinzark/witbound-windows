import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    kotlin("jvm") version "2.1.0"
    id("org.jetbrains.kotlin.plugin.compose") version "2.1.0"
    id("org.jetbrains.compose") version "1.7.3"
}

repositories {
    mavenCentral()
    google()
    maven("https://maven.pkg.jetbrains.space/public/p/compose/dev")
}

dependencies {
    implementation(compose.desktop.currentOs)
    implementation(compose.material3)
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:1.9.0")
    implementation("org.jsoup:jsoup:1.17.2")
    implementation("org.jmdns:jmdns:3.5.9")
    implementation("org.json:json:20240303")
    testImplementation(kotlin("test"))
}

kotlin { jvmToolchain(21) }
tasks.test { useJUnitPlatform() }

compose.desktop {
    application {
        mainClass = "app.witbound.MainKt"
        // Ships whisper-cli/ffmpeg/model per-OS from resources/<os>/ when present
        // (drop the CUDA whisper-cli.exe + ffmpeg.exe + model into resources/windows/).
        nativeDistributions {
            targetFormats(TargetFormat.Msi, TargetFormat.Exe, TargetFormat.Deb, TargetFormat.Dmg)
            packageName = "Witbound for Windows"
            packageVersion = "1.0.0"
            vendor = "Witbound"
            description = "Sync a book on your computer and send it to your phone, already synced."
            appResourcesRootDir.set(project.layout.projectDirectory.dir("app-resources"))
            windows {
                menuGroup = "Witbound"
                perUserInstall = true
                // stable UUID so upgrades replace cleanly (generated once)
                upgradeUuid = "8f3a1b2c-4d5e-4f60-9a71-2b3c4d5e6f70"
            }
            modules("java.instrument", "java.management", "jdk.unsupported")
        }
    }
}

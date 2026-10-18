import com.xuncorp.spw.workshop.gradle.PluginPermission
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("java-library")
    id("org.jetbrains.kotlin.jvm") version "2.4.20"
    id("org.jetbrains.kotlin.kapt") version "2.4.20"
    id("com.xuncorp.spw.workshop") version "0.1.0-dev22"
}

group = "com.gg.spwaiplaylist"
version = "1.1.1"

java {
    sourceCompatibility = JavaVersion.VERSION_25
    targetCompatibility = JavaVersion.VERSION_25
}

kotlin {
    jvmToolchain(25)
    compilerOptions {
        jvmTarget = JvmTarget.JVM_25
        freeCompilerArgs.add("-Xjvm-default=all")
    }
}

dependencies {
    compileOnly(kotlin("stdlib"))
    compileOnly("com.github.Moriafly:spw-workshop-api:0.1.0-dev22")
    kapt("com.github.Moriafly:spw-workshop-api:0.1.0-dev22")
    compileOnly("org.pf4j:pf4j:3.12.0")
    compileOnly("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
    implementation("org.xerial:sqlite-jdbc:3.47.1.0")
    implementation("org.slf4j:slf4j-api:2.0.13")
    implementation("org.json:json:20240303")
}

spmod {
    PluginClass = "com.gg.spwaiplaylist.MainPlugin"
    PluginId = "com.gg.spwaiplaylist"
    PluginName = "SPW AI Playlist"
    PluginDescription = "An AI-powered playlist plugin for SPW."
    PluginVersion = project.version.toString()
    PluginProvider = "Augustu"
    PluginHasConfig = true
    PluginPermissions = listOf(PluginPermission.LIBRARY_READ)
    PluginOpenSourceUrl = "https://github.com/AugustuXue/spw-ai-playlist"
}

val workshopPluginsDir = file(System.getenv("APPDATA") + "/Salt Player for Windows/workshop/plugins/")
val pluginInstallDir = workshopPluginsDir.resolve("SPW-AI-Playlist-${project.version}")

tasks.register<Sync>("deployPlugin") {
    dependsOn(tasks.named("plugin"))
    into(pluginInstallDir)
    from({ zipTree(tasks.named<Zip>("plugin").get().archiveFile.get()) })
}

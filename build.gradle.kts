import org.gradle.api.file.DuplicatesStrategy
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("java-library")
    id("org.jetbrains.kotlin.jvm") version "2.3.0"
}

group = "com.gg.spwaiplaylist"
version = "1.0.0-test"

java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
}

kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_21
        freeCompilerArgs.add("-Xjvm-default=all")
    }
}

repositories {
    mavenCentral()
}

dependencies {
    compileOnly(kotlin("stdlib"))
    compileOnly(files("libs/api-0.1.0-dev20.jar"))
    compileOnly("org.pf4j:pf4j:3.12.0")
    compileOnly("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
    implementation("org.xerial:sqlite-jdbc:3.47.1.0")
    implementation("org.slf4j:slf4j-api:2.0.13")
    implementation("org.json:json:20240303")
}

val pluginClass = "com.gg.spwaiplaylist.MainPlugin"
val pluginId = "com.gg.spwaiplaylist"
val pluginName = "SPW AI Playlist"
val pluginDescription = "An AI-powered playlist plugin for SPW."
val pluginVersion = "1.0.0-test"
val pluginProvider = "Augustu"
val workshopPluginsDir = file(System.getenv("APPDATA") + "/Salt Player for Windows/workshop/plugins/")
val pluginInstallDir = workshopPluginsDir.resolve("SPW-AI-Playlist-$pluginVersion")

fun jarContents() = zipTree(tasks.named<Jar>("jar").get().archiveFile.get())

tasks.named<Jar>("jar") {
    manifest {
        attributes(
            "Plugin-Class" to pluginClass,
            "Plugin-Id" to pluginId,
            "Plugin-Name" to pluginName,
            "Plugin-Description" to pluginDescription,
            "Plugin-Version" to pluginVersion,
            "Plugin-Provider" to pluginProvider,
            "Plugin-Open-Source-Url" to "https://github.com/AugustuXue/spw-ai-playlist",
            "Plugin-Has-Config" to "true",
        )
    }
}

tasks.named<ProcessResources>("processResources") {
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
}

tasks.register<Zip>("pluginZip") {
    destinationDirectory.set(workshopPluginsDir)
    archiveFileName.set("SPW-AI-Playlist-$pluginVersion.zip")
    archiveExtension.set("zip")

    dependsOn(tasks.named("jar"), configurations.runtimeClasspath)

    from({ jarContents() }) {
        into("classes")
    }
    from(configurations.runtimeClasspath) {
        into("lib")
        include("*.jar")
    }
}

tasks.register<Sync>("deployPlugin") {
    into(pluginInstallDir)
    dependsOn(tasks.named("jar"), configurations.runtimeClasspath)

    from({ jarContents() }) {
        into("classes")
    }
    from(configurations.runtimeClasspath) {
        into("lib")
        include("*.jar")
    }
}

tasks.register("plugin") {
    dependsOn("pluginZip", "deployPlugin")
}

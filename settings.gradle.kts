pluginManagement {
    repositories {
        mavenCentral()
        gradlePluginPortal()
        maven("https://maven.fabricmc.net/")
        maven("https://maven.kikugie.dev/releases") { name = "KikuGie Releases" }
    }
}

plugins {
    id("dev.kikugie.stonecutter") version "0.9.7"
    // Loom's dependency API changed at 26.1; this normalises it so one build
    // script can target both the 26 line and 1.21.x.
    id("dev.kikugie.loom-back-compat") version "0.4.2"
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

stonecutter {
    create(rootProject) {
        // One target per API generation, not per Minecraft release: patch
        // releases that share an API build from the same jar and are listed
        // together in mod.mc_releases.
        versions("1.21.8", "1.21.10", "1.21.11", "26.1", "26.2")
        vcsVersion = "26.2"
    }
}

rootProject.name = "Villager Trade Scanner"

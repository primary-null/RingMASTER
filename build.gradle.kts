plugins {
    kotlin("jvm") version "2.4.21" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.21" apply false
    id("org.jetbrains.compose") version "1.12.2" apply false
}
allprojects { group = "local.ringleader"; version = "0.25.0" }

pluginManagement { repositories { gradlePluginPortal(); mavenCentral(); google() } }
dependencyResolutionManagement { repositories { mavenCentral(); google(); maven("https://packages.jetbrains.team/maven/p/ij/intellij-dependencies") { content { includeGroup("org.jetbrains.jediterm") } } } }
rootProject.name = "RingMASTER"
include(":core", ":desktop")

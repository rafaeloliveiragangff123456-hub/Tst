pluginManagement {
    repositories { google(); mavenCentral(); gradlePluginPortal() }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // JitPack só para a biblioteca de ADB (libadb-android e dependências do mesmo autor)
        maven {
            url = uri("https://jitpack.io")
            content { includeGroupByRegex("com\\.github\\.MuntashirAkon.*") }
        }
    }
}
rootProject.name = "BotSync"
include(":app")

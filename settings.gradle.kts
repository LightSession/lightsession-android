pluginManagement {
    // The instrumentation plugin, built from source and resolved by id in the same build. Inside
    // `pluginManagement` rather than at the top level, because that is where Gradle looks for a
    // plugin *id*; an `includeBuild` outside it makes the project available and the id unknown.
    includeBuild("lightsession-gradle-plugin")
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "lightsession-android"
include(":app")
include(":lightsession-android")
include(":lightsession-android-sample")
include(":lightsession-bench")

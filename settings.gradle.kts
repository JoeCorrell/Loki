/*
 * THOR Launcher — Gradle settings.
 *
 * The build is split into a `build-logic` included build that publishes the
 * `thor.*` convention plugins, and the application modules themselves.
 */

pluginManagement {
    includeBuild("build-logic")
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

rootProject.name = "THOR"

enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")

include(":app")

/*
 * The standalone streaming app.
 *
 * A second application in the same build rather than a second repository, which
 * is what lets it share `:feature:stream` and `:core:streaming` as source rather
 * than as a published artefact — a change to either lands in both apps in the
 * same commit and is compiled against both.
 */
include(":moonlightds")

include(":core:model")
include(":core:common")
include(":core:database")
include(":core:datastore")
include(":core:designsystem")
include(":core:display")
include(":core:input")
include(":core:streaming")
include(":core:ui")

include(":data")

include(":feature:home")
include(":feature:topscreen")
include(":feature:settings")
include(":feature:search")
include(":feature:files")
include(":feature:movies")
include(":feature:stream")

include(":core:moonlight")

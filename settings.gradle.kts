pluginManagement {
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

rootProject.name = "android-voice-sip-agent"

// Pure JVM modules (no Android dependency; unit-testable on the host)
include(":voice-api")
include(":sip")
include(":gateway")

// Android modules
include(":telephony")
include(":privileged")
include(":sip-pjsua2")
include(":app")

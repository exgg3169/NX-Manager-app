pluginManagement {
    repositories {
        google()
        // Google's mirror of Maven Central; avoids rate limiting on repo1.
        maven("https://maven-central.storage-download.googleapis.com/maven2/")
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        // Google's mirror of Maven Central; avoids rate limiting on repo1.
        maven("https://maven-central.storage-download.googleapis.com/maven2/")
        mavenCentral()
    }
}

rootProject.name = "NX-Manager"

include(":patcher", ":cli", ":app")

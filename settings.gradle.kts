pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        maven { url = uri("https://repo.jenkins-ci.org/public/") }
        google()
        mavenCentral()
    }
}

rootProject.name = "SSH Protocol"
include(":app")

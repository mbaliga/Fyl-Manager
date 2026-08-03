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
        google()
        mavenCentral()
    }
}

rootProject.name = "Fylz"
include(":app")

// Hyle Design System (dev.aarso:hyle, dev.aarso:crash-recovery) is pulled in via the
// constellation's sanctioned sharing mechanism: git submodule + Gradle includeBuild.
// Do not vendor/copy Hyle source and do not add a second way to depend on it.
//
// hyle-design-system pins Android Gradle Plugin 8.9.1 (see its gradle/libs.versions.toml).
// This build's own AGP version (root build.gradle.kts) must match it exactly, or Gradle
// refuses the composite build with "Using multiple versions of the Android Gradle
// plugin... is not allowed". If hyle-design-system's AGP pin ever moves, bump the version
// here (and the Gradle version CI installs) to match before updating the submodule
// pointer.
includeBuild("hyle-design-system")

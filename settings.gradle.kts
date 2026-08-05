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
includeBuild("hyle-design-system") {
    // The explicit rule is load-bearing, not decoration. hyle-design-system still contains a
    // :crash-recovery TOMBSTONE declaring the same dev.aarso:crash-recovery coordinate as the real
    // module in shared-libraries (deliberately — see that module's MOVED.md; it exists so consumers
    // who have NOT migrated get an actionable compile error rather than an unresolved dependency).
    // With two composites offering one coordinate, Gradle fails with:
    //
    //   Module version 'dev.aarso:crash-recovery' is not unique in composite: can be provided by
    //   [project :hyle-design-system:crash-recovery, project :shared-libraries:crash-recovery]
    //
    // Declaring ANY explicit substitution for an included build disables AUTOMATIC substitution for
    // that build, so naming :hyle here removes hyle-design-system as a crash-recovery candidate and
    // the coordinate resolves unambiguously from shared-libraries.
    dependencySubstitution {
        substitute(module("dev.aarso:hyle")).using(project(":hyle"))
    }
}

// Shared constellation libraries (dev.aarso:crash-recovery, dev.aarso:search-core), pulled in by
// the same sanctioned mechanism as Hyle (D-A): git submodule + Gradle includeBuild. Gradle
// substitutes those coordinates with this build's projects, so no Maven registry is involved.
//
// crash-recovery MOVED here from hyle-design-system (D-V, superseding D-O): it always had zero
// :hyle dependency, so keeping it inside the design-system repo forced apps that must never
// depend on Hyle to carry the whole Hyle submodule to reach it.
//
// This build pins AGP 8.9.1, identical to hyle-design-system, so both composites agree (D-Q).
includeBuild("shared-libraries")


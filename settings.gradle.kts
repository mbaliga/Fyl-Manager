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

// Portable core modules (WP-1.3 of docs/worklog/PHASE-1-*.md): plain Kotlin/JVM libraries with
// zero Android dependency, so their tests run off-device and CI stays SDK-free for them. They
// are internal to this build only — consumed via project(":core-model") etc., not published
// Maven coordinates — so they cannot collide with the dev.aarso:* coordinates the two
// includeBuild composites below vend.
//
// core-model    — ItemRef/ItemSnapshot/VersionStamp, the ItemCapability vocabulary, EntryKind.
// core-format   — file-kind/preview-family detection (FileFormatRegistry and friends).
// core-vfs      — capability-requirement policy: which user actions need which capabilities.
// core-operations — the operation journal's pure model and state-machine policies.
//
// Dependency order: core-model has none of these as a dependency; core-format, core-vfs and
// core-operations each depend on core-model only (never on each other), so there is one shared
// foundation and three independent consumers of it — no cycle is possible to introduce by
// accident.
include(":core-model")
include(":core-format")
include(":core-vfs")
include(":core-operations")

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


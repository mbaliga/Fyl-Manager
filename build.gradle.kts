plugins {
    id("com.android.application") version "8.9.1" apply false
    id("org.jetbrains.kotlin.android") version "2.1.20" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.1.20" apply false
    id("org.jetbrains.kotlin.plugin.parcelize") version "2.1.20" apply false
}

// Paparazzi (app.cash.paparazzi, folder-card redesign, docs/agent/PROGRESS.md): renders Compose to
// real PNGs on pure JVM, no device/emulator needed -- this sandbox's only way to actually check
// visual fidelity against the Figma reference. Applied the classic buildscript+`apply(plugin=...)`
// way in app/build.gradle.kts, not the `plugins{}` block above: every version in the 2.0.0-alpha*
// line (the one with a `plugins{}`-DSL marker) pins its OWN Android Gradle Plugin somewhere between
// 8.8.1 and 9.0.0, and this project's AGP cannot move off 8.9.1 exactly -- the composite-build
// `hyle-design-system` submodule pins that version exactly (settings.gradle.kts's own comment: a
// mismatch here means Gradle refuses the whole composite build outright) -- so every 2.0.0-alpha*
// release was always going to collide with it (Gradle's own `AgpVersionCompatibilityRule`: "Using
// multiple versions of the Android Gradle plugin... is not allowed", confirmed unfixable by forcing
// or excluding the conflicting `com.android.tools.build:gradle` coordinate -- both tried, neither
// worked; full record in docs/agent/PROGRESS.md). Paparazzi 1.3.3 predates that modern,
// AGP-Variant-API-based plugin architecture entirely (CHANGELOG.md: "1.3.4 ... Migrate plugin to
// use modern AGP variant APIs" -- the migration that introduced this exact conflict class -- is the
// release AFTER 1.3.3), so it carries no such pin and applies cleanly against 8.9.1. It publishes
// no `app.cash.paparazzi.gradle.plugin` marker artifact to the Gradle Plugin Portal (confirmed: the
// `plugins{}` DSL form for it fails outright, "Plugin ... was not found"), only its raw
// `paparazzi-gradle-plugin` coordinate to Maven Central, which this classic form resolves directly.
buildscript {
    repositories {
        google()
        mavenCentral()
    }
    dependencies {
        classpath("app.cash.paparazzi:paparazzi-gradle-plugin:1.3.3")
    }
}

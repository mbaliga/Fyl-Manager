plugins {
    // AGP and Kotlin are LOCKSTEPPED with the submodules' version catalogs
    // (hyle-design-system and shared-libraries both pin agp/kotlin in
    // gradle/libs.versions.toml). All three builds share one composite graph, so a
    // mismatched AGP is rejected by Gradle outright, and a mismatched Kotlin only
    // works by forward-compatibility luck. ToolchainLockstepTest fails the build on
    // any drift, so bump all three together or not at all.
    id("com.android.application") version "8.9.1" apply false
    id("org.jetbrains.kotlin.android") version "2.1.0" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.1.0" apply false
}

plugins {
    // AGP and Kotlin are LOCKSTEPPED with the submodules' version catalogs
    // (hyle-design-system and shared-libraries both pin agp/kotlin in
    // gradle/libs.versions.toml). All three builds share one composite graph, so a
    // mismatched AGP is rejected by Gradle outright, and a mismatched Kotlin only
    // works by forward-compatibility luck. ToolchainLockstepTest fails the build on
    // any drift, so bump all three together or not at all.
    id("com.android.application") version "8.9.1" apply false
    id("org.jetbrains.kotlin.android") version "2.1.0" apply false
    // Plain Kotlin/JVM library modules (core-model, core-vfs, core-operations, core-format):
    // no Android dependency, same 2.1.0 lockstep. Same underlying kotlin-gradle-plugin jar as
    // kotlin.android above, so it resolves from the same repositories with no extra setup.
    id("org.jetbrains.kotlin.jvm") version "2.1.0" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.1.0" apply false
}

plugins {
    id("org.jetbrains.kotlin.jvm")
}

// Pure-JVM-first (see core-model/build.gradle.kts). The operation journal's actual JSON codec
// stays in app/operations/OperationJournal.kt on purpose: org.json.* is part of the Android
// platform at runtime, not something an app should bundle a second copy of, so this module
// never takes an org.json dependency — see docs/worklog/WP-1.4.md.
kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(project(":core-model"))
    testImplementation("junit:junit:4.13.2")
}

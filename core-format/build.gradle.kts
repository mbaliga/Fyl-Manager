plugins {
    id("org.jetbrains.kotlin.jvm")
}

// Pure-JVM-first (see core-model/build.gradle.kts). No android.* imports here either — the
// android.provider.DocumentsContract coupling that used to sit next to this logic in
// app/util/FileType.kt was a single MIME-string constant, replaced by a local literal on the
// move (see docs/worklog/WP-1.3.md).
kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(project(":core-model"))
    testImplementation("junit:junit:4.13.2")
}

plugins {
    id("org.jetbrains.kotlin.jvm")
}

// The pure-JVM-first module law (constellation-wide): correctness-critical identity and
// capability logic lives here with zero Android SDK dependency, so its tests run off-device
// and this module compiles even when the Android SDK is unavailable. Do not add an
// androidx.* or android.* import to this module — that is what app/storage/ItemRefs.kt (the
// Uri <-> ItemRef adapter) exists to isolate.
kotlin {
    jvmToolchain(17)
}

dependencies {
    testImplementation("junit:junit:4.13.2")
}

plugins {
    id("org.jetbrains.kotlin.jvm")
}

// Pure-JVM-first (see core-model/build.gradle.kts).
kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(project(":core-model"))
    testImplementation("junit:junit:4.13.2")
}

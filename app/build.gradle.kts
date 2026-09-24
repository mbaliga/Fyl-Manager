plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "io.github.mbaliga.fylz"
    compileSdk = 36
    // M2.3: pinned to the NDK r28+ release installed for fylz-core's cargo-ndk build below,
    // per docs/agent/MASTER_PLAN.md section 2.3 (16 KB page alignment by default).
    ndkVersion = "28.2.13676358"

    defaultConfig {
        applicationId = "io.github.mbaliga.fylz"
        // Hyle's :hyle module (wired below via includeBuild) declares minSdk = 31; a
        // dependency's minSdk can never be lower than its consumer's, so adopting the
        // shared design system means dropping Android 8.0-11 (API 26-30) support.
        minSdk = 31
        targetSdk = 35
        versionCode = 1_000_001
        versionName = "1.0.0-alpha01"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables.useSupportLibrary = true
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
        freeCompilerArgs += "-opt-in=androidx.compose.material3.ExperimentalMaterial3Api"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources.excludes += setOf(
            "/META-INF/AL2.0",
            "/META-INF/LGPL2.1",
            "/META-INF/LICENSE*",
            "/META-INF/NOTICE*",
            // jspecify and all three org.bouncycastle:*-jdk18on artifacts (pulled in
            // transitively by sshj for the SMB/SFTP remote provider) each ship an identical
            // multi-release-jar OSGi manifest at this exact path -- not needed at runtime by
            // a non-OSGi Android app, so it's excluded rather than arbitrarily picking one.
            "META-INF/versions/9/OSGI-INF/MANIFEST.MF",
        )
    }
}

// AGP 8.9 supports Kotlin 2.1. Several fast-moving libraries publish against newer Kotlin
// runtimes. Keep the runtime metadata aligned with the compiler until the project migrates to
// AGP 9.1+ as one deliberate toolchain change.
configurations.configureEach {
    resolutionStrategy.force(
        "org.jetbrains.kotlin:kotlin-stdlib:2.1.20",
        "org.jetbrains.kotlin:kotlin-stdlib-jdk7:2.1.20",
        "org.jetbrains.kotlin:kotlin-stdlib-jdk8:2.1.20",
    )
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2025.04.01")
    val media3Version = "1.10.1"

    implementation(composeBom)
    androidTestImplementation(composeBom)

    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    // BrowserViewModel (P1.10) -- the app's first ViewModel; brings ViewModel/SavedStateHandle and
    // the viewModel() composable factory, matching the lifecycle-runtime-compose version above.
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.documentfile:documentfile:1.0.1")
    implementation("androidx.work:work-runtime:2.11.2")
    implementation("androidx.media3:media3-exoplayer:$media3Version")
    implementation("androidx.media3:media3-ui:$media3Version")
    implementation("net.lingala.zip4j:zip4j:2.11.5")
    implementation("org.apache.commons:commons-compress:1.28.0")
    implementation("org.tukaani:xz:1.12")
    implementation("com.hierynomus:sshj:0.40.0")
    implementation("com.hierynomus:smbj:0.14.0")
    implementation("io.minio:minio:9.0.1")
    implementation("io.coil-kt.coil3:coil-compose:3.5.0")
    implementation("io.coil-kt.coil3:coil-gif:3.5.0")
    implementation("io.coil-kt.coil3:coil-svg:3.5.0")
    implementation("com.google.android.gms:play-services-mlkit-document-scanner:16.0.0")
    implementation("com.google.mlkit:text-recognition:16.0.1")
    implementation("com.google.mlkit:text-recognition-devanagari:16.0.1")
    implementation("com.squareup.okhttp3:okhttp:5.3.0")

    // fylz-core (M2.2): the generated uniffi bindings in core/ need JNA for the native calls
    // and kotlinx-coroutines for the suspend-fun bridge uniffi generates for async Rust fns.
    // JNA is dual-licensed Apache-2.0/LGPL-2.1; the Apache-2.0 option is the one that applies
    // here, per docs/agent/MASTER_PLAN.md section 2.2.
    implementation("net.java.dev.jna:jna:5.19.1@aar")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")

    // Hyle Design System, via the hyle-design-system submodule + includeBuild (see
    // settings.gradle.kts). Gradle's composite-build dependency substitution resolves
    // these to the :hyle and :crash-recovery projects, not a remote registry.
    implementation("dev.aarso:hyle:0.2.0")
    implementation("dev.aarso:crash-recovery:1.4.0")
    // The constellation's navigation and motion shell: the fonebrew spatial pattern (rooms
    // parked off the screen edges), the word-wheel rail and the Niagara-style edge scrubber.
    // Shared rather than local so Fylz and Foto Xplorr move identically — which is the whole
    // of the owner's "followed everywhere".
    implementation("dev.aarso:cell-shell:0.1.0")

    testImplementation("junit:junit:4.13.2")
    // Plain JVM unit tests run against the android.jar STUB, whose android.* / org.json.*
    // method bodies all throw "not mocked" RuntimeExceptions -- several tests construct real
    // org.json.JSONObject/JSONArray content (ModelPackManifestCodec, SignedModelCatalogVerifier,
    // OrganizationEngine's portable-metadata codec). org.json:json is the real, pure-Java
    // reference implementation of the same API; on the test classpath it takes precedence over
    // the stub and those calls behave for real, with zero test-code changes required.
    testImplementation("org.json:json:20260719")
    // PdfPagePlanPolicyTest constructs real android.net.Uri instances via Uri.parse(), which the
    // stub jar cannot provide a working implementation of (unlike org.json, there is no small
    // pure-Java substitute for Uri). Robolectric supplies real framework shadows for exactly this
    // class of test; only PdfPagePlanPolicyTest opts in via @RunWith(RobolectricTestRunner::class)
    // -- every other test class keeps running as a fast plain-JVM test.
    testImplementation("org.robolectric:robolectric:4.16.1")

    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.6.1")
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")

    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}

// M2.3: fylz-core native build (docs/agent/MASTER_PLAN.md sections 4.2 and 5, task M2.3).
// buildCoreDebug/buildCoreRelease cross-compile core/crates/fylz-ffi-android for arm64-v8a,
// armeabi-v7a and x86_64 via cargo-ndk, copy the resulting .so files into src/main/jniLibs, then
// regenerate the Kotlin bindings in src/main/java/io/github/mbaliga/fylz/core/ from one of the
// just-built libraries -- uniffi's --library bindgen mode statically parses ELF metadata (via
// `goblin`), so any one of the just-built target .so files works; no separate host-arch build
// is needed just for bindgen, confirmed by running it against a cross-compiled arm64-v8a .so
// from this x86_64 host.
val fylzCoreDir = rootProject.file("core")
val fylzCoreJniLibsDir = layout.projectDirectory.dir("src/main/jniLibs").asFile
val fylzCoreBindingsOutDir = layout.projectDirectory.dir("src/main/java").asFile

fun registerBuildCoreTask(taskName: String, cargoProfileArgs: List<String>) =
    tasks.register<Exec>(taskName) {
        group = "fylz-core"
        description = "Cross-compiles fylz-ffi-android and regenerates its Kotlin bindings."
        workingDir = fylzCoreDir
        environment("ANDROID_NDK_HOME", android.ndkDirectory.absolutePath)
        commandLine(
            listOf(
                "cargo", "ndk",
                "-t", "arm64-v8a", "-t", "armeabi-v7a", "-t", "x86_64",
                "-P", "31",
                "-o", fylzCoreJniLibsDir.absolutePath,
                "build", "--lib", "-p", "fylz-ffi-android",
            ) + cargoProfileArgs,
        )
        doLast {
            // uniffi-bindgen's --library mode needs the crate's (non-dynamic) symbol table to
            // find its embedded interface metadata -- confirmed by a real "No UniFFI metadata
            // found" failure bindgen-ing buildCoreRelease's own just-built arm64-v8a .so, which
            // `[profile.release] strip = true` (core/Cargo.toml) strips clean. The Kotlin
            // interface is identical across profiles and targets, so a cheap, ordinary
            // dev-profile HOST build -- entirely separate from whatever this task just
            // cross-compiled and possibly stripped above -- is what bindgen actually
            // introspects, exactly how M2.2 first proved this out by hand.
            exec {
                workingDir = fylzCoreDir
                commandLine("cargo", "build", "--lib", "-p", "fylz-ffi-android")
            }
            exec {
                workingDir = fylzCoreDir
                commandLine(
                    "cargo", "run", "--quiet", "-p", "fylz-ffi-android",
                    "--features", "uniffi-cli", "--bin", "uniffi-bindgen", "--",
                    "generate",
                    "--library", fylzCoreDir.resolve("target/debug/libfylz_ffi_android.so").absolutePath,
                    "--language", "kotlin",
                    "--out-dir", fylzCoreBindingsOutDir.absolutePath,
                    "--no-format",
                )
            }
        }
    }

val buildCoreDebug = registerBuildCoreTask("buildCoreDebug", listOf("--profile", "dev-android"))
val buildCoreRelease = registerBuildCoreTask("buildCoreRelease", listOf("--release"))

// 16 KB native page alignment (MASTER_PLAN.md section 2.3): the NDK r28+ toolchain already
// defaults to a 16 KB-aligned linker layout, but this task is the CI-checkable guard against a
// future regression (a dependency's build script forcing a different `-z max-page-size`, an NDK
// downgrade, and so on) rather than a check that should ever legitimately fail today.
val check16KbPageAlignment = tasks.register("check16KbPageAlignment") {
    group = "fylz-core"
    description = "Fails if any fylz-core .so's ELF LOAD segments aren't 16 KB (0x4000) aligned."
    doLast {
        val readelf = android.ndkDirectory.resolve("toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-readelf")
        val soFiles = fileTree(fylzCoreJniLibsDir) { include("**/*.so") }.files
        val misaligned = mutableListOf<String>()
        soFiles.forEach { so ->
            val output = providers.exec {
                commandLine(readelf.absolutePath, "-l", so.absolutePath)
            }.standardOutput.asText.get()
            output.lineSequence()
                .filter { it.trim().startsWith("LOAD") }
                .forEach { line ->
                    val align = line.trim().split(Regex("\\s+")).last().removePrefix("0x")
                    if (align.toLong(16) < 0x4000L) {
                        misaligned += "${so.name}: $line"
                    }
                }
        }
        if (misaligned.isNotEmpty()) {
            throw GradleException(
                "fylz-core native libraries are not 16 KB page-aligned:\n" + misaligned.joinToString("\n"),
            )
        }
    }
}

buildCoreDebug.configure { finalizedBy(check16KbPageAlignment) }
buildCoreRelease.configure { finalizedBy(check16KbPageAlignment) }

tasks.matching { it.name == "preDebugBuild" }.configureEach { dependsOn(buildCoreDebug) }
tasks.matching { it.name == "preReleaseBuild" }.configureEach { dependsOn(buildCoreRelease) }

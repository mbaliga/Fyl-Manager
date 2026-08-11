# WP-0.5 — Close the Kotlin lockstep gap

**Status: DONE, option (a) as recommended.**

Fylz moved from Kotlin **2.1.20 → 2.1.0**, matching `kotlin = "2.1.0"` in both
`hyle-design-system/gradle/libs.versions.toml` and `shared-libraries/gradle/libs.versions.toml`:

- root `build.gradle.kts`: `org.jetbrains.kotlin.android` and
  `org.jetbrains.kotlin.plugin.compose` both `2.1.20 → 2.1.0`
- `app/build.gradle.kts`: the three `kotlin-stdlib*` resolution forces follow to `2.1.0`

Full local verification passed on the downgrade: `:app:testDebugUnitTest :app:lintDebug
:app:assembleDebug` and the release list both build clean — no forward-compat luck required
anymore.

**Codification** (the part that outlives the change): `guard/ToolchainLockstepTest` reads the
versions where each build declares them and fails the build on drift, in both CI task lists:

- Kotlin: root plugins block == hyle catalog == shared catalog
- Compose compiler plugin == Kotlin (they ride together)
- AGP: root == hyle == shared (previously enforced only by a comment in
  `settings.gradle.kts`; Gradle rejects a composite with two AGP versions, so drift here broke
  every consumer at once — now it fails one unit test instead)
- the stdlib resolution forces == the compiler version

**Next:** nothing here. If the constellation ever bumps Kotlin, bump all three builds together;
the test says exactly which file disagrees.

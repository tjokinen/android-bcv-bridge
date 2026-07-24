# android-bcv-bridge

A small Gradle plugin that restores binary compatibility validation for Android library
modules where the
[Kotlin Binary Compatibility Validator](https://github.com/Kotlin/binary-compatibility-validator)
(BCV) silently registers no tasks: `com.android.library` modules built with AGP 9 built-in
Kotlin, and Kotlin Multiplatform modules using AGP's
`com.android.kotlin.multiplatform.library` plugin.

Status: experimental (v0.2.0), but verified end-to-end by committed TestKit functional tests
that run with the configuration cache enabled. They apply the plugin to a real
`com.android.library` module and to a real KMP module with an android target, confirm that the
dump tasks produce correct BCV `.api` dumps, that the check tasks pass when the dump matches,
and that they fail on a breaking change. This plugin is a stopgap until KGP handles Android
projects itself (KT-78025).

## The problem

Two Android setups fall through BCV's cracks, both without any error
([Kotlin/binary-compatibility-validator#312](https://github.com/Kotlin/binary-compatibility-validator/issues/312)):

- **AGP 9 built-in Kotlin.** AGP 9 compiles Kotlin itself, so you no longer apply
  `org.jetbrains.kotlin.android`. But BCV only registers its `apiDump` and `apiCheck` tasks
  when one of `kotlin-android`, `kotlin` or `kotlin-multiplatform` is applied, so it does
  nothing. Kotlin's built-in `abiValidation` doesn't fill the gap either, because its DSL
  isn't exposed on the Kotlin extension AGP provides for Android.
- **KMP with AGP's multiplatform library plugin.** Under `kotlin("multiplatform")` +
  `com.android.kotlin.multiplatform.library`, BCV's multiplatform support only configures
  android compilations named `release`, but AGP's KMP library plugin builds a single variant
  whose compilation is named `main`. BCV registers `jvmApiDump` etc. for the other targets and
  silently skips the android target.

## What this does

Registers BCV's own task types directly, fed by the module's compiled classes.

For `com.android.library` with built-in Kotlin:

- `<variant>ApiDump` generates or updates the committed `api/<module>.api`
- `<variant>ApiCheck` verifies the compiled API against it, and is wired into `check`

For `com.android.kotlin.multiplatform.library`:

- `androidApiDump` generates or updates the committed `api/android/<module>.api`, matching
  BCV's per-target layout (`api/jvm/<module>.api`, ...)
- `androidApiCheck` verifies the compiled API against it
- both are folded into BCV's aggregate `apiDump` and `apiCheck` tasks, so the usual
  `./gradlew apiDump` and `./gradlew check` cover the android target like every other target

## Usage

```kotlin
// settings.gradle.kts
pluginManagement {
    repositories { gradlePluginPortal(); google(); mavenCentral() }
}
```

Android library module (AGP 9 built-in Kotlin):

```kotlin
// <module>/build.gradle.kts
plugins {
    id("com.android.library")
    id("io.github.tjokinen.android-bcv-bridge") version "0.2.0"
}

androidBcvBridge {
    variant.set("release")              // optional, default "release"
    // apiFile defaults to api/<module>.api
}
```

Kotlin Multiplatform module with an android target:

```kotlin
// <module>/build.gradle.kts
plugins {
    id("io.github.tjokinen.android-bcv-bridge") version "0.2.0"
    id("com.android.kotlin.multiplatform.library")
    id("org.jetbrains.kotlinx.binary-compatibility-validator")
    kotlin("multiplatform")
}

// androidBcvBridge { apiFile ... }     // optional; variant is unused in this mode,
                                        // apiFile defaults to api/android/<module>.api
```

Workflow:
1. Run `./gradlew releaseApiDump` (or `./gradlew apiDump` for KMP) to write the api file, and
   commit it.
2. CI runs `check`, and the check task fails on any binary-incompatible public API change.

## How it works

BCV ships the task types `KotlinApiBuildTask` (dump) and `KotlinApiCompareTask` (compare).
This plugin registers them manually and points the build task at the outputs of the tasks that
hold the module's compiled classes: `compile<Variant>Kotlin` and
`compile<Variant>JavaWithJavac` under AGP built-in Kotlin, or `compileAndroidMain` under the
AGP KMP library plugin. No `abiValidation` DSL needed.

## Caveats

- The plugin uses BCV-internal types. `KotlinApiBuildTask` and `KotlinApiCompareTask` are not BCV public
  API and can change between releases. The plugin is pinned to and tested against BCV 0.18.1,
  so align your BCV version accordingly.
- Single variant only, for now. Under `com.android.library` the ABI is checked for one variant
  (default `release`), which is the variant consumers of a published library actually get. An
  Android library's public API can legitimately differ per variant though (`src/debug/java`,
  product flavors), which is exactly the complexity
  [KT-78025](https://youtrack.jetbrains.com/issue/KT-78025) calls out. A variant-specific API
  break outside the checked variant goes undetected. Multi-variant support (one committed dump
  per variant plus aggregate tasks) is a candidate for a future version. Under
  `com.android.kotlin.multiplatform.library` this is moot: AGP's KMP library plugin builds a
  single variant by design.
- With classic KMP (`com.android.library` + `kotlin("multiplatform")` + `androidTarget()`) the
  plugin deliberately does nothing: there the android compilations are named per variant and
  BCV's own multiplatform support handles them.
- This plugin becomes unnecessary once Android Gradle projects are supported in the Kotlin
  Gradle plugin's built-in ABI validation, and its two paths retire on different timelines:
  - The KMP path: KGP's built-in `abiValidation` supports
    `com.android.kotlin.multiplatform.library` from Kotlin 2.4.20
    ([KT-85950](https://youtrack.jetbrains.com/issue/KT-85950), fixed in 2.4.20-Beta2). Once
    on 2.4.20+, KMP projects can migrate from the BCV plugin to KGP's built-in validation and
    drop this bridge. The BCV plugin itself is not getting the fix; the gap is also reported
    as [Kotlin/binary-compatibility-validator#315](https://github.com/Kotlin/binary-compatibility-validator/issues/315).
  - The `com.android.library` built-in Kotlin path: still unsupported upstream, tracked in
    [KT-78025](https://youtrack.jetbrains.com/issue/KT-78025) (open and unscheduled at the
    time of writing) under the broader stabilization umbrella
    [KT-71172](https://youtrack.jetbrains.com/issue/KT-71172). Watch KT-78025 to know when to
    retire this path.
- Relies on task naming. It uses AGP's stable `compile<Variant>Kotlin` and
  `compile<Variant>JavaWithJavac` task names rather than AGP's `ScopedArtifacts` API. Simpler
  and fewer moving parts; the sturdier ScopedArtifacts wiring is a possible future enhancement.
- Configuration cache is supported. The functional test exercises all tasks with the
  configuration cache enabled.

## Prior art

The approach is adapted from the AGP 9 BCV workarounds in
[square/okhttp#9375](https://github.com/square/okhttp/pull/9375) (compile-task-output style)
and [elastic/apm-agent-android#757](https://github.com/elastic/apm-agent-android/pull/757)
(ScopedArtifacts style). This plugin packages the simpler approach so projects don't have to
hand-roll it.

## Developed against

AGP 9.2.0 (task naming also verified on 9.3.0), Gradle 9.6.0, Kotlin 2.3.x (built-in Kotlin
path) and 2.4.x (KMP path), BCV 0.18.1.

## License

[Apache 2.0](LICENSE).

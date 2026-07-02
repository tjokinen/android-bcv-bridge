# android-bcv-bridge

A small Gradle plugin that restores binary compatibility validation for Android library
modules built with AGP 9 built-in Kotlin, where the
[Kotlin Binary Compatibility Validator](https://github.com/Kotlin/binary-compatibility-validator)
(BCV) silently registers no tasks.

Status: experimental (v0.1.0), but verified end-to-end by a committed TestKit functional test
that runs with the configuration cache enabled. The test applies the plugin to a real
`com.android.library` module, confirms that `releaseApiDump` produces a correct BCV `.api`
dump, that `releaseApiCheck` passes when the dump matches, and that it fails on a breaking
change. This plugin is a stopgap until KGP handles Android projects itself (KT-78025).

## The problem

AGP 9 compiles Kotlin itself ("built-in Kotlin"), so you no longer apply
`org.jetbrains.kotlin.android`. But BCV only registers its `apiDump` and `apiCheck` tasks when
one of `kotlin-android`, `kotlin` or `kotlin-multiplatform` is applied. On AGP 9 built-in
Kotlin it therefore does nothing, without any error. Kotlin's built-in `abiValidation` doesn't
fill the gap either, because its DSL isn't exposed on the Kotlin extension AGP provides for
Android. See
[Kotlin/binary-compatibility-validator#312](https://github.com/Kotlin/binary-compatibility-validator/issues/312).

## What this does

Keeps AGP 9 built-in Kotlin and registers BCV's own task types directly, fed by the module's
compiled classes:

- `<variant>ApiDump` generates or updates the committed `api/<module>.api`
- `<variant>ApiCheck` verifies the compiled API against it, and is wired into `check`

## Usage

```kotlin
// settings.gradle.kts
pluginManagement {
    repositories { gradlePluginPortal(); google(); mavenCentral() }
}

// <module>/build.gradle.kts
plugins {
    id("com.android.library")
    id("io.github.tjokinen.android-bcv-bridge") version "0.1.0"
}

androidBcvBridge {
    variant.set("release")              // optional, default "release"
    // apiFile defaults to api/<module>.api
}
```

Workflow:
1. Run `./gradlew releaseApiDump` to write `api/<module>.api`, and commit that file.
2. CI runs `check`, and `releaseApiCheck` fails on any binary-incompatible public API change.

## How it works

BCV ships the task types `KotlinApiBuildTask` (dump) and `KotlinApiCompareTask` (compare).
This plugin registers them manually and points the build task at the outputs of
`compile<Variant>Kotlin` and `compile<Variant>JavaWithJavac`, which hold the module's compiled
classes under AGP built-in Kotlin. No standalone Kotlin plugin, no `abiValidation` DSL.

## Caveats

- The plugin uses BCV-internal types. `KotlinApiBuildTask` and `KotlinApiCompareTask` are not BCV public
  API and can change between releases. The plugin is pinned to and tested against BCV 0.18.1,
  so align your BCV version accordingly.
- Single variant only, for now. The ABI is checked for one variant (default `release`), which
  is the variant consumers of a published library actually get. An Android library's public
  API can legitimately differ per variant though (`src/debug/java`, product flavors), which is
  exactly the complexity [KT-78025](https://youtrack.jetbrains.com/issue/KT-78025) calls out.
  A variant-specific API break outside the checked variant goes undetected. Multi-variant
  support (one committed dump per variant plus aggregate tasks) is a candidate for a future
  version.
- This plugin becomes unnecessary once Android Gradle projects are
  supported in the Kotlin Gradle plugin's built-in ABI validation, tracked in
  [KT-78025](https://youtrack.jetbrains.com/issue/KT-78025) (open and unscheduled at the time
  of writing) under the broader stabilization umbrella
  [KT-71172](https://youtrack.jetbrains.com/issue/KT-71172). Watch KT-78025 to know when to
  retire this plugin.
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

AGP 9.2.0, Gradle 9.6.0, Kotlin 2.3.x, BCV 0.18.1.

## License

[Apache 2.0](LICENSE).

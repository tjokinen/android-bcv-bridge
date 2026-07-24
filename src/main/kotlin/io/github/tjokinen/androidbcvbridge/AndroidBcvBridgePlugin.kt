package io.github.tjokinen.androidbcvbridge

import kotlinx.validation.KotlinApiBuildTask
import kotlinx.validation.KotlinApiCompareTask
import org.gradle.api.Action
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.Task
import org.gradle.kotlin.dsl.named
import org.gradle.kotlin.dsl.register
import org.gradle.language.base.plugins.LifecycleBasePlugin

/**
 * Registers Kotlin Binary Compatibility Validator (BCV) API dump and check tasks for Android
 * library modules where BCV registers none of its own.
 *
 * Two Android setups are covered, both gaps in BCV
 * (Kotlin/binary-compatibility-validator#312):
 *
 * 1. `com.android.library` with AGP 9 built-in Kotlin. BCV only creates its tasks when the
 *    standalone `kotlin-android`, `kotlin` or `kotlin-multiplatform` plugin is applied, so
 *    under built-in Kotlin it silently does nothing. For the configured variant (default
 *    `release`) this plugin creates `<variant>ApiDump` and `<variant>ApiCheck`, fed by the
 *    outputs of `compile<Variant>Kotlin` and `compile<Variant>JavaWithJavac`.
 *
 * 2. `com.android.kotlin.multiplatform.library` with `kotlin("multiplatform")`. BCV's
 *    multiplatform support only configures androidJvm compilations named `release`, but AGP's
 *    KMP library plugin builds a single variant whose compilation is named `main`, so the
 *    android target is silently skipped while the other targets get their tasks. This plugin
 *    creates `androidApiDump` and `androidApiCheck`, fed by the output of
 *    `compileAndroidMain`, and folds them into BCV's aggregate `apiDump` and `apiCheck`.
 *
 * Wiring by AGP's stable compile-task names is simpler than going through AGP's
 * `ScopedArtifacts` API, and the task naming is the only assumption made.
 *
 * Note that [KotlinApiBuildTask] and [KotlinApiCompareTask] are BCV-internal types rather than
 * public API, so this plugin is pinned to a tested BCV version. It is a stopgap until Android
 * support lands in KGP's built-in ABI validation, tracked in KT-78025.
 */
class AndroidBcvBridgePlugin : Plugin<Project> {

    override fun apply(project: Project) {
        // Android library modules built with AGP 9 built-in Kotlin.
        project.pluginManager.withPlugin("com.android.library") {
            val ext = createExtension(project)
            ext.apiFile.convention(
                project.layout.projectDirectory.file("api/${project.name}.api"),
            )

            // Defer until AGP has registered the variant's compile tasks.
            project.afterEvaluate(Action {
                if (project.pluginManager.hasPlugin("org.jetbrains.kotlin.multiplatform")) {
                    // Classic KMP androidTarget(): its compilations are named per variant
                    // (`release`), which BCV's own multiplatform support handles. Adding our
                    // tasks on top would duplicate or break the build, so stand down.
                    project.logger.info(
                        "android-bcv-bridge: kotlin-multiplatform is applied alongside " +
                            "com.android.library; BCV handles this setup itself, doing nothing.",
                    )
                } else {
                    wireBuiltInKotlin(project, ext)
                }
            })
        }

        // KMP modules using AGP's multiplatform library plugin. There is exactly one variant,
        // whose compilation is named `main`, so the extension's `variant` property is unused.
        project.pluginManager.withPlugin("com.android.kotlin.multiplatform.library") {
            val ext = createExtension(project)
            // Match BCV's multi-target layout, which puts each jvm-family dump in its own
            // directory (e.g. api/jvm/<module>.api for the jvm target).
            ext.apiFile.convention(
                project.layout.projectDirectory.file("api/android/${project.name}.api"),
            )

            // Defer until the android target's compile tasks exist.
            project.afterEvaluate(Action { wireMultiplatform(project, ext) })
        }
    }

    private fun createExtension(project: Project): AndroidBcvBridgeExtension {
        val ext = project.extensions.create(
            "androidBcvBridge",
            AndroidBcvBridgeExtension::class.java,
        )
        ext.variant.convention("release")
        return ext
    }

    private fun wireBuiltInKotlin(project: Project, ext: AndroidBcvBridgeExtension) {
        val variant = ext.variant.get()
        val capitalized = variant.replaceFirstChar { it.uppercase() }

        // Under AGP built-in Kotlin the module's compiled classes come from these tasks.
        val kotlinClasses = project.tasks.named("compile${capitalized}Kotlin").map { it.outputs.files }
        val javaClasses = project.tasks.named("compile${capitalized}JavaWithJavac").map { it.outputs.files }

        // 1. Build the API dump from the compiled classes using BCV's own build task.
        val apiBuild = project.tasks.register<KotlinApiBuildTask>("${variant}ApiBuild") {
            inputClassesDirs.from(kotlinClasses)
            inputClassesDirs.from(javaClasses)
            outputApiFile.set(project.layout.buildDirectory.file("bcv/${project.name}.api"))
        }

        // 2. Check the built dump against the committed file using BCV's own compare task.
        val apiCheck = project.tasks.register<KotlinApiCompareTask>("${variant}ApiCheck") {
            projectApiFile.set(ext.apiFile)
            generatedApiFile.set(apiBuild.flatMap { it.outputApiFile })
        }

        // 3. Copy the freshly built API over the committed file, like BCV's own apiDump.
        project.tasks.register<ApiDumpTask>("${variant}ApiDump") {
            group = "verification"
            description = "Updates the committed BCV API dump."
            generatedApiFile.set(apiBuild.flatMap { it.outputApiFile })
            committedApiFile.set(ext.apiFile)
        }

        // Gate the build on the API check.
        project.tasks.named<Task>(LifecycleBasePlugin.CHECK_TASK_NAME) { dependsOn(apiCheck) }
    }

    private fun wireMultiplatform(project: Project, ext: AndroidBcvBridgeExtension) {
        // AGP's KMP library plugin builds a single variant; the android target's compiled
        // classes come from its `main` compilation's compile task.
        val classes = project.tasks.named("compileAndroidMain").map { it.outputs.files }

        // 1. Build the API dump from the compiled classes using BCV's own build task.
        val apiBuild = project.tasks.register<KotlinApiBuildTask>("androidApiBuild") {
            inputClassesDirs.from(classes)
            outputApiFile.set(project.layout.buildDirectory.file("bcv/android/${project.name}.api"))
        }

        // 2. Check the built dump against the committed file using BCV's own compare task.
        val apiCheck = project.tasks.register<KotlinApiCompareTask>("androidApiCheck") {
            projectApiFile.set(ext.apiFile)
            generatedApiFile.set(apiBuild.flatMap { it.outputApiFile })
        }

        // 3. Copy the freshly built API over the committed file, like BCV's own apiDump.
        val apiDump = project.tasks.register<ApiDumpTask>("androidApiDump") {
            group = "verification"
            description = "Updates the committed BCV API dump for the android target."
            generatedApiFile.set(apiBuild.flatMap { it.outputApiFile })
            committedApiFile.set(ext.apiFile)
        }

        // Fold the android tasks into BCV's aggregate apiDump/apiCheck when the BCV plugin is
        // applied (it is what registers jvmApiDump etc. for the other targets), so the plain
        // `apiDump` and `apiCheck` invocations cover the android target too. BCV already wires
        // its aggregate apiCheck into `check`; without BCV, gate `check` directly.
        val taskNames = project.tasks.names
        if ("apiDump" in taskNames) {
            project.tasks.named<Task>("apiDump") { dependsOn(apiDump) }
        }
        if ("apiCheck" in taskNames) {
            project.tasks.named<Task>("apiCheck") { dependsOn(apiCheck) }
        } else {
            project.tasks.named<Task>(LifecycleBasePlugin.CHECK_TASK_NAME) { dependsOn(apiCheck) }
        }
    }
}

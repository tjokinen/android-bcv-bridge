package com.tjokinen.androidbcvbridge

import kotlinx.validation.KotlinApiBuildTask
import kotlinx.validation.KotlinApiCompareTask
import org.gradle.api.Action
import org.gradle.api.DefaultTask
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.Task
import org.gradle.kotlin.dsl.named
import org.gradle.kotlin.dsl.register
import org.gradle.language.base.plugins.LifecycleBasePlugin

/**
 * Wires Kotlin Binary Compatibility Validator (BCV) API dump/check tasks for Android library
 * modules built with AGP's built-in Kotlin.
 *
 * Why this exists: BCV only registers its `apiDump`/`apiCheck` tasks when the standalone
 * `kotlin-android` (or `kotlin`/`kotlin-multiplatform`) plugin is applied. Under AGP 9
 * built-in Kotlin that plugin is not applied, so BCV silently does nothing
 * (Kotlin/binary-compatibility-validator#312). This plugin keeps built-in Kotlin and instead
 * registers BCV's own task types directly, fed by the module's compiled classes.
 *
 * Approach: feed BCV's [KotlinApiBuildTask] the outputs of the variant's `compile<Variant>Kotlin`
 * and `compile<Variant>JavaWithJavac` tasks (the okhttp#9375 style). Simpler and fewer moving
 * parts than AGP's `ScopedArtifacts` API; the only assumption is AGP's stable compile-task
 * naming convention.
 *
 * Tasks created (for the configured variant, default `release`):
 *  - `<variant>ApiDump`  — writes/updates the committed `api/<module>.api`
 *  - `<variant>ApiCheck` — verifies the compiled API against it; wired into `check`
 *
 * Caveat: `KotlinApiBuildTask`/`KotlinApiCompareTask` are BCV-internal types, not public API,
 * so this is pinned to a tested BCV version and is a stopgap until Android support lands in
 * KGP's built-in ABI validation (KT-71172).
 */
class AndroidBcvBridgePlugin : Plugin<Project> {

    override fun apply(project: Project) {
        // Only act on Android library modules.
        project.pluginManager.withPlugin("com.android.library") {
            val ext = project.extensions.create(
                "androidBcvBridge",
                AndroidBcvBridgeExtension::class.java,
            )
            ext.variant.convention("release")
            ext.apiFile.convention(
                project.layout.projectDirectory.file("api/${project.name}.api"),
            )

            // Defer until AGP has registered the variant's compile tasks.
            project.afterEvaluate(Action { wire(project, ext) })
        }
    }

    private fun wire(project: Project, ext: AndroidBcvBridgeExtension) {
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

        // 3. Dump = copy the freshly built API over the committed file (BCV's `apiDump` analogue).
        project.tasks.register<DefaultTask>("${variant}ApiDump") {
            group = "verification"
            description = "Updates the committed API dump (${ext.apiFile.get().asFile.name})."
            dependsOn(apiBuild)
            doLast {
                val generated = apiBuild.get().outputApiFile.get().asFile
                val committed = ext.apiFile.get().asFile
                committed.parentFile?.mkdirs()
                generated.copyTo(committed, overwrite = true)
            }
        }

        // Gate the build on the API check.
        project.tasks.named<Task>(LifecycleBasePlugin.CHECK_TASK_NAME) { dependsOn(apiCheck) }
    }
}

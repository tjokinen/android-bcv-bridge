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
 * library modules built with AGP's built-in Kotlin.
 *
 * BCV only creates its `apiDump` and `apiCheck` tasks when the standalone `kotlin-android`,
 * `kotlin` or `kotlin-multiplatform` plugin is applied. Under AGP 9 built-in Kotlin none of
 * those is applied, so BCV silently does nothing. See
 * Kotlin/binary-compatibility-validator#312. This plugin keeps built-in Kotlin and registers
 * BCV's own task types directly, fed by the module's compiled classes.
 *
 * It feeds [KotlinApiBuildTask] the outputs of the variant's `compile<Variant>Kotlin` and
 * `compile<Variant>JavaWithJavac` tasks. That is simpler than going through AGP's
 * `ScopedArtifacts` API, and the only assumption it makes is AGP's stable compile-task naming.
 *
 * For the configured variant (default `release`) it creates `<variant>ApiDump`, which writes
 * the committed `api/<module>.api`, and `<variant>ApiCheck`, which verifies the compiled API
 * against that file and is wired into `check`.
 *
 * Note that [KotlinApiBuildTask] and [KotlinApiCompareTask] are BCV-internal types rather than
 * public API, so this plugin is pinned to a tested BCV version. It is a stopgap until Android
 * support lands in KGP's built-in ABI validation, tracked in KT-78025.
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
}

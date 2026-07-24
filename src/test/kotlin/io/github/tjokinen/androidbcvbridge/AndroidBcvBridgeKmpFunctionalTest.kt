package io.github.tjokinen.androidbcvbridge

import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * End-to-end test for the Kotlin Multiplatform setup: `com.android.kotlin.multiplatform.library`
 * together with `kotlin("multiplatform")` and the BCV plugin, mirroring how a real KMP library
 * (e.g. JuulLabs/kable) is built. BCV registers `jvmApiDump` for the jvm target but skips the
 * android target (its multiplatform support only handles androidJvm compilations named
 * `release`, and AGP's KMP library plugin uses `main`); the bridge fills that gap with
 * `androidApiDump`/`androidApiCheck` and folds them into BCV's aggregate tasks.
 *
 * Runs with the configuration cache enabled. Requires an Android SDK (ANDROID_HOME) and
 * downloads AGP/KGP, so it is a slow integration test.
 */
class AndroidBcvBridgeKmpFunctionalTest {

    @get:Rule
    val projectDir = TemporaryFolder()

    private val androidApiFile get() = File(projectDir.root, "api/android/sample-lib.api")

    // The plugin is consumed by id/version from a local repo published by the build (see
    // build.gradle.kts) rather than via withPluginClasspath(), so that it shares one classpath
    // scope with the BCV and Kotlin plugins, exactly like a real consumer build.
    private val pluginRepo = File(System.getProperty("functionalTestRepo")).toURI()
    private val pluginVersion = System.getProperty("pluginVersion")

    private fun writeProject() {
        projectDir.newFile("settings.gradle.kts").writeText(
            """
            pluginManagement {
                repositories { maven(url = uri("$pluginRepo")); google(); mavenCentral(); gradlePluginPortal() }
            }
            dependencyResolutionManagement {
                repositories { google(); mavenCentral() }
            }
            rootProject.name = "sample-lib"
            """.trimIndent(),
        )

        projectDir.newFile("gradle.properties").writeText("android.useAndroidX=true\n")

        System.getenv("ANDROID_HOME")?.let {
            projectDir.newFile("local.properties").writeText("sdk.dir=$it\n")
        }

        // The bridge is applied first, before the AGP KMP plugin, like Kable does.
        projectDir.newFile("build.gradle.kts").writeText(
            """
            plugins {
                id("io.github.tjokinen.android-bcv-bridge") version "$pluginVersion"
                id("com.android.kotlin.multiplatform.library") version "9.2.0"
                id("org.jetbrains.kotlinx.binary-compatibility-validator") version "0.18.1"
                kotlin("multiplatform") version "2.4.10"
            }
            kotlin {
                jvm()
                android {
                    namespace = "com.example.samplelib"
                    compileSdk = 36
                    minSdk = 26
                }
            }
            """.trimIndent(),
        )

        val commonDir = projectDir.newFolder("src", "commonMain", "kotlin", "com", "example", "samplelib")
        File(commonDir, "Api.kt").writeText(
            """
            package com.example.samplelib

            class Api {
                fun greet(name: String): String = "hi " + name
            }
            """.trimIndent(),
        )

        val androidDir = projectDir.newFolder("src", "androidMain", "kotlin", "com", "example", "samplelib")
        File(androidDir, "AndroidApi.kt").writeText(
            """
            package com.example.samplelib

            class AndroidApi {
                fun androidOnly(): Int = 1
            }
            """.trimIndent(),
        )
    }

    private fun runner(vararg args: String) =
        GradleRunner.create()
            .withProjectDir(projectDir.root)
            .withArguments(*args, "--configuration-cache", "--stacktrace")
            .forwardOutput()

    @Test
    fun `aggregate apiDump produces the android dump and apiCheck passes against it`() {
        writeProject()

        // The plain `apiDump` aggregate (what users run) must now cover the android target.
        val dump = runner("apiDump").build()
        assertEquals(TaskOutcome.SUCCESS, dump.task(":androidApiDump")?.outcome)
        assertEquals(TaskOutcome.SUCCESS, dump.task(":jvmApiDump")?.outcome)
        assertTrue("android api file should exist", androidApiFile.exists())
        val dumpText = androidApiFile.readText()
        assertTrue(
            "android api file should contain the common class",
            dumpText.contains("com/example/samplelib/Api"),
        )
        assertTrue(
            "android api file should contain the android-only class",
            dumpText.contains("com/example/samplelib/AndroidApi"),
        )

        val check = runner("apiCheck").build()
        assertEquals(TaskOutcome.SUCCESS, check.task(":androidApiCheck")?.outcome)
    }

    @Test
    fun `androidApiCheck fails when the public api diverges from the committed dump`() {
        writeProject()
        runner("androidApiDump").build()

        // Drop the android-only method from the committed dump to simulate a breaking change.
        androidApiFile.writeText(
            androidApiFile.readText().lineSequence().filterNot { it.contains("androidOnly") }.joinToString("\n"),
        )

        val result = runner("androidApiCheck").buildAndFail()
        assertEquals(TaskOutcome.FAILED, result.task(":androidApiCheck")?.outcome)
    }
}

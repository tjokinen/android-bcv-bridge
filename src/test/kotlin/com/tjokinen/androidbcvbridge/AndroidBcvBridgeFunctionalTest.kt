package com.tjokinen.androidbcvbridge

import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * End-to-end test: applies the plugin to a real `com.android.library` project (AGP built-in
 * Kotlin, no standalone Kotlin plugin) and drives the generated tasks. Runs with the
 * configuration cache ON, which also verifies ApiDumpTask is CC-safe.
 *
 * Requires an Android SDK (ANDROID_HOME) and downloads AGP, so it's a slow integration test.
 */
class AndroidBcvBridgeFunctionalTest {

    @get:Rule
    val projectDir = TemporaryFolder()

    private val apiFile get() = File(projectDir.root, "api/sample-lib.api")

    private fun writeProject(greetSignature: String = "fun greet(name: String): String = \"hi \$name\"") {
        projectDir.newFile("settings.gradle.kts").writeText(
            """
            pluginManagement {
                repositories { google(); mavenCentral(); gradlePluginPortal() }
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

        projectDir.newFile("build.gradle.kts").writeText(
            """
            plugins {
                id("com.android.library") version "9.2.0"
                id("com.tjokinen.android-bcv-bridge")
            }
            android {
                namespace = "com.example.samplelib"
                compileSdk { version = release(36) }
                defaultConfig { minSdk = 26 }
            }
            """.trimIndent(),
        )

        val srcDir = projectDir.newFolder("src", "main", "java", "com", "example", "samplelib")
        File(srcDir, "Api.kt").writeText(
            """
            package com.example.samplelib

            class Api {
                $greetSignature
            }
            """.trimIndent(),
        )
    }

    private fun runner(vararg args: String) =
        GradleRunner.create()
            .withProjectDir(projectDir.root)
            .withPluginClasspath()
            .withArguments(*args, "--configuration-cache", "--stacktrace")
            .forwardOutput()

    @Test
    fun `dump generates the api file and check passes against it`() {
        writeProject()

        val dump = runner("releaseApiDump").build()
        assertEquals(TaskOutcome.SUCCESS, dump.task(":releaseApiDump")?.outcome)
        assertTrue("api file should exist", apiFile.exists())
        assertTrue(
            "api file should contain the public class",
            apiFile.readText().contains("com/example/samplelib/Api"),
        )

        val check = runner("releaseApiCheck").build()
        assertEquals(TaskOutcome.SUCCESS, check.task(":releaseApiCheck")?.outcome)
    }

    @Test
    fun `check fails when the public api diverges from the committed dump`() {
        writeProject()
        runner("releaseApiDump").build()

        // Drop the public method from the committed dump to simulate a breaking change.
        apiFile.writeText(
            apiFile.readText().lineSequence().filterNot { it.contains("greet") }.joinToString("\n"),
        )

        val result = runner("releaseApiCheck").buildAndFail()
        assertEquals(TaskOutcome.FAILED, result.task(":releaseApiCheck")?.outcome)
    }
}

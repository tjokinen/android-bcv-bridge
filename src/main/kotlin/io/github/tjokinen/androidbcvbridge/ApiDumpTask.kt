package io.github.tjokinen.androidbcvbridge

import org.gradle.api.DefaultTask
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.work.DisableCachingByDefault

/**
 * Copies a freshly built BCV API dump over the committed file, mirroring BCV's own `apiDump`.
 *
 * Input and output are managed properties so the task stays configuration-cache safe. The
 * action reads its own properties only and never touches the project or the extension.
 */
@DisableCachingByDefault(because = "Trivial copy of the generated dump over the committed file")
abstract class ApiDumpTask : DefaultTask() {

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val generatedApiFile: RegularFileProperty

    @get:OutputFile
    abstract val committedApiFile: RegularFileProperty

    @TaskAction
    fun dump() {
        val generated = generatedApiFile.get().asFile
        val committed = committedApiFile.get().asFile
        committed.parentFile?.mkdirs()
        generated.copyTo(committed, overwrite = true)
    }
}

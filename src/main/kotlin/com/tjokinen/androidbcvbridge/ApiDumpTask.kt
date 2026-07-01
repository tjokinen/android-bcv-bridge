package com.tjokinen.androidbcvbridge

import org.gradle.api.DefaultTask
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.TaskAction

/**
 * Copies a freshly built BCV API dump over the committed file (BCV's `apiDump` analogue).
 *
 * Declares its input/output as managed properties so it is configuration-cache safe: the action
 * touches only `this`'s properties, never the project or extension.
 */
abstract class ApiDumpTask : DefaultTask() {

    @get:InputFile
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

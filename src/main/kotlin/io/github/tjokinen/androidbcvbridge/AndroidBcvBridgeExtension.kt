package io.github.tjokinen.androidbcvbridge

import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property

/** Configuration for the `androidBcvBridge { }` block. */
abstract class AndroidBcvBridgeExtension {
    /** The Android variant whose compiled classes the public API is extracted from. Defaults to `release`. */
    abstract val variant: Property<String>

    /**
     * The committed API dump that the check task verifies against and the dump task writes to.
     * Defaults to `api/<module>.api`.
     */
    abstract val apiFile: RegularFileProperty
}

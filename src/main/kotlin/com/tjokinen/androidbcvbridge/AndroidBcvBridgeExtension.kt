package com.tjokinen.androidbcvbridge

import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property

/** Configuration for the `androidBcvBridge { }` block. */
abstract class AndroidBcvBridgeExtension {
    /** Which Android variant's compiled classes to extract the public API from. Default: `release`. */
    abstract val variant: Property<String>

    /** The committed API dump checked against (and written to by the dump task). Default: `api/<module>.api`. */
    abstract val apiFile: RegularFileProperty
}

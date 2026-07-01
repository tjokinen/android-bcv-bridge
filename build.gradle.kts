plugins {
    `kotlin-dsl`
    `maven-publish`
    alias(libs.plugins.plugin.publish)
}

group = "io.github.tjokinen"
version = "0.1.0"

dependencies {
    // BCV's task types, bundled so consumers don't apply the BCV plugin themselves.
    implementation(libs.bcv)
    // BCV reads @Metadata from .class files to determine Kotlin visibility.
    implementation(libs.kotlin.metadata)
    // BCV's API build worker needs ASM at runtime (not declared in its POM).
    implementation(libs.asm)
    implementation(libs.asm.tree)

    testImplementation("junit:junit:4.13.2")
    testImplementation(gradleTestKit())
}

tasks.named<Test>("test") {
    useJUnit()
    // The functional test runs a full AGP build; give it room and the SDK location.
    System.getenv("ANDROID_HOME")?.let { environment("ANDROID_HOME", it) }
}

java {
    toolchain { languageVersion = JavaLanguageVersion.of(17) }
}

gradlePlugin {
    website = "https://github.com/tjokinen/android-bcv-bridge"
    vcsUrl = "https://github.com/tjokinen/android-bcv-bridge"
    plugins {
        create("androidBcvBridge") {
            id = "io.github.tjokinen.android-bcv-bridge"
            implementationClass = "io.github.tjokinen.androidbcvbridge.AndroidBcvBridgePlugin"
            displayName = "Android BCV Bridge"
            description =
                "Wires Kotlin Binary Compatibility Validator API dump/check tasks for Android " +
                "library modules built with AGP built-in Kotlin, where BCV does not register its " +
                "own tasks (see Kotlin/binary-compatibility-validator#312)."
            tags = listOf("android", "kotlin", "binary-compatibility", "abi", "agp", "api")
        }
    }
}

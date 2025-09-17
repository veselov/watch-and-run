plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.kotlinxSerialization)
}

group = "me.user"
version = "1.0-SNAPSHOT"

repositories {
    mavenCentral()
}

kotlin {
    val hostOs = System.getProperty("os.name")

    // Always declare both Linux targets to produce both x86_64 and aarch64 binaries
    val linuxX64Target = linuxX64("linuxX64")
    val linuxArm64Target = linuxArm64("linuxArm64")

    // Keep host-specific targets for other OSes (optional but harmless)
    if (hostOs == "Mac OS X") {
        // Build both macOS variants when possible
        macosX64("macosX64")
        macosArm64("macosArm64")
    } else if (hostOs.startsWith("Windows")) {
        mingwX64("mingwX64")
    }

    listOf(linuxX64Target, linuxArm64Target).forEach { target ->
        target.binaries {
            executable {
                // On Linux, avoid unnecessary DT_NEEDED deps (e.g., libcrypt.so.1)
                linkerOpts("-Wl,--as-needed")
                entryPoint = "main"
                baseName = "WatchAndRun"
            }
        }
    }

    sourceSets {
        val commonMain = sourceSets.getByName("commonMain")
        // Wire nativeMain to commonMain to keep existing sources under nativeMain
        val nativeMain = sourceSets.maybeCreate("nativeMain")
        val linuxX64Main = sourceSets.getByName("linuxX64Main")
        val linuxArm64Main = sourceSets.getByName("linuxArm64Main")

        nativeMain.dependencies {
            implementation(libs.kotlinxSerializationJson)
        }
        // Keep existing sources under nativeMain; link them to platform source sets
        linuxX64Main.dependsOn(nativeMain)
        linuxArm64Main.dependsOn(nativeMain)
    }
}


// Disable execution of native tests because the test runner may require unavailable system libs (e.g., libcrypt.so.1)
// and we currently have no test sources. This ensures `./gradlew build` works across environments.
tasks.withType<org.jetbrains.kotlin.gradle.targets.native.tasks.KotlinNativeTest>().configureEach {
    enabled = false
}

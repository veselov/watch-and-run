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
    val isArm64 = System.getProperty("os.arch") == "aarch64"
    val isMingwX64 = hostOs.startsWith("Windows")
    val nativeTarget = when {
        hostOs == "Mac OS X" && isArm64 -> macosArm64("native")
        hostOs == "Mac OS X" && !isArm64 -> macosX64("native")
        hostOs == "Linux" && isArm64 -> linuxArm64("native")
        hostOs == "Linux" && !isArm64 -> linuxX64("native")
        isMingwX64 -> mingwX64("native")
        else -> throw GradleException("Host OS is not supported in Kotlin/Native.")
    }

    nativeTarget.apply {
        binaries {
            executable {
                // On Linux, prefer linking against libxcrypt (libcrypt.so.2) when available to avoid old libcrypt.so.1 runtime dep
                if (hostOs == "Linux") {
                    linkerOpts("-Wl,--as-needed")
                }
                entryPoint = "main"
                baseName = "WatchAndRun"
                // Note: static linking may fail on some hosts; keep dynamic by default.
            }
        }
    }

    sourceSets {
        nativeMain.dependencies {
            implementation(libs.kotlinxSerializationJson)
        }
    }
}


// Disable execution of native tests because the test runner may require unavailable system libs (e.g., libcrypt.so.1)
// and we currently have no test sources. This ensures `./gradlew build` works across environments.
tasks.withType<org.jetbrains.kotlin.gradle.targets.native.tasks.KotlinNativeTest>().configureEach {
    enabled = false
}

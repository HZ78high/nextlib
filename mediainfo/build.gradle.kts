import com.android.build.gradle.internal.tasks.factory.dependsOn

plugins {
    alias(libs.plugins.androidLibrary)
    alias(libs.plugins.kotlinAndroid)
    id("maven-publish")
}

android {
    namespace = "io.github.anilbeesetti.nextlib.mediainfo"

    compileSdk = 35

    ndkVersion = libs.versions.androidNdk.get()

    defaultConfig {

        minSdk = 21
        consumerProguardFiles("consumer-rules.pro")
        externalNativeBuild {
            cmake {
                cppFlags("")
            }
        }

        ndk {
            abiFilters += listOf("x86", "x86_64", "armeabi-v7a", "arm64-v8a")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        /// Set JVM target to 17
        jvmTarget = JavaVersion.VERSION_17.toString()
    }

    externalNativeBuild {
        cmake {
            path("src/main/cpp/CMakeLists.txt")
            version = libs.versions.cmake.get()
        }
    }
    publishing {
        singleVariant("release") {
            withSourcesJar()
        }
    }
}

// Gradle task to setup ffmpeg
val ffmpegSetup by tasks.registering(Exec::class) {
    onlyIf{
        !file("../ffmpeg/output").exists()
    }
    workingDir = file("../ffmpeg")
    doFirst {
        // export ndk path and run bash script
        val isWindows = System.getProperty("os.name").lowercase().contains("windows")
        if (isWindows) {
            println("当前系统是 Windows")
        } else {
            println("当前系统不是 Windows")
            environment(
                "CMAKE_HOME_PATH",
                android.sdkDirectory.absolutePath + "/cmake/${libs.versions.cmake.get()}"
            )
            environment("ANDROID_NDK_HOME", android.ndkDirectory.absolutePath)
        }
//    val winToWslPath: (String) -> String = { it.replace("C:\\", "/mnt/c/").replace("\\", "/") }
//    environment("ANDROID_SDK_HOME", winToWslPath(android.sdkDirectory.absolutePath))
//    environment("ANDROID_NDK_HOME", winToWslPath(android.ndkDirectory.absolutePath))
        commandLine("bash", "setup.sh")
    }
}

tasks.preBuild.dependsOn(ffmpegSetup)

dependencies {
    implementation(libs.androidx.annotation)
}

afterEvaluate {
    publishing {
        publications {
            create<MavenPublication>("mavenJava") {
                groupId = "com.fuck"
                artifactId = "nextlib-mediainfo"
                version = libs.versions.publish.get()

                from(components["release"])
            }
        }
        repositories {
            mavenLocal()
        }
    }
}
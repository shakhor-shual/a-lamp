plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.chaquo.python")
}

android {
    namespace = "com.alamp.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.alamp.app"
        minSdk = 24
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"

        ndk {
            abiFilters += listOf("arm64-v8a")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

chaquopy {
    defaultConfig {
        version = "3.12"
        buildPython("/usr/bin/python3")
        pip {
            // Нативные wheels для Android arm64-v8a (API 24+)
            install("./pydantic_core-2.41.5-cp312-cp312-android_24_arm64_v8a.whl")
            install("./jiter-0.19.0-cp312-cp312-android_24_arm64_v8a.whl")
            install("./tiktoken-0.12.0-cp312-cp312-android_24_arm64_v8a.whl")
            install("./watchfiles-1.1.1-cp312-cp312-android_24_arm64_v8a.whl")
            install("./regex-2026.11.19-cp312-cp312-android_24_arm64_v8a.whl")
            install("./cffi-2.0.0-cp312-cp312-android_24_arm64_v8a.whl")
            install("./cryptography-46.0.7-cp312-cp312-android_24_arm64_v8a.whl")
            install("./pillow-13.0.0-cp312-cp312-android_24_arm64_v8a.whl")
            install("./primp-2.0.1-cp312-cp312-android_24_arm64_v8a.whl")
            install("./rfc3161_client-1.0.9-cp312-cp312-android_24_arm64_v8a.whl")
            install("./setproctitle-1.3.8-cp312-cp312-android_24_arm64_v8a.whl")
            install("./rpds_py-2026.9.1-cp312-cp312-android_24_arm64_v8a.whl")
            install("./pysqlite3-0.6.0+chaquopy.1-cp312-cp312-android_24_arm64_v8a.whl")
            // Pure Python
            install("./rapidfuzz-3.14.6-py3-none-any.whl")
            install("pydantic==2.12.5")
            install("jsonschema==4.23.0")
            install("mcp==1.26.0")
            install("./nanobot_ai-0.4.1-py3-none-any.whl")
            install("./alamp_memory-0.1.0-py3-none-any.whl")
        }
        extractPackages("nanobot.web", "nanobot.channels", "alamp_memory")
    }
}

// Workaround: Chaquopy не всегда кладёт native .so в ABI-директорию.
// Копируем .so из common/ в arm64-v8a/ после установки wheel.
afterEvaluate {
    val copySo by tasks.registering(Exec::class) {
        dependsOn("installDebugPythonRequirements")
        workingDir(layout.buildDirectory.dir("python/pip/debug").get().asFile)
        commandLine("bash", "-c", "cd common && for f in \$(find . -name '*.so'); do mkdir -p ../arm64-v8a/\$(dirname \$f) && cp \$f ../arm64-v8a/\$f; done")
    }
    tasks.named("generateDebugPythonRequirementsAssets") {
        dependsOn(copySo)
    }
    tasks.named("generateDebugPythonProxies") {
        dependsOn(copySo)
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.11.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
}

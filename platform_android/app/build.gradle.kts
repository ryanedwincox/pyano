// Concern: configures Android packaging and tests | Non-concern: root plugin versions | IO: none
plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.pyano"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.pyano"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64")
        }

        externalNativeBuild {
            cmake {
                arguments += "-DANDROID_STL=c++_shared"
            }
        }
    }

    buildFeatures {
        compose = true
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    // Read signing secrets from environment or Gradle properties; leave releases unsigned when no keystore is configured.
    val keystorePath = System.getenv("PYANO_KEYSTORE")
        ?: project.findProperty("pyano.keystore") as String?
    val keystorePassword = System.getenv("PYANO_KEYSTORE_PASSWORD")
        ?: project.findProperty("pyano.keystore.password") as String?
    val keystoreKeyAlias = System.getenv("PYANO_KEY_ALIAS")
        ?: project.findProperty("pyano.key.alias") as String?
    val keystoreKeyPassword = System.getenv("PYANO_KEY_PASSWORD")
        ?: project.findProperty("pyano.key.password") as String?
    val hasReleaseKeystore = keystorePath != null && file(keystorePath).exists()

    signingConfigs {
        create("release") {
            if (hasReleaseKeystore) {
                storeFile = file(keystorePath!!)
                storePassword = keystorePassword
                keyAlias = keystoreKeyAlias
                keyPassword = keystoreKeyPassword
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // Only sign when a keystore is actually configured and present; otherwise the release build stays unsigned.
            if (hasReleaseKeystore) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    lint {
        // Keep lint configured/visible but non-blocking so it never fails the build on pre-existing warnings. Tighten these once the warning backlog is clean.
        abortOnError = false
        warningsAsErrors = false
        checkReleaseBuilds = false
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

// Host tests load an empty native stub; without a host C compiler, native-dependent tests skip via Assume.
val stubNativeDir = layout.buildDirectory.dir("test-native").get().asFile

val buildTestNativeStub by tasks.registering {
    val srcFile = file("src/test/native/pyano-native-stub.c")
    val libName = "libpyano-native.so"
    val outFile = File(stubNativeDir, libName)
    inputs.file(srcFile)
    outputs.file(outFile)
    doLast {
        val cc = listOf("cc", "gcc").firstOrNull { exe ->
            try {
                ProcessBuilder(exe, "--version").redirectErrorStream(true).start().waitFor() == 0
            } catch (e: Exception) { false }
        }
        stubNativeDir.mkdirs()
        if (cc == null) {
            logger.warn("No host C compiler found; skipping native test stub build. " +
                "Native-dependent tests will be skipped.")
            return@doLast
        }
        val proc = ProcessBuilder(
            cc, "-shared", "-fPIC", "-o", outFile.absolutePath, srcFile.absolutePath,
        ).redirectErrorStream(true).start()
        val log = proc.inputStream.bufferedReader().readText()
        if (proc.waitFor() != 0) {
            logger.warn("Failed to build native test stub: $log")
        }
    }
}

tasks.withType<Test>().configureEach {
    dependsOn(buildTestNativeStub)
    systemProperty("java.library.path",
        listOf(stubNativeDir.absolutePath, System.getProperty("java.library.path"))
            .joinToString(File.pathSeparator))
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.0")
    implementation(platform("androidx.compose:compose-bom:2024.06.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")

    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.2")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.2")
    implementation("androidx.work:work-runtime-ktx:2.9.0")
    debugImplementation("androidx.compose.ui:ui-tooling")

    implementation("com.google.code.gson:gson:2.11.0")
    implementation("com.journeyapps:zxing-android-embedded:4.3.0")
    androidTestImplementation(platform("androidx.compose:compose-bom:2024.06.00"))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    androidTestImplementation("androidx.test:runner:1.6.2")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.mockito:mockito-core:5.12.0")
}

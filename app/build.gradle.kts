import java.nio.file.Files

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ktlint)
}

ktlint {
    version.set("1.5.0")
    filter {
        exclude("**/generated/**")
    }
}

// Extracts the GLSL sources embedded in Shaders.kt and validates them with
// glslangValidator; skips automatically when the binary is missing locally
// (CI installs glslang-tools to enforce it). Implemented in plain Gradle so
// no extra interpreter is needed beyond the JVM Gradle already runs on.
val validateShaders =
    tasks.register("validateShaders") {
        group = "verification"
        description = "Validate GLSL shader sources embedded in Shaders.kt."

        doLast {
            val shadersFile = File(projectDir, "src/main/kotlin/app/floatdeck/gl/Shaders.kt")
            val tripleQuote = "\"\"\""
            val pattern =
                Regex(
                    "val\\s+(\\w+?)?(Vertex|Fragment)\\s*=\\s*$tripleQuote(.*?)$tripleQuote",
                    RegexOption.DOT_MATCHES_ALL,
                )
            val shaders = pattern.findAll(shadersFile.readText()).toList()
            if (shaders.isEmpty()) {
                throw GradleException("No shader strings found in ${shadersFile.path}")
            }

            val glslangAvailable =
                runCatching {
                    ProcessBuilder("glslangValidator", "--version").start().waitFor() == 0
                }.getOrDefault(false)
            if (!glslangAvailable) {
                logger.lifecycle("glslangValidator not found, skipping GLSL validation")
                return@doLast
            }

            val tmp = Files.createTempDirectory("shader-check").toFile()
            var failed = 0
            shaders.forEach { match ->
                val name = match.groups[1]?.value?.ifEmpty { "shader" } ?: "shader"
                val ext = if (match.groups[2]?.value == "Vertex") "vert" else "frag"
                val body = match.groups[3]?.value ?: return@forEach
                val file = File(tmp, "$name.$ext")
                file.writeText(body.trimStart('\n'))
                val process =
                    ProcessBuilder("glslangValidator", file.absolutePath)
                        .redirectErrorStream(true)
                        .start()
                val output = process.inputStream.bufferedReader().readText()
                if (process.waitFor() != 0) {
                    failed++
                    logger.error(output)
                }
            }
            tmp.deleteRecursively()
            logger.lifecycle("validated ${shaders.size} shaders, $failed failed")
            if (failed > 0) {
                throw GradleException("$failed of ${shaders.size} shaders failed GLSL validation")
            }
        }
    }

tasks.named("check") {
    dependsOn(validateShaders)
}

android {
    namespace = "app.floatdeck"
    compileSdk = 36

    sourceSets {
        getByName("main") {
            java.srcDirs("src/main/kotlin")
        }
    }

    defaultConfig {
        applicationId = "app.floatdeck"
        minSdk = 26
        targetSdk = 36
        versionCode = 4
        versionName = "0.3.1"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }

    signingConfigs {
        create("release") {
            val ksFile = System.getenv("KEYSTORE_FILE")
            if (ksFile != null) {
                storeFile = file(ksFile)
                storePassword = System.getenv("KEYSTORE_PASSWORD")
                keyAlias = System.getenv("KEY_ALIAS")
                keyPassword = System.getenv("KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
        }
        release {
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
        unitTests.all {
            it.useJUnitPlatform()
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime)
    implementation(libs.androidx.activity.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    debugImplementation(libs.androidx.compose.ui.tooling)

    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.datastore)
    implementation(libs.coil.compose)
    implementation(libs.coil.android)
    implementation(libs.kotlin.semver)

    testImplementation(libs.junit5.api)
    testRuntimeOnly(libs.junit5.engine)
    testRuntimeOnly(libs.junit5.launcher)
    testImplementation(libs.junit5.params)
    testImplementation(libs.mockk)
    // Real org.json implementation (the one in android.jar is a stub, so JSON can't be parsed in unit tests)
    testImplementation("org.json:json:20240303")

    androidTestImplementation(libs.compose.test)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}

configurations.all {
    resolutionStrategy {
        force("androidx.test:core:1.6.1")
    }
}

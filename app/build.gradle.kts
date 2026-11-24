/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

import java.net.URI

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

fun git(vararg args: String): String? = try {
    providers.exec { commandLine("git", *args); isIgnoreExitValue = true }.standardOutput.asText.get().trim().takeIf { it.isNotEmpty() }
} catch (e: Exception) {
    null
}

fun gitCommit(): String {
    val head = git("rev-parse", "HEAD")?.takeIf { it.matches(Regex("[0-9a-f]{40}")) } ?: return "unknown"
    return if (git("status", "--porcelain", "--untracked-files=no") != null) "$head-dirty" else head
}

kotlin {
    // Every compiler warning fails the build, as every other linter finding does (see the repo conventions).
    compilerOptions {
        jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17
        allWarningsAsErrors = true
    }
}

android {
    namespace = "com.screenplay"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.screenplay"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
        // The speech runtime ships native code for these only: a phone, and the emulator.
        // `-Pabi=x86_64` keeps one, for a faster emulator build.
        ndk { abiFilters += providers.gradleProperty("abi").map { listOf(it) }.getOrElse(listOf("arm64-v8a", "x86_64")) }
        testInstrumentationRunner = "com.screenplay.TestRunner"
        // The commit this build was made from, stored with each book it generates. "unknown" without git; "-dirty" when tracked files differ from it.
        buildConfigField("String", "COMMIT", "\"${gitCommit()}\"")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    // Android Lint: every warning fails the build. Findings that existed when this was added are in lint-baseline.xml.
    lint {
        warningsAsErrors = true
        abortOnError = true
        baseline = file("lint-baseline.xml")
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    // Fixture checks that run on the device (real OCR) and in the JVM (saved OCR) share one source file.
    sourceSets {
        getByName("test").kotlin.directories.add("src/sharedTest/java")
        getByName("androidTest").kotlin.directories.add("src/sharedTest/java")
    }
    testOptions {
        animationsDisabled = true
        managedDevices {
            localDevices {
                create("pixel6Api34") {
                    device = "Pixel 6"
                    apiLevel = 34
                    systemImageSource = "aosp-atd"
                }
            }
        }
    }
}

// The natural voice's runtime (sherpa-onnx, Apache-2.0) is published as an AAR on GitHub releases only, so it is fetched once into app/libs.
val sherpaVersion = "1.13.8"
val sherpaAar = layout.projectDirectory.file("libs/sherpa-onnx-$sherpaVersion.aar").asFile
val fetchSherpa by tasks.registering {
    // Locals, not the script's properties: the configuration cache cannot serialise the script object.
    val aar = sherpaAar
    val url = "https://github.com/k2-fsa/sherpa-onnx/releases/download/v$sherpaVersion/sherpa-onnx-$sherpaVersion.aar"
    outputs.file(aar)
    doLast {
        if (!aar.exists()) {
            aar.parentFile.mkdirs()
            val part = File(aar.path + ".part")
            URI(url).toURL().openStream().use { i -> part.outputStream().use { i.copyTo(it) } }
            part.renameTo(aar)
        }
    }
}
tasks.named("preBuild") { dependsOn(fetchSherpa) }

// Fails the unit tests when docs/use-cases.md names a test that no longer exists, so the document cannot rot.
val checkUseCases by tasks.registering(Exec::class) {
    workingDir = rootDir
    commandLine("bash", "scripts/check-use-cases.sh")
    inputs.file(rootDir.resolve("docs/use-cases.md"))
    inputs.dir("src/test")
    inputs.dir("src/androidTest")
    outputs.upToDateWhen { true }
}
tasks.matching { it.name == "testDebugUnitTest" }.configureEach { dependsOn(checkUseCases) }

dependencies {
    implementation(files(sherpaAar))
    // Unpacks the natural voice's .tar.bz2 download.
    implementation("org.apache.commons:commons-compress:1.28.0")
    // Reads the book list ("Catalogue"), which is xz compressed: 1.7 MB smaller than gzip for the same rows.
    implementation("org.tukaani:xz:1.12")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.2")
    implementation(platform("androidx.compose:compose-bom:2025.12.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")
    implementation("androidx.work:work-runtime-ktx:2.12.0")
    // Bundled model: OCR runs offline and needs no Google Play services.
    implementation("com.google.mlkit:text-recognition:16.0.1")
    implementation("com.google.mediapipe:tasks-genai:0.10.35")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20260814")

    androidTestImplementation(platform("androidx.compose:compose-bom:2025.12.01"))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test.espresso:espresso-intents:3.6.1")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}

/*
 * Copyright (C) 2016-2025 Álinson Santos Xavier <git@axavier.org>
 *
 * This file is part of ADay.
 *
 * ADay is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by the
 * Free Software Foundation, either version 3 of the License, or (at your
 * option) any later version.
 *
 * ADay is distributed in the hope that it will be useful, but
 * WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY
 * or FITNESS FOR A PARTICULAR PURPOSE. See the GNU General Public License for
 * more details.
 *
 * You should have received a copy of the GNU General Public License along
 * with this program. If not, see <http://www.gnu.org/licenses/>.
 */

plugins {
    alias(libs.plugins.agp)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.ksp)
    alias(libs.plugins.ktlint.plugin)
}

tasks.compileLint {
    dependsOn("updateTranslators")
}

/*
Added on top of kotlinOptions to work around this issue:
https://youtrack.jetbrains.com/issue/KTIJ-24311/task-current-target-is-17-and-kaptGenerateStubsProductionDebugKotlin-task-current-target-is-1.8-jvm-target-compatibility-should#focus=Comments-27-6798448.0-0
Updating gradle might fix this, so try again in the future to remove this and run:
./gradlew --rerun-tasks :aday-android:kaptGenerateStubsReleaseKotlin
If this doesn't produce any warning, try to remove it.
 */
kotlin {
    jvmToolchain(17)
}

android {
    namespace = "org.bruce.aday"
    compileSdk = 36

    defaultConfig {
        versionCode = 20301
        versionName = "2.3.1"
        minSdk = 28
        targetSdk = 36
        applicationId = "org.bruce.aday"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        // One universal APK: real phones (armeabi-v7a / arm64-v8a) + x86_64 emulators (e.g. Pixel).
        // minSdk 28 covers Galaxy S9-class devices on Android 9+. Older OS versions are not supported.
        // Whisper.cpp JNI (libwhisper.so) via CMake; x86_64 for emulators.
        ndk {
            abiFilters += listOf("armeabi-v7a", "arm64-v8a", "x86_64")
        }
        // Google Play 16 KB page-size requirement: NDK r28+ defaults; flag helps r27 CMake builds.
        // https://developer.android.com/guide/practices/page-sizes
        externalNativeBuild {
            cmake {
                arguments += listOf(
                    "-DANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES=ON",
                    // CMakeLists also forces GGML_OPENMP=OFF; duplicate ensures clean reconfigures.
                    "-DGGML_OPENMP=OFF",
                )
            }
        }
    }

    // r28+: 16 KB ELF alignment default for 64-bit; fixes libwhisper/libggml/libomp on 16 KB devices.
    ndkVersion = "28.0.13004108"

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    signingConfigs {
        if (System.getenv("ADAY_KEY_ALIAS") != null) {
            create("release") {
                keyAlias = System.getenv("ADAY_KEY_ALIAS")
                keyPassword = System.getenv("ADAY_KEY_PASSWORD")
                storeFile = file(System.getenv("ADAY_KEY_STORE"))
                storePassword = System.getenv("ADAY_STORE_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android.txt"), "proguard-rules.txt")
            if (signingConfigs.findByName("release") != null) {
                signingConfig = signingConfigs.getByName("release")
            }
        }

        debug {
            enableUnitTestCoverage = true
        }
    }

    compileOptions {
        isCoreLibraryDesugaringEnabled = true
        targetCompatibility(JavaVersion.VERSION_17)
        sourceCompatibility(JavaVersion.VERSION_17)
    }

    kotlinOptions.jvmTarget = JavaVersion.VERSION_17.toString()
    buildFeatures.viewBinding = true
    lint.abortOnError = false

    // Large models in assets: avoid deflate so AssetFileDescriptor.length is reliable and install I/O stays sane.
    androidResources {
        noCompress += listOf("zip", "gguf", "bin")
    }

    // Built APK/AAB files use this basename (e.g. ADAY-debug.apk); launcher title is @string/app_name.
    base {
        archivesName = "ADAY"
    }
}

dependencies {
    compileOnly(libs.jsr250.api)
    coreLibraryDesugaring(libs.desugar.jdk.libs)
    implementation(libs.appIntro)
    implementation(libs.jsr305)
    implementation(libs.dagger)
    implementation(libs.guava)
    implementation(libs.ktor.client.android)
    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.jackson)
    implementation(libs.ktor.client.json)
    implementation(libs.kotlin.stdlib.jdk8)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.appcompat)
    implementation(libs.legacy.preference.v14)
    implementation(libs.legacy.support.v4)
    implementation(libs.material)
    implementation(libs.documentfile)
    implementation(libs.play.services.ads)
    implementation(libs.opencsv)
    implementation(libs.konfetti.xml)
    implementation(libs.llama.kotlin.android)
    implementation(libs.vosk.android)
    implementation(project(":aday-core"))
    ksp(libs.dagger.compiler)

    androidTestImplementation(libs.bundles.androidTest)
    testImplementation(libs.bundles.test)
}

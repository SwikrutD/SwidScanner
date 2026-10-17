import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Release signing is read from keystore.properties (not committed).
val keystoreProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

android {
    namespace = "io.github.swikrutd.swidscanner"
    compileSdk = 35

    defaultConfig {
        applicationId = "io.github.swikrutd.swidscanner"
        minSdk = 26
        targetSdk = 35
        versionCode = 2
        versionName = "1.1.0"
        ndk {
            // Covers practically every phone made in the last decade.
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
            // ./gradlew assembleDebug -Pemulator adds x86_64 for testing on an emulator.
            if (project.hasProperty("emulator")) abiFilters += "x86_64"
        }
    }

    signingConfigs {
        if (keystoreProps.isNotEmpty()) {
            create("release") {
                storeFile = rootProject.file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        // Installs beside the release build, so testing never means uninstalling it and losing its documents.
        debug {
            applicationIdSuffix = ".dev"
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (keystoreProps.isNotEmpty()) signingConfig = signingConfigs.getByName("release")
        }
        // Release speed for timing live detection on a phone. Signed with the debug key and
        // installed beside the other builds as io.github.swikrutd.swidscanner.bench.
        create("bench") {
            initWith(getByName("release"))
            signingConfig = signingConfigs.getByName("debug")
            applicationIdSuffix = ".bench"
            matchingFallbacks += "release"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true; buildConfig = true }
    packaging {
        resources.excludes += listOf(
            "META-INF/{AL2.0,LGPL2.1}", "META-INF/DEPENDENCIES",
            // Post-quantum tables from BouncyCastle; PDF password protection doesn't use them.
            "org/bouncycastle/pqc/**",
        )
        jniLibs.useLegacyPackaging = true
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.navigation:navigation-compose:2.8.5")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.documentfile:documentfile:1.0.1")
    implementation("androidx.exifinterface:exifinterface:1.3.7")

    val camerax = "1.4.1"
    implementation("androidx.camera:camera-core:$camerax")
    implementation("androidx.camera:camera-camera2:$camerax")
    implementation("androidx.camera:camera-lifecycle:$camerax")
    implementation("androidx.camera:camera-view:$camerax")

    implementation("io.coil-kt:coil-compose:2.7.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.9.0")

    // Edge detection, perspective correction and filters
    implementation("org.opencv:opencv:4.12.0")
    // PDF writing with JPEG compression, password protection and an OCR text layer
    implementation("com.tom-roush:pdfbox-android:2.0.27.0")
    // QR and barcode decoding
    implementation("com.google.zxing:core:3.5.3")
    // Offline OCR (model bundled in the APK)
    implementation("com.google.mlkit:text-recognition:16.0.1")
    // Google Drive authorization
    implementation("com.google.android.gms:play-services-auth:21.3.0")
}

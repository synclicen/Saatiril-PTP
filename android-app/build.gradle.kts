import java.io.FileInputStream
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// ── Release signing config ─────────────────────────────────────
// Reads keystore location + passwords from keystore.properties (project root).
// The keystore itself is supplied by CI from a base64-encoded GitHub Actions
// secret (SAATIRIL_KEYSTORE_BASE64).
// Locally, drop `saatiril-release.keystore` + `keystore.properties` in the
// project root to build a signed release APK.
val keystorePropertiesFile = rootProject.file("keystore.properties")
val keystoreProperties = Properties()
if (keystorePropertiesFile.exists()) {
    keystoreProperties.load(FileInputStream(keystorePropertiesFile))
}

android {
    namespace = "com.saatiril.andro"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.saatiril.andro"
        minSdk = 24
        targetSdk = 34
        versionCode = 81
        versionName = "2.7.4-saatiril-andro"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }

        // ── ABI filter: only include ARM architectures ──
        // Real Android devices are ARM (arm64-v8a, armeabi-v7a).
        // x86 and x86_64 are only for emulators.
        // This saves ~18MB by dropping x86/x86_64 native libs
        // (MediaPipe tasks-vision has ~10MB per ABI).
        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
        }
    }

    signingConfigs {
        create("release") {
            if (keystoreProperties.isNotEmpty()) {
                keyAlias = keystoreProperties["keyAlias"] as String
                keyPassword = keystoreProperties["keyPassword"] as String
                storeFile = rootProject.file(keystoreProperties["storeFile"] as String)
                storePassword = keystoreProperties["storePassword"] as String
            }
        }
    }

    // ── Product Flavors ──
    // 'full' = Admin + MC + Operator (all roles, license required)
    // 'mc'   = MC-only (BLE Remote only, no license, no Admin/Operator access)
    flavorDimensions += "distribution"
    productFlavors {
        create("full") {
            dimension = "distribution"
            buildConfigField("boolean", "MC_ONLY", "false")
            resValue("string", "app_name", "Saatiril Andro")
        }
        create("mc") {
            dimension = "distribution"
            applicationIdSuffix = ".mc"
            versionNameSuffix = "-mc"
            buildConfigField("boolean", "MC_ONLY", "true")
            resValue("string", "app_name", "Saatiril MC")
        }
    }

    buildTypes {
        debug {
            // Debug also benefits from ABI filter (same ndk config applies)
            isMinifyEnabled = false
        }
        release {
            // R8 minification DISABLED — ktor CIO engine throws "Array is empty"
            // when R8 strips internal classes needed at runtime. The CIO factory
            // object bypass wasn't enough because R8 also strips plugin pipeline
            // classes, coroutine dispatchers, and ServiceLoader metadata that
            // ktor accesses via reflection. Disabling minification is the only
            // reliable fix (APK is ~50MB instead of ~33MB, but the server works).
            isMinifyEnabled = false
            isShrinkResources = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            // Sign the release APK so Android 7+ will install it.
            signingConfig = signingConfigs.getByName("release")
        }
    }

    // Disable lint to save memory during build
    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }

    kotlinOptions {
        jvmTarget = "1.8"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.8"
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    // AndroidX Core
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.7.0")
    implementation("androidx.activity:activity-compose:1.8.2")
    implementation("androidx.appcompat:appcompat:1.6.1")
    // DocumentFile — reliable SAF folder operations across providers (Google Drive, etc.)
    implementation("androidx.documentfile:documentfile:1.0.1")

    // Jetpack Compose
    implementation(platform("androidx.compose:compose-bom:2024.02.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.runtime:runtime-livedata")
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")

    // Socket.io — Saatiril protocol communication
    implementation("io.socket:socket.io-client:2.1.0")

    // ═══════════════════════════════════════════════════════════════
    // UVCCamera — Direct USB Video Class access for HDMI capture cards
    // ═══════════════════════════════════════════════════════════════
    // v17: Using alexey-pelykh/UVCCamera fork (org.uvccamera:lib) on Maven Central.
    // This is a maintained hard fork of the original saki4510t/UVCCamera.
    // Same com.serenegiant.usb.* package namespace — no code changes needed.
    //
    // CRITICAL: Camera2/CameraX API CANNOT access USB HDMI video capture
    // cards on Android. USB capture cards are UVC (USB Video Class) devices
    // and require a dedicated UVC library to access them via USB Host API.
    //
    // v17 FIX: MacroSilicon (VID:345F) black screen fix:
    //   1. FORCE MJPEG format + lock 720p (never YUYV)
    //   2. setBandwidthFactor(1.0f) immediately after open
    //   3. Proper TextureView surface setup before startPreview()
    implementation("org.uvccamera:lib:0.0.13")

    // JSON parsing
    implementation("com.google.code.gson:gson:2.10.1")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")

    // Navigation
    implementation("androidx.navigation:navigation-compose:2.7.6")

    // DataStore for preferences
    implementation("androidx.datastore:datastore-preferences:1.0.0")

    // ═══════════════════════════════════════════════════════════════
    // MediaPipe Tasks Vision — for "Trigger Tangan" hand trigger
    // Detects any hand (open or closed) with 21 landmarks per hand.
    // Offline model (hand_landmarker.task in assets), no network required.
    //
    // NOTE: com.google.mlkit:hand-detection does NOT exist on Maven!
    // Google's hand detection is only available via MediaPipe Tasks Vision.
    // ═══════════════════════════════════════════════════════════════
    implementation("com.google.mediapipe:tasks-vision:0.10.14")

    // ═══════════════════════════════════════════════════════════════
    // ktor SERVER — the Android app IS the LAN Socket.io hub (like the
    // Electron app). ktor-server-cio is pure Kotlin (no netty native libs),
    // supports HTTP (for Engine.IO polling) + WebSockets on one port.
    // ═══════════════════════════════════════════════════════════════
    implementation("io.ktor:ktor-server-core:2.3.12")
    implementation("io.ktor:ktor-server-cio:2.3.12")
    implementation("io.ktor:ktor-server-websockets:2.3.12")
    implementation("io.ktor:ktor-server-content-negotiation:2.3.12")
    implementation("io.ktor:ktor-serialization-gson:2.3.12")

    // QR code generation (so MC/operator can scan & join the admin's LAN server)
    implementation("com.google.zxing:core:3.5.3")
}

import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.hilt)
    alias(libs.plugins.ksp)
}

// Load local.properties — used for WORKER_URL in Phase F (Cloudflare Worker endpoint)
// Channel3 API key is now user-entered in Settings; no longer loaded from local.properties
val localProps = Properties().also { props ->
    val f = rootProject.file("local.properties")
    if (f.exists()) props.load(f.inputStream())
}

android {
    namespace = "com.funkodex"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.funkodex"
        minSdk = 26
        multiDexEnabled = true  // Phase E: Drive API transitive deps may exceed DEX limit
        targetSdk = 36
        versionCode = 1
        versionName = "1.0.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // Channel3 API key: user-entered in Settings > Data Sources
        // Stored in EncryptedSharedPreferences via SecureKeyStore (never in BuildConfig)

        // Phase F: Cloudflare Worker URL — not secret, safe in BuildConfig.
        // Set in local.properties: workerUrl=https://funkodex-contrib.YOUR_ACCOUNT.workers.dev
        // Defaults to empty string — upload silently skipped if not set.
        buildConfigField(
            "String",
            "WORKER_URL",
            "\"${localProps.getProperty("workerUrl", "")}\""
        )
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions { jvmTarget = "17" }

    buildFeatures {
        compose = true
        buildConfig = true   // retained for Phase F: BuildConfig.WORKER_URL
    }

    // ── Apache POI packaging fix ──────────────────────────────────────────────
    // POI ships duplicate META-INF files that cause a Gradle packaging conflict.
    packaging {
        resources {
            excludes += setOf(
                "META-INF/DEPENDENCIES",
                "META-INF/LICENSE",
                "META-INF/LICENSE.txt",
                "META-INF/NOTICE",
                "META-INF/NOTICE.txt",
                "META-INF/ASL2.0",
                "META-INF/*.kotlin_module",
                "META-INF/versions/9/previous-compilation-data.bin"
            )
        }
    }
}

dependencies {
    // Core
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel)
    implementation(libs.coroutines.android)

    // Compose
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons)
    implementation(libs.compose.activity)
    debugImplementation(libs.compose.ui.tooling)

    // Navigation
    implementation(libs.navigation.compose)
    implementation(libs.hilt.navigation.compose)
    implementation(libs.hilt.work)               // Hilt + WorkManager (@HiltWorker)
    ksp(libs.hilt.work.compiler)                 // Hilt-Work annotation processor
    // Phase E: widget + Drive backup
    implementation(libs.glance.appwidget)        // Jetpack Glance home screen widget
    implementation(libs.play.services.auth)      // Google Sign-In for Drive backup
    implementation(libs.google.api.drive)        // Google Drive API client

    // Hilt
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)

    // Couchbase Lite
    implementation(libs.couchbase.lite)

    // Networking — OkHttp + Gson only (Retrofit removed: all calls use raw OkHttp)
    implementation(libs.okhttp.core)
    implementation(libs.okhttp.logging)
    implementation(libs.gson)

    // DataStore — persisted preferences (theme selection)
    implementation(libs.workmanager)      // Periodic catalog refresh
    implementation(libs.datastore.prefs)
    implementation(libs.security.crypto)       // EncryptedSharedPreferences for Channel3 key
    implementation(libs.exifinterface)          // EXIF rotation correction for user photos (Phase C)

    // Accompanist — FlowRow for series filter chips, permissions helper for camera
    implementation(libs.accompanist.flowlayout)
    implementation(libs.accompanist.permissions)

    // Chrome Custom Tabs — OAuth flows for HobbyDB and eBay
    implementation(libs.browser)

    // Camera & ML Kit
    implementation(libs.mlkit.barcode)
    implementation(libs.camerax.core)
    implementation(libs.camerax.camera2)
    implementation(libs.camerax.lifecycle)
    implementation(libs.camerax.view)

    // Image loading
    implementation(libs.coil)

    // Excel export
    implementation(libs.apache.poi)

    // Testing
    testImplementation(libs.junit)
    testImplementation(libs.kotlin.test)
    testImplementation(libs.coroutines.test)
    testImplementation("io.mockk:mockk:1.13.12")  // F-QA-1: ScannerViewModel state machine tests
    androidTestImplementation(libs.androidx.test.ext)
    androidTestImplementation(libs.androidx.test.espresso)
    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation(libs.compose.ui.test)
}

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
        // Stored via SecureKeyStore (AES/GCM, Android Keystore-backed; never in BuildConfig)

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

    buildFeatures {
        compose = true
        buildConfig = true   // retained for Phase F: BuildConfig.WORKER_URL
    }

    // ── Catalog asset: DO NOT rename back to .gz ─────────────────────────────
    // The catalog ships as `assets/funkodex_base_catalog.json.gz_` — note the
    // TRAILING UNDERSCORE. It is a normal gzip file; the odd extension is
    // deliberate and load-bearing.
    //
    // AGP's asset merger DECOMPRESSES any `.gz` file in src/main/assets and
    // STRIPS the extension during mergeXxxAssets — before AAPT2 ever runs. A
    // 2.0 MB `funkodex_base_catalog.json.gz` went in and an 18.1 MB
    // `funkodex_base_catalog.json` came out in the APK, so
    // `assets.open("funkodex_base_catalog.json.gz")` threw FileNotFoundException,
    // CatalogPreloader returned AssetMissing, and the catalog silently never
    // loaded on ANY device — search fell back to the network and looked fine.
    // (Verified S23 by listing the APK's asset entries; a `gradlew clean` does
    // NOT fix it — the merger does this every build.)
    //
    // `.gz_` is not an extension AGP recognises, so the file passes through
    // untouched. noCompress then stops AAPT2 deflating an already-gzipped file
    // (pure waste: no size win, extra CPU).
    //
    // If you change this extension, change CatalogPreloader.ASSET_NAME and
    // build_catalog_asset.py's DEF_OUT to match, and verify the APK actually
    // contains the .gz_ before shipping:
    //   [IO.Compression.ZipFile]::OpenRead("app-debug.apk").Entries |
    //     Where-Object { $_.FullName -like "assets/funkodex*" }
    androidResources {
        noCompress += "gz_"
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

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    // Core
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.splashscreen)
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
    implementation(libs.play.services.auth)      // Drive AuthorizationClient
    implementation(libs.coroutines.play.services) // .await() for Task<T> (DriveAuthManager)
    implementation(libs.google.api.drive)        // Google Drive API client
    implementation(libs.google.api.client.android) // Drive REST client (HttpRequestInitializer)

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
    implementation(libs.exifinterface)          // EXIF rotation correction for user photos (Phase C)

    // Accompanist — FlowRow for series filter chips, permissions helper for camera
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

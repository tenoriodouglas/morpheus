import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
    // Firebase (remote parent<->child commands) only applies when a
    // google-services.json is present, so open-source builds still work.
    id("com.google.gms.google-services") apply false
}

// Apply the Google Services plugin only if the config file exists.
if (file("google-services.json").exists()) {
    apply(plugin = "com.google.gms.google-services")
}

// Load signing config from an untracked keystore.properties file if present.
val keystorePropsFile = rootProject.file("keystore.properties")
val keystoreProps = Properties().apply {
    if (keystorePropsFile.exists()) load(keystorePropsFile.inputStream())
}

android {
    namespace = "com.morpheus.family"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.morpheus.family"
        minSdk = 26
        targetSdk = 36
        // versionCode auto-increments in CI (VERSION_CODE = run_number + offset)
        // so every Play upload is unique and increasing; defaults to 1 locally.
        versionCode = (System.getenv("VERSION_CODE") ?: "1").toInt()
        versionName = "1.0.0"
        vectorDrawables { useSupportLibrary = true }
    }

    signingConfigs {
        // Fixed debug key (checked into the repo — debug keys are not secret) so
        // every CI debug build shares one signature. Lets you install a new debug
        // APK over an old one without "app not installed" signature conflicts.
        getByName("debug") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
        create("release") {
            // Values come from keystore.properties (local) or env vars (CI).
            val storePath = keystoreProps.getProperty("storeFile")
                ?: System.getenv("KEYSTORE_FILE")
            if (storePath != null) {
                storeFile = file(storePath)
                storePassword = keystoreProps.getProperty("storePassword")
                    ?: System.getenv("KEYSTORE_PASSWORD")
                keyAlias = keystoreProps.getProperty("keyAlias")
                    ?: System.getenv("KEY_ALIAS")
                keyPassword = keystoreProps.getProperty("keyPassword")
                    ?: System.getenv("KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            // Use the release signing config only when a keystore was provided.
            val hasKeystore = keystoreProps.getProperty("storeFile") != null ||
                System.getenv("KEYSTORE_FILE") != null
            signingConfig = if (hasKeystore) signingConfigs.getByName("release") else null
        }
        debug {
            applicationIdSuffix = ".debug"
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }

    buildFeatures {
        compose = true
        // Expose VERSION_CODE/VERSION_NAME to the GitHub self-updater (debug only).
        buildConfig = true
    }

    packaging {
        resources { excludes += "/META-INF/{AL2.0,LGPL2.1}" }
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.09.02")
    implementation(composeBom)

    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.5")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.5")
    implementation("androidx.lifecycle:lifecycle-service:2.8.5")
    implementation("androidx.activity:activity-compose:1.9.2")

    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.material3:material3")

    // Persisted schedule / mode / pairing state.
    implementation("androidx.datastore:datastore-preferences:1.1.1")

    // Coroutines.
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    // JSON serialization for the richer app-policy model (JVM-pure, unit-testable).
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")

    // QR pairing: generate on the child, scan on the parent (bundles zxing core).
    implementation("com.journeyapps:zxing-android-embedded:4.3.0")

    // In-app updates: prompt/apply a new version from Play when the app opens.
    implementation("com.google.android.play:app-update:2.1.0")
    implementation("com.google.android.play:app-update-ktx:2.1.0")

    // Background watchdog that revives the guardian service (WhatsApp-like persistence).
    implementation("androidx.work:work-runtime-ktx:2.9.1")

    // Keyless map (OpenStreetMap) for the live location + 24h route view.
    implementation("org.osmdroid:osmdroid-android:6.1.20")

    // Location (child location for the parent, geofence, SOS).
    implementation("com.google.android.gms:play-services-location:21.3.0")
    // await() bridge for Play Services Tasks.
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.8.1")

    // Firebase (optional remote channel). Guarded at runtime by available().
    implementation(platform("com.google.firebase:firebase-bom:33.3.0"))
    implementation("com.google.firebase:firebase-messaging-ktx")
    implementation("com.google.firebase:firebase-firestore-ktx")
    // Anonymous auth so Firestore security rules can require request.auth != null.
    implementation("com.google.firebase:firebase-auth-ktx")

    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.6.1")

    debugImplementation("androidx.compose.ui:ui-tooling")
    implementation("androidx.compose.ui:ui-tooling-preview")
}

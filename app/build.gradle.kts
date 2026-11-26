plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
}

android {
    namespace = "com.zapp.tv"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.zapp.tv"
        minSdk = 24
        targetSdk = 34
        // Each GitHub build number becomes the version code, so the in-app updater can compare builds.
        versionCode = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 3
        versionName = "0.38.0"
        // Optional: a Simkl app ID built in from the SIMKL_CLIENT_ID GitHub secret (it can also be set on the phone page).
        buildConfigField("String", "SIMKL_CLIENT_ID", "\"" + (System.getenv("SIMKL_CLIENT_ID") ?: "").filter { it.isLetterOrDigit() } + "\"")
    }

    // One permanent key, so every build can update the previous one in place.
    // CI restores it from the ZAPP_KEYSTORE_B64 / ZAPP_KEYSTORE_PASSWORD secrets; without them
    // the build falls back to a throwaway debug key (fine for testing, but can't update earlier installs).
    val zappKeystore = rootProject.file("zapp.keystore")
    signingConfigs {
        create("zapp") {
            if (zappKeystore.exists()) {
                storeFile = zappKeystore
                storeType = "PKCS12"
                storePassword = System.getenv("ZAPP_KEYSTORE_PASSWORD")
                keyAlias = "zapp"
                keyPassword = System.getenv("ZAPP_KEYSTORE_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            // R8 makes Compose much faster on TVs. Names are kept so crash reports stay readable.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = if (zappKeystore.exists()) signingConfigs.getByName("zapp") else signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.09.03"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.6")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.6")
    implementation("androidx.core:core-ktx:1.13.1")

    // Database + paging
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    implementation("androidx.room:room-paging:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")
    implementation("androidx.paging:paging-compose:3.3.2")

    // Networking + images
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("io.coil-kt:coil-compose:2.7.0")
    implementation("androidx.palette:palette-ktx:1.0.0")
    // Applies the libraries' built-in startup profiles on sideloaded installs, so screens run fast from the first launch.
    implementation("androidx.profileinstaller:profileinstaller:1.3.1")

    // Player
    implementation("androidx.media3:media3-exoplayer:1.4.1")
    implementation("androidx.media3:media3-exoplayer-hls:1.4.1")
    implementation("androidx.media3:media3-exoplayer-dash:1.4.1")

    // QR code for phone setup
    implementation("com.google.zxing:core:3.5.3")

    testImplementation("junit:junit:4.13.2")
}

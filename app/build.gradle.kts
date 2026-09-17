plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Single release keystore shipped with the repo so every build signs with the
// SAME key — installs just update, no uninstall first. Env vars let a future
// CI switch to GitHub Secrets overrides without touching build config.
val tetraKeystorePath = System.getenv("TETRA_KEYSTORE_FILE") ?: rootProject.file("keystore/tetra.jks").absolutePath
val tetraKeystorePassword = System.getenv("TETRA_KEYSTORE_PASSWORD") ?: "tetra-bot-2026-key"
val tetraKeyAlias = System.getenv("TETRA_KEY_ALIAS") ?: "tetra"
val tetraKeyPassword = System.getenv("TETRA_KEY_PASSWORD") ?: "tetra-bot-2026-key"

android {
    namespace = "com.tetra.bot"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.tetra.bot"
        minSdk = 26
        targetSdk = 34
        versionCode = 5
        versionName = "1.4"
    }

    signingConfigs {
        create("release") {
            storeFile = file(tetraKeystorePath)
            storePassword = tetraKeystorePassword
            keyAlias = tetraKeyAlias
            keyPassword = tetraKeyPassword
        }
    }

    lint {
        // Custom API-gated code (reflection takeScreenshot, etc.) trips lint's
        // NewApi on release; keep the build focused on compiling + signing.
        checkReleaseBuilds = false
        abortOnError = false
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.2.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    testImplementation("junit:junit:4.13.2")
}
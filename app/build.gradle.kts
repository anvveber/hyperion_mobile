plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// CI passes these in. Locally they fall back to a dev build.
val appVersionCode = (findProperty("versionCode") as String?)?.toIntOrNull() ?: 1
val appVersionName = (findProperty("versionName") as String?) ?: "1.0.0-dev"

// Release signing comes from environment variables so the keystore never
// lives in the repository. Without them the release build is left unsigned
// and CI signs it with the debug key instead (see .github/workflows/android.yml).
val keystorePath: String? = System.getenv("ANDROID_KEYSTORE_PATH")
val keystorePassword: String? = System.getenv("ANDROID_KEYSTORE_PASSWORD")
val hasReleaseKey = !keystorePath.isNullOrBlank() && !keystorePassword.isNullOrBlank()

android {
    namespace = "io.github.anvveber.hyperion"
    compileSdk = 35

    defaultConfig {
        applicationId = "io.github.anvveber.hyperion"
        minSdk = 29
        targetSdk = 35
        versionCode = appVersionCode
        versionName = appVersionName
    }

    signingConfigs {
        if (hasReleaseKey) {
            create("release") {
                storeFile = file(keystorePath!!)
                storePassword = keystorePassword
                keyAlias = System.getenv("ANDROID_KEY_ALIAS") ?: "hyperion"
                keyPassword = System.getenv("ANDROID_KEY_PASSWORD") ?: keystorePassword
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = if (hasReleaseKey) {
                signingConfigs.getByName("release")
            } else {
                signingConfigs.getByName("debug")
            }
        }
        debug {
            versionNameSuffix = "-debug"
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
        buildConfig = true
    }

    // No Google Play Services or Firebase anywhere: the app has to run on
    // GrapheneOS without sandboxed Play installed.
    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.webkit:webkit:1.12.1")
}

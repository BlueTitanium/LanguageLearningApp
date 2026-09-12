plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.tsm.wtlens"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.tsm.wtlens"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
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
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    // Encrypted storage for user-supplied online translation API keys
    implementation("androidx.security:security-crypto:1.1.0-alpha06")

    // Korean text OCR
    implementation("com.google.mlkit:text-recognition-korean:16.0.1")
    // On-device translation
    implementation("com.google.mlkit:translate:17.0.3")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.8.1")

    // Korean morphological analyzer, for finding the dictionary/citation
    // root form of conjugated verbs and adjectives (handles irregular
    // conjugations properly, unlike plain suffix stripping).
    implementation("com.github.shin285:KOMORAN:3.4.0-beta")
}

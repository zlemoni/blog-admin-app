plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "cn.moodlog.admin"
    compileSdk = 34

    defaultConfig {
        applicationId = "cn.moodlog.admin"
        minSdk = 26
        targetSdk = 34
        versionCode = 2
        versionName = "0.1.0-p1"
        ndk { abiFilters += "arm64-v8a" }
    }

    androidResources {
        noCompress += listOf("tar.gz")
    }

    buildFeatures {
        buildConfig = true
    }

    buildTypes {
        release { isMinifyEnabled = false }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.webkit:webkit:1.11.0")
}

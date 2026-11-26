plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val ksPath = System.getenv("KEYSTORE_PATH")
val hasKeystore = ksPath != null && file(ksPath).exists()

android {
    namespace = "cn.moodlog.admin"
    compileSdk = 34

    defaultConfig {
        applicationId = "cn.moodlog.admin"
        minSdk = 26
        targetSdk = 34
        versionCode = 20
        versionName = "0.4.2-p19"
        ndk { abiFilters += "arm64-v8a" }
    }

    androidResources {
        noCompress += listOf("tar.gz")
    }

    // 关键：把 jniLibs 解压到磁盘，proot 才能被执行
    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
    }

    signingConfigs {
        if (hasKeystore) {
            create("app") {
                storeFile = file(ksPath!!)
                storePassword = System.getenv("KEYSTORE_PASSWORD") ?: ""
                keyAlias = System.getenv("KEY_ALIAS") ?: "blog"
                keyPassword = System.getenv("KEY_PASSWORD") ?: ""
            }
        }
    }

    buildFeatures {
        buildConfig = true
    }

    buildTypes {
        debug {
            if (hasKeystore) signingConfig = signingConfigs.getByName("app")
        }
        release {
            isMinifyEnabled = false
            if (hasKeystore) signingConfig = signingConfigs.getByName("app")
        }
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
    implementation("androidx.core:core-ktx:1.13.1")
}

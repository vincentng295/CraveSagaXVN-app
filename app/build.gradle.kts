plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "vn.huskydg.cravesagax"
    compileSdk = 35

    defaultConfig {
        applicationId = "vn.huskydg.cravesagax"
        minSdk = 24
        targetSdk = 35
        versionCode = 1
        versionName = "1.10"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // Ký tạm bằng khóa debug để APK cài được ngay. Muốn phát hành thật thì thay signingConfig.
            signingConfig = signingConfigs.getByName("debug")
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
    implementation("androidx.drawerlayout:drawerlayout:1.2.0")
    implementation("androidx.webkit:webkit:1.12.1")
}

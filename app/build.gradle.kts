import java.util.Base64

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// ---- Cấu hình ký bản release ----
// Ưu tiên 1: file MV2R_KEYSTORE.env ở thư mục gốc project (CI ghi từ secret MV2R_KEYSTORE_ENV).
// Ưu tiên 2: biến môi trường cùng tên. Thiếu cả hai -> ký bằng khóa debug.
// Các khóa: MV_KEYSTORE_BASE64, MV_KEYSTORE_PASSWORD, MV_KEY_ALIAS, MV_KEY_PASSWORD (xem MV2R_KEYSTORE.env.example).
val keystoreEnvFile = rootProject.file("MV2R_KEYSTORE.env")
val keystoreEnv: Map<String, String> =
    if (!keystoreEnvFile.isFile) emptyMap()
    else keystoreEnvFile.readLines()
        .map { it.trim() }
        .filter { it.isNotEmpty() && !it.startsWith("#") && it.contains("=") }
        .associate { line ->
            val i = line.indexOf('=')
            line.substring(0, i).trim().removePrefix("export ").trim() to
                line.substring(i + 1).trim().trim('"', '\'')
        }

fun signingValue(name: String): String? =
    (keystoreEnv[name] ?: System.getenv(name))?.trim()?.takeIf { it.isNotEmpty() }

val ksBase64 = signingValue("MV_KEYSTORE_BASE64")
val ksPassword = signingValue("MV_KEYSTORE_PASSWORD")
val ksAlias = signingValue("MV_KEY_ALIAS")
val ksKeyPassword = signingValue("MV_KEY_PASSWORD")
val hasReleaseKeystore = ksBase64 != null && ksPassword != null && ksAlias != null && ksKeyPassword != null

val releaseKeystoreFile: File? = if (hasReleaseKeystore) {
    layout.buildDirectory.file("keystore/release.jks").get().asFile.also {
        it.parentFile.mkdirs()
        it.writeBytes(Base64.getMimeDecoder().decode(ksBase64!!))
    }
} else null

logger.lifecycle(
    if (hasReleaseKeystore) "[signing] Ký release bằng keystore từ " +
        (if (keystoreEnv.containsKey("MV_KEYSTORE_BASE64")) "MV2R_KEYSTORE.env" else "biến môi trường")
    else "[signing] Không có cấu hình keystore -> ký release bằng khóa debug"
)

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

    signingConfigs {
        if (hasReleaseKeystore) {
            create("release") {
                storeFile = releaseKeystoreFile
                storePassword = ksPassword
                keyAlias = ksAlias
                keyPassword = ksKeyPassword
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // Có keystore (file .env / biến môi trường) thì ký thật, không thì ký tạm bằng khóa debug để APK cài được ngay.
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
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

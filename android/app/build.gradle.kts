plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "jp.satti.shinmaiothello"
    compileSdk = 36

    defaultConfig {
        applicationId = "jp.satti.shinmaiothello"
        // 公式DATサンプルの要件に一致（minSdk 31 / targetSdk 36 / JVM 17）。
        minSdk = 31
        targetSdk = 36
        versionCode = 1
        versionName = "0.2.0"

        // 開発者モード用プレースホルダ（公式ガイド: 開発者モードでは両方 "0"）。
        // 本番では Wearables Developer Center で発行される値を local.properties 等から入れる。
        manifestPlaceholders["mwdat_application_id"] = "0"
        manifestPlaceholders["mwdat_client_token"] = "0"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlin {
        compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
    }
}

dependencies {
    // Meta Wearables Device Access Toolkit 1.0.1（Maven Central, com.meta.wearable）
    implementation(libs.mwdat.core)
    implementation(libs.mwdat.camera)
    implementation(libs.mwdat.display)
    implementation(libs.mwdat.mockdevice)

    implementation(libs.kotlinx.coroutines.android)

    testImplementation(libs.junit)
}
